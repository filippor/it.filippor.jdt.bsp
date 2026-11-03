package it.filippor.jdt.bsp.ui.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import it.filippor.jdt.bsp.core.BspClientManager;
import it.filippor.jdt.bsp.core.BspPreferenceInitializer;

class GradleBspImportWizardPage extends WizardPage {

	private static final String MODE_WRAPPER = "wrapper";
	private static final String MODE_SYSTEM = "system";
	private static final String MODE_PATH = "path";
	private static final String MODE_CUSTOM = "custom";
	private static final boolean WINDOWS_HOST = System.getProperty("os.name", "").toLowerCase().contains("win");
	private static final String COMMAND_VARIABLE_HINT = String.join(System.lineSeparator(),
			"Variables:",
			"${" + BspPreferenceInitializer.VAR_JAVA_HOME + "} (Java home)",
			"${" + BspPreferenceInitializer.VAR_BSP_PLUGIN_DIR + "} (embedded plugin dir)",
			"${" + BspPreferenceInitializer.VAR_BSP_CLASSPATH + "} (embedded launcher classpath)",
			"%VAR% (environment variable)");

	private Text commandLineText;
	private Text rootDirectoryText;
	private Text executablePathText;
	private Button wrapperModeButton;
	private Button systemModeButton;
	private Button pathModeButton;
	private Button customModeButton;
	private Button browseExecutableButton;
	private Button browseRootButton;
	private Button startClientButton;

	GradleBspImportWizardPage() {
		super("gradleBspImportPage");
		setTitle("BSP Import");
		setDescription("Choose wrapper/system/path with embedded BSP plugin, or provide a custom command line.");
	}

	@Override
	public void createControl(Composite parent) {
		Composite container = new Composite(parent, SWT.NONE);
		container.setLayout(new GridLayout(3, false));

		createRootsField(container);
		createCommandModeField(container);
		createCommandLineField(container);
		createStartOption(container);
		initializeDefaults();
		updateCommandFromMode();
		updateModeControls();
		validatePage();

		setControl(container);
	}

