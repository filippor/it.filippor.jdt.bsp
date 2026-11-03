package it.filippor.jdt.bsp.ui.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

import it.filippor.jdt.bsp.core.BspClientManager;
import it.filippor.jdt.bsp.core.nature.BspRootProjectNature;

public class RestartBspRootProjectHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IProject project = selectedProject(event);
		if (project == null) {
			return null;
		}
		try {
			if (!project.hasNature(BspRootProjectNature.NATURE_ID)) {
				return null;
			}
		} catch (Exception e) {
			throw new ExecutionException("Unable to resolve selected project nature", e);
		}
		BspClientManager.getInstance().restartFromPreferences();
		return null;
	}

	private static IProject selectedProject(ExecutionEvent event) {
		Object selection = HandlerUtil.getCurrentSelection(event);
		if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty()) {
			return null;
		}
		Object first = structured.getFirstElement();
		if (first instanceof IProject project) {
			return project;
		}
		if (first instanceof org.eclipse.core.runtime.IAdaptable adaptable) {
			return adaptable.getAdapter(IProject.class);
		}
		return null;
	}
}
