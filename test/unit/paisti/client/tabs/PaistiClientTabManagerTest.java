package paisti.client.tabs;

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
import paisti.client.PBootstrap;
import paisti.client.PGLPanelLoop;
import sun.misc.Unsafe;

import java.awt.Canvas;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PaistiClientTabManagerTest {
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

        private void fireClosed() {
            for(Callback callback : callbacks)
                callback.closed();
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
        private int destroyCalls = 0;
        private Runnable beforeDestroy;

        private TestUI() {
            super(new DummyPanel(), Coord.of(10, 10), null);
        }

        @Override
        protected RootWidget createRoot(Coord sz) {
            return new TestRootWidget(this, sz);
        }

        @Override
        public void destroy() {
            if(beforeDestroy != null)
                beforeDestroy.run();
            destroyCalls++;
            super.destroy();
        }
    }

    private static final class TrackingBootstrap extends PBootstrap {
        private int cancelCalls = 0;

        @Override
        public void cancel() {
            cancelCalls++;
        }
    }

    private static final class SessionFixture {
        private final PaistiSessionContext context;
        private final TestUI ui;
        private final DummyTransport transport;

        private SessionFixture(PaistiSessionContext context, TestUI ui, DummyTransport transport) {
            this.context = context;
            this.ui = ui;
            this.transport = transport;
        }
    }

    private static final class ReentrantCloseContext extends PaistiSessionContext {
        private Runnable beforeClose;

        private ReentrantCloseContext(Session session, UI ui, RemoteUI remoteUI) {
            super(session, ui, remoteUI);
        }

        @Override
        public void close() {
            if(beforeClose != null)
                beforeClose.run();
            super.close();
        }
    }

    private static final class TrackingRemoteUI extends RemoteUI {
        private UI attachedUi;

        private TrackingRemoteUI(Session session) {
            super(session);
        }

        @Override
        public void attach(UI ui) {
            attachedUi = ui;
        }
    }

    private static final class TestLoop extends PGLPanelLoop {
        private TestLoop(GLPanel panel) {
            super(panel);
        }

        private void exposeRegisterNewUi(UI.Runner runner, UI previousUi, UI newUi) {
            registerNewUi(runner, previousUi, newUi);
        }
    }

    @BeforeEach
    @AfterEach
    void clearTabs() {
        PaistiClientTabManager.getInstance().clearForTests();
    }

    @Test
    @Tag("unit")
    void pendingLoginTabsCanBeActivatedBeforeTheyHaveUi() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addPendingLoginTab();
        PaistiClientTab second = manager.addPendingLoginTab();

        assertTrue(manager.activateTab(second));

        assertSame(second, manager.getActiveTab());
        assertNull(manager.getActiveUi());
        assertTrue(manager.activateTab(first));
        assertSame(first, manager.getActiveTab());
    }

    @Test
    @Tag("unit")
    void switchToNextCyclesThroughPendingLoginTabs() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addPendingLoginTab();
        PaistiClientTab second = manager.addPendingLoginTab();
        PaistiClientTab third = manager.addPendingLoginTab();

        manager.switchToNext();

        assertSame(second, manager.getActiveTab());

        manager.switchToNext();

        assertSame(third, manager.getActiveTab());

        manager.switchToNext();

        assertSame(first, manager.getActiveTab());
    }

    @Test
    @Tag("unit")
    void createdLoginTabIsActiveAndSelectable() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI ui = new TestUI();

        PaistiClientTab tab = manager.addLoginTab(ui);

        assertEquals(1, manager.getTabs().size());
        assertSame(tab, manager.getActiveTab());
        assertSame(ui, manager.getActiveUi());
        assertTrue(tab.isLogin());
        assertTrue(tab.isSelectable());
        assertEquals("Login", tab.label());
    }

    @Test
    @Tag("unit")
    void sessionTabLabelUsesAccountName() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture fixture = newContext("paisti");

        PaistiClientTab tab = manager.addSessionTab(fixture.context);

        assertTrue(tab.isSession());
        assertEquals("paisti", tab.label());
        assertSame(fixture.context, tab.sessionContext());
        assertSame(fixture.ui, tab.ui());
    }

    @Test
    @Tag("unit")
    void rejectsNullLoginUi() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();

        assertThrows(NullPointerException.class, () -> manager.addLoginTab(null));
        assertEquals(0, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void rejectsNullSessionContext() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();

        assertThrows(NullPointerException.class, () -> manager.addSessionTab(null));
        assertEquals(0, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void preparingSessionUiReplacesActiveLoginUiBeforeSessionContextExists() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI oldLoginUi = new TestUI();
        TestUI inactiveLoginUi = new TestUI();
        PaistiClientTab oldLogin = manager.addLoginTab(oldLoginUi);
        manager.addLoginTab(inactiveLoginUi);
        manager.activateTab(oldLogin);
        TestUI sessionUi = new TestUI();

        UI replaced = manager.prepareSessionUi(sessionUi);

        assertSame(oldLoginUi, replaced);
        assertSame(oldLogin, manager.getActiveTab());
        assertSame(sessionUi, oldLogin.ui());
        assertTrue(oldLogin.isLogin());
        assertSame(sessionUi, manager.getActiveUi());
        assertEquals(2, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void preparingSessionUiWithoutActiveLoginReturnsNullAndLeavesTabsUnchanged() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI emptySessionUi = new TestUI();

        assertSame(null, manager.prepareSessionUi(emptySessionUi));
        assertSame(null, manager.getActiveTab());
        assertEquals(0, manager.getTabs().size());

        SessionFixture session = newContext("active-session");
        PaistiClientTab sessionTab = manager.addSessionTab(session.context);
        TestUI replacementUi = new TestUI();

        assertSame(null, manager.prepareSessionUi(replacementUi));
        assertSame(sessionTab, manager.getActiveTab());
        assertTrue(sessionTab.isSession());
        assertSame(session.ui, sessionTab.ui());
        assertEquals(1, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void convertActiveLoginToSessionKeepsSameTabIdentity() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab login = manager.addLoginTab(new TestUI());
        SessionFixture session = newContext("converted");

        PaistiClientTab converted = manager.convertActiveLoginToSession(session.context);

        assertSame(login, converted);
        assertSame(login, manager.getActiveTab());
        assertTrue(converted.isSession());
        assertSame(session.context, converted.sessionContext());
        assertSame(session.ui, converted.ui());
        assertEquals(1, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void convertPreparedLoginToSessionUsesPreparedTabEvenIfActiveTabChanged() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI loginAUi = new TestUI();
        TestUI loginBUi = new TestUI();
        PaistiClientTab loginA = manager.addLoginTab(loginAUi);
        PaistiClientTab loginB = manager.addLoginTab(loginBUi);
        SessionFixture session = newContext("prepared-switched");

        manager.prepareSessionUi(loginAUi, session.ui);
        manager.activateTab(loginB);
        PaistiClientTab converted = manager.convertActiveLoginToSession(session.context);

        assertSame(loginA, converted);
        assertTrue(loginA.isSession());
        assertSame(session.context, loginA.sessionContext());
        assertSame(session.ui, loginA.ui());
        assertTrue(loginB.isLogin());
        assertSame(loginBUi, loginB.ui());
        assertSame(loginA, manager.getActiveTab());
        assertEquals(2, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void convertWithoutActiveLoginCreatesSessionTabForDirectConnect() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture session = newContext("direct");

        PaistiClientTab created = manager.convertActiveLoginToSession(session.context);

        assertTrue(created.isSession());
        assertSame(session.context, created.sessionContext());
        assertSame(created, manager.getActiveTab());
        assertEquals(1, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void sessionRunnerWithPreparedLoginConvertsSameTabAndReturnsLobbyRunner() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab login = manager.addLoginTab(new TestUI());
        SessionFixture session = newContext("runner-login");
        TrackingRemoteUI remote = new TrackingRemoteUI(session.context.session);
        PaistiSessionRunner runner = new PaistiSessionRunner(remote);

        UI.Runner next = runner.run(session.ui);

        assertSame(session.ui, remote.attachedUi);
        assertTrue(next instanceof PaistiLobbyRunner);
        assertSame(login, manager.getActiveTab());
        assertTrue(login.isSession());
        assertSame(session.ui, login.ui());
        assertSame(session.context.session, login.sessionContext().session);
        assertSame(remote, login.sessionContext().remoteUI);
        assertEquals(1, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void sessionRunnerWithoutActiveLoginCreatesSessionTabAndReturnsLobbyRunner() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture session = newContext("runner-direct");
        TrackingRemoteUI remote = new TrackingRemoteUI(session.context.session);
        PaistiSessionRunner runner = new PaistiSessionRunner(remote);

        UI.Runner next = runner.run(session.ui);

        assertSame(session.ui, remote.attachedUi);
        assertTrue(next instanceof PaistiLobbyRunner);
        PaistiClientTab active = manager.getActiveTab();
        assertTrue(active.isSession());
        assertSame(session.ui, active.ui());
        assertSame(session.context.session, active.sessionContext().session);
        assertSame(remote, active.sessionContext().remoteUI);
        assertEquals(1, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void paistiLoopRegistersLoginTabsForBootstrapRunner() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestLoop loop = new TestLoop(new DummyPanel());
        TestUI loginUi = new TestUI();

        loop.exposeRegisterNewUi(new PBootstrap(), null, loginUi);

        PaistiClientTab active = manager.getActiveTab();
        assertTrue(active.isLogin());
        assertSame(loginUi, active.ui());
        assertEquals(1, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void requestNewLoginTabCreatesVisiblePendingLoginTabImmediately() throws Exception {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        AtomicInteger changes = new AtomicInteger();
        manager.addListener(changes::incrementAndGet);

        manager.requestNewLoginTab();

        assertEquals(1, manager.getTabs().size());
        PaistiClientTab pending = manager.getTabs().get(0);
        assertTrue(pending.isLogin());
        assertEquals("Login", pending.label());
        assertNull(pending.ui());
        assertFalse(pending.isSelectable());
        assertSame(pending, manager.getActiveTab());
        assertTrue(loginRequestAvailable(manager), "pending login tab creation must still wake the lobby runner");
        assertEquals(1, changes.get());
    }

    @Test
    @Tag("unit")
    void multipleLoginRequestsCreateMultipleVisiblePendingLoginTabs() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();

        manager.requestNewLoginTab();
        manager.requestNewLoginTab();
        manager.requestNewLoginTab();

        assertEquals(3, manager.getTabs().size());
        for(PaistiClientTab tab : manager.getTabs()) {
            assertTrue(tab.isLogin());
            assertNull(tab.ui());
        }
    }

    @Test
    @Tag("unit")
    void hydratingOnePendingLoginKeepsRunnerWakeAvailableForRemainingPendingLogin() throws Exception {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestLoop loop = new TestLoop(new DummyPanel());
        manager.requestNewLoginTab();
        manager.requestNewLoginTab();
        assertTrue(loginRequestAvailable(manager));

        loop.exposeRegisterNewUi(new PBootstrap(), null, new TestUI());

        assertTrue(loginRequestAvailable(manager), "remaining pending login tab must wake the next lobby runner immediately");
    }

    @Test
    @Tag("unit")
    void waitForLoginRequestBlocksAfterAllPendingLoginTabsAreHydrated() throws Exception {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestLoop loop = new TestLoop(new DummyPanel());
        manager.requestNewLoginTab();
        manager.requestNewLoginTab();

        loop.exposeRegisterNewUi(new PBootstrap(), null, new TestUI());
        loop.exposeRegisterNewUi(new PBootstrap(), null, new TestUI());

        assertFalse(loginRequestAvailable(manager), "no runner wake remains after every pending login tab is hydrated");
    }

    @Test
    @Tag("unit")
    void paistiLoopHydratesOldestPendingLoginTabForBootstrapRunner() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addPendingLoginTab();
        PaistiClientTab second = manager.addPendingLoginTab();
        AtomicInteger changes = new AtomicInteger();
        manager.addListener(changes::incrementAndGet);
        TestLoop loop = new TestLoop(new DummyPanel());
        TestUI loginUi = new TestUI();

        loop.exposeRegisterNewUi(new PBootstrap(), null, loginUi);

        assertEquals(2, manager.getTabs().size());
        assertSame(first, manager.getTabs().get(0));
        assertSame(second, manager.getTabs().get(1));
        assertSame(loginUi, first.ui());
        assertTrue(first.isSelectable());
        assertNull(second.ui());
        assertSame(first, manager.getActiveTab());
        assertEquals(1, changes.get());
    }

    @Test
    @Tag("unit")
    void paistiLoopRegistersBootstrapLoginCancellation() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestLoop loop = new TestLoop(new DummyPanel());
        TrackingBootstrap bootstrap = new TrackingBootstrap();

        loop.exposeRegisterNewUi(bootstrap, null, new TestUI());
        manager.closeActiveTab();

        assertEquals(1, bootstrap.cancelCalls);
    }

    @Test
    @Tag("unit")
    void pbootstrapCancelReturnsLobbyRunnerWhileWaitingForLogin() throws Exception {
        PBootstrap bootstrap = new PBootstrap();
        TestUI ui = new TestUI();
        AtomicReference<UI.Runner> next = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                next.set(bootstrap.run(ui));
            } catch(Throwable t) {
                failure.set(t);
            }
        }, "pbootstrap-cancel-test");

        thread.start();
        bootstrap.cancel();
        thread.join(2000);
        if(thread.isAlive())
            thread.interrupt();

        assertFalse(thread.isAlive(), "cancel must unblock the login wait");
        assertNull(failure.get());
        assertTrue(next.get() instanceof PaistiLobbyRunner);
    }

    @Test
    @Tag("unit")
    void paistiLoopPreparesSessionUiForSessionRunner() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI loginUi = new TestUI();
        PaistiClientTab login = manager.addLoginTab(loginUi);
        SessionFixture session = newContext("loop-session");
        TestLoop loop = new TestLoop(new DummyPanel());

        loop.exposeRegisterNewUi(new PaistiSessionRunner(session.context.remoteUI), loginUi, session.ui);

        assertSame(login, manager.getActiveTab());
        assertTrue(login.isLogin());
        assertSame(session.ui, login.ui());
        assertEquals(1, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void paistiLoopSkipsSessionUiPreparationWithoutPreviousLoginUiForDirectConnect() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture session = newContext("loop-direct");
        TrackingRemoteUI remote = new TrackingRemoteUI(session.context.session);
        PaistiSessionRunner runner = new PaistiSessionRunner(remote);
        TestLoop loop = new TestLoop(new DummyPanel());

        loop.exposeRegisterNewUi(runner, null, session.ui);
        assertEquals(0, manager.getTabs().size());

        UI.Runner next = runner.run(session.ui);

        assertTrue(next instanceof PaistiLobbyRunner);
        PaistiClientTab active = manager.getActiveTab();
        assertTrue(active.isSession());
        assertSame(session.ui, active.ui());
        assertSame(remote, active.sessionContext().remoteUI);
        assertEquals(1, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void rejectsNullSessionUiPreparation() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();

        assertThrows(NullPointerException.class, () -> manager.prepareSessionUi(null));
        assertEquals(0, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void rejectsNullLoginToSessionConversionContext() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();

        assertThrows(NullPointerException.class, () -> manager.convertActiveLoginToSession(null));
        assertEquals(0, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void loginAndSessionTabsAreBothSelectable() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI loginUi = new TestUI();
        PaistiClientTab login = manager.addLoginTab(loginUi);
        SessionFixture session = newContext("session");
        PaistiClientTab sessionTab = manager.addSessionTab(session.context);

        assertTrue(manager.activateTab(login));
        assertSame(loginUi, manager.getActiveUi());

        assertTrue(manager.activateTab(sessionTab));
        assertSame(session.ui, manager.getActiveUi());
    }

    @Test
    @Tag("unit")
    void activateTabRejectsNullUnregisteredAndNonSelectableTabs() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab active = manager.addLoginTab(new TestUI());
        SessionFixture registered = newContext("registered");
        PaistiClientTab retiring = manager.addSessionTab(registered.context);
        SessionFixture unregistered = newContext("unregistered");
        PaistiClientTab unregisteredTab = new PaistiClientTab(99, unregistered.context);
        manager.activateTab(active);
        registered.context.close();

        assertFalse(manager.activateTab(null));
        assertSame(active, manager.getActiveTab());

        assertFalse(manager.activateTab(unregisteredTab));
        assertSame(active, manager.getActiveTab());

        assertFalse(manager.activateTab(retiring));
        assertSame(active, manager.getActiveTab());
    }

    @Test
    @Tag("unit")
    void switchToNextAndPreviousCyclesOnlySelectableTabs() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        SessionFixture retiring = newContext("retiring");
        manager.addSessionTab(retiring.context);
        PaistiClientTab third = manager.addLoginTab(new TestUI());
        retiring.context.close();

        assertSame(third, manager.getActiveTab());
        manager.switchToNext();
        assertSame(first, manager.getActiveTab());

        manager.switchToPrevious();
        assertSame(third, manager.getActiveTab());
    }

    @Test
    @Tag("unit")
    void closingActiveLoginTabDestroysOnlyThatUiAndSelectsNeighbor() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI firstUi = new TestUI();
        TestUI secondUi = new TestUI();
        PaistiClientTab first = manager.addLoginTab(firstUi);
        manager.addLoginTab(secondUi);

        manager.closeActiveTab();

        assertEquals(1, manager.getTabs().size());
        assertSame(first, manager.getActiveTab());
        assertEquals(1, secondUi.destroyCalls);
        assertEquals(0, firstUi.destroyCalls);
    }

    @Test
    @Tag("unit")
    void closingLoginTabRunsCancelBeforeDestroyingUi() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI ui = new TestUI();
        AtomicInteger cancelCalls = new AtomicInteger();
        ui.beforeDestroy = () -> assertEquals(1, cancelCalls.get(), "login cancellation must happen before UI destruction");
        manager.addLoginTab(ui, cancelCalls::incrementAndGet);

        manager.closeActiveTab();

        assertEquals(1, cancelCalls.get(), "closing a login tab must unblock Bootstrap.run()");
        assertEquals(1, ui.destroyCalls, "closing a login tab still destroys only that UI");
    }

    @Test
    @Tag("unit")
    void closingLastLoginTabDestroysUiAndRequestsReplacementLogin() throws Exception {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI ui = new TestUI();
        manager.addLoginTab(ui);

        manager.closeActiveTab();

        assertEquals(1, manager.getTabs().size());
        assertTrue(manager.getActiveTab().isLogin());
        assertNull(manager.getActiveTab().ui());
        assertEquals(1, ui.destroyCalls, "closing the last login tab still destroys that UI once");
        assertTrue(loginRequestAvailable(manager), "closing the last login tab must request a replacement login");
    }

    @Test
    @Tag("unit")
    void convertingLoginToSessionClearsCancelCallback() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        AtomicInteger cancelCalls = new AtomicInteger();
        manager.addLoginTab(new TestUI(), cancelCalls::incrementAndGet);
        SessionFixture session = newContext("converted-no-cancel");

        manager.convertActiveLoginToSession(session.context);
        manager.closeActiveTab();

        assertEquals(0, cancelCalls.get(), "session close must not cancel an already completed login");
    }

    @Test
    @Tag("unit")
    void closingActiveSessionStartsShutdownWithoutImmediateDisposal() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");
        PaistiClientTab firstTab = manager.addSessionTab(first.context);
        manager.addSessionTab(second.context);

        manager.closeActiveTab();

        assertEquals(2, manager.getTabs().size(), "session tab remains registered until terminal pruning");
        assertSame(firstTab, manager.getActiveTab());
        assertEquals(0, second.ui.destroyCalls, "active session UI must not be destroyed synchronously");
        assertEquals(1, second.transport.closeCalls, "closing session tab starts session shutdown exactly once");
    }

    @Test
    @Tag("unit")
    void pruneDeadSessionsRemovesTerminalSessionTabsAndDisposesOnce() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");
        PaistiClientTab firstTab = manager.addSessionTab(first.context);
        manager.addSessionTab(second.context);

        second.context.session.close();
        manager.pruneDeadSessions();

        assertEquals(2, manager.getTabs().size(), "close request alone is not terminal");

        second.transport.fireClosed();
        manager.pruneDeadSessions();
        manager.pruneDeadSessions();

        assertEquals(1, manager.getTabs().size());
        assertSame(firstTab, manager.getActiveTab());
        assertEquals(1, second.ui.destroyCalls);
        assertEquals(1, second.transport.closeCalls);
    }

    @Test
    @Tag("unit")
    void closingSessionMarksItNonSelectableBeforeShutdown() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");
        ReentrantCloseContext closingContext = new ReentrantCloseContext(second.context.session, second.ui, second.context.remoteUI);
        PaistiClientTab firstTab = manager.addSessionTab(first.context);
        PaistiClientTab closingTab = manager.addSessionTab(closingContext);
        closingContext.beforeClose = () -> assertFalse(manager.activateTab(closingTab));

        manager.closeActiveTab();

        assertSame(firstTab, manager.getActiveTab());
        assertEquals(1, second.transport.closeCalls);
    }

    @Test
    @Tag("unit")
    void requestNewLoginTabWaiterHandshake() throws Exception {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        CountDownLatch released = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            try {
                manager.waitForLoginRequest();
                released.countDown();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        manager.requestNewLoginTab();

        assertTrue(released.await(2, TimeUnit.SECONDS), "requestNewLoginTab() must release the waiting thread");
        waiter.join(2000);
        assertFalse(waiter.isAlive(), "waiter thread should finish after the login-tab signal");
    }

    @Test
    @Tag("unit")
    void sessionContextLookupOnlyReturnsManagedSessionTabs() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI loginUi = new TestUI();
        manager.addLoginTab(loginUi);
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");
        manager.addSessionTab(first.context);
        manager.addSessionTab(second.context);

        List<PaistiSessionContext> contexts = manager.getSessionContexts();

        assertEquals(2, contexts.size());
        assertSame(first.context, contexts.get(0));
        assertSame(second.context, contexts.get(1));
        assertSame(first.context, manager.findSessionContext(first.ui));
        assertNull(manager.findSessionContext(loginUi));
        assertTrue(manager.isSessionUi(first.ui));
        assertFalse(manager.isSessionUi(loginUi));
        assertFalse(manager.isSessionUi(new TestUI()));
    }

    @Test
    @Tag("unit")
    void retireActiveMovesToSelectableSuccessorOrNull() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab login = manager.addLoginTab(new TestUI());
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");
        PaistiClientTab firstTab = manager.addSessionTab(first.context);
        PaistiClientTab secondTab = manager.addSessionTab(second.context);

        manager.retireActive(second.context);

        assertSame(firstTab, manager.getActiveTab());

        manager.activateTab(firstTab);
        first.context.close();
        manager.retireActive(first.context);

        assertSame(secondTab, manager.getActiveTab());

        manager.closeTab(login);
        manager.activateTab(secondTab);
        second.context.close();
        manager.retireActive(second.context);

        assertNull(manager.getActiveTab());
    }

    @Test
    @Tag("unit")
    void replaceSessionContextReusesOwningTabAndFallsBackToAddSessionTab() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture oldSession = newContext("old");
        PaistiClientTab tab = manager.addSessionTab(oldSession.context);
        SessionFixture replacement = newContext("replacement");

        PaistiClientTab replaced = manager.replaceSessionContext(oldSession.context, replacement.context);

        assertSame(tab, replaced);
        assertSame(tab, manager.getActiveTab());
        assertSame(replacement.context, tab.sessionContext());
        assertSame(replacement.ui, tab.ui());
        assertEquals(1, manager.getTabs().size());

        SessionFixture fallback = newContext("fallback");
        PaistiClientTab added = manager.replaceSessionContext(oldSession.context, fallback.context);

        assertSame(added, manager.getActiveTab());
        assertSame(fallback.context, added.sessionContext());
        assertEquals(2, manager.getTabs().size());
    }

    @Test
    @Tag("unit")
    void pruneDeadSessionsRemovesTerminalTabsDisposesOnceAndRequestsLoginWhenEmpty() throws Exception {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture alive = newContext("alive");
        TrackingDisposeContext deadContext = new TrackingDisposeContext(newContext("dead"));
        PaistiClientTab aliveTab = manager.addSessionTab(alive.context);
        manager.addSessionTab(deadContext);
        forceSessionClosed(deadContext.session);

        manager.pruneDeadSessions();
        manager.pruneDeadSessions();

        assertEquals(1, manager.getTabs().size());
        assertSame(aliveTab, manager.getActiveTab());
        assertEquals(1, deadContext.disposeCalls);
        assertFalse(loginRequestAvailable(manager));

        forceSessionClosed(alive.context.session);
        manager.pruneDeadSessions();
        manager.pruneDeadSessions();

        assertEquals(1, manager.getTabs().size());
        assertTrue(manager.getActiveTab().isLogin());
        assertNull(manager.getActiveTab().ui());
        assertTrue(loginRequestAvailable(manager));
    }

    @Test
    @Tag("unit")
    void pruneDeadSessionsSelectsNextTabAfterRemovedActiveBeforePreviousFallback() throws Exception {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        SessionFixture first = newContext("first");
        SessionFixture active = newContext("active");
        SessionFixture next = newContext("next");
        SessionFixture newest = newContext("newest");
        PaistiClientTab firstTab = manager.addSessionTab(first.context);
        PaistiClientTab activeTab = manager.addSessionTab(active.context);
        PaistiClientTab nextTab = manager.addSessionTab(next.context);
        manager.addSessionTab(newest.context);
        manager.activateTab(activeTab);
        forceSessionClosed(active.context.session);

        manager.pruneDeadSessions();

        assertSame(nextTab, manager.getActiveTab(), "pruning active tab must select the next tab after its removed position");

        manager.activateTab(nextTab);
        forceSessionClosed(next.context.session);
        forceSessionClosed(newest.context.session);
        manager.pruneDeadSessions();

        assertSame(firstTab, manager.getActiveTab(), "when no later selectable tab exists, pruning falls back to previous selectable tab");
    }

    @Test
    @Tag("unit")
    void repeatedLoginRequestsWakeOneRunnerPerVisiblePendingLoginTab() throws Exception {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();

        manager.requestNewLoginTab();
        manager.requestNewLoginTab();
        manager.requestNewLoginTab();

        CountDownLatch firstWake = new CountDownLatch(1);
        CountDownLatch secondWakeAttempt = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            try {
                manager.waitForLoginRequest();
                firstWake.countDown();
                manager.waitForLoginRequest();
                secondWakeAttempt.countDown();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        assertTrue(firstWake.await(2, TimeUnit.SECONDS), "first waitForLoginRequest must wake up");
        assertTrue(secondWakeAttempt.await(2, TimeUnit.SECONDS),
            "multiple visible pending login tabs must keep waking lobby runner waits");

        waiter.interrupt();
        waiter.join(2000);
    }

    @Test
    @Tag("unit")
    void paistiLobbyRunnerWaitsForLoginRequestBeforeReturningBootstrap() throws Exception {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiLobbyRunner runner = new PaistiLobbyRunner();
        TestUI ui = new TestUI();
        CountDownLatch runnerStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<UI.Runner> result = executor.submit(() -> {
                runnerStarted.countDown();
                return runner.run(ui);
            });

            assertTrue(runnerStarted.await(2, TimeUnit.SECONDS), "PaistiLobbyRunner task must start");
            try {
                result.get(100, TimeUnit.MILLISECONDS);
                fail("PaistiLobbyRunner must block until a login tab is requested");
            } catch(TimeoutException expected) {
            }

            manager.requestNewLoginTab();

            assertTrue(result.get(2, TimeUnit.SECONDS) instanceof PBootstrap,
                "PaistiLobbyRunner must hand control back to PBootstrap");
        } finally {
            executor.shutdownNow();
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
            return new SessionFixture(new PaistiSessionContext(session, ui, new RemoteUI(session)), ui, transport);
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class TrackingDisposeContext extends PaistiSessionContext {
        private int disposeCalls = 0;

        private TrackingDisposeContext(SessionFixture fixture) {
            super(fixture.context.session, fixture.ui, fixture.context.remoteUI);
        }

        @Override
        public void dispose() {
            disposeCalls++;
            super.dispose();
        }
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

    private static void forceSessionClosed(Session session) throws Exception {
        setField(Session.class, session, "closereq", true);
        setField(Session.class, session, "connclosed", true);
        setField(Session.class, session, "closed", true);
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
