# WorldPersistence Design Spec

**Goal:** Build a genus-scoped persistence service (`WorldPersistence`) with a spatial map layer (`WorldMap`) that stores chunk walkability data, handles segment stitching, provides a unified coordinate system, and exposes an `IPathfindingMap` interface for pathfinder consumption.

**Architecture:** WorldPersistence is the top-level service scoped to a server genus, shared across sessions on the same server. Its `.map` field is a `WorldMap` that manages spatial data as segments of chunks in persistent global coordinates. WorldMap is session-agnostic — it only receives observations already converted to persistent coordinates. Each game session owns a `CoordinateSystem` that translates between its session coordinates and persistent coordinates. The client converts observations to persistent coords, then submits them to the shared WorldMap. A `StorageBackend` interface abstracts persistence (filesystem initially, remote sync later).

**Target codebase:** `paisti-ender-fork` (Ender's Haven client fork with Paisti plugin layer)

**Tech Stack:** Java 11+ (Ant build), `haven.Coord`/`Coord2d`, `haven.Hitbox`, binary serialization for chunks, JSON for manifests.

**Package:** `paisti.world` (new), `paisti.world.storage` (new)

**Not in scope:** Pathfinder algorithm, local scene grid builder (CompositePathfindingMap), remote sync implementation, village/non-spatial data.

---

## Target Codebase Context

| Concept | Class | Notes |
|---------|-------|-------|
| Game UI | `PGameUI extends GameUI` | Has `genus` field. Custom lifecycle hooks go here. |
| Map view | `PMapView extends MapView` | Tick loop for observation submission. |
| Map cache | `haven.MCache` | 3x3 grids, `Grid.id`/`gc`/`ul`/`tiles`. `cmaps=(100,100)`, `tilesz=(11,11)`. |
| Gobs | `haven.Gob` | `rc` (Coord2d), `a` (angle), `id`, `tags` (Set\<GobTag\>). |
| Hitboxes | `haven.Hitbox` | Polygon-based from Resource.Neg/Obstacle. Cached per-Resource. `Hitbox.forGob(gob)` factory. `passable()` for gates/doors. |
| Gob iteration | `haven.OCache` | `Iterable<Gob>`. `glob.oc` from GameUI. |
| Per-server storage | `Config.genusFile(name, genus)` | Files in `world-{genus}/` directory. |
| Service container | `PaistiServices` | EventBus, PluginService, OverlayManager. |
| Map segments | `haven.MapFile` | Existing segment stitching for visual map tiles. |
| Existing pathfinding | None | No client-side pathfinding exists. |

---

## File Structure

```
src/paisti/world/
  WorldPersistence.java         — Top-level service, genus-scoped, shared across sessions
  WorldMap.java                 — Spatial data: segments, chunks, observations, stitching
  WorldMapConstants.java        — Shared constants (tile sizes, cell counts)
  MapSegment.java               — A connected region of chunks with a coordinate space
  ChunkData.java                — Per-chunk walkability, portals, metadata
  Portal.java                   — Portal connecting two chunks (door, mine, cellar)
  GridObservation.java          — Immutable scene snapshot (in persistent coordinates)
  GobSnapshot.java              — Immutable gob state captured at observation time
  CoordinateSystem.java         — Per-session: session <-> persistent coordinate translation
  IPathfindingMap.java          — Read-only query interface for pathfinder
  SegmentPathfindingMap.java    — Adapts MapSegment to IPathfindingMap
  storage/
    StorageBackend.java         — Persistence interface (file, SQLite, remote)
    FileStorageBackend.java     — File-per-chunk + JSON manifest implementation
```

---

## Constants

`WorldMapConstants` — shared numeric constants:

| Constant | Value | Derivation |
|----------|-------|------------|
| `CHUNK_SIZE_TILES` | 100 | Matches `MCache.cmaps` |
| `CELLS_PER_TILE` | 2 | Half-tile resolution |
| `CELLS_PER_CHUNK` | 200 | 100 * 2 |
| `TILE_SIZE_WORLD` | 11.0 | Matches `MCache.tilesz` |
| `CELL_SIZE_WORLD` | 5.5 | 11.0 / 2 |
| `VISIBLE_RADIUS_CELLS` | 50 | ~25 tiles, approximate visible range |

### Cell flags (bitfield stored in `byte[][] cells`)

Each cell is a single byte. Flags are combined via bitwise OR. A zero byte means walkable observed land.

| Flag | Value | Description |
|------|-------|-------------|
| `OBSERVED` | `1 << 0` | Cell has been seen. Unset = unknown territory. |
| `IMPASSABLE_TERRAIN` | `1 << 1` | Cave walls, rocks, nil/void tiles |
| `SHALLOW_WATER` | `1 << 2` | Wading depth — walkable on foot, relevant for routing |
| `DEEP_WATER` | `1 << 3` | Requires a boat |
| `HIGH_SEAS` | `1 << 4` | Requires an ocean-worthy vessel |
| `STATIC_GOB` | `1 << 5` | Blocked by building, wall, fence, placed object |
| *(2 bits reserved)* | `1 << 6`, `1 << 7` | Available for future use |

**Querying walkability from flags:**
- "Can I walk here on foot?": `(flags & (IMPASSABLE_TERRAIN | DEEP_WATER | HIGH_SEAS | STATIC_GOB)) == 0`
- "Can I boat here?": `(flags & (IMPASSABLE_TERRAIN | HIGH_SEAS | STATIC_GOB)) == 0`
- "Is this explored?": `(flags & OBSERVED) != 0`
- "Is this unknown?": `(flags & OBSERVED) == 0`

**Why `byte[][]` and not multiple BitSets:** All flags for a cell are co-located in one byte. The pathfinder's A* hot loop reads one byte per cell — one memory access and one bitmask op. Multiple BitSets would scatter reads across separate `long[]` arrays, causing cache misses. The 40KB per chunk cost (200x200 bytes) is negligible.

**Dynamic gobs are NOT persisted.** Animals, players, and other moving objects are handled by the live CompositePathfindingMap overlay at query time. Persisting their positions would create stale data.

---

## Data Model

### ChunkData

Per-chunk persistent state. Identified by `gridId` (server-assigned, persistent across sessions). Positioned within a segment by `chunkCoord`.

| Field | Type | Description |
|-------|------|-------------|
| `gridId` | `long` | Server-assigned grid ID (immutable, persistent) |
| `segmentId` | `long` | Which segment this chunk belongs to (mutable on merge) |
| `chunkCoord` | `Coord` | Persistent coordinate within segment's space (mutable on merge) |
| `cells` | `byte[200][200]` | Bitflag per cell (see Cell Flags in Constants section) |
| `portals` | `List<Portal>` | Doors, mine entrances, stairs, cellars |
| `layer` | `String` | "outside", "inside", "cellar" |
| `lastUpdated` | `long` | Epoch millis |
| `version` | `long` | Monotonic counter for sync conflict resolution |
| `dirty` | `boolean` (transient) | Needs persistence |

Defaults: all cells initialize to `0` (no flags set = unobserved). When a cell is first seen, `OBSERVED` is set along with any applicable terrain/obstacle flags. An observed cell with only the `OBSERVED` flag set is walkable land.

### Portal

A connection between two chunks (e.g., door into a building, mine entrance, cellar hatch).

| Field | Type | Description |
|-------|------|-------------|
| `localCell` | `Coord` | Position within this chunk (0-199) |
| `type` | `String` | "door", "cellar", "mine", "stairs" |
| `targetGridId` | `long` | Grid on the other side (-1 if unknown) |
| `targetLocalCell` | `Coord` | Position in target chunk (null if unknown) |

### GobSnapshot

Immutable snapshot of a gob captured on the game thread for background processing.

| Field | Type | Description |
|-------|------|-------------|
| `gobId` | `long` | Gob ID |
| `position` | `Coord2d` | World units (`gob.rc`) |
| `angle` | `double` | Rotation in radians (`gob.a`) |
| `hitboxVertices` | `Coord2d[][]` | Polygon vertices from `Hitbox.forGob()` |
| `resourceName` | `String` | Resource name for classification |
| `passable` | `boolean` | From `Hitbox.passable()` — true for open gates, doors, etc. |

Note: Uses `Hitbox.forGob()` polygon vertices directly, not axis-aligned bounding boxes. The hitbox projection onto the cell grid should use polygon-cell intersection (or the circumscribed AABB of the polygon as a conservative approximation).

### GridObservation

Immutable snapshot of a single MCache grid's observable state. Created on the game thread, **already converted to persistent coordinates** by the session's CoordinateSystem before submission to WorldMap.

| Field | Type | Description |
|-------|------|-------------|
| `gridId` | `long` | `MCache.Grid.id` |
| `persistentChunkCoord` | `Coord` | Persistent chunk coordinate (converted from session gc by CoordinateSystem) |
| `tileTypes` | `int[]` | Raw tile type IDs (100*100) from `Grid.tiles` |
| `gobs` | `List<GobSnapshot>` | All gobs with hitboxes visible in/near this grid |
| `playerWorldPos` | `Coord2d` | Player position in **persistent** world units at capture time |
| `neighborGridIds` | `Map<Direction, Long>` | Adjacent grid IDs discovered from MCache |
| `detectedLayer` | `String` | "outside", "inside", "cellar" |

All fields immutable. `Direction` enum: `NORTH, SOUTH, EAST, WEST`.

WorldMap receives observations in persistent coordinates only — it has no awareness of session coordinate spaces.

---

## MapSegment

A connected region of chunks sharing a persistent coordinate space. Chunks are positioned by `chunkCoord` within the segment. Adjacent chunks at `(x, 0)` and `(x+1, 0)` share a border.

**Global cell coordinate mapping:**
```
globalCellX = chunkCoord.x * CELLS_PER_CHUNK + localCellX
globalCellY = chunkCoord.y * CELLS_PER_CHUNK + localCellY
```

**Key operations:**
- `getCellFlags(globalCellX, globalCellY)` — resolves chunk via `floorDiv(coord, 200)`, local cell via `floorMod(coord, 200)`. Returns the raw flag byte. Chunk boundaries are transparent.
- `absorb(other, offset)` — merge another segment into this one. Re-offsets all absorbed chunks' coordinates by `offset`, updates their `segmentId`. Returns list of moved gridIds.

**Concurrency:** `ConcurrentHashMap` for both `chunksByGridId` and `chunksByCoord` maps, supporting lock-free reads from the pathfinder while the background thread writes.

---

## CoordinateSystem

**Per-session object.** Each game session creates its own CoordinateSystem instance. It translates between that session's coordinates and persistent coordinates. The relationship is a single offset:

```
persistentChunkCoord = sessionGc + sessionOffset
```

### Ownership

CoordinateSystem is **not** owned by WorldMap or WorldPersistence. It lives at the session level (e.g., on PGameUI or a per-session service). The session is responsible for:
1. Creating a CoordinateSystem on connect
2. Calibrating it as grids load
3. Using it to convert observations to persistent coordinates before submitting to WorldMap
4. Providing it to the pathfinder for converting results back to session coordinates

WorldMap and WorldPersistence never touch session coordinates.

### Calibration

On session start, `sessionOffset` is null. As grids load:
1. Check each `gridId` against `knownGridPositions` (obtained from WorldMap's stored chunks on startup)
2. If recognized: `sessionOffset = knownPersistentCoord - sessionGc`. Done.
3. If no grids recognized and this is a fresh world: first grid becomes `(0,0)`, offset = `(0,0) - sessionGc`

Once calibrated, the offset is fixed for the entire session. Newly discovered grids register their persistent position (via WorldMap) for future session calibration redundancy.

### Multiple sessions

Two sessions on the same server will have different CoordinateSystem instances with different offsets, but both map to the same persistent coordinate space. Session A at offset `(-3, 1)` and session B at offset `(5, -2)` both produce the same persistent coordinates for the same physical grid — that's the invariant.

### Conversion methods

| Method | Formula |
|--------|---------|
| `toPersistentChunk(sessionGc)` | `sessionGc + offset` |
| `toSessionChunk(persistentCoord)` | `persistentCoord - offset` |
| `toPersistentTile(sessionTile)` | `sessionTile + (offset * CHUNK_SIZE_TILES)` |
| `toPersistentCell(sessionCell)` | `sessionCell + (offset * CELLS_PER_CHUNK)` |
| `toPersistentWorld(sessionWorld)` | `sessionWorld + (offset * CHUNK_SIZE_TILES * TILE_SIZE_WORLD)` |

### Static helpers (no session state needed)

| Method | Formula |
|--------|---------|
| `worldToTile(Coord2d)` | `floor(world / 11.0)` |
| `worldToCell(Coord2d)` | `floor(world / 5.5)` |
| `tileToChunk(Coord)` | `floorDiv(tile, 100)` |

### Session lifecycle

### Session lifecycle

Constructed on session connect, discarded on disconnect. `knownGridPositions` is populated from WorldMap's stored chunk data at construction time — not persisted separately.

---

## StorageBackend

Interface for pluggable persistence. Designed for replaceability (file → SQLite → remote).

### Methods

| Method | Purpose |
|--------|---------|
| `loadAll() → StorageSnapshot` | Load everything on startup. Returns segments with their chunks. |
| `saveChunk(ChunkData)` | Persist a single dirty chunk. Idempotent. |
| `deleteChunk(segmentId, gridId)` | Remove a chunk from storage. |
| `onSegmentsMerged(keptId, removedId, offset, movedGridIds)` | Notify that a merge occurred. Backend updates its index. |
| `flush()` | Flush buffered writes to durable storage. |
| `close()` | Clean up resources. |

### StorageSnapshot

Returned by `loadAll()`:
```
Map<Long, List<StoredChunk>> segmentChunks
```
Where `StoredChunk` = `(gridId, segmentId, chunkCoord, ChunkData)`.

### Remote sync considerations

- `ChunkData.version` enables last-writer-wins conflict resolution
- `onSegmentsMerged()` is an explicit event a remote backend can propagate as a single operation
- `saveChunk()` is granular enough to push individual updates
- A future `RemoteStorageBackend` would sync on `loadAll()` (pull), then push on `saveChunk()` (debounced)

### FileStorageBackend

Initial implementation. Storage layout:
```
world-{genus}/world/
  manifest.json          — segment index: {segmentId -> {gridId -> chunkCoord}}
  chunks/
    {gridId}.bin         — binary chunk data
```

**Manifest** is the authoritative source for segment membership and chunk coordinates. Chunk binary files store the walkability grid, portals, and metadata. Atomic writes via temp file + rename.

**Segment merges** only update the manifest (chunk files don't move — only their segment assignment and coordinates change).

**Chunk binary format:**
```
[4B]  format version (int)
[8B]  gridId (long)
[8B]  segmentId (long)
[4B]  chunkCoord.x (int)
[4B]  chunkCoord.y (int)
[8B]  lastUpdated (long)
[8B]  version (long)
[var] layer (UTF-8 string, length-prefixed)
[40000B] cells (200*200 bytes, row-major — raw flag bytes)
[4B]  portal count (int)
[per portal] localCell, type, targetGridId, targetLocalCell
```

~40KB per chunk uncompressed. For 1000 explored chunks: ~40MB. Acceptable.

---

## WorldMap

Central spatial data manager. Owns segments, processes observations, handles stitching. **Session-agnostic** — all coordinates it receives and stores are persistent. Multiple sessions can submit observations concurrently.

### State

| Field | Type | Description |
|-------|------|-------------|
| `segments` | `Map<Long, MapSegment>` | segmentId → segment (ConcurrentHashMap) |
| `gridToSegment` | `Map<Long, Long>` | gridId → segmentId index (ConcurrentHashMap) |
| `storage` | `StorageBackend` | Persistence backend |
| `executor` | `ExecutorService` | Single-thread background processor |

### Lifecycle

1. **`load()`** — Call `storage.loadAll()`, populate segments and gridToSegment index, register all known grid positions in CoordinateSystem.
2. **`shutdown()`** — Shut down executor, save dirty chunks, save manifest, close storage.

### Observation Pipeline

```
Session A (game thread)                  Shared WorldMap (background thread)
───────────────────────                  ───────────────────────────────────
collectGridObservations()
  → snapshot MCache grids + gobs
  → convert to persistent coords          processObservations()
    via session's CoordinateSystem           1. for each obs: processGridObservation()
  → submitObservations(observations) ─queue─→   - find or create chunk by gridId
                                                 - update cell flags from tile types + gobs
Session B (game thread)                          - update layer, mark updated
───────────────────────                       2. checkForStitching()
  → same flow, different offset ─────queue─→
```

`submitObservations()` is non-blocking. The single-thread executor serializes all writes, so concurrent submissions from multiple sessions are safe.

### Observation Processing

For each `GridObservation` (already in persistent coordinates):
1. Find existing chunk by gridId, or create a new single-chunk segment at `obs.persistentChunkCoord`
2. Update cell flags:
   - For each cell within `VISIBLE_RADIUS_CELLS` of the player's position in the grid:
     - Set `OBSERVED` flag
     - Check tile type → set `IMPASSABLE_TERRAIN`, `SHALLOW_WATER`, `DEEP_WATER`, or `HIGH_SEAS` as appropriate
     - Check static gob hitbox projections → set `STATIC_GOB`
      - Clear flags that no longer apply (e.g., a gob was removed since last observation — clear `STATIC_GOB`)
3. Update layer, mark chunk dirty

### Gob Hitbox Projection

Unlike nurgling's `NHitBoxD`/`CellsArray` approach, use `Hitbox.forGob()` which returns polygon vertices (`Coord2d[][]`). For each gob:
1. Get polygon vertices from `Hitbox.forGob(gob)` (cached per-Resource, already in local space)
2. Rotate vertices by `gob.a` and translate by `gob.rc` to get world-space polygon
3. Compute the axis-aligned bounding box of the rotated polygon
4. For each cell within that AABB, test if the cell center is inside the polygon (point-in-polygon test) or conservatively mark the entire AABB as blocked
5. Skip gobs where `Hitbox.passable()` is true

The AABB-conservative approach is simpler and safe (over-blocks slightly). Point-in-polygon is more accurate but costs more per cell. Start with AABB, refine if needed.

### Segment Stitching

Triggered after processing a batch of observations. For each observation, check if any `neighborGridIds` point to a grid in a different segment than the observation's own grid.

**Merge logic:**
1. Identify the two segments to merge
2. Larger segment absorbs smaller (fewer chunks to re-offset)
3. Compute coordinate offset from the known neighbor relationship:
   - Grid A is at `chunkCoordA` in the keeper segment
   - Grid B is A's east neighbor → B should be at `chunkCoordA + (1, 0)` in keeper's space
   - `offset = expectedCoordB - actualCoordB` (actual is B's coordinate in the absorbed segment)
4. Call `keeper.absorb(absorbed, offset)` — re-offsets all chunks, updates segmentId
5. Update `gridToSegment` index
6. Remove absorbed segment
7. Notify storage via `onSegmentsMerged()`

### Queries (thread-safe, lock-free reads)

| Method | Description |
|--------|-------------|
| `getCellFlags(segmentId, globalCellX, globalCellY)` | Returns raw flag byte. Delegates to MapSegment. |
| `getSegmentForGrid(gridId) → Long` | Look up which segment a grid belongs to |
| `getKnownGridPositions() → Map<Long, Coord>` | All gridId → persistent chunk coord mappings (for CoordinateSystem calibration) |

### Persistence (background thread)

- `saveDirtyChunks()` — iterate all segments, save chunks where `isDirty()` is true
- `saveManifest()` — write current segment → chunk index to storage
- `scheduleSave()` — enqueue save on background thread (called by periodic timer)

---

## IPathfindingMap

Read-only query interface for pathfinder consumption. The pathfinder sees a flat infinite grid — no awareness of chunks, segments, or storage.

| Method | Returns | Description |
|--------|---------|-------------|
| `getCellFlags(globalCellX, globalCellY)` | `byte` | Raw flag byte for the cell (0 if unobserved) |
| `isWalkable(globalCellX, globalCellY)` | `boolean` | Convenience: observed, no blocking flags set |
| `isExplored(globalCellX, globalCellY)` | `boolean` | Whether `OBSERVED` flag is set |
| `getExploredBounds()` | `int[4]` or null | `[minX, minY, maxX, maxY]` hint for pathfinder search bounds |

The pathfinder can define its own walkability criteria from the raw flags. For example, a boat pathfinder would treat `DEEP_WATER` as walkable while a foot pathfinder would not.

### SegmentPathfindingMap

Adapts a `MapSegment` to `IPathfindingMap`. Used for long-distance pathfinding on stored data.

For local+global composite pathfinding (separate plan), a `CompositePathfindingMap` will layer a precomputed `LocalObstacleGrid` (from live MCache + OCache data) on top of `SegmentPathfindingMap`:

```
CompositePathfindingMap
  ├── LocalObstacleGrid   (rebuilt per pathfind pass from live scene)
  │     covers: ~600x600 cells (3x3 loaded chunks)
  │     has: live gob positions (including dynamic), current tile state
  │     format: same byte[][] flags, with dynamic gob flags added at build time
  └── SegmentPathfindingMap  (fallback for anything outside loaded area)
        covers: entire explored world
        has: last-recorded flags (static gobs + terrain, no dynamic gobs)

  getCellFlags(x, y):
    if localGrid.covers(x, y) → return localGrid.get(x, y)
    else → return segmentMap.getCellFlags(x, y)
```

---

## WorldPersistence

Top-level service. Genus-scoped. **Shared across sessions** on the same server. Entry point for all world persistence.

### State

| Field | Type | Description |
|-------|------|-------------|
| `genus` | `String` | Server identity |
| `map` | `WorldMap` | Spatial data (this spec) |
| `storage` | `StorageBackend` | Created by factory method |
| `saveScheduler` | `ScheduledExecutorService` | Periodic save timer |
| `refCount` | `AtomicInteger` | Number of active sessions using this instance |

### Lifecycle

1. **Construction:** `new WorldPersistence(genus, profileDir)`
2. **`initialize()`:** Create storage backend, create WorldMap, call `map.load()`, start periodic save timer (60s interval)
3. **`shutdown()`:** Stop save timer, call `map.shutdown()` (final save + close)

### Multi-session sharing

WorldPersistence instances are managed by a static registry keyed by genus:

```
WorldPersistenceRegistry (static)
  Map<String, WorldPersistence> instances  — genus -> shared instance

  acquire(genus, profileDir) → WorldPersistence
    - If instance exists for genus: increment refCount, return it
    - Else: create, initialize, store, return

  release(genus)
    - Decrement refCount
    - If refCount == 0: shutdown and remove from registry
```

Each session calls `acquire()` on connect and `release()` on disconnect. The WorldPersistence stays alive as long as any session needs it.

### Access

| Method | Description |
|--------|-------------|
| `getMap()` | Access WorldMap for observations and queries |
| `getPathfindingMap(currentGridId)` | Convenience: returns IPathfindingMap for the player's current segment |
| `getGenus()` | Server identity |

### Integration with Paisti client

**Per-session (on PGameUI):**
- Acquire shared WorldPersistence via registry on connect
- Create session-local CoordinateSystem, populate `knownGridPositions` from `worldPersistence.getMap().getKnownGridPositions()`
- Calibrate CoordinateSystem as grids load
- In `PMapView.tick()` (throttled ~2s): collect observations, convert to persistent coords via CoordinateSystem, submit to shared WorldMap
- Release WorldPersistence on disconnect

**Shared (WorldPersistence):**
- One instance per genus, outlives individual sessions
- Multiple sessions contribute observations to the same WorldMap
- Storage writes serialized through single-thread executor

Observation collection on the game thread (per-session):
1. Ensure CoordinateSystem is calibrated (attempt calibration on each new grid seen)
2. Iterate `MCache.grids` (synchronized) to get loaded grids
3. For each grid: capture `gridId`, convert `gc` to persistent chunk coord, capture `tiles`, detect neighbors by gc offset
4. Iterate `OCache` to snapshot gobs with hitboxes (`Hitbox.forGob()`), convert positions to persistent world coords
5. Determine layer from visible gob types
6. Package as `List<GridObservation>` (all in persistent coordinates), call `worldPersistence.getMap().submitObservations()`

### Future extensions

```
WorldPersistence
  ├── map: WorldMap              ← this spec
  ├── villages: VillageData      ← future: village tracking
  ├── stocks: StockTracker       ← future: item quantity tracking
  └── waypoints: WaypointStore   ← future: named locations
```

Each subsystem gets its own storage and lifecycle but shares genus scope. Spatial subsystems share the persistent coordinate space.

---

## Tile Type Resolution

The observation pipeline needs to determine if a tile type ID represents an impassable tile. MCache stores raw tile type IDs (ints). Resolving these to resource names requires `MCache.tileset(int id)` or similar. Known blocked tile names:

- `nil` — void/unloaded
- `cave` — cave walls
- `rocks` — rock faces
- `deep` — deep water
- `odeep` — ocean deep water

Exception: `deepcave` is walkable despite containing "deep".

The exact tile name resolution mechanism should be verified against `MCache`'s tile resource loading in the target codebase.

---

## Open Questions

1. **Tile type resolution:** How does `MCache` in the Ender fork expose tile resource names from tile type IDs? Need to verify the API.

2. **Hitbox projection accuracy:** Is the AABB-conservative approach sufficient, or do rotated objects (e.g., diagonal fences) create enough false blocking to justify polygon intersection? Can be evaluated empirically.

3. **Instance/layer detection:** How to detect building interiors vs outdoor in the Ender fork? Nurgling uses gob name matching (cellar stairs → cellar, doors without exteriors → inside). Need equivalent heuristic.

4. **Observation throttling:** 2-second interval matches nurgling's approach. May need tuning — too frequent wastes CPU on redundant updates, too infrequent misses fast-moving player.

5. **Segment persistence vs reconstruction:** Currently segments are persisted in the manifest. Alternative: only persist `(gridId, chunkCoord)` pairs and reconstruct segments from neighbor adjacency on load. Simpler storage, slightly more work on load.
