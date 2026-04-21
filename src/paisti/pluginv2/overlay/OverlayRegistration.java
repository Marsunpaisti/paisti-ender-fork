package paisti.pluginv2.overlay;

public final class OverlayRegistration implements AutoCloseable {
    private final OverlayManager manager;
    private final PluginOverlay overlay;

    OverlayRegistration(OverlayManager manager, PluginOverlay overlay) {
        this.manager = manager;
        this.overlay = overlay;
    }

    public PluginOverlay overlay() {
        return overlay;
    }

    @Override
    public void close() {
        manager.unregister(overlay);
    }
}
