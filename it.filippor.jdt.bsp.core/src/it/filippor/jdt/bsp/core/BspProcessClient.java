package it.filippor.jdt.bsp.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import ch.epfl.scala.bsp4j.BuildClient;
import ch.epfl.scala.bsp4j.BuildServer;
import ch.epfl.scala.bsp4j.DidChangeBuildTarget;
import ch.epfl.scala.bsp4j.InitializeBuildResult;
import ch.epfl.scala.bsp4j.LogMessageParams;
import ch.epfl.scala.bsp4j.PrintParams;
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams;
import ch.epfl.scala.bsp4j.ShowMessageParams;
import ch.epfl.scala.bsp4j.TaskFinishParams;
import ch.epfl.scala.bsp4j.TaskProgressParams;
import ch.epfl.scala.bsp4j.TaskStartParams;
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult;
import it.filippor.jdt.bsp.core.model.BspSourceSetModel;
import it.filippor.jdt.bsp.core.model.BspWorkspaceModelResolver;
import it.filippor.jdt.bsp.core.protocol.Bsp4jSupport;
import it.filippor.jdt.bsp.core.protocol.BspExtendedBuildServer;

public class BspProcessClient {

	private static final long INIT_TIMEOUT_SECONDS = 90;
	private static final long WORKSPACE_TARGETS_TIMEOUT_SECONDS = 90;
	private static final long TARGETS_POLL_SECONDS = 15;
	private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

	private ExecutorService ioPool = createIoPool();

	private Process process;
	private BuildServer server;
	private Future<Void> listening;
	private final BspWorkspaceModelResolver modelResolver = new BspWorkspaceModelResolver();
	private volatile boolean protocolEnabled;

	public synchronized void start(BspConnectionConfig config, Consumer<String> onInfo, Consumer<String> onStderr,
			Consumer<List<BspSourceSetModel>> onSourceSets) throws IOException {
		validateConfig(config);
		ensureIoPool();
		ProcessBuilder builder = new ProcessBuilder(config.command());
		builder.directory(config.workingDirectory().toFile());
		try {
			this.process = builder.start();
		} catch (IOException e) {
			onStderr.accept("Failed to start BSP process in '" + config.workingDirectory() + "' with command '"
					+ String.join(" ", config.command()) + "': " + e.getMessage());
			throw e;
		}
		submitIoTask(() -> readStderr(this.process, onStderr), onStderr);
		try {
			Class<? extends BuildServer> remoteInterface = resolveRemoteInterface();
			onInfo.accept("BSP remote interface: " + remoteInterface.getName());
			Launcher<BuildServer> launcher = new Launcher.Builder<BuildServer>()
					.setInput(this.process.getInputStream())
					.setOutput(this.process.getOutputStream())
					.setLocalService(createBuildClient(onInfo, onStderr))
					.setRemoteInterface(remoteInterface)
					.setExecutorService(this.ioPool)
					.create();
			this.server = launcher.getRemoteProxy();
			this.listening = launcher.startListening();
			this.protocolEnabled = true;
			onInfo.accept("BSP protocol bootstrap enabled");
			submitIoTask(() -> initializeSession(config, onInfo, onStderr, onSourceSets), onStderr);
		} catch (LinkageError e) {
			this.protocolEnabled = false;
			onStderr.accept("BSP JSON-RPC runtime dependency missing/incompatible (" + e.getClass().getSimpleName() + ": "
					+ e.getMessage() + "). Running process-only mode; BSP protocol handshake is disabled.");
			submitIoTask(() -> drainStdout(this.process), onStderr);
		}
	}

	public void start(BspConnectionConfig config, Consumer<String> onInfo, Consumer<String> onStderr) throws IOException {
		start(config, onInfo, onStderr, models -> {
			// no-op
		});
	}

