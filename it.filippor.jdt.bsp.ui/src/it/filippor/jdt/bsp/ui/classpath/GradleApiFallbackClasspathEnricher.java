package it.filippor.jdt.bsp.ui.classpath;

import java.util.List;

import org.eclipse.jdt.core.IClasspathEntry;

import it.filippor.jdt.bsp.core.extensions.BspClasspathEnricher;

/**
 * Keep BSP classpath as primary source and augment only when Gradle API bits are missing.
 */
public class GradleApiFallbackClasspathEnricher implements BspClasspathEnricher {

    @Override
    public List<IClasspathEntry> enrich(String sourceSetId, List<IClasspathEntry> classpath) {
        return GradleApiClasspathAugmenter.addGradleApiEntriesIfNeeded(sourceSetId, classpath);
    }
}
