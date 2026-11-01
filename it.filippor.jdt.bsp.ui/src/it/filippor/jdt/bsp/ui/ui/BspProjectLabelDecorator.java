package it.filippor.jdt.bsp.ui.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.osgi.service.prefs.Preferences;

import it.filippor.jdt.bsp.core.BspModuleProjectNature;
import it.filippor.jdt.bsp.core.BspRootProjectNature;
import it.filippor.jdt.bsp.core.BspSourceSetProjectNature;

public class BspProjectLabelDecorator extends LabelProvider implements ILabelDecorator {

	@Override
	public String decorateText(String text, Object element) {
		if (!(element instanceof IProject project)) {
			return null;
		}
		try {
			if (project.hasNature(BspModuleProjectNature.NATURE_ID)) {
				String shortName = moduleDisplayName(project, text);
				return shortName.equals(text) ? null : shortName;
			}
			if (project.hasNature(BspSourceSetProjectNature.NATURE_ID)) {
				String shortName = sourceSetDisplayName(project, text);
				return shortName.equals(text) ? null : shortName;
			}
		} catch (Exception e) {
			// Best-effort decoration only.
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
		String persisted = preferenceValue(project, BspRootProjectNature.KEY_MODULE_NAME);
		if (persisted == null || persisted.isBlank()) {
			persisted = persistentProperty(project, BspRootProjectNature.PROPERTY_MODULE_NAME);
		}
		String base = persisted != null && !persisted.isBlank()
				? persisted
				: project.getLocation() != null && project.getLocation().lastSegment() != null
						? project.getLocation().lastSegment()
						: project.getName();
		return base + suffix;
	}

	private static String sourceSetDisplayName(IProject project, String originalText) {
		String suffix = "";
		int suffixStart = originalText == null ? -1 : originalText.indexOf(" [");
		if (suffixStart >= 0) {
			suffix = originalText.substring(suffixStart);
		}
		String persisted = preferenceValue(project, BspRootProjectNature.KEY_SOURCE_SET_NAME);
		if (persisted == null || persisted.isBlank()) {
			persisted = persistentProperty(project, BspRootProjectNature.PROPERTY_SOURCE_SET_NAME);
		}
		String name = persisted != null && !persisted.isBlank() ? persisted : project.getName();
		int marker = name.lastIndexOf('.');
		String base = marker >= 0 && marker + 1 < name.length() ? name.substring(marker + 1) : name;
		return base + suffix;
	}

	private static String preferenceValue(IProject project, String key) {
		try {
			Preferences node = new ProjectScope(project).getNode(BspRootProjectNature.SETTINGS_NODE);
			return node.get(key, null);
		} catch (Exception e) {
			return null;
		}
	}

	private static String persistentProperty(IProject project, org.eclipse.core.runtime.QualifiedName key) {
		try {
			return project.getPersistentProperty(key);
		} catch (CoreException e) {
			return null;
		}
	}

}
