# Multi-Session Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add support for keeping multiple live sessions connected at once, ticking all of them in the GL loop while rendering and accepting input for only the currently visible UI.

**Architecture:** Keep the current two-thread model (`MainFrame` uiloop + `GLPanel.Loop` render loop) and make sessions first-class runtime objects via `SessionContext` and `SessionManager`. `Bootstrap` still performs authentication, but instead of blocking forever in `RemoteUI.run()`, it hands the logged-in session to `SessionRunner`, which registers the session and returns `LobbyRunner`. The GL loop becomes responsible for polling server UI messages, CPU-ticking every registered session, and rendering either the active session UI or the login UI when the user is adding another account.

**Tech Stack:** Java 11, Ant build system, JUnit 5 unit tests already wired through `build.xml`, existing Haven UI/GL architecture.

**Build command:** `ant clean-code bin -buildfile build.xml`
**Test command:** `ant test -buildfile build.xml`
**Test strategy note:** `build.xml` does not expose a single-test target, so the red/green checkpoints below use the full `ant test -buildfile build.xml` suite.

---

## Scope Choice

This stays as one plan because the work is cross-cutting but still one cohesive feature: session-safe state, message dispatch handoff, session registry, runner handoff, GL loop changes, and user switching. The tasks below land in safe vertical slices so the client keeps building and the new unit tests ratchet the behavior forward.

Two deliberate scope limits for this plan:

- `MainFrame` direct `servargs` startup should use the new multi-session runner path.
- `Bootstrap.replay` remains single-session-only for now. Playback is a debugging path, not the main feature, and forcing it through the lobby/session-manager flow adds complexity with little value.

---

## File Structure

### New production files

- `src/haven/session/SessionContext.java` — immutable holder for one live session's `Session`, `UI`, and `RemoteUI`
- `src/haven/session/SessionManager.java` — singleton registry of registered sessions, active-session pointer, and add-account semaphore
- `src/haven/session/LobbyRunner.java` — blocks the uiloop until the player requests another login
- `src/haven/session/SessionRunner.java` — bridges `Bootstrap`/direct `RemoteUI` startup into `SessionManager`

### Modified production files

- `src/haven/Session.java` — add `pollUIMsg()` and `isClosed()`
- `src/haven/RemoteUI.java` — extract message dispatch and add a non-blocking attach helper
- `src/haven/Glob.java` — add per-session `gobFactory`
- `src/haven/Gob.java` — remove static global gob factory
- `src/haven/OCache.java` — instantiate gobs through `glob.gobFactory`
- `src/paisti/client/PUI.java` — bind `PGob` factory per session instead of mutating global static state
- `src/haven/Bootstrap.java` — return `SessionRunner` after successful login
- `src/haven/MainFrame.java` — wrap direct connect startup in `SessionRunner`, keep replay raw
- `src/haven/GLPanel.java` — preserve registered session UIs, tick all sessions, render only the visible UI
- `src/haven/GameUI.java` — add session switching and add-account keybindings

### Modified test files

- `test/unit/paisti/client/PUILifecycleTest.java` — cover session-scoped gob factory behavior
- `test/unit/haven/GLPanelLoopTest.java` — cover lobby/session UI preservation in `GLPanel.Loop.newui()`

### New test files

- `test/unit/haven/RemoteUITest.java` — cover `Session.pollUIMsg()` and `RemoteUI.dispatchMessage()`
- `test/unit/haven/session/SessionManagerTest.java` — cover registry, active-session cycling, and add-account signaling
- `test/unit/haven/SessionRunnerTest.java` — cover `SessionRunner`/`LobbyRunner` behavior

---

### Task 1: Scope Gob Creation to Each Session

**Files:**
- Modify: `src/haven/Gob.java:45-51`
- Modify: `src/haven/Glob.java:37-64`
- Modify: `src/haven/OCache.java:441-456`
- Modify: `src/haven/Session.java:353-358`
- Modify: `src/paisti/client/PUI.java:67-73`
- Modify: `test/unit/paisti/client/PUILifecycleTest.java`

- [ ] **Step 1: Write the failing unit tests in `test/unit/paisti/client/PUILifecycleTest.java`**

Add a session-aware runner helper and two new tests to the existing file:

