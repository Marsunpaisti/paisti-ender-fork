package paisti.world;

import haven.Coord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkDataTest {
    @Test
    @Tag("unit")
    void fieldsMatchPlannedTypesAndMutability() {
        ChunkData chunkData = new ChunkData(1001L, 2002L, Coord.of(3, 4));
        Coord replacementCoord = Coord.of(8, 9);

        long gridId = chunkData.gridId;
        long segmentId = chunkData.segmentId;
        Coord chunkCoord = chunkData.chunkCoord;
        byte[] cells = chunkData.cells;
        Map<Integer, List<Portal>> portalsByCell = chunkData.portalsByCell;
        String layer = chunkData.layer;
        long lastUpdated = chunkData.lastUpdated;
        long version = chunkData.version;
        boolean dirty = chunkData.dirty;

        chunkData.segmentId = 3003L;
        chunkData.chunkCoord = replacementCoord;
        chunkData.layer = "inside";
        chunkData.lastUpdated = 4004L;
        chunkData.version = 5005L;
        chunkData.dirty = true;

        assertEquals(1001L, gridId);
        assertEquals(2002L, segmentId);
        assertEquals(Coord.of(3, 4), chunkCoord);
        assertEquals(WorldMapConstants.CELL_COUNT, cells.length);
        assertEquals(new HashMap<Integer, List<Portal>>(), portalsByCell);
        assertEquals("outside", layer);
        assertEquals(0L, lastUpdated);
        assertEquals(0L, version);
        assertEquals(false, dirty);
        assertEquals(3003L, chunkData.segmentId);
        assertSame(replacementCoord, chunkData.chunkCoord);
        assertEquals("inside", chunkData.layer);
        assertEquals(4004L, chunkData.lastUpdated);
        assertEquals(5005L, chunkData.version);
        assertEquals(true, chunkData.dirty);
    }

    @Test
    @Tag("unit")
    void readsCellFlagsAsUnsignedByCoordinateAndCellIndex() {
        ChunkData chunkData = new ChunkData(1001L, 2002L, Coord.of(3, 4));

        chunkData.setCellFlags(12, 34, 255);

        assertEquals(255, chunkData.getCellFlags(12, 34));
        assertEquals(255, chunkData.getCellFlags(MapUtil.cellIndex(12, 34)));
    }

    @Test
    @Tag("unit")
    void portalLookupClearsOutputBeforeRefilling() {
        ChunkData chunkData = new ChunkData(1001L, 2002L, Coord.of(3, 4));
        Portal portal = new Portal(PortalType.DOOR, Coord.of(4, 5), 1234L, 8, 9);

        PortalType type = portal.type;
        Coord sourceLocalCell = portal.sourceLocalCell;
        long targetChunkId = portal.targetChunkId;
        int targetCellX = portal.targetCellX;
        int targetCellY = portal.targetCellY;

        chunkData.addPortal(portal);

        List<Portal> out = new ArrayList<>();
        out.add(new Portal(PortalType.GATE, Coord.of(1, 1), 2L, 3, 4));
        chunkData.getCellPortals(4, 5, out);
        assertEquals(List.of(portal), out);

        out.add(new Portal(PortalType.MINE, Coord.of(1, 1), 2L, 3, 4));
        chunkData.getCellPortals(8, 8, out);
        assertEquals(List.of(), out);

        assertEquals(PortalType.DOOR, type);
        assertEquals(Coord.of(4, 5), sourceLocalCell);
        assertEquals(1234L, targetChunkId);
        assertEquals(8, targetCellX);
        assertEquals(9, targetCellY);
    }

    @Test
    @Tag("unit")
    void addPortalDeduplicatesEqualPortalsForSameCell() {
        ChunkData chunkData = new ChunkData(1001L, 2002L, Coord.of(3, 4));
        Portal first = new Portal(PortalType.DOOR, Coord.of(4, 5), 1234L, 8, 9);
        Portal duplicate = new Portal(PortalType.DOOR, Coord.of(4, 5), 1234L, 8, 9);
        List<Portal> out = new ArrayList<>();

        chunkData.addPortal(first);
        chunkData.addPortal(duplicate);
        chunkData.getCellPortals(4, 5, out);

        assertEquals(List.of(first), out);
    }

    @Test
    @Tag("unit")
    void portalEqualityUsesSourceLocalCellValueEquality() {
        Portal first = new Portal(PortalType.DOOR, Coord.of(4, 5), 1234L, 8, 9);
        Portal second = new Portal(PortalType.DOOR, Coord.of(4, 5), 1234L, 8, 9);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    @Tag("unit")
    void portalConstructorRejectsOutOfRangeTargetCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new Portal(PortalType.DOOR, Coord.of(4, 5), 1234L, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Portal(PortalType.DOOR, Coord.of(4, 5), 1234L, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Portal(PortalType.DOOR, Coord.of(4, 5), 1234L, 200, 0));
        assertThrows(IllegalArgumentException.class, () -> new Portal(PortalType.DOOR, Coord.of(4, 5), 1234L, 0, 200));
    }

    @Test
    @Tag("unit")
    void portalDefensivelyCopiesSourceLocalCell() {
        Coord source = Coord.of(4, 5);
        Portal portal = new Portal(PortalType.DOOR, source, 1234L, 8, 9);

        source.x = 99;
        source.y = 100;

        assertEquals(Coord.of(4, 5), portal.sourceLocalCell);
    }

    @Test
    @Tag("unit")
    void portalConstructorRejectsOutOfRangeSourceLocalCellCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new Portal(PortalType.DOOR, Coord.of(-1, 0), 1234L, 8, 9));
        assertThrows(IllegalArgumentException.class, () -> new Portal(PortalType.DOOR, Coord.of(0, -1), 1234L, 8, 9));
        assertThrows(IllegalArgumentException.class, () -> new Portal(PortalType.DOOR, Coord.of(200, 0), 1234L, 8, 9));
        assertThrows(IllegalArgumentException.class, () -> new Portal(PortalType.DOOR, Coord.of(0, 200), 1234L, 8, 9));
    }

    @Test
    @Tag("unit")
    void rejectsOutOfRangeCoordinateOperations() {
        ChunkData chunkData = new ChunkData(1001L, 2002L, Coord.of(3, 4));

        assertThrows(IllegalArgumentException.class, () -> chunkData.setCellFlags(-1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> chunkData.setCellFlags(0, 200, 1));
        assertThrows(IllegalArgumentException.class, () -> chunkData.getCellFlags(200, 0));
        assertThrows(IllegalArgumentException.class, () -> chunkData.getCellFlags(0, -1));
    }
}
