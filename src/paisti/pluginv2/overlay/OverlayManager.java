package paisti.pluginv2.overlay;

import haven.GOut;
import haven.GameUI;
import haven.Loading;
import haven.MapView;
import haven.PaistiServices;
import haven.render.Pipe;
import haven.render.Render;
import haven.render.RenderTree;
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
    private final MapOverlayBridge mapBridge = new MapOverlayBridge(this);

    private volatile MapView attachedMap;
    private volatile RenderTree.Slot mapSlot;

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
            if((registered.overlay instanceof ScreenOverlay) && !registered.disabled && enabledForEnumeration(registered)) {
                result.add((ScreenOverlay) registered.overlay);
            }
        }
        return result;
    }

    public List<MapOverlay> mapOverlays() {
        List<MapOverlay> result = new ArrayList<>();
        for(RegisteredOverlay registered : sorted()) {
            if((registered.overlay instanceof MapOverlay) && !registered.disabled && enabledForEnumeration(registered)) {
                result.add((MapOverlay) registered.overlay);
            }
        }
        return result;
    }

    public void renderMapWorldOverlays(Pipe state, Render out) {
        MapView map = activeRenderMap();
        GameUI gui = currentGui();
        MapWorldOverlayContext ctx = new MapWorldOverlayContext(services.ui(), gui, map, state, out);
        for(RegisteredOverlay registered : sorted()) {
            if(!(registered.overlay instanceof MapOverlay)) {
                continue;
            }
            if(registered.disabled) {
                continue;
            }
            try {
                if(!registered.overlay.enabled()) {
                    continue;
                }
                ((MapOverlay) registered.overlay).renderWorld(ctx);
                registered.mapWorldFailures = 0;
            } catch(Loading l) {
            } catch(Throwable t) {
                handleFailure(registered, t, Phase.MAP_WORLD);
            }
        }
    }

    public void renderMapScreenOverlays(GOut g, Pipe state) {
        MapView map = activeRenderMap();
        GameUI gui = currentGui();
        MapScreenOverlayContext ctx = new MapScreenOverlayContext(services.ui(), gui, map, g, state);
        for(RegisteredOverlay registered : sorted()) {
            if(!(registered.overlay instanceof MapOverlay)) {
                continue;
            }
            if(registered.disabled) {
                continue;
            }
            try {
                if(!registered.overlay.enabled()) {
                    continue;
                }
                ((MapOverlay) registered.overlay).renderScreen(ctx);
                registered.mapScreenFailures = 0;
            } catch(Loading l) {
            } catch(Throwable t) {
                handleFailure(registered, t, Phase.MAP_SCREEN);
            }
        }
    }

    public void syncMapOverlayAttachment() {
        MapView map = currentMap();
        if(map == attachedMap) {
            return;
        }
        clearMapAttachment();
        if(map != null) {
            RenderTree.Slot slot = map.drawadd(mapBridge);
            attachedMap = map;
            mapSlot = slot;
        }
    }

    public void renderScreenOverlays(GOut g) {
        ScreenOverlayContext ctx = new ScreenOverlayContext(services.ui(), g);
        boolean hasActiveGameUi = (ctx.ui() != null) && (ctx.ui().gui != null);
        for(RegisteredOverlay registered : sorted()) {
            if(!(registered.overlay instanceof ScreenOverlay)) {
                continue;
            }
            if(registered.disabled) {
                continue;
            }
            ScreenOverlay overlay = (ScreenOverlay) registered.overlay;
            try {
                if((overlay.scope() == ScreenOverlayScope.GAMEPLAY) && !hasActiveGameUi) {
                    continue;
                }
                if(!registered.overlay.enabled()) {
                    continue;
                }
                overlay.render(ctx);
                registered.screenFailures = 0;
            } catch(Loading l) {
            } catch(Throwable t) {
                handleFailure(registered, t, Phase.SCREEN);
            }
        }
    }

    public void stop() {
        clearMapAttachment();
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

    private void handleFailure(RegisteredOverlay registered, Throwable t, Phase phase) {
        int failures = phase.increment(registered);
        System.err.println("Overlay failure in plugin " + registered.owner.getName() + " overlay " + registered.overlay.id());
        t.printStackTrace(System.err);
        if(failures >= MAX_CONSECUTIVE_FAILURES) {
            System.err.println("Overlay disabled after repeated failures: " + registered.overlay.id());
            unregister(registered);
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

    private boolean enabledForEnumeration(RegisteredOverlay registered) {
        try {
            return registered.overlay.enabled();
        } catch(Loading l) {
            return false;
        } catch(Throwable t) {
            return false;
        }
    }

    private GameUI currentGui() {
        return (services.ui() == null) ? null : services.ui().gui;
    }

    private MapView currentMap() {
        GameUI gui = currentGui();
        if((gui != null) && (gui.map != null)) {
            return gui.map;
        }
        if((services.ui() == null) || (services.ui().root == null)) {
            return null;
        }
        return services.ui().root.findchild(MapView.class);
    }

    private MapView activeRenderMap() {
        return (attachedMap != null) ? attachedMap : currentMap();
    }

    private void clearMapAttachment() {
        RenderTree.Slot slot = mapSlot;
        mapSlot = null;
        if(slot != null) {
            try {
                slot.remove();
            } catch(RenderTree.SlotRemoved e) {
                /* Slot's parent tree was already disposed (e.g. during logout teardown). */
            }
        }
        attachedMap = null;
    }

    static final class RegisteredOverlay {
        private final PaistiPlugin owner;
        private final PluginOverlay overlay;
        private final long order;
        private int screenFailures;
        private int mapWorldFailures;
        private int mapScreenFailures;
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

    private enum Phase {
        SCREEN {
            @Override
            int increment(RegisteredOverlay registered) {
                return ++registered.screenFailures;
            }
        },
        MAP_WORLD {
            @Override
            int increment(RegisteredOverlay registered) {
                return ++registered.mapWorldFailures;
            }
        },
        MAP_SCREEN {
            @Override
            int increment(RegisteredOverlay registered) {
                return ++registered.mapScreenFailures;
            }
        };

        abstract int increment(RegisteredOverlay registered);
    }
}
