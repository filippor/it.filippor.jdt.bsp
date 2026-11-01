package it.filippor.jdt.bsp.ui.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;

import it.filippor.jdt.bsp.core.BspModuleProjectNature;
import it.filippor.jdt.bsp.core.BspSourceSetProjectNature;

public class BspProjectLabelDecorator extends LabelProvider implements ILightweightLabelDecorator {

	@Override
	public void decorate(Object element, IDecoration decoration) {
		IProject project = asProject(element);
		if (project == null || project.getLocation() == null) {
			return;
		}
		try {
			if (project.hasNature(BspModuleProjectNature.NATURE_ID)) {
				decoration.addSuffix(" [" + project.getLocation().lastSegment() + "]");
				return;
			}
			if (project.hasNature(BspSourceSetProjectNature.NATURE_ID)) {
				decoration.addSuffix(" [" + project.getLocation().lastSegment() + "]");
			}
		} catch (Exception e) {
			// Best-effort decoration only.
		}
	}

	private static IProject asProject(Object element) {
		if (element instanceof IProject project) {
			return project;
		}
		if (element instanceof IAdaptable adaptable) {
			return adaptable.getAdapter(IProject.class);
		}
		return null;
	}
}
