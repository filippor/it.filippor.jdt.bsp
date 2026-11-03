package it.filippor.jdt.bsp.core.model;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IClasspathEntry;

public final class GradleApiClasspathAugmenter {

    private static final String CORE_PLUGIN_ID = "it.filippor.jdt.bsp.core";
    private static final Pattern GRADLE_VERSIONED_JAR = Pattern.compile(
        "gradle-(?:tooling-api|api)-([0-9][^/\\\\]*)\\.jar$");
    private static final Pattern GRADLE_TOOLING_API_JAR = Pattern.compile("gradle-tooling-api-([0-9][^/\\\\]*)\\.jar$");
    private static final Pattern GRADLE_WRAPPER_DISTRIBUTION = Pattern.compile(
        "gradle-([0-9][0-9A-Za-z+_.-]*)-(?:bin|all)\\.zip$");
    private static final String DEPENDENCY_PREFIX = "BSP_DEP=";
    private static final Map<String, List<Path>> TOOLING_TRANSITIVE_CACHE = new ConcurrentHashMap<>();

    private GradleApiClasspathAugmenter() {
    }

    public static List<IClasspathEntry> addGradleApiEntriesIfNeeded(String sourceSetId, List<IClasspathEntry> classpath) {
        Path moduleRoot = BspWorkspaceModelResolver.toLocalPathIfPossible(sourceSetId);
        boolean gradlePluginModule = usesJavaGradlePlugin(moduleRoot);
        boolean hasToolingApi = containsLibraryFileName(classpath,
            fileName -> fileName.startsWith("gradle-tooling-api-") && fileName.endsWith(".jar"));
        if (!gradlePluginModule && !hasToolingApi) {
            return classpath;
        }

        boolean hasGradleApi = containsLibraryFileName(classpath,
            fileName -> fileName.matches("gradle-api-[0-9].*\\.jar")
                && !fileName.startsWith("gradle-api-metadata-"));
        logInfo("Gradle enrichment start for " + sourceSetId + ": pluginMarker=" + gradlePluginModule
            + ", toolingApi=" + hasToolingApi + ", hasApi=" + hasGradleApi);
        if (hasGradleApi && !hasToolingApi) {
            return classpath;
        }

        LinkedHashSet<IClasspathEntry> merged = new LinkedHashSet<>(classpath);
        List<String> versionCandidates = resolveGradleVersionCandidates(moduleRoot, classpath);
        logInfo("Gradle enrichment versions for " + sourceSetId + ": " + versionCandidates);
        if (hasToolingApi) {
            Optional<String> toolingVersion = detectToolingApiVersionFromClasspath(classpath);
            String resolutionVersion = toolingVersion.orElseGet(() -> versionCandidates.isEmpty() ? null : versionCandidates.get(0));
            if (resolutionVersion != null && !resolutionVersion.isBlank()) {
                List<Path> transitive = resolveToolingApiTransitiveDependencies(moduleRoot, resolutionVersion);
                for (Path jar : transitive) {
                    IClasspathEntry entry = BspWorkspaceModelResolver.toLibraryClasspathEntry(jar);
                    if (entry != null) {
                        merged.add(entry);
                    }
                }
                logInfo("Gradle enrichment transitive dependencies for " + sourceSetId + " ("
                    + resolutionVersion + "): " + transitive.size());
            } else {
                logInfo("Gradle enrichment skipped transitive resolution for " + sourceSetId
                    + ": unable to detect tooling-api version.");
            }
        }

        for (String version : versionCandidates) {
            if (!hasGradleApi) {
                Path gradleApiJar = findGradleApiJar(version);
                if (gradleApiJar != null) {
                    merged.add(BspWorkspaceModelResolver.toLibraryClasspathEntry(gradleApiJar));
                    hasGradleApi = true;
                    logInfo("Added gradle-api from version " + version + ": " + gradleApiJar);
                }
            }
            if (hasGradleApi) {
                break;
            }
        }

        if (!hasGradleApi) {
            Path fallbackGradleApi = findAnyGeneratedGradleApiJar();
            if (fallbackGradleApi != null) {
                merged.add(BspWorkspaceModelResolver.toLibraryClasspathEntry(fallbackGradleApi));
                logInfo("Added fallback gradle-api jar: " + fallbackGradleApi);
            } else {
                logInfo("Unable to add gradle-api for " + sourceSetId + ": no matching jar found.");
            }
        }
        return new ArrayList<>(merged);
    }

    private static List<String> resolveGradleVersionCandidates(Path moduleRoot, List<IClasspathEntry> classpath) {
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        detectGradleVersionFromWrapper(moduleRoot).ifPresent(versions::add);
        detectGradleVersionFromClasspath(classpath).ifPresent(versions::add);
        return new ArrayList<>(versions);
    }

