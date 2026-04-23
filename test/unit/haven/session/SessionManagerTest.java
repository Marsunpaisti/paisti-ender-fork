package haven.session;

import haven.Area;
import haven.Coord;
import haven.GLPanel;
import haven.GOut;
import haven.Glob;
import haven.PMessage;
import haven.ResID;
import haven.RemoteUI;
import haven.RootWidget;
import haven.Session;
import haven.Transport;
import haven.UI;
import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.awt.Canvas;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {
    private static final class DummyTransport implements Transport {
        private final List<Callback> callbacks = new ArrayList<>();
        private int closeCalls;

        @Override
        public void close() {
            closeCalls++;
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

        private void fireClosed() {
            for(Callback callback : callbacks) {
                callback.closed();
            }
        }
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
        private int destroyCalls;

        private TestUI() {
            super(new DummyPanel(), Coord.of(10, 10), null);
        }

        @Override
        protected RootWidget createRoot(Coord sz) {
            return new TestRootWidget(this, sz);
        }

        @Override
        public void destroy() {
            destroyCalls++;
        }
    }

    private static final class SessionFixture {
        private final DummyTransport transport;
        private final SessionContext context;
        private final TestUI ui;

        private SessionFixture(DummyTransport transport, SessionContext context, TestUI ui) {
            this.transport = transport;
            this.context = context;
            this.ui = ui;
        }
    }

    private static SessionFixture newContext(String name) {
        try {
            DummyTransport transport = new DummyTransport();
            Session session = allocate(Session.class);
            setField(Session.class, session, "conn", transport);
            setField(Session.class, session, "uimsgs", new LinkedList<PMessage>());
            setField(Session.class, session, "user", new Session.User(name));
            setField(Session.class, session, "rescache", new TreeMap<Integer, Session.CachedRes>());
            setField(Session.class, session, "resmapper", new ResID.ResolveMapper(session));
            setField(Session.class, session, "glob", new Glob(session));
            Transport.Callback conncb = newConncb(session);
            setField(Session.class, session, "conncb", conncb);
            transport.add(conncb);
            TestUI ui = new TestUI();
            session.ui = ui;
            ui.sess = session;
            return new SessionFixture(transport, new SessionContext(session, ui, new RemoteUI(session)), ui);
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

    private static Transport.Callback newConncb(Session session) throws Exception {
        Constructor<?> ctor = Class.forName("haven.Session$1").getDeclaredConstructor(Session.class);
        ctor.setAccessible(true);
        return (Transport.Callback) ctor.newInstance(session);
    }

    private static void reset(SessionManager manager) {
        for(SessionContext ctx : manager.getSessions()) {
            manager.removeSession(ctx);
        }
    }

    @Test
    @Tag("unit")
    void newestAddedSessionBecomesActive() {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");

        manager.addSession(first.context);
        manager.addSession(second.context);

        assertEquals(2, manager.getSessions().size());
        assertSame(second.context, manager.getActiveSession());

        reset(manager);
    }

    @Test
    @Tag("unit")
    void switchToNextCyclesSessions() {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");

        manager.addSession(first.context);
        manager.addSession(second.context);
        manager.switchToNext();
        assertSame(first.context, manager.getActiveSession());

        manager.switchToNext();
        assertSame(second.context, manager.getActiveSession());

        reset(manager);
    }

    @Test
    @Tag("unit")
    void pruneDeadSessionsRemovesTerminallyClosedSessions() {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");

        manager.addSession(first.context);
        manager.addSession(second.context);
        second.context.session.close();
        manager.pruneDeadSessions();

        assertEquals(2, manager.getSessions().size(), "close() alone must not prune before transport closure finishes");
        assertFalse(second.context.session.isClosed(), "sanity check: session should not be terminal until transport closure is observed");

        second.transport.fireClosed();
        assertTrue(second.context.session.isClosed(), "session should become terminal after transport closure and queue drain");

        manager.pruneDeadSessions();

        assertEquals(1, manager.getSessions().size());
        assertSame(first.context, manager.getActiveSession());
        assertEquals(1, second.ui.destroyCalls, "pruning must delegate teardown to the context exactly once");
        assertEquals(1, second.transport.closeCalls, "pruning must not re-close an already terminal session more than once");

        reset(manager);
    }

    @Test
    @Tag("unit")
    void removeSessionFallsBackToNewestRemainingWhenActiveRemoved() {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");
        SessionFixture third = newContext("third");

        manager.addSession(first.context);
        manager.addSession(second.context);
        manager.addSession(third.context);

        manager.removeSession(third.context);

        assertEquals(2, manager.getSessions().size());
        assertSame(second.context, manager.getActiveSession());
        assertEquals(1, third.ui.destroyCalls, "removal must delegate teardown to the context exactly once");
        assertEquals(1, third.transport.closeCalls, "removal must close the removed session exactly once");

        manager.removeSession(third.context);

        assertEquals(1, third.ui.destroyCalls, "re-removing a deregistered session must not teardown twice");
        assertEquals(1, third.transport.closeCalls, "re-removing a deregistered session must not re-close twice");

        reset(manager);
    }

    @Test
    @Tag("unit")
    void removeSessionKeepsActiveSessionWhenRemovingNonActiveEntry() {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");
        SessionFixture third = newContext("third");

        manager.addSession(first.context);
        manager.addSession(second.context);
        manager.addSession(third.context);

        manager.removeSession(first.context);

        assertEquals(2, manager.getSessions().size());
        assertSame(third.context, manager.getActiveSession());
        assertEquals(1, first.ui.destroyCalls, "removing an inactive session must still delegate teardown once");
        assertEquals(1, first.transport.closeCalls, "removing an inactive session must close it once");

        reset(manager);
    }

    @Test
    @Tag("unit")
    void requestAddAccountWaiterHandshake() throws Exception {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        CountDownLatch released = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            try {
                manager.waitForAddRequest();
                released.countDown();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        manager.requestAddAccount();

        assertTrue(released.await(2, TimeUnit.SECONDS), "requestAddAccount() must release the waiting thread");
        waiter.join(2000);
        assertFalse(waiter.isAlive(), "waiter thread should finish after the add-account signal");
    }
}
