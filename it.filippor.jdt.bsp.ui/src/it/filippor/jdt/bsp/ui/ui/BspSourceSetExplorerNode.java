package it.filippor.jdt.bsp.ui.ui;

import org.eclipse.core.resources.IProject;

final class BspSourceSetExplorerNode {

	private final BspSourceSetsExplorerNode parent;
	private final IProject sourceSetProject;
	private final String label;

	BspSourceSetExplorerNode(BspSourceSetsExplorerNode parent, IProject sourceSetProject, String label) {
		this.parent = parent;
		this.sourceSetProject = sourceSetProject;
		this.label = label;
	}

	BspSourceSetsExplorerNode parent() {
		return parent;
	}

	IProject sourceSetProject() {
		return sourceSetProject;
	}

	String label() {
		return label;
	}
}
