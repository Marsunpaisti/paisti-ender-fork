package paisti.pluginv2.overlay;

import haven.GOut;
import haven.PaistiServices;
import paisti.pluginv2.PaistiPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;

public class OverlayManager {
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final PaistiServices services;
    private final CopyOnWriteArrayList<RegisteredOverlay> overlays = new CopyOnWriteArrayList<>();
    private final AtomicLong nextOrder = new AtomicLong();

    public OverlayManager(PaistiServices services) {
        this.services = services;
    }

    public OverlayRegistration register(PaistiPlugin owner, PluginOverlay overlay) {
        if(owner == null) {
            throw new IllegalArgumentException("overlay owner must not be null");
        }
        if(overlay == null) {
            throw new IllegalArgumentException("overlay must not be null");
        }
        RegisteredOverlay registered = new RegisteredOverlay(owner, overlay, nextOrder.getAndIncrement());
        overlays.add(registered);
        return new OverlayRegistration(this, registered);
    }

    public void unregister(PluginOverlay overlay) {
        for(RegisteredOverlay registered : overlays) {
            if(registered.overlay == overlay) {
                unregister(registered);
                break;
            }
        }
    }

    void unregister(RegisteredOverlay registration) {
        if(overlays.remove(registration)) {
            disposeIfOrphaned(registration);
        }
    }

    public void unregisterAll(PaistiPlugin owner) {
        for(RegisteredOverlay registered : overlays) {
            if(registered.owner == owner) {
                unregister(registered);
            }
        }
    }

    public List<ScreenOverlay> screenOverlays() {
        List<ScreenOverlay> result = new ArrayList<>();
        for(RegisteredOverlay registered : sorted()) {
            if((registered.overlay instanceof ScreenOverlay) && !registered.disabled && registered.overlay.enabled()) {
                result.add((ScreenOverlay) registered.overlay);
            }
        }
        return result;
    }

    public List<MapOverlay> mapOverlays() {
        List<MapOverlay> result = new ArrayList<>();
        for(RegisteredOverlay registered : sorted()) {
            if((registered.overlay instanceof MapOverlay) && !registered.disabled && registered.overlay.enabled()) {
                result.add((MapOverlay) registered.overlay);
            }
        }
        return result;
    }

    public void renderScreenOverlays(GOut g) {
        ScreenOverlayContext ctx = new ScreenOverlayContext(services.ui(), g);
        for(RegisteredOverlay registered : sorted()) {
            if(!(registered.overlay instanceof ScreenOverlay)) {
                continue;
            }
            if(registered.disabled || !registered.overlay.enabled()) {
                continue;
            }
            try {
                ((ScreenOverlay) registered.overlay).render(ctx);
                registered.failures = 0;
            } catch(Throwable t) {
                handleFailure(registered, t);
            }
        }
    }

    public void stop() {
        for(RegisteredOverlay registered : overlays) {
            unregister(registered);
        }
    }

    public PaistiServices services() {
        return services;
    }

    private List<RegisteredOverlay> sorted() {
        List<RegisteredOverlay> ordered = new ArrayList<>(overlays);
        ordered.sort(Comparator
            .comparingInt((RegisteredOverlay registered) -> registered.overlay.priority())
            .thenComparingLong(registered -> registered.order));
        return ordered;
    }

    private void handleFailure(RegisteredOverlay registered, Throwable t) {
        registered.failures++;
        System.err.println("Overlay failure in plugin " + registered.owner.getName() + " overlay " + registered.overlay.id());
        t.printStackTrace(System.err);
        if(registered.failures >= MAX_CONSECUTIVE_FAILURES) {
            registered.disabled = true;
            System.err.println("Overlay disabled after repeated failures: " + registered.overlay.id());
        }
    }

    private void disposeQuietly(RegisteredOverlay registered) {
        try {
            registered.overlay.dispose();
        } catch(Throwable t) {
            System.err.println("Overlay dispose failure in plugin " + registered.owner.getName() + " overlay " + registered.overlay.id());
            t.printStackTrace(System.err);
        }
    }

    private void disposeIfOrphaned(RegisteredOverlay removed) {
        for(RegisteredOverlay registered : overlays) {
            if(registered.overlay == removed.overlay) {
                return;
            }
        }
        disposeQuietly(removed);
    }

    static final class RegisteredOverlay {
        private final PaistiPlugin owner;
        private final PluginOverlay overlay;
        private final long order;
        private int failures;
        private boolean disabled;

        private RegisteredOverlay(PaistiPlugin owner, PluginOverlay overlay, long order) {
            this.owner = owner;
            this.overlay = overlay;
            this.order = order;
        }

        PluginOverlay overlay() {
            return overlay;
        }
    }
}
