package it.filippor.jdt.bsp.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.filippor.jdt.bsp.core.internal.BspClasspathMapper;
import it.filippor.jdt.bsp.core.model.BspSourceSetModel;
import it.filippor.jdt.bsp.core.nature.BspModuleProjectNature;
import it.filippor.jdt.bsp.core.nature.BspRootProjectNature;
import it.filippor.jdt.bsp.core.nature.BspSourceSetProjectNature;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.prefs.Preferences;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceStatus;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ProjectScope;
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

public class BspWorkspaceProjectSynchronizer {

    private final BspClasspathMapper classpathMapper = new BspClasspathMapper();
    private static final String BSP_META = "it.filippor.jdt.bsp.core";
    private static final String CORE_PLUGIN_ID = "it.filippor.jdt.bsp.core";
    private static final QualifiedName BSP_ROOT_FOLDER = new QualifiedName(BSP_META, "rootFolder");
    private static final QualifiedName BSP_COMMAND = new QualifiedName(BSP_META, "commandLine");

    public IProject ensureRootProject(String rootLabel, String commandLine, IProgressMonitor monitor)
        throws CoreException {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace()
                                             .getRoot();
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

    public void synchronize(String rootLabel, String commandLine, List<BspSourceSetModel> sourceSets,
                            IProgressMonitor monitor)
        throws CoreException, JavaModelException {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace()
                                             .getRoot();
        IProject rootProject = ensureRootProject(rootLabel, commandLine, monitor);
        String rootProjectName = rootProject.getName();
        String rootLogicalName = extractFolderName(rootLabel);
        Map<String, List<BspSourceSetModel>> sourceSetsByModule = groupByModule(rootLabel, sourceSets);
        Map<String, SourceSetBinding> sourceSetById = new HashMap<>();

        List<IProject> containerProjects = new ArrayList<>();
        for (Map.Entry<String, List<BspSourceSetModel>> entry : sourceSetsByModule.entrySet()) {
            String containerName = entry.getKey();
            java.nio.file.Path containerLocation = resolveModuleLocation(rootLabel, containerName);
            String containerProjectName = toModuleProjectName(rootProjectName, containerName);
            IProject containerProject = root.getProject(containerProjectName);
            ensureProject(containerProject, Path.fromOSString(containerLocation.toString()), false, monitor);
            String logicalModuleName = toLogicalModuleName(rootLogicalName, containerName);
            setProjectProperty(containerProject, BspRootProjectNature.PROPERTY_CONTAINER_NAME, logicalModuleName);
            try {
                ensureBspModuleNature(containerProject, monitor);
            } catch (CoreException e) {
                log(IStatus.WARNING,
                    "Failed to apply BSP container nature for project '" + containerProject.getName()
                        + "'. Synchronization will continue without the BSP container nature.",
                    e);
            }

            List<IProject> buildTargetProjects = new ArrayList<>();
            List<SourceSetBinding> containerBindings = new ArrayList<>();
            for (BspSourceSetModel sourceSet : entry.getValue()) {
                String buildTargetName = normalizeSourceSetName(containerName,
                    extractSourceSetName(sourceSet.sourceSetId()));
                String buildTargetProjectName = toSourceSetProjectName(containerProjectName, buildTargetName);
                IProject buildTargetProject = root.getProject(buildTargetProjectName);
                java.nio.file.Path buildTargetLocation = containerLocation.resolve(".bsp")
                                                                          .resolve("targets")
                                                                          .resolve(sanitize(buildTargetName));
                ensureProject(buildTargetProject, Path.fromOSString(buildTargetLocation.toString()), false, monitor);
                String logicalTargetName = toLogicalTargetName(rootLogicalName, containerName, buildTargetName);
                setProjectProperty(buildTargetProject, BspRootProjectNature.PROPERTY_BUILD_TARGET_NAME,
                    logicalTargetName);
                try {
                    ensureBspSourceSetNature(buildTargetProject, monitor);
                } catch (CoreException e) {
                    log(IStatus.WARNING,
                        "Failed to apply BSP build-target nature for project '" + buildTargetProject.getName()
                            + "'. Synchronization will continue without the BSP build-target nature.",
                        e);
                }
                ensureJavaNature(buildTargetProject, monitor);
                IJavaProject javaProject = JavaCore.create(buildTargetProject);
                this.classpathMapper.apply(javaProject, sourceSet, monitor);
                applyJavaCompliance(javaProject, sourceSet.javaSourceLevel());
                applyProjectEncoding(buildTargetProject, sourceSet.javaEncoding(), monitor);
                SourceSetBinding binding = new SourceSetBinding(containerName, buildTargetName, sourceSet,
                    buildTargetProject, javaProject);
                containerBindings.add(binding);
                sourceSetById.put(sourceSet.sourceSetId(), binding);
                buildTargetProjects.add(buildTargetProject);
            }
            // Classpath is already resolved in BspSourceSetModel; avoid adding inferred dependencies here.

            IProjectDescription containerDescription = containerProject.getDescription();
            containerDescription.setReferencedProjects(buildTargetProjects.toArray(IProject[]::new));
            containerProject.setDescription(containerDescription, monitor);
            containerProjects.add(containerProject);
        }

        // Ensure BSP target graph dependencies are represented as project dependencies.
        applyBspTargetDependencies(rootLabel, sourceSetById, monitor);

        IProjectDescription description = rootProject.getDescription();
        description.setReferencedProjects(containerProjects.toArray(IProject[]::new));
        rootProject.setDescription(description, monitor);
    }

    private static void applyBspTargetDependencies(String rootLabel, Map<String, SourceSetBinding> sourceSetById,
                                                   IProgressMonitor monitor)
        throws JavaModelException {
        Map<String, SourceSetBinding> byNormalizedId = new HashMap<>();
        Map<String, SourceSetBinding> byModuleAndSourceSet = new HashMap<>();
        Map<String, SourceSetBinding> mainByModule = new HashMap<>();
        for (SourceSetBinding binding : sourceSetById.values()) {
            String normalized = normalizeTargetId(binding.model()
                                                         .sourceSetId());
            if (normalized != null) {
                byNormalizedId.putIfAbsent(normalized, binding);
            }
            byModuleAndSourceSet.putIfAbsent(moduleAndSourceSetKey(binding.moduleName(), binding.sourceSetName()),
                binding);
            if ("main".equals(binding.sourceSetName())) {
                mainByModule.putIfAbsent(binding.moduleName(), binding);
            }
        }

        for (SourceSetBinding binding : sourceSetById.values()) {
            Set<IProject> deps = new LinkedHashSet<>();
            for (String targetDependencyId : binding.model()
                                                    .targetDependencies()) {
                SourceSetBinding dependencyBinding = resolveDependencyBinding(rootLabel, targetDependencyId,
                    sourceSetById,
                    byNormalizedId, byModuleAndSourceSet, mainByModule);
                if (dependencyBinding != null && !dependencyBinding.project()
                                                                   .equals(binding.project())) {
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
            String normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1)
                : path;
            String query = uri.getQuery();
            return query == null || query.isBlank() ? normalizedPath : normalizedPath + "?" + query;
        } catch (Exception e) {
            return targetId;
        }
    }

    private static String moduleAndSourceSetKey(String moduleName, String sourceSetName) {
        return sanitize(moduleName) + "::" + sanitize(sourceSetName);
    }

   

    private static void addProjectDependencies(IJavaProject project, List<IProject> dependencies,
                                               IProgressMonitor monitor)
        throws JavaModelException {
        if (dependencies == null || dependencies.isEmpty()) {
            log(IStatus.INFO, "No project dependencies to add for '" + project.getElementName() + "'.", null);
            return;
        }
        IClasspathEntry[] rawClasspath = project.getRawClasspath();
        List<IClasspathEntry> updated = new ArrayList<>(List.of(rawClasspath));
        Set<String> existing = new LinkedHashSet<>();
        for (IClasspathEntry entry : rawClasspath) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                existing.add(entry.getPath()
                                  .toString());
            }
        }
        for (IProject dep : dependencies) {
            if (dep == null || !dep.exists() || !dep.isOpen()) {
                log(IStatus.INFO,
                    "Skipping unresolved project dependency for '" + project.getElementName() + "'.",
                    null);
                continue;
            }
            if (dep.equals(project.getProject())) {
                log(IStatus.INFO,
                    "Skipping self project dependency '" + dep.getName() + "' for '" + project.getElementName() + "'.",
                    null);
                continue;
            }
            String depPath = dep.getFullPath()
                                .toString();
            if (existing.add(depPath)) {
                updated.add(JavaCore.newProjectEntry(dep.getFullPath()));
                log(IStatus.INFO,
                    "Added project dependency '" + dep.getName() + "' (" + depPath + ") to '"
                        + project.getElementName() + "'.",
                    null);
            } else {
                log(IStatus.INFO,
                    "Project dependency already present '" + dep.getName() + "' (" + depPath + ") for '"
                        + project.getElementName() + "'.",
                    null);
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

    private static void applyProjectEncoding(IProject project, String javaEncoding, IProgressMonitor monitor) {
        if (project == null || javaEncoding == null || javaEncoding.isBlank()) {
            return;
        }
        try {
            String explicit = project.getDefaultCharset(false);
            if (explicit == null || !javaEncoding.equalsIgnoreCase(explicit)) {
                project.setDefaultCharset(javaEncoding, monitor);
            }
        } catch (Exception e) {
            log(IStatus.WARNING,
                "Failed to apply source encoding '" + javaEncoding + "' for project '" + project.getName() + "'.",
                e);
        }
    }

    private record SourceSetBinding(String moduleName,
                                    String sourceSetName,
                                    BspSourceSetModel model,
                                    IProject project,
                                    IJavaProject javaProject) {
    }

    private static Map<String, List<BspSourceSetModel>> groupByModule(String rootLabel,
                                                                      List<BspSourceSetModel> sourceSets) {
        Map<String, List<BspSourceSetModel>> grouped = new LinkedHashMap<>();
        for (BspSourceSetModel sourceSet : sourceSets) {
            String moduleName = extractModuleName(rootLabel, sourceSet.sourceSetId());
            grouped.computeIfAbsent(moduleName, unused -> new ArrayList<>())
                   .add(sourceSet);
        }
        return grouped;
    }

    private static void ensureProject(IProject project, org.eclipse.core.runtime.IPath location, boolean virtual,
                                      IProgressMonitor monitor) throws CoreException {
        if (!project.exists()) {
            IProjectDescription description = project.getWorkspace()
                                                     .newProjectDescription(project.getName());
            if (location != null) {
                description.setLocation(location);
            }
            int flags = virtual ? IResource.VIRTUAL : IResource.NONE;
            project.create(description, flags, monitor);
        }
        if (!project.isOpen()) {
            try {
                project.open(monitor);
            } catch (CoreException e) {
                if (!isMissingProjectDescription(e)) {
                    throw e;
                }
                // Workspace metadata can keep a stale project handle while the on-disk .project is gone.
                // Recreate the project entry so synchronization can continue.
                project.delete(false, true, monitor);
                IProjectDescription description = project.getWorkspace()
                                                         .newProjectDescription(project.getName());
                if (location != null) {
                    description.setLocation(location);
                }
                int flags = virtual ? IResource.VIRTUAL : IResource.NONE;
                project.create(description, flags, monitor);
                project.open(monitor);
            }
        }
    }

    private static boolean isMissingProjectDescription(CoreException e) {
        if (e == null || e.getStatus() == null) {
            return false;
        }
        if (e.getStatus()
             .getCode() == IResourceStatus.FAILED_READ_METADATA) {
            return true;
        }
        String message = e.getStatus()
                          .getMessage();
        return message != null && message.contains("project description file (.project)")
            && message.toLowerCase()
                      .contains("missing");
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
        IStatus status = ResourcesPlugin.getWorkspace()
                                        .validateNatureSet(newNatures);
        if (status.getCode() == IStatus.OK) {
            description.setNatureIds(newNatures);
            project.setDescription(description, null);
        } else {
            throw new CoreException(new Status(IStatus.ERROR, CORE_PLUGIN_ID,
                "Cannot apply BSP root nature to project '" + project.getName() + "': " + status.getMessage()));
        }
    }

    private static void ensureBspModuleNature(IProject project, IProgressMonitor monitor) throws CoreException {
        IProjectDescription description = project.getDescription();
        String[] natures = description.getNatureIds();
        for (String nature : natures) {
            if (BspModuleProjectNature.NATURE_ID.equals(nature)) {
                return;
            }
        }
        String[] newNatures = Arrays.copyOf(natures, natures.length + 1);
        newNatures[natures.length] = BspModuleProjectNature.NATURE_ID;
        IStatus status = ResourcesPlugin.getWorkspace()
                                        .validateNatureSet(newNatures);
        if (status.getCode() == IStatus.OK) {
            description.setNatureIds(newNatures);
            project.setDescription(description, monitor);
        } else {
            throw new CoreException(new Status(IStatus.ERROR, CORE_PLUGIN_ID,
                "Cannot apply BSP module nature to project '" + project.getName() + "': " + status.getMessage()));
        }
    }

    private static void ensureBspSourceSetNature(IProject project, IProgressMonitor monitor) throws CoreException {
        IProjectDescription description = project.getDescription();
        String[] natures = description.getNatureIds();
        for (String nature : natures) {
            if (BspSourceSetProjectNature.NATURE_ID.equals(nature)) {
                return;
            }
        }
        String[] newNatures = Arrays.copyOf(natures, natures.length + 1);
        newNatures[natures.length] = BspSourceSetProjectNature.NATURE_ID;
        IStatus status = ResourcesPlugin.getWorkspace()
                                        .validateNatureSet(newNatures);
        if (status.getCode() == IStatus.OK) {
            description.setNatureIds(newNatures);
            project.setDescription(description, monitor);
        } else {
            throw new CoreException(new Status(IStatus.ERROR, CORE_PLUGIN_ID,
                "Cannot apply BSP source-set nature to project '" + project.getName() + "': " + status.getMessage()));
        }
    }

    private static void log(int severity, String message, Throwable error) {
        Bundle bundle = FrameworkUtil.getBundle(BspWorkspaceProjectSynchronizer.class);
        if (bundle == null) {
            return;
        }
        Platform.getLog(bundle)
                .log(new Status(severity, CORE_PLUGIN_ID, message, error));
    }

    private static void writeProjectSettings(IProject project, String commandLine) {
        try {
            BspProjectSettingsStore.write(project, commandLine);
        } catch (Exception e) {
            // Metadata persistence is best-effort; startup can still proceed via persistent properties.
        }
    }

    private static void setProjectProperty(IProject project, QualifiedName key, String value) {
        try {
            Preferences node = new ProjectScope(project).getNode(BspRootProjectNature.SETTINGS_NODE);
            if (BspRootProjectNature.PROPERTY_CONTAINER_NAME.equals(key)) {
                node.put(BspRootProjectNature.KEY_CONTAINER_NAME, value == null ? "" : value);
            } else if (BspRootProjectNature.PROPERTY_BUILD_TARGET_NAME.equals(key)) {
                node.put(BspRootProjectNature.KEY_BUILD_TARGET_NAME, value == null ? "" : value);
            }
            node.flush();
        } catch (Exception e) {
            // Best effort metadata.
        }
    }

    private static String toRootProjectName(String rootLabel) {
        String normalized = sanitize(extractFolderName(rootLabel));
        return truncate(normalized, 80);
    }

    private static String toModuleProjectName(String rootProjectName, String moduleName) {
        String moduleSegment = moduleName == null || moduleName.isBlank() ? "root" : moduleName.replace("/", "__");
        String child = rootProjectName + "__" + sanitizeProjectName(moduleSegment);
        return truncateWithHash(child, 100);
    }

    private static String toSourceSetProjectName(String moduleProjectName, String sourceSetName) {
        String child = moduleProjectName + "__" + sanitizeProjectName(sourceSetName);
        return truncateWithHash(child, 110);
    }

    private static String toLogicalModuleName(String rootName, String moduleName) {
        String root = (rootName == null || rootName.isBlank()) ? "root" : rootName;
        if (moduleName == null || moduleName.isBlank() || "root".equals(moduleName)) {
            return root;
        }
        return root + ":" + moduleName.replace('/', ':');
    }

    private static String toLogicalTargetName(String rootName, String moduleName, String sourceSetName) {
        String moduleLogicalName = toLogicalModuleName(rootName, moduleName);
        if (sourceSetName == null || sourceSetName.isBlank()) {
            return moduleLogicalName;
        }
        return moduleLogicalName + ":" + sourceSetName;
    }

    private static String extractModuleName(String rootLabel, String sourceSetId) {
        if (sourceSetId == null || sourceSetId.isBlank()) {
            return "root";
        }
        try {
            java.nio.file.Path rootPath = java.nio.file.Path.of(rootLabel)
                                                            .toAbsolutePath()
                                                            .normalize();
            URI uri = URI.create(sourceSetId);
            String path = uri.getPath();
            if (path != null && !path.isBlank()) {
                java.nio.file.Path modulePath = java.nio.file.Path.of(new URI(uri.getScheme(), uri.getAuthority(), path,
                    null, null))
                                                                  .toAbsolutePath()
                                                                  .normalize();
                if (modulePath.equals(rootPath)) {
                    return "root";
                }
                if (modulePath.startsWith(rootPath)) {
                    java.nio.file.Path relative = rootPath.relativize(modulePath);
                    if (!relative.toString()
                                 .isBlank()) {
                        return relative.toString()
                                       .replace('\\', '/');
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

    private static String extractFolderName(String rootLabel) {
        if (rootLabel == null || rootLabel.isBlank()) {
            return "root";
        }
        try {
            java.nio.file.Path path = java.nio.file.Path.of(rootLabel)
                                                        .normalize();
            java.nio.file.Path name = path.getFileName();
            if (name != null && !name.toString()
                                     .isBlank()) {
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

    private static String normalizeSourceSetName(String moduleName, String sourceSetName) {
        if (moduleName == null) {
            moduleName = "";
        }
        if (sourceSetName == null || sourceSetName.isBlank()) {
            return "unknown";
        }
        String candidate = sourceSetName;
        int colon = candidate.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < candidate.length()) {
            candidate = candidate.substring(colon + 1);
        }
        return candidate;
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

    private static String sanitizeProjectName(String text) {
        if (text == null || text.isBlank()) {
            return "bsp_project";
        }
        String candidate = text.replaceAll("[^A-Za-z0-9._:-]", "_");
        return candidate.isBlank() ? "bsp_project" : candidate;
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
