package paisti.pluginv2.overlay;

public interface ScreenOverlay extends PluginOverlay {
    default ScreenOverlayScope scope() {
        return ScreenOverlayScope.GAMEPLAY;
    }

    void render(ScreenOverlayContext ctx);
}
