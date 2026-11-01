package it.filippor.jdt.bsp.core.model;

import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import it.filippor.jdt.bsp.core.BspProjectSettingsStore;
import it.filippor.jdt.bsp.core.BspRootProjectNature;

public class BspWorkspaceProjectSynchronizer {

	private final BspClasspathMapper classpathMapper = new BspClasspathMapper();
	private static final String BSP_META = "it.filippor.jdt.bsp.core";
	private static final String CORE_PLUGIN_ID = "it.filippor.jdt.bsp.core";
	private static final QualifiedName BSP_ROOT_FOLDER = new QualifiedName(BSP_META, "rootFolder");
	private static final QualifiedName BSP_COMMAND = new QualifiedName(BSP_META, "commandLine");
	private static final Pattern PROJECT_DEP_PATTERN = Pattern
			.compile("project\\s*\\(\\s*(?:path\\s*:\\s*)?['\"](:[^'\"]+)['\"]\\s*\\)");
	public IProject ensureRootProject(String rootLabel, String commandLine, IProgressMonitor monitor)
			throws CoreException {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		String rootProjectName = toRootProjectName(rootLabel);
		IProject rootProject = root.getProject(rootProjectName);
		ensureProject(rootProject, Path.fromOSString(rootLabel), false, monitor);
		try {
			ensureBspRootNature(rootProject, monitor);
		} catch (CoreException e) {
			log(IStatus.WARNING,
					"Failed to apply BSP root nature for project '" + rootProject.getName()
							+ "'. Startup will continue without the BSP root nature.",
					e);
		}
		rootProject.setPersistentProperty(BSP_ROOT_FOLDER, rootLabel);
		rootProject.setPersistentProperty(BSP_COMMAND, commandLine);
		writeProjectSettings(rootProject, commandLine);
		return rootProject;
	}

	public void synchronize(String rootLabel, String commandLine, List<BspSourceSetModel> sourceSets, IProgressMonitor monitor)
			throws CoreException, JavaModelException {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IProject rootProject = ensureRootProject(rootLabel, commandLine, monitor);
		String rootProjectName = rootProject.getName();
		Map<String, List<BspSourceSetModel>> sourceSetsByModule = groupByModule(rootLabel, sourceSets);
		Map<String, SourceSetBinding> sourceSetById = new HashMap<>();

		List<IProject> moduleProjects = new ArrayList<>();
		for (Map.Entry<String, List<BspSourceSetModel>> entry : sourceSetsByModule.entrySet()) {
			String moduleName = entry.getKey();
			java.nio.file.Path moduleLocation = resolveModuleLocation(rootLabel, moduleName);
			String moduleProjectName = toModuleProjectName(rootProjectName, moduleName);
			IProject moduleProject = root.getProject(moduleProjectName);
			ensureProject(moduleProject, Path.fromOSString(moduleLocation.toString()), false, monitor);

			List<IProject> sourceSetProjects = new ArrayList<>();
			List<SourceSetBinding> moduleBindings = new ArrayList<>();
			for (BspSourceSetModel sourceSet : entry.getValue()) {
				String sourceSetName = extractSourceSetName(sourceSet.sourceSetId());
				String sourceSetProjectName = toSourceSetProjectName(moduleProjectName, sourceSetName);
				IProject sourceSetProject = root.getProject(sourceSetProjectName);
				java.nio.file.Path sourceSetLocation = moduleLocation.resolve(".bsp").resolve("sourcesets")
						.resolve(sanitize(sourceSetName));
				ensureProject(sourceSetProject, Path.fromOSString(sourceSetLocation.toString()), false, monitor);
				ensureJavaNature(sourceSetProject, monitor);
				IJavaProject javaProject = JavaCore.create(sourceSetProject);
				this.classpathMapper.apply(javaProject, sourceSet, monitor);
				applyJavaCompliance(javaProject, sourceSet.javaSourceLevel());
				SourceSetBinding binding = new SourceSetBinding(moduleName, sourceSetName, sourceSet, sourceSetProject,
						javaProject);
				moduleBindings.add(binding);
				sourceSetById.put(sourceSet.sourceSetId(), binding);
				sourceSetProjects.add(sourceSetProject);
			}
			applyIntraModuleDependencies(moduleBindings, monitor);

			IProjectDescription moduleDescription = moduleProject.getDescription();
			moduleDescription.setReferencedProjects(sourceSetProjects.toArray(IProject[]::new));
			moduleProject.setDescription(moduleDescription, monitor);
			moduleProjects.add(moduleProject);
		}

		applyBspTargetDependencies(rootLabel, sourceSetById, monitor);
		applyGradleProjectDependencies(rootLabel, sourceSetById, monitor);

		IProjectDescription description = rootProject.getDescription();
		description.setReferencedProjects(moduleProjects.toArray(IProject[]::new));
		rootProject.setDescription(description, monitor);
	}

