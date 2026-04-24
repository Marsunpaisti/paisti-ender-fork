package paisti.world;

import haven.Coord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.world.storage.StorageBackend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldMapQueryTest {
    @Test
    @Tag("unit")
    void fullIdLookupReturnsStoredFlags() {
        WorldMap worldMap = new WorldMap();
        ChunkData chunk = chunk(0x123456789ABCDEFL);
        int flags = WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER;

        chunk.setCellFlags(12, 34, flags);

        worldMap.putChunk(chunk);

        assertSame(chunk, worldMap.getChunk(chunk.gridId));
        assertEquals(flags, worldMap.getCellFlags(chunk.gridId, 12, 34));
    }

    @Test
    @Tag("unit")
    void missingChunkReturnsInvalidSentinel() {
        WorldMap worldMap = new WorldMap();

        assertEquals(WorldMapConstants.INVALID_CELL_FLAGS, worldMap.getCellFlags(9999L, 12, 34));
        assertEquals(WorldMapConstants.INVALID_CELL_FLAGS, worldMap.getCellFlags(MapUtil.packChunkCellCoord(9999L, 12, 34)));
    }

    @Test
    @Tag("unit")
    void packedLookupUsesShortenedChunkKey() {
        long fullChunkId = 0x123456789ABCDEFL;
        WorldMap worldMap = new WorldMap();
        ChunkData chunk = chunk(fullChunkId);
        int flags = WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_OBSERVED;

        chunk.setCellFlags(17, 23, flags);

        worldMap.putChunk(chunk);

        assertEquals(flags, worldMap.getCellFlags(MapUtil.packChunkCellCoord(fullChunkId, 17, 23)));
    }

    @Test
    @Tag("unit")
    void packedPortalLookupClearsAndRefillsBuffer() {
        long fullChunkId = 0x123456789ABCDEFL;
        WorldMap worldMap = new WorldMap();
        ChunkData chunk = chunk(fullChunkId);
        Portal portal = new Portal(PortalType.DOOR, Coord.of(7, 8), 9876L, 11, 12);
        List<Portal> out = new ArrayList<>();

        chunk.addPortal(portal);
        worldMap.putChunk(chunk);
        out.add(new Portal(PortalType.GATE, Coord.of(1, 1), 2L, 3, 4));

        worldMap.getCellPortals(MapUtil.packChunkCellCoord(fullChunkId, 7, 8), out);

        assertEquals(List.of(portal), out);
    }

    @Test
    @Tag("unit")
    void malformedPackedCoordinatesThrow() {
        WorldMap worldMap = new WorldMap();
        long malformedPackedCell = (MapUtil.shortenChunkId(55L) << 16) | ((long) 255 << 8);

        assertThrows(IllegalArgumentException.class, () -> worldMap.getCellFlags(malformedPackedCell));
    }

    @Test
    @Tag("unit")
    void collisionDisablesPackedLookupForThatShortKey() {
        long firstChunkId = 0x0000123456789ABCL;
        long secondChunkId = 0x1111123456789ABCL;
        long packedCell = MapUtil.packChunkCellCoord(firstChunkId, 9, 10);
        WorldMap worldMap = new WorldMap();
        ChunkData firstChunk = chunk(firstChunkId);
        ChunkData secondChunk = chunk(secondChunkId);
        List<Portal> out = new ArrayList<>();

        firstChunk.setCellFlags(9, 10, WorldMapConstants.CELL_BLOCKED_TERRAIN);
        secondChunk.setCellFlags(9, 10, WorldMapConstants.CELL_DEEP_WATER);
        secondChunk.addPortal(new Portal(PortalType.MINE, Coord.of(9, 10), 1234L, 1, 2));
        worldMap.putChunk(firstChunk);
        worldMap.putChunk(secondChunk);
        out.add(new Portal(PortalType.GATE, Coord.of(1, 1), 2L, 3, 4));

        assertEquals(WorldMapConstants.INVALID_CELL_FLAGS, worldMap.getCellFlags(packedCell));
        worldMap.getCellPortals(packedCell, out);
        assertEquals(List.of(), out);
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, worldMap.getCellFlags(firstChunkId, 9, 10));
        assertEquals(WorldMapConstants.CELL_DEEP_WATER, worldMap.getCellFlags(secondChunkId, 9, 10));
    }

    @Test
    @Tag("unit")
    void replacingChunkWithSameGridIdUpdatesCanonicalAndPackedLookups() {
        long fullChunkId = 0x123456789ABCDEFL;
        WorldMap worldMap = new WorldMap();
        ChunkData firstChunk = chunk(fullChunkId);
        ChunkData replacementChunk = chunk(fullChunkId);

        firstChunk.setCellFlags(5, 6, WorldMapConstants.CELL_OBSERVED);
        replacementChunk.setCellFlags(5, 6, WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_OBSERVED);
        worldMap.putChunk(firstChunk);
        worldMap.putChunk(replacementChunk);

        assertSame(replacementChunk, worldMap.getChunk(fullChunkId));
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_OBSERVED, worldMap.getCellFlags(fullChunkId, 5, 6));
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_OBSERVED, worldMap.getCellFlags(MapUtil.packChunkCellCoord(fullChunkId, 5, 6)));
    }

    @Test
    @Tag("unit")
    void collisionWarningIsLoggedOnlyOncePerShortKey() {
        long firstChunkId = 0x0000123456789ABCL;
        long secondChunkId = 0x1111123456789ABCL;
        long thirdChunkId = 0x2222123456789ABCL;
        WorldMap worldMap = new WorldMap();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;

        try (PrintStream captureErr = new PrintStream(errBytes, true)) {
            System.setErr(captureErr);
            worldMap.putChunk(chunk(firstChunkId));
            worldMap.putChunk(chunk(secondChunkId));
            worldMap.rebuildPackedIndex();
            worldMap.putChunk(chunk(thirdChunkId));
        } finally {
            System.setErr(originalErr);
        }

        String warningText = errBytes.toString();
        assertEquals(1, countOccurrences(warningText, "world map packed chunk-key collision for 20015998343868"));
    }

    @Test
    @Tag("unit")
    void loadKeepsExistingMapContentsWhenStorageLoadFails() {
        long existingChunkId = 0x123456789ABCDEFL;
        WorldMap worldMap = new WorldMap(new StorageBackend() {
            @Override
            public Collection<ChunkData> loadChunks() throws IOException {
                throw new IOException("boom");
            }

            @Override
            public void saveChunk(ChunkData chunk) {
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        ChunkData existingChunk = chunk(existingChunkId);
        int flags = WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER | WorldMapConstants.CELL_OBSERVED;
        existingChunk.setCellFlags(5, 6, flags);
        worldMap.putChunk(existingChunk);

        IOException error = assertThrows(IOException.class, worldMap::load);

        assertEquals("boom", error.getMessage());
        assertSame(existingChunk, worldMap.getChunk(existingChunkId));
        assertEquals(flags, worldMap.getCellFlags(existingChunkId, 5, 6));
        assertEquals(flags, worldMap.getCellFlags(MapUtil.packChunkCellCoord(existingChunkId, 5, 6)));
    }

    @Test
    @Tag("unit")
    void saveDirtyChunksKeepsChunkDirtyWhenFlushFails() {
        ChunkData chunk = chunk(0x123456789ABCDEFL);
        chunk.setCellFlags(5, 6, WorldMapConstants.CELL_FLAGS_MASK);
        List<ChunkData> savedChunks = new ArrayList<>();
        WorldMap worldMap = new WorldMap(new StorageBackend() {
            @Override
            public Collection<ChunkData> loadChunks() {
                return List.of();
            }

            @Override
            public void saveChunk(ChunkData chunk) {
                savedChunks.add(chunk);
            }

            @Override
            public void flush() throws IOException {
                throw new IOException("flush failed");
            }

            @Override
            public void close() {
            }
        });
        worldMap.putChunk(chunk);

        IOException error = assertThrows(IOException.class, worldMap::saveDirtyChunks);

        assertEquals("flush failed", error.getMessage());
        assertEquals(List.of(chunk), savedChunks);
        assertEquals(true, chunk.dirty);
    }

    @Test
    @Tag("unit")
    void saveDirtyChunksMarksChunkCleanAfterSuccessfulFlush() throws IOException {
        ChunkData chunk = chunk(0x123456789ABCDEFL);
        chunk.setCellFlags(5, 6, WorldMapConstants.CELL_FLAGS_MASK);
        List<ChunkData> savedChunks = new ArrayList<>();
        List<String> callOrder = new ArrayList<>();
        WorldMap worldMap = new WorldMap(new StorageBackend() {
            @Override
            public Collection<ChunkData> loadChunks() {
                return List.of();
            }

            @Override
            public void saveChunk(ChunkData chunk) {
                callOrder.add("save");
                savedChunks.add(chunk);
            }

            @Override
            public void flush() {
                callOrder.add("flush");
            }

            @Override
            public void close() {
            }
        });
        worldMap.putChunk(chunk);

        worldMap.saveDirtyChunks();

        assertEquals(List.of(chunk), savedChunks);
        assertEquals(List.of("save", "flush"), callOrder);
        assertEquals(false, chunk.dirty);
    }

    private static ChunkData chunk(long gridId) {
        return new ChunkData(gridId, 1L, Coord.of(0, 0));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int start = 0;
        while ((start = haystack.indexOf(needle, start)) >= 0) {
            count++;
            start += needle.length();
        }
        return count;
    }
}
