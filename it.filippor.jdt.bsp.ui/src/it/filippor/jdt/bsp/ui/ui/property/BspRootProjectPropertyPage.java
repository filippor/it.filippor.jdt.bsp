package it.filippor.jdt.bsp.ui.ui.property;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PropertyPage;
import org.osgi.service.prefs.BackingStoreException;

import it.filippor.jdt.bsp.core.BspPreferenceInitializer;
import it.filippor.jdt.bsp.core.BspProjectSettingsStore;
import it.filippor.jdt.bsp.ui.BspClientPlugin;

public class BspRootProjectPropertyPage extends PropertyPage {
	private static final String COMMAND_VARIABLE_HINT = String.join(System.lineSeparator(),
			"Variables:",
			"${" + BspPreferenceInitializer.VAR_JAVA_HOME + "} (Java home)",
			"${" + BspPreferenceInitializer.VAR_BSP_PLUGIN_DIR + "} (embedded plugin dir)",
			"${" + BspPreferenceInitializer.VAR_BSP_CLASSPATH + "} (embedded launcher classpath)",
			"%VAR% (environment variable)");

	private Text commandLineText;

	@Override
	protected Control createContents(Composite parent) {
		Composite container = new Composite(parent, SWT.NONE);
		container.setLayout(new GridLayout(2, false));
		container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		createProjectLocationField(container);
		createCommandField(container);
		loadSettings();
		validate();
		return container;
	}

	private void createProjectLocationField(Composite parent) {
		Label rootLabel = new Label(parent, SWT.NONE);
		rootLabel.setText("BSP root folder:");

		Label value = new Label(parent, SWT.WRAP);
		value.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		IProject project = getSelectedProject();
		IPath location = project == null ? null : project.getLocation();
		value.setText(location == null ? "(project location unavailable)" : location.toOSString());
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

		Label variablesLabel = new Label(parent, SWT.WRAP);
		variablesLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
		variablesLabel.setText(COMMAND_VARIABLE_HINT);
	}

	private void loadSettings() {
		IProject project = getSelectedProject();
		if (project == null) {
			setErrorMessage("No project is selected.");
			setValid(false);
			return;
		}
		String commandLine = BspProjectSettingsStore.readCommandLine(project, "");
		this.commandLineText.setText(commandLine == null ? "" : commandLine);
	}

	private void validate() {
		IProject project = getSelectedProject();
		if (project == null || project.getLocation() == null) {
			setErrorMessage("Project location is unavailable.");
			setValid(false);
			return;
		}
		if (!Files.isDirectory(Path.of(project.getLocation().toOSString()))) {
			setErrorMessage("Project location does not exist: " + project.getLocation().toOSString());
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
			BspProjectSettingsStore.write(project, this.commandLineText.getText().trim());
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