```java
private static final class DummyTransport implements Transport {
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
        return this;
    }
}

private static final class SessionBindingRunner implements UI.Runner {
    private final Session session;

    private SessionBindingRunner(Session session) {
        this.session = session;
    }

    @Override
    public void init(UI ui) {
        ui.sess = session;
        session.ui = ui;
    }

    @Override
    public UI.Runner run(UI ui) {
        return null;
    }
}

private static Session newSession(String name) {
    return new Session(new DummyTransport(), new Session.User(name));
}

private static class TestPUI extends PUI {
    TestPUI(UI.Runner fun) {
        super(new DummyPanel(), Coord.of(10, 10), fun);
    }

    TestPUI() {
        this(null);
    }

    @Override
    protected RootWidget createRoot(Coord sz) {
        return new TestRootWidget(this, sz);
    }
}

@Test
@Tag("unit")
void puiBindsPgobFactoryToItsOwnSessionGlob() {
    Session session = newSession("alpha");
    PUI pui = new TestPUI(new SessionBindingRunner(session));

    Gob gob = session.glob.gobFactory.create(session.glob, Coord2d.z, 1L);

    assertTrue(gob instanceof PGob, "PUI must install PGob creation on its own session glob");
    assertSame(pui, session.ui, "sanity check: the runner should bind the session to the PUI");
}

@Test
@Tag("unit")
void destroyingOnePuiDoesNotResetAnotherSessionsGobFactory() {
    Session firstSession = newSession("first");
    Session secondSession = newSession("second");
    PUI first = new TestPUI(new SessionBindingRunner(firstSession));
    PUI second = new TestPUI(new SessionBindingRunner(secondSession));

    first.destroy();

    Gob gob = secondSession.glob.gobFactory.create(secondSession.glob, Coord2d.z, 2L);

    assertTrue(gob instanceof PGob, "destroying one PUI must not reset gob creation for another live session");
    second.destroy();
}
```

- [ ] **Step 2: Run the test suite and confirm it fails for the missing session-scoped factory**

Run: `ant test -buildfile build.xml`

Expected: `BUILD FAILED` during `compile-test` because `Glob.gobFactory` does not exist yet and `Gob.factory` is still the only creation path.

- [ ] **Step 3: Add the per-session gob factory to `Glob` and stop exposing the static one from `Gob`**

Apply these changes:

```java
// src/haven/Gob.java
public class Gob implements RenderTree.Node, Sprite.Owner, Skeleton.ModOwner, EquipTarget, RandomSource {
    @FunctionalInterface
    public interface Factory {
        Gob create(Glob glob, Coord2d c, long id);
    }

    public Coord2d rc;
```

```java
// src/haven/Glob.java
public class Glob {
    public final OCache oc = new OCache(this);
    public final MCache map;
    public final Session sess;
    public Gob.Factory gobFactory = Gob::new;
    public final Loader loader = new Loader();
```

- [ ] **Step 4: Switch `OCache` and session startup over to the session-scoped factory**

Apply these changes:

```java
// src/haven/OCache.java
if(gob == null) {
    gob = glob.gobFactory.create(glob, Coord2d.z, id);
    gob.virtual = virtual;
}
```

```java
// src/haven/Session.java
public Session(Transport conn, User user) {
    this.character = new CharacterInfo(this);
    this.conn = conn;
    this.user = user;
    this.glob = new Glob(this);
    this.glob.gobFactory = PGob::new;
    conn.add(conncb);
    // ... rest unchanged ...
}
```

```java
// src/paisti/client/PUI.java
@Override
public void destroy() {
    paistiServices.stop();
    paistiServices.clearUi(this);
    super.destroy();
}
```

- [ ] **Step 5: Re-run the test suite and confirm the new gob-factory behavior passes**

Run: `ant test -buildfile build.xml`

Expected: `BUILD SUCCESSFUL`, including the two new `PUILifecycleTest` assertions.

- [ ] **Step 6: Commit the prerequisite refactor**

Run:

```bash
git add src/haven/Gob.java src/haven/Glob.java src/haven/OCache.java src/haven/Session.java src/paisti/client/PUI.java test/unit/paisti/client/PUILifecycleTest.java
git commit -m "refactor: scope gob factory to each session"
```

---

### Task 2: Extract Polling and UI Message Dispatch from `RemoteUI`

**Files:**
- Modify: `src/haven/Session.java:389-410`
- Modify: `src/haven/RemoteUI.java:56-156`
- Create: `test/unit/haven/RemoteUITest.java`

- [ ] **Step 1: Create the failing dispatch/poll tests in `test/unit/haven/RemoteUITest.java`**

Create this new test file:

