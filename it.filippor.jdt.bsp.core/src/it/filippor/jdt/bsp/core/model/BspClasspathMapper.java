package it.filippor.jdt.bsp.core.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.stream.Stream;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

public class BspClasspathMapper {

	public void apply(IJavaProject project, BspSourceSetModel model, IProgressMonitor monitor)
			throws JavaModelException, CoreException {
		List<IClasspathEntry> entries = new ArrayList<>();
		entries.add(JavaCore.newContainerEntry(Path.fromPortableString("org.eclipse.jdt.launching.JRE_CONTAINER")));

		Set<String> usedSourceNames = new HashSet<>();
		for (java.nio.file.Path sourceRoot : computeNonNestedSourceRoots(model.sources())) {
			IPath sourceEntry = ensureLinkedSourceFolder(project, sourceRoot, usedSourceNames, monitor);
			if (sourceEntry != null) {
				entries.add(JavaCore.newSourceEntry(sourceEntry));
			}
		}
		for (java.nio.file.Path cpPath : model.classpath()) {
			IClasspathEntry libraryEntry = toLibraryEntry(cpPath);
			if (libraryEntry != null) {
				entries.add(libraryEntry);
			}
		}
		dropBroadSourceSetLinks(project, entries, usedSourceNames, monitor);
		cleanupStaleLinkedSourceFolders(project, usedSourceNames, monitor);

		IFolder outputFolder = project.getProject().getFolder("bin");
		if (!outputFolder.exists()) {
			outputFolder.create(IResource.FORCE, true, monitor);
		}
		IPath outputPath = outputFolder.getFullPath();

		// Persist .classpath so JDT builder resolves dependencies consistently across refresh/restart.
		project.setRawClasspath(entries.toArray(IClasspathEntry[]::new), outputPath, true, monitor);
	}

	private static List<java.nio.file.Path> computeNonNestedSourceRoots(List<java.nio.file.Path> rawSources) {
		if (rawSources == null || rawSources.isEmpty()) {
			return List.of();
		}
		List<java.nio.file.Path> normalized = new ArrayList<>();
		for (java.nio.file.Path sourcePath : rawSources) {
			java.nio.file.Path sourceRoot = toDirectory(sourcePath);
			if (sourceRoot != null) {
				normalized.add(sourceRoot.toAbsolutePath().normalize());
			}
		}
		normalized.sort((left, right) -> {
			int depth = Integer.compare(right.getNameCount(), left.getNameCount());
			return depth != 0 ? depth : left.toString().compareToIgnoreCase(right.toString());
		});
		List<java.nio.file.Path> filtered = new ArrayList<>();
		for (java.nio.file.Path candidate : normalized) {
			boolean ancestorOfExisting = false;
			for (java.nio.file.Path existing : filtered) {
				if (samePath(candidate, existing) || isAncestorPath(candidate, existing)) {
					ancestorOfExisting = true;
					break;
				}
			}
			if (!ancestorOfExisting) {
				filtered.add(candidate);
			}
		}
		return applyConventionDedupe(filtered);
	}

	private static List<java.nio.file.Path> applyConventionDedupe(List<java.nio.file.Path> roots) {
		if (roots.isEmpty()) {
			return roots;
		}
		Map<String, Boolean> hasLanguageSpecificRootBySourceSet = new HashMap<>();
		List<SourceRootInfo> infos = new ArrayList<>(roots.size());
		for (java.nio.file.Path root : roots) {
			SourceRootInfo info = describeSourceRoot(root);
			infos.add(info);
			if (info.sourceSet() != null && info.language() != null) {
				hasLanguageSpecificRootBySourceSet.put(info.sourceSet(), Boolean.TRUE);
			}
		}
		List<java.nio.file.Path> deduped = new ArrayList<>(roots.size());
		for (SourceRootInfo info : infos) {
			boolean isBroadSourceSetRoot = info.sourceSet() != null && info.language() == null;
			if (isBroadSourceSetRoot && hasLanguageSpecificRootBySourceSet.containsKey(info.sourceSet())) {
				continue;
			}
			deduped.add(info.path());
		}
		return deduped;
	}

