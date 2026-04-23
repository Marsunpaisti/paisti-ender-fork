# Multi-Session Implementation Plan for paisti-ender-fork

Implementation spec for adding multiple simultaneous session support to the paisti-ender-fork Haven client. Based on the generic architecture study in `haven-nurgling2/docs/multi-session-architecture.md`, tailored to this codebase's specific structure.

## Table of Contents

- [Codebase Assessment](#codebase-assessment)
- [Architecture Overview](#architecture-overview)
- [Implementation Steps](#implementation-steps)
  - [Step 1: Session.pollUIMsg()](#step-1-sessionpolluimsg)
  - [Step 2: RemoteUI.dispatchMessage()](#step-2-remoteuimessage-dispatch-extraction)
  - [Step 3: SessionContext](#step-3-sessioncontext)
  - [Step 4: SessionManager](#step-4-sessionmanager)
  - [Step 5: GLPanel.Loop — Tick All, Draw One](#step-5-glpanelloop--tick-all-draw-one)
  - [Step 6: GLPanel.Loop.newui() — Lifecycle Hooks](#step-6-glpanelloopnewui--lifecycle-hooks)
  - [Step 7: Bootstrap — Factory and Hooks](#step-7-bootstrap--factory-and-hooks)
  - [Step 8: MainFrame.uiloop() — Lobby Runner](#step-8-mainframeuiloop--lobby-runner)
  - [Step 9: Session Switching Trigger](#step-9-session-switching-trigger)
- [Static Window Singletons](#static-window-singletons)
- [PUI Per-Instance Services](#pui-per-instance-services)
- [File Summary](#file-summary)
- [Verification Checklist](#verification-checklist)

---

## Codebase Assessment

### What's Already Multi-Session Friendly

| Property | Status | Details |
|----------|--------|---------|
| No `UI.ui` static singleton | Clean | No static UI field, no `UI.getInstance()` |
| Widget instance access | Clean | All widgets use `this.ui` (Widget.java:231) |
| `PUI` per-instance services | Clean | Each PUI owns its own `PaistiServices`, `EventBus`, `PluginService`, `OverlayManager` |
| `auto/` bot code | Clean | All methods take `GameUI gui` parameter, access `gui.ui` |
| `me/ender/` utilities | Clean | Static methods take `UI ui` parameter |
| `Session.ui` bidirectional binding | Clean | Set in `RemoteUI.init()` (RemoteUI.java:149-152) |

### What Needs Modification

| Component | File | Current State | Needed Change |
|-----------|------|---------------|---------------|
| Message polling | `Session.java` | Only blocking `getuimsg()` (line 396) | Add non-blocking `pollUIMsg()` |
| Message dispatch | `RemoteUI.java` | Dispatch logic buried in blocking `run()` loop (lines 92-147) | Extract into callable `dispatchMessage()` |
| GL render loop | `GLPanel.java` Loop.run() (line 338) | Ticks/draws single UI | Tick all sessions, draw active only |
| UI lifecycle | `GLPanel.java` Loop.newui() (line 476) | Destroys previous UI unconditionally (line 499-502) | Add hooks to preserve session UIs |
| UI creation | `GLPanel.java` Loop.makeui() (line 65) | Creates `new PUI(...)` | No change needed — already a factory |
| Login flow | `Bootstrap.java` run() (line 337) | Returns `new RemoteUI(sess)` directly | Add `preRun()` hook, factory for RemoteUI |
| Direct startup entrypoints | `MainFrame.java` main2() (lines 467-478) | Replay and `servargs` paths construct raw `RemoteUI` | Either wrap through the same session runner path or explicitly keep single-session-only |
| Main loop | `MainFrame.java` uiloop() (line 330) | Hardcoded `new Bootstrap()` | Add factory pattern, lobby runner |
| Config windows | 5 files | `static Window instance` singletons | Low priority — change to per-UI maps |
| Gob factory | `Gob.java:50`, `PUI.java:17,69` | `static Factory factory` mutated per PUI lifecycle | Must refactor before multi-session (see below) |
| Session.closed | `Session.java:80` | `private boolean closed` | Need public `isClosed()` getter |

### Existing Extension Points We Can Leverage

- **`GLPanel.Loop.makeui()`** (line 65): Already a virtual method creating `PUI`. No change needed for multi-session.
- **`PUI` constructor** (line 12): Already creates per-instance `PaistiServices`. Each session gets its own event bus, plugins, overlays automatically.
- **`UI.setGUI()` / `UI.clearGUI()`** (UI.java:1162-1174): Lifecycle hooks for GameUI. PUI already overrides these (PUI.java:54-64).
- **`Session.ui` field** (Session.java:79): Public, bidirectional binding set in RemoteUI.init().
- **`UI.Runner.init()`** (UI.java:235-236): Runs during `UI` construction, before `GLPanel.newui()` assigns `ui.env`. Safe for session/UI binding, but too early for logic that depends on render environment data.

---

## Architecture Overview

### Thread Model

```
Network threads (per session)     Uiloop thread        GL render thread (Loop.run)
─────────────────────────────     ─────────────        ───────────────────────────
Transport receives packets        Runs Bootstrap       For EACH registered session:
  → postuimsg() into uimsgs        (login flow)         pollUIMsg() → dispatchMessage()
                                  After login:           ui.tick(), glob.ctick()
                                    register session     glob.map.sendreqs()
                                    block in Lobby     For ACTIVE session only:
                                    wait for "add"       ui.gtick(), glob.gtick()
                                                         draw()
```

Two application threads total (uiloop + GL), regardless of session count.

### Switching

Switching sessions = changing `SessionManager.activeSession` pointer. The GL loop picks up the change on the next frame. No detach/reattach, no background threads, no special messages.

### Session Lifecycle

```
Login (uiloop)                    Register                    Active
  Bootstrap.run()          →      SessionManager.add()   →   GL loop ticks + draws
  creates Session + PUI           session gets ticked         user sees this session

Switch away                       Background                  Switch back
  activeSession = other    →      GL loop ticks only    →    activeSession = this
  no draw() for this              messages still polled       draw() resumes
```

---

## Implementation Steps

### Prerequisite: Fix Static State Before Multi-Session

These must be resolved before any multi-session code works correctly.

**1. `Gob.factory` static singleton (CRITICAL)**

`Gob.java:50` declares `public static Factory factory = Gob::new`. `PUI` constructor (line 17) sets it to `PGob::new`, and `PUI.destroy()` (line 69) resets it to `Gob::new`. With multiple PUIs alive simultaneously:
- The last PUI constructed wins (sets factory to PGob::new — probably fine)
- Destroying *any* PUI resets it to `Gob::new` (breaks all other sessions' gob creation)

**Fix:** Move the factory to `OCache` (per-session, since each Session has its own Glob which has its own OCache) or pass it through the PUI/Glob chain. The consumer is `OCache.java:454`: `gob = Gob.factory.create(glob, Coord2d.z, id)`. Since `OCache` already has access to `glob`, the factory can live on `Glob`:

```java
// In Glob.java — add field:
public Gob.Factory gobFactory = Gob::new;

// In OCache.java:454 — change:
gob = glob.gobFactory.create(glob, Coord2d.z, id);

// In PUI.java constructor — change:
// Instead of: Gob.factory = PGob::new;
// Set on the session's glob after session is bound (e.g., in setGUI or after init)

// In PUI.java destroy — remove the Gob.factory reset entirely
```

**2. `Session.closed` accessibility**

`Session.java:80`: `private boolean closed`. Add a public getter:

```java
// In Session.java, after the closed field:
public boolean isClosed() { return closed; }
```

**3. `Bootstrap.run()` temporary `sess.ui` binding needs care (line 316)**

`Bootstrap.run()` line 316 sets `sess.ui = ui` pointing to the *Bootstrap* login UI. It is tempting to remove this because `RemoteUI.init()` later rebinds `sess.ui` to the real session PUI, but the session is already live at that point and packets may arrive before the next `p.newui(fun)` call.

Do **not** remove line 316 blindly. Either:
- keep the temporary binding until `SessionRunner`/`RemoteUI` installs the real session UI, or
- replace it with an audited staging strategy after checking async `sess.ui` consumers (`Gob`, `OCache`, mapping code, `CharacterInfo`, etc.).

For the minimal implementation, leaving the temporary assignment in place is safer than creating a null window.

---

### Step 1: Session.pollUIMsg()

**File:** `src/haven/Session.java`
**Location:** After `getuimsg()` (line 406)
**Change:** Add non-blocking message poll method

```java
/**
 * Non-blocking poll for the next UI message.
 * Returns null if no messages are available or session is closed.
 */
public PMessage pollUIMsg() {
    synchronized(uimsgs) {
        if(uimsgs.isEmpty())
            return(null);
        return(uimsgs.remove());
    }
}
```

This is the only change to Session.java. The existing `getuimsg()` stays for Bootstrap/RemoteUI's initial blocking flow.

---

### Step 2: RemoteUI Message Dispatch Extraction

**File:** `src/haven/RemoteUI.java`
**Change:** Extract the message dispatch switch from `run()` (lines 103-141) into a standalone method.

Add after `run()`:

```java
/**
 * Dispatch a single server message to the UI.
 * Used by SessionManager for background session message processing.
 *
 * @return true if message was processed, false if session ended
 */
public boolean dispatchMessage(PMessage msg, UI ui) {
    if(msg == null)
        return(false);
    if(msg instanceof Return)
        return(false); // Session transfers not supported in background
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

Note: The existing `run()` method stays unchanged. It's still used for the active session's initial RemoteUI flow on the uiloop thread. The `dispatchMessage()` method is used by the GL loop for background session message processing, and also for the active session once the uiloop hands off to the lobby runner.

**Alternative:** Optionally refactor `run()` to call `dispatchMessage()` internally to avoid code duplication. This is a cleanup step, not required for functionality.

---

### Step 3: SessionContext

**New file:** `src/haven/session/SessionContext.java`

```java
package haven.session;

import haven.*;

/**
 * Holds all state for one logged-in session.
 * Lightweight container — no threads, no lifecycle management.
 */
public class SessionContext {
    public final Session session;
    public final UI ui;
    public final RemoteUI remoteUI;
    private String characterName;
    private volatile boolean alive = true;

    public SessionContext(Session session, UI ui, RemoteUI remoteUI) {
        this.session = session;
        this.ui = ui;
        this.remoteUI = remoteUI;
    }

    public String characterName() { return characterName; }
    public void setCharacterName(String name) { this.characterName = name; }

    public boolean isAlive() { return alive && !session.isClosed(); }

    public void close() {
        alive = false;
        session.close();
    }

    /** Generate a unique key for this session. */
    public String key() {
        return session.user.name + "@" + System.identityHashCode(this);
    }
}
```

Also audit `MainFrame.main2()` (lines 467-478). The replay and `servargs` startup paths currently instantiate raw `RemoteUI` runners; either route them through the same wrapper flow or explicitly document them as single-session-only.

---

### Step 4: SessionManager

**New file:** `src/haven/session/SessionManager.java`

```java
package haven.session;

import haven.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Manages all active sessions. Singleton.
 *
 * Thread safety:
 * - getSessions() returns a snapshot (safe to iterate from GL thread)
 * - switchTo() can be called from any thread (GL thread picks up change next frame)
 * - addSession()/removeSession() called from uiloop thread
 */
public class SessionManager {
    private static final SessionManager instance = new SessionManager();
    public static SessionManager getInstance() { return instance; }

    private final LinkedHashMap<String, SessionContext> sessions = new LinkedHashMap<>();
    private volatile SessionContext activeSession;
    private volatile SessionContext pendingSwitchTo;
    private final Semaphore addAccountSignal = new Semaphore(0);

    private SessionManager() {}

    /* ---- Session registry ---- */

    public synchronized void addSession(SessionContext ctx) {
        sessions.put(ctx.key(), ctx);
        activeSession = ctx;
    }

    public synchronized void removeSession(String key) {
        SessionContext ctx = sessions.remove(key);
        if(ctx != null) {
            ctx.close();
            if(ctx.ui != null) {
                synchronized(ctx.ui) {
                    ctx.ui.destroy();
                }
            }
        }
        // If we removed the active session, switch to another
        if(activeSession != null && activeSession.key().equals(key)) {
            activeSession = sessions.isEmpty() ? null : sessions.values().iterator().next();
        }
    }

    public synchronized void removeSession(SessionContext ctx) {
        removeSession(ctx.key());
    }

    /** Returns a snapshot of all sessions, safe to iterate. */
    public synchronized List<SessionContext> getSessions() {
        return new ArrayList<>(sessions.values());
    }

    public synchronized int sessionCount() {
        return sessions.size();
    }

    /* ---- Active session ---- */

    public SessionContext getActiveSession() {
        return activeSession;
    }

    public UI getActiveUI() {
        SessionContext ctx = activeSession;
        return ctx != null ? ctx.ui : null;
    }

    public void switchTo(SessionContext ctx) {
        activeSession = ctx;
    }

    public synchronized void switchToNext() {
        if(sessions.size() <= 1) return;
        List<SessionContext> list = new ArrayList<>(sessions.values());
        int idx = list.indexOf(activeSession);
        activeSession = list.get((idx + 1) % list.size());
    }

    public synchronized void switchToPrevious() {
        if(sessions.size() <= 1) return;
        List<SessionContext> list = new ArrayList<>(sessions.values());
        int idx = list.indexOf(activeSession);
        activeSession = list.get((idx - 1 + list.size()) % list.size());
    }

    public synchronized void switchToIndex(int index) {
        List<SessionContext> list = new ArrayList<>(sessions.values());
        if(index >= 0 && index < list.size())
            activeSession = list.get(index);
    }

    /* ---- Add account flow ---- */

    /**
     * Called from UI thread (e.g. keybind) to request adding a new account.
     * Wakes the uiloop thread which is blocked in LobbyRunner.
     */
    public void requestAddAccount() {
        addAccountSignal.release();
    }

    /**
     * Called from uiloop thread. Blocks until an "add account" request arrives.
     */
    public void waitForAddRequest() throws InterruptedException {
        addAccountSignal.acquire();
    }

    /* ---- Pending switch (for Bootstrap handoff) ---- */

    public void setPendingSwitch(SessionContext ctx) {
        pendingSwitchTo = ctx;
    }

    public SessionContext consumePendingSwitch() {
        SessionContext t = pendingSwitchTo;
        pendingSwitchTo = null;
        return t;
    }

    /* ---- Cleanup ---- */

    /** Remove dead sessions (closed connections). Called periodically from GL loop. */
    public synchronized void pruneDeadSessions() {
        Iterator<Map.Entry<String, SessionContext>> it = sessions.entrySet().iterator();
        while(it.hasNext()) {
            SessionContext ctx = it.next().getValue();
            if(!ctx.isAlive()) {
                if(ctx.ui != null) {
                    synchronized(ctx.ui) {
                        ctx.ui.destroy();
                    }
                }
                it.remove();
                if(ctx == activeSession)
                    activeSession = sessions.isEmpty() ? null : sessions.values().iterator().next();
            }
        }
    }
}
```

---

### Step 5: GLPanel.Loop — Tick All, Draw One

**File:** `src/haven/GLPanel.java`, `Loop.run()` method (line 338)

This is the most substantial change. The render loop currently operates on a single `ui` field. It needs to:
1. Poll and dispatch messages for all registered sessions
2. Tick (CPU) all sessions
3. GPU-tick and draw only the active session
4. Periodically prune dead sessions

**Current structure of `Loop.run()` (simplified):**
```java
while(true) {
    UI ui;
    synchronized(uilock) {
        this.lockedui = ui = this.ui;
    }
    // ... prefs, debug ...
    synchronized(ui) {
        ed.dispatch(ui);              // input events
        ui.tick();                    // widget tick
        if(ui.sess != null) {
            ui.sess.glob.ctick();     // world CPU tick
            ui.sess.glob.gtick(buf);  // world GPU tick
        }
        ui.gtick(buf);               // widget GPU tick
        // ... draw ...
    }
}
```

**New structure:**
```java
while(true) {
    UI ui;
    synchronized(uilock) {
        this.lockedui = ui = this.ui;
    }

    SessionManager sm = SessionManager.getInstance();

    // Tick ALL registered sessions (CPU only)
    for(SessionContext ctx : sm.getSessions()) {
        PMessage msg;
        while((msg = ctx.session.pollUIMsg()) != null) {
            if(!ctx.remoteUI.dispatchMessage(msg, ctx.ui))
                break; // session ended
        }
        synchronized(ctx.ui) {
            ctx.ui.tick();
            if(ctx.session != null && !ctx.session.isClosed()) {
                ctx.ui.sess.glob.ctick();
                ctx.ui.sess.glob.map.sendreqs();
            }
        }
    }

    // Prune dead sessions periodically (e.g. every 300 frames)
    if(frameno % 300 == 0)
        sm.pruneDeadSessions();

    // Active session gets input dispatch, GPU tick, and rendering
    SessionContext active = sm.getActiveSession();
    if(active != null && active.ui == ui) {
        synchronized(ui) {
            ed.dispatch(ui);
            // GPU ticks
            if(ui.sess != null) {
                ui.sess.glob.gtick(buf);
            }
            ui.gtick(buf);
            // ... existing draw code ...
        }
    }
    // If no active session (e.g. login screen), fall back to current ui
    else if(ui != null) {
        synchronized(ui) {
            ed.dispatch(ui);
            ui.tick();
            if(ui.sess != null) {
                ui.sess.glob.ctick();
                ui.sess.glob.gtick(buf);
            }
            ui.gtick(buf);
            // ... existing draw code ...
        }
    }
}
```

**Important details:**
- The `ui` field on Loop still exists and points to the "current" UI (either login screen or active session)
- When no sessions are registered (login screen), the loop operates in vanilla mode
- Input events (`ed.dispatch`) only go to the active/visible UI
- `ui.mousehover(ui.mc)` (actual code line 384) must only run for the active session — include it in the active-session block alongside `ed.dispatch()`, not in the background session tick loop
- `UI.tick()` already computes widget delta internally from `UI.lasttick`; do not thread `dt` through the plan's new loops
- `lockedui` handshake stays the same — it prevents `newui()` from destroying a UI mid-frame
- `glob.map.sendreqs()` currently happens from `MapView.draw()` (MapView.java:1888), not from `glob.ctick()`. Background sessions that are never drawn will stop issuing map requests unless you add an explicit call from the background tick path or explicitly accept stale map loading while unfocused.
- Keep `Loop.ui` synchronized to the active visible session inside the loop/session-switch plumbing, not inside a keybind handler. `GLPanel.Loop` still reads `this.ui` for prefs, debug modifier state, and final display.

**Gradual migration note:** You can implement this incrementally. Start by adding the session tick loop alongside the existing single-UI code. When `SessionManager` has 0 sessions, the existing code path runs unchanged. When sessions are registered, the multi-session path takes over.

---

### Step 6: GLPanel.Loop.newui() — Lifecycle Hooks

**File:** `src/haven/GLPanel.java`, `Loop.newui()` (line 476)

**Current code (lines 476-505):**
```java
public UI newui(UI.Runner fun) {
    UI prevui, newui = makeui(fun);
    newui.env = p.env();
    // ... console directories ...
    synchronized(uilock) {
        prevui = this.ui;
        ui = newui;
        // ... wait for lockedui handshake ...
    }
    if(prevui != null) {
        synchronized(prevui) {
            prevui.destroy();       // <-- PROBLEM: destroys session UIs
        }
    }
    return(newui);
}
```

**Modified code:**
```java
public UI newui(UI.Runner fun) {
    SessionManager sm = SessionManager.getInstance();
    UI prevui;

    // Hook 0: LobbyRunner should not create a throwaway PUI.
    // Keep the active session UI bound to the loop while the uiloop thread blocks.
    if(fun instanceof LobbyRunner) {
        SessionContext active = sm.getActiveSession();
        if(active != null) {
            synchronized(uilock) {
                prevui = this.ui;
                ui = active.ui;
            }
            if((prevui != null) && (prevui != active.ui) && !isSessionUI(prevui, sm)) {
                synchronized(prevui) {
                    prevui.destroy();
                }
            }
            return active.ui;
        }
    }

    // Hook 1: If switching to an existing session, reuse its UI
    if(fun instanceof RemoteUI) {
        RemoteUI rui = (RemoteUI) fun;
        for(SessionContext ctx : sm.getSessions()) {
            if(ctx.session == rui.sess) {
                // Reuse existing session UI — don't create or destroy anything
                UI reused = ctx.ui;
                reused.env = p.env();
                UI prevui;
                synchronized(uilock) {
                    prevui = this.ui;
                    ui = reused;
                    // ... lockedui handshake (same as before) ...
                }
                // Don't destroy prevui if it belongs to a session
                if(prevui != null && !isSessionUI(prevui, sm)) {
                    synchronized(prevui) {
                        prevui.destroy();
                    }
                }
                return(reused);
            }
        }
    }

    // Normal path: create new UI (for login screens)
    UI prevui, newui = makeui(fun);
    newui.env = p.env();
    // ... console directories (same as before) ...
    synchronized(uilock) {
        prevui = this.ui;
        ui = newui;
        // ... lockedui handshake (same as before) ...
    }
    // Hook 2: Don't destroy prevui if it belongs to a registered session
    if(prevui != null && !isSessionUI(prevui, sm)) {
        synchronized(prevui) {
            prevui.destroy();
        }
    }
    return(newui);
}

private boolean isSessionUI(UI ui, SessionManager sm) {
    for(SessionContext ctx : sm.getSessions()) {
        if(ctx.ui == ui) return true;
    }
    return false;
}
```

**Also update `onLoopTeardown()`** (line 69) to destroy ALL session UIs on shutdown:
```java
private void onLoopTeardown() {
    // Destroy all session UIs
    SessionManager sm = SessionManager.getInstance();
    for(SessionContext ctx : sm.getSessions()) {
        if(ctx.ui != null) {
            synchronized(ctx.ui) {
                ctx.ui.destroy();
            }
        }
    }
    // Destroy current UI if not a session UI
    UI lastui = this.ui;
    if(lastui != null && !isSessionUI(lastui, sm)) {
        synchronized(lastui) {
            lastui.destroy();
        }
    }
}
```

---

### Step 7: Bootstrap — Factory and Hooks

**File:** `src/haven/Bootstrap.java`

**Add factory pattern** (near top of class, after field declarations):
```java
private static Supplier<Bootstrap> factory = Bootstrap::new;

public static void setFactory(Supplier<Bootstrap> f) { factory = f; }
public static Bootstrap create() { return factory.get(); }
```

**Add hook methods** (before `run()`):
```java
/**
 * Called at the start of run(). If it returns non-null,
 * that Runner is used instead of the normal login flow.
 * Used for session switching — skip login, resume existing session.
 */
protected UI.Runner preRun(UI ui) {
    return null;
}

/**
 * Factory for creating RemoteUI. Override to return a subclass.
 */
protected RemoteUI createRemoteUI(Session sess) {
    return new RemoteUI(sess);
}
```

**Modify `run()` method:**

At the very start of `run()` (currently around line 170), before the login screen setup:
```java
public UI.Runner run(UI ui) throws InterruptedException {
    // Hook: check for pending session switch
    UI.Runner preResult = preRun(ui);
    if(preResult != null) return preResult;

    // ... existing login flow unchanged ...
```

At the end of `run()` (line 337), replace `return(new RemoteUI(sess))`:
```java
    return(createRemoteUI(sess));
```

**Modify `MainFrame.uiloop()`** (MainFrame.java:330):
```java
private void uiloop() throws InterruptedException {
    UI.Runner fun = null;
    while(true) {
        if(fun == null)
            fun = Bootstrap.create();  // was: new Bootstrap()
        String t = fun.title();
        if(t == null)
            setTitle(TITLE);
        else
            setTitle(TITLE + " \u2013 " + t);
        fun = fun.run(p.newui(fun));
    }
}
```

---

### Step 8: MainFrame.uiloop() — Lobby Runner

**New file:** `src/haven/session/LobbyRunner.java`

After a successful login, the uiloop needs a Runner that blocks until the next "add account" request. This replaces RemoteUI as the runner returned to the uiloop.

```java
package haven.session;

import haven.UI;

/**
 * A UI.Runner that blocks the uiloop thread until
 * an "add account" request is signaled.
 *
 * The uiloop cycle becomes:
 *   Bootstrap → (login) → SessionRunner → LobbyRunner → Bootstrap → ...
 */
public class LobbyRunner implements UI.Runner {
    @Override
    public UI.Runner run(UI ui) throws InterruptedException {
        // Block until "add account" is requested
        SessionManager.getInstance().waitForAddRequest();
        // Return to Bootstrap for new login
        return Bootstrap.create();
    }

    @Override
    public String title() {
        return null;
    }
}
```

**New file:** `src/haven/session/SessionRunner.java`

Wraps RemoteUI to register the session before handing off to the lobby:

```java
package haven.session;

import haven.*;

/**
 * Bridges between Bootstrap's login result and the session management system.
 * Runs RemoteUI briefly to establish the session, registers it with
 * SessionManager, then returns LobbyRunner to free the uiloop.
 *
 * This Runner is what Bootstrap.createRemoteUI() returns (via a subclass
 * or by overriding Bootstrap to return SessionRunner directly).
 */
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
    public UI.Runner run(UI ui) throws InterruptedException {
        // Register this session
        SessionContext ctx = new SessionContext(remoteUI.sess, ui, remoteUI);
        SessionManager.getInstance().addSession(ctx);

        // Set up the receiver so UI→server messages work
        ui.setreceiver(remoteUI);
        remoteUI.sendua(ui);  // make sendua(UI) package-private or protected

        // Hand off to lobby — GL loop now handles message dispatch for this session
        return new LobbyRunner();
    }

    @Override
    public String title() {
        return remoteUI.title();
    }
}
```

**Important consideration:** The vanilla `RemoteUI.run()` sends the user-agent info (`sendua(ui)`) at the start of its loop. Since `SessionRunner` replaces the `run()` call, it needs to send this too. However, `UI.Runner.init()` is **too early** for this: `UI` calls `fun.init(this)` in its constructor, before `GLPanel.newui()` assigns `ui.env = p.env()`. If `sendua(ui)` moves into `init()`, `ui.getenv()` is still null and the render capability fields stop being reported.

**Recommended:** Make `sendua(UI)` package-private or protected in `RemoteUI`, and call it from `SessionRunner.run()` immediately after `ui.setreceiver(remoteUI)`. That preserves the current behavior while keeping the uiloop handoff short.

### How the uiloop flows after these changes:

```
1. uiloop: fun = Bootstrap.create()
2. uiloop: fun.run(p.newui(fun))
   → Bootstrap shows login screen
   → User logs in
   → Bootstrap returns SessionRunner(new RemoteUI(sess))
3. uiloop: fun.run(p.newui(fun))
   → p.newui() creates PUI for SessionRunner
   → SessionRunner.init() → RemoteUI.init() sets ui.sess
   → SessionRunner.run() sets receiver, sends UA, registers session, returns LobbyRunner
4. uiloop: fun.run(p.newui(fun))
   → p.newui() keeps the active session UI bound to the loop (no throwaway PUI)
   → LobbyRunner.run() blocks on waitForAddRequest()
   → ... user plays the game, GL loop handles everything ...
   → User presses "add account" keybind
   → LobbyRunner.run() returns Bootstrap.create()
5. uiloop: fun.run(p.newui(fun))
   → p.newui() creates PUI for Bootstrap, does NOT destroy session UIs
   → Bootstrap shows login screen for second account
   → Back to step 2
```

**Note on LobbyRunner's PUI creation:** In the current repo, this is **not** harmless. `PUI` starts `PaistiServices` in its constructor, and `GLPanel.Loop` still reads `this.ui` for prefs, modifier state, and display ownership. Creating a throwaway lobby PUI would start an extra service graph and desynchronize `Loop.ui` from the visible session. The plan should therefore special-case `LobbyRunner` in `GLPanel.newui()` instead of treating this as optional polish.

---

### Step 9: Session Switching Trigger

For the minimal implementation, use the existing widget keybinding path rather than hardcoding AWT checks into `GLPanel.Loop.run()`.

**File:** `src/haven/GameUI.java`, `globtype(GlobKeyEvent ev)`

**Minimal approach — add gameplay-scoped keybindings:**
```java
public static final KeyBinding kb_nextsession =
    KeyBinding.get("session-next", KeyMatch.forcode(KeyEvent.VK_TAB, KeyMatch.M));
public static final KeyBinding kb_addsession =
    KeyBinding.get("session-add", KeyMatch.forchar('N', KeyMatch.M));

@Override
public boolean globtype(GlobKeyEvent ev) {
    if(kb_nextsession.key().match(ev)) {
        SessionManager.getInstance().switchToNext();
        return true;
    } else if(kb_addsession.key().match(ev)) {
        SessionManager.getInstance().requestAddAccount();
        return true;
    }
    return super.globtype(ev);
}
```

The exact keybind mechanism depends on how the paisti-ender-fork handles input. This could be:
- `GameUI.globtype()` (recommended for the minimal playable version)
- `RootWidget.globtype()` if the shortcuts must work outside gameplay widgets too
- A dedicated session-switcher widget if you want discoverability/UI affordance

Do not rely on the keybind handler to mutate `GLPanel.Loop.ui` directly. Keep that synchronization in the loop/session-manager plumbing from Step 5 so the rendering path has a single source of truth.

---

## Static Window Singletons

Low priority but should be addressed eventually. These 5 config windows use `static Window instance` which prevents multiple sessions from independently opening them:

| File | Class |
|------|-------|
| `src/haven/GobWarning.java:95` | GobWarning config |
| `src/haven/PathVisualizer.java:206` | PathVisualizer config |
| `src/haven/ShowBuffsCfgWnd.java:7` | ShowBuffs config |
| `src/haven/LoginTogglesCfgWnd.java:7` | LoginToggles config |
| `src/me/ender/GobInfoOpts.java:97` | GobInfo config |

**Fix:** Change from `static Window instance` to `static Map<UI, Window> instances` (or just remove the singleton enforcement — let each UI create its own).

---

## PUI Per-Instance Services

Each `PUI` already creates its own `PaistiServices` in its constructor (PUI.java:14-16). This means:
- Each session automatically gets its own `EventBus`
- Each session automatically gets its own `PluginService`
- Each session automatically gets its own `OverlayManager`
- `PUI.destroy()` (line 67-73) stops services and clears bindings

**Caveat:** `PUI.destroy()` currently resets `Gob.factory = Gob::new` (line 69). This must be fixed before multi-session — see Prerequisite #1 above. After the fix, PUI per-instance services are fully multi-session safe.

The `PUI.of(UI ui)` cast helper (line 20) remains correct — each session's UI is a PUI instance.

---

## File Summary

### Modified files (10)

| File | Change | ~Lines |
|------|--------|--------|
| `src/haven/Session.java` | Add `pollUIMsg()` after line 406, add `isClosed()` getter | ~12 |
| `src/haven/RemoteUI.java` | Add `dispatchMessage()`, make `sendua()` accessible | ~45 |
| `src/haven/GLPanel.java` | Loop.run() multi-session tick/draw, newui() lifecycle hooks | ~80 |
| `src/haven/Bootstrap.java` | Factory pattern, `preRun()` hook, `createRemoteUI()`, preserve or carefully replace the temporary `sess.ui` bridge | ~22 |
| `src/haven/MainFrame.java` | `Bootstrap.create()` instead of `new Bootstrap()`, plus decide how replay/direct-connect paths wrap `RemoteUI` | ~4 |
| `src/haven/Gob.java` | Remove static `factory` field (move to Glob) | ~3 |
| `src/haven/Glob.java` | Add `gobFactory` field | ~2 |
| `src/haven/OCache.java` | Use `glob.gobFactory` instead of `Gob.factory` at line 454 | ~2 |
| `src/paisti/client/PUI.java` | Remove `Gob.factory` mutation from constructor/destroy | ~4 |
| `src/haven/GameUI.java` | Add minimal session-switch/add-account keybindings | ~15 |

### New files (4)

| File | Purpose | ~Lines |
|------|---------|--------|
| `src/haven/session/SessionContext.java` | Per-session state container | ~35 |
| `src/haven/session/SessionManager.java` | Session registry and switching | ~120 |
| `src/haven/session/LobbyRunner.java` | Blocks uiloop between logins | ~20 |
| `src/haven/session/SessionRunner.java` | Registers session after login | ~35 |

### Total: ~420 lines across 14 files

---

## Verification Checklist

After implementation, verify:

- [ ] Single session still works identically to before (no regressions)
- [ ] PGob instances are created (not vanilla Gob) after Gob.factory refactor
- [ ] Can log in on a second account while first remains active
- [ ] Switching between sessions shows the correct game view
- [ ] Background sessions process server messages (world state updates)
- [ ] Background sessions tick (glob.ctick, ui.tick running)
- [ ] Background sessions still issue map requests intentionally (`sendreqs()` moved or deferred by design)
- [ ] Each session has its own PaistiServices/EventBus/PluginService
- [ ] Bot started on session A continues running when switched to session B
- [ ] LobbyRunner does not create a throwaway `PUI` / extra `PaistiServices` instance
- [ ] Closing a session cleans up properly (UI destroyed, removed from manager)
- [ ] Closing one session does not break gob creation in other sessions
- [ ] Client shutdown destroys all session UIs cleanly
- [ ] No `NullPointerException` when switching during login
- [ ] Replay/direct-connect startup paths are either intentionally single-session-only or routed through the same wrapper flow
- [ ] `ui.mousehover()` only runs for the active session
