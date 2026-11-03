package it.filippor.jdt.bsp.ui.ui;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.IImportWizard;
import org.eclipse.ui.IWorkbench;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import it.filippor.jdt.bsp.core.BspClientManager;
import it.filippor.jdt.bsp.core.BspPreferenceInitializer;
import it.filippor.jdt.bsp.ui.BspClientPlugin;

public class GradleBspImportWizard extends Wizard implements IImportWizard {

	private GradleBspImportWizardPage page;

	@Override
	public void init(IWorkbench workbench, IStructuredSelection selection) {
		setWindowTitle("BSP Import");
	}

	@Override
	public void addPages() {
		this.page = new GradleBspImportWizardPage();
		addPage(this.page);
	}

	@Override
	public boolean performFinish() {
		Preferences prefs = InstanceScope.INSTANCE.getNode(BspClientManager.CORE_PLUGIN_ID);
		prefs.put(BspPreferenceInitializer.PREF_COMMAND_LINE, this.page.getCommandLine());
		prefs.put(BspPreferenceInitializer.PREF_ROOT_DIRECTORIES, this.page.getRootDirectoriesPreferenceValue());
		prefs.put(BspPreferenceInitializer.PREF_COMMAND_MODE, this.page.getCommandMode());
		prefs.put(BspPreferenceInitializer.PREF_GRADLE_EXECUTABLE_PATH, this.page.getGradleExecutablePath());
		try {
			prefs.flush();
		} catch (BackingStoreException e) {
			BspClientPlugin.logError("Failed to save BSP import settings", e);
			MessageDialog.openError(getShell(), "BSP Import",
					"Failed to save BSP import settings: " + e.getMessage());
			return false;
		}
		if (this.page.shouldStartClient()) {
			BspClientManager.getInstance().stop();
			BspClientManager.getInstance().startFromPreferences();
		}
		return true;
	}
}
