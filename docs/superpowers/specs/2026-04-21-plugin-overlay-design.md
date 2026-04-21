# Plugin Overlay System Design

**Goal:** Add a plugin-owned overlay system for `PaistiPlugin` that supports both full-screen UI overlays and world-aware map overlays without forcing individual plugins to manage render-tree attachment details.

## Scope

This design covers overlay registration, lifecycle, render hooks, and the minimal plugin-facing API needed to support debug text, labels, bounding boxes, and simple scene geometry.

In scope:

- a new `OverlayManager` service under `PaistiServices`
- plugin registration and cleanup for overlays
- support for both screen overlays and map overlays from day one
- a map overlay bridge that can render both world-space geometry and projected screen-space elements
- ordering, disposal, and failure isolation for overlays

Out of scope:

- external plugin discovery
- a user-facing overlay configuration UI
- a general-purpose retained-mode scene graph for plugins
- deep threading changes or background render preparation infrastructure

## Current Behavior

The new plugin system currently only manages plugin lifecycle, but the surrounding service lifetime has changed since this spec was first written.

- `src/haven/PaistiServices.java`
- `src/paisti/pluginv2/PluginService.java`
- `src/paisti/pluginv2/PaistiPlugin.java`

`PaistiServices` is now a shared service container that outlives individual `UI` instances and tracks the current UI through `bindUi(...)` and `clearUi(...)`.

- `src/haven/PaistiServices.java`
- `src/haven/GLPanel.java`
- `test/unit/haven/PaistiServicesLifetimeTest.java`

That matters for overlays because registrations should survive UI swaps and relog-style UI recreation, while map attachment and screen rendering should follow whichever `UI` is currently bound.

The client already exposes two separate rendering lanes that overlays can build on:

- `UI.drawafter(...)` draws after the widget tree and is suitable for full-screen 2D overlays
  - `src/haven/UI.java`
- `MapView.drawadd(...)` attaches extra render nodes into map rendering
  - `src/haven/MapView.java`
- `PView.Render2D` supports screen-space drawing using map render state, which is how text and icons above world objects are already rendered
  - `src/haven/PView.java`

Relevant existing examples:

- `src/me/ender/gob/GobCombatInfo.java` for world-aware screen-space labels and icons
- `src/haven/ProspectingWnd.java` for map-attached scene geometry

## Chosen Approach

Add a single `OverlayManager` service, but expose two plugin-facing overlay interfaces instead of one generic `render()` method.

Why this approach:

- it matches the engine's real render split instead of hiding it behind a weak abstraction
- it lets plugins support both UI and world overlays from the start
- it keeps raw `RenderTree.Slot` and map-attachment logic out of plugin code
- it centralizes cleanup and ordering
- it is the smallest design that still handles `MapView` replacement and UI recreation correctly

Rejected alternatives:

- one bare `render()` method: too little context for both screen and map overlays
- one fully generic overlay context with no lane split: cleaner on paper, but internally still leaks the two render paths
- letting each plugin call `MapView.drawadd(...)` directly: duplicates lifecycle handling and makes cleanup fragile

## Architecture

### Service Placement

Add `OverlayManager` to `PaistiServices` alongside `PluginService`.

`PaistiPlugin` should gain a convenience accessor:

- `protected OverlayManager overlayManager()`

This keeps overlay registration in the same service container already used by plugin code.

Because `PaistiServices` is now shared across UI swaps, `OverlayManager` should use `services.ui()` as its live UI pointer and must not capture a `UI` permanently during construction.

### Ownership Model

Plugins own overlays, but overlays do not extend `PaistiPlugin`.

Rationale:

- one plugin may want multiple overlays
- plugin lifecycle and render behavior should remain separate concerns
- overlays should be disposable render units, not mini-plugins

### Render Lanes

Support two overlay lanes:

- `ScreenOverlay`
- `MapOverlay`

`ScreenOverlay` is for full-screen 2D rendering after normal widgets.

`MapOverlay` supports two optional phases:

- `renderWorld(...)` for scene-space geometry
- `renderScreen(...)` for screen-space drawing anchored to map render state

This mirrors the existing client patterns:

- world-space scene injection through `MapView.drawadd(...)`
- screen-space projection through `PView.Render2D`

## Plugin-Facing API

### Base Overlay Contract

Recommended shape:

```java
public interface PluginOverlay {
    default String id() { return getClass().getName(); }
    default int priority() { return 0; }
    default boolean enabled() { return true; }
    default void dispose() {}
}
```

### Screen Overlay Contract

```java
public interface ScreenOverlay extends PluginOverlay {
    void render(ScreenOverlayContext ctx);
}
```

### Map Overlay Contract

```java
public interface MapOverlay extends PluginOverlay {
    default void renderWorld(MapWorldOverlayContext ctx) {}
    default void renderScreen(MapScreenOverlayContext ctx) {}
}
```

### Registration API

Recommended manager surface:

```java
public final class OverlayManager {
    OverlayRegistration register(PaistiPlugin owner, PluginOverlay overlay);
    void unregister(PluginOverlay overlay);
    void unregisterAll(PaistiPlugin owner);

    Collection<ScreenOverlay> screenOverlays();
    Collection<MapOverlay> mapOverlays();
}
```

