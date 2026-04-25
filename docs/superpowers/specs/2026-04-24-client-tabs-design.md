# Client Tabs Design

## Goal

Replace the current logged-in-session tab spike with first-class client tabs. A tab represents a user-visible client surface, not only an authenticated session. Tabs can contain a login screen, an active game session, or later a disconnected/error state.

## Problem With The Current Spike

The current `SessionBar` is backed directly by `SessionManager`, so it only knows about logged-in sessions. When `+` opens the login flow, the visible login screen is a standalone `UI`, not a registered tab. The GL loop intentionally avoids overriding standalone login/bootstrap UIs, so clicking a logged-in session while a login screen is visible does not switch away. The bar also flickers because it rebuilds native AWT buttons every refresh, and native buttons look visually out of place.

## Architecture

Replace the session-manager layer with a single Paisti-owned tab manager. New custom code belongs under `src/paisti`, not `src/haven`; existing Haven classes should only receive minimal subclass-enabling hooks/callbacks when needed.

- `PaistiClientTab`: one user-facing tab/surface.
- `PaistiClientTabManager`: owns tab order, active tab, tab lifecycle, and all logged-in `PaistiSessionContext` ownership.
- `PaistiClientTabBar`: visual top-level native/custom-painted tab strip backed by `PaistiClientTabManager`.

There should not be a separate `SessionManager` state machine below this. A logged-in tab directly owns its `PaistiSessionContext`; login tabs own login/bootstrap UIs. Background servicing, pruning, switching, and closing should all operate from the tab list. This avoids split-brain state such as “active tab” versus “active session.”

## Tab States

### Login Tab

- Owns a login/bootstrap `UI`.
- Created by pressing `+`.
- Can be switched to/from like any other tab.
- Closing it destroys only that login UI.

### Session Tab

- Owns a `PaistiSessionContext`.
- Created when a login tab successfully authenticates.
- The login tab transforms into a session tab instead of creating a separate tab.
- Closing it starts session shutdown and defers final UI/session disposal to existing pruning rules.

### Future States

- Disconnected/error tabs can be added later if useful.
- They are out of scope for the first implementation.

## Login Flow

`+` creates a new login tab and switches to it. The tab owns the bootstrap UI while the user logs in. When login succeeds, `PaistiSessionRunner` or equivalent registration code associates the newly created `PaistiSessionContext` with that login tab, converting it into a session tab.

If a login is cancelled or the tab is closed before authentication finishes, only that tab/UI is removed. Other tabs remain selectable.

## Switching Flow

Clicking any tab changes the active `PaistiClientTab`. The Paisti GL loop must sync visible `UI` from the active tab, regardless of whether that UI is a login UI or a session UI. This replaces the current rule that refuses to override standalone login/bootstrap UIs.

The GL loop should service all tabs each frame:

- Login tabs keep only their UI state.
- Session tabs drain network UI messages, CPU tick, and map requests in the background.
- The active tab receives input/rendering.

Keyboard shortcuts should map to client tabs:

- `Ctrl+Shift+Up`: new login tab
- `Ctrl+Shift+Down`: close current tab
- `Ctrl+Shift+Left`: previous tab
- `Ctrl+Shift+Right`: next tab

## Rendering And Styling

Avoid native AWT `Button`s. Use a custom-painted AWT component or lightweight Swing component with a fixed larger height and explicit colors/fonts. The tab bar should update existing tab components or repaint from model state, not rebuild all controls every 500ms.

Minimum visual requirements:

- Larger height than the current spike.
- Readable font size.
- Active tab visibly highlighted.
- Add/close controls visually match the tab strip instead of OS-native Win95 buttons.
- No periodic flicker during refresh.

## Error Handling

- Closing a login tab must not affect session tabs.
- Closing a session tab must not synchronously destroy the currently-rendered UI from inside its own input handler.
- If the active tab is closed, select a neighboring tab if one exists.
- If no tabs remain, create or show a login tab so the client never sits without a visible surface.
- Retiring session tabs must not be selectable.
- Terminal session tabs should be pruned from `PaistiClientTabManager` after their session closes and queued UI messages are drained.

## Testing

- Unit-test `PaistiClientTabManager` tab creation, switching, closing, and login-to-session conversion.
- Unit-test that login tabs and session tabs are both selectable.
- Unit-test that closing the active tab selects a safe successor.
- Unit-test that session-tab pruning disposes terminal sessions once.
- Unit-test tab bar model rendering without creating a native window.
- Keep existing session lifecycle tests passing.

## Out Of Scope

- Multiple game sessions rendered at once.
- Drag-reordering tabs.
- Per-tab thumbnails.
- Persisting tab state across client restarts.
- Character-name labels unless a reliable source is already available.
