package org.eclipse.jdt.bsp.client;

import java.io.IOException;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.bsp.core.BspConnectionConfig;
import org.eclipse.jdt.bsp.core.BspProcessClient;
import org.eclipse.jdt.bsp.core.model.BspSourceSetModel;
import org.eclipse.jdt.bsp.core.model.BspWorkspaceProjectSynchronizer;
import org.osgi.framework.Bundle;
import org.osgi.service.prefs.Preferences;

public final class BspClientManager {

	private static final long STARTUP_TIMEOUT_SECONDS = 120;
	private static final String MODE_WRAPPER = "wrapper";
	private static final String MODE_SYSTEM = "system";
	private static final String MODE_PATH = "path";
	private static final String MODE_CUSTOM = "custom";
	private static final String MODE_EMBEDDED = "embedded";
	private static final String BSP_CORE_BUNDLE_ID = "org.eclipse.jdt.bsp.core";
	private static final String BSP_JAVA_HOME_PROPERTY = "org.eclipse.jdt.bsp.java.home";
	private static final String BSP_JAVA_COMMAND_PROPERTY = "org.eclipse.jdt.bsp.java.command";
	private static final String BSP_JAVA_HOME_ENV = "JDT_BSP_JAVA_HOME";
	private static final int MAX_NATURE_RETRY_ATTEMPTS = 10;

	private static final BspClientManager INSTANCE = new BspClientManager();

	private final Map<Path, BspProcessClient> clients = new ConcurrentHashMap<>();
	private final Map<String, Integer> pendingNatureRetries = new ConcurrentHashMap<>();
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
					ensureBspRootNature(rootProject, monitor);
					BspClientPlugin.logInfo("BSP root project ensured for: " + rootLabel);
					startClient(bspClient, config, rootLabel,
							message -> BspClientPlugin.logInfo("BSP[" + rootLabel + "] <- " + summarize(message)),
							line -> {
								BspClientPlugin.logInfo("BSP[" + rootLabel + "] ! " + line);
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

	private void ensureBspRootNature(IProject project, IProgressMonitor monitor) {
		try {
			if (ResourcesPlugin.getWorkspace().getNatureDescriptor(BspRootProjectNature.NATURE_ID) == null) {
				scheduleNatureRetry(project);
				return;
			}
			this.pendingNatureRetries.remove(project.getName());
			if (project.hasNature(BspRootProjectNature.NATURE_ID)) {
				return;
			}
			IProjectDescription description = project.getDescription();
			String[] natures = description.getNatureIds();
			List<String> updated = new ArrayList<>(natures.length + 1);
			updated.add(BspRootProjectNature.NATURE_ID);
			updated.addAll(Arrays.asList(natures));
			description.setNatureIds(updated.toArray(String[]::new));
			project.setDescription(description, monitor);
		} catch (CoreException e) {
			BspClientPlugin.logError("Failed to apply BSP root nature to project: " + project.getName(), e);
		}
	}

	private void scheduleNatureRetry(IProject project) {
		int attempt = this.pendingNatureRetries.merge(project.getName(), 1, Integer::sum);
		if (attempt > MAX_NATURE_RETRY_ATTEMPTS) {
			this.pendingNatureRetries.remove(project.getName());
			BspClientPlugin.logInfo("BSP root nature is still unavailable after retries for project: " + project.getName());
			return;
		}
		long delayMillis = Math.min(5_000L, 500L * attempt);
		Job.create("Retry BSP root nature", monitor -> {
			IProject retryProject = ResourcesPlugin.getWorkspace().getRoot().getProject(project.getName());
			if (!retryProject.exists() || !retryProject.isOpen()) {
				this.pendingNatureRetries.remove(project.getName());
				return Status.CANCEL_STATUS;
			}
			ensureBspRootNature(retryProject, monitor);
			return Status.OK_STATUS;
		}).schedule(delayMillis);
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
		BspClientPlugin.logInfo("BSP[" + rootLabel + "] source sets resolved: " + models.size());
		for (BspSourceSetModel model : models) {
			BspClientPlugin.logInfo("BSP[" + rootLabel + "] source set id: " + model.sourceSetId());
		}
		Job.create("Synchronize BSP source sets", monitor -> {
			try {
				this.synchronizer.synchronize(rootLabel, commandLine, models, monitor);
				return Status.OK_STATUS;
			} catch (Exception e) {
				BspClientPlugin.logError("Failed to synchronize BSP source sets", e);
				return new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID, "Failed to synchronize BSP source sets", e);
			}
		}).schedule();
	}

	private List<BspConnectionConfig> buildConfigsFromPreferences() throws CoreException {
		List<BspConnectionConfig> configsFromNature = buildConfigsFromBspRootProjects();
		if (!configsFromNature.isEmpty()) {
			return configsFromNature;
		}

			String commandLine = Platform.getPreferencesService().getString(BspClientPlugin.PLUGIN_ID,
					BspPreferenceInitializer.PREF_COMMAND_LINE, BspPreferenceInitializer.DEFAULT_COMMAND_LINE, null);
			String mode = Platform.getPreferencesService().getString(BspClientPlugin.PLUGIN_ID,
					BspPreferenceInitializer.PREF_COMMAND_MODE, MODE_SYSTEM, null);
			String executablePath = Platform.getPreferencesService().getString(BspClientPlugin.PLUGIN_ID,
					BspPreferenceInitializer.PREF_GRADLE_EXECUTABLE_PATH, "", null);
			List<String> command = buildCommand(mode, commandLine, executablePath);
		if (command.isEmpty()) {
			throw new CoreException(new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID,
					"BSP command line preference resolved to empty command"));
		}

		String rootsValue = Platform.getPreferencesService().getString(BspClientPlugin.PLUGIN_ID,
				BspPreferenceInitializer.PREF_ROOT_DIRECTORIES, "", null);
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
		return List.of(new BspConnectionConfig(command, rootPath));
	}

