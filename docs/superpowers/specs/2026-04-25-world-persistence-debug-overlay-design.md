# World Persistence Debug Overlay Design

## Goal

Add a DevTools-only map overlay that visualizes persisted world-persistence terrain cells around the player. The overlay is a debugging aid for validating the recorded half-cell terrain flags before pathfinding consumes them.

## Scope

In scope:

- Render persisted terrain flags around the current player.
- Show only non-passable persisted cells.
- Keep the overlay behind the existing DevTools plugin and a `Ctrl+Shift` modifier gate.
- Use existing Paisti overlay infrastructure.

Out of scope:

- Pathfinder integration.
- Gob collision recording or visualization.
- Portal and neighbor graph visualization.
- Overlay settings UI or persistent config.
- Render caching or other performance optimization beyond straightforward bounds checks.

## Existing Context

World persistence currently stores one `ChunkData` per map grid. Each grid has `200x200` half-tile cells, with four persisted cells per normal `MCache` tile. `WorldPersistence.worldMap()` exposes the persisted `WorldMap`, and `WorldMap.getCellFlags(long fullChunkId, int cellX, int cellY)` returns persisted flags or `WorldMapConstants.INVALID_CELL_FLAGS` for missing chunks.

The plugin overlay system already supports screen-space map overlays through `MapOverlay.renderScreen(MapScreenOverlayContext ctx)`. `MapScreenOverlayContext.worldToScreen(Coord3f world)` projects world coordinates through the active `MapView`. `DevToolsPluginSceneOverlay` already uses `Ctrl+Shift` as the DevTools debug gate.

## Approach

Implement the overlay as a new screen-space `MapOverlay` registered by `DevToolsPlugin`. It will project terrain cell corners above the current terrain surface and draw translucent screen polygons with `GOut.drawp(...)`.

This avoids introducing render-tree mesh state for a developer-only visualization while still validating the important properties: stored cell location, cell size, and flag classification.

## Components

### `DevToolsWorldPersistenceOverlay`

Create `src/paisti/plugin/DevToolsPlugin/DevToolsWorldPersistenceOverlay.java`.

Responsibilities:

- Return early unless `ctx.ui().modctrl` and `ctx.ui().modshift` are both true.
- Return early unless `ctx.gui()` is a `PGameUI`, `ctx.map()` is non-null, and the map has a player gob.
- Get `WorldPersistence` from `PGameUI.worldPersistence()`.
- Sample a `50x50` tile square centered on the player's tile coordinate.
- For each tile, inspect the four half-cells represented by that tile.
- Skip cells with flags `0` or `WorldMapConstants.INVALID_CELL_FLAGS`.
- Draw each interesting cell as a translucent projected quad.

### Coordinate Helper

Add a small testable helper in the overlay class or a package-private helper class under the same package.

Responsibilities:

- Convert a world tile coordinate to the owning map-grid coordinate using `tile.div(MCache.cmaps)`, which uses floor division and is safe for negative coordinates.
- Convert the tile coordinate into local grid tile coordinates by subtracting `grid.gc.mul(MCache.cmaps)`.
- Convert local grid tile coordinates to half-cell coordinates with `cellX = localTileX * 2 + halfX` and `cellY = localTileY * 2 + halfY`.
- Define the world-space bounds of each half-cell as half of `MCache.tilesz` in x and y.

Keeping this mapping isolated makes the most important correctness logic unit-testable without rendering.

### `DevToolsPlugin`

Modify `src/paisti/plugin/DevToolsPlugin/DevToolsPlugin.java`.

Responsibilities:

- Add a `DevToolsWorldPersistenceOverlay` field.
- Register it in `startUp()` with the other DevTools overlays.
- Let existing `unregisterAll(this)` dispose it during shutdown.

## Rendering Details

The overlay draws screen-space polygons rather than world meshes.

For each interesting persisted half-cell:

1. Calculate the four world-space corners in map coordinates.
2. Query terrain position per corner through `ctx.map().glob.map.getzp(cornerCoord2d)`.
3. Add a small positive z offset, for example `2.0f`, before projection.
4. Project corners with `ctx.worldToScreen(...)`.
5. Skip the cell if any corner cannot be projected.
6. Set color with `GOut.chcolor(...)`.
7. Draw the quad with `Model.Mode.TRIANGLE_FAN` and reset color afterward.

Per-corner terrain height keeps the overlay close to sloped terrain. The positive z offset avoids clipping or z-fighting with the ground.

## Colors

Use simple translucent colors:

- `CELL_DEEP_WATER`: blue, higher priority than generic blocked terrain.
- `CELL_BLOCKED_TERRAIN`: red.
- `CELL_OBSERVED`: amber, only if it appears without terrain flags in future data.

The overlay must draw only interesting cells. A persisted passable cell with flags `0` is not rendered.

## Error Handling

- Missing `WorldPersistence`: return without drawing.
- Missing persisted chunk: skip cells with `INVALID_CELL_FLAGS`.
- `haven.Loading` from map/grid/height lookups: let the overlay manager catch it, matching existing overlay behavior.
- Unexpected exceptions: let `OverlayManager` apply its repeated-failure disabling behavior.

## Performance

The first implementation samples at most `50x50` tiles and `100x100` half-cells each frame while `Ctrl+Shift` is held. This is acceptable for DevTools-only debugging and avoids premature caching. If it proves too heavy later, add a short-lived cache keyed by player tile and map-change sequence.

## Tests

Unit tests should cover the coordinate and filtering logic:

- A tile at a grid origin maps to local cells `(0,0)`, `(1,0)`, `(0,1)`, `(1,1)`.
- A tile at local `(99,99)` maps to cells `(198,198)`, `(199,198)`, `(198,199)`, `(199,199)`.
- Flags `0` and `INVALID_CELL_FLAGS` are not interesting.
- `CELL_BLOCKED_TERRAIN`, `CELL_DEEP_WATER`, and future observed-only cells are interesting.

Verification commands:

- `ant test-unit -buildfile build.xml`
- `ant bin -buildfile build.xml`

## Open Decisions

No open decisions remain for this slice. The overlay is `Ctrl+Shift` gated while DevTools is enabled.
