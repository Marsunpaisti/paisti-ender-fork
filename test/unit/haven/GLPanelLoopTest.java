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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.TreeMap;

import sun.misc.Unsafe;

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
    void serviceVisibleSessionHandlesNullContext() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        TrackingUI visibleUi = (TrackingUI) loop.newui(null);

        // visibleUi is NOT registered — serviceVisibleSession should return true (no-op)
        Method svc = GLPanel.Loop.class.getDeclaredMethod("serviceVisibleSession", UI.class);
        svc.setAccessible(true);
        assertTrue((boolean) svc.invoke(loop, visibleUi),
            "serviceVisibleSession must return true when visible UI is not a managed session");
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

        // loop.ui is still ui1 (a session UI), active is ctx2
        assertSame(ui1, loop.currentUi());

        // Simulate the sync logic from the loop
        syncUi(loop, mgr);

        assertSame(ui2, loop.currentUi(),
            "loop.ui should sync to the active session's UI when current is a session UI");
    }

    @Test
    @Tag("unit")
    void loopDoesNotOverrideNonSessionUiWithActiveSession() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);

        // Set up a non-session login UI as the current UI
        TrackingUI loginUi = (TrackingUI) loop.newui(new Bootstrap());
        SessionManager mgr = SessionManager.getInstance();

        // Register a session (simulating a background session exists)
        TrackingUI sessionUi = new TrackingUI(panel);
        mgr.addSession(new SessionContext(null, sessionUi, null));

        // loginUi is NOT a session UI; active session exists
        assertSame(loginUi, loop.currentUi());

        // Simulate the sync logic — should NOT override the login UI
        syncUi(loop, mgr);

        assertSame(loginUi, loop.currentUi(),
            "loop.ui must not be overridden when current UI is a standalone login/bootstrap UI");
    }

    /** Reproduce the uilock sync block from GLPanel.Loop.run() */
    private void syncUi(TestLoop loop, SessionManager mgr) throws Exception {
        Field uilockField = GLPanel.Loop.class.getDeclaredField("uilock");
        uilockField.setAccessible(true);
        Object uilock = uilockField.get(loop);
        synchronized(uilock) {
            haven.session.SessionContext active = mgr.getActiveSession();
            if(active != null && active.ui != null && active.ui != loop.ui
               && (loop.ui == null || mgr.isSessionUi(loop.ui))) {
                loop.ui = active.ui;
            }
        }
    }

    /* --- Session fixture helpers (Unsafe-based, mirrors SessionRunnerTest) --- */

    private static final class DummyTransport implements Transport {
        @Override public void close() {}
        @Override public void queuemsg(PMessage pmsg) {}
        @Override public void send(PMessage msg) {}
        @Override public Transport add(Callback cb) { return this; }
    }

    @SuppressWarnings("unchecked")
    private static Session newStubSession() throws Exception {
        Field uf = Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Unsafe unsafe = (Unsafe) uf.get(null);
        Session s = (Session) unsafe.allocateInstance(Session.class);
        setField(Session.class, s, "conn", new DummyTransport());
        setField(Session.class, s, "uimsgs", new LinkedList<PMessage>());
        setField(Session.class, s, "user", new Session.User("stub"));
        setField(Session.class, s, "rescache", new TreeMap<>());
        setField(Session.class, s, "resmapper", new ResID.ResolveMapper(s));
        setField(Session.class, s, "glob", new Glob(s));
        Constructor<?> ctor = Class.forName("haven.Session$1").getDeclaredConstructor(Session.class);
        ctor.setAccessible(true);
        Transport.Callback conncb = (Transport.Callback) ctor.newInstance(s);
        setField(Session.class, s, "conncb", conncb);
        return s;
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @Tag("unit")
    void returnMessageRemovesSessionContextInsteadOfLeaking() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        SessionManager mgr = SessionManager.getInstance();

        // Build a real-enough session with a Return message queued
        Session sess = newStubSession();
        Session returnedSess = newStubSession();
        sess.postuimsg(new RemoteUI.Return(returnedSess));

        TrackingUI sessionUi = (TrackingUI) loop.newui(null);
        sess.ui = sessionUi;
        sessionUi.sess = sess;
        RemoteUI remote = new RemoteUI(sess);
        SessionContext ctx = new SessionContext(sess, sessionUi, remote);
        mgr.addSession(ctx);

        // Use a different visible UI so the session is iterated
        TrackingUI visibleUi = new TrackingUI(panel);

        Method tick = GLPanel.Loop.class.getDeclaredMethod("tickBackgroundSessions", UI.class);
        tick.setAccessible(true);
        tick.invoke(loop, visibleUi);

        // The context should have been removed from the manager
        assertFalse(mgr.getSessions().contains(ctx),
            "RemoteUI.Return must cause the session context to be removed, not silently lost");

        // The returned session should have close() called (closereq set).
        // Note: isClosed() requires transport-closed AND queue-drained, which
        // won't happen with a stub transport. Check closereq directly.
        Field closereqField = Session.class.getDeclaredField("closereq");
        closereqField.setAccessible(true);
        assertTrue((boolean) closereqField.get(returnedSess),
            "The returned session from RemoteUI.Return must have close() called to prevent leaks");
    }

    @Test
    @Tag("unit")
    void visibleSessionReturnDestroysContextAndSkipsFrame() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        SessionManager mgr = SessionManager.getInstance();

        // Build visible session with a Return queued
        Session sess = newStubSession();
        Session returnedSess = newStubSession();
        sess.postuimsg(new RemoteUI.Return(returnedSess));

        TrackingUI sessionUi = (TrackingUI) loop.newui(null);
        sess.ui = sessionUi;
        sessionUi.sess = sess;
        RemoteUI remote = new RemoteUI(sess);
        SessionContext ctx = new SessionContext(sess, sessionUi, remote);
        mgr.addSession(ctx);

        // serviceVisibleSession should return false (session destroyed)
        Method svc = GLPanel.Loop.class.getDeclaredMethod("serviceVisibleSession", UI.class);
        svc.setAccessible(true);
        boolean alive = (boolean) svc.invoke(loop, sessionUi);

        assertFalse(alive,
            "serviceVisibleSession must return false when visible session hits RemoteUI.Return");
        assertFalse(mgr.getSessions().contains(ctx),
            "visible session context must be removed from manager after Return");

        Field closereqField = Session.class.getDeclaredField("closereq");
        closereqField.setAccessible(true);
        assertTrue((boolean) closereqField.get(returnedSess),
            "returned session must be closed to prevent leaks");
    }

    @Test
    @Tag("unit")
    void visibleSessionReturnSwitchesToNextActiveSession() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        SessionManager mgr = SessionManager.getInstance();

        // Set up two sessions; first is visible, second is background
        Session sess1 = newStubSession();
        sess1.postuimsg(new RemoteUI.Return(newStubSession()));
        TrackingUI ui1 = (TrackingUI) loop.newui(null);
        sess1.ui = ui1;
        ui1.sess = sess1;
        RemoteUI remote1 = new RemoteUI(sess1);
        SessionContext ctx1 = new SessionContext(sess1, ui1, remote1);
        mgr.addSession(ctx1);

        TrackingUI ui2 = new TrackingUI(panel);
        Session sess2 = newStubSession();
        sess2.ui = ui2;
        ui2.sess = sess2;
        SessionContext ctx2 = new SessionContext(sess2, ui2, new RemoteUI(sess2));
        mgr.addSession(ctx2); // ctx2 is now active

        // Force active back to ctx1 so it's the "visible" one being serviced
        // (addSession sets active to the last added, so switch)
        Field activeField = SessionManager.class.getDeclaredField("activeSession");
        activeField.setAccessible(true);
        activeField.set(mgr, ctx1);

        Method svc = GLPanel.Loop.class.getDeclaredMethod("serviceVisibleSession", UI.class);
        svc.setAccessible(true);
        svc.invoke(loop, ui1);

        // After ctx1 is removed, this.ui should switch to next active session (ctx2)
        assertSame(ui2, loop.currentUi(),
            "after visible session Return, loop.ui must switch to the next active session");
    }
}