	private static void applyIntraModuleDependencies(List<SourceSetBinding> moduleBindings, IProgressMonitor monitor)
			throws JavaModelException {
		if (moduleBindings.isEmpty()) {
			return;
		}
		Map<String, SourceSetBinding> bySourceSet = new HashMap<>();
		for (SourceSetBinding binding : moduleBindings) {
			bySourceSet.put(binding.sourceSetName(), binding);
		}
		SourceSetBinding main = bySourceSet.get("main");
		if (main == null) {
			return;
		}
		for (SourceSetBinding binding : moduleBindings) {
			if (!"main".equals(binding.sourceSetName())) {
				addProjectDependencies(binding.javaProject(), List.of(main.project()), monitor);
			}
		}
	}

	private static void applyBspTargetDependencies(String rootLabel, Map<String, SourceSetBinding> sourceSetById,
			IProgressMonitor monitor)
			throws JavaModelException {
		Map<String, SourceSetBinding> byNormalizedId = new HashMap<>();
		Map<String, SourceSetBinding> byModuleAndSourceSet = new HashMap<>();
		Map<String, SourceSetBinding> mainByModule = new HashMap<>();
		for (SourceSetBinding binding : sourceSetById.values()) {
			String normalized = normalizeTargetId(binding.model().sourceSetId());
			if (normalized != null) {
				byNormalizedId.putIfAbsent(normalized, binding);
			}
			byModuleAndSourceSet.putIfAbsent(moduleAndSourceSetKey(binding.moduleName(), binding.sourceSetName()), binding);
			if ("main".equals(binding.sourceSetName())) {
				mainByModule.putIfAbsent(binding.moduleName(), binding);
			}
		}

		for (SourceSetBinding binding : sourceSetById.values()) {
			Set<IProject> deps = new LinkedHashSet<>();
			for (String targetDependencyId : binding.model().targetDependencies()) {
				SourceSetBinding dependencyBinding = resolveDependencyBinding(rootLabel, targetDependencyId, sourceSetById,
						byNormalizedId, byModuleAndSourceSet, mainByModule);
				if (dependencyBinding != null && !dependencyBinding.project().equals(binding.project())) {
					deps.add(dependencyBinding.project());
				}
			}
			if (!deps.isEmpty()) {
				addProjectDependencies(binding.javaProject(), new ArrayList<>(deps), monitor);
			}
		}
	}

	private static SourceSetBinding resolveDependencyBinding(String rootLabel, String dependencyId,
			Map<String, SourceSetBinding> byExactId,
			Map<String, SourceSetBinding> byNormalizedId,
			Map<String, SourceSetBinding> byModuleAndSourceSet,
			Map<String, SourceSetBinding> mainByModule) {
		if (dependencyId == null || dependencyId.isBlank()) {
			return null;
		}
		SourceSetBinding exact = byExactId.get(dependencyId);
		if (exact != null) {
			return exact;
		}
		String normalized = normalizeTargetId(dependencyId);
		if (normalized != null) {
			SourceSetBinding normalizedMatch = byNormalizedId.get(normalized);
			if (normalizedMatch != null) {
				return normalizedMatch;
			}
		}

		String module = extractModuleName(rootLabel, dependencyId);
		String sourceSet = extractSourceSetName(dependencyId);
		SourceSetBinding exactModuleSourceSet = byModuleAndSourceSet.get(moduleAndSourceSetKey(module, sourceSet));
		if (exactModuleSourceSet != null) {
			return exactModuleSourceSet;
		}
		return mainByModule.get(module);
	}

	private static String normalizeTargetId(String targetId) {
		try {
			URI uri = URI.create(targetId);
			if (!"file".equalsIgnoreCase(uri.getScheme())) {
				return targetId;
			}
			String path = uri.getPath();
			if (path == null) {
				return targetId;
			}
			String normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
			String query = uri.getQuery();
			return query == null || query.isBlank() ? normalizedPath : normalizedPath + "?" + query;
		} catch (Exception e) {
			return targetId;
		}
	}

	private static String moduleAndSourceSetKey(String moduleName, String sourceSetName) {
		return sanitize(moduleName) + "::" + sanitize(sourceSetName);
	}

