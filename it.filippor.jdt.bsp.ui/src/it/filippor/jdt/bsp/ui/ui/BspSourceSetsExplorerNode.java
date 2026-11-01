package it.filippor.jdt.bsp.ui.ui;

import org.eclipse.core.resources.IProject;

final class BspSourceSetsExplorerNode {

	private final IProject moduleProject;

	BspSourceSetsExplorerNode(IProject moduleProject) {
		this.moduleProject = moduleProject;
	}

	IProject moduleProject() {
		return moduleProject;
	}
}
