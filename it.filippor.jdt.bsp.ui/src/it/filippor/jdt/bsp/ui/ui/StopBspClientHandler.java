package it.filippor.jdt.bsp.ui.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

import it.filippor.jdt.bsp.ui.BspClientManager;

public class StopBspClientHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		BspClientManager.getInstance().stop();
		return null;
	}
}
