# Client Tabs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace logged-in-session switching with first-class client tabs where login screens and game sessions are both switchable tabs.

**Architecture:** Paisti-owned tab/session code lives under `src/paisti`, with `PaistiClientTabManager` as the single source of truth for tab order, active tab, lifecycle, and logged-in session ownership. Existing Haven classes under `src/haven` should only receive minimal subclass-enabling hooks or callbacks when subclassing cannot reach the needed lifecycle point; client behavior belongs in Paisti subclasses/adapters.

**Tech Stack:** Java 8-style source, AWT/Swing, JUnit 5 unit tests, Ant build commands from `AGENTS.md`.

---

## File Structure

- Create `src/paisti/client/tabs/PaistiClientTab.java`: immutable tab identity plus mutable tab state (`LOGIN` or `SESSION`), current `UI`, optional `PaistiSessionContext`, close/selectability helpers, and label calculation.
- Create `src/paisti/client/tabs/PaistiClientTabManager.java`: singleton manager for all tabs, active tab, login-request semaphore, session pruning, lifecycle transitions, and listener notification for the tab bar.
- Create `src/paisti/client/tabs/PaistiSessionContext.java`: moved replacement for the existing custom `haven.session.SessionContext`; owns `Session`, `UI`, `RemoteUI`, retiring/disposed flags, and deferred disposal.
- Create `src/paisti/client/tabs/PaistiSessionRunner.java`: moved replacement for the existing custom `haven.session.SessionRunner`; attaches `RemoteUI`, creates `PaistiSessionContext`, and converts the active login tab into a session tab.
- Create `src/paisti/client/tabs/PaistiLobbyRunner.java`: moved replacement for the existing custom `haven.session.LobbyRunner`; waits on `PaistiClientTabManager.waitForLoginRequest()` and returns a Paisti login runner.
- Create `src/paisti/client/tabs/PaistiClientTabBar.java`: custom-painted AWT component with hit-tested add, close, and tab regions. No native `Button`s and no periodic remove/recreate refresh loop.
- Create `src/paisti/client/PBootstrap.java`: Paisti-owned login/bootstrap runner. Prefer subclassing `Bootstrap`; if private vanilla helpers make a full override impractical, keep the copied login flow here rather than adding more Paisti behavior to `src/haven/Bootstrap.java`.
- Create `src/paisti/client/PGLPanelLoop.java`: Paisti-owned `GLPanel.Loop` subclass overriding protected lifecycle hooks for tab-aware active-UI sync, background servicing, visible `RemoteUI.Return` handling, and pruning.
- Create `src/paisti/client/PJOGLPanel.java` and `src/paisti/client/PLWJGLPanel.java`: renderer subclasses that install `PGLPanelLoop` through a minimal `src/haven/JOGLPanel.java` / `src/haven/LWJGLPanel.java` factory hook.
- Modify `src/paisti/client/PMainFrame.java`: use Paisti renderer subclasses, Paisti default login runner, and `PaistiClientTabBar(PaistiClientTabManager.getInstance(), renderer)`.
- Modify `src/haven/MainFrame.java`: only add protected factory hooks needed by `PMainFrame`, such as `protected UIPanel renderer()` and `protected UI.Runner defaultRunner()`. Do not import Paisti tab/session classes here.
- Modify `src/haven/GLPanel.java`: only add protected extension hooks inside `GLPanel.Loop` that `PGLPanelLoop` can override. Do not import Paisti tab/session classes here.
- Modify `src/haven/JOGLPanel.java` and `src/haven/LWJGLPanel.java`: only add `protected GLPanel.Loop createLoop()` and initialize the private loop field from that hook. Do not add Paisti tab/session logic here.
- Modify `src/haven/GameUI.java` only if no Paisti subclass/hook already owns global hotkeys. The preferred target is a Paisti subclass or existing Paisti keybinding extension that calls `PaistiClientTabManager`.
- Delete custom session/tab code from `src/haven/session/**`: `SessionContext`, `SessionManager`, `SessionRunner`, `LobbyRunner`, and `SessionBar` should be removed or moved to `src/paisti/client/tabs/**`.
- Rewrite tests under `test/unit/paisti/client/tabs/**` for Paisti tab manager/bar behavior. Keep `test/unit/haven/**` only for minimal Haven hook coverage.

## Important Design Notes

- Custom code belongs under `src/paisti`. The existing multi-session spike currently under `src/haven/session` is technical debt from the prototype and must be moved, not expanded.
- `src/haven` edits are acceptable only as minimal hooks on existing client classes when a Paisti subclass cannot otherwise attach behavior. Examples: a renderer loop factory hook, a default-runner factory hook, or protected `GLPanel.Loop` lifecycle hooks. The implementation must not add `PaistiClientTabManager`, tab lifecycle, or Paisti UI behavior directly to Haven classes.
- Any code snippets below that still use older names such as `ClientTabManager`, `ClientTab`, `ClientTabBar`, `SessionRunner`, `LobbyRunner`, or `SessionContext` under `haven.session` must be implemented as the Paisti-owned equivalents: `PaistiClientTabManager`, `PaistiClientTab`, `PaistiClientTabBar`, `PaistiSessionRunner`, `PaistiLobbyRunner`, and `PaistiSessionContext` under `package paisti.client.tabs`.
- Test commands that name `haven.session.ClientTabManagerTest` should be implemented as `paisti.client.tabs.PaistiClientTabManagerTest` after the package move.
- Preserve the current single `UI.Runner` thread. Do not start a second concurrent `Bootstrap.run()` thread for login tabs in this implementation.
- `+` should call `PaistiClientTabManager.requestNewLoginTab()`. `PaistiLobbyRunner` wakes, returns `PBootstrap`, and the Paisti loop creates the login tab.
- Successful login should keep the same tab: the Paisti loop replaces the active login tab's UI with the session UI, and `PaistiSessionRunner.run()` converts that same tab into a session tab.
- Direct-connect startup may have no active login tab. In that case `PaistiSessionRunner.run()` creates a new session tab.
- Closing a session tab calls `PaistiSessionContext.close()` only; final `UI.destroy()` remains deferred to `PaistiClientTabManager.pruneDeadSessions()`.
- Closing a login tab must cancel the owning `PBootstrap` before destroying the login `UI`; otherwise the single runner thread can block forever in the login message wait and no later `+` request can create a new login tab.
- If all tabs are closed or pruned, create a login tab by requesting the runner to show `PBootstrap`; while the runner is waking, `PGLPanelLoop` may safely skip frames with `ui == null`.
- Do not commit during implementation unless the user explicitly asks. The plan includes verification checkpoints instead of commit steps.

---

### Task 0: Restore Paisti Boundary For Existing Multi-Session Spike

**Files:**
- Create: `src/paisti/client/tabs/PaistiSessionContext.java`
- Create: `src/paisti/client/tabs/PaistiSessionRunner.java`
- Create: `src/paisti/client/tabs/PaistiLobbyRunner.java`
- Create: `src/paisti/client/PBootstrap.java`
- Create: `src/paisti/client/PGLPanelLoop.java`
- Create: `src/paisti/client/PJOGLPanel.java`
- Create: `src/paisti/client/PLWJGLPanel.java`
- Modify: `src/paisti/client/PMainFrame.java`
- Modify: `src/haven/MainFrame.java` only for protected factory hooks
- Modify: `src/haven/GLPanel.java` only for protected loop lifecycle hooks
- Modify: `src/haven/JOGLPanel.java` only for a protected loop factory hook
- Modify: `src/haven/LWJGLPanel.java` only for a protected loop factory hook
- Delete after migration: `src/haven/session/SessionContext.java`
- Delete after migration: `src/haven/session/SessionManager.java`
- Delete after migration: `src/haven/session/SessionRunner.java`
- Delete after migration: `src/haven/session/LobbyRunner.java`
- Delete after migration: `src/haven/session/SessionBar.java`

