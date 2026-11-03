package it.filippor.jdt.bsp.core.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;

import it.filippor.jdt.bsp.core.extensions.BspClasspathEnricher;

public final class BspClasspathEnricherRegistry {

    private static final String CORE_PLUGIN_ID = "it.filippor.jdt.bsp.core";
    private static final String EXTENSION_POINT_ID = CORE_PLUGIN_ID + ".classpathEnricher";

    private BspClasspathEnricherRegistry() {
    }

    public static List<BspClasspathEnricher> loadEnrichers() {
        IConfigurationElement[] elements = Platform.getExtensionRegistry().getConfigurationElementsFor(
            EXTENSION_POINT_ID);
        List<EnricherContribution> contributions = new ArrayList<>(elements.length);
        for (IConfigurationElement element : elements) {
            if (!"enricher".equals(element.getName())) {
                continue;
            }
            try {
                Object executable = element.createExecutableExtension("class");
                if (executable instanceof BspClasspathEnricher enricher) {
                    contributions.add(new EnricherContribution(parseOrder(element.getAttribute("order")), enricher));
                }
            } catch (CoreException e) {
                // Best effort: ignore invalid contributions.
            }
        }
        contributions.sort(Comparator.comparingInt(EnricherContribution::order));
        List<BspClasspathEnricher> ordered = new ArrayList<>(contributions.size());
        for (EnricherContribution contribution : contributions) {
            ordered.add(contribution.enricher());
        }
        return ordered;
    }

    private static int parseOrder(String order) {
        if (order == null || order.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(order.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record EnricherContribution(int order, BspClasspathEnricher enricher) {
    }
}
