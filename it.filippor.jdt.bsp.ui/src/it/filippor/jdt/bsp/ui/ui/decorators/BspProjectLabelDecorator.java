package it.filippor.jdt.bsp.ui.ui.decorators;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
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
		if (project == null && isRequiredProjectWrapper(element) && looksLikeBspGeneratedProjectName(text)) {
			project = resolveProjectFromDisplayedText(text);
		}
		if (project == null) {
			return null;
		}
		try {
			if (isSourceSetProject(project)) {
				String shortName = sourceSetDisplayName(project, text);
				return shortName.equals(text) ? null : shortName;
			}
			if (isModuleProject(project)) {
				String shortName = moduleDisplayName(project, text);
				return shortName.equals(text) ? null : shortName;
			}
		} catch (Exception e) {
			// Best-effort decoration only.
		}
		return null;
	}

	private static boolean isRequiredProjectWrapper(Object element) {
		return element != null && "org.eclipse.jdt.internal.ui.packageview.ClassPathContainer$RequiredProjectWrapper"
			.equals(element.getClass().getName());
	}

	private static IProject resolveProject(Object element) {
		if (element instanceof IProject project) {
			return project;
		}
		if (element instanceof IJavaProject javaProject) {
			return javaProject.getProject();
		}
		if (element instanceof IClasspathEntry classpathEntry) {
			return resolveProjectFromClasspathEntry(classpathEntry);
		}
		if (element instanceof IAdaptable adaptable) {
			IClasspathEntry classpathEntry = adaptable.getAdapter(IClasspathEntry.class);
			if (classpathEntry != null) {
				IProject classpathProject = resolveProjectFromClasspathEntry(classpathEntry);
				if (classpathProject != null) {
					return classpathProject;
				}
			}
			if (isRequiredProjectWrapper(element)) {
	            return adaptable.getAdapter(IProject.class);
	        }
		}

		return null;
	}

	private static IProject resolveProjectFromClasspathEntry(IClasspathEntry classpathEntry) {
		if (classpathEntry == null || classpathEntry.getEntryKind() != IClasspathEntry.CPE_PROJECT) {
			return null;
		}
		IPath path = classpathEntry.getPath();
		if (path == null || path.segmentCount() == 0) {
			return null;
		}
		String projectName = path.segment(0);
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		return project.exists() ? project : null;
	}

	private static boolean looksLikeBspGeneratedProjectName(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		return text.indexOf("__") > 0 && text.indexOf('/') < 0 && text.indexOf(' ') < 0;
	}

	private static IProject resolveProjectFromDisplayedText(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String candidate = text;
		int pathSuffix = candidate.indexOf(" - ");
		if (pathSuffix > 0) {
			candidate = candidate.substring(0, pathSuffix);
		}
		int bracketSuffix = candidate.indexOf(" [");
		if (bracketSuffix > 0) {
			candidate = candidate.substring(0, bracketSuffix);
		}
		candidate = candidate.trim();
		if (candidate.isEmpty()) {
			return null;
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(candidate);
		return project.exists() ? project : null;
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
