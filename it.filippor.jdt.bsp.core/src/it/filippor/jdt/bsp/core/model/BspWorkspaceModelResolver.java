package it.filippor.jdt.bsp.core.model;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import ch.epfl.scala.bsp4j.BuildTarget;
import ch.epfl.scala.bsp4j.BuildTargetIdentifier;
import ch.epfl.scala.bsp4j.BuildServer;
import ch.epfl.scala.bsp4j.DependencySourcesItem;
import ch.epfl.scala.bsp4j.DependencySourcesParams;
import ch.epfl.scala.bsp4j.DependencySourcesResult;
import ch.epfl.scala.bsp4j.SourceItem;
import ch.epfl.scala.bsp4j.SourcesItem;
import ch.epfl.scala.bsp4j.SourcesParams;
import ch.epfl.scala.bsp4j.SourcesResult;
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult;
import it.filippor.jdt.bsp.core.protocol.BspExtendedBuildServer;
import it.filippor.jdt.bsp.core.protocol.BspTargetParams;

public class BspWorkspaceModelResolver {

	private static final long TARGET_DETAIL_TIMEOUT_SECONDS = 60;
	private static final Pattern GRADLE_TOOLING_API_VERSION = Pattern.compile("gradle-tooling-api-([0-9][^/\\\\]*)\\\\.jar$");

	public List<BspSourceSetModel> fromWorkspaceTargets(BuildServer server, WorkspaceBuildTargetsResult targetsResult) {
		if (targetsResult == null || targetsResult.getTargets() == null) {
			return List.of();
		}
		List<BspSourceSetModel> models = new ArrayList<>(targetsResult.getTargets().size());
		for (BuildTarget target : targetsResult.getTargets()) {
			BuildTargetIdentifier id = target.getId();
			if (id == null || id.getUri() == null) {
				continue;
			}
			String sourceSetId = id.getUri();
			List<Path> sources = resolveSources(server, id, sourceSetId);
			List<Path> classpath = resolveClasspath(server, id);
			classpath = enrichGradlePluginClasspath(sourceSetId, classpath);
			String javaSourceLevel = resolveJavaSourceLevel(server, id);
			List<String> dependencies = toDependencyIds(target.getDependencies());
			Path outputFolder = toLocalPathIfPossible(sourceSetId);
			models.add(new BspSourceSetModel(sourceSetId, sources, classpath, dependencies, outputFolder, javaSourceLevel));
		}
		return models;
	}

	private String resolveJavaSourceLevel(BuildServer server, BuildTargetIdentifier id) {
		Object javacResult = invokeExtendedRequest(server, id, "buildTargetJavacOptions");
		if (javacResult == null) {
			return null;
		}
		List<List<String>> optionSets = extractJavacOptionSets(javacResult);
		for (List<String> options : optionSets) {
			String level = parseSourceLevelFromOptions(options);
			if (level != null) {
				return level;
			}
		}
		return null;
	}

