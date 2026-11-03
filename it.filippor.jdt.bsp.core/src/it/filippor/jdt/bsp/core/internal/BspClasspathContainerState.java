package it.filippor.jdt.bsp.core.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.osgi.service.prefs.Preferences;

import it.filippor.jdt.bsp.core.nature.BspRootProjectNature;

final class BspClasspathContainerState {

    private static final String KEY_ENTRIES = "bspClasspathEntries";

    private BspClasspathContainerState() {
    }

    static void write(IJavaProject project, List<IClasspathEntry> entries) {
        if (project == null || project.getProject() == null) {
            return;
        }
        Preferences node = new ProjectScope(project.getProject()).getNode(BspRootProjectNature.SETTINGS_NODE);
        if (entries == null || entries.isEmpty()) {
            node.remove(KEY_ENTRIES);
            flush(node);
            return;
        }
        List<String> encoded = new ArrayList<>(entries.size());
        for (IClasspathEntry entry : entries) {
            String line = serialize(entry);
            if (line != null) {
                encoded.add(line);
            }
        }
        node.put(KEY_ENTRIES, String.join("\n", encoded));
        flush(node);
    }

    static IClasspathEntry[] read(IJavaProject project) {
        if (project == null || project.getProject() == null) {
            return new IClasspathEntry[0];
        }
        Preferences node = new ProjectScope(project.getProject()).getNode(BspRootProjectNature.SETTINGS_NODE);
        String raw = node.get(KEY_ENTRIES, "");
        if (raw == null || raw.isBlank()) {
            return new IClasspathEntry[0];
        }
        List<IClasspathEntry> entries = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            IClasspathEntry entry = deserialize(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries.toArray(IClasspathEntry[]::new);
    }

    private static String serialize(IClasspathEntry entry) {
        if (entry == null || entry.getPath() == null) {
            return null;
        }
        int kind = entry.getEntryKind();
        if (kind != IClasspathEntry.CPE_LIBRARY && kind != IClasspathEntry.CPE_PROJECT
                && kind != IClasspathEntry.CPE_CONTAINER) {
            return null;
        }
        String path = encode(entry.getPath().toPortableString());
        String source = encode(toPortableOrEmpty(entry.getSourceAttachmentPath()));
        String root = encode(toPortableOrEmpty(entry.getSourceAttachmentRootPath()));
        return kind + "\t" + path + "\t" + source + "\t" + root;
    }

    private static IClasspathEntry deserialize(String encodedLine) {
        if (encodedLine == null || encodedLine.isBlank()) {
            return null;
        }
        String[] parts = encodedLine.split("\\t", -1);
        if (parts.length < 2) {
            return null;
        }
        int kind;
        try {
            kind = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        IPath path = toPath(decode(parts[1]));
        IPath source = parts.length > 2 ? toPath(decode(parts[2])) : null;
        IPath root = parts.length > 3 ? toPath(decode(parts[3])) : null;
        if (path == null) {
            return null;
        }
        return switch (kind) {
            case IClasspathEntry.CPE_LIBRARY -> JavaCore.newLibraryEntry(path, source, root);
            case IClasspathEntry.CPE_PROJECT -> JavaCore.newProjectEntry(path);
            case IClasspathEntry.CPE_CONTAINER -> JavaCore.newContainerEntry(path);
            default -> null;
        };
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static IPath toPath(String portable) {
        if (portable == null || portable.isBlank()) {
            return null;
        }
        return org.eclipse.core.runtime.Path.fromPortableString(portable);
    }

    private static String toPortableOrEmpty(IPath path) {
        return path == null ? "" : path.toPortableString();
    }

    private static void flush(Preferences node) {
        try {
            node.flush();
        } catch (Exception e) {
            // Best effort persistence.
        }
    }
}
