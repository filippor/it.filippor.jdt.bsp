package org.eclipse.jdt.bsp.client;

import org.eclipse.ui.IStartup;

public class BspEarlyStartup implements IStartup {

	@Override
	public void earlyStartup() {
		// Keep startup side effects minimal. Real connection starts from command.
		BspClientPlugin.logInfo("BSP client plug-in loaded");
	}
}