```java
package haven;

import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

class RemoteUITest {
    private static final class DummyTransport implements Transport {
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

    private static final class RecordingUI extends UI {
        private int lastDestroyId = -1;
        private int lastAddId = -1;
        private int lastAddParent = -1;
        private int lastMessageId = -1;
        private String lastMessageName;
        private Object[] lastMessageArgs;
        private Collection<Integer> lastBarrierDeps;
        private Collection<Integer> lastBarrierBars;

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
        public void addwidget(int id, int parent, Object... pargs) {
            lastAddId = id;
            lastAddParent = parent;
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

    private static Session newSession() {
        return new Session(new DummyTransport(), new Session.User("tester"));
    }

@Test
@Tag("unit")
void pollUIMsgReturnsQueuedMessagesThenNull() {
    Session session = newSession();
    PMessage msg = new PMessage(RMessage.RMSG_DSTWDG);
    msg.addint32(77);

        session.postuimsg(msg);

        assertSame(msg, session.pollUIMsg(), "pollUIMsg() must return the queued message without blocking");
        assertNull(session.pollUIMsg(), "pollUIMsg() must return null when the queue is empty");
    }

    @Test
    @Tag("unit")
    void dispatchMessageRoutesWidgetMessages() {
        Session session = newSession();
        RemoteUI remote = new RemoteUI(session);
        RecordingUI ui = new RecordingUI();

        PMessage widgetMessage = new PMessage(RMessage.RMSG_WDGMSG);
        widgetMessage.addint32(5);
        widgetMessage.addstring("ping");
        widgetMessage.addlist(new Object[] {"arg", 3});

        assertTrue(remote.dispatchMessage(widgetMessage, ui), "widget messages should be consumed");
        assertEquals(5, ui.lastMessageId);
        assertEquals("ping", ui.lastMessageName);
        assertArrayEquals(new Object[] {"arg", 3}, ui.lastMessageArgs);
    }

@Test
@Tag("unit")
void dispatchMessageHandlesDestroyAndBarrierMessages() {
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

        assertTrue(remote.dispatchMessage(destroy, ui));
        assertEquals(11, ui.lastDestroyId);

        assertTrue(remote.dispatchMessage(barrier, ui));
        assertEquals(2, ui.lastBarrierDeps.size());
        assertEquals(1, ui.lastBarrierBars.size());
    }

    @Test
    @Tag("unit")
    void dispatchMessageReturnsFalseForNullAndReturnMessages() {
        Session session = newSession();
        RemoteUI remote = new RemoteUI(session);
        RecordingUI ui = new RecordingUI();

        assertFalse(remote.dispatchMessage(null, ui));
        assertFalse(remote.dispatchMessage(new RemoteUI.Return(session), ui));
    }
}
```

- [ ] **Step 2: Run the test suite and confirm it fails because the new API does not exist yet**

Run: `ant test -buildfile build.xml`

Expected: `BUILD FAILED` during `compile-test` because `Session.pollUIMsg()` and `RemoteUI.dispatchMessage(...)` do not exist yet.

- [ ] **Step 3: Add `pollUIMsg()` and `isClosed()` to `Session`**

Insert these methods after `getuimsg()` in `src/haven/Session.java`:

```java
public PMessage pollUIMsg() {
    synchronized(uimsgs) {
        if(uimsgs.isEmpty())
            return(null);
        return(uimsgs.remove());
    }
}

public boolean isClosed() {
    return(closed);
}
```

- [ ] **Step 4: Extract attach/dispatch helpers from `RemoteUI` and refactor `run()` to use them**

Apply these changes in `src/haven/RemoteUI.java`:

```java
public void attach(UI ui) {
    ui.setreceiver(this);
    sendua(ui);
}

public boolean dispatchMessage(PMessage msg, UI ui) {
    if(msg == null)
        return(false);
    if(msg instanceof Return)
        return(false);
    if(msg.type == RMessage.RMSG_NEWWDG) {
        int id = msg.int32();
        String type = msg.string();
        int parent = msg.int32();
        Object[] pargs = msg.list(sess.resmapper);
        Object[] cargs = msg.list(sess.resmapper);
        ui.newwidgetp(id, type, parent, pargs, cargs);
    } else if(msg.type == RMessage.RMSG_WDGMSG) {
        int id = msg.int32();
        String name = msg.string();
        ui.uimsg(id, name, msg.list(sess.resmapper));
    } else if(msg.type == RMessage.RMSG_DSTWDG) {
        int id = msg.int32();
        ui.destroy(id);
    } else if(msg.type == RMessage.RMSG_ADDWDG) {
        int id = msg.int32();
        int parent = msg.int32();
        Object[] pargs = msg.list(sess.resmapper);
        ui.addwidget(id, parent, pargs);
    } else if(msg.type == RMessage.RMSG_WDGBAR) {
        Collection<Integer> deps = new ArrayList<>();
        while(!msg.eom()) {
            int dep = msg.int32();
            if(dep == -1)
                break;
            deps.add(dep);
        }
        Collection<Integer> bars = deps;
        if(!msg.eom()) {
            bars = new ArrayList<>();
            while(!msg.eom()) {
                int bar = msg.int32();
                if(bar == -1)
                    break;
                bars.add(bar);
            }
        }
        ui.wdgbarrier(deps, bars);
    }
    return(true);
}
```

```java
public UI.Runner run(UI ui) throws InterruptedException {
    try {
        attach(ui);
        while(true) {
            PMessage msg = sess.getuimsg();
            if(msg == null) {
                return(null);
            } else if(msg instanceof Return) {
                sess.close();
                return(new RemoteUI(((Return)msg).ret));
            } else {
                dispatchMessage(msg, ui);
            }
        }
    } finally {
        sess.close();
        while(sess.getuimsg() != null);
    }
}
```

- [ ] **Step 5: Re-run the suite and confirm the new queue/dispatch behavior is green**

Run: `ant test -buildfile build.xml`

Expected: `BUILD SUCCESSFUL`, including the new `RemoteUITest` class.

- [ ] **Step 6: Commit the queue/dispatch extraction**

