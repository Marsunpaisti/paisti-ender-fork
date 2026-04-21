package paisti.pluginv2.overlay;

import haven.PaistiServices;
import haven.PView;
import haven.Coord;
import haven.Coord2d;
import haven.ActAudio;
import haven.MapView;
import haven.RootWidget;
import haven.UI;
import haven.render.Pipe;
import haven.render.Render;
import haven.render.GroupPipe;
import haven.render.RenderTree;
import haven.render.Rendered;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.pluginv2.PluginDescription;
import paisti.pluginv2.PaistiPlugin;
import sun.misc.Unsafe;

import java.util.ArrayList;
import java.util.Arrays;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(1, broken.disposeCalls, "expected broken overlay resources to be disposed immediately once it is permanently disabled");
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
        TrackingMapOverlay healthy = new TrackingMapOverlay("healthy", 0, null, true);
        TrackingMapOverlay disabled = new TrackingMapOverlay("disabled", 0, null, false);

        manager.register(owner, disabled);
        manager.register(owner, healthy);

        assertEquals(List.of(healthy), manager.mapOverlays(), "expected map overlay list to exclude overlays whose enabled() is false");
    }

    @Test
    @Tag("unit")
    void mapOverlaysRenderInPriorityThenRegistrationOrder() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        List<String> trace = new ArrayList<>();

        manager.register(owner, new TrackingMapOverlay("late", 10, trace, true));
        manager.register(owner, new TrackingMapOverlay("early-a", 0, trace, true));
        manager.register(owner, new TrackingMapOverlay("early-b", 0, trace, true));

        manager.renderMapWorldOverlays((Pipe) null, (Render) null);
        manager.renderMapScreenOverlays(null, null);

        assertEquals(Arrays.asList(
            "world:early-a",
            "world:early-b",
            "world:late",
            "screen:early-a",
            "screen:early-b",
            "screen:late"
        ), trace);
    }

    @Test
    @Tag("unit")
    void mapRenderDispatchUsesAttachedBridgeMapInsteadOfReResolvedCurrentMap() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        TestPlugin owner = new TestPlugin(services);
        UI ui = fakeUi(services);
        TestMapView attached = allocate(TestMapView.class);
        TestMapView current = allocate(TestMapView.class);
        TrackingMapOverlay overlay = new TrackingMapOverlay("tracked", 0, null, true);

        setRootMap(ui, attached);
        services.bindUi(ui);
        manager.register(owner, overlay);

        setRootMap(ui, current);
        manager.renderMapWorldOverlays((Pipe) null, (Render) null);
        manager.renderMapScreenOverlays(null, null);

        assertSame(attached, overlay.lastWorldMap, "expected world map overlay context to use the map that owns the active bridge");
        assertSame(attached, overlay.lastScreenMap, "expected screen map overlay context to use the map that owns the active bridge");
    }

    @Test
    @Tag("unit")
    void mapOverlayBridgeImplementsExpectedRenderInterfaces() throws Exception {
        Class<?> type = Class.forName("paisti.pluginv2.overlay.MapOverlayBridge");

        assertTrue(RenderTree.Node.class.isAssignableFrom(type), "expected map bridge to implement RenderTree.Node");
        assertTrue(Rendered.class.isAssignableFrom(type), "expected map bridge to implement Rendered");
        assertTrue(PView.Render2D.class.isAssignableFrom(type), "expected map bridge to implement PView.Render2D");
    }

    @Test
    @Tag("unit")
    void bindUiAttachesAlreadyPresentMapBridgeWithoutWaitingForDraw() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        UI ui = fakeUi(services);
        TestMapView map = allocate(TestMapView.class);

        setRootMap(ui, map);
        services.bindUi(ui);

        assertSame(map, attachedMap(manager), "expected bindUi(...) to attach an already-present map bridge immediately");
        assertEquals(1, map.drawaddCalls, "expected current map to receive the bridge without waiting for a later draw");
        assertNotNull(map.lastSlot, "expected map bridge attachment to create a slot");
    }

    @Test
    @Tag("unit")
    void bindUiReplacesMapBridgeAttachmentWithoutWaitingForDraw() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        UI firstUi = fakeUi(services);
        UI secondUi = fakeUi(services);
        TestMapView firstMap = allocate(TestMapView.class);
        TestMapView secondMap = allocate(TestMapView.class);

        setRootMap(firstUi, firstMap);
        services.bindUi(firstUi);

        TestRenderTreeSlot firstSlot = firstMap.lastSlot;
        setRootMap(secondUi, secondMap);

        services.bindUi(secondUi);

        assertTrue(firstSlot.removed, "expected rebinding to a new UI to promptly remove the old map bridge slot");
        assertSame(secondMap, attachedMap(manager), "expected rebinding to attach the bridge to the replacement UI map immediately");
        assertEquals(1, secondMap.drawaddCalls, "expected replacement map to receive the bridge without waiting for a later draw");
    }

    @Test
    @Tag("unit")
    void clearUiDetachesMapBridgePromptly() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        UI ui = fakeUi(services);
        TestMapView map = allocate(TestMapView.class);

        setRootMap(ui, map);
        services.bindUi(ui);

        TestRenderTreeSlot slot = map.lastSlot;
        services.clearUi(ui);

        assertNull(attachedMap(manager), "expected clearUi(...) to detach the active map promptly");
        assertNull(mapSlot(manager), "expected clearUi(...) to clear the manager's map slot promptly");
        assertTrue(slot.removed, "expected clearUi(...) to remove the stale map bridge slot immediately");
    }

    @Test
    @Tag("unit")
    void setGuiResyncsMapBridgeWithinSameUi() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        UI ui = fakeUi(services);
        TestMapView firstMap = allocate(TestMapView.class);
        TestMapView secondMap = allocate(TestMapView.class);

        setRootMap(ui, firstMap);
        services.bindUi(ui);

        TestRenderTreeSlot firstSlot = firstMap.lastSlot;
        setRootMap(ui, secondMap);
        ui.setGUI(null);

        assertTrue(firstSlot.removed, "expected UI.setGUI(...) to remove the stale bridge slot when the current map changes inside one UI");
        assertSame(secondMap, attachedMap(manager), "expected UI.setGUI(...) to attach the bridge to the replacement map immediately");
        assertEquals(1, secondMap.drawaddCalls, "expected replacement map to receive the bridge immediately from UI.setGUI(...)");
    }

    @Test
    @Tag("unit")
    void clearGuiResyncsMapBridgeWithinSameUi() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        UI ui = fakeUi(services);
        TestMapView map = allocate(TestMapView.class);

        setRootMap(ui, map);
        services.bindUi(ui);

        TestRenderTreeSlot slot = map.lastSlot;
        setRootMap(ui, null);
        ui.clearGUI(null);

        assertNull(attachedMap(manager), "expected UI.clearGUI(...) to detach the active map bridge when the UI no longer exposes a map");
        assertNull(mapSlot(manager), "expected UI.clearGUI(...) to clear the active map slot immediately");
        assertTrue(slot.removed, "expected UI.clearGUI(...) to remove the stale bridge slot immediately");
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
        private final String name;
        private final int priority;
        private final List<String> trace;
        private final boolean enabled;
        private MapView lastWorldMap;
        private MapView lastScreenMap;

        private TrackingMapOverlay(String name, int priority, List<String> trace, boolean enabled) {
            this.name = name;
            this.priority = priority;
            this.trace = trace;
            this.enabled = enabled;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public void renderWorld(MapWorldOverlayContext ctx) {
            lastWorldMap = ctx.map();
            if(trace != null) {
                trace.add("world:" + name);
            }
        }

        @Override
        public void renderScreen(MapScreenOverlayContext ctx) {
            lastScreenMap = ctx.map();
            if(trace != null) {
                trace.add("screen:" + name);
            }
        }
    }

    private static final class ThrowingScreenOverlay extends TrackingScreenOverlay {
        private int disposeCalls;

        private ThrowingScreenOverlay() {
            super("broken", 0);
        }

        @Override
        public void render(ScreenOverlayContext ctx) {
            renders++;
            throw new RuntimeException("boom");
        }

        @Override
        public void dispose() {
            disposeCalls++;
            super.dispose();
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

    private static UI fakeUi(PaistiServices services) throws Exception {
        UI ui = allocate(UI.class);
        setField(UI.class, ui, "paistiServices", services);
        setField(UI.class, ui, "guiLock", new Object());
        setField(UI.class, ui, "audio", new ActAudio.Root());
        setField(UI.class, ui, "root", new TestRootWidget(ui));
        return ui;
    }

    private static void setRootMap(UI ui, MapView map) {
        ((TestRootWidget) ui.root).mapChild = map;
    }

    private static MapView attachedMap(OverlayManager manager) throws Exception {
        return getField(OverlayManager.class, manager, "attachedMap", MapView.class);
    }

    private static RenderTree.Slot mapSlot(OverlayManager manager) throws Exception {
        return getField(OverlayManager.class, manager, "mapSlot", RenderTree.Slot.class);
    }

    private static <T> T allocate(Class<T> cl) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return cl.cast(unsafe.allocateInstance(cl));
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T getField(Class<?> owner, Object target, String name, Class<T> type) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static final class TestMapView extends MapView {
        private int drawaddCalls;
        private RenderTree.Node lastNode;
        private TestRenderTreeSlot lastSlot;

        private TestMapView() {
            super(Coord.z, null, (Coord2d) null, 0);
        }

        @Override
        public RenderTree.Slot drawadd(RenderTree.Node extra) {
            drawaddCalls++;
            lastNode = extra;
            lastSlot = new TestRenderTreeSlot(extra);
            return lastSlot;
        }
    }

    private static final class TestRootWidget extends RootWidget {
        private MapView mapChild;

        private TestRootWidget(UI ui) {
            super(ui, Coord.z);
        }

        @Override
        protected me.ender.gob.GobEffects createEffects(UI ui) {
            return null;
        }

        @Override
        public <T extends haven.Widget> T findchild(Class<T> cl) {
            if((mapChild != null) && cl.isInstance(mapChild)) {
                return cl.cast(mapChild);
            }
            return null;
        }
    }

    private static final class TestRenderTreeSlot implements RenderTree.Slot {
        private final RenderTree.Node node;
        private boolean removed;

        private TestRenderTreeSlot(RenderTree.Node node) {
            this.node = node;
        }

        @Override
        public GroupPipe state() {
            return null;
        }

        @Override
        public RenderTree.Node obj() {
            return node;
        }

        @Override
        public RenderTree.Slot add(RenderTree.Node n, Pipe.Op state) {
            return null;
        }

        @Override
        public void remove() {
            removed = true;
        }

        @Override
        public void clear() {
        }

        @Override
        public void cstate(Pipe.Op state) {
        }

        @Override
        public void ostate(Pipe.Op state) {
        }

        @Override
        public RenderTree.Slot parent() {
            return null;
        }

        @Override
        public void update() {
        }
    }
}
