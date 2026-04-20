package paisti.pluginv2;

import haven.PaistiServices;
import haven.UI;
import paisti.hooks.EventBus;

public abstract class PaistiPlugin {
    protected final PaistiServices services;

    public PaistiPlugin(PaistiServices services) {
	this.services = services;
    }

    public abstract void startUp();
    public abstract void shutDown();

    protected PaistiServices services() {
	return services;
    }

    protected UI ui() {
	return services.ui();
    }

    protected EventBus eventBus() {
	return services().eventBus();
    }

    protected PluginService pluginService() {
	return services().pluginService();
    }

    public String getName()
    {
	return getClass().getAnnotation(PluginDescription.class).name();
    }
}
