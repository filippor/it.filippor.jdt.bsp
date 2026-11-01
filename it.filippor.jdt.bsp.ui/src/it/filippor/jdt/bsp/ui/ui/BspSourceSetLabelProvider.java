package it.filippor.jdt.bsp.ui.ui;

import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.navigator.ICommonLabelProvider;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;

public class BspSourceSetLabelProvider extends LabelProvider implements ICommonLabelProvider {

	@Override
	public String getText(Object element) {
		if (element instanceof BspSourceSetsExplorerNode) {
			return "Source Sets";
		}
		if (element instanceof BspSourceSetExplorerNode node) {
			return node.label();
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
		if (anElement instanceof BspSourceSetsExplorerNode container) {
			return container.moduleProject().getName();
		}
		if (anElement instanceof BspSourceSetExplorerNode node) {
			return node.sourceSetProject().getName();
		}
		return null;
	}
}
