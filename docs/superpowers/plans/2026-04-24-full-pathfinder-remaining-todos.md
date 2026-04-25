# Full Pathfinder Remaining Work Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the completed persisted world map core into a usable end-to-end pathfinder that can observe the live scene, build walkability, search across chunks and portals, and execute/replan movement safely.

**Architecture:** Keep the existing `paisti.world` chunk/query/persistence core as the canonical backing store, then add four missing layers on top of it: genus-scoped service ownership, session-scoped observation/capture, search/query adapters including a live local overlay, and path execution/replanning. Persist only stable world knowledge; derive dynamic blockers from the live scene at query time.

**Tech Stack:** Java 11, Ant, JUnit 5, existing Haven client primitives (`MCache`, `Gob`, `Hitbox`, `Coord`, `Coord2d`), Paisti session classes (`PGameUI`, `PMapView`), filesystem persistence in `paisti.world.storage`.

---

## Current Baseline

Already implemented on `feature/world-persistence-core`:

- `src/paisti/world/ChunkData.java`
- `src/paisti/world/WorldMap.java`
- `src/paisti/world/MapUtil.java`
- `src/paisti/world/storage/StorageBackend.java`
- `src/paisti/world/storage/ChunkDataCodec.java`
- `src/paisti/world/storage/FileStorageBackend.java`
- unit coverage for chunk storage, packed lookup, persistence, and lifecycle under `test/unit/paisti/world/**`

What is still missing:

- no genus-scoped `WorldPersistence` service
- no session `CoordinateSystem`
- no observation pipeline from `PMapView` / `MCache` / `OCache`
- no tile classification into cell flags
- no gob-hitbox projection into chunk cells
- no portal discovery / pairing workflow
- no local live obstacle overlay for dynamic blockers
- no actual pathfinder/search classes
- no path execution / replanning / cancellation layer
- no UI or debugging tooling around pathfinding

---

## File Structure

### Existing files to extend

- `src/paisti/client/PGameUI.java`
  - Session attach/detach point for acquiring and releasing world/pathfinder services.
- `src/paisti/client/PMapView.java`
  - Best place for throttled observation capture from loaded grids and visible gobs.
- `src/haven/Hitbox.java`
  - Existing polygon/passable logic to reuse for gob-to-cell projection.
- `src/haven/MCache.java`
  - Tile IDs, grid IDs, grid coordinates, and tile-resource resolution API.
- `src/paisti/world/ChunkData.java`
  - Likely needs richer cell-flag constants, portal metadata, and safer mutation helpers.
- `src/paisti/world/WorldMap.java`
  - Needs observation application entry points and segment-level search helpers.

### New source files likely needed

- `src/paisti/world/WorldPersistence.java`
  - Genus-scoped top-level owner for `WorldMap`, storage backend, save scheduler, and pathfinder access.
- `src/paisti/world/WorldPersistenceRegistry.java`
  - Shared-instance registry keyed by genus so multiple sessions contribute to one world model.
- `src/paisti/world/CoordinateSystem.java`
  - Session-local conversion between session coords and persistent chunk/cell/world coords.
- `src/paisti/world/GridObservation.java`
  - Immutable snapshot of one observed grid in persistent coordinates.
- `src/paisti/world/GobSnapshot.java`
  - Immutable snapshot of a gob's resource, hitbox/passability, angle, and persistent world position.
- `src/paisti/world/TileFlagResolver.java`
  - Turns `MCache` tile IDs/resources into world cell flags.
- `src/paisti/world/GobCellProjector.java`
  - Projects hitboxes into affected cells and classifies static vs dynamic blockers.
- `src/paisti/world/PortalRecorder.java`
  - Detects, updates, pairs, and deduplicates portals using source object identity.
- `src/paisti/world/LocalObstacleGrid.java`
  - Temporary local overlay built from currently loaded grids and dynamic gobs.
- `src/paisti/world/CompositePathfindingMap.java`
  - Queries local live data first, falls back to persisted `WorldMap` outside loaded range.
- `src/paisti/world/Pathfinder.java`
  - Core graph search over packed cells and portals.
- `src/paisti/world/PathfinderRequest.java`
  - Search input: source, destination, movement mode, constraints.
- `src/paisti/world/PathfinderResult.java`
  - Path, failure reason, explored stats, used portals.
- `src/paisti/world/PathStep.java`
  - One step in the output route (walk cell range, portal traversal, final goal).
- `src/paisti/world/PathExecutor.java`
  - Converts route steps into client movement/actions, detects stalls, replans.
- `src/paisti/world/PathfinderDebugOverlay.java`
  - Visual debugging for blocked cells, explored bounds, current route, and portals.

### New tests likely needed

