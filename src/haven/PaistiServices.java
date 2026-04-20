package haven;

import paisti.hooks.EventBus;
import paisti.pluginv2.PluginService;

public class PaistiServices {
    private final UI ui;
    private final EventBus eventBus;
    private final PluginService pluginService;
    private boolean started;

    public PaistiServices(UI ui) {
	this.ui = ui;
	this.eventBus = new EventBus();
	this.pluginService = new PluginService(this);
    }

    public UI ui() {
	return ui;
    }

    public EventBus eventBus() {
	return eventBus;
    }

    public PluginService pluginService() {
	return pluginService;
    }

    public void start() {
	if(started)
	    return;
	started = true;
	pluginService.loadBuiltInPlugins();
	pluginService.syncActivePlugins();
    }

    public void stop() {
	if(!started)
	    return;
	started = false;
	pluginService.stopAll();
    }
}
