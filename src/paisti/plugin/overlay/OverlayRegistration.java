package paisti.plugin.overlay;

import java.util.concurrent.atomic.AtomicBoolean;

public final class OverlayRegistration implements AutoCloseable {
    private final OverlayManager manager;
    private final OverlayManager.RegisteredOverlay registration;
    private final AtomicBoolean closed = new AtomicBoolean();

    OverlayRegistration(OverlayManager manager, OverlayManager.RegisteredOverlay registration) {
        this.manager = manager;
        this.registration = registration;
    }

    public PluginOverlay overlay() {
        return registration.overlay();
    }

    @Override
    public void close() {
        if(!closed.compareAndSet(false, true)) {
            return;
        }
        manager.unregister(registration);
    }
}
