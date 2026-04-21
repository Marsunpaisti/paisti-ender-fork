package paisti.pluginv2.overlay;

import haven.GOut;
import haven.PView;
import haven.render.Pipe;
import haven.render.Render;
import haven.render.RenderTree;
import haven.render.Rendered;

final class MapOverlayBridge implements RenderTree.Node, Rendered, PView.Render2D {
    private final OverlayManager manager;

    MapOverlayBridge(OverlayManager manager) {
        this.manager = manager;
    }

    @Override
    public void draw(Pipe state, Render out) {
        manager.renderMapWorldOverlays(state, out);
    }

    @Override
    public void draw(GOut g, Pipe state) {
        manager.renderMapScreenOverlays(g, state);
    }
}
