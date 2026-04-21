package paisti.pluginv2.overlay;

import haven.Coord;
import haven.GOut;
import haven.UI;

public final class ScreenOverlayContext {
    private final UI ui;
    private final GOut g;

    public ScreenOverlayContext(UI ui, GOut g) {
        this.ui = ui;
        this.g = g;
    }

    public UI ui() {
        return ui;
    }

    public GOut g() {
        return g;
    }

    public Coord mouse() {
        return (ui == null) ? Coord.z : ui.mc;
    }

    public Coord size() {
        return (g == null) ? Coord.z : g.sz();
    }
}
