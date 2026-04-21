package paisti.pluginv2.DevToolsPlugin;

import haven.Coord;
import haven.Coord3f;
import haven.Gob;
import haven.Text;
import haven.Tex;
import haven.TexI;
import paisti.pluginv2.overlay.MapOverlay;
import paisti.pluginv2.overlay.MapScreenOverlayContext;

import java.awt.Color;

public class DevToolsPluginSceneOverlay implements MapOverlay {
    private Tex label;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void renderScreen(MapScreenOverlayContext ctx) {
        if((ctx.ui() == null) || (ctx.map() == null) || !ctx.ui().modctrl || !ctx.ui().modshift) {
            return;
        }
        Gob player = ctx.map().player();
        if(player == null) {
            return;
        }
        if(label == null) {
            label = new TexI(Text.renderstroked("DEV player", Color.WHITE, Color.BLACK).img);
        }
        Coord3f playerLabel = new Coord3f(player.getc().x, player.getc().y, player.getc().z + 30f);
        Coord sc = ctx.worldToScreen(playerLabel);
        if((sc == null) || !sc.isect(Coord.z, ctx.g().sz())) {
            return;
        }
        ctx.g().aimage(label, sc, 0.5, 1.0);
    }

    @Override
    public void dispose() {
        if(label != null) {
            label.dispose();
            label = null;
        }
    }
}