Run:

```bash
git add src/haven/Session.java src/haven/RemoteUI.java test/unit/haven/RemoteUITest.java
git commit -m "refactor: extract remote ui dispatch helpers"
```

---

### Task 3: Add `SessionContext` and `SessionManager`

**Files:**
- Create: `src/haven/session/SessionContext.java`
- Create: `src/haven/session/SessionManager.java`
- Create: `test/unit/haven/session/SessionManagerTest.java`

- [ ] **Step 1: Create the failing session-manager tests in `test/unit/haven/session/SessionManagerTest.java`**

Create this test file:

```java
package haven.session;

import haven.*;
import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {
    private static final class DummyTransport implements Transport {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void queuemsg(PMessage pmsg) {
        }

        @Override
        public void send(PMessage msg) {
        }

        @Override
        public Transport add(Callback cb) {
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

    private static SessionContext newContext(String name) {
        Session session = new Session(new DummyTransport(), new Session.User(name));
        UI ui = new TestUI();
        session.ui = ui;
        ui.sess = session;
        return new SessionContext(session, ui, new RemoteUI(session));
    }

    private static void reset(SessionManager manager) {
        for(SessionContext ctx : manager.getSessions()) {
            manager.removeSession(ctx);
        }
    }

    @Test
    @Tag("unit")
    void addSessionMakesNewestSessionActive() {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        SessionContext first = newContext("first");
        SessionContext second = newContext("second");

        manager.addSession(first);
        manager.addSession(second);

        assertEquals(2, manager.getSessions().size());
        assertSame(second, manager.getActiveSession());

        reset(manager);
    }

    @Test
    @Tag("unit")
    void switchToNextCyclesThroughRegisteredSessions() {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        SessionContext first = newContext("first");
        SessionContext second = newContext("second");

        manager.addSession(first);
        manager.addSession(second);
        manager.switchToNext();

        assertSame(first, manager.getActiveSession());

        reset(manager);
    }

    @Test
    @Tag("unit")
    void pruneDeadSessionsRemovesClosedEntries() {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        SessionContext first = newContext("first");
        SessionContext second = newContext("second");

        manager.addSession(first);
        manager.addSession(second);
        second.close();
        manager.pruneDeadSessions();

        assertEquals(1, manager.getSessions().size());
        assertSame(first, manager.getActiveSession());

        reset(manager);
    }

    @Test
    @Tag("unit")
    void addAccountSignalUnblocksLobbyWaiter() throws Exception {
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

        assertTrue(released.await(2, TimeUnit.SECONDS), "requestAddAccount() must release the waiting lobby runner");
        waiter.join(2000);
    }
}
```

- [ ] **Step 2: Run the suite and confirm it fails for the missing session infrastructure**

Run: `ant test -buildfile build.xml`

Expected: `BUILD FAILED` during `compile-test` because `SessionContext` and `SessionManager` do not exist yet.

- [ ] **Step 3: Create `src/haven/session/SessionContext.java`**

Create this file exactly:

```java
package haven.session;

import haven.RemoteUI;
import haven.Session;
import haven.UI;

public class SessionContext {
    public final Session session;
    public final UI ui;
    public final RemoteUI remoteUI;
    private volatile boolean alive = true;

    public SessionContext(Session session, UI ui, RemoteUI remoteUI) {
        this.session = session;
        this.ui = ui;
        this.remoteUI = remoteUI;
    }

    public boolean isAlive() {
        return(alive && !session.isClosed());
    }

    public void close() {
        alive = false;
        session.close();
    }
}
```

- [ ] **Step 4: Create `src/haven/session/SessionManager.java`**

Create this file exactly:

```java
package haven.session;

import haven.UI;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;

public class SessionManager {
    private static final SessionManager instance = new SessionManager();

    public static SessionManager getInstance() {
        return(instance);
    }

    private final List<SessionContext> sessions = new ArrayList<>();
    private final Semaphore addAccountSignal = new Semaphore(0);
    private volatile SessionContext activeSession;

    private SessionManager() {
    }

    public synchronized void addSession(SessionContext ctx) {
        sessions.add(ctx);
        activeSession = ctx;
    }

    public synchronized void removeSession(SessionContext ctx) {
        sessions.remove(ctx);
        ctx.close();
        synchronized(ctx.ui) {
            ctx.ui.destroy();
        }
        if(activeSession == ctx) {
            activeSession = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
        }
    }

    public synchronized List<SessionContext> getSessions() {
        return(new ArrayList<>(sessions));
    }

    public synchronized boolean isSessionUi(UI ui) {
        for(SessionContext ctx : sessions) {
            if(ctx.ui == ui)
                return(true);
        }
        return(false);
    }

    public SessionContext getActiveSession() {
        return(activeSession);
    }

    public synchronized void switchToNext() {
        if(sessions.size() <= 1)
            return;
        int idx = sessions.indexOf(activeSession);
        if(idx < 0)
            idx = 0;
        activeSession = sessions.get((idx + 1) % sessions.size());
    }

    public void requestAddAccount() {
        addAccountSignal.release();
    }

    public void waitForAddRequest() throws InterruptedException {
        addAccountSignal.acquire();
    }

    public synchronized void pruneDeadSessions() {
        for(Iterator<SessionContext> it = sessions.iterator(); it.hasNext();) {
            SessionContext ctx = it.next();
            if(!ctx.isAlive()) {
                synchronized(ctx.ui) {
                    ctx.ui.destroy();
                }
                it.remove();
                if(activeSession == ctx) {
                    activeSession = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
                }
            }
        }
    }
}
```

