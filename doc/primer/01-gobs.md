# Gobs And World Objects

## What A Gob Is

A `Gob` is the client's in-world object type: players, animals, crops, machines, containers, vehicles, and scenery all end up represented as `haven.Gob`.

Core files:

- `src/haven/Gob.java`
- `src/haven/OCache.java`
- `src/haven/MapView.java`
- `src/haven/Drawable.java`
- `src/haven/ResDrawable.java`
- `src/haven/Composite.java`
- `src/haven/Composited.java`

## How Gobs Exist In Memory

The server streams object deltas into `OCache`. `OCache` creates or updates `Gob` instances by id, and each gob accumulates state as `GAttrib` components.

Important consequences:

- A gob is not one big monolithic class.
- A lot of gob behavior is attribute-driven.
- Rendering depends on which drawable-style attribute the gob currently has.

Typical cases:

- `ResDrawable` for resource-backed objects.
- `Composite` and `Composited` for pose/equipment/skeleton-based objects like players and animals.

## How A Gob Gets Rendered

`MapView` places gobs into the render tree. `Gob.placed` handles world placement, and `Gob.added(...)` then adds the gob's drawable, overlays, and any renderable attributes.

The important mental model is:

1. `OCache` owns live gob instances.
2. `MapView` decides which gobs are in the scene.
3. `Gob` contributes render nodes and overlays.

## Right-Click Menus

The client does not decide flower menu options on its own.

What the client does:

1. `MapView` hit-tests the click.
2. `ClickData` and gob click helpers encode which gob or sub-part was clicked.
3. `MapView` sends `wdgmsg("click", ...)` to the server.
4. The server later creates a `FlowerMenu` widget with the actual option strings.

Relevant files:

- `src/haven/ClickData.java`
- `src/haven/Clickable.java`
- `src/haven/FlowerMenu.java`
- `src/haven/MapView.java`

So if you ask "why does this gob have `Pick` or `Open` or `Giddyup!`?", the answer is usually server-side game logic, not client-side menu generation.

## Hitboxes

The fork's visible gob hitboxes are primarily reconstructed from resource layers, especially:

- `Resource.Neg`
- `Resource.Obstacle`

Relevant files:

- `src/haven/Hitbox.java`
- `src/haven/Resource.java`

That means the hitbox overlay is a client interpretation of resource data. It is useful, but it is not guaranteed to be a perfect copy of server-side collision rules.

## Native Client Behavior

Native Haven gives you the core model:

- gob identity and lifetime in `OCache`
- gob state in `Gob` and `GAttrib`
- render placement in `MapView`
- drawable resolution through `ResDrawable` or `Composite`
- click payload generation through `ClickData` and gob click helpers
- server-driven flower menus in `FlowerMenu`

## Ender Additions

This fork layers a lot onto gobs:

- semantic tags in `src/haven/GobTag.java`
- overlay/info text in `src/haven/GobInfo.java` and `src/haven/GeneralGobInfo.java`
- warning highlights in `src/haven/GobWarning.java`
- visible hitbox rendering in `src/haven/Hitbox.java`
- hidden-gob and marker logic inside `Gob.java`
- automation-facing heuristics used by `src/auto/**`

The big practical difference is that upstream Haven mostly cares about "can this gob be rendered and interacted with?" while this fork also cares about "can this gob be classified, highlighted, searched, automated, or annotated?"

## Gotchas

- A lot of Ender gob behavior is injected into native files, especially `Gob.java`, `OCache.java`, and `MapView.java`.
- Visible hitboxes are not the same thing as authoritative server collision.
- Right-click options are server-authored even though the click payload is client-authored.
- Clicks can target gob sub-parts, not just the whole gob.
