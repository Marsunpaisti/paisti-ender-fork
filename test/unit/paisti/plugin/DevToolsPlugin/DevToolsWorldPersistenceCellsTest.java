package paisti.plugin.DevToolsPlugin;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.world.WorldMapConstants;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevToolsWorldPersistenceCellsTest {
    @Test
    @Tag("unit")
    void gridOriginTileMapsToFirstFourHalfCells() {
        Coord gridCoord = Coord.of(3, -2);
        Coord tile = gridCoord.mul(MCache.cmaps);

        List<DevToolsWorldPersistenceCells.CellSample> cells = DevToolsWorldPersistenceCells.cellsForTile(tile);

        assertEquals(4, cells.size());
        assertCell(cells.get(0), gridCoord, 0, 0, tile.mul(MCache.tilesz));
        assertCell(cells.get(1), gridCoord, 1, 0, tile.mul(MCache.tilesz).add(MCache.tilesz.x / 2.0, 0));
        assertCell(cells.get(2), gridCoord, 0, 1, tile.mul(MCache.tilesz).add(0, MCache.tilesz.y / 2.0));
        assertCell(cells.get(3), gridCoord, 1, 1, tile.mul(MCache.tilesz).add(MCache.tilesz.x / 2.0, MCache.tilesz.y / 2.0));
    }

    @Test
    @Tag("unit")
    void lastTileInGridMapsToLastFourHalfCells() {
        Coord gridCoord = Coord.of(-2, 4);
        Coord tile = gridCoord.mul(MCache.cmaps).add(99, 99);

        List<DevToolsWorldPersistenceCells.CellSample> cells = DevToolsWorldPersistenceCells.cellsForTile(tile);

        assertCell(cells.get(0), gridCoord, 198, 198, tile.mul(MCache.tilesz));
        assertCell(cells.get(1), gridCoord, 199, 198, tile.mul(MCache.tilesz).add(MCache.tilesz.x / 2.0, 0));
        assertCell(cells.get(2), gridCoord, 198, 199, tile.mul(MCache.tilesz).add(0, MCache.tilesz.y / 2.0));
        assertCell(cells.get(3), gridCoord, 199, 199, tile.mul(MCache.tilesz).add(MCache.tilesz.x / 2.0, MCache.tilesz.y / 2.0));
    }

    @Test
    @Tag("unit")
    void interestingFlagsExcludePassableAndInvalidSentinel() {
        assertFalse(DevToolsWorldPersistenceCells.isInterestingFlags(0));
        assertFalse(DevToolsWorldPersistenceCells.isInterestingFlags(WorldMapConstants.INVALID_CELL_FLAGS));
        assertTrue(DevToolsWorldPersistenceCells.isInterestingFlags(WorldMapConstants.CELL_BLOCKED_TERRAIN));
        assertTrue(DevToolsWorldPersistenceCells.isInterestingFlags(WorldMapConstants.CELL_DEEP_WATER));
        assertTrue(DevToolsWorldPersistenceCells.isInterestingFlags(WorldMapConstants.CELL_OBSERVED));
    }

    @Test
    @Tag("unit")
    void colorPriorityUsesDeepWaterBeforeBlockedBeforeObserved() {
        assertEquals(new Color(40, 110, 255, 100), DevToolsWorldPersistenceCells.colorForFlags(
            WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER));
        assertEquals(new Color(255, 50, 50, 100), DevToolsWorldPersistenceCells.colorForFlags(
            WorldMapConstants.CELL_BLOCKED_TERRAIN));
        assertEquals(new Color(255, 190, 30, 90), DevToolsWorldPersistenceCells.colorForFlags(
            WorldMapConstants.CELL_OBSERVED));
    }

    private static void assertCell(DevToolsWorldPersistenceCells.CellSample cell, Coord gridCoord, int cellX, int cellY, Coord2d worldOrigin) {
        assertEquals(gridCoord, cell.gridCoord);
        assertEquals(cellX, cell.localCellX);
        assertEquals(cellY, cell.localCellY);
        assertEquals(worldOrigin, cell.worldOrigin);
    }
}
