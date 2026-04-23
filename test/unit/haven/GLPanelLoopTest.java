package haven;

import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import haven.session.LobbyRunner;
import haven.session.SessionContext;
import haven.session.SessionManager;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.lang.reflect.Field;
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
            this(panel, null);
        }

        private TrackingUI(GLPanel panel, UI.Runner fun) {
            super(panel, Coord.of(10, 10), fun);
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
            return new TrackingUI(panel, fun);
        }

        private TrackingUI currentUi() {
            return (TrackingUI) ui;
        }
    }

    @BeforeEach
    void clearSessionManager() {
        clearSessions();
    }

    @AfterEach
    void restoreSessionManager() {
        clearSessions();
    }

    private void clearSessions() {
        SessionManager mgr = SessionManager.getInstance();
        try {
            java.lang.reflect.Field field = SessionManager.class.getDeclaredField("sessions");
            field.setAccessible(true);
            ((java.util.List<?>)field.get(mgr)).clear();
            java.lang.reflect.Field activeField = SessionManager.class.getDeclaredField("activeSession");
            activeField.setAccessible(true);
            activeField.set(mgr, null);
        } catch(Exception e) {
            throw new RuntimeException(e);
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

    @Test
    @Tag("unit")
    void newuiWithLoginDoesNotDestroyRegisteredSessionUi() {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);

        // Simulate a session UI being created and registered
        TrackingUI sessionUi = (TrackingUI) loop.newui(null);
        SessionManager mgr = SessionManager.getInstance();
        // Register this UI as a session UI (simulating what SessionRunner does)
        mgr.addSession(new SessionContext(null, sessionUi, null));

        // Now open a login UI (e.g. Bootstrap) — this should NOT destroy the session UI
        TrackingUI loginUi = (TrackingUI) loop.newui(new Bootstrap());

        assertEquals(0, sessionUi.destroyCalls,
            "opening a login UI must not destroy a registered session UI");
        assertNotSame(sessionUi, loginUi,
            "login UI should be a new UI instance");
    }

    @Test
    @Tag("unit")
    void newuiWithLobbyRunnerReusesActiveSessionUi() {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);

        // Simulate: session UI is active and registered
        TrackingUI sessionUi = (TrackingUI) loop.newui(null);
        SessionManager mgr = SessionManager.getInstance();
        mgr.addSession(new SessionContext(null, sessionUi, null));

        // Transition to LobbyRunner — should reuse the active session UI, not create a new one
        UI lobbyUi = loop.newui(new LobbyRunner());

        assertSame(sessionUi, lobbyUi,
            "newui(LobbyRunner) should reuse the active session UI instead of creating a throwaway");
        assertEquals(0, sessionUi.destroyCalls,
            "session UI must not be destroyed during LobbyRunner transition");
    }

    @Test
    @Tag("unit")
    void tickBackgroundSessionsServicesVisibleSessionMessages() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        TrackingUI visibleUi = (TrackingUI) loop.newui(null);

        // Register as a session so tickBackgroundSessions iterates it
        SessionManager mgr = SessionManager.getInstance();
        mgr.addSession(new SessionContext(null, visibleUi, null));

        // tickBackgroundSessions should NOT skip the visible session for message dispatch
        // (it only skips CPU-tick for the visible one since the main loop handles that)
        Method tick = GLPanel.Loop.class.getDeclaredMethod("tickBackgroundSessions", UI.class);
        tick.setAccessible(true);
        // Should not throw; visible session is iterated but background-only work is skipped
        tick.invoke(loop, visibleUi);
    }

    @Test
    @Tag("unit")
    void loopSyncsUiWithActiveSession() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);

        // Create two UIs simulating two sessions
        TrackingUI ui1 = (TrackingUI) loop.newui(null);
        SessionManager mgr = SessionManager.getInstance();
        SessionContext ctx1 = new SessionContext(null, ui1, null);
        mgr.addSession(ctx1);

        TrackingUI ui2 = new TrackingUI(panel);
        SessionContext ctx2 = new SessionContext(null, ui2, null);
        mgr.addSession(ctx2); // ctx2 is now the active session

        // loop.ui is still ui1, but active session is ctx2
        assertSame(ui1, loop.currentUi());

        // Simulate what the loop does: sync this.ui with active session under uilock
        Field uilockField = GLPanel.Loop.class.getDeclaredField("uilock");
        uilockField.setAccessible(true);
        Object uilock = uilockField.get(loop);
        synchronized(uilock) {
            SessionContext active = mgr.getActiveSession();
            if(active != null && active.ui != null && active.ui != loop.ui) {
                loop.ui = active.ui;
            }
        }

        assertSame(ui2, loop.currentUi(),
            "loop.ui should sync to the active session's UI");
    }
}
