package paisti.plugin.DevToolsPlugin;

import haven.Coord;
import haven.Text;
import haven.Tex;
import haven.TexI;
import paisti.plugin.overlay.ScreenOverlay;
import paisti.plugin.overlay.ScreenOverlayContext;

import java.awt.Color;

public class DevToolsPluginScreenOverlay implements ScreenOverlay {
    private Tex label;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void render(ScreenOverlayContext ctx) {
        if((ctx.ui() == null) || !ctx.ui().modctrl || !ctx.ui().modshift) {
            return;
        }
        if(label == null) {
            label = new TexI(Text.renderstroked("DEV overlay active", Color.WHITE, Color.BLACK).img);
        }
        ctx.g().image(label, Coord.of(10, 10));
    }

    @Override
    public void dispose() {
        if(label != null) {
            label.dispose();
            label = null;
        }
    }
}
