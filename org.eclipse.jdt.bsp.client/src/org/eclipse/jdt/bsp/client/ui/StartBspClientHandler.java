package org.eclipse.jdt.bsp.client.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jdt.bsp.client.BspClientManager;

public class StartBspClientHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		BspClientManager.getInstance().startFromPreferences();
		return null;
	}
}
