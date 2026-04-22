package paisti.plugin.overlay;

import haven.Coord;
import haven.Coord3f;
import haven.GOut;
import haven.GameUI;
import haven.MapView;
import haven.UI;
import haven.render.Pipe;

public final class MapScreenOverlayContext {
    private final UI ui;
    private final GameUI gui;
    private final MapView map;
    private final GOut g;
    private final Pipe state;

    public MapScreenOverlayContext(UI ui, GameUI gui, MapView map, GOut g, Pipe state) {
        this.ui = ui;
        this.gui = gui;
        this.map = map;
        this.g = g;
        this.state = state;
    }

    public UI ui() {
        return ui;
    }

    public GameUI gui() {
        return gui;
    }

    public MapView map() {
        return map;
    }

    public GOut g() {
        return g;
    }

    public Pipe state() {
        return state;
    }

    public Coord worldToScreen(Coord3f world) {
        if ((map == null) || (world == null)) {
            return null;
        }
        return map.screenxf(world).round2();
    }
}
