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

    @Test
    @Tag("unit")
    void registrationHandleOnlyClosesItsOwnRegistration() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        CountingScreenOverlay overlay = new CountingScreenOverlay("shared", 0);

        OverlayRegistration first = manager.register(owner, overlay);
        OverlayRegistration second = manager.register(owner, overlay);

        first.close();

        assertEquals(1, manager.screenOverlays().size(), "expected one registration to remain after closing the first handle");
        assertEquals(0, overlay.disposeCalls, "expected shared overlay instance to stay undisposed while a newer registration remains");

        first.close();

        assertEquals(1, manager.screenOverlays().size(), "expected stale handle close to leave the newer registration intact");
        assertEquals(0, overlay.disposeCalls, "expected stale handle close not to dispose the newer registration");

        second.close();

        assertTrue(manager.screenOverlays().isEmpty(), "expected all registrations to be gone after closing the second handle");
        assertEquals(1, overlay.disposeCalls, "expected overlay instance to be disposed exactly once when its last registration closes");
    }

    @Test
    @Tag("unit")
    void unregisterRemovesOnlyOneMatchingRegistrationAtATime() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        CountingScreenOverlay overlay = new CountingScreenOverlay("shared", 0);

        manager.register(owner, overlay);
        manager.register(owner, overlay);

        manager.unregister(overlay);

        assertEquals(1, manager.screenOverlays().size(), "expected unregister(overlay) to remove only one matching registration");
        assertEquals(0, overlay.disposeCalls, "expected overlay instance not to be disposed while another registration is still active");

        manager.unregister(overlay);

        assertTrue(manager.screenOverlays().isEmpty(), "expected second unregister(overlay) call to remove the final registration");
        assertEquals(1, overlay.disposeCalls, "expected overlay instance to be disposed once after the last matching registration is removed");
    }

    @Test
    @Tag("unit")
    void screenOverlaysExcludeDisabledByEnabledFlagAndRenderingMatches() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        TrackingScreenOverlay healthy = new TrackingScreenOverlay("healthy", 0);
        DisabledScreenOverlay disabled = new DisabledScreenOverlay("disabled", 0);

        manager.register(owner, disabled);
        manager.register(owner, healthy);

        assertEquals(List.of(healthy), manager.screenOverlays(), "expected screen overlay list to exclude overlays whose enabled() is false");

        renderScreenOverlays(manager);

        assertEquals(1, healthy.renders, "expected enabled screen overlay to render");
        assertEquals(0, disabled.renders, "expected disabled screen overlay not to render");
    }

    @Test
    @Tag("unit")
    void mapOverlaysExcludeDisabledByEnabledFlag() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        TrackingMapOverlay healthy = new TrackingMapOverlay(true);
        TrackingMapOverlay disabled = new TrackingMapOverlay(false);

        manager.register(owner, disabled);
        manager.register(owner, healthy);

        assertEquals(List.of(healthy), manager.mapOverlays(), "expected map overlay list to exclude overlays whose enabled() is false");
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

    private static final class CountingScreenOverlay extends TrackingScreenOverlay {
        private int disposeCalls;

        private CountingScreenOverlay(String name, int priority) {
            super(name, priority);
        }

        @Override
        public void dispose() {
            disposeCalls++;
            super.dispose();
        }
    }

    private static final class DisabledScreenOverlay extends TrackingScreenOverlay {
        private DisabledScreenOverlay(String name, int priority) {
            super(name, priority);
        }

        @Override
        public boolean enabled() {
            return false;
        }
    }

    private static final class TrackingMapOverlay implements MapOverlay {
        private final boolean enabled;

        private TrackingMapOverlay(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean enabled() {
            return enabled;
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
