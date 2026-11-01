package org.eclipse.jdt.bsp.client;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class BspClientPlugin extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "org.eclipse.jdt.bsp.client";

	private static BspClientPlugin plugin;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		try {
			BspClientManager.getInstance().stop();
		} finally {
			plugin = null;
			super.stop(context);
		}
	}

	public static BspClientPlugin getDefault() {
		return plugin;
	}

	public static void logInfo(String message) {
		if (plugin != null) {
			plugin.getLog().log(new Status(IStatus.INFO, PLUGIN_ID, message));
		}
	}

	public static void logError(String message, Throwable throwable) {
		if (plugin != null) {
			plugin.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, message, throwable));
		}
	}
}
