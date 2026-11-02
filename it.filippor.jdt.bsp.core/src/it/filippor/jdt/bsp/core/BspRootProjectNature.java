package it.filippor.jdt.bsp.core;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectNature;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.QualifiedName;

public class BspRootProjectNature implements IProjectNature {

	public static final String NATURE_ID = "it.filippor.jdt.bsp.core.bspRootNature";
	public static final String SETTINGS_NODE = "it.filippor.jdt.bsp.core";
	public static final String KEY_BSP_COMMAND_LINE = "bspCommandLine";
	public static final String KEY_CONTAINER_NAME = "containerName";
	public static final String KEY_BUILD_TARGET_NAME = "buildTargetName";
	public static final QualifiedName PROPERTY_CONTAINER_NAME = new QualifiedName(SETTINGS_NODE, "containerName");
	public static final QualifiedName PROPERTY_BUILD_TARGET_NAME = new QualifiedName(SETTINGS_NODE, "buildTargetName");

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
