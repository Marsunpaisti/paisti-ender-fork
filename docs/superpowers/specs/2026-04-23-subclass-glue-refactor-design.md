# Subclass-Based Glue Code Refactor

**Date:** 2026-04-23  
**Status:** Approved  
**Goal:** Replace direct modifications to vanilla `src/haven/*` classes with subclasses under `src/paisti/client/`, improving code readability and upstream mergeability while preserving all existing service functionality.

## Problem

The paisti fork currently modifies ~45 lines across 5 vanilla haven files (`UI.java`, `GLPanel.java`, `OwnerContext.java`, `PaistiServices.java` in haven package, `MapView.java`) to wire in custom services (EventBus, PluginService, OverlayManager). This creates merge conflicts when pulling upstream changes and makes it hard to see at a glance what the fork touches.

## Solution

Subclass the key vanilla classes (`UI`, `GameUI`, `MapView`) in `src/paisti/client/`. Move all service ownership and behavioral hooks into the subclasses. Reduce vanilla modifications to 3 one-line instantiation swaps.

## Design Constraints

- EventBus, OverlayManager, PluginService must continue working with the same API
- Services must be scoped per-UI instance (no global/static state) to support future multi-session
- Each PUI instance gets fresh services (plugins reload on reconnect — acceptable and cleaner than shared lifecycle)
- Overlay rendering execution order must be preserved
- The `@RName` widget factory system (server-driven widget creation) must be respected

## New Classes

All new files live under `src/paisti/client/`:

### `PUI extends haven.UI`

The primary service owner. Replaces all PaistiServices code currently in `UI.java` and `GLPanel.java`.

```java
package paisti.client;

public class PUI extends UI {
    private final PaistiServices paistiServices;
    private final ScreenOverlayAfterDraw screenOverlayAfterDraw;

    public PUI(Context uictx, Coord sz, Runner fun) {
        super(uictx, sz, fun);
        this.paistiServices = new PaistiServices();
        this.paistiServices.bindUi(this);
        this.screenOverlayAfterDraw = new ScreenOverlayAfterDraw(paistiServices);
        this.paistiServices.start();
    }

    // --- Static helper for casting ---
    public static PUI of(UI ui) { return (PUI) ui; }

    // --- Service accessors ---
    public PaistiServices services() { return paistiServices; }
    public EventBus eventBus() { return paistiServices.eventBus(); }
    public PluginService pluginService() { return paistiServices.pluginService(); }
    public OverlayManager overlayManager() { return paistiServices.overlayManager(); }

    // --- Behavioral overrides ---

    @Override
    public void tick(double dt) {
        super.tick(dt);
        drawafter(screenOverlayAfterDraw); // register for screen overlay rendering each frame
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        eventBus().post(new BeforeOutgoingWidgetMessage(this, sender, sender.wdgid(), msg, args));
        super.wdgmsg(sender, msg, args);
    }

    @Override
    public void setGUI(GameUI gui) {
        super.setGUI(gui);
        paistiServices.overlayManager().syncMapOverlayAttachment();
    }

    @Override
    public void clearGUI(GameUI gui) {
        super.clearGUI(gui);
        paistiServices.overlayManager().syncMapOverlayAttachment();
    }

    @Override
    public void destroy() {
        paistiServices.stop();
        paistiServices.clearUi(this);
        super.destroy();
    }
}
```

### `ScreenOverlayAfterDraw implements UI.AfterDraw`

Bridges screen overlay rendering into the vanilla `AfterDraw` mechanism, eliminating the need for a mid-method hook in `UI.draw()`.

```java
package paisti.client;

public class ScreenOverlayAfterDraw implements UI.AfterDraw {
    private final PaistiServices services;

    public ScreenOverlayAfterDraw(PaistiServices services) {
        this.services = services;
    }

    @Override
    public void draw(GOut g) {
        services.overlayManager().renderScreenOverlays(g);
    }
}
```

### `PGameUI extends haven.GameUI`

