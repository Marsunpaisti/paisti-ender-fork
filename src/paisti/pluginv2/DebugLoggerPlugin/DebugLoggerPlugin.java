package paisti.pluginv2.DebugLoggerPlugin;

import haven.UI;
import paisti.hooks.EventBus;
import paisti.hooks.events.BeforeOutgoingWidgetMessage;
import paisti.pluginv2.PaistiPlugin;

import java.awt.*;

public class DebugLoggerPlugin extends PaistiPlugin {
    EventBus.Subscriber outgoingWidgetMessageSubscriber;
    private boolean debugOutgoingWidgetMessages(){
	return true;
    }

    public DebugLoggerPlugin(UI ui) {
	super(ui);
    }

    private void logOutgoingWidgetMessage(BeforeOutgoingWidgetMessage event){
	if(debugOutgoingWidgetMessages()) {
	    System.out.println(event.toString());
	    if (ui != null) {
		ui.msg(event.toString(), Color.WHITE, null);
	    }
	}
    }

    @Override
    public void startUp() {
	outgoingWidgetMessageSubscriber = EventBus.get().register(BeforeOutgoingWidgetMessage.class, this::logOutgoingWidgetMessage, 0);
    }

    @Override
    public void shutDown() {
	EventBus.get().unregister(outgoingWidgetMessageSubscriber);
    }
}