	private static void applyGradleProjectDependencies(String rootLabel, Map<String, SourceSetBinding> sourceSetById,
			IProgressMonitor monitor) throws JavaModelException {
		Map<String, SourceSetBinding> mainByModule = new HashMap<>();
		Map<String, List<SourceSetBinding>> bindingsByModule = new HashMap<>();
		for (SourceSetBinding binding : sourceSetById.values()) {
			bindingsByModule.computeIfAbsent(binding.moduleName(), unused -> new ArrayList<>()).add(binding);
			if ("main".equals(binding.sourceSetName())) {
				mainByModule.put(binding.moduleName(), binding);
			}
		}
		for (Map.Entry<String, List<SourceSetBinding>> entry : bindingsByModule.entrySet()) {
			String moduleName = entry.getKey();
			Set<IProject> deps = new LinkedHashSet<>();
			for (String moduleDependency : readGradleProjectDependencies(rootLabel, moduleName)) {
				SourceSetBinding dep = resolveMainModuleBinding(mainByModule, moduleDependency);
				if (dep != null) {
					deps.add(dep.project());
				}
			}
			if (!deps.isEmpty()) {
				for (SourceSetBinding binding : entry.getValue()) {
					List<IProject> filtered = new ArrayList<>(deps);
					filtered.remove(binding.project());
					if (!filtered.isEmpty()) {
						addProjectDependencies(binding.javaProject(), filtered, monitor);
					}
				}
			}
		}
	}

	private static List<String> readGradleProjectDependencies(String rootLabel, String moduleName) {
		java.nio.file.Path root = java.nio.file.Path.of(rootLabel);
		java.nio.file.Path moduleDir = root;
		String normalizedModuleName = normalizeModulePath(moduleName);
		if (!"root".equals(normalizedModuleName)) {
			for (String segment : normalizedModuleName.split("/")) {
				if (!segment.isBlank()) {
					moduleDir = moduleDir.resolve(segment);
				}
			}
		}
		List<String> deps = new ArrayList<>();
		collectDependencies(moduleDir.resolve("build.gradle"), deps);
		collectDependencies(moduleDir.resolve("build.gradle.kts"), deps);
		return deps;
	}

	private static void collectDependencies(java.nio.file.Path buildFile, List<String> out) {
		if (buildFile == null || !Files.isRegularFile(buildFile)) {
			return;
		}
		try {
			String content = Files.readString(buildFile);
			Matcher matcher = PROJECT_DEP_PATTERN.matcher(content);
			while (matcher.find()) {
				String gradlePath = matcher.group(1);
				String normalized = normalizeModulePath(gradlePath);
				if (!normalized.isBlank()) {
					out.add(normalized);
				}
			}
		} catch (Exception e) {
			// Best effort dependency extraction.
		}
	}

