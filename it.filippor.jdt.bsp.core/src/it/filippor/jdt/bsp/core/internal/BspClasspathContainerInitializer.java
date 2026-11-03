package it.filippor.jdt.bsp.core.internal;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ClasspathContainerInitializer;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

public class BspClasspathContainerInitializer extends ClasspathContainerInitializer {

    @Override
    public void initialize(IPath containerPath, IJavaProject project) throws CoreException {
        if (!isBspContainerPath(containerPath) || project == null) {
            return;
        }
        try {
            JavaCore.setClasspathContainer(BspClasspathContainer.CONTAINER_PATH, new IJavaProject[] { project },
                    new IClasspathContainer[] { new BspClasspathContainer(project) }, null);
        } catch (JavaModelException e) {
            throw new CoreException(e.getStatus());
        }
    }

    @Override
    public String getDescription(IPath containerPath, IJavaProject project) {
        return isBspContainerPath(containerPath) ? "BSP Resolved Classpath" : null;
    }

    @Override
    public boolean canUpdateClasspathContainer(IPath containerPath, IJavaProject project) {
        return false;
    }

    private static boolean isBspContainerPath(IPath containerPath) {
        return containerPath != null && BspClasspathContainer.CONTAINER_PATH.segmentCount() > 0
                && BspClasspathContainer.CONTAINER_PATH.segment(0).equals(containerPath.segment(0));
    }
}
