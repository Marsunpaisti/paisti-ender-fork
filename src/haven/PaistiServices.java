package haven;

import paisti.hooks.EventBus;
import paisti.plugin.PluginService;
import paisti.plugin.overlay.OverlayManager;

public class PaistiServices {
    private volatile UI ui;
    private final EventBus eventBus;
    private final PluginService pluginService;
    private final OverlayManager overlayManager;
    private boolean started;

    public PaistiServices() {
	this.eventBus = new EventBus();
	this.pluginService = new PluginService(this);
	this.overlayManager = new OverlayManager(this);
    }

    public UI ui() {
	return ui;
    }

    public void bindUi(UI ui) {
	this.ui = ui;
	overlayManager.syncMapOverlayAttachment();
    }

    public void clearUi(UI ui) {
	if(this.ui == ui) {
	    this.ui = null;
	    overlayManager.syncMapOverlayAttachment();
	}
    }

    public EventBus eventBus() {
	return eventBus;
    }

    public PluginService pluginService() {
	return pluginService;
    }

    public OverlayManager overlayManager() {
	return overlayManager;
    }

    public synchronized void start() {
	if(started)
	    return;
	started = true;
	overlayManager.syncMapOverlayAttachment();
	pluginService.loadBuiltInPlugins();
	pluginService.syncActivePlugins();
    }

    public synchronized void stop() {
	if(started) {
	    started = false;
	    pluginService.stopAll();
	}
	overlayManager.stop();
    }
}