Behavior rules:

- `register(...)` associates the overlay with its owning plugin
- `unregisterAll(plugin)` removes every overlay registered by that plugin
- `dispose()` is called when an overlay is unregistered
- overlays are rendered only when `enabled()` returns `true`

`OverlayRegistration` can exist as a convenience handle, but plugin shutdown must not depend on the plugin keeping that handle alive. `unregisterAll(plugin)` is the required safe path.

## Render Contexts

Use small practical context objects instead of forcing overlays to reach into global state.

### ScreenOverlayContext

```java
public final class ScreenOverlayContext {
    public UI ui();
    public GOut g();
    public Coord mouse();
    public Coord size();
}
```

### MapWorldOverlayContext

```java
public final class MapWorldOverlayContext {
    public UI ui();
    public GameUI gui();
    public MapView map();
}
```

### MapScreenOverlayContext

```java
public final class MapScreenOverlayContext {
    public UI ui();
    public GameUI gui();
    public MapView map();
    public GOut g();
    public Pipe state();
    public Coord worldToScreen(Coord3f world);
}
```

Context design goals:

- provide the exact render-state inputs plugins actually need
- avoid exposing raw `RenderTree.Slot` or manager internals
- keep map projection helper logic in one place

## Runtime Responsibilities

### OverlayManager

`OverlayManager` is responsible for:

- storing registered overlays grouped by owner plugin
- iterating overlays in stable priority order
- attaching and detaching the map bridge node as the active `MapView` changes
- providing the single screen overlay bridge for UI drawing
- disposing overlays during unregister and shutdown
- isolating per-overlay render failures

Suggested internal state:

```java
final class OverlayManager {
    private final List<RegisteredOverlay> overlays;
    private MapView attachedMap;
    private RenderTree.Slot mapSlot;
    private final MapOverlayBridge mapBridge;
}
```

Use `CopyOnWriteArrayList` or an equivalent snapshot-friendly structure so overlays can be iterated safely without complex synchronization during rendering.

### Screen Overlay Bridge

The manager should own the screen overlay call site.

Use one explicit `overlayManager.renderScreenOverlays(...)` call in `UI.draw(...)` after the existing `afterdraws` loop completes.

Rationale:

- it keeps all plugin overlay drawing in one stable place
- it guarantees screen overlays render after normal widgets and after existing ad hoc `drawafter(...)` users
- it is easier to reason about than re-scheduling a manager-owned `ui.drawafter(...)` callback every frame

### Map Overlay Bridge

The manager should own one map bridge node per active `MapView`, not one raw `drawadd(...)` slot per overlay.

That bridge should implement both:

- `RenderTree.Node`
- `PView.Render2D`

Responsibilities:

- attach to the current map through `MapView.drawadd(...)`
- invoke `renderWorld(...)` for registered `MapOverlay`s in the world phase
- invoke `renderScreen(...)` for registered `MapOverlay`s in the screen phase

This centralizes all render-tree lifecycle handling in one place.

## Lifecycle Rules

### Shared Service Lifetime

`OverlayManager` should share the lifetime of the shared `PaistiServices` instance, not the lifetime of one specific `UI`.

That means:

- plugins may register overlays before `GameUI` or `MapView` exists
- overlays must start rendering automatically once the currently bound UI exposes a map
- overlay registrations should survive UI swaps while shared services remain started
- all overlays must be disposed when shared services stop

### UI Binding

The active `UI` should be discovered through `services.ui()`, which is maintained by `PaistiServices.bindUi(...)` and `PaistiServices.clearUi(...)`.

Implications:

- screen overlay rendering must tolerate `services.ui()` being `null`
- map attachment must re-resolve the active `MapView` from the currently bound UI instead of assuming a single UI lifetime
- UI destruction should not itself clear overlay registrations; only shared-service shutdown or explicit plugin unregister should do that

### MapView Attachment

The current `MapView` should be resolved lazily from `ui.gui.map` during render or attachment checks rather than cached forever.

When the active `MapView` instance changes:

1. remove the old manager-owned `RenderTree.Slot`
2. attach the manager bridge to the new map with `drawadd(...)`
3. continue rendering registered overlays without requiring plugin re-registration

This handles relog, session recreation, widget replacement, and the now-tested shared-service UI swap behavior.

### Plugin Shutdown

Plugins should register overlays in `startUp()` and remove them in `shutDown()`.

Expected shutdown flow:

1. plugin calls `overlayManager.unregisterAll(this)`
2. manager removes every owned overlay
3. manager calls `dispose()` on each removed overlay

The manager should also tolerate duplicate or late unregister calls safely.

## Ordering

Render order should be deterministic.

Recommended rule:

- sort by `priority()` ascending
- when priorities match, preserve registration order

This means higher priority overlays render later and therefore appear on top.

The same rule should apply consistently to:

- `ScreenOverlay.render(...)`
- `MapOverlay.renderWorld(...)`
- `MapOverlay.renderScreen(...)`

## Error Handling

Overlay failures must not break the whole frame.

