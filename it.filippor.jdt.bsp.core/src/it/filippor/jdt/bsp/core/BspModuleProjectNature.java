package it.filippor.jdt.bsp.core;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectNature;
import org.eclipse.core.runtime.CoreException;

public class BspModuleProjectNature implements IProjectNature {

	public static final String NATURE_ID = "it.filippor.jdt.bsp.core.bspModuleNature";

	private IProject project;

	@Override
	public void configure() throws CoreException {
		// Nature marker only; sync/configuration is handled by core services.
	}

	@Override
	public void deconfigure() throws CoreException {
		// No teardown required.
	}

	@Override
	public IProject getProject() {
		return project;
	}

	@Override
	public void setProject(IProject project) {
		this.project = project;
	}
}
