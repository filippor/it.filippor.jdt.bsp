package it.filippor.jdt.bsp.ui.ui.decorators;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.ICommonLabelProvider;
import org.osgi.service.prefs.Preferences;

import it.filippor.jdt.bsp.core.nature.BspModuleProjectNature;
import it.filippor.jdt.bsp.core.nature.BspRootProjectNature;
import it.filippor.jdt.bsp.core.nature.BspSourceSetProjectNature;

public class BspSourceSetLabelProvider extends LabelProvider implements ICommonLabelProvider, IStyledLabelProvider {

	@Override
	public String getText(Object element) {
		StyledString styled = getStyledText(element);
		return styled != null ? styled.getString() : super.getText(element);
	}

	@Override
	public StyledString getStyledText(Object element) {
		if (!(element instanceof IProject project)) {
			return null;
		}
		if (isSourceSetProject(project)) {
			return styledSourceSet(project);
		}
		if (isModuleProject(project)) {
			return styledModule(project);
		}
		return null;
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
		if (anElement instanceof IProject project && (isSourceSetProject(project) || isModuleProject(project))) {
			return project.getName();
		}
		return null;
	}

	private static StyledString styledSourceSet(IProject project) {
		String logicalName = preferenceValue(project, BspRootProjectNature.KEY_BUILD_TARGET_NAME);
		if (logicalName == null || logicalName.isBlank()) {
			return null;
		}
		String base = lastSegment(logicalName, ':');
		String hierarchy = allButLastSegment(logicalName, ':');
		return withDimmedHierarchy(base, hierarchy);
	}

	private static StyledString styledModule(IProject project) {
		String logicalName = preferenceValue(project, BspRootProjectNature.KEY_CONTAINER_NAME);
		if (logicalName == null || logicalName.isBlank()) {
			return null;
		}
		String base = lastSegment(logicalName, ':');
		String hierarchy = allButLastSegment(logicalName, ':');
		return withDimmedHierarchy(base, hierarchy);
	}

	private static StyledString withDimmedHierarchy(String base, String hierarchy) {
		StyledString styled = new StyledString(base);
		if (hierarchy != null && !hierarchy.isBlank()) {
			styled.append(" (" + hierarchy + ")", StyledString.QUALIFIER_STYLER);
		}
		return styled;
	}

	private static String preferenceValue(IProject project, String preferenceKey) {
		try {
			Preferences node = new ProjectScope(project).getNode(BspRootProjectNature.SETTINGS_NODE);
			String value = node.get(preferenceKey, null);
			return value == null || value.isBlank() ? null : value;
		} catch (Exception e) {
			return null;
		}
	}

	private static String lastSegment(String value, char separator) {
		if (value == null || value.isBlank()) {
			return "";
		}
		int marker = value.lastIndexOf(separator);
		return marker >= 0 && marker + 1 < value.length() ? value.substring(marker + 1) : value;
	}

	private static String allButLastSegment(String value, char separator) {
		if (value == null || value.isBlank()) {
			return "";
		}
		int marker = value.lastIndexOf(separator);
		return marker > 0 ? value.substring(0, marker) : "";
	}

	private static boolean isSourceSetProject(IProject project) {
		try {
			if (project.hasNature(BspSourceSetProjectNature.NATURE_ID)) {
				return true;
			}
		} catch (Exception e) {
			// Fallback below.
		}
		String logicalName = preferenceValue(project, BspRootProjectNature.KEY_BUILD_TARGET_NAME);
		if (logicalName == null) {
			return false;
		}
		return logicalName.chars().filter(ch -> ch == ':').count() >= 2;
	}

	private static boolean isModuleProject(IProject project) {
		try {
			if (project.hasNature(BspModuleProjectNature.NATURE_ID)) {
				return true;
			}
		} catch (Exception e) {
			// Fallback below.
		}
		String logicalName = preferenceValue(project, BspRootProjectNature.KEY_CONTAINER_NAME);
		if (logicalName == null) {
			return false;
		}
		return logicalName.indexOf(':') > 0;
	}
}