package it.filippor.jdt.bsp.ui;

import java.io.IOException;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.Bundle;

import it.filippor.jdt.bsp.core.BspConnectionConfig;
import it.filippor.jdt.bsp.core.BspProcessClient;
import it.filippor.jdt.bsp.core.BspProjectSettingsStore;
import it.filippor.jdt.bsp.core.BspRootProjectNature;
import it.filippor.jdt.bsp.core.model.BspBuildTargetActionModel;
import it.filippor.jdt.bsp.core.model.BspSourceSetModel;
import it.filippor.jdt.bsp.core.model.BspWorkspaceProjectSynchronizer;

public final class BspClientManager {

	private static final long STARTUP_TIMEOUT_SECONDS = 120;
	private static final String MODE_WRAPPER = "wrapper";
	private static final String MODE_SYSTEM = "system";
	private static final String MODE_PATH = "path";
	private static final String MODE_CUSTOM = "custom";
	// Kept for backward compatibility with already persisted preferences.
	private static final String MODE_EMBEDDED = "embedded";
	private static final String BSP_CORE_BUNDLE_ID = "it.filippor.jdt.bsp.core";
	private static final String BSP_JAVA_HOME_PROPERTY = "it.filippor.jdt.bsp.java.home";
	private static final String BSP_JAVA_COMMAND_PROPERTY = "it.filippor.jdt.bsp.java.command";
	private static final String BSP_JAVA_HOME_ENV = "JDT_BSP_JAVA_HOME";

	private static final BspClientManager INSTANCE = new BspClientManager();

	private final Map<Path, BspProcessClient> clients = new ConcurrentHashMap<>();
	private final BspWorkspaceProjectSynchronizer synchronizer = new BspWorkspaceProjectSynchronizer();

	private BspClientManager() {
		// Singleton
	}

	public static BspClientManager getInstance() {
		return INSTANCE;
	}

	public void startFromPreferences() {
		Job.create("Start BSP client", this::runStartJob).schedule();
	}

	public void restartFromPreferences() {
		stop();
		startFromPreferences();
	}

