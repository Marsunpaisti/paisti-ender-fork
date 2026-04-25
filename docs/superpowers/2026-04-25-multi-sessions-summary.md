# Multi-Session Client Tabs Summary

## Goal

Implement first-class multi-session support where the client can hold multiple login/session tabs, switch between them, and keep background sessions alive without rebuilding native AWT controls or putting Paisti-specific behavior into vanilla `src/haven` classes.

## High-Level Result

- Added a Paisti-owned client tab system under `src/paisti/client/tabs`.
- Replaced the earlier `src/haven/session` session-manager approach with tab-oriented Paisti classes.
- Added custom-painted client tabs above the renderer instead of native AWT `Button` controls.
- Supported visible pending login tabs before their actual login `UI` is hydrated.
- Supported switching between login screens, character-select screens, and in-game sessions.
- Kept most new behavior in `src/paisti`, with `src/haven` limited mostly to generic extension hooks needed by Paisti wrappers.

## Main Architecture

### Paisti Tab Model

`PaistiClientTabManager` is the central owner for tab state:

- tab list and active tab
- pending login tab queue
- login tab hydration
- session tab conversion
- active tab switching
- session close/prune lifecycle
- listener notifications for repainting the tab bar

`PaistiClientTab` has two states:

- `LOGIN`: either pending (`ui == null`) or hydrated (`ui != null`)
- `SESSION`: backed by `PaistiSessionContext`

Pending login tabs are activatable for the tab model, but not renderable until they receive a `UI`.

### Paisti Runners

`PBootstrap` owns the Paisti login flow and cancellation support.

`PaistiLobbyRunner` waits for a requested pending login tab, then returns `PBootstrap` so the GL loop can create/hydrate the next login UI.

`PaistiSessionRunner` attaches `RemoteUI` to a session UI, converts the prepared login tab into a session tab, and returns to the lobby runner.

### Paisti GL Loop

`PGLPanelLoop` extends the generic hooks added to `GLPanel.Loop` and handles:

- creating `PUI` instances
- hydrating pending login tabs when `PBootstrap` UIs are created
- replacing login UIs with session UIs during session startup
- syncing the visible UI to the active tab
- ticking background sessions
- dispatching background `RemoteUI` messages
- handling visible/background `RemoteUI.Return`
- pruning terminal sessions

### Frame And Renderer Wrappers

`PMainFrame` wires the Paisti client into the normal startup path:

- uses `PBootstrap` as the default runner
- uses `PJOGLPanel` / `PLWJGLPanel`
- wraps the renderer with `PaistiClientTabBar`
- routes direct-connect and replay sessions through `PaistiSessionRunner`
- installs `PaistiSessions` as the connector for `SessWidget`

`PJOGLPanel` and `PLWJGLPanel` only override the loop creation to use `PGLPanelLoop`.

## UI Lifecycle

### Login Screen

At startup, `PBootstrap` creates a real `PUI`/`UI` and adds a `LoginScreen` widget:

```java
ui.newwidgetp(1, ($1, $2) -> new LoginScreen(confname), 0, new Object[] {Coord.z});
```

The login screen is therefore a normal widget tree rendered by `UI.draw(...)`.

### Character Select

After authentication succeeds, the same UI becomes session-backed:

- `Session` is created
- `session.ui = ui`
- the login widget is destroyed with `ui.destroy(1)`
- `RemoteUI` starts processing server widget messages

Character select is server-driven widgets in the same session UI. The server creates widgets such as `Charlist` via `RemoteUI.dispatchMessage(...) -> ui.newwidgetp(...)`.

### In-Game

When the server creates `gameui`, `GameUI.$_` constructs `PGameUI` and calls `ui.setGUI(gui)`. Only at this point does the `PGameUI` object exist.

Because login and character select do not have `PGameUI`, tab hotkeys cannot live only in `PGameUI.globtype(...)`.

## Tab Bar And Hotkeys

`PaistiClientTabBar` is a custom-painted AWT `Panel`:

- no native AWT `Button`s
- stable layout without timer-based component rebuilding
- add, close, and tab hit regions
- pending login tabs are visible
- long labels are truncated safely

Client-tab hotkeys:

- `Ctrl+Shift+Up`: open/request login tab
- `Ctrl+Shift+Down`: close active tab
- `Ctrl+Shift+Left`: previous tab
- `Ctrl+Shift+Right`: next tab

