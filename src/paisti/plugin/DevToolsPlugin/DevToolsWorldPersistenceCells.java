package paisti.plugin.DevToolsPlugin;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import paisti.world.WorldMapConstants;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

final class DevToolsWorldPersistenceCells {
    static final Color DEEP_WATER_COLOR = new Color(40, 110, 255, 100);
    static final Color BLOCKED_TERRAIN_COLOR = new Color(255, 50, 50, 100);
    static final Color OBSERVED_COLOR = new Color(255, 190, 30, 90);

    private DevToolsWorldPersistenceCells() {
    }

    static List<CellSample> cellsForTile(Coord tile) {
        Coord gridCoord = tile.div(MCache.cmaps);
        Coord localTile = tile.sub(gridCoord.mul(MCache.cmaps));
        List<CellSample> cells = new ArrayList<>(4);
        cells.add(cell(gridCoord, localTile, tile, 0, 0));
        cells.add(cell(gridCoord, localTile, tile, 1, 0));
        cells.add(cell(gridCoord, localTile, tile, 0, 1));
        cells.add(cell(gridCoord, localTile, tile, 1, 1));
        return cells;
    }

    static boolean isInterestingFlags(int flags) {
        return (flags != 0) && (flags != WorldMapConstants.INVALID_CELL_FLAGS);
    }

    static Color colorForFlags(int flags) {
        if((flags & WorldMapConstants.CELL_DEEP_WATER) != 0)
            return DEEP_WATER_COLOR;
        if((flags & WorldMapConstants.CELL_BLOCKED_TERRAIN) != 0)
            return BLOCKED_TERRAIN_COLOR;
        if((flags & WorldMapConstants.CELL_OBSERVED) != 0)
            return OBSERVED_COLOR;
        return null;
    }

    static Coord2d cellSize() {
        return MCache.tilesz.div(2.0);
    }

    private static CellSample cell(Coord gridCoord, Coord localTile, Coord tile, int halfX, int halfY) {
        Coord2d tileOrigin = tile.mul(MCache.tilesz);
        Coord2d cellOrigin = tileOrigin.add(MCache.tilesz.x * 0.5 * halfX, MCache.tilesz.y * 0.5 * halfY);
        return new CellSample(gridCoord, (localTile.x * 2) + halfX, (localTile.y * 2) + halfY, cellOrigin);
    }

    static final class CellSample {
        final Coord gridCoord;
        final int localCellX;
        final int localCellY;
        final Coord2d worldOrigin;

        private CellSample(Coord gridCoord, int localCellX, int localCellY, Coord2d worldOrigin) {
            this.gridCoord = gridCoord;
            this.localCellX = localCellX;
            this.localCellY = localCellY;
            this.worldOrigin = worldOrigin;
        }
    }
}
