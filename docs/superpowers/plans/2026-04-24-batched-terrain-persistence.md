# Batched Terrain Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist terrain-derived blocking data for loaded map chunks in 500ms batches without marking cells as gob/FOV-observed.

**Architecture:** `PMapView` polls `MCache.chseq` and hands a snapshot of loaded grids to a genus-scoped `WorldPersistence` service. `WorldPersistence` debounces grid snapshots for 500ms, then updates/creates `ChunkData` records, writes only terrain blocking flags, and saves dirty chunks after the batch. Missing chunk data means unknown terrain; chunk presence means terrain is known.

**Tech Stack:** Java 11, Ant, JUnit 5, existing `paisti.world` persistence classes, existing Haven `MCache` tile/grid APIs.

---

## File Structure

- Modify `src/paisti/world/WorldMapConstants.java`: add cell flag bit constants. Do not add a terrain-known flag.
- Modify `src/paisti/world/ChunkData.java`: make `setCellFlags` no-op when the value is unchanged to avoid redundant dirty saves.
- Create `src/paisti/world/TerrainFlagResolver.java`: converts a `MCache.Grid` tile into terrain flags for the corresponding half-tile cells.
- Create `src/paisti/world/WorldPersistence.java`: owns one `WorldMap`, queues loaded grids, applies 500ms debounce, updates chunks, and saves.
- Create `src/paisti/world/WorldPersistenceRegistry.java`: creates/caches one `WorldPersistence` per genus and owns lifecycle cleanup.
- Modify `src/haven/MCache.java`: add a public `loadedGrids()` snapshot method only; do not import Paisti code into `haven`.
- Modify `src/paisti/client/PaistiServices.java`: instantiate and stop `WorldPersistenceRegistry`.
- Modify `src/paisti/client/PUI.java`: expose `worldPersistenceRegistry()`.
- Modify `src/paisti/client/PGameUI.java`: acquire/release the genus-scoped persistence service on attach/destroy.
- Modify `src/paisti/client/PMapView.java`: poll `glob.map.chseq` and enqueue `loadedGrids()` snapshots.
- Create tests under `test/unit/paisti/world/` for constants, resolver, debounce batching, and registry path/lifecycle behavior.

Do not implement portal discovery, gob hitbox/FOV observation, dynamic blockers, pathfinder execution, or a `TERRAIN_KNOWN` bit in this plan.

---

### Task 1: Cell Flag Constants And Dirty No-Op

**Files:**
- Modify: `src/paisti/world/WorldMapConstants.java`
- Modify: `src/paisti/world/ChunkData.java`
- Test: `test/unit/paisti/world/ChunkDataTest.java`

- [ ] **Step 1: Write failing tests for flag constants and unchanged cell no-op**

Append these tests to `test/unit/paisti/world/ChunkDataTest.java`:

```java
    @Test
    @Tag("unit")
    void terrainAndObservedFlagsUseSeparateBitsWithoutTerrainKnown() {
        assertEquals(1, WorldMapConstants.CELL_BLOCKED_TERRAIN);
        assertEquals(2, WorldMapConstants.CELL_DEEP_WATER);
        assertEquals(4, WorldMapConstants.CELL_OBSERVED);
    }

    @Test
    @Tag("unit")
    void settingCellFlagsToExistingValueDoesNotMarkDirty() {
        ChunkData chunkData = new ChunkData(1001L, 2002L, Coord.of(3, 4));

        chunkData.setCellFlags(12, 34, WorldMapConstants.CELL_BLOCKED_TERRAIN);
        chunkData.markClean();

        chunkData.setCellFlags(12, 34, WorldMapConstants.CELL_BLOCKED_TERRAIN);

        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, chunkData.getCellFlags(12, 34));
        assertEquals(false, chunkData.dirty);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `ant test-unit -buildfile build.xml`

Expected: FAIL because `CELL_BLOCKED_TERRAIN`, `CELL_DEEP_WATER`, and `CELL_OBSERVED` do not exist, and/or unchanged `setCellFlags` still marks dirty.

- [ ] **Step 3: Implement constants and dirty no-op**

Change `src/paisti/world/WorldMapConstants.java` to:

```java
package paisti.world;

