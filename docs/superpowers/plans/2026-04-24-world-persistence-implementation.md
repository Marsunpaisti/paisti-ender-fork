# World Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the persisted world map/query core: canonical chunk storage, packed runtime cell queries, sparse portal indexing, and filesystem persistence for chunks.

**Architecture:** Keep full `gridId` chunk identity as the canonical model in `ChunkData` and `WorldMap`, then layer a packed `long` runtime cell handle on top for hot-path pathfinder queries. `WorldMap` owns both the authoritative full-ID index and the eager shortened-key lookup index, while `FileStorageBackend` persists canonical chunk data and rebuilds runtime indexes on load.

**Tech Stack:** Java 11, Ant, JUnit 5, existing `haven` primitives (`Coord`, `Warning`), filesystem persistence under the Paisti client codebase.

---

## File Structure

### New source files

- `src/paisti/world/WorldMapConstants.java`
  - Shared numeric constants and sentinels: `CELL_AXIS = 200`, `CELL_COUNT = 40000`, `INVALID_CELL_FLAGS = 0xFF`, bit masks for packed-cell encoding.
- `src/paisti/world/MapUtil.java`
  - Pure static helpers for packing/unpacking chunk-local cells into a `long` and shortening full chunk IDs to 48 bits.
- `src/paisti/world/IPathfindingMap.java`
  - Raw flag query contract for packed-cell and canonical full-ID lookups.
- `src/paisti/world/IPortalMap.java`
  - Buffer-based exact-cell portal query contract for packed-cell and canonical full-ID lookups.
- `src/paisti/world/PortalType.java`
  - `DOOR`, `GATE`, `MINE`, `CELLAR`, `STAIRS` enum.
- `src/paisti/world/Portal.java`
  - Canonical directed portal record using source local cell and exact target chunk/cell.
- `src/paisti/world/ChunkData.java`
  - Flat `byte[40000]` cell flags, sparse `Map<Integer, List<Portal>> portalsByCell`, primitive getters/setters, portal indexing helpers.
- `src/paisti/world/WorldMap.java`
  - Canonical `gridId -> ChunkData` index, eager shortened-key `shortChunkKey -> ChunkData` index, `IPathfindingMap` and `IPortalMap` implementation, storage load/save hooks.
- `src/paisti/world/storage/StorageBackend.java`
  - Minimal backend contract used by `WorldMap`.
- `src/paisti/world/storage/ChunkDataCodec.java`
  - Binary read/write for one `ChunkData`, including flattened portal records.
- `src/paisti/world/storage/FileStorageBackend.java`
  - Directory-based chunk persistence using canonical full IDs only.

### New test files

- `test/unit/paisti/world/MapUtilTest.java`
  - Validates packing, unpacking, low-48-bit shortening, and coordinate validation.
- `test/unit/paisti/world/ChunkDataTest.java`
  - Validates flat cell storage, unsigned flag reads, sparse portal lookup, and out-buffer clearing behavior.
- `test/unit/paisti/world/WorldMapQueryTest.java`
  - Validates full-ID lookups, packed-cell lookups, `0xFF` invalid sentinel behavior, portal queries, and shortened-key collision logging.
- `test/unit/paisti/world/storage/ChunkDataCodecTest.java`
  - Validates chunk binary round-trip for cells and portals.
- `test/unit/paisti/world/storage/FileStorageBackendTest.java`
  - Validates save/load from disk and packed-index rebuild through `WorldMap.load()`.

### Existing files expected to remain untouched in this plan

- `src/paisti/client/PGameUI.java`
- `src/paisti/client/PMapView.java`
- `src/haven/MCache.java`
- `src/haven/Hitbox.java`

Session observation capture, portal recording, and pathfinder integration stay out of scope for this implementation plan.

---

### Task 1: Build Core World Primitives

