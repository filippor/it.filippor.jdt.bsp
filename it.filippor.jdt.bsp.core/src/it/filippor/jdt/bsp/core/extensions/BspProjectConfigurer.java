package it.filippor.jdt.bsp.core.extensions;

/**
 * Extension point contract used to customize synchronized BSP projects.
 */
public interface BspProjectConfigurer {

    /**
     * Applies extra customization (for example natures or metadata) to a synchronized project.
     *
     * The BSP standard interface is available via {@code context.bspServer()} when initialized.
     */
    void configure(BspProjectConfigurationContext context);
}