	private static List<Path> enrichGradlePluginClasspath(String sourceSetId, List<Path> classpath) {
		Path moduleRoot = toLocalPathIfPossible(sourceSetId);
		if (!usesJavaGradlePlugin(moduleRoot)) {
			return classpath;
		}
		LinkedHashSet<Path> merged = new LinkedHashSet<>(classpath);
		Optional<String> gradleVersion = detectGradleVersionFromClasspath(classpath);
		if (gradleVersion.isPresent()) {
			Path gradleApiJar = findGradleApiJar(gradleVersion.get());
			if (gradleApiJar != null) {
				merged.add(gradleApiJar);
			}
			for (Path wrapperJar : findWrapperGradleCoreJars(gradleVersion.get())) {
				merged.add(wrapperJar);
			}
		}
		return new ArrayList<>(merged);
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

	private static Optional<String> detectGradleVersionFromClasspath(List<Path> classpath) {
		for (Path entry : classpath) {
			if (entry == null || entry.getFileName() == null) {
				continue;
			}
			Matcher matcher = GRADLE_TOOLING_API_VERSION.matcher(entry.getFileName().toString());
			if (matcher.find()) {
				return Optional.of(matcher.group(1));
			}
		}
		return Optional.empty();
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
		Path generated = gradleHome.resolve("caches").resolve("generated-gradle-jars");
		if (!Files.isDirectory(generated)) {
			return null;
		}
		String expected = "gradle-api-" + gradleVersion + ".jar";
		try (Stream<Path> walk = Files.walk(generated, 4)) {
			return walk.filter(Files::isRegularFile)
					.filter(path -> expected.equals(path.getFileName().toString()))
					.findFirst()
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
						String name = path.getFileName().toString();
						return name.equals("gradle-core-api-" + gradleVersion + ".jar")
								|| name.equals("gradle-core-" + gradleVersion + ".jar");
					})
					.forEach(jars::add);
		} catch (Exception e) {
			return Collections.emptyList();
		}
		return jars;
	}

	private List<Path> resolveClasspath(BuildServer server, BuildTargetIdentifier id) {
		LinkedHashSet<Path> resolved = new LinkedHashSet<>();
		resolved.addAll(resolveJavacOptionsClasspath(server, id));
		resolved.addAll(resolveCompileClasspath(server, id));
		resolved.addAll(resolveDependencySources(server, id));
		return new ArrayList<>(resolved);
	}

	private List<Path> resolveJavacOptionsClasspath(BuildServer server, BuildTargetIdentifier id) {
		return invokeExtendedClasspathRequest(server, id, "buildTargetJavacOptions");
	}

	private static List<String> toDependencyIds(List<BuildTargetIdentifier> dependencies) {
		if (dependencies == null || dependencies.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> ids = new ArrayList<>(dependencies.size());
		for (BuildTargetIdentifier dependency : dependencies) {
			if (dependency != null && dependency.getUri() != null && !dependency.getUri().isBlank()) {
				ids.add(dependency.getUri());
			}
		}
		return ids;
	}

	private List<Path> resolveSources(BuildServer server, BuildTargetIdentifier id, String sourceSetId) {
		if (server == null) {
			return resolveConventionalSources(sourceSetId);
		}
		try {
			SourcesResult result = server.buildTargetSources(new SourcesParams(List.of(id)))
					.get(TARGET_DETAIL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (result == null || result.getItems() == null || result.getItems().isEmpty()) {
				return resolveConventionalSources(sourceSetId);
			}
			LinkedHashSet<Path> resolved = new LinkedHashSet<>();
			for (SourcesItem item : result.getItems()) {
				if (item == null || item.getSources() == null) {
					continue;
				}
				for (SourceItem source : item.getSources()) {
					if (source == null || source.getUri() == null) {
						continue;
					}
					Path path = normalizeSourcePath(toLocalPathIfPossible(source.getUri()));
					if (path != null) {
						resolved.add(path);
					}
				}
			}
			if (resolved.isEmpty()) {
				return resolveConventionalSources(sourceSetId);
			}
			return new ArrayList<>(resolved);
		} catch (Exception e) {
			return resolveConventionalSources(sourceSetId);
		}
	}

	private List<Path> resolveDependencySources(BuildServer server, BuildTargetIdentifier id) {
		if (server == null) {
			return Collections.emptyList();
		}
		try {
			DependencySourcesResult result = server.buildTargetDependencySources(new DependencySourcesParams(List.of(id)))
					.get(TARGET_DETAIL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (result == null || result.getItems() == null || result.getItems().isEmpty()) {
				return Collections.emptyList();
			}
			LinkedHashSet<Path> resolved = new LinkedHashSet<>();
			for (DependencySourcesItem item : result.getItems()) {
				if (item == null || item.getSources() == null) {
					continue;
				}
				for (String sourceUri : item.getSources()) {
					Path path = toClasspathPath(toLocalPathIfPossible(sourceUri));
					if (path != null) {
						resolved.add(path);
					}
				}
			}
			return new ArrayList<>(resolved);
		} catch (Exception e) {
			return Collections.emptyList();
		}
	}

	private List<Path> resolveCompileClasspath(BuildServer server, BuildTargetIdentifier id) {
		if (server == null || id == null) {
			return Collections.emptyList();
		}
		List<Path> fromJvmCompileClasspath = invokeExtendedClasspathRequest(server, id, "buildTargetJvmCompileClasspath");
		if (!fromJvmCompileClasspath.isEmpty()) {
			return fromJvmCompileClasspath;
		}
		return invokeExtendedClasspathRequest(server, id, "buildTargetCompileClasspath");
	}

	private List<Path> invokeExtendedClasspathRequest(BuildServer server, BuildTargetIdentifier id, String methodName) {
		Object result = invokeExtendedRequest(server, id, methodName);
		if (result == null) {
			return Collections.emptyList();
		}
		return extractClasspathEntries(result);
	}

	private Object invokeExtendedRequest(BuildServer server, BuildTargetIdentifier id, String methodName) {
		if (!(server instanceof BspExtendedBuildServer extendedServer)) {
			return null;
		}
		try {
			Object futureResult = extendedServer.getClass().getMethod(methodName, BspTargetParams.class)
					.invoke(extendedServer, new BspTargetParams(List.of(id)));
			if (!(futureResult instanceof Future<?> future)) {
				return null;
			}
			return future.get(TARGET_DETAIL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (NoSuchMethodException e) {
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	private static List<List<String>> extractJavacOptionSets(Object result) {
		if (result == null) {
			return Collections.emptyList();
		}
		List<?> items = extractItems(result);
		if (items.isEmpty()) {
			return Collections.emptyList();
		}
		List<List<String>> optionSets = new ArrayList<>();
		for (Object item : items) {
			List<String> options;
			if (item instanceof Map<?, ?> map) {
				options = toStringList(map.get("options"));
			} else {
				options = invokeStringListGetter(item, "getOptions");
			}
			if (!options.isEmpty()) {
				optionSets.add(options);
			}
		}
		return optionSets;
	}

	private static List<?> extractItems(Object result) {
		if (result instanceof Map<?, ?> mapResult) {
			Object itemsObj = mapResult.get("items");
			return itemsObj instanceof List<?> items ? items : Collections.emptyList();
		}
		try {
			Object itemsObj = result.getClass().getMethod("getItems").invoke(result);
			return itemsObj instanceof List<?> items ? items : Collections.emptyList();
		} catch (Exception e) {
			return Collections.emptyList();
		}
	}

	private static String parseSourceLevelFromOptions(List<String> options) {
		if (options == null || options.isEmpty()) {
			return null;
		}
		for (int i = 0; i < options.size(); i++) {
			String option = options.get(i);
			if (option == null || option.isBlank()) {
				continue;
			}
			if (option.startsWith("--release=")) {
				return normalizeSourceLevel(option.substring("--release=".length()));
			}
			if (option.startsWith("-source=")) {
				return normalizeSourceLevel(option.substring("-source=".length()));
			}
			if ("--release".equals(option) || "-source".equals(option)) {
				if (i + 1 < options.size()) {
					return normalizeSourceLevel(options.get(i + 1));
				}
			}
		}
		return null;
	}

	private static String normalizeSourceLevel(String rawLevel) {
		if (rawLevel == null) {
			return null;
		}
		String level = rawLevel.trim();
		if (level.isEmpty()) {
			return null;
		}
		if ("8".equals(level)) {
			return "1.8";
		}
		return level;
	}

	private static List<Path> extractClasspathEntries(Object result) {
		if (result == null) {
			return Collections.emptyList();
		}
		if (result instanceof Map<?, ?> mapResult) {
			return extractClasspathEntriesFromMap(mapResult);
		}
		try {
			Object itemsObj = result.getClass().getMethod("getItems").invoke(result);
			if (!(itemsObj instanceof List<?> items) || items.isEmpty()) {
				return Collections.emptyList();
			}
			LinkedHashSet<Path> resolved = new LinkedHashSet<>();
			for (Object item : items) {
				for (String uri : extractUrisFromClasspathItem(item)) {
					Path path = toClasspathPath(toLocalPathIfPossible(uri));
					if (path != null) {
						resolved.add(path);
					}
				}
			}
			return new ArrayList<>(resolved);
		} catch (Exception e) {
			return Collections.emptyList();
		}
	}

	private static List<Path> extractClasspathEntriesFromMap(Map<?, ?> mapResult) {
		Object itemsObj = mapResult.get("items");
		if (!(itemsObj instanceof List<?> items) || items.isEmpty()) {
			return Collections.emptyList();
		}
		LinkedHashSet<Path> resolved = new LinkedHashSet<>();
		for (Object item : items) {
			for (String uri : extractUrisFromClasspathItem(item)) {
				Path path = toClasspathPath(toLocalPathIfPossible(uri));
				if (path != null) {
					resolved.add(path);
				}
			}
		}
		return new ArrayList<>(resolved);
	}

	private static List<String> extractUrisFromClasspathItem(Object item) {
		if (item == null) {
			return Collections.emptyList();
		}
		if (item instanceof Map<?, ?> itemMap) {
			return extractUrisFromClasspathItemMap(itemMap);
		}
		List<String> uris = invokeStringListGetter(item, "getClasspath");
		if (!uris.isEmpty()) {
			return uris;
		}
		uris = invokeStringListGetter(item, "getClasspaths");
		if (!uris.isEmpty()) {
			return uris;
		}
		uris = invokeStringListGetter(item, "getUris");
		if (!uris.isEmpty()) {
			return uris;
		}
		return extractClasspathFromJavacOptions(item);
	}

	private static List<String> extractUrisFromClasspathItemMap(Map<?, ?> itemMap) {
		List<String> uris = toStringList(itemMap.get("classpath"));
		if (!uris.isEmpty()) {
			return uris;
		}
		uris = toStringList(itemMap.get("classpaths"));
		if (!uris.isEmpty()) {
			return uris;
		}
		uris = toStringList(itemMap.get("uris"));
		if (!uris.isEmpty()) {
			return uris;
		}
		return extractClasspathFromJavacOptionsMap(itemMap);
	}

	private static List<String> extractClasspathFromJavacOptionsMap(Map<?, ?> itemMap) {
		List<String> options = toStringList(itemMap.get("options"));
		if (options.isEmpty()) {
			return Collections.emptyList();
		}
		return extractPathsFromJavacOptions(options);
	}

	private static List<String> extractClasspathFromJavacOptions(Object item) {
		List<String> options = invokeStringListGetter(item, "getOptions");
		if (options.isEmpty()) {
			return Collections.emptyList();
		}
		return extractPathsFromJavacOptions(options);
	}

	private static List<String> extractPathsFromJavacOptions(List<String> options) {
		List<String> entries = new ArrayList<>();
		for (int i = 0; i < options.size(); i++) {
			String option = options.get(i);
			if ("-classpath".equals(option) || "--classpath".equals(option) || "-cp".equals(option)
					|| "-processorpath".equals(option) || "--processor-path".equals(option)) {
				if (i + 1 < options.size()) {
					entries.addAll(splitClasspathEntries(options.get(i + 1)));
				}
				continue;
			}
			if (option != null && option.startsWith("--processor-path=")) {
				entries.addAll(splitClasspathEntries(option.substring("--processor-path=".length())));
				continue;
			}
			if (option != null && option.startsWith("-processorpath=")) {
				entries.addAll(splitClasspathEntries(option.substring("-processorpath=".length())));
			}
		}
		return entries;
	}

	private static List<String> splitClasspathEntries(String classpathValue) {
		if (classpathValue == null || classpathValue.isBlank()) {
			return Collections.emptyList();
		}
		String separator = java.io.File.pathSeparator;
		String[] raw = classpathValue.split(java.util.regex.Pattern.quote(separator));
		List<String> entries = new ArrayList<>(raw.length);
		for (String token : raw) {
			if (token != null && !token.isBlank()) {
				entries.add(token.trim());
			}
		}
		return entries;
	}

	private static List<String> invokeStringListGetter(Object target, String getterName) {
		try {
			Object value = target.getClass().getMethod(getterName).invoke(target);
			if (!(value instanceof List<?> rawValues) || rawValues.isEmpty()) {
				return Collections.emptyList();
			}
			List<String> converted = new ArrayList<>(rawValues.size());
			for (Object rawValue : rawValues) {
				if (rawValue != null) {
					converted.add(String.valueOf(rawValue));
				}
			}
			return converted;
		} catch (Exception e) {
			return Collections.emptyList();
		}
	}

	private static List<String> toStringList(Object value) {
		if (!(value instanceof List<?> rawValues) || rawValues.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> converted = new ArrayList<>(rawValues.size());
		for (Object rawValue : rawValues) {
			if (rawValue != null) {
				converted.add(String.valueOf(rawValue));
			}
		}
		return converted;
	}

	private static Path normalizeSourcePath(Path path) {
		if (path == null) {
			return null;
		}
		if (!Files.exists(path)) {
			return path;
		}
		if (Files.isDirectory(path)) {
			return path;
		}
		Path parent = path.getParent();
		return parent == null ? null : parent;
	}

	private static Path toClasspathPath(Path path) {
		if (path == null) {
			return null;
		}
		Path binaryCandidate = toBinaryJarIfPossible(path);
		if (binaryCandidate != null && Files.isRegularFile(binaryCandidate)) {
			return binaryCandidate;
		}
		Path fromGradleCache = findBinaryInGradleVersionFolder(path);
		if (fromGradleCache != null) {
			return fromGradleCache;
		}
		if (!Files.exists(path)) {
			return path;
		}
		return path;
	}

	private static Path toBinaryJarIfPossible(Path path) {
		String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
		if (fileName.endsWith("-sources.jar")) {
			String binaryName = fileName.substring(0, fileName.length() - "-sources.jar".length()) + ".jar";
			return path.resolveSibling(binaryName);
		}
		if (fileName.endsWith("-javadoc.jar")) {
			String binaryName = fileName.substring(0, fileName.length() - "-javadoc.jar".length()) + ".jar";
			return path.resolveSibling(binaryName);
		}
		return null;
	}

	private static Path findBinaryInGradleVersionFolder(Path path) {
		String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
		String maybeBinaryName = null;
		if (fileName.endsWith("-sources.jar")) {
			maybeBinaryName = fileName.substring(0, fileName.length() - "-sources.jar".length()) + ".jar";
		} else if (fileName.endsWith("-javadoc.jar")) {
			maybeBinaryName = fileName.substring(0, fileName.length() - "-javadoc.jar".length()) + ".jar";
		}
		if (maybeBinaryName == null) {
			return null;
		}
		final String binaryName = maybeBinaryName;
		Path hashFolder = path.getParent();
		Path versionFolder = hashFolder == null ? null : hashFolder.getParent();
		if (versionFolder == null || !Files.isDirectory(versionFolder)) {
			return null;
		}
		try (Stream<Path> candidates = Files.walk(versionFolder, 2)) {
			return candidates
					.filter(Files::isRegularFile)
					.filter(candidate -> binaryName.equals(candidate.getFileName().toString()))
					.findFirst()
					.orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	private static List<Path> resolveConventionalSources(String sourceSetId) {
		Path root = toLocalPathIfPossible(sourceSetId);
		String sourceSetName = extractSourceSetName(sourceSetId);
		if (root == null || sourceSetName == null || sourceSetName.isBlank()) {
			return Collections.emptyList();
		}
		LinkedHashSet<Path> resolved = new LinkedHashSet<>();
		addIfDirectory(resolved, root.resolve("src").resolve(sourceSetName).resolve("java"));
		addIfDirectory(resolved, root.resolve("src").resolve(sourceSetName).resolve("kotlin"));
		addIfDirectory(resolved, root.resolve("src").resolve(sourceSetName).resolve("groovy"));
		addIfDirectory(resolved, root.resolve("src").resolve(sourceSetName).resolve("resources"));
		return new ArrayList<>(resolved);
	}

	private static void addIfDirectory(LinkedHashSet<Path> paths, Path candidate) {
		if (candidate != null && Files.isDirectory(candidate)) {
			paths.add(candidate);
		}
	}

	private static String extractSourceSetName(String sourceSetId) {
		if (sourceSetId == null || sourceSetId.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(sourceSetId);
			String query = uri.getQuery();
			if (query == null || query.isBlank()) {
				return null;
			}
			for (String part : query.split("&")) {
				if (part.startsWith("sourceset=")) {
					String value = part.substring("sourceset=".length());
					return value.isBlank() ? null : value;
				}
			}
		} catch (IllegalArgumentException e) {
			// ignore
		}
		return null;
	}


	private static Path toLocalPathIfPossible(String uriString) {
		if (uriString == null || uriString.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(uriString);
			if ("file".equalsIgnoreCase(uri.getScheme())) {
				URI withoutQuery = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null);
				return Paths.get(withoutQuery);
			}
		} catch (IllegalArgumentException e) {
			// Fall through to plain path parsing below.
		} catch (Exception e) {
			// Fall through to plain path parsing below.
		}
		try {
			return Path.of(uriString);
		} catch (InvalidPathException e) {
			return null;
		}
	}
}
