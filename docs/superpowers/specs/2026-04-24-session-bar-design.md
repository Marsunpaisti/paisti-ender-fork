# Native Session Bar Design

## Goal

Add a top-level session bar above the game renderer so active sessions can be seen and switched visually. The bar should be outside the in-game UI and remain visible across login, lobby, and active gameplay states.

## Approach

Use a native AWT container in `MainFrame`: a top `SessionBar` component plus the existing `UIPanel` renderer below it. This preserves the existing GL/UI rendering model and keeps the session controls at the meta-client level rather than inside any one `UI` tree.

## Components

- `SessionBar`: a small AWT panel rendered above the GL canvas.
- `SessionManager`: remains the source of truth for sessions and active session selection.
- `MainFrame`: owns the layout and installs the bar next to the renderer.

## Behavior

- Show one button per live selectable session.
- Highlight the active session.
- Clicking a session button switches to that session.
- Show a compact `+` button that calls `SessionManager.requestAddAccount()`.
- Show a compact close/remove button for the active session that calls `SessionManager.removeActiveSession()`.
- Use session labels from `Session.user.name` initially; character-name display can be added later if a reliable source is identified.
- Refresh the bar on a lightweight timer, and later replace polling with listener callbacks if needed.

## Layout

`MainFrame` should use `BorderLayout`:

- `BorderLayout.NORTH`: `SessionBar`
- `BorderLayout.CENTER`: existing renderer component

The bar should have a fixed compact height and should not overlap or draw over the GL canvas.

## Error Handling

- Ignore null sessions/UI contexts in rendering.
- Do not show retiring/non-selectable sessions as switch targets.
- If no session is active, show only the add-session control.
- Session switching and closing should delegate to `SessionManager`; the bar must not directly destroy UIs or sessions.

## Testing

- Add unit tests for any new `SessionManager` helper used by the bar.
- Add a focused test for `SessionBar` model generation if the UI code is separated into a simple model method.
- Existing session lifecycle tests should continue to pass.

## Out Of Scope

- Drag-reordering sessions.
- Per-session thumbnails.
- Character-name lookup beyond `Session.user.name`.
- Full styling parity with Haven widgets.
