package haven;

import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.awt.Canvas;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.TreeMap;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class RemoteUITest {
    private static final class DummyTransport implements Transport {
        private boolean closed;
        private int closeCalls;
        private boolean closeSignalsTransport;
        private final Collection<PMessage> queuedMessages = new ArrayList<>();
        private final Collection<Callback> callbacks = new ArrayList<>();

        @Override
        public void close() {
            closed = true;
            closeCalls++;
            if(closeSignalsTransport) {
                fireClosed();
            }
        }

        @Override
        public void queuemsg(PMessage pmsg) {
            queuedMessages.add(pmsg);
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

    private static final class RecordingUI extends UI {
        private int lastDestroyId = -1;
        private int lastMessageId = -1;
        private String lastMessageName;
        private Object[] lastMessageArgs;
        private Collection<Integer> lastBarrierDeps;
        private Collection<Integer> lastBarrierBars;
        private int lastNewWidgetId = -1;
        private String lastNewWidgetType;
        private int lastNewWidgetParent = Integer.MIN_VALUE;
        private Object[] lastNewWidgetPargs;
        private Object[] lastNewWidgetCargs;
        private boolean interruptOnNewWidget;

        private RecordingUI() {
            super(new DummyPanel(), Coord.of(10, 10), null);
        }

        @Override
        protected RootWidget createRoot(Coord sz) {
            return new TestRootWidget(this, sz);
        }

        @Override
        public void destroy(int id) {
            lastDestroyId = id;
        }

        @Override
        public void newwidgetp(int id, String type, int parent, Object[] pargs, Object... cargs) throws InterruptedException {
            if(interruptOnNewWidget) {
                throw new InterruptedException("test interruption");
            }
            lastNewWidgetId = id;
            lastNewWidgetType = type;
            lastNewWidgetParent = parent;
            lastNewWidgetPargs = pargs;
            lastNewWidgetCargs = cargs;
        }

        @Override
        public void uimsg(int id, String msg, Object... args) {
            lastMessageId = id;
            lastMessageName = msg;
            lastMessageArgs = args;
        }

        @Override
        public void wdgbarrier(Collection<Integer> deps, Collection<Integer> bars) {
            lastBarrierDeps = deps;
            lastBarrierBars = bars;
        }
    }

    private static final class SessionFixture {
        private final DummyTransport transport = new DummyTransport();
        private final Session session;

        private SessionFixture(Session session) {
            this.session = session;
        }
    }

    private static SessionFixture newSessionFixture() {
        try {
            Session session = allocate(Session.class);
            SessionFixture fixture = new SessionFixture(session);
            setField(Session.class, session, "conn", fixture.transport);
            setField(Session.class, session, "uimsgs", new LinkedList<PMessage>());
            setField(Session.class, session, "user", new Session.User("tester"));
            setField(Session.class, session, "rescache", new TreeMap<Integer, Session.CachedRes>());
            setField(Session.class, session, "resmapper", new ResID.ResolveMapper(session));
            setField(Session.class, session, "glob", new Glob(session));
            Transport.Callback conncb = newConncb(session);
            setField(Session.class, session, "conncb", conncb);
            fixture.transport.add(conncb);
            return fixture;
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Session newSession() {
        return newSessionFixture().session;
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

    private static PMessage incoming(PMessage msg) {
        return new PMessage(msg.type, msg.fin());
    }

    private static PMessage addEncodedList(PMessage msg, Object... args) {
        msg.addlist(args);
        msg.adduint8(Message.T_END);
        return (PMessage) msg;
    }

    @Test
    @Tag("unit")
    void pollUIMsgReturnsQueuedMessagesThenNull() {
        Session session = newSession();
        PMessage msg = new PMessage(RMessage.RMSG_DSTWDG);
        msg.addint32(77);

        session.postuimsg(incoming(msg));

        assertEquals(incoming(msg), session.pollUIMsg(), "pollUIMsg() must return the queued message without blocking");
        assertNull(session.pollUIMsg(), "pollUIMsg() must return null when the queue is empty");
    }

    @Test
    @Tag("unit")
    void closeRequestWaitsForTransportClosureAndQueueDrain() {
        SessionFixture fixture = newSessionFixture();
        PMessage msg = new PMessage(RMessage.RMSG_DSTWDG);
        msg.addint32(77);

        assertFalse(fixture.session.isClosed(), "sanity check: fresh test session should start open");

        fixture.session.postuimsg(incoming(msg));
        fixture.session.close();

        assertFalse(fixture.session.isClosed(), "close() must not report terminal closure before transport closure");
        assertEquals(incoming(msg), fixture.session.pollUIMsg(), "close() must leave queued UI messages available for draining");
        assertFalse(fixture.session.isClosed(), "draining alone must not make the session terminal before transport closure");
        fixture.transport.fireClosed();
        assertTrue(fixture.session.isClosed(), "isClosed() must become true after transport closure and queue drain");
        assertTrue(fixture.transport.closed, "close() must still close the underlying transport");
    }

    @Test
    @Tag("unit")
    void closeIsIdempotentAcrossRepeatedShutdownCalls() {
        SessionFixture fixture = newSessionFixture();
        fixture.transport.closeSignalsTransport = true;

        fixture.session.close();
        fixture.session.close();

        assertTrue(fixture.session.isClosed(), "session should remain terminally closed after repeated shutdown signals");
        assertEquals(1, fixture.transport.closeCalls, "close() must only close the transport once");
    }

    @Test
    @Tag("unit")
    void lateUiMessagesAfterTransportClosureReopenUntilDrainedAgain() {
        SessionFixture fixture = newSessionFixture();
        PMessage msg = new PMessage(RMessage.RMSG_DSTWDG);
        msg.addint32(91);

        fixture.transport.closeSignalsTransport = true;
        fixture.session.close();

        assertTrue(fixture.session.isClosed(), "sanity check: auto-signaled transport closure should reach terminal state with an empty queue");

        fixture.session.postuimsg(incoming(msg));

        assertFalse(fixture.session.isClosed(), "late UI messages must temporarily reopen terminal closure state until drained");
        assertEquals(incoming(msg), fixture.session.pollUIMsg(), "late UI messages must still be drainable after shutdown");
        assertTrue(fixture.session.isClosed(), "terminal closure must return once the late UI message is drained");
    }

    @Test
    @Tag("unit")
    void attachSetsReceiverAndSendsUserAgentMessages() {
        SessionFixture fixture = newSessionFixture();
        RemoteUI remote = new RemoteUI(fixture.session);
        RecordingUI ui = new RecordingUI();

        remote.attach(ui);

        assertSame(remote, ui.rcvr, "attach() must set the UI receiver to this RemoteUI");
        assertFalse(fixture.transport.queuedMessages.isEmpty(), "attach() must enqueue user-agent messages");
    }

    @Test
    @Tag("unit")
    void dispatchMessageRoutesWidgetMessages() throws InterruptedException {
        Session session = newSession();
        RemoteUI remote = new RemoteUI(session);
        RecordingUI ui = new RecordingUI();

        PMessage widgetMessage = new PMessage(RMessage.RMSG_WDGMSG);
        widgetMessage.addint32(5);
        widgetMessage.addstring("ping");
        widgetMessage.addlist(new Object[]{"arg", 3});

        assertTrue(remote.dispatchMessage(incoming(widgetMessage), ui), "widget messages should be consumed");
        assertEquals(5, ui.lastMessageId);
        assertEquals("ping", ui.lastMessageName);
        assertArrayEquals(new Object[]{"arg", 3}, ui.lastMessageArgs);
    }

    @Test
    @Tag("unit")
    void dispatchMessageRoutesNewWidgetMessages() throws InterruptedException {
        Session session = newSession();
        RemoteUI remote = new RemoteUI(session);
        RecordingUI ui = new RecordingUI();

        PMessage newWidget = new PMessage(RMessage.RMSG_NEWWDG);
        newWidget.addint32(9);
        newWidget.addstring("dummy");
        newWidget.addint32(2);
        addEncodedList(newWidget, "parent");
        addEncodedList(newWidget, "child", 7);

        assertTrue(remote.dispatchMessage(incoming(newWidget), ui), "new widget messages should be consumed");
        assertEquals(9, ui.lastNewWidgetId);
        assertEquals("dummy", ui.lastNewWidgetType);
        assertEquals(2, ui.lastNewWidgetParent);
        assertArrayEquals(new Object[]{"parent"}, ui.lastNewWidgetPargs);
        assertArrayEquals(new Object[]{"child", 7}, ui.lastNewWidgetCargs);
    }

    @Test
    @Tag("unit")
    void dispatchMessagePropagatesInterruptedNewWidgetCreation() {
        Session session = newSession();
        RemoteUI remote = new RemoteUI(session);
        RecordingUI ui = new RecordingUI();
        ui.interruptOnNewWidget = true;

        PMessage newWidget = new PMessage(RMessage.RMSG_NEWWDG);
        newWidget.addint32(9);
        newWidget.addstring("dummy");
        newWidget.addint32(-1);
        addEncodedList(newWidget);
        addEncodedList(newWidget);

        assertThrows(InterruptedException.class, () -> remote.dispatchMessage(incoming(newWidget), ui),
                "dispatchMessage() must not swallow interrupted new-widget creation");
    }

    @Test
    @Tag("unit")
    void dispatchMessageHandlesDestroyAndBarrierMessages() throws InterruptedException {
        Session session = newSession();
        RemoteUI remote = new RemoteUI(session);
        RecordingUI ui = new RecordingUI();

        PMessage destroy = new PMessage(RMessage.RMSG_DSTWDG);
        destroy.addint32(11);
        PMessage barrier = new PMessage(RMessage.RMSG_WDGBAR);
        barrier.addint32(1);
        barrier.addint32(2);
        barrier.addint32(-1);
        barrier.addint32(3);
        barrier.addint32(-1);

        assertTrue(remote.dispatchMessage(incoming(destroy), ui));
        assertEquals(11, ui.lastDestroyId);

        assertTrue(remote.dispatchMessage(incoming(barrier), ui));
        assertEquals(2, ui.lastBarrierDeps.size());
        assertEquals(1, ui.lastBarrierBars.size());
    }

    @Test
    @Tag("unit")
    void dispatchMessageReturnsFalseForNullAndReturnMessages() throws InterruptedException {
        Session session = newSession();
        RemoteUI remote = new RemoteUI(session);
        RecordingUI ui = new RecordingUI();

        assertFalse(remote.dispatchMessage(null, ui));
        assertFalse(remote.dispatchMessage(new RemoteUI.Return(session), ui));
    }
}