public final class WorldMapConstants {
    public static final int CELL_AXIS = 200;
    public static final int CELL_COUNT = 40000;
    public static final int INVALID_CELL_FLAGS = 0xFF;
    public static final long SHORT_CHUNK_MASK = 0x0000FFFFFFFFFFFFL;

    public static final int CELL_BLOCKED_TERRAIN = 1;
    public static final int CELL_DEEP_WATER = 1 << 1;
    public static final int CELL_OBSERVED = 1 << 2;

    private WorldMapConstants() {
    }
}
```

Update `src/paisti/world/ChunkData.java` method `setCellFlags(int cellX, int cellY, int flags)` to:

```java
    public void setCellFlags(int cellX, int cellY, int flags) {
        setCellFlags(MapUtil.cellIndex(cellX, cellY), flags);
    }

    public void setCellFlags(int cellIndex, int flags) {
        validateCellIndex(cellIndex);
        validateFlags(flags);
        byte encodedFlags = (byte) flags;
        if (cells[cellIndex] == encodedFlags) {
            return;
        }
        cells[cellIndex] = encodedFlags;
        dirty = true;
    }
```

Keep `getCellFlags(...)`, `addPortal(...)`, and validation methods unchanged.

- [ ] **Step 4: Run tests to verify they pass**

Run: `ant test-unit -buildfile build.xml`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/paisti/world/WorldMapConstants.java src/paisti/world/ChunkData.java test/unit/paisti/world/ChunkDataTest.java
git commit -m "feat: add world map cell flags"
```

---

### Task 2: Terrain Flag Resolver

**Files:**
- Create: `src/paisti/world/TerrainFlagResolver.java`
- Test: `test/unit/paisti/world/TerrainFlagResolverTest.java`

- [ ] **Step 1: Write failing tests for tile resource classification**

Create `test/unit/paisti/world/TerrainFlagResolverTest.java`:

```java
package paisti.world;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainFlagResolverTest {
    @Test
    @Tag("unit")
    void nilCaveAndRockTilesAreTerrainBlocked() {
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource("gfx/tiles/nil"));
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource("gfx/tiles/cave"));
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource("gfx/tiles/rocks/gray"));
    }

    @Test
    @Tag("unit")
    void deepWaterSetsBothTerrainBlockedAndDeepWater() {
        int expected = WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER;

        assertEquals(expected, TerrainFlagResolver.flagsForTileResource("gfx/tiles/deep"));
        assertEquals(expected, TerrainFlagResolver.flagsForTileResource("gfx/tiles/odeep"));
    }

    @Test
    @Tag("unit")
    void deepCaveOverridesCavePrefixAndRemainsPassable() {
        assertEquals(0, TerrainFlagResolver.flagsForTileResource("gfx/tiles/deepcave"));
        assertEquals(0, TerrainFlagResolver.flagsForTileResource("gfx/tiles/grass"));
    }

    @Test
    @Tag("unit")
    void unknownTileResourceIsConservativelyBlocked() {
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource(null));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `ant test-unit -buildfile build.xml`

Expected: FAIL because `TerrainFlagResolver` does not exist.

- [ ] **Step 3: Implement resource-name classification**

Create `src/paisti/world/TerrainFlagResolver.java`:

```java
package paisti.world;

import haven.MCache;
import haven.Resource;
import haven.resutil.Ridges;

import java.util.List;

public class TerrainFlagResolver {
    private static final List<String> BLOCKED_TILES = List.of(
        "gfx/tiles/nil",
        "gfx/tiles/cave",
        "gfx/tiles/rocks"
    );
    private static final List<String> DEEP_WATER_TILES = List.of(
        "gfx/tiles/deep",
        "gfx/tiles/odeep"
    );
    private static final List<String> WALKABLE_CAVE_TILES = List.of(
        "gfx/tiles/deepcave"
    );

