package paisti.client;

import haven.Area;
import haven.AuthClient;
import haven.Coord;
import haven.GLPanel;
import haven.GOut;
import haven.Glob;
import haven.PMessage;
import haven.ResID;
import haven.RootWidget;
import haven.Session;
import haven.Transport;
import haven.UI;
import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.client.tabs.PaistiLobbyRunner;
import sun.misc.Unsafe;

import java.awt.Canvas;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PBootstrapTest {
    private static final class DummyTransport implements Transport {
        private final List<Callback> callbacks = new ArrayList<>();
        private int closeCalls = 0;

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
        private TestUI() {
            super(new DummyPanel(), Coord.of(10, 10), null);
        }

        @Override
        protected RootWidget createRoot(Coord sz) {
            return new TestRootWidget(this, sz);
        }
    }

    private static final class FakeCredentials extends AuthClient.Credentials {
        @Override
        public String tryauth(AuthClient cl) throws IOException {
            return "ignored";
        }

        @Override
        public String name() {
            return "queued";
        }
    }

    private static final class SessionFixture {
        private final Session session;
        private final DummyTransport transport;

        private SessionFixture(Session session, DummyTransport transport) {
            this.session = session;
            this.transport = transport;
        }
    }

    private static final class CancellingConnectBootstrap extends PBootstrap {
        private final Session session;

        private CancellingConnectBootstrap(Session session) {
            this.session = session;
            setinitcookie("cancelled-connect", new byte[] {1});
        }

        @Override
        protected Session connectSession(SocketAddress address, Session.User acct, boolean encrypt, byte[] cookie, Object... args) {
            cancel();
            return session;
        }
    }

    @Test
    @Tag("unit")
    void cancelTakesPriorityOverQueuedLoginMessage() throws Exception {
        PBootstrap bootstrap = new PBootstrap();
        bootstrap.rcvmsg(1, "login", new FakeCredentials(), false);

        bootstrap.cancel();

        assertEquals("cancel", bootstrap.takeNextMessageNameForTests());
    }

    @Test
    @Tag("unit")
    void cancelAfterConnectClosesSessionAndReturnsLobbyRunner() throws Exception {
        SessionFixture fixture = newSession("cancelled-connect");
        CancellingConnectBootstrap bootstrap = new CancellingConnectBootstrap(fixture.session);
        TestUI ui = new TestUI();

        UI.Runner next = bootstrap.run(ui);

        assertTrue(next instanceof PaistiLobbyRunner);
        assertEquals(1, fixture.transport.closeCalls, "session created before cancellation must be closed");
    }

    @Test
    @Tag("unit")
    void cancelledBootstrapIgnoresMessagesQueuedAfterCancel() throws Exception {
        PBootstrap bootstrap = new PBootstrap();

        bootstrap.cancel();
        bootstrap.rcvmsg(1, "login", new FakeCredentials(), false);

        assertEquals("cancel", bootstrap.takeNextMessageNameForTests());
    }

    private static SessionFixture newSession(String name) throws Exception {
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
        return new SessionFixture(session, transport);
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
}
