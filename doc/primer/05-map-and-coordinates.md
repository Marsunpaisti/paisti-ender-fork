# Map, Tiles, Chunks, And Coordinates

## Core Coordinate Types

The client uses several coordinate types, and confusing them is one of the fastest ways to get lost.

- `Coord`: integer 2D positions
- `Coord2d`: double-precision world positions
- `Coord3f`: 3D render/object positions

Core files:

- `src/haven/Coord.java`
- `src/haven/Coord2d.java`
- `src/haven/Coord3f.java`
- `src/haven/MCache.java`
- `src/haven/MapMesh.java`
- `src/haven/MapFile.java`

## Important Constants

In `MCache`:

- one tile is `11 x 11` world units
- one map grid is `100 x 100` tiles
- one render cut is `25 x 25` tiles

So a full runtime map grid spans `1100 x 1100` world units.

## Runtime Terrain Model

`MCache` is the live terrain cache.

It stores runtime map grids containing:

- tile ids
- heights
- overlays
- lazily built render cuts and meshes

The basic coordinate conversions are:

- world position `Coord2d` -> tile coord via `floor(MCache.tilesz)`
- tile coord -> runtime grid coord via `div(MCache.cmaps)`
- tile coord within a grid -> local tile offset via subtraction from grid origin

## Persistent Map Model

`MapFile` is separate from `MCache`.

- `MCache` is live streamed terrain.
- `MapFile` is the saved stitched world/minimap representation.

This is why code sometimes moves between runtime grid coordinates and persistent map-segment coordinates.

## Native Client Behavior

Native Haven provides:

- coordinate math types
- runtime terrain caching in `MCache`
- terrain rendering in `MapMesh`
- map click to world-position conversion in `MapView`
- persistent map storage in `MapFile` and `MiniMap`

## Ender Additions

The fork adds a lot of map-adjacent behavior:

- mapper integration in `src/integrations/mapv4/MappingClient.java`
- custom map window behavior in `src/haven/MapWnd2.java`
- minimap overlays like `src/me/ender/minimap/Minesweeper.java`
- config-driven overlays and tile highlighting
- automarking hooks from gob/resource data

In practice, native Haven gives you the spaces and conversions, while Ender adds more things that consume those spaces.

## Gotchas

- `Coord` by itself is ambiguous; it may be UI space, tile space, grid space, or segment space.
- Runtime grid coordinates are not the same thing as persistent map-segment coordinates.
- Render-space Y often flips relative to map-space Y.
- Some fork code hardcodes the `1100` world-units-per-grid relationship instead of deriving it from constants.