**Files:**
- Create: `src/paisti/world/WorldMapConstants.java`
- Create: `src/paisti/world/MapUtil.java`
- Create: `src/paisti/world/IPathfindingMap.java`
- Create: `src/paisti/world/IPortalMap.java`
- Create: `src/paisti/world/PortalType.java`
- Create: `src/paisti/world/Portal.java`
- Create: `src/paisti/world/ChunkData.java`
- Test: `test/unit/paisti/world/MapUtilTest.java`
- Test: `test/unit/paisti/world/ChunkDataTest.java`

- [ ] **Step 1: Write the failing utility and chunk tests**

```java
package paisti.world;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapUtilTest {
    @Test
    @Tag("unit")
    void packRoundTripsChunkKeyAndCellCoords() {
        long fullChunkId = 0x1234_5678_9ABC_DEF0L;

        long packed = MapUtil.packChunkCellCoord(fullChunkId, 199, 17);

        assertEquals(199, MapUtil.unpackChunkCellCoordX(packed));
        assertEquals(17, MapUtil.unpackChunkCellCoordY(packed));
        assertEquals(MapUtil.shortenChunkId(fullChunkId), MapUtil.unpackChunkCellCoordShortChunkKey(packed));
    }

    @Test
    @Tag("unit")
    void shortenChunkIdUsesLowest48Bits() {
        long fullChunkId = 0xFEDC_BA98_7654_3210L;

        assertEquals(0x0000_BA98_7654_3210L, MapUtil.shortenChunkId(fullChunkId));
    }

    @Test
    @Tag("unit")
    void packRejectsOutOfRangeCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> MapUtil.packChunkCellCoord(42L, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> MapUtil.packChunkCellCoord(42L, 0, 200));
    }
}
```

```java
package paisti.world;

import haven.Coord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkDataTest {
    @Test
    @Tag("unit")
    void returnsStoredFlagsAsUnsignedInt() {
        ChunkData chunk = new ChunkData(7L, 3L, Coord.of(12, 18));
        chunk.setCellFlags(5, 9, 0xE2);

        assertEquals(0xE2, chunk.getCellFlags(5, 9));
        assertEquals(0xE2, chunk.getCellFlags(9 * WorldMapConstants.CELL_AXIS + 5));
    }

    @Test
    @Tag("unit")
    void portalLookupClearsAndRefillsOutputBuffer() {
        ChunkData chunk = new ChunkData(7L, 3L, Coord.of(12, 18));
        Portal portal = new Portal(PortalType.GATE, Coord.of(4, 8), 99L, 10, 11);
        chunk.addPortal(portal);

        List<Portal> out = new ArrayList<>();
        out.add(new Portal(PortalType.DOOR, Coord.of(0, 0), 1L, 2, 3));

        chunk.getCellPortals(4, 8, out);

        assertEquals(1, out.size());
        assertSame(portal, out.get(0));
    }

    @Test
    @Tag("unit")
    void throwsOnOutOfRangeCoordinates() {
        ChunkData chunk = new ChunkData(7L, 3L, Coord.of(12, 18));

        assertThrows(IllegalArgumentException.class, () -> chunk.getCellFlags(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> chunk.getCellFlags(0, 200));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `ant test-unit`

Expected: `BUILD FAILED` with compile errors for missing `paisti.world` classes.

- [ ] **Step 3: Write the minimal primitives and chunk model**

```java
package paisti.world;

public final class WorldMapConstants {
    public static final int CELL_AXIS = 200;
    public static final int CELL_COUNT = CELL_AXIS * CELL_AXIS;
    public static final int INVALID_CELL_FLAGS = 0xFF;
    public static final long SHORT_CHUNK_MASK = 0x0000FFFFFFFFFFFFL;

    private WorldMapConstants() {
    }
}
```

```java
package paisti.world;

public final class MapUtil {
    private MapUtil() {
    }

    public static long packChunkCellCoord(long fullChunkId, int cellX, int cellY) {
        validateCellCoord(cellX, cellY);
        long shortChunkKey = shortenChunkId(fullChunkId);
        return (shortChunkKey << 16) | ((long) cellX << 8) | (long) cellY;
    }

    public static int unpackChunkCellCoordX(long packed) {
        return (int) ((packed >>> 8) & 0xFFL);
    }

