package it.filippor.jdt.bsp.core.internal;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;

public final class BspClasspathContainer implements IClasspathContainer {

    public static final String CONTAINER_ID = "it.filippor.jdt.bsp.core.BSP_CLASSPATH_CONTAINER";
    public static final IPath CONTAINER_PATH = Path.fromPortableString(CONTAINER_ID);

    private final IJavaProject project;

    public BspClasspathContainer(IJavaProject project) {
        this.project = project;
    }

    @Override
    public IClasspathEntry[] getClasspathEntries() {
        return BspClasspathContainerState.read(this.project);
    }

    @Override
    public String getDescription() {
        return "BSP Resolved Classpath";
    }

    @Override
    public int getKind() {
        return IClasspathContainer.K_APPLICATION;
    }

    @Override
    public IPath getPath() {
        return CONTAINER_PATH;
    }
}