	private static SourceRootInfo describeSourceRoot(java.nio.file.Path path) {
		List<String> parts = StreamSupport.stream(path.spliterator(), false)
				.map(java.nio.file.Path::toString)
				.collect(Collectors.toList());
		int srcIndex = lastIndexOfIgnoreCase(parts, "src");
		if (srcIndex < 0 || srcIndex + 1 >= parts.size()) {
			return new SourceRootInfo(path, null, null);
		}
		String sourceSet = parts.get(srcIndex + 1).toLowerCase();
		String language = null;
		if (srcIndex + 2 < parts.size()) {
			String candidate = parts.get(srcIndex + 2).toLowerCase();
			if ("java".equals(candidate) || "kotlin".equals(candidate) || "groovy".equals(candidate)) {
				language = candidate;
			}
		}
		return new SourceRootInfo(path, sourceSet, language);
	}

	private static int lastIndexOfIgnoreCase(List<String> parts, String token) {
		for (int i = parts.size() - 1; i >= 0; i--) {
			if (token.equalsIgnoreCase(parts.get(i))) {
				return i;
			}
		}
		return -1;
	}

	private record SourceRootInfo(java.nio.file.Path path, String sourceSet, String language) {
	}

	private static boolean samePath(java.nio.file.Path left, java.nio.file.Path right) {
		return left.normalize().equals(right.normalize())
				|| left.normalize().toString().equalsIgnoreCase(right.normalize().toString());
	}

	private static boolean isAncestorPath(java.nio.file.Path ancestor, java.nio.file.Path descendant) {
		java.nio.file.Path normalizedAncestor = ancestor.normalize();
		java.nio.file.Path normalizedDescendant = descendant.normalize();
		if (samePath(normalizedAncestor, normalizedDescendant)) {
			return true;
		}
		if (normalizedDescendant.startsWith(normalizedAncestor)) {
			return true;
		}
		String ancestorKey = normalizedAncestor.toString().replace('\\', '/').toLowerCase();
		String descendantKey = normalizedDescendant.toString().replace('\\', '/').toLowerCase();
		if (!descendantKey.startsWith(ancestorKey)) {
			return false;
		}
		return descendantKey.length() == ancestorKey.length()
				|| descendantKey.charAt(ancestorKey.length()) == '/';
	}

	private static void cleanupStaleLinkedSourceFolders(IJavaProject project, Set<String> usedSourceNames,
			IProgressMonitor monitor) throws CoreException {
		IFolder sourcesRoot = project.getProject().getFolder("sources");
		if (!sourcesRoot.exists()) {
			return;
		}
		for (IResource child : sourcesRoot.members()) {
			if (!(child instanceof IFolder folder) || !folder.isLinked()) {
				continue;
			}
			String key = folder.getProjectRelativePath().toString().replace('\\', '/');
			if (!usedSourceNames.contains(key)) {
				folder.delete(IResource.FORCE, monitor);
			}
		}
	}