For each overlay callback:

- wrap invocation in `try/catch`
- log the owner plugin name and overlay id
- skip only the failing overlay for that frame

Recommended additional behavior:

- track consecutive failures per overlay
- auto-disable an overlay after 5 consecutive render failures and log that it was disabled

This is especially important for render code because an exception can otherwise spam every frame indefinitely.

## Performance And Safety Constraints

Overlay callbacks should be treated as render-thread code.

Required expectations for plugin authors:

- do not block
- do not perform expensive full-world scans every frame unless the overlay is explicitly lightweight enough
- do not mutate overlay registration while relying on immediate visibility in the current pass
- do not assume `GameUI` or `MapView` is always present

Allowed responsibilities for overlays:

- cache rendered text or textures
- maintain lightweight filtered target lists
- dispose cached resources in `dispose()`

Not part of this design:

- background jobs for overlay preparation
- a separate retained overlay scene model

## Example Usage

Plugin code:

```java
public class DevToolsPlugin extends PaistiPlugin {
    private final DevToolsPluginSceneOverlay sceneOverlay = new DevToolsPluginSceneOverlay();

    public DevToolsPlugin(PaistiServices services) {
        super(services);
    }

    @Override
    public void startUp() {
        overlayManager().register(this, sceneOverlay);
    }

    @Override
    public void shutDown() {
        overlayManager().unregisterAll(this);
    }
}
```

Overlay code:

```java
public class DevToolsPluginSceneOverlay implements MapOverlay {
    @Override
    public void renderScreen(MapScreenOverlayContext ctx) {
        // draw labels, text, and projected bounds above gobs
    }

    @Override
    public void renderWorld(MapWorldOverlayContext ctx) {
        // optional world-space geometry
    }
}
```

This is intentionally enough structure to support the desired API shape without exposing raw rendering attachment details to every plugin.

## Files Expected To Change

- `src/haven/PaistiServices.java`
- `src/paisti/pluginv2/PaistiPlugin.java`
- new overlay manager and overlay interfaces under `src/paisti/pluginv2/` or a closely related package
- one stable screen-overlay draw hook in `src/haven/UI.java`
- map bridge integration using `src/haven/MapView.java`
- `test/unit/haven/PaistiServicesLifetimeTest.java`
- `test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java`

Possible support file if the tests benefit from extracted helpers:

- `test/support/paisti/pluginv2/testing/*.java`

Probable new files:

- `OverlayManager.java`
- `PluginOverlay.java`
- `ScreenOverlay.java`
- `MapOverlay.java`
- `ScreenOverlayContext.java`
- `MapWorldOverlayContext.java`
- `MapScreenOverlayContext.java`
- a small internal `MapOverlayBridge.java`

## Testing Strategy

This repo now has a JUnit 5 harness wired through Ant:

- unit tests live under `test/unit`
- shared test helpers live under `test/support`
- `build.xml` provides `compile-test`, `test-unit`, and `test` targets backed by `junitlauncher`

Use that harness for all logic that does not require a live rendered client.

Automate at least:

1. API exposure for `OverlayManager` and overlay contracts
2. owner-scoped unregister and disposal
3. screen overlay ordering by priority then registration order
4. repeated overlay failure handling and auto-disable behavior
5. map bridge type contract and manager dispatch ordering
6. shared-service lifecycle expectations such as overlay cleanup on `PaistiServices.stop()` and registration survival across UI swaps when services stay alive

Manual verification is still required for actual on-screen rendering behavior, because the JUnit harness does not exercise a live game session or real draw output.

Minimum useful manual checks:

1. enable a plugin that registers a `ScreenOverlay` and confirm it draws after normal widgets
2. enable a plugin that registers a `MapOverlay` and confirm it renders over the world
3. confirm map overlays begin rendering even if the plugin started before `GameUI` was ready
4. relog or recreate the session and confirm overlays continue rendering without re-registering
5. disable the plugin and confirm overlays disappear and resources are cleaned up
6. force an overlay exception and confirm only that overlay fails while the client keeps rendering

Minimum automated verification:

- `ant test-unit -buildfile build.xml`

Minimum build verification:

- `ant bin -buildfile build.xml`

## Risks

- a map bridge that caches the wrong `MapView` instance will silently stop rendering after UI or session changes
- plugin authors may write overlays that do too much work every frame
- repeated exceptions from a broken overlay can flood logs if failure handling is too weak
- world-space geometry and screen-space projected drawing have different constraints, which can confuse plugin authors if the docs are vague

## Mitigations

- keep all `MapView` attachment logic inside `OverlayManager`
- expose separate `renderWorld(...)` and `renderScreen(...)` methods rather than one ambiguous callback
- document render-thread expectations clearly
- wrap every overlay callback independently and track repeated failures
- require overlays to own and dispose their cached resources

## Open Decisions Resolved

- support both UI overlays and map overlays from day one: yes
- let overlays subclass `PaistiPlugin`: no
- expose raw `RenderTree.Slot` to overlays: no
- use one manager-owned map bridge instead of one slot per overlay: yes
- support both world-space geometry and projected screen-space drawing for map overlays: yes
