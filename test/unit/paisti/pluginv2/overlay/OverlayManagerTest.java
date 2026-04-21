package paisti.pluginv2.overlay;

import haven.PaistiServices;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.pluginv2.PluginDescription;
import paisti.pluginv2.PaistiPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayManagerTest {
    @PluginDescription(name = "Test Plugin", configName = "overlay-manager-test")
    private static final class TestPlugin extends PaistiPlugin {
        private TestPlugin(PaistiServices services) {
            super(services);
        }

        @Override
        public void startUp() {
        }

        @Override
        public void shutDown() {
        }

        private OverlayManager exposedOverlayManager() {
            return overlayManager();
        }
    }

    @Test
    @Tag("unit")
    void overlayApiExists() throws Exception {
        assertNotNull(Class.forName("paisti.pluginv2.overlay.PluginOverlay"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.ScreenOverlay"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.MapOverlay"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.OverlayRegistration"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.ScreenOverlayContext"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.MapWorldOverlayContext"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.MapScreenOverlayContext"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.OverlayManager"));
    }

    @Test
    @Tag("unit")
    void servicesExposeStableSharedOverlayManager() throws Exception {
        Method method = PaistiServices.class.getMethod("overlayManager");
        assertEquals("paisti.pluginv2.overlay.OverlayManager", method.getReturnType().getName());

        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();

        assertNotNull(manager);
        assertSame(manager, services.overlayManager());
    }

    @Test
    @Tag("unit")
    void pluginBaseDelegatesToServicesOverlayManager() throws Exception {
        Method method = PaistiPlugin.class.getDeclaredMethod("overlayManager");
        assertEquals("paisti.pluginv2.overlay.OverlayManager", method.getReturnType().getName());

        PaistiServices services = new PaistiServices();
        TestPlugin plugin = new TestPlugin(services);

        assertSame(services.overlayManager(), plugin.exposedOverlayManager());
    }

    @Test
    @Tag("unit")
    void overlayManagerExposesOrderedOverlayLists() throws Exception {
        assertSame(List.class, OverlayManager.class.getMethod("screenOverlays").getReturnType());
        assertSame(List.class, OverlayManager.class.getMethod("mapOverlays").getReturnType());
    }

    @Test
    @Tag("unit")
    void unregisterAllDisposesOwnerOverlays() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        TrackingScreenOverlay overlay = new TrackingScreenOverlay("owner", 0);

        manager.register(owner, overlay);
        manager.unregisterAll(owner);

        assertTrue(overlay.disposed, "expected unregisterAll(owner) to dispose registered overlay");
        assertTrue(manager.screenOverlays().isEmpty(), "expected owner overlay list to be empty after unregisterAll(owner)");
    }

    @Test
    @Tag("unit")
    void screenOverlaysRenderInPriorityThenRegistrationOrder() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        List<String> trace = new ArrayList<>();

        manager.register(owner, new TrackingScreenOverlay("late", 10, trace));
        manager.register(owner, new TrackingScreenOverlay("early-a", 0, trace));
        manager.register(owner, new TrackingScreenOverlay("early-b", 0, trace));

        renderScreenOverlays(manager);

        assertEquals(Arrays.asList("early-a", "early-b", "late"), trace);
    }

    @Test
    @Tag("unit")
    void repeatedScreenFailuresDisableOnlyTheBrokenOverlay() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        TrackingScreenOverlay healthy = new TrackingScreenOverlay("healthy", 0);
        ThrowingScreenOverlay broken = new ThrowingScreenOverlay();

        manager.register(owner, broken);
        manager.register(owner, healthy);

        for(int i = 0; i < 6; i++) {
            renderScreenOverlays(manager);
        }

        assertEquals(6, healthy.renders, "expected healthy overlay to keep rendering after broken overlay failures");
        assertEquals(5, broken.renders, "expected broken overlay to be disabled after five failures");
    }

    private static class TrackingScreenOverlay implements ScreenOverlay {
        private final String name;
        private final int priority;
        private final List<String> trace;
        private boolean disposed;
        protected int renders;

        private TrackingScreenOverlay(String name, int priority) {
            this(name, priority, null);
        }

        private TrackingScreenOverlay(String name, int priority, List<String> trace) {
            this.name = name;
            this.priority = priority;
            this.trace = trace;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public void render(ScreenOverlayContext ctx) {
            renders++;
            if(trace != null) {
                trace.add(name);
            }
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private static final class ThrowingScreenOverlay extends TrackingScreenOverlay {
        private ThrowingScreenOverlay() {
            super("broken", 0);
        }

        @Override
        public void render(ScreenOverlayContext ctx) {
            renders++;
            throw new RuntimeException("boom");
        }
    }

    private static void renderScreenOverlays(OverlayManager manager) {
        try {
            Method method = OverlayManager.class.getMethod("renderScreenOverlays", haven.GOut.class);
            method.invoke(manager, new Object[] {null});
        } catch(NoSuchMethodException e) {
            throw new AssertionError("expected OverlayManager to expose renderScreenOverlays(GOut)", e);
        } catch(IllegalAccessException e) {
            throw new AssertionError("expected OverlayManager.renderScreenOverlays(GOut) to be accessible", e);
        } catch(InvocationTargetException e) {
            Throwable cause = e.getCause();
            if(cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if(cause instanceof Error) {
                throw (Error) cause;
            }
            throw new AssertionError("unexpected checked exception from renderScreenOverlays", cause);
        }
    }
}
