package org.eclipse.jdt.bsp.client;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectNature;
import org.eclipse.core.runtime.CoreException;

public class BspRootProjectNature implements IProjectNature {

	public static final String NATURE_ID = "org.eclipse.jdt.bsp.client.bspRootNature";
	public static final String SETTINGS_NODE = "org.eclipse.jdt.bsp.client";
	public static final String KEY_BSP_ROOT_FOLDER = "bspRootFolder";
	public static final String KEY_BSP_COMMAND_LINE = "bspCommandLine";

	private IProject project;

	@Override
	public void configure() throws CoreException {
		// No builder required yet.
	}

	@Override
	public void deconfigure() throws CoreException {
		// No builder required yet.
	}

	@Override
	public IProject getProject() {
		return this.project;
	}

	@Override
	public void setProject(IProject project) {
		this.project = project;
	}
}
