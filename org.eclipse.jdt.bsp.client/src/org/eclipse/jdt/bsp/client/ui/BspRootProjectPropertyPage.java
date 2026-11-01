package org.eclipse.jdt.bsp.client.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jdt.bsp.client.BspClientPlugin;
import org.eclipse.jdt.bsp.core.BspProjectSettingsStore;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PropertyPage;
import org.osgi.service.prefs.BackingStoreException;

public class BspRootProjectPropertyPage extends PropertyPage {

	private Text rootFolderText;
	private Text commandLineText;

	@Override
	protected Control createContents(Composite parent) {
		Composite container = new Composite(parent, SWT.NONE);
		container.setLayout(new GridLayout(3, false));
		container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		createRootFolderField(container);
		createCommandField(container);
		loadSettings();
		validate();
		return container;
	}

	private void createRootFolderField(Composite parent) {
		Label rootLabel = new Label(parent, SWT.NONE);
		rootLabel.setText("BSP root folder:");

		this.rootFolderText = new Text(parent, SWT.BORDER);
		this.rootFolderText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		this.rootFolderText.addModifyListener(event -> validate());

		Button browseButton = new Button(parent, SWT.PUSH);
		browseButton.setText("Browse...");
		browseButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
		browseButton.addListener(SWT.Selection, event -> browseRootFolder());
	}

	private void createCommandField(Composite parent) {
		Label commandLabel = new Label(parent, SWT.NONE);
		commandLabel.setText("BSP command line:");
		commandLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));

		this.commandLineText = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
		GridData commandData = new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1);
		commandData.heightHint = 60;
		this.commandLineText.setLayoutData(commandData);
		this.commandLineText.addModifyListener(event -> validate());
	}

	private void browseRootFolder() {
		DirectoryDialog dialog = new DirectoryDialog(getShell());
		dialog.setMessage("Select the BSP root folder for this project");
		String selected = dialog.open();
		if (selected != null && !selected.isBlank()) {
			this.rootFolderText.setText(selected);
		}
	}

	private void loadSettings() {
		IProject project = getSelectedProject();
		if (project == null) {
			setErrorMessage("No project is selected.");
			setValid(false);
			return;
		}
		String rootFolder = BspProjectSettingsStore.readRootFolder(project);
		String commandLine = BspProjectSettingsStore.readCommandLine(project, "");
		this.rootFolderText.setText(rootFolder == null ? "" : rootFolder);
		this.commandLineText.setText(commandLine == null ? "" : commandLine);
	}

	private void validate() {
		String rootFolder = this.rootFolderText.getText().trim();
		if (rootFolder.isBlank()) {
			setErrorMessage("BSP root folder cannot be empty.");
			setValid(false);
			return;
		}
		if (!Files.isDirectory(Path.of(rootFolder))) {
			setErrorMessage("BSP root folder does not exist: " + rootFolder);
			setValid(false);
			return;
		}
		if (this.commandLineText.getText().trim().isBlank()) {
			setErrorMessage("BSP command line cannot be empty.");
			setValid(false);
			return;
		}
		setErrorMessage(null);
		setValid(true);
	}

	@Override
	public boolean performOk() {
		if (!isValid()) {
			return false;
		}
		IProject project = getSelectedProject();
		if (project == null) {
			return false;
		}
		try {
			BspProjectSettingsStore.write(project, this.rootFolderText.getText().trim(),
					this.commandLineText.getText().trim());
		} catch (BackingStoreException e) {
			BspClientPlugin.logError("Unable to persist BSP project settings for " + project.getName(), e);
			MessageDialog.openError(getShell(), "BSP", "Unable to save BSP project settings.");
			return false;
		}
		return true;
	}

	@Override
	protected void performDefaults() {
		this.commandLineText.setText("");
		IProject project = getSelectedProject();
		this.rootFolderText.setText(project == null ? "" : project.getLocation().toOSString());
		super.performDefaults();
		validate();
	}

	private IProject getSelectedProject() {
		IAdaptable element = getElement();
		if (element == null) {
			return null;
		}
		return element.getAdapter(IProject.class);
	}
}
