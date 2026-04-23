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

import java.awt.Canvas;

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

        @Override
        protected RootWidget createRoot(Coord sz) {
            return new TestRootWidget(this, sz);
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

        // tick() registers the screenOverlayAfterDraw callback
        pui.tick();
        // draw() executes afterdraw callbacks which render screen overlays
        pui.draw(null);

        assertEquals(1, overlay.renders, "screen overlays must be rendered via the AfterDraw mechanism after tick() + draw()");
        assertFalse(overlay.disposed, "rendering must not dispose the overlay");
    }
}