- [ ] **Step 5: Re-run the suite and confirm the manager registry is green**

Run: `ant test -buildfile build.xml`

Expected: `BUILD SUCCESSFUL`, including the new `SessionManagerTest` class.

- [ ] **Step 6: Commit the session registry layer**

Run:

```bash
git add src/haven/session/SessionContext.java src/haven/session/SessionManager.java test/unit/haven/session/SessionManagerTest.java
git commit -m "feat: add session registry infrastructure"
```

---

### Task 4: Hand Logged-In Sessions Off to the Manager

**Files:**
- Create: `src/haven/session/LobbyRunner.java`
- Create: `src/haven/session/SessionRunner.java`
- Create: `test/unit/haven/SessionRunnerTest.java`
- Modify: `src/haven/Bootstrap.java:331-337`
- Modify: `src/haven/MainFrame.java:466-479`

- [ ] **Step 1: Create the failing runner tests in `test/unit/haven/SessionRunnerTest.java`**

Create this test file:

```java
package haven;

import haven.session.LobbyRunner;
import haven.session.SessionContext;
import haven.session.SessionManager;
import haven.session.SessionRunner;
import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionRunnerTest {
    private static final class DummyTransport implements Transport {
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

    private static final class TrackingRemoteUI extends RemoteUI {
        private int attachCalls;

        private TrackingRemoteUI(Session sess) {
            super(sess);
        }

        @Override
        public void attach(UI ui) {
            attachCalls++;
            super.attach(ui);
        }
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
        Session session = new Session(new DummyTransport(), new Session.User("tester"));
        TrackingRemoteUI remote = new TrackingRemoteUI(session);
        SessionRunner runner = new SessionRunner(remote);
        UI ui = new TestUI();

        runner.init(ui);
        UI.Runner next = runner.run(ui);

        assertTrue(next instanceof LobbyRunner);
        assertSame(remote, ui.rcvr, "SessionRunner must attach the remote receiver before handing off to the lobby");
        assertEquals(1, remote.attachCalls, "attach() should be called exactly once");
        assertSame(ui, manager.getActiveSession().ui);

        reset(manager);
    }

    @Test
    @Tag("unit")
    void lobbyRunnerWaitsUntilAddAccountIsRequested() throws Exception {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        LobbyRunner runner = new LobbyRunner();
        UI ui = new TestUI();
        CountDownLatch finished = new CountDownLatch(1);
        UI.Runner[] next = new UI.Runner[1];

        Thread thread = new Thread(() -> {
            try {
                next[0] = runner.run(ui);
                finished.countDown();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();

        assertFalse(finished.await(200, TimeUnit.MILLISECONDS), "LobbyRunner should block before requestAddAccount()");
        manager.requestAddAccount();
        assertTrue(finished.await(2, TimeUnit.SECONDS), "LobbyRunner should resume after requestAddAccount()");
        assertTrue(next[0] instanceof Bootstrap, "LobbyRunner should hand the uiloop back to Bootstrap");

        thread.join(2000);
    }
}
```

- [ ] **Step 2: Run the suite and confirm it fails for the missing runner classes**

Run: `ant test -buildfile build.xml`

Expected: `BUILD FAILED` during `compile-test` because `SessionRunner` and `LobbyRunner` do not exist yet.

- [ ] **Step 3: Create `LobbyRunner` and `SessionRunner`**

Create these files exactly:

```java
// src/haven/session/LobbyRunner.java
package haven.session;

import haven.Bootstrap;
import haven.UI;

public class LobbyRunner implements UI.Runner {
    @Override
    public UI.Runner run(UI ui) throws InterruptedException {
        SessionManager.getInstance().waitForAddRequest();
        return(new Bootstrap());
    }

    @Override
    public String title() {
        return(null);
    }
}
```

```java
// src/haven/session/SessionRunner.java
package haven.session;

import haven.RemoteUI;
import haven.UI;

public class SessionRunner implements UI.Runner {
    private final RemoteUI remoteUI;

    public SessionRunner(RemoteUI remoteUI) {
        this.remoteUI = remoteUI;
    }

    @Override
    public void init(UI ui) {
        remoteUI.init(ui);
    }

    @Override
    public UI.Runner run(UI ui) {
        remoteUI.attach(ui);
        SessionManager.getInstance().addSession(new SessionContext(remoteUI.sess, ui, remoteUI));
        return(new LobbyRunner());
    }

    @Override
    public String title() {
        return(remoteUI.title());
    }
}
```