- [ ] **Step 1: Add minimal Haven factory hooks**

In `src/haven/MainFrame.java`, change `private UIPanel renderer()` to `protected UIPanel renderer()`. Add a protected factory for the default runner and use it in `uiloop()`:

```java
protected UI.Runner defaultRunner() {
    return new Bootstrap();
}

private void uiloop() throws InterruptedException {
    UI.Runner fun = null;
    while(true) {
        if(fun == null)
            fun = defaultRunner();
        String t = fun.title();
        if(t == null)
            setTitle(TITLE);
        else
            setTitle(TITLE + " \u2013 " + t);
        fun = fun.run(p.newui(fun));
    }
}
```

In `src/haven/JOGLPanel.java`, change the loop field to use an overridable factory:

```java
private final Loop main = createLoop();

protected Loop createLoop() {
    return new Loop(this);
}
```

In `src/haven/LWJGLPanel.java`, make the same change:

```java
private final Loop main = createLoop();

protected Loop createLoop() {
    return new Loop(this);
}
```

In `src/haven/GLPanel.java`, add protected hook methods to `GLPanel.Loop` and call them from the existing loop instead of hard-coding session-manager behavior. The hook methods should default to vanilla/no-op behavior so the base client remains usable without Paisti subclasses:

```java
protected UI syncActiveUi(UI current) {
    return current;
}

protected boolean isManagedUi(UI ui) {
    return false;
}

protected boolean isManagedSessionUi(UI ui) {
    return false;
}

protected void pruneManagedSessions() {
}

protected UI handlePrunedManagedUi(UI visibleUi) {
    return visibleUi;
}

protected void tickBackgroundManagedSessions(UI visibleUi) {
}

protected boolean serviceVisibleManagedSession(UI visibleUi) {
    return true;
}

protected boolean isActiveManagedUi(UI ui) {
    return true;
}

protected void registerNewUi(UI.Runner runner, UI previousUi, UI newUi) {
}
```

Then move the Paisti-specific work that currently references `SessionManager` out of `GLPanel.Loop` and into `PGLPanelLoop` overrides. `GLPanel.Loop` may call these hooks, but it must not import or reference `paisti.*`.

- [ ] **Step 2: Add Paisti renderer subclasses**

Create `src/paisti/client/PJOGLPanel.java`:

```java
package paisti.client;

import haven.GLPanel;
import haven.JOGLPanel;

public class PJOGLPanel extends JOGLPanel {
    @Override
    protected GLPanel.Loop createLoop() {
        return new PGLPanelLoop(this);
    }
}
```

Create `src/paisti/client/PLWJGLPanel.java`:

```java
package paisti.client;

import haven.GLPanel;
import haven.LWJGLPanel;

public class PLWJGLPanel extends LWJGLPanel {
    @Override
    protected GLPanel.Loop createLoop() {
        return new PGLPanelLoop(this);
    }
}
```

- [ ] **Step 3: Add Paisti loop subclass shell**

Create `src/paisti/client/PGLPanelLoop.java`:

```java
package paisti.client;

import haven.GLPanel;

public class PGLPanelLoop extends GLPanel.Loop {
    public PGLPanelLoop(GLPanel panel) {
        super(panel);
    }
}
```

Later tasks move tab-aware loop behavior here instead of adding more behavior to `src/haven/GLPanel.java`.

- [ ] **Step 4: Override Paisti renderer/default runner in PMainFrame**

Update `src/paisti/client/PMainFrame.java` to own renderer and default login runner choices:

```java
package paisti.client;

import haven.Coord;
import haven.MainFrame;
import haven.UI;
import haven.UIPanel;
import paisti.client.tabs.PaistiClientTabBar;
import paisti.client.tabs.PaistiClientTabManager;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Panel;

public class PMainFrame extends MainFrame {
    public PMainFrame(Coord isz) {
        super(isz);
    }

    @Override
    protected UIPanel renderer() {
        String id = haven.MainFrame.renderer.get();
        switch(id) {
        case "jogl":
            return new PJOGLPanel();
        case "lwjgl":
            return new PLWJGLPanel();
        default:
            throw new RuntimeException("invalid renderer specified in haven.renderer: " + id);
        }
    }

    @Override
    protected UI.Runner defaultRunner() {
        return new PBootstrap();
    }

    @Override
    protected Component wrapRenderer(Component renderer) {
        Panel panel = new Panel(new BorderLayout());
        panel.add(new PaistiClientTabBar(PaistiClientTabManager.getInstance(), renderer), BorderLayout.NORTH);
        panel.add(renderer, BorderLayout.CENTER);
        return panel;
    }
}
```

- [ ] **Step 5: Move existing custom session classes to Paisti package**

Move the current custom class bodies from `src/haven/session/SessionContext.java`, `SessionRunner.java`, and `LobbyRunner.java` to Paisti-owned names under `src/paisti/client/tabs/`:

```java
package paisti.client.tabs;
```

Rename classes as follows:

```text
SessionContext -> PaistiSessionContext
SessionRunner -> PaistiSessionRunner
LobbyRunner -> PaistiLobbyRunner
```

Update imports in Paisti-owned files to use these names. Do not leave wrappers in `src/haven/session`.

- [ ] **Step 6: Verify no custom session package remains under Haven**

Search for `package haven.session`.

Expected: no production files under `src/haven/session` remain after the move. If tests still use old package names, move them to `test/unit/paisti/client/tabs`.

- [ ] **Step 7: Run compile checkpoint**

Run: `ant clean-code test -buildfile build.xml`

Expected: compilation either passes or fails only on expected references that later tasks explicitly migrate to `paisti.client.tabs` names.

---

### Task 1: Add ClientTab Model

**Files:**
- Create: `src/paisti/client/tabs/PaistiClientTab.java`
- Test: `test/unit/paisti/client/tabs/PaistiClientTabManagerTest.java`

- [ ] **Step 1: Write failing model tests**

Create `test/unit/paisti/client/tabs/PaistiClientTabManagerTest.java` by copying the fixture helpers from the existing `SessionManagerTest` and replacing the first tests with these model-focused tests:

```java
package paisti.client.tabs;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTabManagerTest {
    private static final class DummyTransport implements Transport {
        private final List<Callback> callbacks = new ArrayList<>();
        private int closeCalls;

        @Override public void close() { closeCalls++; }
        @Override public void queuemsg(PMessage pmsg) { }
        @Override public void send(PMessage msg) { }
        @Override public Transport add(Callback cb) { callbacks.add(cb); return this; }

        private void fireClosed() {
            for(Callback callback : callbacks)
                callback.closed();
        }
    }

    private static final class DummyPanel extends Canvas implements GLPanel {
        @Override public GLEnvironment env() { return null; }
        @Override public Area shape() { return Area.sized(Coord.z, Coord.of(10, 10)); }
        @Override public Pipe basestate() { return null; }
        @Override public void glswap(haven.render.gl.GL gl) { }
        @Override public void setmousepos(Coord c) { }
        @Override public UI newui(UI.Runner fun) { throw new UnsupportedOperationException(); }
        @Override public void background(boolean bg) { }
        @Override public void run() { }
    }

    private static final class TestRootWidget extends RootWidget {
        private TestRootWidget(UI ui, Coord sz) { super(ui, sz); }
        @Override protected GobEffects createEffects(UI ui) { return null; }
        @Override public void tick(double dt) { }
        @Override public void draw(GOut g) { }
    }

    private static final class TestUI extends UI {
        private int destroyCalls;
        private TestUI() { super(new DummyPanel(), Coord.of(10, 10), null); }
        @Override protected RootWidget createRoot(Coord sz) { return new TestRootWidget(this, sz); }
        @Override public void destroy() { destroyCalls++; }
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

    @BeforeEach
    void clearManagerBefore() {
        ClientTabManager.getInstance().clearForTests();
    }

    @AfterEach
    void clearManagerAfter() {
        ClientTabManager.getInstance().clearForTests();
    }

    @Test
    @Tag("unit")
    void createdLoginTabIsActiveAndSelectable() {
        ClientTabManager manager = ClientTabManager.getInstance();
        TestUI ui = new TestUI();

        ClientTab tab = manager.addLoginTab(ui);

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
        ClientTabManager manager = ClientTabManager.getInstance();
        SessionFixture fixture = newContext("paisti");

        ClientTab tab = manager.addSessionTab(fixture.context);

        assertTrue(tab.isSession());
        assertEquals("paisti", tab.label());
        assertSame(fixture.context, tab.sessionContext());
        assertSame(fixture.ui, tab.ui());
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
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabManagerTest -buildfile build.xml`

