package it.filippor.jdt.bsp.ui.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import it.filippor.jdt.bsp.ui.BspClientPlugin;

public class ShowBspActionableTasksViewHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
		if (page == null) {
			return null;
		}
		try {
			page.showView(BspActionableTasksView.VIEW_ID);
		} catch (PartInitException e) {
			BspClientPlugin.logError("Failed to open BSP actionable tasks view", e);
		}
		return null;
	}
}
