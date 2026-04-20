package paisti.pluginv2.DevToolsPlugin;

import haven.PaistiServices;
import paisti.hooks.EventBus;
import paisti.hooks.events.BeforeOutgoingWidgetMessage;
import paisti.pluginv2.PaistiPlugin;
import paisti.pluginv2.PluginDescription;

import java.awt.*;

@PluginDescription(
    name = "Developer tools",
    description = "Allows the option of enabling various developer tools",
    enabledByDefault = true,
    hidden = true
)
public class DevToolsPlugin extends PaistiPlugin {
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
    }

    @Override
    public void shutDown() {
	eventBus().unregister(outgoingWidgetMessageSubscriber);
    }
}