    public int flagsForTile(MCache.Grid grid, int tileX, int tileY) {
        int flags = flagsForTileResource(resolveTileResourceName(grid, tileX, tileY));
        if (isBrokenRidge(grid, tileX, tileY)) {
            flags |= WorldMapConstants.CELL_BLOCKED_TERRAIN;
        }
        return flags;
    }

    static int flagsForTileResource(String tileName) {
        if (tileName == null) {
            return WorldMapConstants.CELL_BLOCKED_TERRAIN;
        }
        for (String walkable : WALKABLE_CAVE_TILES) {
            if (matchesTile(tileName, walkable)) {
                return 0;
            }
        }
        for (String deepWater : DEEP_WATER_TILES) {
            if (matchesTile(tileName, deepWater)) {
                return WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER;
            }
        }
        for (String blocked : BLOCKED_TILES) {
            if (matchesTile(tileName, blocked)) {
                return WorldMapConstants.CELL_BLOCKED_TERRAIN;
            }
        }
        return 0;
    }

    private static boolean matchesTile(String tileName, String pattern) {
        return tileName.equals(pattern) || tileName.startsWith(pattern);
    }

    private static String resolveTileResourceName(MCache.Grid grid, int tileX, int tileY) {
        Resource resource = grid.tileset(grid.gettile(haven.Coord.of(tileX, tileY))).res;
        return resource == null ? null : resource.name;
    }