Created by the server widget factory. Handles game-phase-specific lifecycle.

```java
package paisti.client;

public class PGameUI extends GameUI {
    public PGameUI(String chrid, long plid, String genus) {
        super(chrid, plid, genus);
    }

    // Future: game-phase initialization, per-session service scoping
}
```

### `PMapView extends haven.MapView`

Created by the server widget factory. Absorbs the `Bot.cancelCurrent()` call from vanilla MapView's click handler.

```java
package paisti.client;

public class PMapView extends MapView {
    // Constructor matching MapView's

    // Override click handler to call Bot.cancelCurrent() instead of having it in vanilla MapView
}
```

### `PaistiServices` (moved from `haven` to `paisti.client`)

The existing `PaistiServices.java` moves from the `haven` package to `paisti.client` (or `paisti`). No behavioral changes — just a package relocation.

## Vanilla Modifications

### Removed (cleanup)

| File | What's removed |
|---|---|
| `haven/UI.java` | `PaistiServices` field, both constructors' services code, `services()`/`eventBus()`/`pluginService()` accessors, `draw()` overlay call, `wdgmsg()` EventBus post, `destroy()` cleanup call, `setGUI()`/`clearGUI()` sync calls, paisti imports |
| `haven/GLPanel.java` | `PaistiServices` field, custom `makeui(UI.Runner, PaistiServices)` signature reverts to `makeui(UI.Runner)`, `onUiSwapped()` method removed entirely, `onLoopTeardown()` service stop logic removed (UI.destroy() handles it) |
| `haven/OwnerContext.java` | `.add(PaistiServices.class, UI::services)` (dead code — never called via `context()`) |
| `haven/PaistiServices.java` | Entire file deleted from `haven` package (moved to `paisti.client`) |
| `haven/MapView.java` | `import auto.Bot;` and `Bot.cancelCurrent()` call in click handler |

### Added (3 one-line swaps)

| File | Change |
|---|---|
| `haven/GLPanel.java` | `makeui()` returns `new PUI(ctx, sz, fun)` instead of `new UI(...)` (+ import) |
| `haven/GameUI.java` | `@RName("gameui")` factory `$_` returns `new PGameUI(chrid, plid, genus)` instead of `new GameUI(...)` (+ import) |
| `haven/MapView.java` | `@RName("mapview")` factory `$_` returns `new PMapView(...)` instead of `new MapView(...)` (+ import) |

### Callers to update

These 4 production call sites reference `ui.services()` or `ui.pluginService()` and need to cast to PUI:

| File | Line | Current | Updated |
|---|---|---|---|
| `haven/MapView.java` | 703-704 | `ui.services()` | `PUI.of(ui).services()` |
| `haven/OptWnd.java` | 884, 887 | `ui.pluginService()` | `PUI.of(ui).pluginService()` |

### Net delta

- **Before:** ~45 lines of modifications across 5 vanilla files
- **After:** 3 one-line instantiation swaps + 3 import lines across 3 vanilla files

## Service Lifecycle

Each `PUI` instance creates and owns its own fresh `PaistiServices`. Services do not survive across UI recreations (e.g., login → game → disconnect → login). This is a deliberate simplification: plugins reload on each reconnect, which gives a clean slate and avoids stale state.

