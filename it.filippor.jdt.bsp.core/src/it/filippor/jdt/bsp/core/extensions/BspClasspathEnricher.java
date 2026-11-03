package it.filippor.jdt.bsp.core.extensions;

import java.util.List;

import org.eclipse.jdt.core.IClasspathEntry;

/**
 * Extension point contract used to enrich classpath entries resolved from BSP.
 */
public interface BspClasspathEnricher {

    /**
     * @param sourceSetId source-set identifier from BSP build target URI
     * @param classpath current resolved classpath entries from BSP
     * @return enriched classpath entries; return the same list when no change is needed
     */
    List<IClasspathEntry> enrich(String sourceSetId, List<IClasspathEntry> classpath);
}
