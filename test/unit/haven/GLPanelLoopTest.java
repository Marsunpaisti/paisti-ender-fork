package haven;

import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

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

        private TrackingUI(GLPanel panel) {
            super(panel, Coord.of(10, 10), null);
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

    private static final class TestLoop extends GLPanel.Loop {
        private final GLPanel panel;

        private TestLoop(GLPanel panel) {
            super(panel);
            this.panel = panel;
        }

        @Override
        protected UI makeui(UI.Runner fun) {
            return new TrackingUI(panel);
        }

        private TrackingUI currentUi() {
            return (TrackingUI) ui;
        }
    }

    @Test
    @Tag("unit")
    void teardownDoesNotLeaveStaleUiForLaterNewui() throws Exception {
        TestLoop loop = new TestLoop(new DummyPanel());
        TrackingUI first = (TrackingUI) loop.newui(null);

        Method teardown = GLPanel.Loop.class.getDeclaredMethod("onLoopTeardown");
        teardown.setAccessible(true);
        teardown.invoke(loop);

        assertEquals(1, first.destroyCalls, "teardown should destroy the active UI once");
        assertNull(loop.currentUi(), "teardown must clear loop.ui so later newui() does not see a stale previous UI");

        loop.newui(null);

        assertEquals(1, first.destroyCalls, "newui() after teardown must not destroy the old UI a second time");
    }
}
