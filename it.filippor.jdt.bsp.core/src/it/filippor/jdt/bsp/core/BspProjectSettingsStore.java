package it.filippor.jdt.bsp.core;

import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;

/**
 * Shared project-scoped BSP settings access for UI and headless callers.
 */
public final class BspProjectSettingsStore {

    private BspProjectSettingsStore() {
        // Utility class
    }

    public static Preferences node(IProject project) {
        return new ProjectScope(project).getNode(BspRootProjectNature.SETTINGS_NODE);
    }

    private static String readWithLegacyFallback(IProject project, String key, String fallback) {
        Preferences current = node(project);
        String defaultValue = fallback == null ? "" : fallback;
        return current.get(key, defaultValue);
    }

    public static String readCommandLine(IProject project, String defaultCommandLine) {
        String fallback = defaultCommandLine == null ? "" : defaultCommandLine;
        String value = readWithLegacyFallback(project, BspRootProjectNature.KEY_BSP_COMMAND_LINE, fallback);
        return value == null ? fallback : value;
    }

    public static void write(IProject project, String commandLine) throws BackingStoreException {
        Preferences node = node(project);
        node.put(BspRootProjectNature.KEY_BSP_COMMAND_LINE, commandLine == null ? "" : commandLine);
        node.flush();
    }
}