Expected: compilation fails because `ClientTab` and `ClientTabManager` do not exist.

- [ ] **Step 3: Implement `ClientTab`**

Create `src/paisti/client/tabs/PaistiClientTab.java`:

```java
package paisti.client.tabs;

import haven.Session;
import haven.UI;

public class ClientTab {
    public enum State { LOGIN, SESSION }

    private final long id;
    private State state;
    private UI ui;
    private SessionContext sessionContext;
    private Runnable cancelLogin;

    ClientTab(long id, UI ui) {
        this(id, ui, null);
    }

    ClientTab(long id, UI ui, Runnable cancelLogin) {
        this.id = id;
        this.state = State.LOGIN;
        this.ui = ui;
        this.cancelLogin = cancelLogin;
    }

    ClientTab(long id, SessionContext sessionContext) {
        this.id = id;
        convertToSession(sessionContext);
    }

    public long id() { return id; }
    public State state() { return state; }
    public UI ui() { return ui; }
    public SessionContext sessionContext() { return sessionContext; }
    public boolean isLogin() { return state == State.LOGIN; }
    public boolean isSession() { return state == State.SESSION; }

    UI replaceUi(UI ui) {
        UI old = this.ui;
        this.ui = ui;
        return old;
    }

    void convertToSession(SessionContext sessionContext) {
        this.state = State.SESSION;
        this.sessionContext = sessionContext;
        this.cancelLogin = null;
        this.ui = sessionContext.ui;
    }

    void cancelLogin() {
        if(cancelLogin != null)
            cancelLogin.run();
    }

    boolean owns(UI ui) {
        return this.ui == ui;
    }

    public boolean isSelectable() {
        return ui != null && (sessionContext == null || sessionContext.isSelectable());
    }

    public String label() {
        if(isLogin())
            return "Login";
        Session session = sessionContext == null ? null : sessionContext.session;
        String name = (session == null || session.user == null) ? null : session.user.name;
        return (name == null || name.trim().isEmpty()) ? "Session" : name;
    }
}
```

- [ ] **Step 4: Implement minimal `ClientTabManager` surface**

Create `src/paisti/client/tabs/PaistiClientTabManager.java`:

```java
package paisti.client.tabs;

import haven.UI;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

public class ClientTabManager {
    private static final ClientTabManager instance = new ClientTabManager();

    public static ClientTabManager getInstance() {
        return instance;
    }

    private final List<ClientTab> tabs = new ArrayList<>();
    private final Semaphore loginRequestSignal = new Semaphore(0);
    private long nextId = 1;
    private ClientTab activeTab;

    private ClientTabManager() { }

    public synchronized ClientTab addLoginTab(UI ui) {
        return addLoginTab(ui, null);
    }

    public synchronized ClientTab addLoginTab(UI ui, Runnable cancelLogin) {
        ClientTab tab = new ClientTab(nextId++, ui, cancelLogin);
        tabs.add(tab);
        activeTab = tab;
        return tab;
    }

    public synchronized ClientTab addSessionTab(SessionContext context) {
        ClientTab tab = new ClientTab(nextId++, context);
        tabs.add(tab);
        activeTab = tab;
        return tab;
    }

    public synchronized List<ClientTab> getTabs() {
        return new ArrayList<>(tabs);
    }

    public synchronized ClientTab getActiveTab() {
        return activeTab;
    }

    public synchronized UI getActiveUi() {
        return activeTab == null ? null : activeTab.ui();
    }

    synchronized void clearForTests() {
        tabs.clear();
        activeTab = null;
        nextId = 1;
        loginRequestSignal.drainPermits();
    }
}
```

- [ ] **Step 5: Run focused tests and verify they pass**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabManagerTest -buildfile build.xml`

Expected: `ClientTabManagerTest` passes.

---

### Task 2: Implement Tab Switching And Closing Semantics

**Files:**
- Modify: `src/paisti/client/tabs/PaistiClientTabManager.java`
- Modify: `test/unit/paisti/client/tabs/PaistiClientTabManagerTest.java`

- [ ] **Step 1: Add failing manager tests**

Append these tests to `ClientTabManagerTest`:

```java
@Test
@Tag("unit")
void loginAndSessionTabsAreBothSelectable() {
    ClientTabManager manager = ClientTabManager.getInstance();
    TestUI loginUi = new TestUI();
    ClientTab login = manager.addLoginTab(loginUi);
    SessionFixture session = newContext("session");
    ClientTab sessionTab = manager.addSessionTab(session.context);

    assertTrue(manager.activateTab(login));
    assertSame(loginUi, manager.getActiveUi());

    assertTrue(manager.activateTab(sessionTab));
    assertSame(session.ui, manager.getActiveUi());
}

@Test
@Tag("unit")
void switchToNextAndPreviousCyclesOnlySelectableTabs() {
    ClientTabManager manager = ClientTabManager.getInstance();
    ClientTab first = manager.addLoginTab(new TestUI());
    SessionFixture retiring = newContext("retiring");
    ClientTab second = manager.addSessionTab(retiring.context);
    ClientTab third = manager.addLoginTab(new TestUI());
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
    ClientTabManager manager = ClientTabManager.getInstance();
    TestUI firstUi = new TestUI();
    TestUI secondUi = new TestUI();
    ClientTab first = manager.addLoginTab(firstUi);
    ClientTab second = manager.addLoginTab(secondUi);

    manager.closeActiveTab();

    assertEquals(1, manager.getTabs().size());
    assertSame(first, manager.getActiveTab());
    assertEquals(1, secondUi.destroyCalls);
    assertEquals(0, firstUi.destroyCalls);
}

@Test
@Tag("unit")
void closingActiveSessionStartsShutdownWithoutImmediateDisposal() {
    ClientTabManager manager = ClientTabManager.getInstance();
    SessionFixture first = newContext("first");
    SessionFixture second = newContext("second");
    ClientTab firstTab = manager.addSessionTab(first.context);
    ClientTab secondTab = manager.addSessionTab(second.context);

    manager.closeActiveTab();

    assertEquals(2, manager.getTabs().size(), "session tab remains registered until terminal pruning");
    assertSame(firstTab, manager.getActiveTab());
    assertEquals(0, second.ui.destroyCalls, "active session UI must not be destroyed synchronously");
    assertEquals(1, second.transport.closeCalls, "closing session tab starts session shutdown exactly once");
}
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabManagerTest -buildfile build.xml`

Expected: compilation fails for missing `activateTab`, `switchToNext`, `switchToPrevious`, and `closeActiveTab`.

- [ ] **Step 3: Implement manager switching and close behavior**

Add these methods to `ClientTabManager`:

```java
public synchronized boolean isManagedUi(UI ui) {
    return findTab(ui) != null;
}

public synchronized ClientTab findTab(UI ui) {
    for(ClientTab tab : tabs) {
        if(tab.owns(ui))
            return tab;
    }
    return null;
}

public synchronized boolean activateTab(ClientTab tab) {
    if(tab == null || !tab.isSelectable())
        return false;
    for(ClientTab current : tabs) {
        if(current == tab) {
            activeTab = tab;
            return true;
        }
    }
    return false;
}

public synchronized boolean isActiveUi(UI ui) {
    return activeTab != null && activeTab.ui() == ui;
}

