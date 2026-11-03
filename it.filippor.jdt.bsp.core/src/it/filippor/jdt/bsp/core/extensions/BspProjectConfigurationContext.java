package it.filippor.jdt.bsp.core.extensions;

import ch.epfl.scala.bsp4j.BuildServer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Context passed to project configurer contributions during BSP synchronization.
 */
public record BspProjectConfigurationContext(IProject project,
                                             BspProjectKind kind,
                                             BuildServer bspServer,
                                             String rootLabel,
                                             String containerName,
                                             String sourceSetName,
                                             IProgressMonitor monitor) {
}