    private static boolean containsLibraryFileName(Iterable<IClasspathEntry> classpathEntries,
                                                   Predicate<String> fileNamePredicate) {
        if (classpathEntries == null || fileNamePredicate == null) {
            return false;
        }
        for (IClasspathEntry entry : classpathEntries) {
            String fileName = toLibraryFileName(entry);
            if (fileName != null && fileNamePredicate.test(fileName)) {
                return true;
            }
        }
        return false;
    }

    private static String toLibraryFileName(IClasspathEntry entry) {
        if (entry == null || entry.getEntryKind() != IClasspathEntry.CPE_LIBRARY || entry.getPath() == null) {
            return null;
        }
        try {
            Path path = Path.of(entry.getPath()
                                     .toOSString());
            return path.getFileName() == null ? null : path.getFileName()
                                                           .toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean usesJavaGradlePlugin(Path moduleRoot) {
        if (moduleRoot == null) {
            return false;
        }
        for (String buildFile : List.of("build.gradle", "build.gradle.kts")) {
            Path candidate = moduleRoot.resolve(buildFile);
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                String content = Files.readString(candidate);
                if (content.contains("java-gradle-plugin")) {
                    return true;
                }
            } catch (Exception e) {
                // Best effort only.
            }
        }
        return false;
    }

    private static Optional<String> detectGradleVersionFromClasspath(List<IClasspathEntry> classpath) {
        if (classpath == null) {
            return Optional.empty();
        }
        for (IClasspathEntry entry : classpath) {
            String fileName = toLibraryFileName(entry);
            if (fileName == null) {
                continue;
            }
            Matcher matcher = GRADLE_VERSIONED_JAR.matcher(fileName);
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> detectToolingApiVersionFromClasspath(List<IClasspathEntry> classpath) {
        if (classpath == null) {
            return Optional.empty();
        }
        for (IClasspathEntry entry : classpath) {
            String fileName = toLibraryFileName(entry);
            if (fileName == null) {
                continue;
            }
            Matcher matcher = GRADLE_TOOLING_API_JAR.matcher(fileName);
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> detectGradleVersionFromWrapper(Path moduleRoot) {
        if (moduleRoot == null) {
            return Optional.empty();
        }
        Path wrapperProps = findNearestGradleWrapperProperties(moduleRoot);
        if (wrapperProps == null) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(wrapperProps)) {
            properties.load(in);
        } catch (Exception e) {
            return Optional.empty();
        }
        String distributionUrl = properties.getProperty("distributionUrl");
        if (distributionUrl == null || distributionUrl.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = GRADLE_WRAPPER_DISTRIBUTION.matcher(distributionUrl.trim());
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private static Path findNearestGradleWrapperProperties(Path start) {
        Path current = start.toAbsolutePath()
                            .normalize();
        while (current != null) {
            Path candidate = current.resolve("gradle")
                                    .resolve("wrapper")
                                    .resolve("gradle-wrapper.properties");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

    private static Path findGradleApiJar(String gradleVersion) {
        Path gradleHome = Path.of(System.getProperty("user.home"), ".gradle");
        Path direct = gradleHome.resolve("caches")
                                .resolve(gradleVersion)
                                .resolve("generated-gradle-jars")
                                .resolve("gradle-api-" + gradleVersion + ".jar");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path generated = gradleHome.resolve("caches")
                                   .resolve("generated-gradle-jars");
        if (!Files.isDirectory(generated)) {
            return null;
        }
        String expected = "gradle-api-" + gradleVersion + ".jar";
        try (Stream<Path> walk = Files.walk(generated, 4)) {
            return walk.filter(Files::isRegularFile)
                       .filter(path -> expected.equals(path.getFileName()
                                                           .toString()))
                       .findFirst()
                       .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Path findAnyGeneratedGradleApiJar() {
        Path generatedRoot = Path.of(System.getProperty("user.home"), ".gradle", "caches", "generated-gradle-jars");
        if (!Files.isDirectory(generatedRoot)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(generatedRoot, 6)) {
            return walk.filter(Files::isRegularFile)
                       .filter(path -> {
                           String fileName = path.getFileName() == null ? "" : path.getFileName()
                                                                                   .toString();
                           return fileName.startsWith("gradle-api-") && fileName.endsWith(".jar");
                       })
                       .max((left, right) -> left.toString()
                                                 .compareToIgnoreCase(right.toString()))
                       .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Path> resolveToolingApiTransitiveDependencies(Path moduleRoot, String gradleVersion) {
        if (gradleVersion == null || gradleVersion.isBlank()) {
            return Collections.emptyList();
        }
        String key = (moduleRoot == null ? "" : moduleRoot.toAbsolutePath()
                                                      .normalize()
                                                      .toString()) + "#" + gradleVersion;
        List<Path> cached = TOOLING_TRANSITIVE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Path projectRoot = findNearestGradleProjectRoot(moduleRoot);
        if (projectRoot == null) {
            return Collections.emptyList();
        }
        List<Path> resolved = resolveWithGradleCommand(projectRoot, gradleVersion);
        TOOLING_TRANSITIVE_CACHE.put(key, resolved);
        return resolved;
    }

    private static Path findNearestGradleProjectRoot(Path start) {
        if (start == null) {
            return null;
        }
        Path current = start.toAbsolutePath()
                            .normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                || Files.isRegularFile(current.resolve("settings.gradle.kts"))
                || Files.isRegularFile(current.resolve("build.gradle"))
                || Files.isRegularFile(current.resolve("build.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static List<Path> resolveWithGradleCommand(Path projectRoot, String gradleVersion) {
        Path initScript = null;
        try {
            initScript = Files.createTempFile("bsp-gradle-tooling-api", ".gradle");
            // The init script prints transitive jars and also triggers source artifact resolution.
            Files.writeString(initScript, gradleInitScript(), StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);

            ProcessBuilder processBuilder = new ProcessBuilder(buildGradleCommand(projectRoot, initScript, gradleVersion));
            processBuilder.directory(projectRoot.toFile());
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            List<Path> resolved = new ArrayList<>();
            try (InputStream inputStream = process.getInputStream();
					Stream<String> lines = new java.io.BufferedReader(
                new java.io.InputStreamReader(inputStream)).lines()) {
                lines.filter(line -> line != null && line.startsWith(DEPENDENCY_PREFIX))
                     .map(line -> line.substring(DEPENDENCY_PREFIX.length()))
                     .map(String::trim)
                     .filter(path -> !path.isBlank())
                     .map(Path::of)
                     .filter(Files::isRegularFile)
                     .forEach(resolved::add);
            }

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                logInfo("Gradle transitive resolution failed for " + gradleVersion + " at " + projectRoot);
                return Collections.emptyList();
            }
            return resolved;
        } catch (Exception e) {
            logInfo("Gradle transitive resolution error for " + gradleVersion + ": " + e.getMessage());
            return Collections.emptyList();
        } finally {
            if (initScript != null) {
                try {
                    Files.deleteIfExists(initScript);
                } catch (Exception ignored) {
                    // Best effort only.
                }
            }
        }
    }

    private static List<String> buildGradleCommand(Path projectRoot, Path initScript, String gradleVersion) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                                    .contains("win");
        Path wrapper = windows ? projectRoot.resolve("gradlew.bat") : projectRoot.resolve("gradlew");
        if (windows) {
            List<String> command = new ArrayList<>();
            command.add("cmd.exe");
            command.add("/c");
            command.add(Files.isRegularFile(wrapper) ? wrapper.toString() : "gradle");
            command.add("-q");
            command.add("-I");
            command.add(initScript.toString());
            command.add("bspResolveToolingApi");
            command.add("-PbspToolingApiVersion=" + gradleVersion);
            return command;
        }
        List<String> command = new ArrayList<>();
        command.add(Files.isRegularFile(wrapper) ? wrapper.toString() : "gradle");
        command.add("-q");
        command.add("-I");
        command.add(initScript.toString());
        command.add("bspResolveToolingApi");
        command.add("-PbspToolingApiVersion=" + gradleVersion);
        return command;
    }

    private static String gradleInitScript() {
        return """
allprojects {
    tasks.register("bspResolveToolingApi") {
        doLast {
            def version = gradle.startParameter.projectProperties['bspToolingApiVersion']
            if (!version) {
                return
            }
            def tooling = dependencies.create("org.gradle:gradle-tooling-api:${version}")
            def runtime = configurations.detachedConfiguration(tooling)
            runtime.transitive = true
            def binaries = runtime.resolve()
            binaries.each { file ->
                println("BSP_DEP=" + file.absolutePath)
            }

            def sourceDependencies = runtime.resolvedConfiguration.resolvedArtifacts.collect { artifact ->
                dependencies.create("${artifact.moduleVersion.id.group}:${artifact.name}:${artifact.moduleVersion.id.version}:sources")
            }
            if (!sourceDependencies.isEmpty()) {
                def sourceConfig = configurations.detachedConfiguration(sourceDependencies as Dependency[])
                sourceConfig.transitive = false
                sourceConfig.incoming.artifactView { view ->
                    view.lenient = true
                }.files.files
            }
        }
    }
}
""";
    }

    private static void logInfo(String message) {
        Bundle bundle = FrameworkUtil.getBundle(GradleApiClasspathAugmenter.class);
        if (bundle == null) {
            return;
        }
        Platform.getLog(bundle)
                .log(new Status(IStatus.INFO, CORE_PLUGIN_ID, message));
    }
}