public synchronized void switchToNext() {
    switchBy(1);
}

public synchronized void switchToPrevious() {
    switchBy(-1);
}

private void switchBy(int delta) {
    if(tabs.size() <= 1)
        return;
    int idx = tabs.indexOf(activeTab);
    if(idx < 0)
        idx = 0;
    int size = tabs.size();
    for(int i = 1; i <= size; i++) {
        ClientTab candidate = tabs.get((idx + (delta * i) + size) % size);
        if(candidate.isSelectable()) {
            activeTab = candidate;
            return;
        }
    }
}

public void closeActiveTab() {
    ClientTab tab;
    synchronized(this) {
        tab = activeTab;
        if(tab == null)
            return;
        selectNeighborBeforeRemoving(tab);
    }
    closeTab(tab);
}

public void closeTab(ClientTab tab) {
    if(tab == null)
        return;
    UI loginUi = null;
    SessionContext sessionContext = null;
    synchronized(this) {
        if(!tabs.contains(tab))
            return;
        if(tab.isLogin()) {
            tabs.remove(tab);
            if(activeTab == tab)
                activeTab = null;
            loginUi = tab.ui();
        } else {
            sessionContext = tab.sessionContext();
        }
    }
    if(loginUi != null) {
        tab.cancelLogin();
        synchronized(loginUi) {
            loginUi.destroy();
        }
    }
    if(sessionContext != null)
        sessionContext.close();
    ensureLoginIfEmpty();
}

private void selectNeighborBeforeRemoving(ClientTab removed) {
    int idx = tabs.indexOf(removed);
    activeTab = null;
    if(idx >= 0) {
        int size = tabs.size();
        for(int i = 1; i < size; i++) {
            ClientTab candidate = tabs.get((idx - i + size) % size);
            if(candidate != removed && candidate.isSelectable()) {
                activeTab = candidate;
                break;
            }
        }
    }
}

private void ensureLoginIfEmpty() {
    synchronized(this) {
        if(!tabs.isEmpty())
            return;
    }
    requestNewLoginTab();
}
```

- [ ] **Step 4: Run focused tests and verify they pass**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabManagerTest -buildfile build.xml`

Expected: all current `ClientTabManagerTest` tests pass.

---

### Task 3: Convert Login Tabs Into Session Tabs

**Files:**
- Modify: `src/paisti/client/tabs/PaistiClientTabManager.java`
- Modify: `src/paisti/client/tabs/PaistiSessionRunner.java`
- Modify: `src/paisti/client/PGLPanelLoop.java`
- Modify: `test/unit/paisti/client/tabs/PaistiClientTabManagerTest.java`

- [ ] **Step 1: Add failing conversion tests**

Append these tests to `ClientTabManagerTest`:

```java
@Test
@Tag("unit")
void preparingSessionUiReplacesActiveLoginUiBeforeSessionContextExists() {
    ClientTabManager manager = ClientTabManager.getInstance();
    TestUI loginUi = new TestUI();
    TestUI sessionUi = new TestUI();
    ClientTab login = manager.addLoginTab(loginUi);

    UI replaced = manager.prepareSessionUi(sessionUi);

    assertSame(loginUi, replaced);
    assertSame(login, manager.getActiveTab());
    assertSame(sessionUi, login.ui());
    assertTrue(login.isLogin(), "tab remains a login tab until PaistiSessionRunner has a PaistiSessionContext");
}

@Test
@Tag("unit")
void convertActiveLoginToSessionKeepsSameTabIdentity() {
    ClientTabManager manager = ClientTabManager.getInstance();
    TestUI loginUi = new TestUI();
    ClientTab login = manager.addLoginTab(loginUi);
    SessionFixture session = newContext("converted");
    manager.prepareSessionUi(session.ui);

    ClientTab converted = manager.convertActiveLoginToSession(session.context);

    assertSame(login, converted);
    assertTrue(converted.isSession());
    assertSame(session.context, converted.sessionContext());
    assertSame(session.ui, converted.ui());
    assertSame(converted, manager.getActiveTab());
}

@Test
@Tag("unit")
void convertWithoutActiveLoginCreatesSessionTabForDirectConnect() {
    ClientTabManager manager = ClientTabManager.getInstance();
    SessionFixture session = newContext("direct");

    ClientTab tab = manager.convertActiveLoginToSession(session.context);

    assertEquals(1, manager.getTabs().size());
    assertTrue(tab.isSession());
    assertSame(session.context, tab.sessionContext());
    assertSame(tab, manager.getActiveTab());
}
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabManagerTest -buildfile build.xml`

Expected: compilation fails for missing `prepareSessionUi` and `convertActiveLoginToSession`.

- [ ] **Step 3: Implement conversion methods**

Add these methods to `ClientTabManager`:

```java
public synchronized UI prepareSessionUi(UI sessionUi) {
    if(activeTab != null && activeTab.isLogin())
        return activeTab.replaceUi(sessionUi);
    return null;
}

public synchronized ClientTab convertActiveLoginToSession(SessionContext context) {
    if(activeTab != null && activeTab.isLogin()) {
        activeTab.convertToSession(context);
        return activeTab;
    }
    return addSessionTab(context);
}
```

- [ ] **Step 4: Update `SessionRunner` to use `ClientTabManager`**

Replace `src/paisti/client/tabs/PaistiSessionRunner.java` with:

```java
package paisti.client.tabs;

import haven.RemoteUI;
import haven.UI;

public class PaistiSessionRunner extends UI.Runner.Proxy {
    private final RemoteUI remote;

    public PaistiSessionRunner(RemoteUI remote) {
        super(remote);
        this.remote = remote;
    }

    @Override
    public UI.Runner run(UI ui) {
        remote.attach(ui);
        PaistiClientTabManager.getInstance().convertActiveLoginToSession(new PaistiSessionContext(remote.sess, ui, remote));
        return new PaistiLobbyRunner();
    }
}
```

- [ ] **Step 5: Update `PGLPanelLoop.newui()` registration points**

In `src/paisti/client/PGLPanelLoop.java`, override `newui()` from `GLPanel.Loop`. Keep the vanilla setup behavior, but replace `SessionManager mgr = SessionManager.getInstance();` with `PaistiClientTabManager mgr = PaistiClientTabManager.getInstance();` and use this flow after `newui` has console/env/profile fields initialized but before destroying `prevui`:

```java
UI replacedUi = null;
if(fun instanceof PBootstrap) {
    mgr.addLoginTab(newui, ((PBootstrap)fun)::cancel);
} else if(fun instanceof PaistiSessionRunner) {
    replacedUi = mgr.prepareSessionUi(newui);
}
```

Change the previous-UI destruction guard to keep only UIs still owned by a tab:

```java
if(prevui != null && !mgr.isManagedUi(prevui)) {
    synchronized(prevui) {
        prevui.destroy();
    }
}
if(replacedUi != null && replacedUi != prevui && !mgr.isManagedUi(replacedUi)) {
    synchronized(replacedUi) {
        replacedUi.destroy();
    }
}
```

Keep the `LobbyRunner` reuse optimization, but base it on the active tab UI:

```java
if(fun instanceof LobbyRunner) {
    ClientTab active = mgr.getActiveTab();
    if(active != null && active.ui() == this.ui)
        return this.ui;
}
```