- [ ] **Step 4: Wire `Bootstrap` and `MainFrame` into the new runner flow**

Apply these changes:

```java
// src/haven/Bootstrap.java
ui.destroy(1);
haven.error.ErrorHandler.setprop("usr", sess.user.name);
return(new haven.session.SessionRunner(new RemoteUI(sess)));
```

```java
// src/haven/MainFrame.java
if(Bootstrap.replay.get() != null) {
    try {
        Transport.Playback player = new Transport.Playback(Files.newBufferedReader(Bootstrap.replay.get(), Utils.utf8));
        fun = new RemoteUI(new Session(player, new Session.User("Playback")));
        player.start();
    } catch(IOException e) {
        System.err.println("hafen: " + e.getMessage());
        System.exit(1);
    }
} else if(Bootstrap.servargs.get() != null) {
    try {
        fun = new haven.session.SessionRunner(new RemoteUI(connect(Bootstrap.servargs.get())));
    } catch(ConnectionError e) {
        System.err.println("hafen: " + e.getMessage());
        System.exit(1);
    }
}
```

- [ ] **Step 5: Re-run the suite and confirm the runner handoff is green**

Run: `ant test -buildfile build.xml`

Expected: `BUILD SUCCESSFUL`, including `SessionRunnerTest`.

- [ ] **Step 6: Commit the runner handoff layer**

Run:

```bash
git add src/haven/session/LobbyRunner.java src/haven/session/SessionRunner.java src/haven/Bootstrap.java src/haven/MainFrame.java test/unit/haven/SessionRunnerTest.java
git commit -m "feat: hand logged-in sessions to the session manager"
```

---

### Task 5: Make `GLPanel.Loop` Session-Aware Without Destroying Session UIs

**Files:**
- Modify: `src/haven/GLPanel.java:69-76`
- Modify: `src/haven/GLPanel.java:342-509`
- Modify: `test/unit/haven/GLPanelLoopTest.java`

- [ ] **Step 1: Extend `GLPanelLoopTest` with failing lifecycle-preservation tests**

Append these helpers/tests to `test/unit/haven/GLPanelLoopTest.java`:

```java
private static final class DummyTransport implements Transport {
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
        return this;
    }
}

private static final class DummyRunner implements UI.Runner {
    @Override
    public UI.Runner run(UI ui) {
        return null;
    }
}

// In TestLoop, add this helper.
private void setCurrentUi(UI ui) {
    this.ui = ui;
}

@Test
@Tag("unit")
void newuiDoesNotDestroyRegisteredSessionUiWhenOpeningLoginScreen() {
    haven.session.SessionManager manager = haven.session.SessionManager.getInstance();
    for(haven.session.SessionContext ctx : manager.getSessions()) {
        manager.removeSession(ctx);
    }

    TestLoop loop = new TestLoop(new DummyPanel());
    TrackingUI sessionUi = new TrackingUI(loop.panel);
    Session session = new Session(new DummyTransport(), new Session.User("tester"));
    session.ui = sessionUi;
    sessionUi.sess = session;
    manager.addSession(new haven.session.SessionContext(session, sessionUi, new RemoteUI(session)));
    loop.setCurrentUi(sessionUi);

    loop.newui(new DummyRunner());

    assertEquals(0, sessionUi.destroyCalls, "opening a login UI must not destroy a registered session UI");

    for(haven.session.SessionContext ctx : manager.getSessions()) {
        manager.removeSession(ctx);
    }
}

@Test
@Tag("unit")
void lobbyRunnerReusesActiveSessionUiInsteadOfCreatingThrowawayUi() {
    haven.session.SessionManager manager = haven.session.SessionManager.getInstance();
    for(haven.session.SessionContext ctx : manager.getSessions()) {
        manager.removeSession(ctx);
    }

    TestLoop loop = new TestLoop(new DummyPanel());
    TrackingUI loginUi = (TrackingUI) loop.newui(null);
    TrackingUI sessionUi = new TrackingUI(loop.panel);
    Session session = new Session(new DummyTransport(), new Session.User("tester"));
    session.ui = sessionUi;
    sessionUi.sess = session;
    manager.addSession(new haven.session.SessionContext(session, sessionUi, new RemoteUI(session)));

    UI reused = loop.newui(new haven.session.LobbyRunner());

    assertSame(sessionUi, reused, "LobbyRunner must reuse the active session UI");
    assertEquals(1, loginUi.destroyCalls, "the temporary login UI should be cleaned up when the lobby rebinds the visible UI");

    for(haven.session.SessionContext ctx : manager.getSessions()) {
        manager.removeSession(ctx);
    }
}
```

- [ ] **Step 2: Run the suite and confirm it fails for the missing session-aware lifecycle hooks**

Run: `ant test -buildfile build.xml`

Expected: `BUILD FAILED` or failing `GLPanelLoopTest` assertions because `GLPanel.Loop.newui()` still destroys the previous UI unconditionally and `LobbyRunner` still creates a throwaway `PUI`.

