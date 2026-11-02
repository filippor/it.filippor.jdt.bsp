package it.filippor.jdt.bsp.core.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
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

final class GradleApiClasspathAugmenter {

    private static final String CORE_PLUGIN_ID = "it.filippor.jdt.bsp.core";
    private static final Pattern GRADLE_VERSIONED_JAR = Pattern.compile(
        "gradle-(?:tooling-api|api|core-api|core)-([0-9][^/\\\\]*)\\.jar$");
    private static final Pattern GRADLE_WRAPPER_DISTRIBUTION = Pattern.compile(
        "gradle-([0-9][0-9A-Za-z+_.-]*)-(?:bin|all)\\.zip$");

    private GradleApiClasspathAugmenter() {
    }

    static List<IClasspathEntry> addGradleApiEntriesIfNeeded(String sourceSetId, List<IClasspathEntry> classpath) {
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
        boolean hasGradleCoreApi = containsLibraryFileName(classpath,
            fileName -> fileName.matches("gradle-core-api-[0-9].*\\.jar"));
        boolean hasGradleCore = containsLibraryFileName(classpath,
            fileName -> fileName.matches("gradle-core-[0-9].*\\.jar"));
        logInfo("Gradle enrichment start for " + sourceSetId + ": pluginMarker=" + gradlePluginModule
            + ", toolingApi=" + hasToolingApi + ", hasApi=" + hasGradleApi + ", hasCoreApi=" + hasGradleCoreApi
            + ", hasCore=" + hasGradleCore);
        if (hasGradleApi && hasGradleCoreApi && hasGradleCore) {
            return classpath;
        }

        LinkedHashSet<IClasspathEntry> merged = new LinkedHashSet<>(classpath);
        List<String> versionCandidates = resolveGradleVersionCandidates(moduleRoot, classpath);
        logInfo("Gradle enrichment versions for " + sourceSetId + ": " + versionCandidates);
        for (String version : versionCandidates) {
            String expectedCoreApi = "gradle-core-api-" + version + ".jar";
            String expectedCore = "gradle-core-" + version + ".jar";
            if (!hasGradleApi) {
                Path gradleApiJar = findGradleApiJar(version);
                if (gradleApiJar != null) {
                    merged.add(BspWorkspaceModelResolver.toLibraryClasspathEntry(gradleApiJar));
                    hasGradleApi = true;
                    logInfo("Added gradle-api from version " + version + ": " + gradleApiJar);
                }
            }
            for (Path wrapperJar : findWrapperGradleCoreJars(version)) {
                String fileName = wrapperJar.getFileName() == null ? "" : wrapperJar.getFileName()
                                                                                    .toString();
                if ((expectedCoreApi.equals(fileName) && !hasGradleCoreApi)
                    || (expectedCore.equals(fileName) && !hasGradleCore)) {
                    merged.add(BspWorkspaceModelResolver.toLibraryClasspathEntry(wrapperJar));
                    if (expectedCoreApi.equals(fileName)) {
                        hasGradleCoreApi = true;
                        logInfo("Added gradle-core-api from version " + version + ": " + wrapperJar);
                    } else if (expectedCore.equals(fileName)) {
                        hasGradleCore = true;
                        logInfo("Added gradle-core from version " + version + ": " + wrapperJar);
                    }
                }
            }
            if (hasGradleApi && hasGradleCoreApi && hasGradleCore) {
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
        for (IClasspathEntry entry : classpath) {
            if (entry == null || entry.getEntryKind() != IClasspathEntry.CPE_LIBRARY || entry.getPath() == null) {
                continue;
            }
            Path libraryPath = Path.of(entry.getPath()
                                            .toOSString());
            if (libraryPath.getFileName() == null) {
                continue;
            }
            Matcher matcher = GRADLE_VERSIONED_JAR.matcher(libraryPath.getFileName()
                                                                      .toString());
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

    private static List<Path> findWrapperGradleCoreJars(String gradleVersion) {
        Path wrapperDists = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists");
        if (!Files.isDirectory(wrapperDists)) {
            return Collections.emptyList();
        }
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(wrapperDists, 8)) {
            walk.filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName()
                                      .toString();
                    return name.equals("gradle-core-api-" + gradleVersion + ".jar")
                        || name.equals("gradle-core-" + gradleVersion + ".jar");
                })
                .forEach(jars::add);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        return jars;
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
