package paisti.pluginv2.overlay;

import haven.GameUI;
import haven.MapView;
import haven.UI;
import haven.render.Pipe;
import haven.render.Render;

public final class MapWorldOverlayContext {
    private final UI ui;
    private final GameUI gui;
    private final MapView map;
    private final Pipe state;
    private final Render out;

    public MapWorldOverlayContext(UI ui, GameUI gui, MapView map, Pipe state, Render out) {
        this.ui = ui;
        this.gui = gui;
        this.map = map;
        this.state = state;
        this.out = out;
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

    public Pipe state() {
        return state;
    }

    public Render out() {
        return out;
    }
}