    public static int unpackChunkCellCoordY(long packed) {
        return (int) (packed & 0xFFL);
    }

    public static long unpackChunkCellCoordShortChunkKey(long packed) {
        return (packed >>> 16) & WorldMapConstants.SHORT_CHUNK_MASK;
    }

    public static long shortenChunkId(long fullChunkId) {
        return fullChunkId & WorldMapConstants.SHORT_CHUNK_MASK;
    }

    public static int cellIndex(int cellX, int cellY) {
        validateCellCoord(cellX, cellY);
        return cellY * WorldMapConstants.CELL_AXIS + cellX;
    }

    private static void validateCellCoord(int cellX, int cellY) {
        if((cellX < 0) || (cellX >= WorldMapConstants.CELL_AXIS) || (cellY < 0) || (cellY >= WorldMapConstants.CELL_AXIS)) {
            throw new IllegalArgumentException("cell coordinates out of range: (" + cellX + ", " + cellY + ")");
        }
    }
}
```

```java
package paisti.world;

public interface IPathfindingMap {
    int getCellFlags(long packedCell);
    int getCellFlags(long fullChunkId, int cellX, int cellY);
}
```

```java
package paisti.world;

import java.util.List;

public interface IPortalMap {
    void getCellPortals(long packedCell, List<Portal> out);
    void getCellPortals(long fullChunkId, int cellX, int cellY, List<Portal> out);
}
```

```java
package paisti.world;

public enum PortalType {
    DOOR,
    GATE,
    MINE,
    CELLAR,
    STAIRS
}
```

```java
package paisti.world;

import haven.Coord;

public class Portal {
    public final PortalType type;
    public final Coord sourceLocalCell;
    public final long targetChunkId;
    public final int targetCellX;
    public final int targetCellY;

    public Portal(PortalType type, Coord sourceLocalCell, long targetChunkId, int targetCellX, int targetCellY) {
        this.type = type;
        this.sourceLocalCell = sourceLocalCell;
        this.targetChunkId = targetChunkId;
        this.targetCellX = targetCellX;
        this.targetCellY = targetCellY;
    }
}
```

```java
package paisti.world;