- `test/unit/paisti/world/CoordinateSystemTest.java`
- `test/unit/paisti/world/TileFlagResolverTest.java`
- `test/unit/paisti/world/GobCellProjectorTest.java`
- `test/unit/paisti/world/PortalRecorderTest.java`
- `test/unit/paisti/world/CompositePathfindingMapTest.java`
- `test/unit/paisti/world/PathfinderTest.java`
- `test/unit/paisti/world/PathExecutorTest.java`

---

## Remaining TODOs

### Task 1: Add Genus-Scoped Service Ownership

**Files:**
- Create: `src/paisti/world/WorldPersistence.java`
- Create: `src/paisti/world/WorldPersistenceRegistry.java`
- Modify: `src/paisti/client/PGameUI.java`

- [ ] Create a genus-scoped `WorldPersistence` wrapper around `WorldMap` and `StorageBackend`.
- [ ] Add lifecycle methods: initialize, periodic save, shutdown, and accessor(s) for map/pathfinder services.
- [ ] Add a static registry keyed by `GameUI.genus` so multiple sessions share one world model.
- [ ] Acquire the shared instance when a `PGameUI` attaches and release it when the session is destroyed.
- [ ] Ensure the shared instance survives one session disconnecting while another remains active.

### Task 2: Add Session Coordinate Calibration

**Files:**
- Create: `src/paisti/world/CoordinateSystem.java`
- Modify: `src/paisti/client/PGameUI.java`
- Test: `test/unit/paisti/world/CoordinateSystemTest.java`

- [ ] Add a session-local coordinate translator from session `gc` / tile / world coords into persistent coords.
- [ ] Calibrate from known `gridId -> chunkCoord` mappings already stored in `WorldMap`.
- [ ] Support first-session bootstrap when no persisted chunks exist yet.
- [ ] Keep persistent-space logic out of `WorldMap`; `WorldMap` should only receive already-converted observations.
- [ ] Add unit tests for calibration, conversions, and fresh-world bootstrap.

### Task 3: Capture Grid Observations From Live Session State

**Files:**
- Create: `src/paisti/world/GridObservation.java`
- Create: `src/paisti/world/GobSnapshot.java`
- Modify: `src/paisti/client/PMapView.java`
- Modify: `src/paisti/client/PGameUI.java`

- [ ] Add a throttled observation tick in `PMapView`.
- [ ] Snapshot loaded `MCache.Grid` instances: `gridId`, `gc`, raw tile IDs, neighbor grid IDs.
- [ ] Snapshot relevant gobs from `glob.oc` into immutable `GobSnapshot`s.
- [ ] Convert snapshot positions into persistent coordinates through `CoordinateSystem` before submitting them.
- [ ] Submit observations asynchronously to the shared world service rather than mutating world state on the UI thread.

### Task 4: Classify Terrain Into Cell Flags

**Files:**
- Create: `src/paisti/world/TileFlagResolver.java`
- Modify: `src/paisti/world/WorldMapConstants.java`
- Modify: `src/paisti/world/ChunkData.java`
- Modify: `src/paisti/world/WorldMap.java`
- Test: `test/unit/paisti/world/TileFlagResolverTest.java`

- [ ] Define the real cell-flag bit layout needed by the pathfinder: at minimum observed, impassable terrain, shallow water, deep water, high seas, static gob.
- [ ] Resolve tile IDs through `MCache.tileset(int)` / tile resources rather than hardcoding raw numeric IDs.
- [ ] Decide and encode pathfinder-facing semantics per movement mode (foot, boat, maybe minecart later).
- [ ] Add world-map mutation helpers for applying observed terrain to cells and clearing stale terrain-derived bits.
- [ ] Add tests for expected tile classes and for preserving unrelated flags when terrain is refreshed.

### Task 5: Project Static Gob Hitboxes Into Occupied Cells

**Files:**
- Create: `src/paisti/world/GobCellProjector.java`
- Modify: `src/paisti/world/WorldMap.java`
- Test: `test/unit/paisti/world/GobCellProjectorTest.java`

- [ ] Reuse `Hitbox.forGob(gob, ...)` / hitbox resource data to project blockers onto the 200x200 cell grid.
- [ ] Skip gobs whose hitboxes are passable (`Hitbox.passable()` semantics).
- [ ] Separate persisted static blockers from dynamic blockers. Persist buildings, walls, fences, placed objects; do not persist animals/players/vehicles.
- [ ] Start with conservative AABB-to-cell projection unless diagonal false positives prove unacceptable.
- [ ] Clear stale static-gob flags when an observed blocker disappears.

### Task 6: Record And Pair Portals Correctly

**Files:**
- Create: `src/paisti/world/PortalRecorder.java`
- Modify: `src/paisti/world/Portal.java`
- Modify: `src/paisti/world/ChunkData.java`
- Modify: `src/paisti/world/storage/ChunkDataCodec.java`
- Test: `test/unit/paisti/world/PortalRecorderTest.java`

