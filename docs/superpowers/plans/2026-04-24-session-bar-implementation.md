# Native Session Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a native top-level AWT session bar above the GL renderer for visual session switching and add/remove controls.

**Architecture:** Keep the game renderer and per-session `UI` trees unchanged. Add a small AWT `SessionBar` owned by `MainFrame`, backed by `SessionManager`, with a polling refresh timer and focused manager helpers for selecting an exact session.

**Tech Stack:** Java 11, AWT, existing Ant build, JUnit 5 unit tests.

---

## File Structure

- Create `src/haven/session/SessionBar.java`: AWT `Panel` that renders `+`, active-session remove, and one button per selectable session.
- Modify `src/haven/session/SessionManager.java`: add exact-session activation helper used by `SessionBar` buttons.
- Modify `src/haven/MainFrame.java`: install `SessionBar` above the existing renderer using `BorderLayout`.
- Modify `test/unit/haven/session/SessionManagerTest.java`: cover exact-session activation and rejecting retiring sessions.
- Create `test/unit/haven/session/SessionBarTest.java`: cover pure session-bar entry/model generation without showing a native window.

---

### Task 1: Add Exact Session Activation To SessionManager

**Files:**
- Modify: `src/haven/session/SessionManager.java`
- Modify: `test/unit/haven/session/SessionManagerTest.java`

- [ ] **Step 1: Write failing tests for exact activation**

Add these tests near the other switch/remove tests in `test/unit/haven/session/SessionManagerTest.java`:

```java
    @Test
    @Tag("unit")
    void activateSessionSelectsRegisteredSelectableSession() {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");

        manager.addSession(first.context);
        manager.addSession(second.context);

        assertTrue(manager.activateSession(first.context));
        assertSame(first.context, manager.getActiveSession());

        reset(manager);
    }

    @Test
    @Tag("unit")
    void activateSessionRejectsRetiringSession() {
        SessionManager manager = SessionManager.getInstance();
        reset(manager);
        SessionFixture first = newContext("first");
        SessionFixture second = newContext("second");

        manager.addSession(first.context);
        manager.addSession(second.context);
        second.context.close();

        assertFalse(manager.activateSession(second.context));
        assertSame(second.context, manager.getActiveSession(),
            "rejecting a retiring session must not silently switch to some other session");

        reset(manager);
    }
```

- [ ] **Step 2: Run tests to verify failure**

Run: `ant test -buildfile build.xml`

Expected: compile failure because `SessionManager.activateSession(SessionContext)` does not exist.

- [ ] **Step 3: Implement minimal manager helper**

Add this method to `src/haven/session/SessionManager.java` after `getActiveSession()`:

```java
    public synchronized boolean activateSession(SessionContext ctx) {
        if(ctx == null || !sessions.contains(ctx) || !ctx.isSelectable()) {
            return false;
        }
        activeSession = ctx;
        return true;
    }
```

- [ ] **Step 4: Run tests to verify pass**

Run: `ant test -buildfile build.xml`

Expected: all unit tests pass.

---

### Task 2: Add SessionBar Model And Native Panel

**Files:**
- Create: `src/haven/session/SessionBar.java`
- Create: `test/unit/haven/session/SessionBarTest.java`

- [ ] **Step 1: Write failing model tests**

Create `test/unit/haven/session/SessionBarTest.java`:

```java
package haven.session;

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

class SessionBarTest {
    private static final class DummyTransport implements Transport {
        private final List<Callback> callbacks = new ArrayList<>();

        @Override public void close() {}
        @Override public void queuemsg(PMessage pmsg) {}
        @Override public void send(PMessage msg) {}
        @Override public Transport add(Callback cb) { callbacks.add(cb); return this; }
    }

    private static final class DummyPanel extends Canvas implements GLPanel {
        @Override public GLEnvironment env() { return null; }
        @Override public Area shape() { return Area.sized(Coord.z, Coord.of(10, 10)); }
        @Override public Pipe basestate() { return null; }
        @Override public void glswap(haven.render.gl.GL gl) {}
        @Override public void setmousepos(Coord c) {}
        @Override public UI newui(UI.Runner fun) { throw new UnsupportedOperationException(); }
        @Override public void background(boolean bg) {}
        @Override public void run() {}
    }

    private static final class TestRootWidget extends RootWidget {
        private TestRootWidget(UI ui, Coord sz) { super(ui, sz); }
        @Override protected GobEffects createEffects(UI ui) { return null; }
        @Override public void tick(double dt) {}
        @Override public void draw(GOut g) {}
    }

    private static final class TestUI extends UI {
        private TestUI() { super(new DummyPanel(), Coord.of(10, 10), null); }
        @Override protected RootWidget createRoot(Coord sz) { return new TestRootWidget(this, sz); }
    }

    @AfterEach
    void resetManager() {
        SessionManager manager = SessionManager.getInstance();
        for(SessionContext ctx : manager.getSessions()) {
            manager.removeSession(ctx);
        }
    }

    @Test
    @Tag("unit")
    void entriesIncludeOnlySelectableSessionsAndMarkActive() {
        SessionManager manager = SessionManager.getInstance();
        SessionContext first = newContext("first");
        SessionContext second = newContext("second");
        SessionContext third = newContext("third");

        manager.addSession(first);
        manager.addSession(second);
        manager.addSession(third);
        second.close();
        manager.activateSession(first);

        List<SessionBar.Entry> entries = SessionBar.entries(manager);

        assertEquals(2, entries.size());
        assertSame(first, entries.get(0).context);
        assertEquals("first", entries.get(0).label);
        assertTrue(entries.get(0).active);
        assertSame(third, entries.get(1).context);
        assertEquals("third", entries.get(1).label);
        assertFalse(entries.get(1).active);
    }

    private static SessionContext newContext(String name) {
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
            return new SessionContext(session, ui, new RemoteUI(session));
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

- [ ] **Step 2: Run tests to verify failure**

Run: `ant test -buildfile build.xml`

Expected: compile failure because `SessionBar` does not exist.

- [ ] **Step 3: Implement SessionBar**

Create `src/haven/session/SessionBar.java`:

```java
package haven.session;

