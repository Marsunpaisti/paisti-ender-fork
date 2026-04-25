# World Persistence Design

## Scope

This spec defines the in-memory map/query model for persisted world data used by long-distance pathfinding.

It covers:
- chunk storage layout
- pathfinding and portal query interfaces
- packed runtime cell coordinates
- portal data model
- packed lookup indexing in `WorldMap`

It does not cover:
- portal discovery/recording logic
- pathfinder algorithm or neighbor policy
- local live-scene overlay logic
- storage backend transport/sync details beyond the data shape

Target codebase: `paisti-ender-fork`

## Goals

- Keep canonical persisted world data simple and correct.
- Keep the pathfinding hot path primitive-heavy.
- Avoid baking pathfinding policy into the map layer.
- Preserve full chunk identity in storage while allowing packed runtime lookups.

## High-Level Design

- `WorldMap` is the central mutable store.
- `WorldMap` implements both `IPathfindingMap` and `IPortalMap`.
- Canonical storage uses full `gridId: long` chunk identity.
- Runtime pathfinding can use packed `long` cell handles for faster primitive-based operations.
- Packed lookup is an optimization layer on top of canonical storage, not the source of truth.

## Canonical World Model

### Chunk Identity

- Every chunk is identified canonically by full `gridId: long`.
- Segment assignment and segment-local `chunkCoord` are stored separately.
- Full-ID lookups are always the authoritative path.

### ChunkData

`ChunkData` holds the in-memory state for one chunk.

Fields:
- `long gridId`
- `long segmentId`
- `Coord chunkCoord`
- `byte[] cells` with length `40000`
- `Map<Integer, List<Portal>> portalsByCell`
- metadata such as `layer`, `lastUpdated`, `version`, `dirty`

Cell storage:
- The chunk uses a unified `200 x 200` logical cell grid.
- Backing storage is flat: `byte[] cells = new byte[40000]`.
- Cell index is `cellIndex = cellY * 200 + cellX`.
- `0` means unknown/unobserved cell state.
- `0xFF` is reserved for query-time invalid lookup and is not stored as normal cell state.

`ChunkData` should expose:
- `int getCellFlags(int cellIndex)`
- `int getCellFlags(int cellX, int cellY)`

The returned type is `int`, even though storage is `byte`, to avoid signed-byte handling and to preserve the `0xFF` sentinel naturally.

### Portal Storage

While loaded in memory, a chunk stores portals only in:
- `Map<Integer, List<Portal>> portalsByCell`

Rules:
- Key is `cellIndex = cellY * 200 + cellX`
- This is a sparse structure because portals are expected to be rare
- There is no parallel master portal list in memory
- Persistence may flatten/rebuild this structure during save/load

## Portal Model

### PortalType

`PortalType` is an enum. Initial values:
- `DOOR`
- `GATE`
- `MINE`
- `CELLAR`
- `STAIRS`

### Portal

`Portal` stores canonical chunk-local coordinates.

Fields:
- `PortalType type`
- `Coord sourceLocalCell`
- `long targetChunkId`
- `int targetCellX`
- `int targetCellY`

Rules:
- Source chunk is implied by the owning `ChunkData`
- `sourceLocalCell` is still stored on the object for canonical clarity
- Destination is exact-cell only
- Portals are directed
- Portals are only persisted once both ends are known

Packed source/target cells are not cached on the model object. If needed at runtime, they are packed on demand through `MapUtil`.

## Query Interfaces

The map layer stays policy-free. It exposes raw flags and exact-cell portal data only.

### IPathfindingMap

```java
public interface IPathfindingMap {
    int getCellFlags(long packedCell);
    int getCellFlags(long fullChunkId, int cellX, int cellY);
}
```

Behavior:
- Returns raw cell flags as `int`
- Does not expose `isWalkable`, `isExplored`, bounds helpers, or neighbor APIs
- Packed overload is for runtime/pathfinder use
- Full-ID overload is canonical and safe for non-pathfinder callers

