package paisti.pluginv2.DevToolsPlugin;

import haven.PaistiServices;
import paisti.hooks.EventBus;
import paisti.hooks.events.BeforeOutgoingWidgetMessage;
import paisti.pluginv2.PaistiPlugin;
import paisti.pluginv2.PluginDescription;
import paisti.pluginv2.overlay.OverlayRegistration;

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
    private OverlayRegistration sceneOverlayRegistration;
    private OverlayRegistration screenOverlayRegistration;
    EventBus.Subscriber outgoingWidgetMessageSubscriber;

    private boolean debugOutgoingWidgetMessages(){
	return true;
    }

    public DevToolsPlugin(PaistiServices services) {
	super(services);
    }

    private void logOutgoingWidgetMessage(BeforeOutgoingWidgetMessage event){
	if(debugOutgoingWidgetMessages()) {
	    System.out.println(event.toString());
	    if (ui() != null) {
		ui().msg(event.toString(), Color.WHITE, null);
	    }
	}
    }

    @Override
    public void startUp() {
	outgoingWidgetMessageSubscriber = eventBus().register(BeforeOutgoingWidgetMessage.class, this::logOutgoingWidgetMessage, 0);
	sceneOverlayRegistration = overlayManager().register(this, sceneOverlay);
	screenOverlayRegistration = overlayManager().register(this, screenOverlay);
    }

    @Override
    public void shutDown() {
	eventBus().unregister(outgoingWidgetMessageSubscriber);
	if(sceneOverlayRegistration != null) {
	    sceneOverlayRegistration.close();
	    sceneOverlayRegistration = null;
	}
	if(screenOverlayRegistration != null) {
	    screenOverlayRegistration.close();
	    screenOverlayRegistration = null;
	}
	overlayManager().unregisterAll(this);
    }
}
