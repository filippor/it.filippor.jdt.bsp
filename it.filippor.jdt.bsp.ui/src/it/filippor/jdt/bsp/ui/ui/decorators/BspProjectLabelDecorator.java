package it.filippor.jdt.bsp.ui.ui.decorators;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.osgi.service.prefs.Preferences;

import it.filippor.jdt.bsp.core.nature.BspModuleProjectNature;
import it.filippor.jdt.bsp.core.nature.BspRootProjectNature;
import it.filippor.jdt.bsp.core.nature.BspSourceSetProjectNature;

public class BspProjectLabelDecorator extends LabelProvider implements ILabelDecorator {

	@Override
	public String decorateText(String text, Object element) {
		IProject project = resolveProject(element);
		if (project == null) {
			return null;
		}
		try {
			if (isModuleProject(project)) {
				String shortName = moduleDisplayName(project, text);
				return shortName.equals(text) ? null : shortName;
			}
			if (isSourceSetProject(project)) {
				String shortName = sourceSetDisplayName(project, text);
				return shortName.equals(text) ? null : shortName;
			}
		} catch (Exception e) {
			// Best-effort decoration only.
		}
		return null;
	}

	private static IProject resolveProject(Object element) {
		if (element instanceof IProject project) {
			return project;
		}
		if (element instanceof IJavaProject javaProject) {
			return javaProject.getProject();
		}
		if (element instanceof IAdaptable adaptable) {
			return adaptable.getAdapter(IProject.class);
		}
		return null;
	}

	@Override
	public Image decorateImage(Image image, Object element) {
		return null;
	}

	private static String moduleDisplayName(IProject project, String originalText) {
		String suffix = "";
		int suffixStart = originalText == null ? -1 : originalText.indexOf(" [");
		if (suffixStart >= 0) {
			suffix = originalText.substring(suffixStart);
		}
		String persisted = preferenceValue(project, BspRootProjectNature.KEY_CONTAINER_NAME);
		if (persisted == null || persisted.isBlank()) {
			return originalText;
		}
		String display = shortWithParent(persisted);
		return display + suffix;
	}

	private static String sourceSetDisplayName(IProject project, String originalText) {
		String suffix = "";
		int suffixStart = originalText == null ? -1 : originalText.indexOf(" [");
		if (suffixStart >= 0) {
			suffix = originalText.substring(suffixStart);
		}
		String persisted = preferenceValue(project, BspRootProjectNature.KEY_BUILD_TARGET_NAME);
		if (persisted == null || persisted.isBlank()) {
			return originalText;
		}
		String display = shortWithParent(persisted);
		return display + suffix;
	}

	private static String shortWithParent(String value) {
		if (value == null || value.isBlank()) {
			return value;
		}
		int marker = value.lastIndexOf(':');
		if (marker <= 0 || marker + 1 >= value.length()) {
			return value;
		}
		String base = value.substring(marker + 1);
		String parent = value.substring(0, marker);
		return parent.isBlank() ? base : base + " (" + parent + ")";
	}

	private static boolean isModuleProject(IProject project) {
		try {
			if (project.hasNature(BspModuleProjectNature.NATURE_ID)) {
				return true;
			}
		} catch (CoreException e) {
			// Fallback below.
		}
		String logical = preferenceValue(project, BspRootProjectNature.KEY_CONTAINER_NAME);
		return logical != null && logical.indexOf(':') > 0;
	}

	private static boolean isSourceSetProject(IProject project) {
		try {
			if (project.hasNature(BspSourceSetProjectNature.NATURE_ID)) {
				return true;
			}
		} catch (CoreException e) {
			// Fallback below.
		}
		String logical = preferenceValue(project, BspRootProjectNature.KEY_BUILD_TARGET_NAME);
		return logical != null && logical.chars().filter(ch -> ch == ':').count() >= 2;
	}

	private static String preferenceValue(IProject project, String key) {
		try {
			Preferences node = new ProjectScope(project).getNode(BspRootProjectNature.SETTINGS_NODE);
			return node.get(key, null);
		} catch (Exception e) {
			return null;
		}
	}

}