```
MainFrame.uiloop()                          [loops: Bootstrap → RemoteUI → Bootstrap ...]
  └── p.newui(fun)
        └── GLPanel.Loop.newui(fun)
              └── makeui(fun)
                    └── new PUI(ctx, sz, fun)
                          ├── super(ctx, sz, fun)              [vanilla UI init]
                          ├── paistiServices = new PaistiServices()
                          ├── paistiServices.bindUi(this)
                          └── paistiServices.start()           [loads plugins, syncs config]

              [newui() continues: sets env, console, profiling on the returned PUI]

Every tick:
  PUI.tick(dt)
    ├── super.tick(dt)                       [vanilla tick]
    └── drawafter(screenOverlayAfterDraw)    [register for this frame's afterdraw pass]

Every frame:
  UI.draw(g)
    ├── root.draw(g)                         [widget tree draw]
    └── afterdraws loop
         └── screenOverlayAfterDraw.draw(g)  [renders screen overlays]

Server sends "gameui" widget:
  GameUI.$_ factory → new PGameUI(chrid, plid, genus)
    └── PGameUI attached to widget tree

PUI.setGUI(gui) / clearGUI(gui):
  super.setGUI/clearGUI(gui)
  overlayManager().syncMapOverlayAttachment()

PUI.wdgmsg(sender, msg, args):
  eventBus().post(BeforeOutgoingWidgetMessage)
  super.wdgmsg(sender, msg, args)

On disconnect / UI recreation:
  GLPanel.Loop.newui() destroys old PUI:
    └── PUI.destroy()
          ├── paistiServices.stop()          [stops all plugins, disposes overlays]
          ├── paistiServices.clearUi(this)
          └── super.destroy()
  Then creates new PUI with fresh services.
```

### GLPanel.Loop Changes

GLPanel.Loop becomes much simpler — it no longer owns `PaistiServices` at all:
- **Removed:** `private final PaistiServices paistiServices` field
- **Removed:** `onUiSwapped()` method (binding/starting no longer needed)
- **Simplified:** `onLoopTeardown()` → only destroys `lastui` (service cleanup handled by PUI.destroy()). The `paistiServices.stop()` call is removed since PUI.destroy() handles it.
- **Changed:** `makeui(fun, services)` → `makeui(fun)` (no services parameter)
- **Changed:** `newui()` no longer calls `paistiServices.bindUi()` or `startSharedServices()` in the uilock block — PUI handles this in its constructor

## Session Isolation (Future Multi-Session)

Each `PUI` instance owns its own `PaistiServices`, which in turn owns its own `EventBus`, `PluginService`, and `OverlayManager`. There is no global/static state for these services. When multi-session support is added:

- Each session gets its own `PUI` instance (active or headless)
- Each `PUI`'s services are independent
- The active session's `PUI` renders overlays; headless sessions skip the afterdraw registration
- A future `SessionManager` would track `PUI` instances

## What Stays Unchanged

- **EventBus** — same API, just called from PUI instead of UI
- **OverlayManager** — same API, screen overlays now rendered via AfterDraw (same visual result)
- **PluginService** — same API, same lifecycle
- **PaistiPlugin** — same base class, same accessors
- **DevToolsPlugin** — no changes
- **All overlay types** (ScreenOverlay, MapOverlay, MapOverlayBridge) — no changes
- **OptWnd.java** plugin panel — stays as-is (Ender UI code, separate concern)

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| `PUI.tick()` calling `drawafter()` every frame is slightly less efficient than a direct call in `draw()` | The overhead is negligible — `drawafter()` is a synchronized list add. Nurgling uses this same pattern. |
| Upstream might change `wdgmsg()`/`setGUI()`/`clearGUI()` signatures | These are stable public API methods in the Haven client; they haven't changed in years. If they do, the compile error is obvious. |
| `PaistiServices.start()` in PUI constructor runs before `env` is set on the UI (env is set by `newui()` after `makeui()` returns) | `start()` only loads plugins and reads config — it does not need the rendering environment. Map overlay attachment happens later in `setGUI()`. |
| Code that does `ui.services()` or `ui.eventBus()` will need to cast to PUI | Plugin code already accesses services via `PaistiPlugin.services()` (stored in constructor). Only 4 production call sites need updating. We add a static `PUI.of(UI)` helper for convenience. |
| Plugins reload on each reconnect (behavioral change from shared lifecycle) | This is intentional — gives a clean slate per session and avoids stale state. Plugin state that needs to persist across sessions should use config/filesystem. |
| `PaistiServices.stop()` must be called before `super.destroy()` to avoid accessing destroyed widgets | Stop order in `PUI.destroy()`: stop services first, clear UI ref, then destroy widget tree. |
