package haven;

import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class GLPanelLoopTest {
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
        private TestRootWidget(UI ui, Coord sz) {
            super(ui, sz);
        }

        @Override
        protected GobEffects createEffects(UI ui) {
            return null;
        }

        @Override
        public void tick(double dt) {
        }

        @Override
        public void draw(GOut g) {
        }
    }

    private static final class TrackingUI extends UI {
        private int destroyCalls;

        private TrackingUI(GLPanel panel, UI.Runner runner) {
            super(panel, Coord.of(10, 10), runner);
        }

        @Override
        protected RootWidget createRoot(Coord sz) {
            return new TestRootWidget(this, sz);
        }

        @Override
        public void destroy() {
            destroyCalls++;
            super.destroy();
        }
    }

    private static class TestLoop extends GLPanel.Loop {
        private final GLPanel panel;

        private TestLoop(GLPanel panel) {
            super(panel);
            this.panel = panel;
        }

        @Override
        protected UI makeui(UI.Runner runner) {
            return new TrackingUI(panel, runner);
        }

        private TrackingUI currentUi() {
            return (TrackingUI)ui;
        }
    }

    private static final class ManagedLoop extends TestLoop {
        private UI managedUi;
        private UI reuseUi;

        private ManagedLoop(GLPanel panel) {
            super(panel);
        }

        @Override
        protected boolean isManagedUi(UI ui) {
            return ui == managedUi;
        }

        @Override
        protected UI reuseManagedUiForRunner(UI.Runner runner) {
            return reuseUi;
        }
    }

    @Test
    @Tag("unit")
    void teardownDestroysUnmanagedUiAndClearsLoopState() throws Exception {
        TestLoop loop = new TestLoop(new DummyPanel());
        TrackingUI first = (TrackingUI)loop.newui(null);

        Method teardown = GLPanel.Loop.class.getDeclaredMethod("onLoopTeardown");
        teardown.setAccessible(true);
        teardown.invoke(loop);

        assertEquals(1, first.destroyCalls);
        assertNull(loop.currentUi());

        loop.newui(null);

        assertEquals(1, first.destroyCalls, "cleared UI must not be destroyed twice by later newui()");
    }

    @Test
    @Tag("unit")
    void newuiLeavesManagedPreviousUiLifecycleToSubclass() {
        ManagedLoop loop = new ManagedLoop(new DummyPanel());
        TrackingUI managed = (TrackingUI)loop.newui(null);
        loop.managedUi = managed;

        TrackingUI login = (TrackingUI)loop.newui(new Bootstrap());

        assertEquals(0, managed.destroyCalls);
        assertNotSame(managed, login);
    }

    @Test
    @Tag("unit")
    void newuiCanReuseSubclassManagedUiForRunnerTransition() {
        ManagedLoop loop = new ManagedLoop(new DummyPanel());
        TrackingUI managed = (TrackingUI)loop.newui(null);
        loop.reuseUi = managed;

        UI reused = loop.newui(new Bootstrap());

        assertSame(managed, reused);
        assertEquals(0, managed.destroyCalls);
    }
}
