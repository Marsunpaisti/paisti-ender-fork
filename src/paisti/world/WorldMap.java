package paisti.world;

import haven.Warning;
import paisti.world.storage.StorageBackend;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class WorldMap implements IPathfindingMap, IPortalMap, AutoCloseable {
    private final Map<Long, ChunkData> chunksById = new HashMap<>();
    private final Map<Long, ChunkData> chunksByShortKey = new HashMap<>();
    private final Set<Long> collidingShortChunkKeys = new HashSet<>();
    private final Set<Long> warnedCollidingShortChunkKeys = new HashSet<>();

    private final StorageBackend storage;

    public WorldMap() {
        this(null);
    }

    public WorldMap(StorageBackend storage) {
        this.storage = storage;
    }

    public void putChunk(ChunkData chunk) {
        ChunkData safeChunk = Objects.requireNonNull(chunk, "chunk");
        chunksById.put(safeChunk.gridId, safeChunk);
        indexChunk(safeChunk);
    }

    public ChunkData getChunk(long fullChunkId) {
        return chunksById.get(fullChunkId);
    }

    public void rebuildPackedIndex() {
        chunksByShortKey.clear();
        collidingShortChunkKeys.clear();

        for (ChunkData chunk : chunksById.values()) {
            indexChunk(chunk);
        }
    }

    public void load() throws IOException {
        if (storage == null) {
            return;
        }

        Collection<ChunkData> loadedChunks = storage.loadChunks();

        chunksById.clear();
        chunksByShortKey.clear();
        collidingShortChunkKeys.clear();

        for (ChunkData chunk : loadedChunks) {
            chunk.markClean();
            putChunk(chunk);
        }
    }

    public void saveDirtyChunks() throws IOException {
        if (storage == null) {
            return;
        }

        List<ChunkData> savedChunks = new java.util.ArrayList<>();
        for (ChunkData chunk : chunksById.values()) {
            if (chunk.dirty) {
                storage.saveChunk(chunk);
                savedChunks.add(chunk);
            }
        }
        storage.flush();
        for (ChunkData chunk : savedChunks) {
            chunk.markClean();
        }
    }

    @Override
    public void close() throws IOException {
        if (storage != null) {
            storage.close();
        }
    }

    @Override
    public int getCellFlags(long packedCell) {
        int cellX = MapUtil.unpackChunkCellCoordX(packedCell);
        int cellY = MapUtil.unpackChunkCellCoordY(packedCell);
        int cellIndex = MapUtil.cellIndex(cellX, cellY);
        ChunkData chunk = getPackedChunk(MapUtil.unpackChunkCellCoordShortChunkKey(packedCell));
        if (chunk == null) {
            return WorldMapConstants.INVALID_CELL_FLAGS;
        }
        return chunk.getCellFlags(cellIndex);
    }

    @Override
    public int getCellFlags(long fullChunkId, int cellX, int cellY) {
        ChunkData chunk = chunksById.get(fullChunkId);
        if (chunk == null) {
            return WorldMapConstants.INVALID_CELL_FLAGS;
        }
        return chunk.getCellFlags(cellX, cellY);
    }

    @Override
    public void getCellPortals(long packedCell, List<Portal> out) {
        List<Portal> safeOut = Objects.requireNonNull(out, "out");
        int cellX = MapUtil.unpackChunkCellCoordX(packedCell);
        int cellY = MapUtil.unpackChunkCellCoordY(packedCell);
        int cellIndex = MapUtil.cellIndex(cellX, cellY);
        ChunkData chunk = getPackedChunk(MapUtil.unpackChunkCellCoordShortChunkKey(packedCell));
        if (chunk == null) {
            safeOut.clear();
            return;
        }
        chunk.getCellPortals(cellIndex, safeOut);
    }

    @Override
    public void getCellPortals(long fullChunkId, int cellX, int cellY, List<Portal> out) {
        List<Portal> safeOut = Objects.requireNonNull(out, "out");
        ChunkData chunk = chunksById.get(fullChunkId);
        if (chunk == null) {
            safeOut.clear();
            return;
        }
        chunk.getCellPortals(cellX, cellY, safeOut);
    }

    private ChunkData getPackedChunk(long shortChunkKey) {
        if (collidingShortChunkKeys.contains(shortChunkKey)) {
            return null;
        }
        return chunksByShortKey.get(shortChunkKey);
    }

    private void indexChunk(ChunkData chunk) {
        long shortChunkKey = MapUtil.shortenChunkId(chunk.gridId);
        if (collidingShortChunkKeys.contains(shortChunkKey)) {
            return;
        }

        ChunkData existing = chunksByShortKey.get(shortChunkKey);
        if ((existing == null) || (existing.gridId == chunk.gridId)) {
            chunksByShortKey.put(shortChunkKey, chunk);
            return;
        }

        chunksByShortKey.remove(shortChunkKey);
        collidingShortChunkKeys.add(shortChunkKey);
        if (warnedCollidingShortChunkKeys.add(shortChunkKey)) {
            Warning.warn("world map packed chunk-key collision for %d: %d vs %d", shortChunkKey, existing.gridId, chunk.gridId);
        }
    }
}
