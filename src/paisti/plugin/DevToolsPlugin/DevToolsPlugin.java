package paisti.plugin.DevToolsPlugin;

import paisti.client.PaistiServices;
import paisti.hooks.EventBus;
import paisti.hooks.events.BeforeOutgoingWidgetMessage;
import paisti.plugin.PaistiPlugin;
import paisti.plugin.PluginDescription;

import java.awt.*;

@PluginDescription(
    name = "Developer tools",
    configName = "devtools",
    description = "Allows the option of enabling various developer tools",
    enabledByDefault = false,
    hidden = false
)
public class DevToolsPlugin extends PaistiPlugin {
    private final DevToolsPluginSceneOverlay sceneOverlay = new DevToolsPluginSceneOverlay();
    private final DevToolsPluginScreenOverlay screenOverlay = new DevToolsPluginScreenOverlay();
    private final DevToolsPlayerCoordsOverlay coordsOverlay = new DevToolsPlayerCoordsOverlay();
    private final DevToolsWorldPersistenceOverlay worldPersistenceOverlay = new DevToolsWorldPersistenceOverlay();
    EventBus.Subscriber outgoingWidgetMessageSubscriber;

    private boolean debugOutgoingWidgetMessages() {
	return true;
    }

    public DevToolsPlugin(PaistiServices services) {
	super(services);
    }

    private void logOutgoingWidgetMessage(BeforeOutgoingWidgetMessage event) {
	if(debugOutgoingWidgetMessages()) {
	    System.out.println(event.toString());
	    if(ui() != null) {
		ui().msg(event.toString(), Color.WHITE, null);
	    }
	}
    }

    @Override
    public void startUp() {
	outgoingWidgetMessageSubscriber = eventBus().register(BeforeOutgoingWidgetMessage.class, this::logOutgoingWidgetMessage, 0);
	overlayManager().register(this, sceneOverlay);
	overlayManager().register(this, screenOverlay);
	overlayManager().register(this, coordsOverlay);
        overlayManager().register(this, worldPersistenceOverlay);
    }

    @Override
    public void shutDown() {
	eventBus().unregister(outgoingWidgetMessageSubscriber);
	overlayManager().unregisterAll(this);
    }
}