- [ ] Extend portal identity so two different portal objects at the same cell are not collapsed accidentally.
- [ ] Store source object identity (e.g. gob id/resource-derived stable key) in the portal model or a paired metadata object.
- [ ] Implement observation-time detection for cellar doors, mineholes, ladders, stairs, doors, gates.
- [ ] Pair directed portals when both ends are known; avoid persisting unresolved portal destinations forever.
- [ ] Preserve the current identical-portal no-op behavior, but base “identical” on source object identity as well as type/source/destination.

### Task 7: Build A Live Local Obstacle Overlay

**Files:**
- Create: `src/paisti/world/LocalObstacleGrid.java`
- Create: `src/paisti/world/CompositePathfindingMap.java`
- Modify: `src/paisti/world/IPathfindingMap.java` only if truly necessary
- Test: `test/unit/paisti/world/CompositePathfindingMapTest.java`

- [ ] Build a short-lived local grid from currently loaded chunks/gobs for pathfinding near the player.
- [ ] Overlay dynamic blockers (players, animals, moving vehicles, temporary gate states) only in this local layer.
- [ ] Query local data first and fall back to persisted `WorldMap` outside the live loaded area.
- [ ] Keep the persisted world as the long-distance memory and the local overlay as the freshness layer.
- [ ] Add tests that prove local blockers override persisted state only within the covered area.

### Task 8: Implement The Search Algorithm

**Files:**
- Create: `src/paisti/world/Pathfinder.java`
- Create: `src/paisti/world/PathfinderRequest.java`
- Create: `src/paisti/world/PathfinderResult.java`
- Create: `src/paisti/world/PathStep.java`
- Test: `test/unit/paisti/world/PathfinderTest.java`

- [ ] Add A* (or another admissible search) over packed cells for hot-path node keys.
- [ ] Support chunk-crossing naturally via packed global cell representation.
- [ ] Treat portals as graph edges between source cell and target cell.
- [ ] Keep pathfinding policy out of `WorldMap`; interpret raw flags inside the pathfinder according to request movement mode.
- [ ] Add failure reasons for unknown territory, blocked goal, no route, and insufficient portal knowledge.

### Task 9: Turn Routes Into Client Actions

**Files:**
- Create: `src/paisti/world/PathExecutor.java`
- Modify: `src/paisti/client/PGameUI.java`
- Test: `test/unit/paisti/world/PathExecutorTest.java`

- [ ] Convert path steps into click/move/traverse commands the client can actually perform.
- [ ] Handle portal traversal actions separately from plain walking.
- [ ] Detect stalls, desync, or route invalidation and trigger replanning.
- [ ] Add cancellation and timeout behavior so a bad route does not soft-lock automation.
- [ ] Keep execution separate from search so the pathfinder can be reused for diagnostics and preview.

### Task 10: Add UI, Debugging, And Operational Safety

**Files:**
- Create: `src/paisti/world/PathfinderDebugOverlay.java`
- Modify: `src/paisti/client/PGameUI.java`
- Modify: `src/paisti/client/PMapView.java`

- [ ] Add a minimal UI entry point to request a path, cancel it, and expose failure reasons.
- [ ] Add a debug overlay to show blocked cells, portals, current route, and unknown/explored regions.
- [ ] Add cheap logging/telemetry around path search size, route length, and portal usage.
- [ ] Expose a manual save/load/debug action so pathfinder state can be inspected outside normal gameplay flow.

### Task 11: Harden Persistence And Long-Term Maintenance

**Files:**
- Modify: `src/paisti/world/storage/FileStorageBackend.java`
- Modify: `src/paisti/world/storage/ChunkDataCodec.java`
- Modify: `src/paisti/world/ChunkData.java`
- Test: `test/unit/paisti/world/storage/FileStorageBackendTest.java`

- [ ] Decide whether `ATOMIC_MOVE` should fall back to non-atomic replace on unsupported filesystems.
- [ ] Consider making `ChunkData` mutation less public so dirty tracking cannot be bypassed accidentally.
- [ ] Add regression tests for partial save failures (`saveChunk` throws mid-batch, `flush` throws after some saves).
- [ ] Document migration rules if portal identity or cell flag layout changes after initial deployment.

---

## Open Questions To Resolve Early

- How exactly should tile resource names be mapped to foot/boat movement semantics in this fork?
- Is conservative hitbox AABB blocking accurate enough, or do diagonal fences/walls require polygon-cell intersection?
- What heuristic should identify inside/cellar layers in this client fork?
- What is the canonical identity for portals: raw gob id, resource + position, or a richer stable key?
- Should the first full implementation support only foot travel, or foot + boat from day one?

---

## Recommended Execution Order

1. World service + coordinate system
2. Observation capture
3. Terrain classification
4. Static gob projection
5. Portal recording
6. Local live obstacle overlay
7. Search algorithm
8. Path execution + replanning
9. UI/debugging/hardening

This order keeps the work honest: get trustworthy world data first, then search, then movement.