- [ ] **Step 6: Run focused tests**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabManagerTest -buildfile build.xml`

Expected: `ClientTabManagerTest` passes.

---

### Task 4: Make Login Tab Close Cancel Bootstrap

**Files:**
- Modify: `src/haven/Bootstrap.java`
- Modify: `src/paisti/client/tabs/PaistiClientTab.java`
- Modify: `src/paisti/client/tabs/PaistiClientTabManager.java`
- Modify: `test/unit/paisti/client/tabs/PaistiClientTabManagerTest.java`

- [ ] **Step 1: Add failing cancellation test**

Append this test to `ClientTabManagerTest`:

```java
@Test
@Tag("unit")
void closingLoginTabRunsCancelBeforeDestroyingUi() {
    ClientTabManager manager = ClientTabManager.getInstance();
    TestUI ui = new TestUI();
    java.util.concurrent.atomic.AtomicInteger cancelCalls = new java.util.concurrent.atomic.AtomicInteger();
    manager.addLoginTab(ui, cancelCalls::incrementAndGet);

    manager.closeActiveTab();

    assertEquals(1, cancelCalls.get(), "closing a login tab must unblock Bootstrap.run()");
    assertEquals(1, ui.destroyCalls, "closing a login tab still destroys only that UI");
}
```

- [ ] **Step 2: Run focused test and verify failure**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabManagerTest -buildfile build.xml`

Expected: failure if the login close callback is not wired, or compilation failure if `addLoginTab(UI, Runnable)` is not yet implemented.

- [ ] **Step 3: Implement login close callback support**

If not already added while implementing Task 1 or Task 2, update `ClientTab` with:

```java
private Runnable cancelLogin;

ClientTab(long id, UI ui) {
    this(id, ui, null);
}

ClientTab(long id, UI ui, Runnable cancelLogin) {
    this.id = id;
    this.state = State.LOGIN;
    this.ui = ui;
    this.cancelLogin = cancelLogin;
}

void convertToSession(SessionContext sessionContext) {
    this.state = State.SESSION;
    this.sessionContext = sessionContext;
    this.cancelLogin = null;
    this.ui = sessionContext.ui;
}

void cancelLogin() {
    if(cancelLogin != null)
        cancelLogin.run();
}
```

Update `ClientTabManager.addLoginTab` and login-close handling:

```java
public synchronized ClientTab addLoginTab(UI ui) {
    return addLoginTab(ui, null);
}

public synchronized ClientTab addLoginTab(UI ui, Runnable cancelLogin) {
    ClientTab tab = new ClientTab(nextId++, ui, cancelLogin);
    tabs.add(tab);
    activeTab = tab;
    return tab;
}
```

In `ClientTabManager.closeTab`, run cancellation before destroying login UI:

```java
if(loginUi != null) {
    tab.cancelLogin();
    synchronized(loginUi) {
        loginUi.destroy();
    }
}
```

- [ ] **Step 4: Add `Bootstrap.cancel()` and cancel handling**

In `src/haven/Bootstrap.java`, add this method near `getmsg()`:

```java
public void cancel() {
    synchronized(msgs) {
        msgs.add(new Message(-1, "cancel"));
        msgs.notifyAll();
    }
}
```

In the login message loop inside `run(UI ui)`, immediately after `Message msg = getmsg();`, handle cancellation:

```java
if(msg.name == "cancel") {
    ui.destroy(1);
    return new paisti.client.tabs.PaistiLobbyRunner();
}
```

- [ ] **Step 5: Register Bootstrap cancellation from `GLPanel.newui()`**

When `GLPanel.newui()` creates a login tab, register the cancellation callback:

```java
if(fun instanceof Bootstrap) {
    mgr.addLoginTab(newui, ((Bootstrap)fun)::cancel);
} else if(fun instanceof PaistiSessionRunner) {
    replacedUi = mgr.prepareSessionUi(newui);
}
```

- [ ] **Step 6: Run focused tests**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabManagerTest -buildfile build.xml`

Expected: `ClientTabManagerTest` passes.

---

### Task 5: Move Login Requests From SessionManager To ClientTabManager

**Files:**
- Modify: `src/paisti/client/tabs/PaistiClientTabManager.java`
- Modify: `src/paisti/client/tabs/PaistiLobbyRunner.java`
- Modify: `test/unit/paisti/client/tabs/PaistiClientTabManagerTest.java`

- [ ] **Step 1: Add failing login request tests**

Append these tests to `ClientTabManagerTest`:

```java
@Test
@Tag("unit")
void requestNewLoginTabWaiterHandshake() throws Exception {
    ClientTabManager manager = ClientTabManager.getInstance();
    java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);

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

    assertTrue(released.await(2, java.util.concurrent.TimeUnit.SECONDS));
    waiter.join(2000);
    assertFalse(waiter.isAlive());
}

@Test
@Tag("unit")
void repeatedLoginRequestsDoNotLeaveStalePermits() throws Exception {
    ClientTabManager manager = ClientTabManager.getInstance();
    manager.requestNewLoginTab();
    manager.requestNewLoginTab();
    manager.requestNewLoginTab();

    java.util.concurrent.CountDownLatch firstWake = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch secondWakeAttempt = new java.util.concurrent.CountDownLatch(1);

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

    assertTrue(firstWake.await(2, java.util.concurrent.TimeUnit.SECONDS));
    assertFalse(secondWakeAttempt.await(500, java.util.concurrent.TimeUnit.MILLISECONDS));

    waiter.interrupt();
    waiter.join(2000);
}
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabManagerTest -buildfile build.xml`

Expected: missing login request methods.

- [ ] **Step 3: Implement request methods**

Add to `ClientTabManager`:

```java
public void requestNewLoginTab() {
    loginRequestSignal.release();
}

public void waitForLoginRequest() throws InterruptedException {
    loginRequestSignal.acquire();
    loginRequestSignal.drainPermits();
}
```

- [ ] **Step 4: Update `LobbyRunner`**

Replace `src/paisti/client/tabs/PaistiLobbyRunner.java` with:

```java
package paisti.client.tabs;

import haven.Bootstrap;
import haven.UI;

public class PaistiLobbyRunner implements UI.Runner {
    @Override
    public UI.Runner run(UI ui) throws InterruptedException {
        ClientTabManager.getInstance().waitForLoginRequest();
        return new Bootstrap();
    }
}
```

- [ ] **Step 5: Run focused tests**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabManagerTest -buildfile build.xml`

Expected: `ClientTabManagerTest` passes.

---

### Task 6: Replace SessionManager In Paisti GL Loop

**Files:**
- Modify: `src/paisti/client/PGLPanelLoop.java`
- Modify: `src/paisti/client/tabs/PaistiClientTabManager.java`
- Create/modify: `test/unit/paisti/client/PGLPanelLoopTest.java`

- [ ] **Step 1: Add/replace GL loop tests for active tab sync**

In `PGLPanelLoopTest`, use `PaistiClientTabManager` and update manager cleanup to call `PaistiClientTabManager.getInstance().clearForTests()`.

Replace the old `loopDoesNotOverrideNonSessionUiWithActiveSession` test with this behavior:

```java
@Test
@Tag("unit")
void loopSyncsVisibleUiFromActiveLoginTab() throws Exception {
    DummyPanel panel = new DummyPanel();
    TestLoop loop = new TestLoop(panel);
    ClientTabManager mgr = ClientTabManager.getInstance();

    TrackingUI sessionUi = (TrackingUI) loop.newui(null);
    Session sess = newStubSession();
    sess.ui = sessionUi;
    sessionUi.sess = sess;
    SessionContext sessionCtx = new SessionContext(sess, sessionUi, new RemoteUI(sess));
    ClientTab sessionTab = mgr.addSessionTab(sessionCtx);

    TrackingUI loginUi = new TrackingUI(panel);
    ClientTab loginTab = mgr.addLoginTab(loginUi);

    assertSame(sessionUi, loop.currentUi());
    syncUi(loop, mgr);

    assertSame(loginUi, loop.currentUi(),
        "active login tab must replace the visible session UI during loop sync");
    assertSame(loginTab, mgr.getActiveTab());
}
```

Update the helper to match the new sync rule:

```java
private void syncUi(TestLoop loop, ClientTabManager mgr) throws Exception {
    Field uilockField = GLPanel.Loop.class.getDeclaredField("uilock");
    uilockField.setAccessible(true);
    Object uilock = uilockField.get(loop);
    synchronized(uilock) {
        UI active = mgr.getActiveUi();
        if(active != null && active != loop.ui)
            loop.ui = active;
    }
}
```

