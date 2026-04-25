# FlatLaf Window Chrome Design

## Goal

Give the main client window a RuneLite-like dark desktop chrome without changing the in-game UI or the existing Paisti tab bar. The result should feel modern and flat, with custom themed titlebar controls, while preserving the current launch, fullscreen, resize, and close behavior.

## Scope

- Add FlatLaf as a client dependency.
- Install a dark FlatLaf look and feel during AWT/Swing initialization.
- Enable FlatLaf custom window decorations for the main client window when available.
- Keep an escape hatch: `-Dpaisti.flatlaf=false` disables FlatLaf setup and uses the current system look and feel path.
- Keep this change limited to desktop window chrome. Tab styling, plugin panels, launcher windows, and in-game widgets are out of scope.

## Architecture

The current main window is `haven.MainFrame`, a `java.awt.Frame`. FlatLaf custom window decorations are Swing root-pane features, so the main frame should become a `javax.swing.JFrame`. Existing code already embeds the renderer inside AWT components, and `PMainFrame.wrapRenderer()` returns an AWT `Panel`, so the renderer can remain the center content of the Swing frame.

`MainFrame.initawt()` will delegate to a Paisti-owned look-and-feel initializer in `paisti.client.ui`. That initializer will attempt FlatLaf setup unless disabled by system property. If FlatLaf setup fails, it should warn and fall back to the existing system look and feel instead of preventing startup.

The frame constructor will set the content pane layout and add the renderer wrapper through Swing-compatible APIs. When FlatLaf is active, it will set root-pane client properties for custom window decorations. Existing icon, title, resize, size persistence, window listeners, and focus behavior remain in `MainFrame`.

## Fullscreen Behavior

Fullscreen currently disposes the frame, toggles undecorated mode, shows it again, and hands it to `GraphicsDevice.setFullScreenWindow`. This should remain the source of truth. Returning to windowed mode should restore normal decorated state. If FlatLaf custom decorations are active, the restored decorated state should be FlatLaf-managed rather than OS-default where the platform supports it.

## Build Integration

The FlatLaf jar should be stored under `lib/` like other checked-in dependencies and added to:

- `hafen-client.classpath`
- `test.classpath` through the existing client classpath reference
- manifest/runtime classpath so `flatlaf-3.7.jar` can ship beside `hafen.jar` without losing multi-release jar metadata

Prefer the smallest build change that works with this repository's current Ant packaging. FlatLaf should remain an external jar rather than be unpacked into the fat jar because its multi-release `META-INF/versions` entries are part of the upstream artifact.

## Testing

Automated tests should cover the initializer's enable/disable decision and avoid requiring a native visible window in headless CI. Existing `PMainFrameTest` and tab tests should continue passing.

Manual verification should include:

- `ant test -buildfile build.xml`
- `ant bin -buildfile build.xml`
- launching the client normally on Windows
- launching with `-Dpaisti.flatlaf=false` to verify fallback
- toggling fullscreen and returning to windowed mode

## Risks

- Migrating `MainFrame` from AWT `Frame` to Swing `JFrame` is a real behavior change even if the public API mostly overlaps.
- Heavyweight JOGL/LWJGL canvases inside Swing can have focus or repaint quirks, so verification should explicitly check both renderer focus and fullscreen.
- FlatLaf custom decorations are platform-dependent. Windows 10/11 and Linux are the primary targets; macOS may retain native behavior.
