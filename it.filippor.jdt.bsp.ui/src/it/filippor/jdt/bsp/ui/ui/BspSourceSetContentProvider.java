package it.filippor.jdt.bsp.ui.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.ICommonContentProvider;
import org.osgi.service.prefs.Preferences;

import it.filippor.jdt.bsp.core.BspModuleProjectNature;
import it.filippor.jdt.bsp.core.BspRootProjectNature;
import it.filippor.jdt.bsp.core.BspSourceSetProjectNature;

public class BspSourceSetContentProvider implements ICommonContentProvider {

	@Override
	public Object[] getChildren(Object parentElement) {
		if (parentElement instanceof IProject moduleProject) {
			return moduleHasSourceSets(moduleProject)
					? new Object[] { new BspSourceSetsExplorerNode(moduleProject) }
					: new Object[0];
		}
		if (!(parentElement instanceof BspSourceSetsExplorerNode container)) {
			return new Object[0];
		}
		IProject moduleProject = container.moduleProject();
		List<IProject> nodes = new ArrayList<>();
		for (IProject candidate : referencedProjects(moduleProject)) {
			if (!candidate.exists() || !candidate.isOpen()) {
				continue;
			}
			if (!isSourceSetProject(candidate)) {
				continue;
			}
			nodes.add(candidate);
		}
		nodes.sort(Comparator.comparing(BspSourceSetContentProvider::sourceSetLabel));
		return nodes.toArray(Object[]::new);
	}

	@Override
	public Object getParent(Object element) {
		if (element instanceof BspSourceSetsExplorerNode container) {
			return container.moduleProject();
		}
		if (element instanceof IProject sourceSetProject && isSourceSetProject(sourceSetProject)) {
			IProject module = findModuleProject(sourceSetProject);
			if (module != null) {
				return new BspSourceSetsExplorerNode(module);
			}
		}
		return null;
	}

	@Override
	public boolean hasChildren(Object element) {
		return getChildren(element).length > 0;
	}

	@Override
	public Object[] getElements(Object inputElement) {
		return new Object[0];
	}

	@Override
	public void dispose() {
		// No state to dispose.
	}

	@Override
	public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
		// No state to update.
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

	private static boolean hasNature(IProject project, String natureId) {
		try {
			return project.hasNature(natureId);
		} catch (Exception e) {
			return false;
		}
	}

	private static boolean moduleHasSourceSets(IProject moduleProject) {
		if (moduleProject == null || !moduleProject.exists() || !moduleProject.isOpen()) {
			return false;
		}
		if (!isModuleProject(moduleProject)) {
			return false;
		}
		for (IProject candidate : referencedProjects(moduleProject)) {
			if (candidate.exists() && candidate.isOpen() && isSourceSetProject(candidate)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isModuleProject(IProject project) {
		if (hasNature(project, BspModuleProjectNature.NATURE_ID)) {
			return true;
		}
		return project.getName().contains(".container.");
	}

	private static boolean isSourceSetProject(IProject project) {
		if (hasNature(project, BspSourceSetProjectNature.NATURE_ID)) {
			return true;
		}
		return project.getName().contains(".target.");
	}

	private static IProject[] referencedProjects(IProject project) {
		try {
			return project.getDescription().getReferencedProjects();
		} catch (CoreException e) {
			return new IProject[0];
		}
	}

	private static String sourceSetLabel(IProject sourceSetProject) {
		try {
			Preferences node = new ProjectScope(sourceSetProject).getNode(BspRootProjectNature.SETTINGS_NODE);
			String persisted = node.get(BspRootProjectNature.KEY_BUILD_TARGET_NAME, null);
			if ((persisted == null || persisted.isBlank())) {
				persisted = sourceSetProject.getPersistentProperty(BspRootProjectNature.PROPERTY_BUILD_TARGET_NAME);
			}
			if (persisted != null && !persisted.isBlank()) {
				return persisted;
			}
		} catch (CoreException e) {
			// fallback below
		}
		if (sourceSetProject.getLocation() != null && sourceSetProject.getLocation().lastSegment() != null) {
			return sourceSetProject.getLocation().lastSegment();
		}
		String name = sourceSetProject.getName();
		int marker = name.lastIndexOf(".target.");
		return marker >= 0 ? name.substring(marker + ".target.".length()) : name;
	}

	private static IProject findModuleProject(IProject sourceSetProject) {
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!project.exists() || !project.isOpen() || !isModuleProject(project)) {
				continue;
			}
			for (IProject referenced : referencedProjects(project)) {
				if (sourceSetProject.equals(referenced)) {
					return project;
				}
			}
		}
		return null;
	}
}