import haven.Coord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChunkData {
    public final long gridId;
    public long segmentId;
    public Coord chunkCoord;
    public final byte[] cells;
    public final Map<Integer, List<Portal>> portalsByCell;
    public String layer = "outside";
    public long lastUpdated;
    public long version;
    public boolean dirty;

    public ChunkData(long gridId, long segmentId, Coord chunkCoord) {
        this.gridId = gridId;
        this.segmentId = segmentId;
        this.chunkCoord = chunkCoord;
        this.cells = new byte[WorldMapConstants.CELL_COUNT];
        this.portalsByCell = new HashMap<>();
    }

    public int getCellFlags(int cellIndex) {
        return cells[cellIndex] & 0xFF;
    }

    public int getCellFlags(int cellX, int cellY) {
        return getCellFlags(MapUtil.cellIndex(cellX, cellY));
    }

    public void setCellFlags(int cellX, int cellY, int flags) {
        cells[MapUtil.cellIndex(cellX, cellY)] = (byte) flags;
        dirty = true;
    }

    public void addPortal(Portal portal) {
        int cellIndex = MapUtil.cellIndex(portal.sourceLocalCell.x, portal.sourceLocalCell.y);
        portalsByCell.computeIfAbsent(cellIndex, ignored -> new ArrayList<>()).add(portal);
        dirty = true;
    }

    public void getCellPortals(int cellX, int cellY, List<Portal> out) {
        getCellPortals(MapUtil.cellIndex(cellX, cellY), out);
    }

    public void getCellPortals(int cellIndex, List<Portal> out) {
        out.clear();
        List<Portal> portals = portalsByCell.get(cellIndex);
        if(portals != null) {
            out.addAll(portals);
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `ant test-unit`

Expected: `BUILD SUCCESSFUL`, including `MapUtilTest` and `ChunkDataTest` passing.

- [ ] **Step 5: Commit**

```bash
git add src/paisti/world/WorldMapConstants.java src/paisti/world/MapUtil.java src/paisti/world/IPathfindingMap.java src/paisti/world/IPortalMap.java src/paisti/world/PortalType.java src/paisti/world/Portal.java src/paisti/world/ChunkData.java test/unit/paisti/world/MapUtilTest.java test/unit/paisti/world/ChunkDataTest.java
git commit -m "feat: add world map primitives"
```

### Task 2: Implement WorldMap Query Surfaces And Packed Indexing

**Files:**
- Create: `src/paisti/world/WorldMap.java`
- Test: `test/unit/paisti/world/WorldMapQueryTest.java`

- [ ] **Step 1: Write the failing WorldMap query tests**

```java
package paisti.world;

import haven.Coord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldMapQueryTest {
    @Test
    @Tag("unit")
    void fullIdLookupReturnsStoredFlags() {
        WorldMap map = new WorldMap();
        ChunkData chunk = new ChunkData(100L, 1L, Coord.of(0, 0));
        chunk.setCellFlags(3, 4, 0x12);
        map.putChunk(chunk);

        assertEquals(0x12, map.getCellFlags(100L, 3, 4));
    }

    @Test
    @Tag("unit")
    void missingChunkReturnsInvalidSentinel() {
        WorldMap map = new WorldMap();

        assertEquals(WorldMapConstants.INVALID_CELL_FLAGS, map.getCellFlags(999L, 3, 4));
    }

    @Test
    @Tag("unit")
    void packedLookupUsesShortenedChunkKey() {
        WorldMap map = new WorldMap();
        ChunkData chunk = new ChunkData(0x1234_5678_9ABC_DEF0L, 1L, Coord.of(0, 0));
        chunk.setCellFlags(7, 8, 0x7A);
        map.putChunk(chunk);

        long packed = MapUtil.packChunkCellCoord(chunk.gridId, 7, 8);

        assertEquals(0x7A, map.getCellFlags(packed));
    }

    @Test
    @Tag("unit")
    void packedPortalLookupClearsAndRefillsBuffer() {
        WorldMap map = new WorldMap();
        ChunkData chunk = new ChunkData(100L, 1L, Coord.of(0, 0));
        chunk.addPortal(new Portal(PortalType.CELLAR, Coord.of(5, 6), 200L, 9, 10));
        map.putChunk(chunk);

        List<Portal> out = new ArrayList<>();
        out.add(new Portal(PortalType.DOOR, Coord.of(0, 0), 1L, 2, 3));

        map.getCellPortals(MapUtil.packChunkCellCoord(100L, 5, 6), out);

        assertEquals(1, out.size());
        assertEquals(PortalType.CELLAR, out.get(0).type);
    }

    @Test
    @Tag("unit")
    void malformedPackedCoordinatesThrow() {
        WorldMap map = new WorldMap();
        long malformed = (MapUtil.shortenChunkId(1L) << 16) | ((long) 250 << 8);

        assertThrows(IllegalArgumentException.class, () -> map.getCellFlags(malformed));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `ant test-unit`

Expected: `BUILD FAILED` because `WorldMap` does not exist yet.

- [ ] **Step 3: Implement WorldMap with canonical and packed indexes**

```java
package paisti.world;

import haven.Warning;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorldMap implements IPathfindingMap, IPortalMap {
    private final Map<Long, ChunkData> chunksById = new ConcurrentHashMap<>();
    private final Map<Long, ChunkData> chunksByShortKey = new ConcurrentHashMap<>();
    private final java.util.Set<Long> collidingShortKeys = ConcurrentHashMap.newKeySet();

    public void putChunk(ChunkData chunk) {
        chunksById.put(chunk.gridId, chunk);
        reindexPackedChunk(chunk);
    }

    public ChunkData getChunk(long fullChunkId) {
        return chunksById.get(fullChunkId);
    }

    public void rebuildPackedIndex() {
        chunksByShortKey.clear();
        collidingShortKeys.clear();
        for(ChunkData chunk : chunksById.values()) {
            reindexPackedChunk(chunk);
        }
    }

    @Override
    public int getCellFlags(long packedCell) {
        int cellX = MapUtil.unpackChunkCellCoordX(packedCell);
        int cellY = MapUtil.unpackChunkCellCoordY(packedCell);
        int cellIndex = MapUtil.cellIndex(cellX, cellY);
        long shortChunkKey = MapUtil.unpackChunkCellCoordShortChunkKey(packedCell);
        if(collidingShortKeys.contains(shortChunkKey)) {
            return WorldMapConstants.INVALID_CELL_FLAGS;
        }
        ChunkData chunk = chunksByShortKey.get(shortChunkKey);
        return (chunk == null) ? WorldMapConstants.INVALID_CELL_FLAGS : chunk.getCellFlags(cellIndex);
    }

    @Override
    public int getCellFlags(long fullChunkId, int cellX, int cellY) {
        ChunkData chunk = chunksById.get(fullChunkId);
        return (chunk == null) ? WorldMapConstants.INVALID_CELL_FLAGS : chunk.getCellFlags(cellX, cellY);
    }

    @Override
    public void getCellPortals(long packedCell, List<Portal> out) {
        int cellX = MapUtil.unpackChunkCellCoordX(packedCell);
        int cellY = MapUtil.unpackChunkCellCoordY(packedCell);
        long shortChunkKey = MapUtil.unpackChunkCellCoordShortChunkKey(packedCell);
        if(collidingShortKeys.contains(shortChunkKey)) {
            out.clear();
            return;
        }
        ChunkData chunk = chunksByShortKey.get(shortChunkKey);
        if(chunk == null) {
            out.clear();
            return;
        }
        chunk.getCellPortals(cellX, cellY, out);
    }

    @Override
    public void getCellPortals(long fullChunkId, int cellX, int cellY, List<Portal> out) {
        ChunkData chunk = chunksById.get(fullChunkId);
        if(chunk == null) {
            out.clear();
            return;
        }
        chunk.getCellPortals(cellX, cellY, out);
    }

    private void reindexPackedChunk(ChunkData chunk) {
        long shortChunkKey = MapUtil.shortenChunkId(chunk.gridId);
        if(collidingShortKeys.contains(shortChunkKey)) {
            return;
        }
        ChunkData existing = chunksByShortKey.putIfAbsent(shortChunkKey, chunk);
        if((existing != null) && (existing.gridId != chunk.gridId)) {
            collidingShortKeys.add(shortChunkKey);
            chunksByShortKey.remove(shortChunkKey);
            Warning.warn("world map packed chunk-key collision for %d: %d vs %d", shortChunkKey, existing.gridId, chunk.gridId);
        }
    }
}
```

- [ ] **Step 4: Add a collision test before moving on**

Append this test to `test/unit/paisti/world/WorldMapQueryTest.java`:

```java
    @Test
    @Tag("unit")
    void collisionDisablesPackedLookupForThatShortKey() {
        WorldMap map = new WorldMap();
        ChunkData first = new ChunkData(0x0000_0000_0000_0001L, 1L, Coord.of(0, 0));
        ChunkData second = new ChunkData(0xFFFF_0000_0000_0001L, 1L, Coord.of(1, 0));
        first.setCellFlags(1, 1, 0x55);
        second.setCellFlags(1, 1, 0x66);

        map.putChunk(first);
        map.putChunk(second);

        assertEquals(WorldMapConstants.INVALID_CELL_FLAGS, map.getCellFlags(MapUtil.packChunkCellCoord(first.gridId, 1, 1)));
        assertEquals(WorldMapConstants.INVALID_CELL_FLAGS, map.getCellFlags(MapUtil.packChunkCellCoord(second.gridId, 1, 1)));
        assertEquals(0x55, map.getCellFlags(first.gridId, 1, 1));
        assertEquals(0x66, map.getCellFlags(second.gridId, 1, 1));
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `ant test-unit`

Expected: `BUILD SUCCESSFUL`, including `WorldMapQueryTest` passing.

- [ ] **Step 6: Commit**

```bash
git add src/paisti/world/WorldMap.java test/unit/paisti/world/WorldMapQueryTest.java
git commit -m "feat: add world map query indexes"
```

### Task 3: Add Canonical Chunk Persistence

**Files:**
- Create: `src/paisti/world/storage/StorageBackend.java`
- Create: `src/paisti/world/storage/ChunkDataCodec.java`
- Create: `src/paisti/world/storage/FileStorageBackend.java`
- Test: `test/unit/paisti/world/storage/ChunkDataCodecTest.java`
- Test: `test/unit/paisti/world/storage/FileStorageBackendTest.java`

- [ ] **Step 1: Write the failing storage tests**

```java
package paisti.world.storage;

import haven.Coord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.world.ChunkData;
import paisti.world.Portal;
import paisti.world.PortalType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkDataCodecTest {
    @Test
    @Tag("unit")
    void roundTripsCellsAndPortals() throws Exception {
        ChunkData chunk = new ChunkData(77L, 5L, Coord.of(3, 4));
        chunk.layer = "inside";
        chunk.version = 9L;
        chunk.lastUpdated = 11L;
        chunk.setCellFlags(2, 3, 0x44);
        chunk.addPortal(new Portal(PortalType.STAIRS, Coord.of(6, 7), 88L, 9, 10));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ChunkDataCodec codec = new ChunkDataCodec();
        codec.write(new DataOutputStream(bytes), chunk);

        ChunkData restored = codec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        ArrayList<Portal> portals = new ArrayList<>();
        restored.getCellPortals(6, 7, portals);

        assertEquals(0x44, restored.getCellFlags(2, 3));
        assertEquals("inside", restored.layer);
        assertEquals(1, portals.size());
        assertEquals(PortalType.STAIRS, portals.get(0).type);
        assertEquals(88L, portals.get(0).targetChunkId);
    }
}
```

```java
package paisti.world.storage;

import haven.Coord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import paisti.world.ChunkData;
import paisti.world.Portal;
import paisti.world.PortalType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileStorageBackendTest {
    @TempDir
    Path tempDir;

    @Test
    @Tag("unit")
    void savesAndLoadsCanonicalChunks() throws Exception {
        FileStorageBackend backend = new FileStorageBackend(tempDir);
        ChunkData chunk = new ChunkData(321L, 9L, Coord.of(12, 13));
        chunk.setCellFlags(1, 2, 0x77);
        chunk.addPortal(new Portal(PortalType.MINE, Coord.of(4, 5), 654L, 6, 7));

        backend.saveChunk(chunk);

        Collection<ChunkData> loaded = backend.loadChunks();
        ChunkData restored = loaded.iterator().next();
        ArrayList<Portal> portals = new ArrayList<>();
        restored.getCellPortals(4, 5, portals);

        assertEquals(321L, restored.gridId);
        assertEquals(0x77, restored.getCellFlags(1, 2));
        assertEquals(1, portals.size());
        assertEquals(654L, portals.get(0).targetChunkId);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `ant test-unit`

Expected: `BUILD FAILED` with missing `paisti.world.storage` classes.

- [ ] **Step 3: Implement the storage contract and chunk codec**

```java
package paisti.world.storage;

import paisti.world.ChunkData;

import java.io.IOException;
import java.util.Collection;

public interface StorageBackend extends AutoCloseable {
    Collection<ChunkData> loadChunks() throws IOException;
    void saveChunk(ChunkData chunk) throws IOException;
    void flush() throws IOException;

    @Override
    void close() throws IOException;
}
```

```java
package paisti.world.storage;

import haven.Coord;
import paisti.world.ChunkData;
import paisti.world.Portal;
import paisti.world.PortalType;
import paisti.world.WorldMapConstants;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChunkDataCodec {
    private static final int FORMAT_VERSION = 1;

    public void write(DataOutput out, ChunkData chunk) throws IOException {
        out.writeInt(FORMAT_VERSION);
        out.writeLong(chunk.gridId);
        out.writeLong(chunk.segmentId);
        out.writeInt(chunk.chunkCoord.x);
        out.writeInt(chunk.chunkCoord.y);
        out.writeUTF(chunk.layer);
        out.writeLong(chunk.lastUpdated);
        out.writeLong(chunk.version);
        out.write(chunk.cells);

        int portalCount = 0;
        for(List<Portal> portals : chunk.portalsByCell.values()) {
            portalCount += portals.size();
        }
        out.writeInt(portalCount);
        for(Map.Entry<Integer, List<Portal>> entry : chunk.portalsByCell.entrySet()) {
            for(Portal portal : entry.getValue()) {
                out.writeUTF(portal.type.name());
                out.writeInt(portal.sourceLocalCell.x);
                out.writeInt(portal.sourceLocalCell.y);
                out.writeLong(portal.targetChunkId);
                out.writeInt(portal.targetCellX);
                out.writeInt(portal.targetCellY);
            }
        }
    }

    public ChunkData read(DataInput in) throws IOException {
        int version = in.readInt();
        if(version != FORMAT_VERSION) {
            throw new IOException("unsupported chunk format version: " + version);
        }
        ChunkData chunk = new ChunkData(in.readLong(), in.readLong(), Coord.of(in.readInt(), in.readInt()));
        chunk.layer = in.readUTF();
        chunk.lastUpdated = in.readLong();
        chunk.version = in.readLong();
        in.readFully(chunk.cells, 0, WorldMapConstants.CELL_COUNT);

        int portalCount = in.readInt();
        for(int i = 0; i < portalCount; i++) {
            Portal portal = new Portal(
                PortalType.valueOf(in.readUTF()),
                Coord.of(in.readInt(), in.readInt()),
                in.readLong(),
                in.readInt(),
                in.readInt()
            );
            chunk.addPortal(portal);
        }
        chunk.dirty = false;
        return chunk;
    }
}
```

- [ ] **Step 4: Implement the filesystem backend**

```java
package paisti.world.storage;

import paisti.world.ChunkData;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Stream;

public class FileStorageBackend implements StorageBackend {
    private final Path chunksDir;
    private final ChunkDataCodec codec = new ChunkDataCodec();

    public FileStorageBackend(Path baseDir) throws IOException {
        this.chunksDir = baseDir.resolve("chunks");
        Files.createDirectories(chunksDir);
    }

    @Override
    public Collection<ChunkData> loadChunks() throws IOException {
        ArrayList<ChunkData> chunks = new ArrayList<>();
        try(Stream<Path> stream = Files.list(chunksDir)) {
            for(java.util.Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
                Path file = it.next();
                if(!file.getFileName().toString().endsWith(".bin")) {
                    continue;
                }
                try(InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                    chunks.add(codec.read(new java.io.DataInputStream(input)));
                }
            }
        }
        return chunks;
    }

    @Override
    public void saveChunk(ChunkData chunk) throws IOException {
        Path target = chunksDir.resolve(chunk.gridId + ".bin");
        Path temp = chunksDir.resolve(chunk.gridId + ".bin.tmp");
        try(OutputStream output = new BufferedOutputStream(Files.newOutputStream(temp))) {
            codec.write(new java.io.DataOutputStream(output), chunk);
        }
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        chunk.dirty = false;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `ant test-unit`

Expected: `BUILD SUCCESSFUL`, including `ChunkDataCodecTest` and `FileStorageBackendTest` passing.

- [ ] **Step 6: Commit**

```bash
git add src/paisti/world/storage/StorageBackend.java src/paisti/world/storage/ChunkDataCodec.java src/paisti/world/storage/FileStorageBackend.java test/unit/paisti/world/storage/ChunkDataCodecTest.java test/unit/paisti/world/storage/FileStorageBackendTest.java
git commit -m "feat: add persisted chunk storage"
```

### Task 4: Wire WorldMap Load/Save Lifecycle

**Files:**
- Modify: `src/paisti/world/WorldMap.java`
- Modify: `src/paisti/world/ChunkData.java`
- Test: `test/unit/paisti/world/storage/FileStorageBackendTest.java`

- [ ] **Step 1: Extend the storage test to drive WorldMap load/save**

Append this test to `test/unit/paisti/world/storage/FileStorageBackendTest.java`:

```java
    @Test
    @Tag("unit")
    void worldMapLoadRebuildsPackedLookup() throws Exception {
        FileStorageBackend backend = new FileStorageBackend(tempDir);
        ChunkData chunk = new ChunkData(0x1234_5678_9ABC_DEF0L, 9L, Coord.of(12, 13));
        chunk.setCellFlags(11, 12, 0xA1);
        backend.saveChunk(chunk);

        paisti.world.WorldMap map = new paisti.world.WorldMap(backend);
        map.load();

        long packed = paisti.world.MapUtil.packChunkCellCoord(chunk.gridId, 11, 12);
        assertEquals(0xA1, map.getCellFlags(packed));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `ant test-unit`

Expected: `BUILD FAILED` because `WorldMap(StorageBackend)` and `load()` do not exist yet.

- [ ] **Step 3: Add storage-aware lifecycle to WorldMap**

Merge these additions into the existing `WorldMap` from Task 2. Keep the query and packed-index methods from Task 2 exactly as they are; only add the constructor overload, `StorageBackend` field, and lifecycle methods shown below.

```java
package paisti.world;

import haven.Warning;
import paisti.world.storage.StorageBackend;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorldMap implements IPathfindingMap, IPortalMap {
    private final Map<Long, ChunkData> chunksById = new ConcurrentHashMap<>();
    private final Map<Long, ChunkData> chunksByShortKey = new ConcurrentHashMap<>();
    private final StorageBackend storage;

    public WorldMap() {
        this(null);
    }

    public WorldMap(StorageBackend storage) {
        this.storage = storage;
    }

    public void load() throws IOException {
        if(storage == null) {
            return;
        }
        chunksById.clear();
        chunksByShortKey.clear();
        for(ChunkData chunk : storage.loadChunks()) {
            chunk.dirty = false;
            putChunk(chunk);
        }
    }

    public void saveDirtyChunks() throws IOException {
        if(storage == null) {
            return;
        }
        for(ChunkData chunk : chunksById.values()) {
            if(chunk.dirty) {
                storage.saveChunk(chunk);
            }
        }
        storage.flush();
    }

    public void close() throws IOException {
        if(storage != null) {
            storage.close();
        }
    }
}
```

Also add this helper to `src/paisti/world/ChunkData.java` so later observation code can mark loaded chunks clean explicitly without reaching into fields everywhere:

```java
    public void markClean() {
        dirty = false;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `ant test-unit`

Expected: `BUILD SUCCESSFUL`, with the new `worldMapLoadRebuildsPackedLookup` test passing.

- [ ] **Step 5: Commit**

```bash
git add src/paisti/world/WorldMap.java src/paisti/world/ChunkData.java test/unit/paisti/world/storage/FileStorageBackendTest.java
git commit -m "feat: wire world map persistence lifecycle"
```

---

## Self-Review

### Spec coverage

- Canonical full-ID chunk model: Task 1
- Flat `byte[40000]` cell storage: Task 1
- Sparse `Map<Integer, List<Portal>> portalsByCell`: Task 1
- `IPathfindingMap` and `IPortalMap` query surfaces: Task 1 and Task 2
- Packed `long` runtime cell format and 48-bit shortened chunk key: Task 1
- Global eager `shortChunkKey -> ChunkData` index in `WorldMap`: Task 2
- Visible collision logging and packed-key disable on collision: Task 2
- Canonical persistence without storing packed keys: Task 3
- `WorldMap` load/save hooks rebuilding packed lookup on load: Task 4

### Placeholder scan

- No `TODO`, `TBD`, or "implement later" markers remain.
- Each task lists exact file paths.
- Every code-writing step includes concrete class and method shapes.
- Every verification step includes an exact command and expected result.

### Type consistency

- `getCellFlags(...)` returns `int` everywhere.
- Packed helpers use `long` for packed cells and shortened chunk keys.
- Portal destination stays canonical as `targetChunkId`, `targetCellX`, `targetCellY`.
- Packed runtime lookup is isolated to `MapUtil` and `WorldMap`.