	private List<BspConnectionConfig> buildConfigsFromBspRootProjects() {
		List<BspConnectionConfig> configs = new ArrayList<>();
		String defaultCommandLine = Platform.getPreferencesService().getString(BspClientPlugin.PLUGIN_ID,
				BspPreferenceInitializer.PREF_COMMAND_LINE, BspPreferenceInitializer.DEFAULT_COMMAND_LINE, null);
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			try {
				if (!project.exists() || !project.isOpen() || !project.hasNature(BspRootProjectNature.NATURE_ID)) {
					continue;
				}
				Preferences node = new ProjectScope(project).getNode(BspRootProjectNature.SETTINGS_NODE);
				String rootFolder = node.get(BspRootProjectNature.KEY_BSP_ROOT_FOLDER, "");
				if (rootFolder == null || rootFolder.isBlank()) {
					continue;
				}
				Path rootPath = Path.of(rootFolder);
				if (!Files.isDirectory(rootPath)) {
					continue;
				}
				String commandLine = node.get(BspRootProjectNature.KEY_BSP_COMMAND_LINE, defaultCommandLine);
				List<String> command = parseCommandLine(commandLine);
				if (!command.isEmpty()) {
					configs.add(new BspConnectionConfig(command, rootPath));
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
			case MODE_WRAPPER -> isWindowsHost() ? List.of("cmd", "/c", "gradlew.bat", "bsp") : List.of("./gradlew", "bsp");
			case MODE_SYSTEM -> List.of("gradle", "bsp");
			case MODE_PATH -> {
				if (executablePath == null || executablePath.isBlank()) {
					throw new CoreException(new Status(IStatus.ERROR, BspClientPlugin.PLUGIN_ID,
							"Gradle executable path is empty in path mode"));
				}
				yield List.of(executablePath.trim(), "bsp");
			}
			case MODE_EMBEDDED -> buildEmbeddedServerCommand();
			case MODE_CUSTOM -> parseCommandLine(customCommandLine);
			default -> parseCommandLine(customCommandLine);
		};
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