	private void createCommandModeField(Composite parent) {
		Label label = new Label(parent, SWT.NONE);
		label.setText("BSP command mode:");
		label.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));

		Composite modeComposite = new Composite(parent, SWT.NONE);
		modeComposite.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
		modeComposite.setLayout(new GridLayout(2, false));

		this.wrapperModeButton = createModeButton(modeComposite,
				"Gradle wrapper with embedded BSP plugin (" + defaultWrapperCommand() + ")", MODE_WRAPPER);
		this.systemModeButton = createModeButton(modeComposite,
				"System Gradle with embedded BSP plugin (gradle bsp)", MODE_SYSTEM);
		this.pathModeButton = createModeButton(modeComposite,
				"Specific Gradle installation with embedded BSP plugin", MODE_PATH);
		this.customModeButton = createModeButton(modeComposite,
				"Custom command line", MODE_CUSTOM);

		this.executablePathText = new Text(modeComposite, SWT.BORDER);
		this.executablePathText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		this.executablePathText.addModifyListener(event -> {
			if (this.pathModeButton.getSelection()) {
				updateCommandFromMode();
			}
			validatePage();
		});

		this.browseExecutableButton = new Button(modeComposite, SWT.PUSH);
		this.browseExecutableButton.setText("Browse...");
		this.browseExecutableButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
		this.browseExecutableButton.addListener(SWT.Selection, event -> browseExecutable());
	}

	private Button createModeButton(Composite parent, String text, String mode) {
		Button button = new Button(parent, SWT.RADIO);
		button.setText(text);
		button.setData(mode);
		button.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, true, false, 2, 1));
		button.addListener(SWT.Selection, event -> {
			updateModeControls();
			updateCommandFromMode();
			validatePage();
		});
		return button;
	}

	private void createCommandLineField(Composite parent) {
		Label label = new Label(parent, SWT.NONE);
		label.setText("Custom command line:");

		this.commandLineText = new Text(parent, SWT.BORDER);
		this.commandLineText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		this.commandLineText.addModifyListener(event -> validatePage());

		new Label(parent, SWT.NONE);
		Label variableHint = new Label(parent, SWT.WRAP);
		variableHint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
		variableHint.setText(COMMAND_VARIABLE_HINT);
	}

	private void createRootsField(Composite parent) {
		Label label = new Label(parent, SWT.NONE);
		label.setText("BSP root directory:");

		this.rootDirectoryText = new Text(parent, SWT.BORDER);
		this.rootDirectoryText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		this.rootDirectoryText.addModifyListener(event -> validatePage());

		this.browseRootButton = new Button(parent, SWT.PUSH);
		this.browseRootButton.setText("Browse...");
		this.browseRootButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
		this.browseRootButton.addListener(SWT.Selection, event -> chooseRootDirectory());
	}

	private void createStartOption(Composite parent) {
		this.startClientButton = new Button(parent, SWT.CHECK);
		this.startClientButton.setText("Start BSP client after import");
		this.startClientButton.setSelection(true);
		this.startClientButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 3, 1));
	}

	private void initializeDefaults() {
		String mode = readPreference(BspPreferenceInitializer.PREF_COMMAND_MODE, "");
		if (mode == null || mode.isBlank()) {
			mode = System.getProperty("os.name", "").toLowerCase().contains("win") ? MODE_WRAPPER : MODE_SYSTEM;
		}
		this.wrapperModeButton.setSelection(MODE_WRAPPER.equals(mode));
		this.systemModeButton.setSelection(MODE_SYSTEM.equals(mode));
		this.pathModeButton.setSelection(MODE_PATH.equals(mode));
		this.customModeButton.setSelection(MODE_CUSTOM.equals(mode));
		if (!this.wrapperModeButton.getSelection() && !this.systemModeButton.getSelection()
				&& !this.pathModeButton.getSelection() && !this.customModeButton.getSelection()) {
			this.systemModeButton.setSelection(true);
		}

		String executablePath = readPreference(BspPreferenceInitializer.PREF_GRADLE_EXECUTABLE_PATH, "");
		this.executablePathText.setText(executablePath == null ? "" : executablePath);

		String commandLine = readPreference(BspPreferenceInitializer.PREF_COMMAND_LINE,
				BspPreferenceInitializer.DEFAULT_COMMAND_LINE);
		if (commandLine == null || commandLine.isBlank()) {
			commandLine = BspPreferenceInitializer.DEFAULT_COMMAND_LINE;
		}
		this.commandLineText.setText(commandLine);

		String currentRoots = readPreference(BspPreferenceInitializer.PREF_ROOT_DIRECTORIES, "");
		String rootDirectory = ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();
		if (currentRoots != null && !currentRoots.isBlank()) {
			for (String candidate : currentRoots.split("[;\\r\\n]+")) {
				String trimmed = candidate.trim();
				if (!trimmed.isBlank()) {
					rootDirectory = trimmed;
					break;
				}
			}
		}
		this.rootDirectoryText.setText(rootDirectory);
	}

	private void updateModeControls() {
		boolean isCustom = this.customModeButton.getSelection();
		boolean isPath = this.pathModeButton.getSelection();
		this.commandLineText.setEditable(isCustom);
		this.executablePathText.setEnabled(isPath);
		this.browseExecutableButton.setEnabled(isPath);
	}

	private void updateCommandFromMode() {
		if (this.customModeButton.getSelection()) {
			return;
		}
		if (this.wrapperModeButton.getSelection()) {
			this.commandLineText.setText("<embedded-gradle-bsp:wrapper>");
			return;
		}
		if (this.pathModeButton.getSelection()) {
			String executable = this.executablePathText.getText().trim();
			if (!executable.isBlank()) {
				this.commandLineText.setText("<embedded-gradle-bsp:path=" + executable + ">");
			} else {
				this.commandLineText.setText("<embedded-gradle-bsp:path>");
			}
			return;
		}
		this.commandLineText.setText("<embedded-gradle-bsp:system>");
	}


	private static String defaultWrapperCommand() {
		return WINDOWS_HOST ? "cmd /c gradlew.bat bsp" : "./gradlew bsp";
	}

	private void browseExecutable() {
		FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
		dialog.setText("Select Gradle executable");
		dialog.setFilterExtensions(new String[] { "*.bat;*.cmd;*.exe;*", "*" });
		String selected = dialog.open();
		if (selected != null && !selected.isBlank()) {
			this.executablePathText.setText(selected);
		}
	}

	private void chooseRootDirectory() {
		DirectoryDialog dialog = new DirectoryDialog(getShell());
		dialog.setMessage("Select a BSP root directory");
		String path = dialog.open();
		if (path == null || path.isBlank()) {
			return;
		}
		this.rootDirectoryText.setText(path);
		validatePage();
	}

	private void validatePage() {
		String commandLine = getCommandLine();
		if (commandLine.isBlank()) {
			setErrorMessage("BSP command line cannot be empty.");
			setPageComplete(false);
			return;
		}
		if (this.pathModeButton.getSelection()) {
			String executablePath = this.executablePathText.getText().trim();
			if (executablePath.isBlank()) {
				setErrorMessage("Gradle executable path cannot be empty in path mode.");
				setPageComplete(false);
				return;
			}
			Path configuredPath = Path.of(executablePath);
			if (!Files.isRegularFile(configuredPath) && !Files.isDirectory(configuredPath)) {
				setErrorMessage("Gradle installation path does not exist: " + executablePath);
				setPageComplete(false);
				return;
			}
		}
		String root = getRootDirectory();
		if (root.isBlank()) {
			setErrorMessage("A BSP root directory is required.");
			setPageComplete(false);
			return;
		}
		if (!Files.isDirectory(Path.of(root))) {
			setErrorMessage("Directory does not exist: " + root);
			setPageComplete(false);
			return;
		}
		setErrorMessage(null);
		setPageComplete(true);
	}

	String getCommandLine() {
		return this.commandLineText.getText().trim();
	}

	String getRootDirectoriesPreferenceValue() {
		return getRootDirectory();
	}

	String getCommandMode() {
		if (this.wrapperModeButton.getSelection()) {
			return MODE_WRAPPER;
		}
		if (this.pathModeButton.getSelection()) {
			return MODE_PATH;
		}
		if (this.customModeButton.getSelection()) {
			return MODE_CUSTOM;
		}
		return MODE_SYSTEM;
	}

	String getGradleExecutablePath() {
		return this.executablePathText.getText().trim();
	}

	boolean shouldStartClient() {
		return this.startClientButton.getSelection();
	}

	private String getRootDirectory() {
		return this.rootDirectoryText.getText().trim();
	}

	private static String readPreference(String key, String defaultValue) {
		String value = Platform.getPreferencesService().getString(BspClientManager.CORE_PLUGIN_ID, key, defaultValue,
				null);
		return value == null ? defaultValue : value;
	}
}
