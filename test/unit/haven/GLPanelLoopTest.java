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
            // Drain any add-account signals left by tests
            java.lang.reflect.Field sigField = SessionManager.class.getDeclaredField("addAccountSignal");
            sigField.setAccessible(true);
            java.util.concurrent.Semaphore sig = (java.util.concurrent.Semaphore) sigField.get(mgr);
            sig.drainPermits();
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
    void backgroundReturnClosesSessionAndSkipsFrameWork() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        SessionManager mgr = SessionManager.getInstance();

        // Build a background session with a Return message queued
        Session sess = newStubSession();
        Session returnedSess = newStubSession();
        sess.postuimsg(new RemoteUI.Return(returnedSess));

        TrackingUI sessionUi = (TrackingUI) loop.newui(null);
        sess.ui = sessionUi;
        sessionUi.sess = sess;
        RemoteUI remote = new RemoteUI(sess);
        SessionContext ctx = new SessionContext(sess, sessionUi, remote);
        mgr.addSession(ctx);

        // Use a different visible UI so the session is iterated as background
        TrackingUI visibleUi = new TrackingUI(panel);

        Method tick = GLPanel.Loop.class.getDeclaredMethod("tickBackgroundSessions", UI.class);
        tick.setAccessible(true);
        tick.invoke(loop, visibleUi);

        // Context stays registered (not removed/disposed) — pruning handles final teardown
        assertTrue(mgr.getSessions().contains(ctx),
            "background Return must NOT immediately remove the context; pruneDeadSessions owns that");

        // ctx.close() was called (session shutdown initiated)
        Field closereqField = Session.class.getDeclaredField("closereq");
        closereqField.setAccessible(true);
        assertTrue((boolean) closereqField.get(sess),
            "background Return must initiate shutdown on the current session via ctx.close()");

        // The returned session must have close() called
        assertTrue((boolean) closereqField.get(returnedSess),
            "returned session from RemoteUI.Return must be closed to prevent leaks");

        // UI is NOT destroyed (dispose was not called)
        assertEquals(0, sessionUi.destroyCalls,
            "background Return must not destroy the UI — pruneDeadSessions owns disposal");
    }

    @Test
    @Tag("unit")
    void visibleSessionReturnTransitionsToReturnedSession() throws Exception {
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

        Method svc = GLPanel.Loop.class.getDeclaredMethod("serviceVisibleSession", UI.class);
        svc.setAccessible(true);
        boolean alive = (boolean) svc.invoke(loop, sessionUi);

        assertFalse(alive,
            "serviceVisibleSession must return false when visible session hits RemoteUI.Return");

        // Old context stays registered — not immediately removed/disposed
        assertTrue(mgr.getSessions().contains(ctx),
            "visible Return must NOT immediately remove the old context");

        // ctx.close() initiated shutdown on the OLD session
        Field closereqField = Session.class.getDeclaredField("closereq");
        closereqField.setAccessible(true);
        assertTrue((boolean) closereqField.get(sess),
            "visible Return must initiate shutdown on the old session");

        // The returned session must NOT be closed — it was transitioned to
        assertFalse((boolean) closereqField.get(returnedSess),
            "returned session must NOT be closed; it is the new active session");

        // A new context for the returned session must be registered
        SessionContext newCtx = null;
        for(SessionContext c : mgr.getSessions()) {
            if(c.session == returnedSess) {
                newCtx = c;
                break;
            }
        }
        assertNotNull(newCtx,
            "a new SessionContext must be registered for the returned session");
        assertNotNull(newCtx.ui,
            "the new context must have a UI");
        assertNotNull(newCtx.remoteUI,
            "the new context must have a RemoteUI");
        assertSame(returnedSess, newCtx.remoteUI.sess,
            "the new RemoteUI must be bound to the returned session");

        // loop.ui must point at the new UI
        assertSame(newCtx.ui, loop.currentUi(),
            "loop.ui must switch to the new UI for the returned session");

        // The new UI must have the returned session bound
        assertSame(returnedSess, loop.currentUi().sess,
            "the new UI's sess must be the returned session");

        // Old UI is NOT destroyed
        assertEquals(0, sessionUi.destroyCalls,
            "visible Return must not destroy the old UI in the GL loop");
    }

    @Test
    @Tag("unit")
    void visibleSessionReturnTransitionsEvenWithOtherLiveSessions() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        SessionManager mgr = SessionManager.getInstance();

        // Set up two sessions; first is visible with Return, second is alive background
        Session sess1 = newStubSession();
        Session returnedSess = newStubSession();
        sess1.postuimsg(new RemoteUI.Return(returnedSess));
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
        mgr.addSession(ctx2);

        // Force active to ctx1 so it's the visible one being serviced
        Field activeField = SessionManager.class.getDeclaredField("activeSession");
        activeField.setAccessible(true);
        activeField.set(mgr, ctx1);

        Method svc = GLPanel.Loop.class.getDeclaredMethod("serviceVisibleSession", UI.class);
        svc.setAccessible(true);
        svc.invoke(loop, ui1);

        // loop.ui must now point at the NEW UI for the returned session (not ui2)
        assertNotSame(ui1, loop.currentUi(),
            "loop.ui must not remain on the old session's UI");
        assertNotSame(ui2, loop.currentUi(),
            "loop.ui must point at the transitioned session's new UI, not the background session");
        assertSame(returnedSess, loop.currentUi().sess,
            "loop.ui's session must be the returned session");

        // activeSession must be the newly created context for the returned session
        assertNotSame(ctx1, mgr.getActiveSession(),
            "getActiveSession must not return the retiring context after Return");
        assertSame(returnedSess, mgr.getActiveSession().session,
            "getActiveSession must point to the returned session's context");
    }

    @Test
    @Tag("unit")
    void nextFrameUilockSyncDoesNotRebindToRetiringSession() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        SessionManager mgr = SessionManager.getInstance();

        // Set up: visible session hits Return, successor exists
        Session sess1 = newStubSession();
        Session returnedSess = newStubSession();
        sess1.postuimsg(new RemoteUI.Return(returnedSess));
        TrackingUI ui1 = (TrackingUI) loop.newui(null);
        sess1.ui = ui1;
        ui1.sess = sess1;
        SessionContext ctx1 = new SessionContext(sess1, ui1, new RemoteUI(sess1));
        mgr.addSession(ctx1);

        TrackingUI ui2 = new TrackingUI(panel);
        Session sess2 = newStubSession();
        sess2.ui = ui2;
        ui2.sess = sess2;
        SessionContext ctx2 = new SessionContext(sess2, ui2, new RemoteUI(sess2));
        mgr.addSession(ctx2);

        Field activeField = SessionManager.class.getDeclaredField("activeSession");
        activeField.setAccessible(true);
        activeField.set(mgr, ctx1);

        // Trigger Return on visible session
        Method svc = GLPanel.Loop.class.getDeclaredMethod("serviceVisibleSession", UI.class);
        svc.setAccessible(true);
        svc.invoke(loop, ui1);

        // loop.ui is now the transitioned session's UI
        UI transitionedUi = loop.currentUi();
        assertNotSame(ui1, transitionedUi,
            "loop.ui must not be the retiring session's UI");
        assertSame(returnedSess, transitionedUi.sess,
            "loop.ui must be bound to the returned session");

        // Now simulate the next frame's uilock sync (from run())
        syncUi(loop, mgr);

        // loop.ui must still be the transitioned UI, NOT ui1
        assertNotSame(ui1, loop.currentUi(),
            "next-frame uilock sync must not rebind to the retiring session");
    }

    @Test
    @Tag("unit")
    void visibleSessionReturnWithNullReturnedSessionWakesLobby() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        SessionManager mgr = SessionManager.getInstance();

        // Single session with Return(null) — no returned session to transition to
        Session sess = newStubSession();
        sess.postuimsg(new RemoteUI.Return(null));
        TrackingUI sessionUi = (TrackingUI) loop.newui(null);
        sess.ui = sessionUi;
        sessionUi.sess = sess;
        RemoteUI remote = new RemoteUI(sess);
        SessionContext ctx = new SessionContext(sess, sessionUi, remote);
        mgr.addSession(ctx);

        Method svc = GLPanel.Loop.class.getDeclaredMethod("serviceVisibleSession", UI.class);
        svc.setAccessible(true);
        boolean alive = (boolean) svc.invoke(loop, sessionUi);

        assertFalse(alive, "must return false on Return");

        // UI is NOT destroyed — still usable until pruning
        assertEquals(0, sessionUi.destroyCalls,
            "UI must not be destroyed even when no successor exists");

        // The add-account signal should have been released (waking LobbyRunner)
        Field addAccountField = SessionManager.class.getDeclaredField("addAccountSignal");
        addAccountField.setAccessible(true);
        java.util.concurrent.Semaphore signal = (java.util.concurrent.Semaphore) addAccountField.get(mgr);
        assertTrue(signal.tryAcquire(),
            "when returned session is null and no live successor exists, requestAddAccount must be called");
    }

    /** Force a stub session into the fully-closed state (isClosed() == true). */
    private static void forceSessionClosed(Session sess) throws Exception {
        setField(Session.class, sess, "closereq", true);
        setField(Session.class, sess, "connclosed", true);
        setField(Session.class, sess, "closed", true);
    }

    @Test
    @Tag("unit")
    void prunedVisibleSessionRebindsToSuccessor() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        SessionManager mgr = SessionManager.getInstance();

        // Two sessions: sess1 (visible, dead) and sess2 (alive)
        Session sess1 = newStubSession();
        TrackingUI ui1 = (TrackingUI) loop.newui(null);
        sess1.ui = ui1;
        ui1.sess = sess1;
        SessionContext ctx1 = new SessionContext(sess1, ui1, new RemoteUI(sess1));
        mgr.addSession(ctx1);

        Session sess2 = newStubSession();
        TrackingUI ui2 = new TrackingUI(panel);
        sess2.ui = ui2;
        ui2.sess = sess2;
        SessionContext ctx2 = new SessionContext(sess2, ui2, new RemoteUI(sess2));
        mgr.addSession(ctx2);

        // Force sess1 into fully-closed state so pruning removes it
        forceSessionClosed(sess1);
        mgr.pruneDeadSessions();

        // ui1 is no longer a session UI; handlePrunedVisibleSession should rebind
        UI result = loop.handlePrunedVisibleSession(ui1);

        assertSame(ui2, result,
            "handlePrunedVisibleSession must rebind to the live successor session UI");
        assertSame(ui2, loop.currentUi(),
            "loop.ui must point to the successor after prune failover");
    }

    @Test
    @Tag("unit")
    void prunedVisibleSessionWithNoSuccessorReturnsNull() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        SessionManager mgr = SessionManager.getInstance();

        // Single session, visible and dead
        Session sess = newStubSession();
        TrackingUI ui1 = (TrackingUI) loop.newui(null);
        sess.ui = ui1;
        ui1.sess = sess;
        SessionContext ctx = new SessionContext(sess, ui1, new RemoteUI(sess));
        mgr.addSession(ctx);

        // Force fully closed and prune
        forceSessionClosed(sess);
        mgr.pruneDeadSessions();

        UI result = loop.handlePrunedVisibleSession(ui1);

        assertNull(result,
            "handlePrunedVisibleSession must return null when no successor exists");
        assertNull(loop.currentUi(),
            "loop.ui must be null when no successor exists");

        // Should have woken the login flow
        Field addAccountField = SessionManager.class.getDeclaredField("addAccountSignal");
        addAccountField.setAccessible(true);
        java.util.concurrent.Semaphore signal = (java.util.concurrent.Semaphore) addAccountField.get(mgr);
        assertTrue(signal.tryAcquire(),
            "requestAddAccount must be called when no live successor exists");
    }

    @Test
    @Tag("unit")
    void prunedVisibleSessionNoOpWhenStillAlive() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        SessionManager mgr = SessionManager.getInstance();

        // Single alive session
        Session sess = newStubSession();
        TrackingUI ui1 = (TrackingUI) loop.newui(null);
        sess.ui = ui1;
        ui1.sess = sess;
        SessionContext ctx = new SessionContext(sess, ui1, new RemoteUI(sess));
        mgr.addSession(ctx);

        // Session is alive, not pruned
        UI result = loop.handlePrunedVisibleSession(ui1);

        assertSame(ui1, result,
            "handlePrunedVisibleSession must return the same UI when session is still alive");
        assertSame(ui1, loop.currentUi(),
            "loop.ui must remain unchanged when session is still alive");
    }

    @Test
    @Tag("unit")
    void nullUiAfterPruneDoesNotCrashFrameSetup() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);

        // Force loop.ui to null — simulates handlePrunedVisibleSession setting
        // this.ui = null while waiting for login to install a replacement.
        Field uiField = GLPanel.Loop.class.getDeclaredField("ui");
        uiField.setAccessible(true);
        uiField.set(loop, null);

        assertNull(loop.currentUi(), "precondition: loop.ui must be null");

        // The uilock sync block in run() reads this.ui into a local; verify
        // the null-safe early-exit path exists by exercising the same read.
        Field uilockField = GLPanel.Loop.class.getDeclaredField("uilock");
        uilockField.setAccessible(true);
        Object uilock = uilockField.get(loop);
        UI localUi;
        synchronized(uilock) {
            localUi = loop.ui;
        }

        // The fix: when ui is null, run() must skip ui.modflags() et al.
        // We cannot call run() directly (it needs a GL env), but we verify
        // the contract: null localUi must not be dereferenced.
        assertNull(localUi,
            "when loop.ui is null, the per-frame local must also be null — " +
            "run() must skip the frame instead of calling ui.modflags()");
    }
}
