package paisti.pluginv2.overlay;

import haven.PaistiServices;
import paisti.pluginv2.PaistiPlugin;

import java.util.Collection;
import java.util.Collections;

public class OverlayManager {
    private final PaistiServices services;

    public OverlayManager(PaistiServices services) {
        this.services = services;
    }

    public OverlayRegistration register(PaistiPlugin owner, PluginOverlay overlay) {
        return new OverlayRegistration(this, overlay);
    }

    public void unregister(PluginOverlay overlay) {
    }

    public void unregisterAll(PaistiPlugin owner) {
    }

    public Collection<ScreenOverlay> screenOverlays() {
        return Collections.emptyList();
    }

    public Collection<MapOverlay> mapOverlays() {
        return Collections.emptyList();
    }
}
