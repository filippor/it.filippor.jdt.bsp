package it.filippor.jdt.bsp.ui.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.part.ViewPart;

import it.filippor.jdt.bsp.ui.BspActionableTaskItem;
import it.filippor.jdt.bsp.ui.BspClientManager;
import it.filippor.jdt.bsp.ui.BspClientPlugin;

public class BspActionableTasksView extends ViewPart {

	public static final String VIEW_ID = "it.filippor.jdt.bsp.ui.views.actionableTasks";

	private TreeViewer viewer;
	private List<BspActionableTaskItem> loadedItems = List.of();
	private boolean exposedOnly = true;

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new FillLayout());
		this.viewer = new TreeViewer(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
		this.viewer.setContentProvider(new ITreeContentProvider() {
			@Override
			public Object[] getElements(Object inputElement) {
				if (inputElement instanceof List<?> roots) {
					return roots.toArray();
				}
				return new Object[0];
			}

			@Override
			public Object[] getChildren(Object parentElement) {
				if (parentElement instanceof RootNode rootNode) {
					return rootNode.targets().toArray();
				}
				if (parentElement instanceof TargetNode targetNode) {
					return targetNode.tasks().toArray();
				}
				return new Object[0];
			}

			@Override
			public Object getParent(Object element) {
				if (element instanceof TargetNode targetNode) {
					return targetNode.parent();
				}
				if (element instanceof TaskNode taskNode) {
					return taskNode.parent();
				}
				return null;
			}

			@Override
			public boolean hasChildren(Object element) {
				return getChildren(element).length > 0;
			}
		});

		Tree tree = this.viewer.getTree();
		tree.setHeaderVisible(true);
		tree.setLinesVisible(true);

		createColumn("Element", 420, this::elementLabel);
		createColumn("Type", 110, this::elementType);
		createColumn("Exposed", 90, this::elementExposed);
		createColumn("Build Target URI", 520, this::elementTargetUri);

		Action refreshAction = new Action("Refresh") {
			@Override
			public void run() {
				refreshTasks();
			}
		};
		refreshAction.setToolTipText("Refresh actionable BSP tasks");
		getViewSite().getActionBars().getToolBarManager().add(refreshAction);

		Action exposedOnlyAction = new Action("Exposed Only", IAction.AS_CHECK_BOX) {
			@Override
			public void run() {
				exposedOnly = isChecked();
				rebuildTreeInput();
			}
		};
		exposedOnlyAction.setChecked(true);
		exposedOnlyAction.setToolTipText("Show only tasks exposed by BSP capabilities");
		getViewSite().getActionBars().getToolBarManager().add(exposedOnlyAction);

		Action runTaskAction = new Action("Run Task") {
			@Override
			public void run() {
				runSelectedTask(false);
			}
		};
		runTaskAction.setToolTipText("Run selected BSP task");
		getViewSite().getActionBars().getToolBarManager().add(runTaskAction);

		Action debugTaskAction = new Action("Debug Task") {
			@Override
			public void run() {
				runSelectedTask(true);
			}
		};
		debugTaskAction.setToolTipText("Debug selected BSP task (if supported by BSP server)");
		getViewSite().getActionBars().getToolBarManager().add(debugTaskAction);

		MenuManager menuManager = new MenuManager();
		menuManager.add(runTaskAction);
		menuManager.add(debugTaskAction);
		this.viewer.getControl().setMenu(menuManager.createContextMenu(this.viewer.getControl()));

		this.viewer.addDoubleClickListener(event -> runSelectedTask(false));

		refreshTasks();
	}

	private void createColumn(String title, int width, java.util.function.Function<Object, String> valueProvider) {
		TreeViewerColumn column = new TreeViewerColumn(this.viewer, SWT.LEFT);
		column.getColumn().setText(title);
		column.getColumn().setWidth(width);
		column.getColumn().setResizable(true);
		column.getColumn().setMoveable(true);
		column.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				return valueProvider.apply(element);
			}
		});
	}

	private void refreshTasks() {
		Job.create("Load BSP actionable tasks", monitor -> {
			List<BspActionableTaskItem> items = BspClientManager.getInstance().listActionableTasks();
			Display.getDefault().asyncExec(() -> {
				if (this.viewer == null || this.viewer.getControl().isDisposed()) {
					return;
				}
				this.loadedItems = items;
				rebuildTreeInput();
			});
			return new Status(IStatus.OK, BspClientPlugin.PLUGIN_ID, "Actionable tasks refreshed");
		}).schedule();
	}

	private void rebuildTreeInput() {
		List<RootNode> roots = toTree(this.loadedItems, this.exposedOnly);
		this.viewer.setInput(roots);
		this.viewer.expandToLevel(2);
	}

	private void runSelectedTask(boolean debug) {
		if (this.viewer == null || this.viewer.getControl().isDisposed()) {
			return;
		}
		Object selected = ((IStructuredSelection) this.viewer.getSelection()).getFirstElement();
		if (!(selected instanceof TaskNode taskNode)) {
			MessageDialog.openInformation(getSite().getShell(), "BSP Task",
					"Select a leaf task node (package/run/test) to execute.");
			return;
		}
		BspActionableTaskItem item = taskNode.item();
		if (!item.exposed()) {
			MessageDialog.openInformation(getSite().getShell(), "BSP Task",
					"The selected task is not exposed by this BSP server for the target.");
			return;
		}
		String mode = debug ? "debug" : "run";
		Job.create("BSP " + mode + " " + item.task(), monitor -> {
			IStatus status = BspClientManager.getInstance().executeTask(item.root(), item.buildTargetId(), item.task(), debug,
					monitor);
			Display.getDefault().asyncExec(() -> {
				if (this.viewer == null || this.viewer.getControl().isDisposed()) {
					return;
				}
				if (status.getSeverity() == IStatus.CANCEL) {
					MessageDialog.openInformation(getSite().getShell(), "BSP Task", status.getMessage());
				} else if (status.isOK()) {
					MessageDialog.openInformation(getSite().getShell(), "BSP Task", status.getMessage());
				} else {
					MessageDialog.openError(getSite().getShell(), "BSP Task Failed", status.getMessage());
				}
			});
			return status;
		}).schedule();
	}

	private static List<RootNode> toTree(List<BspActionableTaskItem> items, boolean exposedOnly) {
		Map<String, RootBuilder> roots = new LinkedHashMap<>();
		for (BspActionableTaskItem item : items) {
			if (exposedOnly && !item.exposed()) {
				continue;
			}
			RootBuilder rootBuilder = roots.computeIfAbsent(item.root(), RootBuilder::new);
			rootBuilder.add(item);
		}
		List<RootNode> result = new ArrayList<>();
		for (RootBuilder rootBuilder : roots.values()) {
			result.add(rootBuilder.build());
		}
		return result;
	}

	private String elementLabel(Object element) {
		if (element instanceof RootNode rootNode) {
			return rootNode.label();
		}
		if (element instanceof TargetNode targetNode) {
			return targetNode.label();
		}
		if (element instanceof TaskNode taskNode) {
			return taskNode.item().task();
		}
		return "";
	}

	private String elementType(Object element) {
		if (element instanceof RootNode) {
			return "root";
		}
		if (element instanceof TargetNode) {
			return "target";
		}
		if (element instanceof TaskNode) {
			return "task";
		}
		return "";
	}

	private String elementExposed(Object element) {
		if (element instanceof TaskNode taskNode) {
			return taskNode.item().exposed() ? "yes" : "no";
		}
		return "";
	}

	private String elementTargetUri(Object element) {
		if (element instanceof TargetNode targetNode) {
			return targetNode.buildTargetId();
		}
		if (element instanceof TaskNode taskNode) {
			return taskNode.item().buildTargetId();
		}
		return "";
	}

	private static final class RootBuilder {
		private final String root;
		private final Map<String, TargetBuilder> targets = new LinkedHashMap<>();

		private RootBuilder(String root) {
			this.root = root;
		}

		private void add(BspActionableTaskItem item) {
			String key = item.buildTargetId() + "::" + item.buildTarget();
			targets.computeIfAbsent(key, unused -> new TargetBuilder(item.buildTarget(), item.buildTargetId())).add(item);
		}

		private RootNode build() {
			List<TargetNode> targetNodes = new ArrayList<>();
			RootNode rootNode = new RootNode(this.root, targetNodes);
			for (TargetBuilder builder : this.targets.values()) {
				targetNodes.add(builder.build(rootNode));
			}
			return rootNode;
		}
	}

	private static final class TargetBuilder {
		private final String label;
		private final String buildTargetId;
		private final List<BspActionableTaskItem> items = new ArrayList<>();

		private TargetBuilder(String label, String buildTargetId) {
			this.label = label;
			this.buildTargetId = buildTargetId;
		}

		private void add(BspActionableTaskItem item) {
			this.items.add(item);
		}

		private TargetNode build(RootNode parent) {
			List<TaskNode> tasks = new ArrayList<>();
			TargetNode node = new TargetNode(parent, this.label, this.buildTargetId, tasks);
			for (BspActionableTaskItem item : this.items) {
				tasks.add(new TaskNode(node, item));
			}
			return node;
		}
	}

	// Keep these as plain classes (not records): parent/children references are cyclic,
	// and record-generated equals/hashCode would recurse (Root -> Target -> Root) and overflow.
	private static final class RootNode {
		private final String label;
		private final List<TargetNode> targets;

		private RootNode(String label, List<TargetNode> targets) {
			this.label = label;
			this.targets = targets;
		}

		private String label() {
			return this.label;
		}

		private List<TargetNode> targets() {
			return this.targets;
		}
	}

	private static final class TargetNode {
		private final RootNode parent;
		private final String label;
		private final String buildTargetId;
		private final List<TaskNode> tasks;

		private TargetNode(RootNode parent, String label, String buildTargetId, List<TaskNode> tasks) {
			this.parent = parent;
			this.label = label;
			this.buildTargetId = buildTargetId;
			this.tasks = tasks;
		}

		private RootNode parent() {
			return this.parent;
		}

		private String label() {
			return this.label;
		}

		private String buildTargetId() {
			return this.buildTargetId;
		}

		private List<TaskNode> tasks() {
			return this.tasks;
		}
	}

	private static final class TaskNode {
		private final TargetNode parent;
		private final BspActionableTaskItem item;

		private TaskNode(TargetNode parent, BspActionableTaskItem item) {
			this.parent = parent;
			this.item = item;
		}

		private TargetNode parent() {
			return this.parent;
		}

		private BspActionableTaskItem item() {
			return this.item;
		}
	}

	@Override
	public void setFocus() {
		if (this.viewer != null && this.viewer.getControl() != null && !this.viewer.getControl().isDisposed()) {
			this.viewer.getControl().setFocus();
		}
	}
}
