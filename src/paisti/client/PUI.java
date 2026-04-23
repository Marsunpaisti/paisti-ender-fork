package paisti.client;

import haven.*;
import paisti.hooks.EventBus;
import paisti.hooks.events.BeforeOutgoingWidgetMessage;
import paisti.plugin.PluginService;
import paisti.plugin.overlay.OverlayManager;

public class PUI extends UI {
    private final PaistiServices paistiServices;

    public PUI(Context uictx, Coord sz, Runner fun) {
	super(uictx, sz, fun);
	this.paistiServices = new PaistiServices();
	this.paistiServices.bindUi(this);
	this.paistiServices.start();
	if(this.sess != null) {
	    this.sess.glob.gobFactory = PGob::new;
	}
    }

    public static PUI of(UI ui) {
	return (PUI) ui;
    }

    public PaistiServices services() {
	return paistiServices;
    }

    public EventBus eventBus() {
	return paistiServices.eventBus();
    }

    public PluginService pluginService() {
	return paistiServices.pluginService();
    }

    public OverlayManager overlayManager() {
	return paistiServices.overlayManager();
    }

    @Override
    public void draw(GOut g) {
	super.draw(g);
	paistiServices.overlayManager().renderScreenOverlays(g);
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
	int id = widgetid(sender);
	if(id >= 0) {
	    eventBus().post(new BeforeOutgoingWidgetMessage(this, sender, id, msg, args));
	}
	super.wdgmsg(sender, msg, args);
    }

    @Override
    public void setGUI(GameUI gui) {
	super.setGUI(gui);
	paistiServices.overlayManager().syncMapOverlayAttachment();
    }

    @Override
    public void clearGUI(GameUI gui) {
	super.clearGUI(gui);
	paistiServices.overlayManager().syncMapOverlayAttachment();
    }

    @Override
    public void destroy() {
	paistiServices.stop();
	paistiServices.clearUi(this);
	super.destroy();
    }
}
