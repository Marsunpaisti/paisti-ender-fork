package paisti.client;

import haven.*;
import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.plugin.PluginDescription;
import paisti.plugin.PaistiPlugin;
import paisti.plugin.overlay.ScreenOverlay;
import paisti.plugin.overlay.ScreenOverlayContext;
import paisti.plugin.overlay.ScreenOverlayScope;
import sun.misc.Unsafe;

import java.awt.Canvas;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class PUILifecycleTest {

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

    private static final class TestRootWidget extends RootWidget {
        private boolean destroyed;

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

        @Override
        public void tick(double dt) {
            // skip effects.tick() which NPEs when effects is null
        }

        @Override
        public void draw(GOut g) {
        }
    }

    /**
     * Test subclass of PUI that overrides createRoot to avoid loading
     * real game resources during unit tests.
     */
    private static class TestPUI extends PUI {
        TestPUI() {
            super(new DummyPanel(), Coord.of(10, 10), null);
        }

        TestPUI(Session sess) {
            super(new DummyPanel(), Coord.of(10, 10), new SessionRunner(sess));
        }

        @Override
        protected RootWidget createRoot(Coord sz) {
            return new TestRootWidget(this, sz);
        }
    }

    private static final class SessionRunner implements UI.Runner {
        private final Session sess;

        private SessionRunner(Session sess) {
            this.sess = sess;
        }

        @Override
        public UI.Runner run(UI ui) {
            return null;
        }

        @Override
        public void init(UI ui) {
            ui.sess = sess;
            sess.ui = ui;
        }
    }

    @PluginDescription(name = "Lifecycle Test Plugin", configName = "pui-lifecycle-test")
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
    }

    private static class TrackingScreenOverlay implements ScreenOverlay {
        private boolean disposed;
        private int renders;

        @Override
        public ScreenOverlayScope scope() {
            return ScreenOverlayScope.GLOBAL;
        }

        @Override
        public void render(ScreenOverlayContext ctx) {
            renders++;
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private static Session session(String name) {
        try {
            Session sess = allocate(Session.class);
            setField(Session.class, sess, "conn", new Transport.Playback(new java.io.StringReader("")));
            setField(Session.class, sess, "user", new Session.User(name));
            setField(Session.class, sess, "character", new CharacterInfo(sess));
            setField(Session.class, sess, "glob", new Glob(sess));
            return sess;
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
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

    @Test
    @Tag("unit")
    void puiConstructorCreatesFreshServices() {
        PUI pui = new TestPUI();

        assertNotNull(pui.services(), "PUI constructor must create PaistiServices");
        assertNotNull(pui.eventBus(), "PUI must expose an event bus");
        assertNotNull(pui.pluginService(), "PUI must expose a plugin service");
        assertNotNull(pui.overlayManager(), "PUI must expose an overlay manager");
        assertSame(pui, pui.services().ui(), "services must be bound to the PUI that created them");
    }

    @Test
    @Tag("unit")
    void puiDestroyStopsServicesAndClearsUi() {
        PUI pui = new TestPUI();
        PaistiServices services = pui.services();
        TrackingScreenOverlay overlay = new TrackingScreenOverlay();

        services.overlayManager().register(new TestPlugin(services), overlay);

        pui.destroy();

        assertNull(services.ui(), "destroy() must clear the services' UI reference");
        assertTrue(overlay.disposed, "destroy() must dispose registered overlays via services.stop()");
    }

    @Test
    @Tag("unit")
    void eachPuiGetsFreshIndependentServices() {
        PUI first = new TestPUI();
        PUI second = new TestPUI();

        assertNotSame(first.services(), second.services(), "each PUI must get its own PaistiServices");
        assertNotSame(first.eventBus(), second.eventBus(), "each PUI must get its own EventBus");
    }

    @Test
    @Tag("unit")
    void puiOfCastsUiToPui() {
        PUI pui = new TestPUI();
        UI ui = pui;

        assertSame(pui, PUI.of(ui));
    }

    @Test
    @Tag("unit")
    void screenOverlaysRenderedViaAfterDraw() {
        PUI pui = new TestPUI();
        TrackingScreenOverlay overlay = new TrackingScreenOverlay();

        pui.services().overlayManager().register(new TestPlugin(pui.services()), overlay);

        // draw() renders screen overlays after all widget + afterdraw rendering
        pui.draw(null);

        assertEquals(1, overlay.renders, "screen overlays must be rendered via PUI.draw() after the base UI draw pass");
        assertFalse(overlay.disposed, "rendering must not dispose the overlay");
    }

    @Test
    @Tag("unit")
    void gobFactoryIsScopedPerSessionAndSurvivesOtherPuiDestroy() {
        Session firstSession = session("first");
        Session secondSession = session("second");
        PUI first = new TestPUI(firstSession);
        PUI second = new TestPUI(secondSession);

        try {
            assertInstanceOf(PGob.class,
                    firstSession.glob.gobFactory.create(firstSession.glob, Coord2d.z, 1L),
                    "PUI must bind PGob creation onto its own session glob");
            assertInstanceOf(PGob.class,
                    secondSession.glob.gobFactory.create(secondSession.glob, Coord2d.z, 2L),
                    "each session must keep its own PGob factory");

            first.destroy();

            assertInstanceOf(PGob.class,
                    secondSession.glob.gobFactory.create(secondSession.glob, Coord2d.z, 3L),
                    "destroying one PUI must not reset gob creation for another live session");
        } finally {
            second.destroy();
        }
    }
}