import java.awt.Button;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Panel;
import java.util.ArrayList;
import java.util.List;

public class SessionBar extends Panel {
    private static final Color ACTIVE = new Color(90, 120, 160);
    private static final Color INACTIVE = new Color(70, 70, 70);

    private final SessionManager manager;
    private final Component focusTarget;
    private final javax.swing.Timer refreshTimer;

    public static class Entry {
        public final SessionContext context;
        public final String label;
        public final boolean active;

        public Entry(SessionContext context, String label, boolean active) {
            this.context = context;
            this.label = label;
            this.active = active;
        }
    }

    public SessionBar(SessionManager manager, Component focusTarget) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 2));
        this.manager = manager;
        this.focusTarget = focusTarget;
        setBackground(new Color(35, 35, 35));
        refreshTimer = new javax.swing.Timer(500, e -> refresh());
        refresh();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        refreshTimer.start();
    }

    @Override
    public void removeNotify() {
        refreshTimer.stop();
        super.removeNotify();
    }

    public void refresh() {
        removeAll();
        add(controlButton("+", () -> manager.requestAddAccount()));
        add(controlButton("x", () -> manager.removeActiveSession()));
        for(Entry entry : entries(manager)) {
            Button button = new Button(entry.label);
            button.setForeground(Color.WHITE);
            button.setBackground(entry.active ? ACTIVE : INACTIVE);
            button.addActionListener(e -> {
                manager.activateSession(entry.context);
                refocusGame();
                refresh();
            });
            add(button);
        }
        validate();
        repaint();
    }

    private Button controlButton(String label, Runnable action) {
        Button button = new Button(label);
        button.addActionListener(e -> {
            action.run();
            refocusGame();
            refresh();
        });
        return button;
    }

    private void refocusGame() {
        if(focusTarget != null) {
            focusTarget.requestFocus();
        }
    }

    public static List<Entry> entries(SessionManager manager) {
        List<Entry> result = new ArrayList<>();
        SessionContext active = manager.getActiveSession();
        for(SessionContext ctx : manager.getSessions()) {
            if(ctx == null || !ctx.isSelectable()) {
                continue;
            }
            result.add(new Entry(ctx, label(ctx), ctx == active));
        }
        return result;
    }

    private static String label(SessionContext ctx) {
        if(ctx.session == null || ctx.session.user == null || ctx.session.user.name == null || ctx.session.user.name.isEmpty()) {
            return "Session";
        }
        return ctx.session.user.name;
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `ant test -buildfile build.xml`

Expected: all unit tests pass.

---

### Task 3: Install SessionBar In MainFrame

**Files:**
- Modify: `src/haven/MainFrame.java`

- [ ] **Step 1: Update imports**

Add this import near the other session imports in `src/haven/MainFrame.java`:

```java
import haven.session.SessionBar;
```

- [ ] **Step 2: Install BorderLayout and bar**

Replace this block in `MainFrame(Coord isz)`:

```java
    Component pp = (Component)(this.p = renderer());
```

with:

```java
    Component pp = (Component)(this.p = renderer());
    SessionBar sessionBar = new SessionBar(haven.session.SessionManager.getInstance(), pp);
```

Replace this block:

```java
    add(pp);
    pp.setSize(sz.x, sz.y);
```

with:

```java
    setLayout(new BorderLayout());
    add(sessionBar, BorderLayout.NORTH);
    add(pp, BorderLayout.CENTER);
    pp.setPreferredSize(new Dimension(sz.x, sz.y));
    pp.setSize(sz.x, sz.y);
```

- [ ] **Step 3: Preserve window resize command behavior**

In the `sz` console command, replace:

```java
            p.setSize(w, h);
            pack();
```

with:

```java
            ((Component)p).setPreferredSize(new Dimension(w, h));
            p.setSize(w, h);
            pack();
```

- [ ] **Step 4: Compile and test**

Run: `ant test -buildfile build.xml`

Expected: all unit tests pass.

---

### Task 4: Build Dev Artifact And Manually Inspect

**Files:**
- No source changes expected.

- [ ] **Step 1: Build the dev artifact**

Run: `ant clean-code bin-dev -buildfile build.xml`

Expected: `BUILD SUCCESSFUL` and `bin-dev/hafen.jar` updated.

- [ ] **Step 2: Manual smoke check in `hafen-dev`**

Launch the `hafen-dev` run config and verify:

- The bar appears above the game renderer.
- `+` starts the login/add-session flow.
- Each logged-in account appears as a session button.
- Clicking a session button switches to that session.
- `x` removes the active session without crashing.
- Keyboard shortcuts still work: `Ctrl+Shift+Up/Down/Left/Right`.

- [ ] **Step 3: Report implementation status**

Run:

```powershell
git status --short
```

Expected: source and test changes are visible. Do not commit unless the user explicitly asks for a commit.

---

## Self-Review

- Spec coverage: The plan installs a native AWT bar in `MainFrame`, shows selectable sessions, highlights active session, delegates add/remove/switching to `SessionManager`, avoids direct UI destruction, and includes testing.
- Placeholder scan: No placeholder tasks remain; every code change includes concrete snippets and commands.
- Type consistency: `SessionBar.Entry`, `SessionManager.activateSession(SessionContext)`, and `SessionContext.isSelectable()` are used consistently across tests and implementation.