	private static void dropBroadSourceSetLinks(IJavaProject project, List<IClasspathEntry> entries,
			Set<String> usedSourceNames, IProgressMonitor monitor) throws CoreException {
		Set<String> languageSpecificSourceSets = new HashSet<>();
		for (String name : usedSourceNames) {
			String token = sourceToken(name);
			if (token == null) {
				continue;
			}
			int dash = token.indexOf('-');
			if (dash > 0) {
				languageSpecificSourceSets.add(token.substring(0, dash).toLowerCase());
			}
		}
		if (languageSpecificSourceSets.isEmpty()) {
			return;
		}
		Set<String> toDrop = new HashSet<>();
		for (String name : new ArrayList<>(usedSourceNames)) {
			String token = sourceToken(name);
			if (token == null || token.contains("-")) {
				continue;
			}
			if (languageSpecificSourceSets.contains(token.toLowerCase())) {
				toDrop.add(name);
			}
		}
		if (toDrop.isEmpty()) {
			return;
		}
		entries.removeIf(entry -> entry.getEntryKind() == IClasspathEntry.CPE_SOURCE
				&& toDrop.contains(toProjectRelativePath(project, entry.getPath())));
		for (String relative : toDrop) {
			usedSourceNames.remove(relative);
			IFolder folder = project.getProject().getFolder(relative);
			if (folder.exists() && folder.isLinked()) {
				folder.delete(IResource.FORCE, monitor);
			}
		}
	}

	private static String sourceToken(String projectRelativePath) {
		if (projectRelativePath == null) {
			return null;
		}
		String normalized = projectRelativePath.replace('\\', '/');
		if (!normalized.startsWith("sources/")) {
			return null;
		}
		return normalized.substring("sources/".length());
	}

	private static String toProjectRelativePath(IJavaProject project, IPath fullPath) {
		IPath projectPath = project.getProject().getFullPath();
		if (projectPath.isPrefixOf(fullPath)) {
			return fullPath.removeFirstSegments(projectPath.segmentCount()).toString().replace('\\', '/');
		}
		return fullPath.toString().replace('\\', '/');
	}

	private static IPath ensureLinkedSourceFolder(IJavaProject project, java.nio.file.Path sourcePath, Set<String> usedSourceNames,
			IProgressMonitor monitor) throws CoreException {
		java.nio.file.Path sourceRoot = toDirectory(sourcePath);
		if (sourceRoot == null) {
			return null;
		}
		if (!java.nio.file.Files.isDirectory(sourceRoot)) {
			return null;
		}
		String linkName = pickLinkName(sourceRoot, usedSourceNames);
		IFolder folder = project.getProject().getFolder(linkName);
		ensureVirtualParents(folder.getParent(), monitor);
		if (folder.exists()) {
			if (folder.isLinked() && sourceRoot.toUri().equals(folder.getLocationURI())) {
				return folder.getFullPath();
			}
			folder.delete(IResource.FORCE, monitor);
		}
		folder.createLink(sourceRoot.toUri(), IResource.REPLACE, monitor);
		return folder.getFullPath();
	}

	private static String pickLinkName(java.nio.file.Path sourceRoot, Set<String> usedSourceNames) {
		List<String> parts = StreamSupport.stream(sourceRoot.spliterator(), false)
				.map(java.nio.file.Path::toString)
				.collect(Collectors.toList());
		int srcIndex = parts.lastIndexOf("src");
		String candidate;
		if (srcIndex >= 0 && srcIndex + 2 < parts.size()) {
			candidate = "sources/" + sanitize(parts.get(srcIndex + 1) + "-" + parts.get(srcIndex + 2));
		} else if (!parts.isEmpty()) {
			candidate = "sources/" + sanitize(parts.get(parts.size() - 1));
		} else {
			candidate = "sources/linked";
		}
		String unique = candidate;
		int counter = 1;
		while (!usedSourceNames.add(unique)) {
			unique = candidate + "-" + counter++;
		}
		return unique;
	}

	private static void ensureVirtualParents(IContainer container, IProgressMonitor monitor) throws CoreException {
		if (container == null || container.getType() == IResource.PROJECT || container.exists()) {
			return;
		}
		ensureVirtualParents(container.getParent(), monitor);
		if (container instanceof IFolder folder && !folder.exists()) {
			folder.create(IResource.VIRTUAL, true, monitor);
		}
	}