- [ ] **Step 2: Run GLPanel focused tests and verify failure**

Run: `ant test -Dtest=paisti.client.PGLPanelLoopTest -buildfile build.xml`

Expected: compilation failures or assertion failures because `GLPanel` still uses `SessionManager` and old sync rules.

- [ ] **Step 3: Add session-tab lookup and pruning methods to manager**

Add to `ClientTabManager`:

```java
public synchronized List<SessionContext> getSessionContexts() {
    List<SessionContext> contexts = new ArrayList<>();
    for(ClientTab tab : tabs) {
        if(tab.isSession() && tab.sessionContext() != null)
            contexts.add(tab.sessionContext());
    }
    return contexts;
}

public synchronized SessionContext findSessionContext(UI ui) {
    ClientTab tab = findTab(ui);
    return (tab == null || !tab.isSession()) ? null : tab.sessionContext();
}

public synchronized boolean isSessionUi(UI ui) {
    ClientTab tab = findTab(ui);
    return tab != null && tab.isSession();
}

public synchronized void retireActive(SessionContext context) {
    if(activeTab == null || activeTab.sessionContext() != context)
        return;
    for(ClientTab tab : tabs) {
        if(tab.sessionContext() != context && tab.isSelectable()) {
            activeTab = tab;
            return;
        }
    }
    activeTab = null;
}

public void pruneDeadSessions() {
    List<SessionContext> removed = new ArrayList<>();
    synchronized(this) {
        for(java.util.Iterator<ClientTab> it = tabs.iterator(); it.hasNext();) {
            ClientTab tab = it.next();
            SessionContext context = tab.sessionContext();
            if(context == null || context.isAlive())
                continue;
            it.remove();
            removed.add(context);
            if(activeTab == tab)
                activeTab = newestSelectableLocked();
        }
    }
    for(SessionContext context : removed)
        context.dispose();
    ensureLoginIfEmpty();
}

private ClientTab newestSelectableLocked() {
    for(int i = tabs.size() - 1; i >= 0; i--) {
        ClientTab candidate = tabs.get(i);
        if(candidate.isSelectable())
            return candidate;
    }
    return null;
}
```

- [ ] **Step 4: Update `PGLPanelLoop` session servicing**

In `tickBackgroundSessions`, change manager and loop source:

```java
ClientTabManager mgr = ClientTabManager.getInstance();
for(SessionContext ctx : mgr.getSessionContexts()) {
```

In `serviceVisibleSession`, replace the manual scan with:

```java
ClientTabManager mgr = ClientTabManager.getInstance();
SessionContext ctx = mgr.findSessionContext(visibleUi);
```

In visible `RemoteUI.Return` handling, replace `mgr.addSession(...)` with conversion of the current tab:

```java
SessionContext replacement = new SessionContext(returned, newUi, newRemote);
mgr.convertActiveLoginToSession(replacement);
```

If the current active tab is still the old session tab, add a manager method `replaceSessionContext(ctx, replacement)` instead of using `convertActiveLoginToSession`. Implement it like this:

```java
public synchronized ClientTab replaceSessionContext(SessionContext oldContext, SessionContext newContext) {
    for(ClientTab tab : tabs) {
        if(tab.sessionContext() == oldContext) {
            tab.convertToSession(newContext);
            activeTab = tab;
            return tab;
        }
    }
    return addSessionTab(newContext);
}
```

Use `mgr.replaceSessionContext(ctx, replacement)` for `RemoteUI.Return`.

- [ ] **Step 5: Update `PGLPanelLoop` active UI sync and active-change guard**

Replace the uilock sync block with:

```java
ClientTabManager mgr = ClientTabManager.getInstance();
UI active = mgr.getActiveUi();
if(active != null && active != this.ui)
    this.ui = active;
this.lockedui = ui = this.ui;
uiWasSession = (ui != null && mgr.isSessionUi(ui));
uilock.notifyAll();
```

Replace `SessionManager.getInstance().pruneDeadSessions();` with `ClientTabManager.getInstance().pruneDeadSessions();`.

Replace `if(uiWasSession && !SessionManager.getInstance().isActiveSessionUi(ui))` with:

```java
if(!ClientTabManager.getInstance().isActiveUi(ui)) {
    env.submit(buf);
    buf = null;
    continue;
}
```

- [ ] **Step 6: Update `handlePrunedVisibleSession`**

Use active-tab UI and login request methods:

```java
UI handlePrunedVisibleSession(UI visibleUi) {
    ClientTabManager mgr = ClientTabManager.getInstance();
    if(mgr.isManagedUi(visibleUi))
        return(visibleUi);
    synchronized(uilock) {
        UI successor = mgr.getActiveUi();
        if(successor != null) {
            this.ui = successor;
        } else {
            this.ui = null;
            mgr.requestNewLoginTab();
        }
        this.lockedui = this.ui;
        return(this.ui);
    }
}
```

- [ ] **Step 7: Run focused GL tests**

Run: `ant test -Dtest=paisti.client.PGLPanelLoopTest -buildfile build.xml`

Expected: updated `PGLPanelLoopTest` passes.

---

### Task 7: Retarget Hotkeys To Client Tabs

**Files:**
- Prefer modify/create: Paisti-owned keybinding extension or subclass that already handles global shortcuts
- Modify: `src/haven/GameUI.java` only if no Paisti hook exists and only to add a minimal callback/hook

- [ ] **Step 1: Replace import**

Change:

```java
import haven.session.SessionManager;
```

to:

```java
import paisti.client.tabs.PaistiClientTabManager;
```

- [ ] **Step 2: Replace hotkey actions**

Search in `GameUI.java` for `SessionManager.getInstance()` and replace the session calls with:

```java
PaistiClientTabManager tabs = PaistiClientTabManager.getInstance();
tabs.requestNewLoginTab();
tabs.closeActiveTab();
tabs.switchToPrevious();
tabs.switchToNext();
```

Keep the existing key combinations unchanged:

```java
Ctrl+Shift+Up
Ctrl+Shift+Down
Ctrl+Shift+Left
Ctrl+Shift+Right
```

- [ ] **Step 3: Compile-check after hotkey change**

Run: `ant clean-code test -buildfile build.xml`

Expected: compilation succeeds or reports only remaining `SessionManager` references from files not yet migrated.

---

### Task 8: Replace SessionBar With Custom-Painted ClientTabBar

**Files:**
- Delete: `src/haven/session/SessionBar.java`
- Create: `src/paisti/client/tabs/PaistiClientTabBar.java`
- Modify: `src/paisti/client/tabs/PaistiClientTabManager.java`
- Modify: `src/paisti/client/PMainFrame.java`
- Rewrite: `test/unit/haven/session/SessionBarTest.java` to `test/unit/paisti/client/tabs/PaistiClientTabBarTest.java`

- [ ] **Step 1: Add manager listener support**

Add to `ClientTabManager`:

```java
public interface Listener {
    void tabsChanged();
}

private final List<Listener> listeners = new ArrayList<>();

public synchronized void addListener(Listener listener) {
    listeners.add(listener);
}

public synchronized void removeListener(Listener listener) {
    listeners.remove(listener);
}

private void fireTabsChanged() {
    List<Listener> copy;
    synchronized(this) {
        copy = new ArrayList<>(listeners);
    }
    for(Listener listener : copy)
        listener.tabsChanged();
}
```

Call `fireTabsChanged()` after `addLoginTab`, `addSessionTab`, `activateTab`, `switchBy`, `closeTab`, `prepareSessionUi`, `convertActiveLoginToSession`, `replaceSessionContext`, and `pruneDeadSessions` when those methods actually change model state.

- [ ] **Step 2: Write tab bar model tests**

Create `test/unit/paisti/client/tabs/PaistiClientTabBarTest.java`:

```java
package paisti.client.tabs;

import haven.Coord;
import haven.GLPanel;
import haven.RootWidget;
import haven.UI;
import haven.Area;
import haven.GOut;
import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTabBarTest {
    private static final class DummyPanel extends Canvas implements GLPanel {
        @Override public GLEnvironment env() { return null; }
        @Override public Area shape() { return Area.sized(Coord.z, Coord.of(10, 10)); }
        @Override public Pipe basestate() { return null; }
        @Override public void glswap(haven.render.gl.GL gl) { }
        @Override public void setmousepos(Coord c) { }
        @Override public UI newui(UI.Runner fun) { throw new UnsupportedOperationException(); }
        @Override public void background(boolean bg) { }
        @Override public void run() { }
    }

    private static final class TestRootWidget extends RootWidget {
        private TestRootWidget(UI ui, Coord sz) { super(ui, sz); }
        @Override protected GobEffects createEffects(UI ui) { return null; }
        @Override public void tick(double dt) { }
        @Override public void draw(GOut g) { }
    }

    private static final class TestUI extends UI {
        private TestUI() { super(new DummyPanel(), Coord.of(10, 10), null); }
        @Override protected RootWidget createRoot(Coord sz) { return new TestRootWidget(this, sz); }
    }

    @BeforeEach
    void clearBefore() { ClientTabManager.getInstance().clearForTests(); }

    @AfterEach
    void clearAfter() { ClientTabManager.getInstance().clearForTests(); }

    @Test
    @Tag("unit")
    void preferredHeightIsReadable() {
        ClientTabBar bar = new ClientTabBar(ClientTabManager.getInstance(), new Canvas());
        Dimension size = bar.getPreferredSize();
        assertTrue(size.height >= 30, "tab bar should be taller than tiny native buttons");
    }

    @Test
    @Tag("unit")
    void snapshotContainsAddCloseAndTabRegionsWithoutNativeButtons() {
        ClientTabManager manager = ClientTabManager.getInstance();
        manager.addLoginTab(new TestUI());
        ClientTabBar bar = new ClientTabBar(manager, new Canvas());

        java.util.List<ClientTabBar.HitRegion> regions = bar.layoutRegionsForTests(320, 34);

        assertTrue(regions.stream().anyMatch(r -> r.kind == ClientTabBar.HitKind.ADD));
        assertTrue(regions.stream().anyMatch(r -> r.kind == ClientTabBar.HitKind.CLOSE));
        assertTrue(regions.stream().anyMatch(r -> r.kind == ClientTabBar.HitKind.TAB));
        for(Component child : bar.getComponents())
            assertFalse(child instanceof java.awt.Button, "tab bar must not use native AWT Button controls");
    }

    @Test
    @Tag("unit")
    void paintDoesNotRequireNativeWindow() {
        ClientTabManager manager = ClientTabManager.getInstance();
        manager.addLoginTab(new TestUI());
        ClientTabBar bar = new ClientTabBar(manager, new Canvas());
        bar.setSize(320, 34);

        BufferedImage img = new BufferedImage(320, 34, BufferedImage.TYPE_INT_ARGB);
        bar.paint(img.getGraphics());

        assertEquals(320, img.getWidth());
    }
}
```

- [ ] **Step 3: Run tab bar test and verify failure**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabBarTest -buildfile build.xml`

Expected: compilation fails because `ClientTabBar` does not exist.

- [ ] **Step 4: Implement `ClientTabBar`**

Create `src/paisti/client/tabs/PaistiClientTabBar.java`:

```java
package paisti.client.tabs;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class ClientTabBar extends Canvas {
    static final int HEIGHT = 34;
    static final int CONTROL_W = 32;
    static final int TAB_MIN_W = 92;
    static final int TAB_MAX_W = 180;
    static final Color BG = new Color(28, 31, 36);
    static final Color ACTIVE_BG = new Color(64, 103, 150);
    static final Color INACTIVE_BG = new Color(48, 52, 59);
    static final Color FG = new Color(235, 238, 242);
    static final Color MUTED_FG = new Color(178, 184, 191);

    public enum HitKind { ADD, CLOSE, TAB }

    public static final class HitRegion {
        public final HitKind kind;
        public final Rectangle rect;
        public final ClientTab tab;

        HitRegion(HitKind kind, Rectangle rect, ClientTab tab) {
            this.kind = kind;
            this.rect = rect;
            this.tab = tab;
        }
    }

    private final ClientTabManager manager;
    private final Component gameComponent;
    private final ClientTabManager.Listener listener = this::scheduleRepaint;

    public ClientTabBar(ClientTabManager manager, Component gameComponent) {
        this.manager = manager;
        this.gameComponent = gameComponent;
        setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { click(e.getX(), e.getY()); }
        });
    }

    @Override public void addNotify() {
        super.addNotify();
        manager.addListener(listener);
    }

    @Override public void removeNotify() {
        manager.removeListener(listener);
        super.removeNotify();
    }

    @Override public Dimension getPreferredSize() {
        return new Dimension(640, HEIGHT);
    }

    @Override public void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight() <= 0 ? HEIGHT : getHeight();
        g.setColor(BG);
        g.fillRect(0, 0, w, h);
        g.setFont(getFont());
        for(HitRegion region : layoutRegionsForTests(w, h)) {
            if(region.kind == HitKind.ADD) {
                paintButton(g, region.rect, "+");
            } else if(region.kind == HitKind.CLOSE) {
                paintButton(g, region.rect, "x");
            } else {
                paintTab(g, region.rect, region.tab);
            }
        }
    }

    public List<HitRegion> layoutRegionsForTests(int width, int height) {
        List<HitRegion> regions = new ArrayList<>();
        int x = 4;
        regions.add(new HitRegion(HitKind.ADD, new Rectangle(x, 4, CONTROL_W, height - 8), null));
        x += CONTROL_W + 4;
        if(manager.getActiveTab() != null) {
            regions.add(new HitRegion(HitKind.CLOSE, new Rectangle(x, 4, CONTROL_W, height - 8), manager.getActiveTab()));
            x += CONTROL_W + 6;
        }
        List<ClientTab> tabs = manager.getTabs();
        int available = Math.max(0, width - x - 4);
        int tabw = tabs.isEmpty() ? TAB_MIN_W : Math.max(TAB_MIN_W, Math.min(TAB_MAX_W, available / tabs.size()));
        for(ClientTab tab : tabs) {
            if(!tab.isSelectable())
                continue;
            regions.add(new HitRegion(HitKind.TAB, new Rectangle(x, 4, tabw, height - 8), tab));
            x += tabw + 3;
        }
        return regions;
    }

    private void click(int x, int y) {
        for(HitRegion region : layoutRegionsForTests(getWidth(), getHeight())) {
            if(!region.rect.contains(x, y))
                continue;
            if(region.kind == HitKind.ADD)
                manager.requestNewLoginTab();
            else if(region.kind == HitKind.CLOSE)
                manager.closeActiveTab();
            else if(region.kind == HitKind.TAB)
                manager.activateTab(region.tab);
            refocusGame();
            repaint();
            return;
        }
    }

    private void paintButton(Graphics g, Rectangle r, String label) {
        g.setColor(INACTIVE_BG);
        g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g.setColor(FG);
        drawCentered(g, label, r);
    }

    private void paintTab(Graphics g, Rectangle r, ClientTab tab) {
        boolean active = manager.getActiveTab() == tab;
        g.setColor(active ? ACTIVE_BG : INACTIVE_BG);
        g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g.setColor(active ? FG : MUTED_FG);
        FontMetrics fm = g.getFontMetrics();
        String label = tab.label();
        int max = r.width - 16;
        while(label.length() > 1 && fm.stringWidth(label) > max)
            label = label.substring(0, label.length() - 2) + "...";
        g.drawString(label, r.x + 8, r.y + ((r.height - fm.getHeight()) / 2) + fm.getAscent());
    }

    private void drawCentered(Graphics g, String text, Rectangle r) {
        FontMetrics fm = g.getFontMetrics();
        int tx = r.x + (r.width - fm.stringWidth(text)) / 2;
        int ty = r.y + ((r.height - fm.getHeight()) / 2) + fm.getAscent();
        g.drawString(text, tx, ty);
    }

    private void scheduleRepaint() {
        if(EventQueue.isDispatchThread())
            repaint();
        else
            EventQueue.invokeLater(this::repaint);
    }

    private void refocusGame() {
        if(gameComponent != null) {
            if(!gameComponent.requestFocusInWindow())
                gameComponent.requestFocus();
        }
    }
}
```

- [ ] **Step 5: Update `PMainFrame`**

Replace imports and wrapper body in `src/paisti/client/PMainFrame.java`:

```java
package paisti.client;

