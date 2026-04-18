# Windows And Widgets

## Core Widget Model

The entire UI is a tree of `Widget` instances managed by one `UI` object.

Core files:

- `src/haven/Widget.java`
- `src/haven/UI.java`
- `src/haven/RemoteUI.java`
- `src/haven/RootWidget.java`
- `src/haven/Window.java`

The basic server-driven path is:

1. `RemoteUI` reads a UI packet
2. `UI` creates or finds the widget by id
3. the parent widget places the child via `addchild(...)`
4. later `uimsg(...)` calls mutate the widget

## How Windows Are Created

Windows are just widgets created through the `wnd` factory.

In a mostly native client, that would simply instantiate `Window`. In this fork, window creation is intercepted.

## Parent-Driven Layout

One subtle Haven convention is that child placement is often parent-specific. The server may create a child widget, but the parent's `addchild(...)` decides what that means structurally.

That is why understanding windows and dialogs often requires reading both:

- the widget constructor/factory
- the parent's `addchild(...)`

## Native Client Behavior

Native Haven gives you:

- the widget tree in `Widget`
- widget id routing in `UI`
- server-driven UI adaptation in `RemoteUI`
- `Window` as the base desktop-style movable window
- message flow through `uimsg(...)` and `wdgmsg(...)`

## Ender Additions

This fork patches the window system in a few important ways:

- `Window.java` routes `wnd` creation through `src/me/ender/WindowDetector.java`
- `WindowDetector.newWindow(...)` may substitute custom classes like `WindowX`, `ProspectingWnd`, or `CharterBook`
- `WindowDetector` recognizes finished windows on the `pack` event and patches them at runtime
- `ExtInventory` replaces plain inventory presentation for `inv` widgets
- `WindowX` and `DecoX` provide themed window chrome

The cleanest summary is:

- native Haven defines the widget protocol
- Ender rewrites parts of the window instantiation and post-processing flow

## Gotchas

- `src/haven/**` includes fork hooks, so `Window.java` and `UI.java` are not purely upstream behavior here.
- `pack` is not just layout in this fork; it also acts as the signal that a window is ready for `WindowDetector` recognition.
- A server-created `inv` is often wrapped by `ExtInventory`, so widget-tree assumptions can be wrong if you expect only native classes.
