package org.eclipse.jdt.bsp.core.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
				normalized.add(sourceRoot.normalize());
			}
		}
		normalized.sort((left, right) -> {
			int depth = Integer.compare(right.getNameCount(), left.getNameCount());
			return depth != 0 ? depth : left.toString().compareToIgnoreCase(right.toString());
		});
		List<java.nio.file.Path> filtered = new ArrayList<>();
		List<String> filteredKeys = new ArrayList<>();
		for (java.nio.file.Path candidate : normalized) {
			boolean ancestorOfExisting = false;
			String candidateKey = toComparablePathKey(candidate);
			for (String existingKey : filteredKeys) {
				if (existingKey.equals(candidateKey) || isAncestorPath(candidateKey, existingKey)) {
					ancestorOfExisting = true;
					break;
				}
			}
			if (!ancestorOfExisting) {
				filtered.add(candidate);
				filteredKeys.add(candidateKey);
			}
		}
		return filtered;
	}

	private static String toComparablePathKey(java.nio.file.Path path) {
		String normalized = path.normalize().toString().replace('\\', '/');
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized.toLowerCase();
	}

	private static boolean isAncestorPath(String ancestor, String descendant) {
		if (ancestor.isEmpty() || descendant.isEmpty()) {
			return false;
		}
		if (!descendant.startsWith(ancestor)) {
			return false;
		}
		if (descendant.length() == ancestor.length()) {
			return true;
		}
		return descendant.charAt(ancestor.length()) == '/';
	}

	private static IPath ensureLinkedSourceFolder(IJavaProject project, java.nio.file.Path sourcePath, Set<String> usedSourceNames,
			IProgressMonitor monitor) throws CoreException {
		java.nio.file.Path sourceRoot = toDirectory(sourcePath);
		if (sourceRoot == null) {
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
		folder.createLink(sourceRoot.toUri(), IResource.REPLACE | IResource.ALLOW_MISSING_LOCAL, monitor);
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
