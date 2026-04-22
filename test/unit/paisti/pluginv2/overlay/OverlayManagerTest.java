package paisti.pluginv2.overlay;

import haven.PaistiServices;
import haven.PView;
import haven.Coord;
import haven.Coord2d;
import haven.ActAudio;
import haven.Loading;
import haven.MapView;
import haven.GameUI;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        TestPlugin owner = new TestPlugin(services);
        List<String> trace = new ArrayList<>();

        manager.register(owner, new TrackingScreenOverlay("late", 10, trace, ScreenOverlayScope.GLOBAL));
        manager.register(owner, new TrackingScreenOverlay("early-a", 0, trace, ScreenOverlayScope.GLOBAL));
        manager.register(owner, new TrackingScreenOverlay("early-b", 0, trace, ScreenOverlayScope.GLOBAL));

        renderScreenOverlays(manager);

        assertEquals(Arrays.asList("early-a", "early-b", "late"), trace);
    }

    @Test
    @Tag("unit")
    void repeatedScreenFailuresDisableOnlyTheBrokenOverlay() {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        TestPlugin owner = new TestPlugin(services);
        TrackingScreenOverlay healthy = new TrackingScreenOverlay("healthy", 0, null, ScreenOverlayScope.GLOBAL);
        ThrowingScreenOverlay broken = new ThrowingScreenOverlay(ScreenOverlayScope.GLOBAL);

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
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        TestPlugin owner = new TestPlugin(services);
        TrackingScreenOverlay healthy = new TrackingScreenOverlay("healthy", 0, null, ScreenOverlayScope.GLOBAL);
        DisabledScreenOverlay disabled = new DisabledScreenOverlay("disabled", 0, ScreenOverlayScope.GLOBAL);

        manager.register(owner, disabled);
        manager.register(owner, healthy);

        assertEquals(List.of(healthy), manager.screenOverlays(), "expected screen overlay list to exclude overlays whose enabled() is false");

        renderScreenOverlays(manager);

        assertEquals(1, healthy.renders, "expected enabled screen overlay to render");
        assertEquals(0, disabled.renders, "expected disabled screen overlay not to render");
    }

    @Test
    @Tag("unit")
    void screenOverlaysEnumerationSkipsOverlayWhoseEnabledThrows() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        ThrowingEnabledScreenOverlay broken = new ThrowingEnabledScreenOverlay();
        TrackingScreenOverlay healthy = new TrackingScreenOverlay("healthy", 0);

        manager.register(owner, broken);
        manager.register(owner, healthy);

        assertEquals(List.of(healthy), manager.screenOverlays(), "expected screen overlay enumeration to skip overlays whose enabled() throws");
        assertEquals(0, broken.disposeCalls, "expected screen overlay enumeration not to permanently disable a throwing enabled() overlay");
    }

    @Test
    @Tag("unit")
    void screenOverlayLoadingIsTreatedAsTransientAndDoesNotDisableOverlay() {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        TestPlugin owner = new TestPlugin(services);
        LoadingScreenOverlay loading = new LoadingScreenOverlay(ScreenOverlayScope.GLOBAL);
        TrackingScreenOverlay healthy = new TrackingScreenOverlay("healthy", 0, null, ScreenOverlayScope.GLOBAL);

        manager.register(owner, loading);
        manager.register(owner, healthy);

        for(int i = 0; i < 6; i++) {
            renderScreenOverlays(manager);
        }

        assertEquals(6, loading.renders, "expected loading overlay to be retried every frame");
        assertEquals(0, loading.disposeCalls, "expected loading overlay not to be disposed as a permanent failure");
        assertEquals(6, healthy.renders, "expected sibling overlays to keep rendering past transient loading");
        assertTrue(!healthy.disposed, "expected healthy overlay to remain active through transient loading");
    }

    @Test
    @Tag("unit")
    void throwingScreenEnabledPredicateIsIsolatedFromRenderPass() {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        TestPlugin owner = new TestPlugin(services);
        ThrowingEnabledScreenOverlay broken = new ThrowingEnabledScreenOverlay(ScreenOverlayScope.GLOBAL);
        TrackingScreenOverlay healthy = new TrackingScreenOverlay("healthy", 0, null, ScreenOverlayScope.GLOBAL);

        manager.register(owner, broken);
        manager.register(owner, healthy);

        for(int i = 0; i < 6; i++) {
            renderScreenOverlays(manager);
        }

        assertEquals(6, healthy.renders, "expected healthy overlay to keep rendering when another overlay's enabled() throws");
        assertEquals(5, broken.enabledCalls, "expected throwing enabled() overlay to be disabled after five failures");
        assertEquals(1, broken.disposeCalls, "expected throwing enabled() overlay to be disposed after repeated failures");
        assertTrue(!healthy.disposed, "expected healthy overlay to remain active when another overlay's enabled() throws");
    }

    @Test
    @Tag("unit")
    void defaultScreenOverlaySkipsRenderWithoutActiveGameUi() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        TestPlugin owner = new TestPlugin(services);
        TrackingScreenOverlay overlay = new TrackingScreenOverlay("gameplay", 0);

        services.bindUi(fakeUi(services));
        manager.register(owner, overlay);

        renderScreenOverlays(manager);

        assertEquals(0, overlay.renders, "expected gameplay-scoped screen overlay not to render without an active GameUI");
        assertEquals(0, screenFailures(manager, overlay), "expected skipped gameplay overlay not to record screen failures");
    }

    @Test
    @Tag("unit")
    void skippedDefaultScreenOverlayDoesNotAccumulateFailuresOrDisable() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        TestPlugin owner = new TestPlugin(services);
        ThrowingScreenOverlay overlay = new ThrowingScreenOverlay();

        services.bindUi(fakeUi(services));
        manager.register(owner, overlay);

        for(int i = 0; i < 6; i++) {
            renderScreenOverlays(manager);
        }

        assertEquals(0, overlay.renders, "expected skipped gameplay overlay never to enter render() without an active GameUI");
        assertEquals(0, overlay.disposeCalls, "expected skipped gameplay overlay not to be disabled while UI is out of game");
        assertEquals(0, screenFailures(manager, overlay), "expected skipped gameplay overlay not to accumulate screen failures");
        assertEquals(List.of(overlay), manager.screenOverlays(), "expected skipped gameplay overlay to remain registered for the next in-game UI");
    }

    @Test
    @Tag("unit")
    void globalScreenOverlayRendersWithoutActiveGameUi() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        TestPlugin owner = new TestPlugin(services);
        TrackingGlobalScreenOverlay overlay = new TrackingGlobalScreenOverlay("global", 0);

        services.bindUi(fakeUi(services));
        manager.register(owner, overlay);

        renderScreenOverlays(manager);

        assertEquals(1, overlay.renders, "expected global screen overlay to keep rendering without an active GameUI");
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
    void repeatedWorldFailuresDisableMapOverlayEvenWhenScreenSucceeds() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        ThrowingMapOverlay broken = ThrowingMapOverlay.worldOnly();

        manager.register(owner, broken);

        for(int i = 0; i < 6; i++) {
            manager.renderMapWorldOverlays(null, null);
            manager.renderMapScreenOverlays(null, null);
        }

        assertEquals(5, broken.worldRenders, "expected broken world phase to be disabled after five failures");
        assertEquals(4, broken.screenRenders, "expected succeeding screen phase to stop once the overlay is disabled by world failures");
        assertEquals(1, broken.disposeCalls, "expected map overlay resources to be disposed when world failures disable it");
        assertTrue(manager.mapOverlays().isEmpty(), "expected broken map overlay to be removed after repeated world failures");
    }

    @Test
    @Tag("unit")
    void repeatedScreenFailuresDisableMapOverlayEvenWhenWorldSucceeds() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        ThrowingMapOverlay broken = ThrowingMapOverlay.screenOnly();

        manager.register(owner, broken);

        for(int i = 0; i < 6; i++) {
            manager.renderMapWorldOverlays(null, null);
            manager.renderMapScreenOverlays(null, null);
        }

        assertEquals(5, broken.screenRenders, "expected broken screen phase to be disabled after five failures");
        assertEquals(5, broken.worldRenders, "expected succeeding world phase to stop once the overlay is disabled by screen failures");
        assertEquals(1, broken.disposeCalls, "expected map overlay resources to be disposed when screen failures disable it");
        assertTrue(manager.mapOverlays().isEmpty(), "expected broken map overlay to be removed after repeated screen failures");
    }

    @Test
    @Tag("unit")
    void mapOverlayLoadingIsTreatedAsTransientAcrossBothRenderPhases() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        LoadingMapOverlay loading = new LoadingMapOverlay();

        manager.register(owner, loading);

        for(int i = 0; i < 6; i++) {
            manager.renderMapWorldOverlays(null, null);
            manager.renderMapScreenOverlays(null, null);
        }

        assertEquals(6, loading.worldCalls, "expected world loading overlay to be retried every frame");
        assertEquals(6, loading.screenCalls, "expected screen loading overlay to be retried every frame");
        assertEquals(0, loading.disposeCalls, "expected loading map overlay not to be disposed as a permanent failure");
    }

    @Test
    @Tag("unit")
    void throwingMapEnabledPredicateIsIsolatedFromBothRenderPhases() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        ThrowingEnabledMapOverlay broken = new ThrowingEnabledMapOverlay();
        TrackingMapOverlay healthy = new TrackingMapOverlay("healthy", 0, null, true);

        manager.register(owner, broken);
        manager.register(owner, healthy);

        for(int i = 0; i < 6; i++) {
            manager.renderMapWorldOverlays(null, null);
            manager.renderMapScreenOverlays(null, null);
        }

        assertEquals(6, healthy.worldCalls, "expected healthy map overlay world phase to keep rendering when another overlay's enabled() throws");
        assertEquals(6, healthy.screenCalls, "expected healthy map overlay screen phase to keep rendering when another overlay's enabled() throws");
        assertTrue(broken.enabledCalls >= 5, "expected throwing map enabled() overlay to be retried until it is disabled");
        assertEquals(1, broken.disposeCalls, "expected throwing map enabled() overlay to be disposed after repeated failures");
    }

    @Test
    @Tag("unit")
    void mapOverlaysEnumerationSkipsOverlayWhoseEnabledThrows() {
        OverlayManager manager = new OverlayManager(new PaistiServices());
        TestPlugin owner = new TestPlugin(new PaistiServices());
        ThrowingEnabledMapOverlay broken = new ThrowingEnabledMapOverlay();
        TrackingMapOverlay healthy = new TrackingMapOverlay("healthy", 0, null, true);

        manager.register(owner, broken);
        manager.register(owner, healthy);

        assertEquals(List.of(healthy), manager.mapOverlays(), "expected map overlay enumeration to skip overlays whose enabled() throws");
        assertEquals(0, broken.disposeCalls, "expected map overlay enumeration not to permanently disable a throwing enabled() overlay");
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

    @Test
    @Tag("unit")
    void bindUiLeavesManagerDetachedWhenMapDrawaddThrows() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        UI ui = fakeUi(services);
        ThrowingDrawaddMapView map = allocate(ThrowingDrawaddMapView.class);

        setRootMap(ui, map);

        RuntimeException error = assertThrows(RuntimeException.class, () -> services.bindUi(ui), "expected bindUi(...) to surface drawadd failures");

        assertEquals("drawadd-boom", error.getMessage(), "expected drawadd failure to come from the map attachment attempt");
        assertNull(attachedMap(manager), "expected failed map bridge attachment to leave the manager detached");
        assertNull(mapSlot(manager), "expected failed map bridge attachment not to retain a slot");
        assertEquals(1, map.drawaddCalls, "expected attachment to attempt drawadd exactly once");
    }

    @Test
    @Tag("unit")
    void startReattachesMapBridgeWhenUiRemainsBoundAfterStop() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        UI ui = fakeUi(services);
        TestMapView map = allocate(TestMapView.class);

        setRootMap(ui, map);
        services.bindUi(ui);
        services.start();

        TestRenderTreeSlot firstSlot = map.lastSlot;
        services.stop();

        assertNull(attachedMap(manager), "expected stop() to clear the active map attachment");
        assertTrue(firstSlot.removed, "expected stop() to remove the active map slot");

        services.start();

        assertSame(map, attachedMap(manager), "expected start() to reattach the bound UI map after stop()");
        assertEquals(2, map.drawaddCalls, "expected restart to attach the map bridge again");
    }

    @Test
    @Tag("unit")
    void throwingScreenScopeIsIsolatedFromRenderPass() throws Exception {
        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();
        TestPlugin owner = new TestPlugin(services);
        ThrowingScopeScreenOverlay broken = new ThrowingScopeScreenOverlay();
        TrackingGlobalScreenOverlay healthy = new TrackingGlobalScreenOverlay("healthy", 0);

        manager.register(owner, broken);
        manager.register(owner, healthy);

        renderScreenOverlays(manager);

        assertEquals(1, broken.scopeCalls, "expected render pass to attempt scope evaluation once");
        assertEquals(1, screenFailures(manager, broken), "expected throwing scope() to count as a screen failure");
        assertEquals(1, healthy.renders, "expected healthy overlay to keep rendering when another overlay's scope() throws");
        assertEquals(0, broken.disposeCalls, "expected one throwing scope() failure not to disable the overlay immediately");
    }

    private static class TrackingScreenOverlay implements ScreenOverlay {
        private final String name;
        private final int priority;
        private final List<String> trace;
        private final ScreenOverlayScope scope;
        private boolean disposed;
        protected int renders;

        private TrackingScreenOverlay(String name, int priority) {
            this(name, priority, null, ScreenOverlayScope.GAMEPLAY);
        }

        private TrackingScreenOverlay(String name, int priority, List<String> trace) {
            this(name, priority, trace, ScreenOverlayScope.GAMEPLAY);
        }

        private TrackingScreenOverlay(String name, int priority, List<String> trace, ScreenOverlayScope scope) {
            this.name = name;
            this.priority = priority;
            this.trace = trace;
            this.scope = scope;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public ScreenOverlayScope scope() {
            return scope;
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

    private static final class TrackingGlobalScreenOverlay extends TrackingScreenOverlay {
        private TrackingGlobalScreenOverlay(String name, int priority) {
            super(name, priority, null, ScreenOverlayScope.GLOBAL);
        }
    }

    private static final class DisabledScreenOverlay extends TrackingScreenOverlay {
        private DisabledScreenOverlay(String name, int priority) {
            super(name, priority);
        }

        private DisabledScreenOverlay(String name, int priority, ScreenOverlayScope scope) {
            super(name, priority, null, scope);
        }

        @Override
        public boolean enabled() {
            return false;
        }
    }

    private static final class LoadingScreenOverlay extends TrackingScreenOverlay {
        private int disposeCalls;

        private LoadingScreenOverlay() {
            super("loading", 0);
        }

        private LoadingScreenOverlay(ScreenOverlayScope scope) {
            super("loading", 0, null, scope);
        }

        @Override
        public void render(ScreenOverlayContext ctx) {
            renders++;
            throw new Loading();
        }

        @Override
        public void dispose() {
            disposeCalls++;
            super.dispose();
        }
    }

    private static final class ThrowingEnabledScreenOverlay extends TrackingScreenOverlay {
        private int enabledCalls;
        private int disposeCalls;

        private ThrowingEnabledScreenOverlay() {
            super("enabled-broken", 0);
        }

        private ThrowingEnabledScreenOverlay(ScreenOverlayScope scope) {
            super("enabled-broken", 0, null, scope);
        }

        @Override
        public boolean enabled() {
            enabledCalls++;
            throw new RuntimeException("enabled-boom");
        }

        @Override
        public void dispose() {
            disposeCalls++;
            super.dispose();
        }
    }

    private static final class ThrowingScopeScreenOverlay extends TrackingScreenOverlay {
        private int scopeCalls;
        private int disposeCalls;

        private ThrowingScopeScreenOverlay() {
            super("scope-broken", 0, null, ScreenOverlayScope.GLOBAL);
        }

        @Override
        public ScreenOverlayScope scope() {
            scopeCalls++;
            throw new RuntimeException("scope-boom");
        }

        @Override
        public void dispose() {
            disposeCalls++;
            super.dispose();
        }
    }

    private static final class TrackingMapOverlay implements MapOverlay {
        private final String name;
        private final int priority;
        private final List<String> trace;
        private final boolean enabled;
        private MapView lastWorldMap;
        private MapView lastScreenMap;
        private int worldCalls;
        private int screenCalls;

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
            worldCalls++;
            lastWorldMap = ctx.map();
            if(trace != null) {
                trace.add("world:" + name);
            }
        }

        @Override
        public void renderScreen(MapScreenOverlayContext ctx) {
            screenCalls++;
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

        private ThrowingScreenOverlay(ScreenOverlayScope scope) {
            super("broken", 0, null, scope);
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

    private static final class ThrowingMapOverlay implements MapOverlay {
        private final boolean failWorld;
        private final boolean failScreen;
        private int worldRenders;
        private int screenRenders;
        private int disposeCalls;

        private ThrowingMapOverlay(boolean failWorld, boolean failScreen) {
            this.failWorld = failWorld;
            this.failScreen = failScreen;
        }

        private static ThrowingMapOverlay worldOnly() {
            return new ThrowingMapOverlay(true, false);
        }

        private static ThrowingMapOverlay screenOnly() {
            return new ThrowingMapOverlay(false, true);
        }

        @Override
        public void renderWorld(MapWorldOverlayContext ctx) {
            worldRenders++;
            if(failWorld) {
                throw new RuntimeException("world-boom");
            }
        }

        @Override
        public void renderScreen(MapScreenOverlayContext ctx) {
            screenRenders++;
            if(failScreen) {
                throw new RuntimeException("screen-boom");
            }
        }

        @Override
        public void dispose() {
            disposeCalls++;
        }
    }

    private static final class LoadingMapOverlay implements MapOverlay {
        private int worldCalls;
        private int screenCalls;
        private int disposeCalls;

        @Override
        public void renderWorld(MapWorldOverlayContext ctx) {
            worldCalls++;
            throw new Loading();
        }

        @Override
        public void renderScreen(MapScreenOverlayContext ctx) {
            screenCalls++;
            throw new Loading();
        }

        @Override
        public void dispose() {
            disposeCalls++;
        }
    }

    private static final class ThrowingEnabledMapOverlay implements MapOverlay {
        private int enabledCalls;
        private int disposeCalls;

        @Override
        public boolean enabled() {
            enabledCalls++;
            throw new RuntimeException("enabled-boom");
        }

        @Override
        public void dispose() {
            disposeCalls++;
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

    private static int screenFailures(OverlayManager manager, ScreenOverlay overlay) throws Exception {
        Object registration = registrationFor(manager, overlay);
        return getField(registration.getClass(), registration, "screenFailures", Integer.class);
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

    private static Object registrationFor(OverlayManager manager, PluginOverlay overlay) throws Exception {
        CopyOnWriteArrayList<?> registrations = getField(OverlayManager.class, manager, "overlays", CopyOnWriteArrayList.class);
        for(Object registration : registrations) {
            PluginOverlay registeredOverlay = getField(registration.getClass(), registration, "overlay", PluginOverlay.class);
            if(registeredOverlay == overlay) {
                return registration;
            }
        }
        throw new AssertionError("expected overlay registration to exist for " + overlay.id());
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

    private static final class ThrowingDrawaddMapView extends MapView {
        private int drawaddCalls;
        private RenderTree.Node lastNode;

        private ThrowingDrawaddMapView() {
            super(Coord.z, null, (Coord2d) null, 0);
        }

        @Override
        public RenderTree.Slot drawadd(RenderTree.Node extra) {
            drawaddCalls++;
            lastNode = extra;
            throw new RuntimeException("drawadd-boom");
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