- [ ] **Step 3: Teach `GLPanel.Loop.newui()` and teardown to preserve registered session UIs**

Apply these changes in `src/haven/GLPanel.java`:

```java
private void onLoopTeardown() {
    haven.session.SessionManager sm = haven.session.SessionManager.getInstance();
    for(haven.session.SessionContext ctx : sm.getSessions()) {
        synchronized(ctx.ui) {
            ctx.ui.destroy();
        }
    }
    UI lastui = this.ui;
    this.ui = null;
    if((lastui != null) && !sm.isSessionUi(lastui)) {
        synchronized(lastui) {
            lastui.destroy();
        }
    }
}

private boolean isSessionUi(UI ui) {
    return haven.session.SessionManager.getInstance().isSessionUi(ui);
}
```

```java
public UI newui(UI.Runner fun) {
    haven.session.SessionManager sm = haven.session.SessionManager.getInstance();
    UI prevui;
    if(fun instanceof haven.session.LobbyRunner) {
        haven.session.SessionContext active = sm.getActiveSession();
        if(active != null) {
            active.ui.env = p.env();
            synchronized(uilock) {
                prevui = this.ui;
                ui = active.ui;
                ui.root.guprof = uprof;
                ui.root.grprof = rprof;
                ui.root.ggprof = gprof;
                while((this.lockedui != null) && (this.lockedui == prevui)) {
                    try {
                        uilock.wait();
                    } catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if((prevui != null) && (prevui != active.ui) && !isSessionUi(prevui)) {
                synchronized(prevui) {
                    prevui.destroy();
                }
            }
            return(active.ui);
        }
    }

    UI newui = makeui(fun);
    newui.env = p.env();
    if(p.getParent() instanceof Console.Directory)
        newui.cons.add((Console.Directory)p.getParent());
    if(p instanceof Console.Directory)
        newui.cons.add((Console.Directory)p);
    newui.cons.add(this);
    synchronized(uilock) {
        prevui = this.ui;
        ui = newui;
        ui.root.guprof = uprof;
        ui.root.grprof = rprof;
        ui.root.ggprof = gprof;
        while((this.lockedui != null) && (this.lockedui == prevui)) {
            try {
                uilock.wait();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    if((prevui != null) && !isSessionUi(prevui)) {
        synchronized(prevui) {
            prevui.destroy();
        }
    }
    return(newui);
}
```

- [ ] **Step 4: Update the render loop to tick all managed sessions and only render the visible UI**

Replace the single-session assumptions in `GLPanel.Loop.run()` with this structure:

```java
haven.session.SessionManager sm = haven.session.SessionManager.getInstance();
while(true) {
    double fwaited = 0;
    GLEnvironment env = p.env();
    buf = env.render();
    UI ui;
    synchronized(uilock) {
        haven.session.SessionContext active = sm.getActiveSession();
        if((active != null) && sm.isSessionUi(this.ui) && (this.ui != active.ui)) {
            this.ui = active.ui;
        }
        this.lockedui = ui = this.ui;
        uilock.notifyAll();
    }

    for(haven.session.SessionContext ctx : sm.getSessions()) {
        PMessage msg;
        while((msg = ctx.session.pollUIMsg()) != null) {
            if(msg instanceof RemoteUI.Return) {
                ctx.close();
                break;
            }
            ctx.remoteUI.dispatchMessage(msg, ctx.ui);
        }
        synchronized(ctx.ui) {
            ctx.ui.tick();
            if(!ctx.session.isClosed()) {
                ctx.session.glob.ctick();
                ctx.session.glob.map.sendreqs();
            }
        }
    }
    if((frameno % 300) == 0) {
        sm.pruneDeadSessions();
    }

    boolean managedVisibleUi = sm.isSessionUi(ui);
    synchronized(ui) {
        ed.dispatch(ui);
        ui.mousehover(ui.mc);

        if(!managedVisibleUi && (ui.sess != null)) {
            ui.sess.glob.ctick();
        }
        if(ui.sess != null) {
            ui.sess.glob.gtick(buf);
        }
        if(!managedVisibleUi) {
            ui.tick();
        }
        ui.gtick(buf);
        Area shape = p.shape();
        if((ui.root.sz.x != (shape.br.x - shape.ul.x)) || (ui.root.sz.y != (shape.br.y - shape.ul.y)))
            ui.root.resize(new Coord(shape.br.x - shape.ul.x, shape.br.y - shape.ul.y));
        buf.submit(new ProfilePart(rprofc, "draw"));
        if(curgf != null) curgf.part(buf, "draw");
    }
```

Implementation notes for the executor:

- The visible UI stays the login UI while the player is adding another account, because `sm.isSessionUi(this.ui)` is false in that phase.
- When the visible UI is a managed session UI, the active-session pointer is allowed to replace `this.ui` on the next frame.
- `RemoteUI.Return` is intentionally treated as a session shutdown in this MVP. Do not silently ignore it.