Sentinel:
- `0xFF` means invalid cell lookup

Non-packed overload:
- throws if `cellX` or `cellY` is outside `0..199`
- returns `0xFF` if the chunk is missing

Packed overload:
- decodes the packed value
- throws if decoded `cellX` or `cellY` is outside `0..199`
- returns `0xFF` if the packed chunk key does not resolve to a chunk

### IPortalMap

```java
public interface IPortalMap {
    void getCellPortals(long packedCell, List<Portal> out);
    void getCellPortals(long fullChunkId, int cellX, int cellY, List<Portal> out);
}
```

Behavior:
- Always `out.clear()` first, then appends results
- Matches only the exact source cell
- Missing chunk leaves `out` empty
- Non-packed overload throws if `cellX` or `cellY` is outside `0..199`
- Packed overload throws if decoded `cellX` or `cellY` is outside `0..199`
- Packed chunk key miss leaves `out` empty

## Packed Runtime Coordinate Model

Packed runtime coordinates exist only to make the pathfinder/node representation and hot-path queries use primitives.

They are not the canonical world identity format.

### Packed Cell Layout

One `long` stores:
- high 48 bits: shortened chunk key
- next 8 bits: `cellX`
- low 8 bits: `cellY`

Cell range requirements:
- `cellX` in `0..199`
- `cellY` in `0..199`

### MapUtil

`MapUtil` owns the packing helpers:

```java
public final class MapUtil {
    public static long packChunkCellCoord(long fullChunkId, int cellX, int cellY);
    public static int unpackChunkCellCoordX(long packed);
    public static int unpackChunkCellCoordY(long packed);
    public static long unpackChunkCellCoordShortChunkKey(long packed);
    public static long shortenChunkId(long fullChunkId);
}
```

Rules:
- `packChunkCellCoord(...)` validates `cellX` and `cellY` are in `0..199`
- `fullChunkId` is treated as opaque and may be any `long`
- `shortenChunkId(...)` uses the lowest significant 48 bits in v1
- `MapUtil` is pure and stateless
- `MapUtil` does not own chunk lookup tables or collision handling

### Collision Behavior

Packed runtime lookup uses a shortened chunk key, so collisions are possible.

V1 policy:
- `WorldMap` eagerly builds and maintains a global `shortChunkKey -> ChunkData` index
- If two chunks map to the same shortened key, log a visible error
- Keep running
- Leave the duplicate chunk out of packed lookup

This is an intentional v1 tradeoff to observe real-world collision behavior before adding a more complex fallback strategy.

Canonical full-ID lookups still continue to work even if packed lookup drops a colliding chunk.

## WorldMap Responsibilities

`WorldMap` owns:
- canonical chunk storage/indexes
- packed runtime lookup index
- chunk mutation/update lifecycle
- implementations of `IPathfindingMap` and `IPortalMap`

Core indexes:
- `Map<Long, ChunkData>` or equivalent for full `gridId -> ChunkData`
- global eager `Map<Long, ChunkData>` or equivalent for `shortChunkKey -> ChunkData`

Packed index maintenance is eager:
- build on load
- update on chunk add
- update on chunk update if needed
- update for moved chunks after segment merge

Do not defer packed-index creation until pathfinder construction.

## Persistence Shape

Persistence keeps canonical data only.

Stored per chunk:
- full `gridId`
- `segmentId`
- `chunkCoord`
- raw cell flags
- flattened portal records

Persistence does not store:
- shortened chunk keys
- packed runtime cell handles
- packed portal endpoints

On load:
- rebuild `cells`
- rebuild `portalsByCell`
- rebuild the global packed index
- log any shortened-key collisions during packed-index construction

## Non-Goals And Deferred Decisions

Deferred for later:
- portal discovery and recording workflow
- reverse portal pairing heuristics
- collision fallback beyond visible logging and packed omission
- pathfinder interpretation of flags
- packed-coordinate use outside pathfinding/runtime query paths

This spec intentionally leaves those areas out to keep the v1 map/query layer simple and measurable.
