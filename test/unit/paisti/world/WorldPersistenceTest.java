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

    @Test
    @Tag("unit")
    void applyingTerrainPreservesExistingObservedBit() throws IOException {
        TestClock clock = new TestClock();
        WorldMap worldMap = new WorldMap();
        ChunkData existing = new ChunkData(1001L, 0L, Coord.of(3, 4));
        existing.setCellFlags(4, 6, WorldMapConstants.CELL_OBSERVED);
        existing.markClean();
        worldMap.putChunk(existing);
        WorldPersistence persistence = new WorldPersistence(worldMap, clock::now, WorldMap::saveDirtyChunks);
        byte[] flags = new byte[WorldMapConstants.CELL_COUNT];
        flags[MapUtil.cellIndex(4, 6)] = (byte) WorldMapConstants.CELL_BLOCKED_TERRAIN;

        persistence.enqueueLoadedGrids(List.of(new WorldPersistence.LoadedGrid(1001L, Coord.of(3, 4), Coord.of(300, 400), flags)));
        clock.now = 500;
        persistence.tick();

        assertEquals(WorldMapConstants.CELL_OBSERVED | WorldMapConstants.CELL_BLOCKED_TERRAIN, worldMap.getCellFlags(1001L, 4, 6));
    }

    private static class TestClock {
        long now;

        long now() {
            return now;
        }
    }
}