Hotkeys are handled at the tab-bar/AWT dispatcher layer so they work on login screens and character-select screens, not just in-game.

## Haven-Core Changes

The old Haven-side session package was removed:

- `src/haven/session/SessionManager.java`
- `src/haven/session/SessionContext.java`
- `src/haven/session/SessionRunner.java`
- `src/haven/session/LobbyRunner.java`

The remaining `src/haven` changes are mostly generic hooks:

- `GLPanel.Loop` exposes overridable lifecycle hooks for managed UIs.
- `JOGLPanel` and `LWJGLPanel` expose loop creation hooks.
- `MainFrame.ClientFactory` allows Paisti startup wiring without hardcoding Paisti into vanilla startup.
- `SessWidget` supports a configurable connector.
- `Session` supports non-blocking UI message polling and custom gob factories.
- `RemoteUI` supports attach/dispatch helpers and `Return` handling used by tab sessions.

Paisti-specific tab behavior was moved out of `GameUI` and into Paisti classes.

## Session And Gob Factory

`PaistiSessions` centralizes Paisti session creation:

- direct sessions use `PGob::new`
- replay sessions use the same Paisti session factory path

This avoids adding direct `haven -> paisti` coupling for gob creation.

## Important Bug Fixes During Implementation

### Pending Login Tabs Were Not Switchable

Pending login tabs originally rendered in the tab bar but could not be clicked or reached with cycling hotkeys because activation used `isSelectable()`, which requires a non-null UI.

Fix:

- added a separate activatable concept
- kept renderability/selectability tied to non-null UI
- allowed pending login tabs to become active model tabs

### Hotkeys Did Not Work Outside In-Game UI

Hotkeys originally lived in `PGameUI`, but login and character select do not have `PGameUI` yet.

Fix:

- tab bar registers a `KeyEventDispatcher`
- tab hotkeys work at client-frame level before `GameUI` exists

### Automapper Crashed On Fast Multi-Character Entry

Two sessions entering `GameUI` concurrently could race in `Config.initAutomapper(...)`:

```java
if (MappingClient.initialized()) {
    MappingClient.destroy();
}
MappingClient.init(ui.sess.glob);
```

Each `MappingClient` method synchronized individually, but the whole check/destroy/init sequence was not atomic. Two `GameUI.attach(...)` calls could interleave and throw `MappingClient can only be initialized once!`.

Fix:

- made `Config.initAutomapper(UI ui)` synchronized
- added a concurrent regression test

Note: automapper remains process-global; this prevents the crash but does not make automapper truly multi-session-aware.

## Tests Added Or Reworked

New tests cover:

- tab manager lifecycle
- pending login tabs
- login hydration
- session conversion
- session close/prune behavior
- tab switching and hotkeys
- custom-painted tab bar hit testing and rendering
- Paisti GL loop session/background behavior
- `PBootstrap` cancellation
- `PMainFrame` factory/wrapper behavior
- concurrent automapper initialization

Old Haven-session-manager tests were removed with the old `src/haven/session` package.

## Verification Commands Used

The feature and follow-up fixes were verified with:

```bash
ant clean-code test -buildfile build.xml
ant clean-code bin-dev -buildfile build.xml
git diff --check -- src/haven src/paisti test/unit docs/superpowers/plans docs/superpowers/specs
```

The last full verification after the automapper fix passed:

- `ant clean-code test -buildfile build.xml`: `BUILD SUCCESSFUL`, 132 tests, 0 failures
- `ant clean-code bin-dev -buildfile build.xml`: `BUILD SUCCESSFUL`
- diff whitespace check: no whitespace errors, only LF-to-CRLF warnings

## Commit History

The main feature commit is:

```text
f02a64cad feat: add client tabs for multi-session UI
```

Follow-up fixes for pending-tab activation, login-screen hotkeys, and automapper concurrency were implemented after that commit and may need a separate commit.

## Known Residual Risks

- `PBootstrap.run(...)` duplicates substantial vanilla `Bootstrap.run(...)` logic, so upstream bootstrap changes can be missed during merges.
- `GLPanel.Loop` now has a generic managed-UI hook protocol whose invariants are mostly implicit.
- `SessWidget` uses a process-global connector override.
- `MappingClient` remains process-global. The crash is fixed, but the last in-game session to initialize automapper owns the singleton.
- Pending login tabs with `ui == null` are active in the tab model but cannot render a distinct client screen until hydrated. The renderer must continue treating `getActiveUi() == null` as a safe waiting state.
