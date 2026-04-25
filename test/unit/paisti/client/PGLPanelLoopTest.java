package paisti.client;

import haven.Area;
import haven.Coord;
import haven.GLPanel;
import haven.GOut;
import haven.Glob;
import haven.PMessage;
import haven.RemoteUI;
import haven.ResID;
import haven.RootWidget;
import haven.Session;
import haven.Transport;
import haven.UI;
import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.client.tabs.PaistiClientTab;
import paisti.client.tabs.PaistiClientTabManager;
import paisti.client.tabs.PaistiSessionContext;
import sun.misc.Unsafe;

import java.awt.Canvas;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PGLPanelLoopTest {
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

    private static final class TestLoop extends PGLPanelLoop {
        private final GLPanel panel;

        private TestLoop(GLPanel panel) {
            super(panel);
            this.panel = panel;
        }

        @Override
        protected UI makeui(UI.Runner runner) {
            return new TrackingUI(panel);
        }

        private TrackingUI currentUi() {
            return (TrackingUI) ui;
        }

        private void setLoopUi(UI ui) {
            this.ui = ui;
        }

        private UI exposeSyncActiveUi(UI current) {
            return syncActiveUi(current);
        }

        private void exposeTickBackgroundManagedSessions(UI visibleUi) {
            tickBackgroundManagedSessions(visibleUi);
        }

        private boolean exposeServiceVisibleManagedSession(UI visibleUi) {
            return serviceVisibleManagedSession(visibleUi);
        }

        private UI exposeHandlePrunedManagedUi(UI visibleUi) {
            return handlePrunedManagedUi(visibleUi);
        }
    }

    private static final class DummyTransport implements Transport {
        private final List<Callback> callbacks = new ArrayList<>();

        @Override
        public void close() {
        }

        @Override
        public void queuemsg(PMessage pmsg) {
        }

        @Override
        public void send(PMessage msg) {
        }

        @Override
        public Transport add(Callback cb) {
            callbacks.add(cb);
            return this;
        }
    }

    private static final class SessionFixture {
        private final Session session;
        private final TrackingUI ui;
        private final PaistiSessionContext context;

        private SessionFixture(Session session, TrackingUI ui, PaistiSessionContext context) {
            this.session = session;
            this.ui = ui;
            this.context = context;
        }
    }

    private static final class TrackingDisposeContext extends PaistiSessionContext {
        private int disposeCalls = 0;

        private TrackingDisposeContext(Session session, UI ui, RemoteUI remoteUI) {
            super(session, ui, remoteUI);
        }

        @Override
        public void dispose() {
            disposeCalls++;
            super.dispose();
        }
    }

    @BeforeEach
    @AfterEach
    void clearTabs() throws Exception {
        Method clear = PaistiClientTabManager.class.getDeclaredMethod("clearForTests");
        clear.setAccessible(true);
        clear.invoke(PaistiClientTabManager.getInstance());
    }

    @Test
    @Tag("unit")
    void loopSyncsVisibleUiFromActiveLoginTab() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture session = newContext(panel, "session");
        manager.addSessionTab(session.context);
        loop.setLoopUi(session.ui);
        TrackingUI loginUi = new TrackingUI(panel);
        PaistiClientTab loginTab = manager.addLoginTab(loginUi);

        UI synced = loop.exposeSyncActiveUi(loop.currentUi());

        assertSame(loginUi, synced, "active login tab must replace the visible session UI during loop sync");
        assertSame(loginTab, manager.getActiveTab());
    }

    @Test
    @Tag("unit")
    void backgroundReturnClosesOldAndReturnedSessionsWithoutDisposal() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture background = newContext(panel, "background");
        Session returned = newStubSession("returned");
        background.session.postuimsg(new RemoteUI.Return(returned));
        manager.addSessionTab(background.context);
        TrackingUI visibleUi = new TrackingUI(panel);

        loop.exposeTickBackgroundManagedSessions(visibleUi);

        assertTrue(manager.getSessionContexts().contains(background.context));
        assertTrue(isCloseRequested(background.session));
        assertTrue(isCloseRequested(returned));
        assertEquals(0, background.ui.destroyCalls);
    }

    @Test
    @Tag("unit")
    void visibleReturnReplacesOwningSessionTabWithReturnedSession() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture oldSession = newContext(panel, "old");
        Session returned = newStubSession("returned");
        oldSession.session.postuimsg(new RemoteUI.Return(returned));
        PaistiClientTab tab = manager.addSessionTab(oldSession.context);
        loop.setLoopUi(oldSession.ui);

        boolean keepFrame = loop.exposeServiceVisibleManagedSession(oldSession.ui);

        assertFalse(keepFrame);
        assertTrue(isCloseRequested(oldSession.session));
        assertFalse(isCloseRequested(returned));
        assertSame(tab, manager.getActiveTab());
        assertNotSame(oldSession.context, tab.sessionContext());
        assertSame(returned, tab.sessionContext().session);
        assertSame(returned, tab.ui().sess);
        assertSame(tab.ui(), loop.currentUi());
        assertEquals(1, manager.getTabs().size());
        assertEquals(1, oldSession.ui.destroyCalls);
    }

    @Test
    @Tag("unit")
    void visibleReturnDisposesOldContextExactlyOnceAfterReplacement() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture oldSession = newContext(panel, "old");
        TrackingDisposeContext oldContext = new TrackingDisposeContext(oldSession.session, oldSession.ui, oldSession.context.remoteUI);
        Session returned = newStubSession("returned");
        oldSession.session.postuimsg(new RemoteUI.Return(returned));
        PaistiClientTab tab = manager.addSessionTab(oldContext);
        loop.setLoopUi(oldSession.ui);

        boolean keepFrame = loop.exposeServiceVisibleManagedSession(oldSession.ui);
        manager.pruneDeadSessions();

        assertFalse(keepFrame);
        assertSame(tab, manager.getActiveTab());
        assertSame(returned, tab.sessionContext().session);
        assertEquals(1, oldContext.disposeCalls, "old context must not be orphaned after visible Return handoff");
        assertEquals(1, oldSession.ui.destroyCalls, "disposing the old context owns old UI teardown exactly once");
    }

    @Test
    @Tag("unit")
    void visibleServiceSkipsStaleInactiveSessionWithoutDrainingReturn() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture stale = newContext(panel, "stale");
        Session returned = newStubSession("returned");
        stale.session.postuimsg(new RemoteUI.Return(returned));
        PaistiClientTab staleTab = manager.addSessionTab(stale.context);
        SessionFixture active = newContext(panel, "active");
        PaistiClientTab activeTab = manager.addSessionTab(active.context);
        loop.setLoopUi(stale.ui);

        boolean keepFrame = loop.exposeServiceVisibleManagedSession(stale.ui);

        assertFalse(keepFrame, "stale visible session must skip current frame so next loop can rebind");
        assertSame(activeTab, manager.getActiveTab());
        assertSame(stale.context, staleTab.sessionContext(), "stale tab must not be replaced by a queued Return");
        assertSame(stale.ui, staleTab.ui());
        assertFalse(isCloseRequested(stale.session), "stale session queue must not be drained or closed");
        assertFalse(isCloseRequested(returned), "queued returned session must remain untouched when visible UI is stale");
    }

    @Test
    @Tag("unit")
    void visibleNullReturnRetiresOldSessionAndRequestsLoginWhenNoSuccessor() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture oldSession = newContext(panel, "old");
        oldSession.session.postuimsg(new RemoteUI.Return(null));
        manager.addSessionTab(oldSession.context);
        loop.setLoopUi(oldSession.ui);

        boolean keepFrame = loop.exposeServiceVisibleManagedSession(oldSession.ui);

        assertFalse(keepFrame);
        assertTrue(isCloseRequested(oldSession.session));
        PaistiClientTab pending = manager.getActiveTab();
        assertTrue(pending.isLogin());
        assertNull(pending.ui());
        assertNull(loop.currentUi());
        assertTrue(loginRequestAvailable(manager));
        assertEquals(0, oldSession.ui.destroyCalls);
    }

    @Test
    @Tag("unit")
    void visibleNullReturnRebindsToNextSafeSuccessor() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture previous = newContext(panel, "previous");
        SessionFixture returning = newContext(panel, "returning");
        SessionFixture next = newContext(panel, "next");
        manager.addSessionTab(previous.context);
        PaistiClientTab returningTab = manager.addSessionTab(returning.context);
        PaistiClientTab nextTab = manager.addSessionTab(next.context);
        manager.activateTab(returningTab);
        returning.session.postuimsg(new RemoteUI.Return(null));
        loop.setLoopUi(returning.ui);

        boolean keepFrame = loop.exposeServiceVisibleManagedSession(returning.ui);

        assertFalse(keepFrame);
        assertTrue(isCloseRequested(returning.session));
        assertSame(nextTab, manager.getActiveTab(), "null Return should fall forward to the next selectable tab");
        assertSame(next.ui, loop.currentUi());
        assertFalse(loginRequestAvailable(manager));
    }

    @Test
    @Tag("unit")
    void prunedVisibleManagedUiRebindsToActiveSuccessorOrRequestsLogin() throws Exception {
        DummyPanel panel = new DummyPanel();
        TestLoop loop = new TestLoop(panel);
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture first = newContext(panel, "first");
        SessionFixture second = newContext(panel, "second");
        manager.addSessionTab(first.context);
        manager.addSessionTab(second.context);
        loop.setLoopUi(first.ui);
        forceSessionClosed(first.session);
        manager.pruneDeadSessions();

        UI rebound = loop.exposeHandlePrunedManagedUi(first.ui);

        assertSame(second.ui, rebound);
        assertSame(second.ui, loop.currentUi());

        forceSessionClosed(second.session);
        manager.pruneDeadSessions();
        rebound = loop.exposeHandlePrunedManagedUi(second.ui);

        assertNull(rebound);
        assertNull(loop.currentUi());
        assertTrue(loginRequestAvailable(manager));
    }

    private static SessionFixture newContext(GLPanel panel, String name) throws Exception {
        Session session = newStubSession(name);
        TrackingUI ui = new TrackingUI(panel);
        session.ui = ui;
        ui.sess = session;
        return new SessionFixture(session, ui, new PaistiSessionContext(session, ui, new RemoteUI(session)));
    }

    private static Session newStubSession(String name) throws Exception {
        Session session = allocate(Session.class);
        DummyTransport transport = new DummyTransport();
        setField(Session.class, session, "conn", transport);
        setField(Session.class, session, "uimsgs", new LinkedList<PMessage>());
        setField(Session.class, session, "user", new Session.User(name));
        setField(Session.class, session, "rescache", new TreeMap<Integer, Session.CachedRes>());
        setField(Session.class, session, "resmapper", new ResID.ResolveMapper(session));
        setField(Session.class, session, "glob", new Glob(session));
        Transport.Callback conncb = newConncb(session);
        setField(Session.class, session, "conncb", conncb);
        transport.add(conncb);
        return session;
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

    private static Transport.Callback newConncb(Session session) throws Exception {
        Constructor<?> ctor = Class.forName("haven.Session$1").getDeclaredConstructor(Session.class);
        ctor.setAccessible(true);
        return (Transport.Callback) ctor.newInstance(session);
    }

    private static boolean isCloseRequested(Session session) throws Exception {
        Field field = Session.class.getDeclaredField("closereq");
        field.setAccessible(true);
        return (boolean) field.get(session);
    }

    private static void forceSessionClosed(Session session) throws Exception {
        setField(Session.class, session, "closereq", true);
        setField(Session.class, session, "connclosed", true);
        setField(Session.class, session, "closed", true);
    }

    private static boolean loginRequestAvailable(PaistiClientTabManager manager) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = executor.submit(() -> {
                try {
                    manager.waitForLoginRequest();
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                result.get(150, TimeUnit.MILLISECONDS);
                return true;
            } catch(TimeoutException e) {
                result.cancel(true);
                return false;
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
