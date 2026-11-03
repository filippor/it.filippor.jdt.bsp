package it.filippor.jdt.bsp.core.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;

import it.filippor.jdt.bsp.core.extensions.BspProjectConfigurer;

public final class BspProjectConfigurerRegistry {

    private static final String CORE_PLUGIN_ID = "it.filippor.jdt.bsp.core";
    private static final String EXTENSION_POINT_ID = CORE_PLUGIN_ID + ".projectConfigurer";

    private BspProjectConfigurerRegistry() {
    }

    public static List<BspProjectConfigurer> loadConfigurers() {
        IConfigurationElement[] elements = Platform.getExtensionRegistry().getConfigurationElementsFor(
            EXTENSION_POINT_ID);
        List<ConfigurerContribution> contributions = new ArrayList<>(elements.length);
        for (IConfigurationElement element : elements) {
            if (!"configurer".equals(element.getName())) {
                continue;
            }
            try {
                Object executable = element.createExecutableExtension("class");
                if (executable instanceof BspProjectConfigurer configurer) {
                    contributions.add(new ConfigurerContribution(parseOrder(element.getAttribute("order")), configurer));
                }
            } catch (CoreException e) {
                // Best effort: ignore invalid contributions.
            }
        }
        contributions.sort(Comparator.comparingInt(ConfigurerContribution::order));
        List<BspProjectConfigurer> ordered = new ArrayList<>(contributions.size());
        for (ConfigurerContribution contribution : contributions) {
            ordered.add(contribution.configurer());
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

    private record ConfigurerContribution(int order, BspProjectConfigurer configurer) {
    }
}
