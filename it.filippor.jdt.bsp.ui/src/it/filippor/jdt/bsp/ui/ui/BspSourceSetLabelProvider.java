package it.filippor.jdt.bsp.ui.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.navigator.ICommonLabelProvider;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;

import it.filippor.jdt.bsp.core.BspSourceSetProjectNature;

public class BspSourceSetLabelProvider extends LabelProvider implements ICommonLabelProvider {

	@Override
	public String getText(Object element) {
		if (element instanceof IProject project && isSourceSetProject(project)) {
			String name = project.getName();
			int marker = name.lastIndexOf('.');
			return marker >= 0 && marker + 1 < name.length() ? name.substring(marker + 1) : name;
		}
		return super.getText(element);
	}

	@Override
	public void init(ICommonContentExtensionSite aConfig) {
		// No initialization required.
	}

	@Override
	public void restoreState(IMemento aMemento) {
		// No persisted state.
	}

	@Override
	public void saveState(IMemento aMemento) {
		// No persisted state.
	}

	@Override
	public String getDescription(Object anElement) {
		if (anElement instanceof IProject project && isSourceSetProject(project)) {
			return project.getName();
		}
		return null;
	}

	private static boolean isSourceSetProject(IProject project) {
		try {
			if (project.hasNature(BspSourceSetProjectNature.NATURE_ID)) {
				return true;
			}
		} catch (Exception e) {
			// Fallback below.
		}
		return project.getName().contains(".target.");
	}
}
