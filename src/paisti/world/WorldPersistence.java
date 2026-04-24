package paisti.world;

import haven.Coord;
import haven.MCache;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

public class WorldPersistence implements AutoCloseable {
    static final long GRID_BATCH_DEBOUNCE_MS = 500L;

    private static final int TERRAIN_FLAGS_MASK = WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER;

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

    synchronized void enqueueLoadedGrids(Collection<LoadedGrid> grids) {
        if(grids.isEmpty())
            return;
        for(LoadedGrid grid : grids)
            pendingGrids.put(grid.gridId, grid);
        lastEnqueueMillis = clockMillis.getAsLong();
    }

    public synchronized void enqueueMCacheGrids(Collection<MCache.Grid> grids) {
        if(grids.isEmpty())
            return;
        for(MCache.Grid grid : grids)
            pendingGrids.put(grid.id, snapshotGrid(grid));
        lastEnqueueMillis = clockMillis.getAsLong();
    }

    public synchronized void tick() throws IOException {
        if(pendingGrids.isEmpty())
            return;
        if((clockMillis.getAsLong() - lastEnqueueMillis) < GRID_BATCH_DEBOUNCE_MS)
            return;
        applyBatch(drainPendingGrids());
    }

    private LoadedGrid snapshotGrid(MCache.Grid grid) {
        byte[] flags = new byte[WorldMapConstants.CELL_COUNT];
        for(int tileY = 0; tileY < WorldMapConstants.CELL_AXIS / 2; tileY++) {
            for(int tileX = 0; tileX < WorldMapConstants.CELL_AXIS / 2; tileX++) {
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

    private void applyBatch(Map<Long, LoadedGrid> batch) throws IOException {
        for(LoadedGrid grid : batch.values())
            applyLoadedGrid(grid);
        saveAction.save(worldMap);
    }

    private static int mergeTerrainFlags(int existingFlags, int incomingTerrainFlags) {
        int dynamicFlags = existingFlags & WorldMapConstants.CELL_OBSERVED;
        int terrainFlags = incomingTerrainFlags & TERRAIN_FLAGS_MASK;
        return dynamicFlags | terrainFlags;
    }

    private void applyLoadedGrid(LoadedGrid grid) {
        long now = clockMillis.getAsLong();
        ChunkData chunk = worldMap.getChunk(grid.gridId);
        if(chunk == null) {
            chunk = new ChunkData(grid.gridId, 0L, grid.gridCoord);
            chunk.dirty = true;
            worldMap.putChunk(chunk);
        } else if(!chunk.chunkCoord.equals(grid.gridCoord)) {
            chunk.chunkCoord = grid.gridCoord;
            chunk.dirty = true;
        }

        for(int i = 0; i < WorldMapConstants.CELL_COUNT; i++) {
            chunk.setCellFlags(i, mergeTerrainFlags(chunk.getCellFlags(i), Byte.toUnsignedInt(grid.cellFlags[i])));
        }
        if(chunk.lastUpdated != now) {
            chunk.lastUpdated = now;
            chunk.dirty = true;
        }
    }

    private synchronized void flushPendingNow() throws IOException {
        if(pendingGrids.isEmpty())
            return;
        applyBatch(drainPendingGrids());
    }

    private Map<Long, LoadedGrid> drainPendingGrids() {
        Map<Long, LoadedGrid> batch = new LinkedHashMap<>(pendingGrids);
        pendingGrids.clear();
        return batch;
    }

    @Override
    public synchronized void close() throws IOException {
        IOException first = null;
        try {
            flushPendingNow();
        } catch(IOException e) {
            first = e;
        }
        try {
            worldMap.close();
        } catch(IOException e) {
            if(first == null)
                first = e;
            else
                first.addSuppressed(e);
        }
        if(first != null)
            throw first;
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
            this.gridCoord = Objects.requireNonNull(gridCoord, "gridCoord");
            this.worldTileOrigin = Objects.requireNonNull(worldTileOrigin, "worldTileOrigin");
            Objects.requireNonNull(cellFlags, "cellFlags");
            if(cellFlags.length != WorldMapConstants.CELL_COUNT)
                throw new IllegalArgumentException("cellFlags length must be " + WorldMapConstants.CELL_COUNT);
            this.cellFlags = Arrays.copyOf(cellFlags, cellFlags.length);
        }
    }
}