	private static void addProjectDependencies(IJavaProject project, List<IProject> dependencies, IProgressMonitor monitor)
			throws JavaModelException {
		if (dependencies == null || dependencies.isEmpty()) {
			return;
		}
		IClasspathEntry[] rawClasspath = project.getRawClasspath();
		List<IClasspathEntry> updated = new ArrayList<>(List.of(rawClasspath));
		Set<String> existing = new LinkedHashSet<>();
		for (IClasspathEntry entry : rawClasspath) {
			if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
				existing.add(entry.getPath().toString());
			}
		}
		for (IProject dep : dependencies) {
			if (dep == null || !dep.exists() || !dep.isOpen()) {
				continue;
			}
			String depPath = dep.getFullPath().toString();
			if (existing.add(depPath)) {
				updated.add(JavaCore.newProjectEntry(dep.getFullPath()));
			}
		}
		project.setRawClasspath(updated.toArray(IClasspathEntry[]::new), project.getOutputLocation(), monitor);
	}

	private static void applyJavaCompliance(IJavaProject javaProject, String javaSourceLevel) {
		if (javaSourceLevel == null || javaSourceLevel.isBlank()) {
			return;
		}
		Map<String, String> options = new HashMap<>(javaProject.getOptions(false));
		JavaCore.setComplianceOptions(javaSourceLevel, options);
		options.put(JavaCore.COMPILER_RELEASE, JavaCore.ENABLED);
		javaProject.setOptions(options);
	}

	private record SourceSetBinding(String moduleName, String sourceSetName, BspSourceSetModel model, IProject project,
			IJavaProject javaProject) {
	}

	private static Map<String, List<BspSourceSetModel>> groupByModule(String rootLabel, List<BspSourceSetModel> sourceSets) {
		Map<String, List<BspSourceSetModel>> grouped = new LinkedHashMap<>();
		for (BspSourceSetModel sourceSet : sourceSets) {
			String moduleName = extractModuleName(rootLabel, sourceSet.sourceSetId());
			grouped.computeIfAbsent(moduleName, unused -> new ArrayList<>()).add(sourceSet);
		}
		return grouped;
	}

	private static void ensureProject(IProject project, org.eclipse.core.runtime.IPath location, boolean virtual,
			IProgressMonitor monitor) throws CoreException {
		if (!project.exists()) {
			IProjectDescription description = project.getWorkspace().newProjectDescription(project.getName());
			if (location != null) {
				description.setLocation(location);
			}
			int flags = virtual ? IResource.VIRTUAL : IResource.NONE;
			project.create(description, flags, monitor);
		}
		if (!project.isOpen()) {
			project.open(monitor);
		}
	}

	private static void ensureJavaNature(IProject project, IProgressMonitor monitor) throws CoreException {
		IProjectDescription description = project.getDescription();
		String[] natures = description.getNatureIds();
		for (String nature : natures) {
			if (JavaCore.NATURE_ID.equals(nature)) {
				return;
			}
		}
		List<String> updated = new ArrayList<>(natures.length + 1);
		updated.add(JavaCore.NATURE_ID);
		for (String nature : natures) {
			updated.add(nature);
		}
		description.setNatureIds(updated.toArray(String[]::new));
		project.setDescription(description, monitor);
	}

	private static void ensureBspRootNature(IProject project, IProgressMonitor monitor) throws CoreException {
		IProjectDescription description = project.getDescription();
		String[] natures = description.getNatureIds();
		for (String nature : natures) {
			if (BspRootProjectNature.NATURE_ID.equals(nature)) {
				return;
			}
		}
		String[] newNatures = Arrays.copyOf(natures, natures.length + 1);
		newNatures[natures.length] = BspRootProjectNature.NATURE_ID;
		IStatus status = ResourcesPlugin.getWorkspace().validateNatureSet(newNatures);
		if (status.getCode() == IStatus.OK) {
		    description.setNatureIds(newNatures);
		    project.setDescription(description, null);
		} else {
		    throw new CoreException(new Status(IStatus.ERROR, CORE_PLUGIN_ID,
		            "Cannot apply BSP root nature to project '" + project.getName() + "': " + status.getMessage()));
		}
	}

	private static void log(int severity, String message, Throwable error) {
		Bundle bundle = FrameworkUtil.getBundle(BspWorkspaceProjectSynchronizer.class);
		if (bundle == null) {
			return;
		}
		Platform.getLog(bundle).log(new Status(severity, CORE_PLUGIN_ID, message, error));
	}

	private static void writeProjectSettings(IProject project, String commandLine) {
		try {
			BspProjectSettingsStore.write(project, commandLine);
		} catch (Exception e) {
			// Metadata persistence is best-effort; startup can still proceed via persistent properties.
		}
	}

	private static String toRootProjectName(String rootLabel) {
		String normalized = sanitize(extractFolderName(rootLabel));
		return truncate(normalized, 80);
	}

	private static String toModuleProjectName(String rootProjectName, String moduleName) {
		String child = rootProjectName + ".module." + sanitize(moduleName);
		return truncateWithHash(child, 100);
	}

	private static String toSourceSetProjectName(String moduleProjectName, String sourceSetName) {
		String child = moduleProjectName + ".sourceset." + sanitize(sourceSetName);
		return truncateWithHash(child, 110);
	}

	private static String extractModuleName(String rootLabel, String sourceSetId) {
		if (sourceSetId == null || sourceSetId.isBlank()) {
			return "root";
		}
		try {
			java.nio.file.Path rootPath = java.nio.file.Path.of(rootLabel).toAbsolutePath().normalize();
			URI uri = URI.create(sourceSetId);
			String path = uri.getPath();
			if (path != null && !path.isBlank()) {
				java.nio.file.Path modulePath = java.nio.file.Path.of(new URI(uri.getScheme(), uri.getAuthority(), path, null, null))
						.toAbsolutePath().normalize();
				if (modulePath.equals(rootPath)) {
					return "root";
				}
				if (modulePath.startsWith(rootPath)) {
					java.nio.file.Path relative = rootPath.relativize(modulePath);
					if (!relative.toString().isBlank()) {
						return relative.toString().replace('\\', '/');
					}
				}
				String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
				int slash = normalized.lastIndexOf('/');
				String tail = slash >= 0 ? normalized.substring(slash + 1) : normalized;
				if (!tail.isBlank()) {
					return tail;
				}
			}
		} catch (IllegalArgumentException e) {
			// Fall back to root.
		} catch (Exception e) {
			// Fall back to root.
		}
		return "root";
	}

	private static SourceSetBinding resolveMainModuleBinding(Map<String, SourceSetBinding> mainByModule,
			String moduleDependency) {
		String normalizedDependency = normalizeModulePath(moduleDependency);
		SourceSetBinding exact = mainByModule.get(normalizedDependency);
		if (exact != null) {
			return exact;
		}
		if ("root".equals(normalizedDependency)) {
			return mainByModule.get("root");
		}
		List<SourceSetBinding> candidates = new ArrayList<>();
		for (Map.Entry<String, SourceSetBinding> entry : mainByModule.entrySet()) {
			String key = normalizeModulePath(entry.getKey());
			if (key.equals(normalizedDependency) || key.endsWith("/" + normalizedDependency)
					|| moduleLeaf(key).equals(moduleLeaf(normalizedDependency))) {
				candidates.add(entry.getValue());
			}
		}
		return candidates.size() == 1 ? candidates.get(0) : null;
	}

	private static String normalizeModulePath(String modulePath) {
		if (modulePath == null) {
			return "";
		}
		String normalized = modulePath.trim().replace('\\', '/');
		if (":".equals(normalized)) {
			return "root";
		}
		while (normalized.startsWith(":")) {
			normalized = normalized.substring(1);
		}
		while (normalized.endsWith(":")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		normalized = normalized.replace(':', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized.isBlank() ? "root" : normalized;
	}

	private static String moduleLeaf(String modulePath) {
		String normalized = normalizeModulePath(modulePath);
		int idx = normalized.lastIndexOf('/');
		return idx < 0 ? normalized : normalized.substring(idx + 1);
	}

	private static String extractFolderName(String rootLabel) {
		if (rootLabel == null || rootLabel.isBlank()) {
			return "root";
		}
		try {
			java.nio.file.Path path = java.nio.file.Path.of(rootLabel).normalize();
			java.nio.file.Path name = path.getFileName();
			if (name != null && !name.toString().isBlank()) {
				return name.toString();
			}
		} catch (Exception e) {
			// fallback below
		}
		return rootLabel;
	}

	private static String extractSourceSetName(String sourceSetId) {
		if (sourceSetId == null || sourceSetId.isBlank()) {
			return "unknown";
		}
		try {
			URI uri = URI.create(sourceSetId);
			String query = uri.getQuery();
			if (query != null) {
				for (String part : query.split("&")) {
					if (part.startsWith("sourceset=")) {
						String value = part.substring("sourceset=".length());
						if (!value.isBlank()) {
							return value;
						}
					}
				}
			}
			String path = uri.getPath();
			if (path != null && !path.isBlank()) {
				int slash = path.lastIndexOf('/');
				String tail = slash >= 0 ? path.substring(slash + 1) : path;
				if (!tail.isBlank()) {
					return tail;
				}
			}
		} catch (IllegalArgumentException e) {
			// Fall back to raw identifier sanitization.
		}
		return sourceSetId;
	}

	private static java.nio.file.Path resolveModuleLocation(String rootLabel, String moduleName) {
		java.nio.file.Path rootPath = java.nio.file.Path.of(rootLabel);
		if ("root".equals(moduleName)) {
			return rootPath;
		}
		java.nio.file.Path modulePath = rootPath;
		for (String segment : moduleName.split("/")) {
			if (!segment.isBlank()) {
				modulePath = modulePath.resolve(segment);
			}
		}
		return modulePath;
	}

	private static String sanitize(String text) {
		String candidate = text.replaceAll("[^A-Za-z0-9._-]", "_");
		if (candidate.isBlank()) {
			candidate = "bsp_project";
		}
		return candidate;
	}

	private static String truncate(String value, int max) {
		if (value.length() <= max) {
			return value;
		}
		return value.substring(value.length() - max);
	}

	private static String truncateWithHash(String value, int max) {
		if (value.length() <= max) {
			return value;
		}
		String hash = Integer.toHexString(value.hashCode());
		String suffix = "_" + hash;
		int prefixLength = Math.max(8, max - suffix.length());
		return value.substring(0, prefixLength) + suffix;
	}

}