- [ ] **Step 5: Re-run the suite and confirm the GL lifecycle tests pass**

Run: `ant test -buildfile build.xml`

Expected: `BUILD SUCCESSFUL`, including the new `GLPanelLoopTest` coverage.

- [ ] **Step 6: Commit the session-aware GL loop changes**

Run:

```bash
git add src/haven/GLPanel.java test/unit/haven/GLPanelLoopTest.java
git commit -m "feat: keep multiple session UIs alive in the GL loop"
```

---

### Task 6: Add User Controls and Run Full Verification

**Files:**
- Modify: `src/haven/GameUI.java:1793-1822`

- [ ] **Step 1: Add the session-switch and add-account keybindings to `GameUI.globtype()`**

Apply these changes in `src/haven/GameUI.java`:

```java
public static final KeyBinding kb_shoot = KeyBinding.get("screenshot", KeyMatch.forchar('S', KeyMatch.C));
public static final KeyBinding kb_chat = KeyBinding.get("chat-toggle", KeyMatch.forchar('C', KeyMatch.C));
public static final KeyBinding kb_hide = KeyBinding.get("ui-toggle", KeyMatch.nil);
public static final KeyBinding kb_logout = KeyBinding.get("logout", KeyMatch.nil);
public static final KeyBinding kb_switchchr = KeyBinding.get("logout-cs", KeyMatch.nil);
public static final KeyBinding kb_nextsession = KeyBinding.get("session-next", KeyMatch.forcode(KeyEvent.VK_TAB, KeyMatch.M));
public static final KeyBinding kb_addsession = KeyBinding.get("session-add", KeyMatch.forchar('N', KeyMatch.M));
public boolean globtype(GlobKeyEvent ev) {
    if(ev.c == ':') {
        entercmd();
        return(true);
    } else if(kb_shoot.key().match(ev) && (Screenshooter.screenurl.get() != null)) {
        Screenshooter.take(this, Screenshooter.screenurl.get());
        return(true);
    } else if(kb_hide.key().match(ev)) {
        toggleui();
        return(true);
    } else if(kb_logout.key().match(ev)) {
        act("lo");
        return(true);
    } else if(kb_switchchr.key().match(ev)) {
        act("lo", "cs");
        return(true);
    } else if(kb_chat.key().match(ev)) {
        toggleChat();
        return(true);
    } else if(kb_nextsession.key().match(ev)) {
        haven.session.SessionManager.getInstance().switchToNext();
        return(true);
    } else if(kb_addsession.key().match(ev)) {
        haven.session.SessionManager.getInstance().requestAddAccount();
        return(true);
    } else if((ev.c == 27) && (map != null) && !map.hasfocus) {
        setfocus(map);
        return(true);
    }
    return(super.globtype(ev));
}
```

- [ ] **Step 2: Run the full unit-test suite**

Run: `ant test -buildfile build.xml`

Expected: `BUILD SUCCESSFUL` with all existing and new unit tests passing.

- [ ] **Step 3: Run the distributable build**

Run: `ant clean-code bin -buildfile build.xml`

Expected: `BUILD SUCCESSFUL` and updated artifacts under `bin/`.

- [ ] **Step 4: Perform the manual smoke test with two live accounts**

Run: `ant run -buildfile build.xml`

Manual verification checklist:

```text
1. Log in with account A.
2. Press Alt+N to open the login UI without disconnecting account A.
3. Log in with account B.
4. Press Alt+Tab and verify the rendered world swaps between account A and account B.
5. Leave one character walking or running a bot on account A, switch to account B, and verify account A keeps updating.
6. Close one session and verify the other session still creates PGob instances normally.
7. Repeat Alt+N from a live session and verify the login UI appears while the existing session stays alive underneath.
8. Try logout / character switch once and record whether `RemoteUI.Return` needs follow-up work.
```

- [ ] **Step 5: Commit the controls and verified end-to-end build**

Run:

```bash
git add src/haven/GameUI.java
git commit -m "feat: add multi-session switching controls"
```

---

## Self-Review

### Spec coverage

- Session-safe gob creation is covered by Task 1.
- Non-blocking message polling and extracted dispatch are covered by Task 2.
- Session registry and add-account signaling are covered by Task 3.
- Bootstrap/direct-connect handoff to registered sessions is covered by Task 4.
- GL loop lifecycle preservation, background ticking, and visible-UI rules are covered by Task 5.
- User controls, full test/build verification, and live smoke verification are covered by Task 6.

### Placeholder scan

- No `TODO`, `TBD`, or deferred "fill this in later" instructions remain in the tasks.
- Every code-writing step includes concrete Java code or exact command text.

### Type consistency

- The plan consistently uses `SessionContext`, `SessionManager`, `SessionRunner`, and `LobbyRunner` across all tasks.
- The `RemoteUI` helper exposed for handoff is consistently named `attach(UI ui)`.
- The session-scoped gob factory is consistently named `glob.gobFactory`.