	private IStatus runStartJob(IProgressMonitor monitor) {
		try {
			List<BspConnectionConfig> configs = buildConfigsFromPreferences();
			List<IStatus> failures = new ArrayList<>();
			List<RootStartProbe> probes = new ArrayList<>();
			int started = 0;
			for (BspConnectionConfig config : configs) {
				BspProcessClient existingClient = this.clients.remove(config.workingDirectory());
				if (existingClient != null) {
					existingClient.stop();
					BspClientPlugin.logInfo("Restarting BSP client for root refresh: " + config.workingDirectory());
				}
				BspProcessClient bspClient = new BspProcessClient();
				String rootLabel = config.workingDirectory().toString();
				String commandLineForRoot = String.join(" ", config.command());
				RootStartProbe probe = new RootStartProbe(rootLabel);
				probes.add(probe);
				try {
					IProject rootProject = this.synchronizer.ensureRootProject(rootLabel, commandLineForRoot, monitor);
					BspClientPlugin.logInfo("BSP root project ensured for: " + rootLabel);
					startClient(bspClient, config, rootLabel,
							message -> logInfo(rootLabel, "BSP[" + rootLabel + "] <- " + summarize(message)),
							line -> {
								logError(rootLabel, "BSP[" + rootLabel + "] ! " + line, null);
								if (line != null && line.contains("BSP initialization failed")) {
									probe.failureMessage = line;
									probe.latch.countDown();
								}
							},
							models -> {
								probe.sourceSetsResolved = true;
								logSourceSets(rootLabel, String.join(" ", config.command()), models);
								probe.latch.countDown();
							});
					this.clients.put(config.workingDirectory(), bspClient);
					started++;
					BspClientPlugin.logInfo("BSP client started for root: " + rootLabel
							+ " using command: " + commandLineForRoot);
				} catch (Throwable e) {
					String command = commandLineForRoot;
					String detail = "Failed to start BSP client for root '" + rootLabel
							+ "' with command '" + command + "'.";
					if (e.getMessage() != null && e.getMessage().contains("CreateProcess error=2")) {
						detail += " Executable not found. Configure a valid command (for example 'gradle bsp' if Gradle BSP is installed,"
								+ " or on Windows wrapper projects use 'cmd /c gradlew.bat bsp').";
					} else if (e instanceof NoClassDefFoundError) {
						detail += " Missing runtime dependency: " + e.getMessage()
								+ ". Ensure the runtime target includes BSP dependencies before starting the client.";
					}
					IStatus failure = new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID, detail, e);
					failures.add(failure);
					BspClientPlugin.logError(detail, e);
				}
			}
			if (started == 0 && !failures.isEmpty()) {
				return failures.get(0);
			}
			if (this.clients.isEmpty()) {
				return new Status(IStatus.WARNING, BspClientPlugin.PLUGIN_ID,
						"No valid BSP roots were configured");
			}
			if (!probes.isEmpty()) {
				int readyCount = 0;
				List<String> timeoutRoots = new ArrayList<>();
				List<String> failedRoots = new ArrayList<>();
				for (RootStartProbe probe : probes) {
					boolean signaled;
					try {
						signaled = probe.latch.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						signaled = false;
					}
					if (!signaled) {
						timeoutRoots.add(probe.rootLabel);
					} else if (probe.sourceSetsResolved) {
						readyCount++;
					} else {
						failedRoots.add(probe.rootLabel + (probe.failureMessage == null ? "" : " - " + probe.failureMessage));
					}
				}
				if (readyCount == 0 && (!timeoutRoots.isEmpty() || !failedRoots.isEmpty())) {
					String details = "BSP started but initialization did not complete.";
					if (!timeoutRoots.isEmpty()) {
						details += " Timed out roots: " + String.join(", ", timeoutRoots) + ".";
					}
					if (!failedRoots.isEmpty()) {
						details += " Failed roots: " + String.join(" | ", failedRoots) + ".";
					}
					return new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID, details);
				}
				if (readyCount < probes.size()) {
					return new Status(IStatus.WARNING, BspClientPlugin.PLUGIN_ID,
							"Some BSP roots are not fully initialized; see Error Log for details.");
				}
			}
			if (!failures.isEmpty()) {
				return new Status(IStatus.WARNING, BspClientPlugin.PLUGIN_ID,
						"Some BSP roots failed to start; see Error Log for details.");
			}
			return Status.OK_STATUS;
		} catch (CoreException e) {
			BspClientPlugin.logError("Failed to start BSP client", e);
			return new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID, "Failed to start BSP client", e);
		}
	}

	private void startClient(BspProcessClient bspClient, BspConnectionConfig config, String rootLabel,
			java.util.function.Consumer<String> onInfo,
			java.util.function.Consumer<String> onStderr,
			java.util.function.Consumer<List<BspSourceSetModel>> onSourceSets) throws IOException {
		try {
			bspClient.start(config, onInfo, onStderr, onSourceSets);
		} catch (IOException e) {
			BspConnectionConfig wrapperConfig = tryWrapperFallback(config, e);
			if (wrapperConfig == null) {
				throw e;
			}
			BspClientPlugin.logInfo("BSP[" + rootLabel + "] retry with Gradle wrapper command");
			bspClient.start(wrapperConfig, onInfo, onStderr, onSourceSets);
		}
	}

	private BspConnectionConfig tryWrapperFallback(BspConnectionConfig config, IOException failure) {
		if (failure.getMessage() == null || !failure.getMessage().contains("CreateProcess error=2")) {
			return null;
		}
		if (config.command().isEmpty() || !"gradle".equals(config.command().get(0))) {
			return null;
		}
		Path wrapper = config.workingDirectory().resolve("gradlew.bat");
		if (!Files.isRegularFile(wrapper)) {
			return null;
		}
		return new BspConnectionConfig(List.of("cmd", "/c", "gradlew.bat", "bsp"), config.workingDirectory());
	}

	private static String summarize(String json) {
		String compact = json.replace('\n', ' ').replace('\r', ' ').trim();
		if (compact.length() > 200) {
			return compact.substring(0, 200) + "...";
		}
		return compact;
	}

	private void logSourceSets(String rootLabel, String commandLine, List<BspSourceSetModel> models) {
		BspClientPlugin.logInfo("BSP[" + rootLabel + "] build targets resolved: " + models.size());
		for (BspSourceSetModel model : models) {
			BspClientPlugin.logInfo("BSP[" + rootLabel + "] build target id: " + model.sourceSetId());
		}
		Job.create("Synchronize BSP build targets", monitor -> {
			try {
				this.synchronizer.synchronize(rootLabel, commandLine, models, monitor);
				return Status.OK_STATUS;
			} catch (Exception e) {
				BspClientPlugin.logError("Failed to synchronize BSP build targets", e);
				return new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID, "Failed to synchronize BSP build targets", e);
			}
		}).schedule();
	}

	private List<BspConnectionConfig> buildConfigsFromPreferences() throws CoreException {
		String commandLine = readPreference(BspPreferenceInitializer.PREF_COMMAND_LINE,
				BspPreferenceInitializer.DEFAULT_COMMAND_LINE);
		String mode = readPreference(BspPreferenceInitializer.PREF_COMMAND_MODE, MODE_SYSTEM);
		String executablePath = readPreference(BspPreferenceInitializer.PREF_GRADLE_EXECUTABLE_PATH, "");

		List<BspConnectionConfig> configsFromNature = buildConfigsFromBspRootProjects();
		if (!configsFromNature.isEmpty()) {
			return configsFromNature;
		}

		List<String> command = buildCommand(mode, commandLine, executablePath);
		Map<String, Object> initializeData = buildInitializeData(mode, executablePath);
		if (command.isEmpty()) {
			throw new CoreException(new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID,
					"BSP command line preference resolved to empty command"));
		}

		String rootsValue = readPreference(BspPreferenceInitializer.PREF_ROOT_DIRECTORIES, "");
		Path rootPath;
		if (rootsValue == null || rootsValue.isBlank()) {
			rootPath = Path.of(ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString());
		} else {
			List<Path> roots = Arrays.stream(rootsValue.split("[;\\r\\n]+"))
					.map(String::trim)
					.filter(s -> !s.isBlank())
					.map(Path::of)
					.filter(Files::isDirectory)
					.distinct()
					.collect(Collectors.toList());
			rootPath = roots.isEmpty() ? Path.of(ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString())
					: roots.get(0);
		}
		return List.of(new BspConnectionConfig(command, rootPath, initializeData));
	}

	private List<BspConnectionConfig> buildConfigsFromBspRootProjects() {
		List<BspConnectionConfig> configs = new ArrayList<>();
		String mode = readPreference(BspPreferenceInitializer.PREF_COMMAND_MODE, MODE_SYSTEM);
		String executablePath = readPreference(BspPreferenceInitializer.PREF_GRADLE_EXECUTABLE_PATH, "");
		String defaultCommandLine = readPreference(BspPreferenceInitializer.PREF_COMMAND_LINE,
				BspPreferenceInitializer.DEFAULT_COMMAND_LINE);
		List<String> defaultCommand;
		Map<String, Object> defaultInitializeData;
		try {
			defaultCommand = buildCommand(mode, defaultCommandLine, executablePath);
			defaultInitializeData = buildInitializeData(mode, executablePath);
		} catch (CoreException e) {
			BspClientPlugin.logError("Failed to resolve BSP command preferences", e);
			return configs;
		}
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			try {
				if (!project.exists() || !project.isOpen()) {
					continue;
				}
				if (!project.hasNature(BspRootProjectNature.NATURE_ID)) {
					continue;
				}
				if (project.getLocation() == null) {
					continue;
				}
				Path rootPath = Path.of(project.getLocation().toOSString());
				if (!Files.isDirectory(rootPath)) {
					continue;
				}
				String commandLine = BspProjectSettingsStore.readCommandLine(project, defaultCommandLine);
				boolean customMode = MODE_CUSTOM.equals(mode);
				List<String> command = customMode ? parseCommandLine(commandLine) : defaultCommand;
				Map<String, Object> initializeData = customMode ? Map.of() : defaultInitializeData;
				if (!command.isEmpty()) {
					configs.add(new BspConnectionConfig(command, rootPath, initializeData));
				}
			} catch (Exception e) {
				BspClientPlugin.logError("Failed to read BSP root project settings for " + project.getName(), e);
			}
		}
		return configs;
	}

	private List<String> buildCommand(String mode, String customCommandLine, String executablePath) throws CoreException {
		String resolvedMode = mode == null || mode.isBlank() ? MODE_SYSTEM : mode;
		return switch (resolvedMode) {
			case MODE_WRAPPER -> buildEmbeddedServerCommand();
			case MODE_SYSTEM -> buildEmbeddedServerCommand();
			case MODE_PATH -> {
				if (executablePath == null || executablePath.isBlank()) {
					throw new CoreException(new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID,
							"Gradle executable path is empty in path mode"));
				}
				yield buildEmbeddedServerCommand();
			}
			case MODE_EMBEDDED -> buildEmbeddedServerCommand();
			case MODE_CUSTOM -> parseCommandLine(customCommandLine);
			default -> parseCommandLine(customCommandLine);
		};
	}

	private Map<String, Object> buildInitializeData(String mode, String executablePath) throws CoreException {
		String resolvedMode = mode == null || mode.isBlank() ? MODE_SYSTEM : mode;
		return switch (resolvedMode) {
			case MODE_WRAPPER -> Map.of("isWrapperEnabled", Boolean.TRUE);
			case MODE_SYSTEM -> Map.of("isWrapperEnabled", Boolean.FALSE);
			case MODE_PATH -> {
				if (executablePath == null || executablePath.isBlank()) {
					throw new CoreException(new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID,
							"Gradle executable path is empty in path mode"));
				}
				Map<String, Object> data = new LinkedHashMap<>();
				data.put("isWrapperEnabled", Boolean.FALSE);
				data.put("gradleHome", resolveGradleHome(executablePath));
				yield data;
			}
			case MODE_CUSTOM -> Map.of();
			case MODE_EMBEDDED -> Map.of();
			default -> Map.of();
		};
	}

	private static String resolveGradleHome(String executablePath) {
		Path path = Path.of(executablePath.trim());
		if (Files.isDirectory(path)) {
			return path.toString();
		}
		Path parent = path.getParent();
		if (parent == null) {
			return executablePath.trim();
		}
		Path binParent = parent.getFileName();
		if (binParent != null && "bin".equalsIgnoreCase(binParent.toString()) && parent.getParent() != null) {
			return parent.getParent().toString();
		}
		return parent.toString();
	}
	public List<BspActionableTaskItem> listActionableTasks() {
		List<BspActionableTaskItem> tasks = new ArrayList<>();
		for (Map.Entry<Path, BspProcessClient> entry : new ArrayList<>(this.clients.entrySet())) {
			String root = entry.getKey().toString();
			for (BspBuildTargetActionModel model : entry.getValue().listBuildTargetActions()) {
				String targetName = model.displayName() == null || model.displayName().isBlank()
						? model.buildTargetId()
						: model.displayName();
				String targetId = model.buildTargetId() == null ? "" : model.buildTargetId();
				tasks.add(new BspActionableTaskItem(root, targetName, targetId, "compile", model.canPackage()));
				tasks.add(new BspActionableTaskItem(root, targetName, targetId, "run", model.canRun()));
				tasks.add(new BspActionableTaskItem(root, targetName, targetId, "test", model.canTest()));
			}
		}
		tasks.sort(Comparator
				.comparing(BspActionableTaskItem::root, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(BspActionableTaskItem::buildTarget, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(BspActionableTaskItem::task, String.CASE_INSENSITIVE_ORDER));
		return tasks;
	}

	public IStatus executeTask(String root, String buildTargetId, String task, boolean debug) {
		return executeTask(root, buildTargetId, task, debug, null);
	}

	public IStatus executeTask(String root, String buildTargetId, String task, boolean debug,
			IProgressMonitor monitor) {
		if (root == null || root.isBlank()) {
			return new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID, "Missing root for BSP task execution");
		}
		if (buildTargetId == null || buildTargetId.isBlank()) {
			return new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID, "Missing build target id for BSP task execution");
		}
		if (task == null || task.isBlank()) {
			return new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID, "Missing task name for BSP task execution");
		}
		BspProcessClient client = this.clients.get(Path.of(root));
		if (client == null) {
			return new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID,
					"No BSP client found for root: " + root + ". Start BSP before running tasks.");
		}
		try {
			logInfo(root, "BSP executing " + (debug ? "debug" : "run") + " task '" + task + "' on " + buildTargetId);
			String result = client.executeBuildTargetTask(buildTargetId, task, debug,
					() -> monitor != null && monitor.isCanceled());
			String mode = debug ? "debug" : "run";
			String message = "BSP " + mode + " task '" + task + "' completed for target " + buildTargetId
					+ " (" + result + ")";
			logInfo(root, message);
			return new Status(IStatus.OK, BspClientPlugin.PLUGIN_ID, message);
		} catch (CancellationException e) {
			String message = "BSP task '" + task + "' cancelled for target " + buildTargetId;
			logInfo(root, message);
			return new Status(IStatus.CANCEL, BspClientPlugin.PLUGIN_ID, message, e);
		} catch (Exception e) {
			String mode = debug ? "debug" : "run";
			String message = "BSP " + mode + " task '" + task + "' failed for target " + buildTargetId;
			logError(root, message, e);
			return new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID, message + ": " + e.getMessage(), e);
		}
	}

	private static void logInfo(String rootLabel, String message) {
		BspClientPlugin.logInfo(message);
		BspConsole.write(rootLabel, message, false);
	}

	private static void logError(String rootLabel, String message, Throwable error) {
		if (error == null) {
			BspClientPlugin.logInfo(message);
			BspConsole.write(rootLabel, message, true);
			return;
		}
		BspClientPlugin.logError(message, error);
		BspConsole.write(rootLabel, message + "\n" + error, true);
	}

	private List<String> buildEmbeddedServerCommand() throws CoreException {
		Path serverJar = resolveBundledPath("lib/gradle-bsp/server.jar");
		Path runtimeDir = resolveBundledPath("lib/gradle-bsp/runtime");
		Path pluginDir = resolveBundledPath("lib/gradle-bsp/plugins");
		String runtimeWildcard = runtimeDir.toString() + File.separator + "*";
		String classpath = serverJar + File.pathSeparator + runtimeWildcard;
		return List.of(resolveJavaCommand(), "-Dplugin.dir=" + pluginDir, "-cp", classpath,
				"com.microsoft.java.bs.core.Launcher");
	}

	private static Path resolveBundledPath(String relativePath) throws CoreException {
		Bundle bundle = Platform.getBundle(BSP_CORE_BUNDLE_ID);
		if (bundle == null) {
			throw new CoreException(new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID,
					"Missing required bundle: " + BSP_CORE_BUNDLE_ID));
		}
		URL entry = bundle.getEntry(relativePath);
		if (entry == null) {
			throw new CoreException(new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID,
					"Missing bundled BSP artifact: " + relativePath));
		}
		try {
			return Path.of(FileLocator.toFileURL(entry).toURI());
		} catch (Exception e) {
			throw new CoreException(new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID,
					"Failed to resolve bundled BSP artifact: " + relativePath, e));
		}
	}

	private static String resolveJavaCommand() {
		String explicitCommand = System.getProperty(BSP_JAVA_COMMAND_PROPERTY);
		if (explicitCommand != null && !explicitCommand.isBlank()) {
			return explicitCommand.trim();
		}

		Path fromBspJavaHomeProperty = resolveJavaFromHome(System.getProperty(BSP_JAVA_HOME_PROPERTY));
		if (fromBspJavaHomeProperty != null) {
			return fromBspJavaHomeProperty.toString();
		}

		Path fromBspJavaHomeEnv = resolveJavaFromHome(System.getenv(BSP_JAVA_HOME_ENV));
		if (fromBspJavaHomeEnv != null) {
			return fromBspJavaHomeEnv.toString();
		}

		Path fromJavaHomeEnv = resolveJavaFromHome(System.getenv("JAVA_HOME"));
		if (fromJavaHomeEnv != null) {
			return fromJavaHomeEnv.toString();
		}

		String javaHome = System.getProperty("java.home");
		Path fromJavaHomeProperty = resolveJavaFromHome(javaHome);
		if (fromJavaHomeProperty != null) {
			return fromJavaHomeProperty.toString();
		}
		return "java";
	}

	private static Path resolveJavaFromHome(String javaHome) {
		if (javaHome == null || javaHome.isBlank()) {
			return null;
		}
		Path candidate = Path.of(javaHome.trim(), "bin", isWindowsHost() ? "java.exe" : "java");
		return Files.isRegularFile(candidate) ? candidate : null;
	}

	private static boolean isWindowsHost() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private static String readPreference(String key, String defaultValue) {
		return Platform.getPreferencesService().getString(BspClientPlugin.PLUGIN_ID, key, defaultValue, null);
	}
	private static List<String> parseCommandLine(String commandLine) {
		if (commandLine == null || commandLine.isBlank()) {
			commandLine = BspPreferenceInitializer.DEFAULT_COMMAND_LINE;
		}
		List<String> tokens = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inQuotes = false;
		for (int i = 0; i < commandLine.length(); i++) {
			char c = commandLine.charAt(i);
			if (c == '"') {
				inQuotes = !inQuotes;
				continue;
			}
			if (Character.isWhitespace(c) && !inQuotes) {
				if (current.length() > 0) {
					tokens.add(current.toString());
					current.setLength(0);
				}
				continue;
			}
			current.append(c);
		}
		if (current.length() > 0) {
			tokens.add(current.toString());
		}
		return tokens;
	}

	public void stop() {
		for (Map.Entry<Path, BspProcessClient> entry : this.clients.entrySet()) {
			entry.getValue().stop();
			BspClientPlugin.logInfo("BSP client stopped for root: " + entry.getKey());
		}
		this.clients.clear();
	}

	private static class RootStartProbe {
		private final String rootLabel;
		private final CountDownLatch latch = new CountDownLatch(1);
		private volatile boolean sourceSetsResolved;
		private volatile String failureMessage;

		private RootStartProbe(String rootLabel) {
			this.rootLabel = rootLabel;
		}
	}
}
