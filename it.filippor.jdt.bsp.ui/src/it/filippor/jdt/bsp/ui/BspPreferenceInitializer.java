package it.filippor.jdt.bsp.ui;

import org.osgi.service.prefs.Preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;

public class BspPreferenceInitializer extends AbstractPreferenceInitializer {

	public static final String PREF_COMMAND_LINE = "commandLine";
	public static final String PREF_ROOT_DIRECTORIES = "rootDirectories";
	public static final String PREF_COMMAND_MODE = "commandMode";
	public static final String PREF_GRADLE_EXECUTABLE_PATH = "gradleExecutablePath";
	public static final String VAR_JAVA_HOME = "JAVA_HOME";
	public static final String VAR_BSP_PLUGIN_DIR = "BSP_GRADLE_EMBEDDED_PLUGIN_DIR";
	public static final String VAR_BSP_CLASSPATH = "BSP_GRADLE_EMBEDDED_CLASSPATH";
	public static final String DEFAULT_COMMAND_LINE = "\"${" + VAR_JAVA_HOME
			+ "}\\bin\\java.exe\" -Dplugin.dir=\"${" + VAR_BSP_PLUGIN_DIR + "}\" -cp \"${"
			+ VAR_BSP_CLASSPATH + "}\" com.microsoft.java.bs.core.Launcher";
	private static final String DEFAULT_ROOT_DIRECTORY = "C:\\Localgit\\my\\build-server-for-gradle";

	@Override
	public void initializeDefaultPreferences() {
		Preferences defaults = DefaultScope.INSTANCE.getNode(BspClientPlugin.PLUGIN_ID);
		defaults.put(PREF_COMMAND_LINE, DEFAULT_COMMAND_LINE);
		defaults.put(PREF_ROOT_DIRECTORIES, DEFAULT_ROOT_DIRECTORY);
		defaults.put(PREF_COMMAND_MODE, "wrapper");
		defaults.put(PREF_GRADLE_EXECUTABLE_PATH, "");
	}
}
