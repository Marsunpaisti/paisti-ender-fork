package paisti.client;

import haven.GOut;
import haven.UI;

public class ScreenOverlayAfterDraw implements UI.AfterDraw {
    private final PaistiServices services;

    public ScreenOverlayAfterDraw(PaistiServices services) {
	this.services = services;
    }

    @Override
    public void draw(GOut g) {
	services.overlayManager().renderScreenOverlays(g);
    }
}
