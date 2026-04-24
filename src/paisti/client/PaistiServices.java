package paisti.client;

import haven.Config;
import haven.UI;
import paisti.hooks.EventBus;
import paisti.plugin.PluginService;
import paisti.plugin.overlay.OverlayManager;
import paisti.world.WorldPersistenceRegistry;

public class PaistiServices {
    private volatile UI ui;
    private final EventBus eventBus;
    private final PluginService pluginService;
    private final OverlayManager overlayManager;
    private final WorldPersistenceRegistry worldPersistenceRegistry;
    private boolean started;

    public PaistiServices() {
	this.eventBus = new EventBus();
	this.pluginService = new PluginService(this);
	this.overlayManager = new OverlayManager(this);
        this.worldPersistenceRegistry = new WorldPersistenceRegistry(Config.getFile("paisti-world").toPath());
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

    public WorldPersistenceRegistry worldPersistenceRegistry() {
        return worldPersistenceRegistry;
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
        try {
            worldPersistenceRegistry.close();
        } catch(Exception e) {
            System.err.println("Failed to stop world persistence: " + e);
        }
    }
}
