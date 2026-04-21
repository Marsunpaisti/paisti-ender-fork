package haven;

import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.pluginv2.PaistiPlugin;
import sun.misc.Unsafe;

import java.awt.Canvas;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaistiServicesLifetimeTest {
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

    @SuppressWarnings("unchecked")
    private static Collection<PaistiPlugin> activePlugins(PaistiServices services) throws Exception {
        Field field = services.pluginService().getClass().getDeclaredField("activePlugins");
        field.setAccessible(true);
        return (Collection<PaistiPlugin>) field.get(services.pluginService());
    }

    private static UI fakeUi(PaistiServices services) throws Exception {
        UI ui = allocate(UI.class);
        setField(UI.class, ui, "paistiServices", services);
        setField(UI.class, ui, "root", allocate(TestRootWidget.class));
        setField(UI.class, ui, "audio", new TestAudioRoot());
        setField(UI.class, ui, "cons", new Console());
        return ui;
    }

    private static final class DummyPanel extends Canvas implements GLPanel {
        @Override
        public GLEnvironment env() {
            return null;
        }

        @Override
        public Area shape() {
            return Area.sized(Coord.z, Coord.of(10, 10));
        }

        @Override
        public Pipe basestate() {
            return null;
        }

        @Override
        public void glswap(haven.render.gl.GL gl) {
        }

        @Override
        public void setmousepos(Coord c) {
        }

        @Override
        public UI newui(UI.Runner fun) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void background(boolean bg) {
        }

        @Override
        public void run() {
        }
    }

    private static final class TestLoop extends GLPanel.Loop {
        private TestLoop() {
            super(new DummyPanel());
        }

        @Override
        protected UI makeui(UI.Runner fun, PaistiServices services) {
            try {
                return fakeUi(services);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private void shutdownForTest() {
            shutdownServices();
        }
    }

    private static final class OrderCheckingLoop extends GLPanel.Loop {
        private boolean swapCommittedOnStart = false;
        private UI startedUi = null;
        private PaistiServices capturedServices = null;
        private UI createdUi = null;

        private OrderCheckingLoop() {
            super(new DummyPanel());
        }

        @Override
        protected UI makeui(UI.Runner fun, PaistiServices services) {
            try {
                capturedServices = services;
                createdUi = fakeUi(services);
                return createdUi;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        protected void startSharedServices() {
            startedUi = capturedServices.ui();
            swapCommittedOnStart = (startedUi == createdUi) && (ui == createdUi);
            super.startSharedServices();
        }
    }

    private static final class TestableUI extends UI {
        private UI boundUiDuringRootCreation;
        private PaistiServices servicesDuringRootCreation;

        TestableUI(Context uictx, Coord sz, Runner fun, PaistiServices services) {
            super(uictx, sz, fun, services);
        }

        TestableUI(Context uictx, Coord sz, Runner fun) {
            super(uictx, sz, fun);
        }

        @Override
        protected RootWidget createRoot(Coord sz) {
            servicesDuringRootCreation = services();
            boundUiDuringRootCreation = servicesDuringRootCreation.ui();
            return new TestRootWidget(this, sz);
        }
    }

    private static final class TestRootWidget extends RootWidget {
        private boolean destroyed;

        private TestRootWidget() {
            super(null, null);
        }

        private TestRootWidget(UI ui, Coord sz) {
            super(ui, sz);
        }

        @Override
        protected GobEffects createEffects(UI ui) {
            return null;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }

    private static final class TestAudioRoot extends ActAudio.Root {
        private boolean cleared;

        @Override
        public void clear() {
            cleared = true;
        }
    }

    @Test
    @Tag("unit")
    void bindUiTracksCurrentActiveUi() throws Exception {
        PaistiServices services = new PaistiServices();
        Constructor<UI> constructor = UI.class.getConstructor(UI.Context.class, Coord.class, UI.Runner.class, PaistiServices.class);
        UI first = allocate(UI.class);
        UI second = allocate(UI.class);

        assertNotNull(constructor);

        assertNull(services.ui());

        services.bindUi(first);
        assertSame(first, services.ui());

        services.bindUi(second);
        assertSame(second, services.ui());

        services.clearUi(first);
        assertSame(second, services.ui());

        services.clearUi(second);
        assertNull(services.ui());
    }

    @Test
    @Tag("unit")
    void destroyClearsBoundUiAndTearsDownUiResources() throws Exception {
        PaistiServices services = new PaistiServices();
        UI ui = allocate(UI.class);
        TestRootWidget root = allocate(TestRootWidget.class);
        TestAudioRoot audio = new TestAudioRoot();

        setField(UI.class, ui, "paistiServices", services);
        setField(UI.class, ui, "root", root);
        setField(UI.class, ui, "audio", audio);
        services.bindUi(ui);

        ui.destroy();

        assertNull(services.ui());
        assertTrue(root.destroyed);
        assertTrue(audio.cleared);
    }

    @Test
    @Tag("unit")
    void loopReusesSamePaistiServicesAcrossUiSwaps() {
        GLPanel.Loop loop = new TestLoop();

        UI first = loop.newui(null);
        UI second = loop.newui(null);

        assertSame(first.services(), second.services());
    }

    @Test
    @Tag("unit")
    void loopReusesSamePluginInstancesAcrossUiSwaps() {
        GLPanel.Loop loop = new TestLoop();

        UI first = loop.newui(null);
        List<PaistiPlugin> firstLoad = new ArrayList<>(first.services().pluginService().getLoadedPlugins());
        UI second = loop.newui(null);
        List<PaistiPlugin> secondLoad = new ArrayList<>(second.services().pluginService().getLoadedPlugins());

        assertFalse(firstLoad.isEmpty());
        assertSame(firstLoad.get(0), secondLoad.get(0));
    }

    @Test
    @Tag("unit")
    void loopRebindsServicesToLatestActiveUiAfterSwap() {
        GLPanel.Loop loop = new TestLoop();

        UI first = loop.newui(null);
        PaistiServices services = first.services();
        UI second = loop.newui(null);

        assertSame(second, services.ui());
    }

    @Test
    @Tag("unit")
    void loopKeepsActivePluginsRunningAcrossUiSwaps() throws Exception {
        GLPanel.Loop loop = new TestLoop();

        UI first = loop.newui(null);
        PaistiServices services = first.services();
        PaistiPlugin plugin = first.services().pluginService().getLoadedPlugins().iterator().next();
        first.services().pluginService().startPlugin(plugin);

        assertTrue(activePlugins(services).contains(plugin));

        loop.newui(null);

        assertTrue(activePlugins(services).contains(plugin));
    }

    @Test
    @Tag("unit")
    void loopFinalTeardownStopsActivePlugins() throws Exception {
        TestLoop loop = new TestLoop();

        UI ui = loop.newui(null);
        PaistiServices services = ui.services();
        PaistiPlugin plugin = services.pluginService().getLoadedPlugins().iterator().next();
        services.pluginService().startPlugin(plugin);

        assertTrue(activePlugins(services).contains(plugin));

        loop.shutdownForTest();

        assertFalse(activePlugins(services).contains(plugin));
    }

    @Test
    @Tag("unit")
    void sharedServicesStartAfterUiBound() {
        OrderCheckingLoop loop = new OrderCheckingLoop();

        UI ui = loop.newui(null);

        assertTrue(loop.swapCommittedOnStart, "Shared services must start only after the swap is committed");
        assertSame(ui, loop.startedUi, "The started UI must be the committed active UI");
    }

    @Test
    @Tag("unit")
    void fourArgConstructorDoesNotSelfBind() throws Exception {
        PaistiServices services = new PaistiServices();
        TestableUI ui = new TestableUI(new DummyPanel(), Coord.of(10, 10), null, services);

        assertNull(services.ui(), "4-arg UI constructor must not implicitly bind to shared PaistiServices");
        assertSame(services, ui.servicesDuringRootCreation, "4-arg constructor must expose the injected PaistiServices during root creation");
        assertNull(ui.boundUiDuringRootCreation, "4-arg constructor must not bind injected PaistiServices during root creation");
        assertNotNull(ui.root, "root widget must be created by the constructor");
    }

    @Test
    @Tag("unit")
    void threeArgConstructorSelfBinds() throws Exception {
        TestableUI ui = new TestableUI(new DummyPanel(), Coord.of(10, 10), null);

        assertNotNull(ui.services(), "3-arg constructor must create its own PaistiServices");
        assertSame(ui.services(), ui.servicesDuringRootCreation, "3-arg constructor must create services before root creation");
        assertSame(ui, ui.boundUiDuringRootCreation, "3-arg constructor must self-bind before root creation");
        assertSame(ui, ui.services().ui(), "3-arg constructor must self-bind to its own PaistiServices");
        assertNotNull(ui.root, "root widget must be created by the constructor");
    }
}