	private void initializeSession(BspConnectionConfig config, Consumer<String> onInfo, Consumer<String> onStderr,
			Consumer<List<BspSourceSetModel>> onSourceSets) {
		onInfo.accept("BSP initialize thread entered for root: " + config.workingDirectory());
		try {
			onInfo.accept("BSP initialize request starting for root: " + config.workingDirectory());
			InitializeBuildResult init = this.server
					.buildInitialize(Bsp4jSupport.createInitializeParams(config.workingDirectory().toUri(), "Eclipse IDE", "0.1.0",
							config.initializeData()))
					.get(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			onInfo.accept(init == null ? "BSP initialized" : "BSP initialized successfully");

			this.server.onBuildInitialized();
			onInfo.accept("BSP build initialized notification sent");

			onInfo.accept("BSP requesting workspace build targets...");
			WorkspaceBuildTargetsResult targets = awaitWorkspaceBuildTargets(onInfo);
			int targetCount = targets == null || targets.getTargets() == null ? 0 : targets.getTargets().size();
			onInfo.accept("BSP targets discovered: " + targetCount);

			List<BspSourceSetModel> models = this.modelResolver.fromWorkspaceTargets(this.server, targets);
			onInfo.accept("BSP source set models resolved: " + models.size());
			onSourceSets.accept(models);
			onInfo.accept("BSP initialize thread completed for root: " + config.workingDirectory());
		} catch (Throwable e) {
			Throwable rootCause = unwrap(e);
			onStderr.accept("BSP initialization failed (" + rootCause.getClass().getSimpleName() + "): " + rootCause.getMessage());
			if (rootCause instanceof java.util.concurrent.TimeoutException) {
				onStderr.accept("BSP initialize timed out. Check Gradle/JDK compatibility for the BSP server (for Gradle 8.7 prefer JDK 21), or increase BSP timeouts.");
			}
			onStderr.accept("BSP initialization stacktrace:\n" + toStackTrace(rootCause));
			if (this.process != null && !this.process.isAlive()) {
				onStderr.accept("BSP process exited before initialization completed (exit code: " + this.process.exitValue() + ").");
			}
			onStderr.accept("BSP initialize thread aborted for root: " + config.workingDirectory());
		}
	}

	private WorkspaceBuildTargetsResult awaitWorkspaceBuildTargets(Consumer<String> onInfo) throws Exception {
		Future<WorkspaceBuildTargetsResult> targetsFuture = this.server.workspaceBuildTargets();
		long startNanos = System.nanoTime();
		long deadlineNanos = startNanos + TimeUnit.SECONDS.toNanos(WORKSPACE_TARGETS_TIMEOUT_SECONDS);
		while (true) {
			long remainingNanos = deadlineNanos - System.nanoTime();
			if (remainingNanos <= 0) {
				long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
				throw new TimeoutException("workspaceBuildTargets did not complete within "
						+ WORKSPACE_TARGETS_TIMEOUT_SECONDS + "s (elapsed " + elapsed + "s)");
			}
			long waitSeconds = Math.min(TARGETS_POLL_SECONDS,
					Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remainingNanos)));
			try {
				return targetsFuture.get(waitSeconds, TimeUnit.SECONDS);
			} catch (TimeoutException e) {
				long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
				onInfo.accept("BSP still resolving workspace targets... elapsed " + elapsed + "s");
			}
		}
	}

	private static void readStderr(Process process, Consumer<String> onStderr) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				onStderr.accept(line);
			}
		} catch (IOException e) {
			onStderr.accept("Failed reading BSP stderr: " + e.getMessage());
		}
	}

	private static void drainStdout(Process process) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			while (reader.readLine() != null) {
				// Ignore output in process-only fallback mode.
			}
		} catch (IOException e) {
			// Best effort drain only.
		}
	}

	public synchronized void stop() {
		Process current = this.process;
		this.process = null;
		BuildServer currentServer = this.server;
		this.server = null;
		Future<Void> currentListening = this.listening;
		this.listening = null;
		if (this.protocolEnabled && currentServer != null) {
			try {
				currentServer.buildShutdown().get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				currentServer.onBuildExit();
			} catch (Exception e) {
				// Best effort shutdown.
			}
		}
		this.protocolEnabled = false;
		if (currentListening != null) {
			currentListening.cancel(true);
		}
		if (current != null) {
			current.destroy();
		}
		if (this.ioPool != null) {
			this.ioPool.shutdownNow();
		}
	}

	private static ExecutorService createIoPool() {
		return Executors.newFixedThreadPool(3, runnable -> {
			Thread thread = new Thread(runnable, "BSP-IO");
			thread.setDaemon(true);
			return thread;
		});
	}

	@SuppressWarnings("unchecked")
	private static Class<? extends BuildServer> resolveRemoteInterface() {
		if (BuildServer.class.isAssignableFrom(BspExtendedBuildServer.class)) {
			return (Class<? extends BuildServer>) BspExtendedBuildServer.class;
		}
		for (String candidate : List.of(
				"ch.epfl.scala.bsp4j.JavaBuildServer",
				"ch.epfl.scala.bsp4j.JvmBuildServer")) {
			try {
				Class<?> loaded = Class.forName(candidate);
				if (BuildServer.class.isAssignableFrom(loaded)) {
					return (Class<? extends BuildServer>) loaded;
				}
			} catch (ClassNotFoundException e) {
				// Try next candidate.
			}
		}
		return BuildServer.class;
	}

	private synchronized void ensureIoPool() {
		if (this.ioPool == null || this.ioPool.isShutdown()) {
			this.ioPool = createIoPool();
		}
	}

	private static void validateConfig(BspConnectionConfig config) {
		if (config.command() == null || config.command().isEmpty()) {
			throw new IllegalArgumentException("BSP command is empty");
		}
		Path workingDirectory = config.workingDirectory();
		if (workingDirectory == null || !Files.isDirectory(workingDirectory)) {
			throw new IllegalArgumentException("BSP working directory does not exist: " + workingDirectory);
		}
	}

	private void submitIoTask(Runnable task, Consumer<String> onStderr) {
		try {
			this.ioPool.submit(task);
		} catch (RejectedExecutionException e) {
			onStderr.accept("Failed to schedule BSP I/O task: " + e.getMessage());
		}
	}

	private static BuildClient createBuildClient(Consumer<String> onInfo, Consumer<String> onStderr) {
		return new BuildClient() {
			@Override
			public void onBuildShowMessage(ShowMessageParams params) {
				if (params != null && params.getMessage() != null) {
					onInfo.accept("BSP server message: " + params.getMessage());
				}
			}

			@Override
			public void onBuildLogMessage(LogMessageParams params) {
				if (params != null && params.getMessage() != null) {
					onInfo.accept("BSP server log: " + params.getMessage());
				}
			}

			@Override
			public void onBuildPublishDiagnostics(PublishDiagnosticsParams params) {
				// Diagnostics are consumed from build targets and source-set sync, no-op here.
			}

			@Override
			public void onBuildTargetDidChange(DidChangeBuildTarget params) {
				// No-op for now.
			}

			@Override
			public void onBuildTaskStart(TaskStartParams params) {
				onInfo.accept("BSP task started" + summarizeTask(params));
			}

			@Override
			public void onBuildTaskProgress(TaskProgressParams params) {
				onInfo.accept("BSP task progress" + summarizeTask(params));
			}

			@Override
			public void onBuildTaskFinish(TaskFinishParams params) {
				onInfo.accept("BSP task finished" + summarizeTask(params));
			}

			@Override
			public void onRunPrintStdout(PrintParams params) {
				if (params != null && params.getMessage() != null) {
					onInfo.accept("BSP stdout: " + params.getMessage());
				}
			}

			@Override
			public void onRunPrintStderr(PrintParams params) {
				if (params != null && params.getMessage() != null) {
					onStderr.accept("BSP stderr: " + params.getMessage());
				}
			}
		};
	}

	private static String summarizeTask(Object taskParams) {
		if (taskParams == null) {
			return "";
		}
		String message = invokeStringGetter(taskParams, "getMessage");
		String dataKind = invokeStringGetter(taskParams, "getDataKind");
		String eventTime = invokeStringGetter(taskParams, "getEventTime");
		StringBuilder summary = new StringBuilder();
		if (message != null && !message.isBlank()) {
			summary.append(": ").append(message);
		}
		if (dataKind != null && !dataKind.isBlank()) {
			summary.append(" [kind=").append(dataKind).append(']');
		}
		if (eventTime != null && !eventTime.isBlank()) {
			summary.append(" [time=").append(eventTime).append(']');
		}
		return summary.toString();
	}

	private static String invokeStringGetter(Object target, String methodName) {
		try {
			Object value = target.getClass().getMethod(methodName).invoke(target);
			return value == null ? null : String.valueOf(value);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private static Throwable unwrap(Throwable e) {
		if (e instanceof ExecutionException && e.getCause() != null) {
			return e.getCause();
		}
		return e;
	}

	private static String toStackTrace(Throwable t) {
		StringWriter writer = new StringWriter();
		t.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}
}