    private static boolean isBrokenRidge(MCache.Grid grid, int tileX, int tileY) {
        try {
            return Ridges.brokenp(grid.map, grid, haven.Coord.of(tileX, tileY));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
```

If `grid.tileset(...).res` is not accessible in this codebase, use `Resource resource = grid.map.tilesetr(grid.gettile(Coord.of(tileX, tileY)));` instead and import `haven.Coord`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `ant test-unit -buildfile build.xml`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/paisti/world/TerrainFlagResolver.java test/unit/paisti/world/TerrainFlagResolverTest.java
git commit -m "feat: classify terrain cell flags"
```

---

### Task 3: WorldPersistence Batch Processing

**Files:**
- Create: `src/paisti/world/WorldPersistence.java`
- Test: `test/unit/paisti/world/WorldPersistenceTest.java`

- [ ] **Step 1: Write failing unit tests for 500ms debounce behavior**

Create `test/unit/paisti/world/WorldPersistenceTest.java` with tests around an injected clock and injected save callback:

```java
package paisti.world;

import haven.Coord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldPersistenceTest {
    @Test
    @Tag("unit")
    void debounceWaitsUntilFiveHundredMillisecondsAfterLastGridSnapshot() throws IOException {
        TestClock clock = new TestClock();
        List<String> calls = new ArrayList<>();
        WorldPersistence persistence = new WorldPersistence(new WorldMap(), clock::now, ignored -> calls.add("save"));

        persistence.enqueueLoadedGrids(List.of(new WorldPersistence.LoadedGrid(1001L, Coord.of(3, 4), Coord.of(300, 400), new byte[WorldMapConstants.CELL_COUNT])));
        clock.now = 499;
        persistence.tick();
        assertEquals(List.of(), calls);

        persistence.enqueueLoadedGrids(List.of(new WorldPersistence.LoadedGrid(1002L, Coord.of(3, 5), Coord.of(300, 500), new byte[WorldMapConstants.CELL_COUNT])));
        clock.now = 999;
        persistence.tick();
        assertEquals(List.of(), calls);

        clock.now = 1000;
        persistence.tick();

        assertEquals(List.of("save"), calls);
    }

    @Test
    @Tag("unit")
    void applyingBatchCreatesChunksWithoutObservedFlag() throws IOException {
        TestClock clock = new TestClock();
        WorldMap worldMap = new WorldMap();
        WorldPersistence persistence = new WorldPersistence(worldMap, clock::now, WorldMap::saveDirtyChunks);
        byte[] flags = new byte[WorldMapConstants.CELL_COUNT];
        flags[MapUtil.cellIndex(4, 6)] = (byte) WorldMapConstants.CELL_BLOCKED_TERRAIN;

        persistence.enqueueLoadedGrids(List.of(new WorldPersistence.LoadedGrid(1001L, Coord.of(3, 4), Coord.of(300, 400), flags)));
        clock.now = 500;
        persistence.tick();

        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, worldMap.getCellFlags(1001L, 4, 6));
        assertEquals(0, worldMap.getCellFlags(1001L, 4, 7));
        assertEquals(0, worldMap.getCellFlags(1001L, 4, 6) & WorldMapConstants.CELL_OBSERVED);
    }

    private static class TestClock {
        long now;

        long now() {
            return now;
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `ant test-unit -buildfile build.xml`

Expected: FAIL because `WorldPersistence` does not exist.

- [ ] **Step 3: Implement debounced batch core with test-only `LoadedGrid` input**

Create `src/paisti/world/WorldPersistence.java`:

```java
package paisti.world;

import haven.Coord;
import haven.MCache;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

public class WorldPersistence implements AutoCloseable {
    static final long GRID_BATCH_DEBOUNCE_MS = 500L;

    private final WorldMap worldMap;
    private final LongSupplier clockMillis;
    private final SaveAction saveAction;
    private final TerrainFlagResolver terrainFlagResolver;
    private final Map<Long, LoadedGrid> pendingGrids = new LinkedHashMap<>();
    private long lastEnqueueMillis;

    public WorldPersistence(WorldMap worldMap) {
        this(worldMap, System::currentTimeMillis, WorldMap::saveDirtyChunks);
    }

    WorldPersistence(WorldMap worldMap, LongSupplier clockMillis, SaveAction saveAction) {
        this.worldMap = Objects.requireNonNull(worldMap, "worldMap");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.saveAction = Objects.requireNonNull(saveAction, "saveAction");
        this.terrainFlagResolver = new TerrainFlagResolver();
    }

    public WorldMap worldMap() {
        return worldMap;
    }

    public synchronized void enqueueLoadedGrids(Collection<LoadedGrid> grids) {
        if (grids.isEmpty()) {
            return;
        }
        for (LoadedGrid grid : grids) {
            pendingGrids.put(grid.gridId, grid);
        }
        lastEnqueueMillis = clockMillis.getAsLong();
    }

    public synchronized void enqueueMCacheGrids(Collection<MCache.Grid> grids) {
        if (grids.isEmpty()) {
            return;
        }
        for (MCache.Grid grid : grids) {
            pendingGrids.put(grid.id, snapshotGrid(grid));
        }
        lastEnqueueMillis = clockMillis.getAsLong();
    }

    public void tick() throws IOException {
        Map<Long, LoadedGrid> batch;
        synchronized (this) {
            if (pendingGrids.isEmpty()) {
                return;
            }
            if ((clockMillis.getAsLong() - lastEnqueueMillis) < GRID_BATCH_DEBOUNCE_MS) {
                return;
            }
            batch = new LinkedHashMap<>(pendingGrids);
            pendingGrids.clear();
        }
        for (LoadedGrid grid : batch.values()) {
            applyLoadedGrid(grid);
        }
        saveAction.save(worldMap);
    }

    private LoadedGrid snapshotGrid(MCache.Grid grid) {
        byte[] flags = new byte[WorldMapConstants.CELL_COUNT];
        for (int tileY = 0; tileY < 100; tileY++) {
            for (int tileX = 0; tileX < 100; tileX++) {
                int tileFlags = terrainFlagResolver.flagsForTile(grid, tileX, tileY);
                int cellX = tileX * 2;
                int cellY = tileY * 2;
                flags[MapUtil.cellIndex(cellX, cellY)] = (byte) tileFlags;
                flags[MapUtil.cellIndex(cellX + 1, cellY)] = (byte) tileFlags;
                flags[MapUtil.cellIndex(cellX, cellY + 1)] = (byte) tileFlags;
                flags[MapUtil.cellIndex(cellX + 1, cellY + 1)] = (byte) tileFlags;
            }
        }
        return new LoadedGrid(grid.id, grid.gc, grid.ul, flags);
    }

    private void applyLoadedGrid(LoadedGrid grid) {
        ChunkData chunk = worldMap.getChunk(grid.gridId);
        if (chunk == null) {
            chunk = new ChunkData(grid.gridId, 0L, grid.gridCoord);
            worldMap.putChunk(chunk);
        }
        chunk.chunkCoord = grid.gridCoord;
        for (int i = 0; i < WorldMapConstants.CELL_COUNT; i++) {
            int dynamicFlags = chunk.getCellFlags(i) & WorldMapConstants.CELL_OBSERVED;
            int terrainFlags = Byte.toUnsignedInt(grid.cellFlags[i]) & ~WorldMapConstants.CELL_OBSERVED;
            chunk.setCellFlags(i, dynamicFlags | terrainFlags);
        }
        chunk.lastUpdated = clockMillis.getAsLong();
    }

    @Override
    public void close() throws IOException {
        tick();
        worldMap.close();
    }

    @FunctionalInterface
    interface SaveAction {
        void save(WorldMap worldMap) throws IOException;
    }

    static class LoadedGrid {
        final long gridId;
        final Coord gridCoord;
        final Coord worldTileOrigin;
        final byte[] cellFlags;

        LoadedGrid(long gridId, Coord gridCoord, Coord worldTileOrigin, byte[] cellFlags) {
            this.gridId = gridId;
            this.gridCoord = gridCoord;
            this.worldTileOrigin = worldTileOrigin;
            this.cellFlags = Objects.requireNonNull(cellFlags, "cellFlags");
            if (cellFlags.length != WorldMapConstants.CELL_COUNT) {
                throw new IllegalArgumentException("cellFlags length must be " + WorldMapConstants.CELL_COUNT);
            }
        }
    }
}
```

If preserving only `CELL_OBSERVED` looks too narrow after later gob flags are added, introduce an explicit dynamic mask then. Do not add that mask in this task.

- [ ] **Step 4: Run tests to verify they pass**

Run: `ant test-unit -buildfile build.xml`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/paisti/world/WorldPersistence.java test/unit/paisti/world/WorldPersistenceTest.java
git commit -m "feat: batch terrain persistence updates"
```

---

### Task 4: Genus-Scoped Registry And Storage Path

**Files:**
- Create: `src/paisti/world/WorldPersistenceRegistry.java`
- Test: `test/unit/paisti/world/WorldPersistenceRegistryTest.java`

- [ ] **Step 1: Write failing registry tests**

Create `test/unit/paisti/world/WorldPersistenceRegistryTest.java`:

```java
package paisti.world;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WorldPersistenceRegistryTest {
    @Test
    @Tag("unit")
    void sameGenusReturnsSamePersistenceInstance() throws IOException {
        WorldPersistenceRegistry registry = new WorldPersistenceRegistry(Path.of("base"));

        WorldPersistence first = registry.get("abc123");
        WorldPersistence second = registry.get("abc123");

        assertSame(first, second);
        registry.close();
    }

    @Test
    @Tag("unit")
    void storagePathIsSanitizedByGenus() {
        assertEquals(Path.of("base", "abc_123"), WorldPersistenceRegistry.storageBasePath(Path.of("base"), "abc/123"));
        assertEquals(Path.of("base", "unknown"), WorldPersistenceRegistry.storageBasePath(Path.of("base"), null));
        assertEquals(Path.of("base", "unknown"), WorldPersistenceRegistry.storageBasePath(Path.of("base"), ""));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `ant test-unit -buildfile build.xml`

Expected: FAIL because `WorldPersistenceRegistry` does not exist.

- [ ] **Step 3: Implement registry**

Create `src/paisti/world/WorldPersistenceRegistry.java`:

```java
package paisti.world;

import paisti.world.storage.FileStorageBackend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class WorldPersistenceRegistry implements AutoCloseable {
    private final Path basePath;
    private final Map<String, WorldPersistence> byGenus = new HashMap<>();

    public WorldPersistenceRegistry(Path basePath) {
        this.basePath = Objects.requireNonNull(basePath, "basePath");
    }

    public synchronized WorldPersistence get(String genus) throws IOException {
        String safeGenus = sanitizeGenus(genus);
        WorldPersistence existing = byGenus.get(safeGenus);
        if (existing != null) {
            return existing;
        }

        WorldMap worldMap = new WorldMap(new FileStorageBackend(storageBasePath(basePath, safeGenus)));
        worldMap.load();
        WorldPersistence persistence = new WorldPersistence(worldMap);
        byGenus.put(safeGenus, persistence);
        return persistence;
    }

    static Path storageBasePath(Path basePath, String genus) {
        return basePath.resolve(sanitizeGenus(genus));
    }

    private static String sanitizeGenus(String genus) {
        if ((genus == null) || genus.isEmpty()) {
            return "unknown";
        }
        String sanitized = genus.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isEmpty() ? "unknown" : sanitized;
    }

    @Override
    public synchronized void close() throws IOException {
        IOException first = null;
        for (WorldPersistence persistence : byGenus.values()) {
            try {
                persistence.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        byGenus.clear();
        if (first != null) {
            throw first;
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `ant test-unit -buildfile build.xml`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/paisti/world/WorldPersistenceRegistry.java test/unit/paisti/world/WorldPersistenceRegistryTest.java
git commit -m "feat: add genus-scoped world persistence"
```

---

### Task 5: Client Service Wiring

**Files:**
- Modify: `src/paisti/client/PaistiServices.java`
- Modify: `src/paisti/client/PUI.java`
- Modify: `src/paisti/client/PGameUI.java`

- [ ] **Step 1: Add service fields and accessors**

Modify `src/paisti/client/PaistiServices.java`:

```java
import haven.Config;
import paisti.world.WorldPersistenceRegistry;
```

Add field:

```java
    private final WorldPersistenceRegistry worldPersistenceRegistry;
```

In the constructor after `overlayManager`:

```java
        this.worldPersistenceRegistry = new WorldPersistenceRegistry(Config.getFile("paisti-world").toPath());
```

Add accessor:

```java
    public WorldPersistenceRegistry worldPersistenceRegistry() {
        return worldPersistenceRegistry;
    }
```

Update `stop()` to close the registry after `overlayManager.stop()`:

```java
        try {
            worldPersistenceRegistry.close();
        } catch (Exception e) {
            System.err.println("Failed to stop world persistence: " + e);
        }
```

- [ ] **Step 2: Expose registry from PUI**

Modify `src/paisti/client/PUI.java`:

```java
import paisti.world.WorldPersistenceRegistry;
```

Add method:

```java
    public WorldPersistenceRegistry worldPersistenceRegistry() {
        return paistiServices.worldPersistenceRegistry();
    }
```

- [ ] **Step 3: Acquire persistence in PGameUI**

Modify `src/paisti/client/PGameUI.java`:

```java
import paisti.world.WorldPersistence;
```

Add field:

```java
    private WorldPersistence worldPersistence;
```

At the end of `attached()`:

```java
        initWorldPersistence();
```

Add method:

```java
    private void initWorldPersistence() {
        if (worldPersistence != null || !(ui instanceof PUI)) {
            return;
        }
        try {
            worldPersistence = PUI.of(ui).worldPersistenceRegistry().get(genus);
        } catch (Exception e) {
            System.err.println("Failed to initialize world persistence: " + e);
        }
    }

    public WorldPersistence worldPersistence() {
        return worldPersistence;
    }
```

Do not close `worldPersistence` in `PGameUI.destroy()`; the registry owns shared genus instances and closes them when `PaistiServices.stop()` runs.

- [ ] **Step 4: Run build/tests**

Run: `ant test-unit -buildfile build.xml`

Expected: BUILD SUCCESSFUL.

Run: `ant bin -buildfile build.xml`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/paisti/client/PaistiServices.java src/paisti/client/PUI.java src/paisti/client/PGameUI.java
git commit -m "feat: wire world persistence service"
```

---

### Task 6: Map Grid Snapshot Hook With 500ms Batch Tick

**Files:**
- Modify: `src/haven/MCache.java`
- Modify: `src/paisti/client/PMapView.java`

- [ ] **Step 1: Add loaded grid snapshot API to MCache**

Modify `src/haven/MCache.java` near `getgrid(long id)`:

```java
    public Collection<Grid> loadedGrids() {
        synchronized (grids) {
            return new ArrayList<>(grids.values());
        }
    }
```

This keeps the vanilla-surface change small and does not import Paisti code into `haven`.

- [ ] **Step 2: Poll chseq from PMapView and enqueue snapshots**

Modify `src/paisti/client/PMapView.java`:

```java
package paisti.client;

import haven.*;
import paisti.world.WorldPersistence;

import java.io.IOException;

public class PMapView extends MapView {
    private int observedMapChangeSeq = -1;

    public PMapView(Coord sz, Glob glob, Coord2d cc, long plgob) {
        super(sz, glob, cc, plgob);
    }

    @Override
    protected void attached() {
        super.attached();
        if(ui instanceof PUI) {
            PUI.of(ui).overlayManager().syncMapOverlayAttachment();
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        tickWorldPersistence();
    }

    private void tickWorldPersistence() {
        if (!(ui instanceof PUI) || !(ui.gui instanceof PGameUI)) {
            return;
        }
        WorldPersistence worldPersistence = ((PGameUI) ui.gui).worldPersistence();
        if (worldPersistence == null) {
            return;
        }

        int currentSeq = glob.map.chseq;
        if (currentSeq != observedMapChangeSeq) {
            observedMapChangeSeq = currentSeq;
            worldPersistence.enqueueMCacheGrids(glob.map.loadedGrids());
        }
        try {
            worldPersistence.tick();
        } catch (IOException e) {
            System.err.println("Failed to update world persistence: " + e);
        }
    }
}
```

- [ ] **Step 3: Run build/tests**

Run: `ant test-unit -buildfile build.xml`

Expected: BUILD SUCCESSFUL.

Run: `ant bin -buildfile build.xml`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/haven/MCache.java src/paisti/client/PMapView.java
git commit -m "feat: enqueue loaded map grids for persistence"
```

---

### Task 7: Final Verification And Review

**Files:**
- Verify all changed files.

- [ ] **Step 1: Run unit tests**

Run: `ant test-unit -buildfile build.xml`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run distributable build**

Run: `ant bin -buildfile build.xml`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Review git diff for forbidden semantics**

Run: `git diff HEAD~6..HEAD`

Verify:
- No `TERRAIN_KNOWN` constant exists.
- Terrain loading never sets `CELL_OBSERVED`.
- Passable terrain cells remain `0`.
- Missing chunks still return `INVALID_CELL_FLAGS`.
- `haven.MCache` does not import `paisti.*`.

- [ ] **Step 4: Request code review**

Dispatch a code-review subagent with this prompt:

```text
Review the world persistence terrain batching changes on the current branch. Focus on correctness, persistence semantics, threading/lifecycle, and whether loaded terrain incorrectly marks gob/FOV observation. Confirm no TERRAIN_KNOWN bit was added and no Paisti dependency was introduced into haven.MCache.
```

- [ ] **Step 5: Address review findings, then commit fixes if any**

If review finds issues, fix them with targeted commits. If no issues are found, do not create an empty commit.

---

## Self-Review Notes

- Spec coverage: The plan implements genus-scoped persistence, loaded terrain batching, 500ms debounce, no terrain-known bit, no observed-on-load behavior, and terrain blocker classification based on Nurgling tile rules plus ridge checks.
- Placeholder scan: No TBD/TODO/fill-in-later steps remain. Task 2 includes one fallback for resource lookup because `Tileset.res` accessibility must be confirmed during implementation.
- Type consistency: `WorldPersistence.LoadedGrid`, `WorldPersistenceRegistry`, `TerrainFlagResolver`, and flag constants are consistently named across tasks.
