package org.eclipse.jdt.bsp.client.ui;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jdt.bsp.client.BspClientManager;
import org.eclipse.jdt.bsp.client.BspClientPlugin;
import org.eclipse.jdt.bsp.client.BspPreferenceInitializer;
import org.eclipse.ui.IImportWizard;
import org.eclipse.ui.IWorkbench;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

public class GradleBspImportWizard extends Wizard implements IImportWizard {

	private GradleBspImportWizardPage page;

	@Override
	public void init(IWorkbench workbench, IStructuredSelection selection) {
		setWindowTitle("Import Gradle Projects (BSP)");
	}

	@Override
	public void addPages() {
		this.page = new GradleBspImportWizardPage();
		addPage(this.page);
	}

	@Override
	public boolean performFinish() {
		Preferences prefs = InstanceScope.INSTANCE.getNode(BspClientPlugin.PLUGIN_ID);
		prefs.put(BspPreferenceInitializer.PREF_COMMAND_LINE, this.page.getCommandLine());
		prefs.put(BspPreferenceInitializer.PREF_ROOT_DIRECTORIES, this.page.getRootDirectoriesPreferenceValue());
		prefs.put(BspPreferenceInitializer.PREF_COMMAND_MODE, this.page.getCommandMode());
		prefs.put(BspPreferenceInitializer.PREF_GRADLE_EXECUTABLE_PATH, this.page.getGradleExecutablePath());
		try {
			prefs.flush();
		} catch (BackingStoreException e) {
			BspClientPlugin.logError("Failed to save BSP import preferences", e);
			MessageDialog.openError(getShell(), "BSP Import",
					"Failed to save BSP preferences: " + e.getMessage());
			return false;
		}
		if (this.page.shouldStartClient()) {
			BspClientManager.getInstance().stop();
			BspClientManager.getInstance().startFromPreferences();
		}
		return true;
	}
}
