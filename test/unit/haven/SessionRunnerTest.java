package haven;

import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import haven.session.LobbyRunner;
import haven.session.SessionContext;
import haven.session.SessionManager;
import haven.session.SessionRunner;
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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRunnerTest {
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
        protected me.ender.gob.GobEffects createEffects(UI ui) {
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

    private static final class SessionFixture {
        private final Session session;
        private final TestUI ui;

        private SessionFixture(Session session, TestUI ui) {
            this.session = session;
            this.ui = ui;
        }
    }

    private static SessionFixture newSessionFixture(String name) {
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
            return new SessionFixture(session, ui);
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
    void sessionRunnerRegistersSessionAttachesRemoteUiAndReturnsLobbyRunner() throws Exception {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        SessionFixture fixture = newSessionFixture("runner");
        RemoteUI remote = new RemoteUI(fixture.session);

        UI.Runner next = new SessionRunner(remote).run(fixture.ui);

        assertInstanceOf(LobbyRunner.class, next);
        assertSame(remote, fixture.ui.rcvr, "SessionRunner must attach the RemoteUI before handing off");
        assertSame(fixture.session, fixture.ui.sess, "SessionRunner must preserve the session on the UI");
        assertSame(fixture.session, manager.getActiveSession().session, "SessionRunner must register the session with the manager");
        assertSame(remote, manager.getActiveSession().remoteUI, "SessionRunner must store the RemoteUI in the registered context");

        reset(manager);
    }

    @Test
    @Tag("unit")
    void lobbyRunnerWaitsForAddAccountBeforeReturningBootstrap() throws Exception {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        LobbyRunner runner = new LobbyRunner();
        TestUI ui = new TestUI();
        CountDownLatch finished = new CountDownLatch(1);
        List<UI.Runner> result = new ArrayList<>();

        Thread thread = new Thread(() -> {
            try {
                result.add(runner.run(ui));
                finished.countDown();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();

        Thread.sleep(100);
        assertTrue(result.isEmpty(), "LobbyRunner must block until add-account is requested");

        manager.requestAddAccount();

        assertTrue(finished.await(2, TimeUnit.SECONDS), "LobbyRunner must finish after requestAddAccount()");
        thread.join(2000);
        assertTrue(result.get(0) instanceof Bootstrap, "LobbyRunner must hand control back to Bootstrap");
    }
}
