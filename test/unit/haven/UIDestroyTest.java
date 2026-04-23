package haven;

import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;

import static org.junit.jupiter.api.Assertions.*;

class UIDestroyTest {
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

    private static final class TestUI extends UI {
        private TestUI() {
            super(new DummyPanel(), Coord.of(10, 10), null);
        }

        @Override
        protected RootWidget createRoot(Coord sz) {
            return new TestRootWidget(this, sz);
        }
    }

    private static final class TrackingWidget extends Widget {
        private int disposeCalls;

        private TrackingWidget() {
            super(Coord.z);
        }

        @Override
        public void dispose() {
            disposeCalls++;
            super.dispose();
        }
    }

    @Test
    @Tag("unit")
    void destroyRemovesWidgetIdsForEntireTree() {
        UI ui = new TestUI();
        TrackingWidget child = new TrackingWidget();

        ui.root.add(child, Coord.z);
        ui.bind(child, 1);

        assertSame(child, ui.getwidget(1), "sanity check: child must be bound before destroy");

        ui.destroy();

        assertEquals(1, child.disposeCalls, "destroy() should dispose child exactly once");
        assertNull(ui.getwidget(1), "destroy() must remove child ids so queued dstwdg commands cannot redispose widgets");
        assertEquals(-1, ui.widgetid(child), "destroy() must clear reverse widget ids as well");
    }
}