import haven.Coord;
import haven.MainFrame;
import paisti.client.tabs.PaistiClientTabBar;
import paisti.client.tabs.PaistiClientTabManager;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Panel;

public class PMainFrame extends MainFrame {
    public PMainFrame(Coord isz) {
        super(isz);
    }

    @Override
    protected Component wrapRenderer(Component renderer) {
        Panel panel = new Panel(new BorderLayout());
        panel.add(new PaistiClientTabBar(PaistiClientTabManager.getInstance(), renderer), BorderLayout.NORTH);
        panel.add(renderer, BorderLayout.CENTER);
        return panel;
    }
}
```

- [ ] **Step 6: Delete old spike files/tests**

Remove `src/haven/session/SessionBar.java` and `test/unit/haven/session/SessionBarTest.java` after `ClientTabBarTest` exists.

- [ ] **Step 7: Run tab bar tests**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabBarTest -buildfile build.xml`

Expected: `ClientTabBarTest` passes without creating a native window.

---

### Task 9: Remove SessionManager And Finish Reference Migration

**Files:**
- Delete: `src/haven/session/SessionManager.java`
- Modify: all files still importing `haven.session.SessionManager`
- Modify: tests still importing `SessionManager`

- [ ] **Step 1: Find remaining references**

Run an exact search for `SessionManager`.

Expected remaining references before edits are in `PGLPanelLoop.java`, hotkey integration, old tests, and old bar files if not already removed.

- [ ] **Step 2: Remove or migrate every reference**

Replace remaining behavior as follows:

```java
SessionManager.getInstance().getSessions()        -> ClientTabManager.getInstance().getSessionContexts()
SessionManager.getInstance().getActiveSession()   -> ClientTabManager.getInstance().getActiveTab().sessionContext()
SessionManager.getInstance().isSessionUi(ui)      -> ClientTabManager.getInstance().isSessionUi(ui)
SessionManager.getInstance().isActiveSessionUi(ui)-> ClientTabManager.getInstance().isActiveUi(ui)
SessionManager.getInstance().requestAddAccount()  -> ClientTabManager.getInstance().requestNewLoginTab()
SessionManager.getInstance().waitForAddRequest()  -> ClientTabManager.getInstance().waitForLoginRequest()
SessionManager.getInstance().removeActiveSession()-> ClientTabManager.getInstance().closeActiveTab()
```

- [ ] **Step 3: Delete `SessionManager.java`**

Remove `src/haven/session/SessionManager.java` after the search has no production references.

- [ ] **Step 4: Run compile/test checkpoint**

Run: `ant clean-code test -buildfile build.xml`

Expected: no compile errors for `SessionManager` and unit tests pass or expose behavior-specific failures to fix in Task 9.

---

### Task 10: Complete Lifecycle And Return/Prune Tests

**Files:**
- Modify: `test/unit/paisti/client/PGLPanelLoopTest.java`
- Modify: `test/unit/paisti/client/tabs/PaistiClientTabManagerTest.java`
- Modify: `src/paisti/client/PGLPanelLoop.java`
- Modify: `src/paisti/client/tabs/PaistiClientTabManager.java`

- [ ] **Step 1: Add session pruning test to manager**

Append to `ClientTabManagerTest`:

```java
@Test
@Tag("unit")
void pruneDeadSessionsRemovesTerminalSessionTabsAndDisposesOnce() {
    PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
    SessionFixture first = newContext("first");
    SessionFixture second = newContext("second");
    PaistiClientTab firstTab = manager.addSessionTab(first.context);
    PaistiClientTab secondTab = manager.addSessionTab(second.context);

    second.context.session.close();
    manager.pruneDeadSessions();

    assertEquals(2, manager.getTabs().size(), "close request alone is not terminal");

    second.transport.fireClosed();
    manager.pruneDeadSessions();

    assertEquals(1, manager.getTabs().size());
    assertSame(firstTab, manager.getActiveTab());
    assertEquals(1, second.ui.destroyCalls);
    assertEquals(1, second.transport.closeCalls);
}
```

- [ ] **Step 2: Update visible `RemoteUI.Return` tests**

In `PGLPanelLoopTest`, change assertions that scan `mgr.getSessions()` to scan `PaistiClientTabManager.getInstance().getSessionContexts()`.

For visible return, assert the same tab is converted/replaced:

```java
PaistiClientTab active = mgr.getActiveTab();
assertSame(returnedSess, active.sessionContext().session);
assertSame(active.ui(), loop.currentUi());
```

- [ ] **Step 3: Update null-return and no-successor tests**

Replace semaphore reflection on `SessionManager.addAccountSignal` with public manager behavior:

```java
java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
Thread waiter = new Thread(() -> {
    try {
        PaistiClientTabManager.getInstance().waitForLoginRequest();
        released.countDown();
    } catch(InterruptedException e) {
        Thread.currentThread().interrupt();
    }
});
waiter.start();
// trigger code path
assertTrue(released.await(2, java.util.concurrent.TimeUnit.SECONDS));
waiter.interrupt();
waiter.join(2000);
```

- [ ] **Step 4: Run focused lifecycle tests**

Run: `ant test -Dtest=paisti.client.tabs.PaistiClientTabManagerTest -Dtest=paisti.client.PGLPanelLoopTest -buildfile build.xml`

Expected: client tab manager and GL loop tests pass. If Ant does not support two `-Dtest` selectors, run the two test classes separately.

---

### Task 11: Final Verification And Cleanup

**Files:**
- All modified files

- [ ] **Step 1: Search for removed abstractions and native button spike**

Search for these exact terms:

```text
SessionManager
SessionBar
java.awt.Button
requestAddAccount
waitForAddRequest
```

Expected:
- No `SessionManager` production references.
- No `SessionBar` references.
- No `java.awt.Button` usage in the client tab bar.
- No `requestAddAccount` or `waitForAddRequest` references.

- [ ] **Step 2: Run full unit suite**

Run: `ant clean-code test -buildfile build.xml`

Expected: build succeeds and tests pass.

- [ ] **Step 3: Run distributable/dev build**

Run: `ant clean-code bin-dev -buildfile build.xml`

Expected: build succeeds.

- [ ] **Step 4: Inspect git diff**

Run: `git diff -- src/haven src/paisti test/unit docs/superpowers/plans docs/superpowers/specs`

Expected:
- `ClientTabManager` owns tab order, active tab, lifecycle, and session contexts.
- `SessionManager` is gone.
- `ClientTabBar` paints tabs itself and does not rebuild child controls on a timer.
- `GLPanel` syncs to active login/session tab uniformly.
- Closing active sessions still defers UI destruction until pruning.
- No unrelated user files were reverted.

---

## Self-Review Notes

- Spec coverage: login tabs, session tabs, login-to-session conversion, switching, hotkeys, active tab sync, pruning, safe close behavior, custom-painted tab bar, and tests are covered by Tasks 1-11.
- Deliberate constraint: this plan preserves the single `UI.Runner` thread. It does not implement simultaneous independent login runners. That is consistent with the first implementation scope and avoids a risky rewrite of `Bootstrap.run()`.
- Out of scope honored: no drag-reordering, no thumbnails, no persisted tabs, no multi-rendering, no character labels beyond session account name.
- Existing uncommitted `SessionBar` spike is replaced, not polished.