	private static String sanitize(String value) {
		return value.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static java.nio.file.Path toDirectory(java.nio.file.Path path) {
		if (path == null) {
			return null;
		}
		if (java.nio.file.Files.isDirectory(path)) {
			return path;
		}
		if (!java.nio.file.Files.exists(path)) {
			if (looksLikeFilePath(path)) {
				java.nio.file.Path parent = path.getParent();
				return parent != null ? parent : path;
			}
			return path;
		}
		java.nio.file.Path parent = path.getParent();
		return parent != null && java.nio.file.Files.isDirectory(parent) ? parent : null;
	}

	private static boolean looksLikeFilePath(java.nio.file.Path path) {
		java.nio.file.Path fileName = path.getFileName();
		if (fileName == null) {
			return false;
		}
		String name = fileName.toString();
		int dot = name.lastIndexOf('.');
		return dot > 0 && dot < name.length() - 1;
	}

	private static IClasspathEntry toLibraryEntry(java.nio.file.Path cpPath) {
		if (cpPath == null) {
			return null;
		}
		java.nio.file.Path binaryPath = resolveBinaryJar(cpPath);
		if (binaryPath == null) {
			binaryPath = cpPath;
		}
		if (!java.nio.file.Files.exists(binaryPath)) {
			return null;
		}
		IPath libraryPath = Path.fromOSString(binaryPath.toString());
		java.nio.file.Path sourcePath = resolveSourceJar(cpPath, binaryPath);
		IPath sourceAttachment = sourcePath == null ? null : Path.fromOSString(sourcePath.toString());
		return JavaCore.newLibraryEntry(libraryPath, sourceAttachment, null);
	}

	private static java.nio.file.Path resolveBinaryJar(java.nio.file.Path cpPath) {
		String fileName = cpPath.getFileName() == null ? "" : cpPath.getFileName().toString();
		if (fileName.endsWith("-sources.jar")) {
			String binaryName = fileName.substring(0, fileName.length() - "-sources.jar".length()) + ".jar";
			java.nio.file.Path sibling = cpPath.resolveSibling(binaryName);
			if (java.nio.file.Files.isRegularFile(sibling)) {
				return sibling;
			}
			return findInVersionFolder(cpPath, binaryName);
		}
		if (java.nio.file.Files.isRegularFile(cpPath)) {
			return cpPath;
		}
		return null;
	}

	private static java.nio.file.Path resolveSourceJar(java.nio.file.Path originalPath, java.nio.file.Path binaryPath) {
		String originalName = originalPath.getFileName() == null ? "" : originalPath.getFileName().toString();
		if (originalName.endsWith("-sources.jar") && java.nio.file.Files.isRegularFile(originalPath)) {
			return originalPath;
		}
		if (binaryPath == null) {
			return null;
		}
		String binaryName = binaryPath.getFileName() == null ? "" : binaryPath.getFileName().toString();
		if (!binaryName.endsWith(".jar")) {
			return null;
		}
		String sourceName = binaryName.substring(0, binaryName.length() - ".jar".length()) + "-sources.jar";
		java.nio.file.Path sibling = binaryPath.resolveSibling(sourceName);
		if (java.nio.file.Files.isRegularFile(sibling)) {
			return sibling;
		}
		return findInVersionFolder(binaryPath, sourceName);
	}

	private static java.nio.file.Path findInVersionFolder(java.nio.file.Path path, String fileName) {
		java.nio.file.Path hashFolder = path.getParent();
		java.nio.file.Path versionFolder = hashFolder == null ? null : hashFolder.getParent();
		if (versionFolder == null || !java.nio.file.Files.isDirectory(versionFolder)) {
			return null;
		}
		try (Stream<java.nio.file.Path> candidates = java.nio.file.Files.walk(versionFolder, 2)) {
			return candidates
					.filter(java.nio.file.Files::isRegularFile)
					.filter(candidate -> fileName.equals(candidate.getFileName().toString()))
					.findFirst()
					.orElse(null);
		} catch (Exception e) {
			return null;
		}
	}
}
