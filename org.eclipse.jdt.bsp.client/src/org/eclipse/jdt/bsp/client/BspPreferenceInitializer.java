package org.eclipse.jdt.bsp.client;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.osgi.service.prefs.Preferences;

public class BspPreferenceInitializer extends AbstractPreferenceInitializer {

	public static final String PREF_COMMAND_LINE = "commandLine";
	public static final String PREF_ROOT_DIRECTORIES = "rootDirectories";
	public static final String PREF_COMMAND_MODE = "commandMode";
	public static final String PREF_GRADLE_EXECUTABLE_PATH = "gradleExecutablePath";
	public static final String DEFAULT_COMMAND_LINE = "gradle bsp";
	private static final String DEFAULT_ROOT_DIRECTORY = "C:\\Localgit\\my\\build-server-for-gradle";

	@Override
	public void initializeDefaultPreferences() {
		Preferences defaults = DefaultScope.INSTANCE.getNode(BspClientPlugin.PLUGIN_ID);
		defaults.put(PREF_COMMAND_LINE, DEFAULT_COMMAND_LINE);
		defaults.put(PREF_ROOT_DIRECTORIES, DEFAULT_ROOT_DIRECTORY);
		defaults.put(PREF_COMMAND_MODE, "embedded");
		defaults.put(PREF_GRADLE_EXECUTABLE_PATH, "");
	}
}
