package paisti.plugin.overlay;

public interface MapOverlay extends PluginOverlay {
    default void renderWorld(MapWorldOverlayContext ctx) {
    }

    default void renderScreen(MapScreenOverlayContext ctx) {
    }
}
