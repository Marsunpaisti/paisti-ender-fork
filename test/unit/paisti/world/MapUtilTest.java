package paisti.world;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapUtilTest {
    @Test
    @Tag("unit")
    void packRoundTripPreservesShortChunkIdAndCellCoordinates() {
        long fullChunkId = 0x123456789ABCDEFL;
        long shortChunkKey = fullChunkId & WorldMapConstants.SHORT_CHUNK_MASK;
        int cellX = 17;
        int cellY = 199;

        long packedCell = MapUtil.packChunkCellCoord(fullChunkId, cellX, cellY);

        assertEquals((shortChunkKey << 16) | ((long) cellX << 8) | (long) cellY, packedCell);
        assertEquals(shortChunkKey, MapUtil.unpackChunkCellCoordShortChunkKey(packedCell));
        assertEquals(cellX, MapUtil.unpackChunkCellCoordX(packedCell));
        assertEquals(cellY, MapUtil.unpackChunkCellCoordY(packedCell));
    }

    @Test
    @Tag("unit")
    void shortenChunkIdKeepsOnlyLowFortyEightBits() {
        long fullChunkId = 0xFEDCBA9876543210L;

        assertEquals(fullChunkId & WorldMapConstants.SHORT_CHUNK_MASK, MapUtil.shortenChunkId(fullChunkId));
    }

    @Test
    @Tag("unit")
    void rejectsOutOfRangeCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> MapUtil.packChunkCellCoord(55L, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> MapUtil.packChunkCellCoord(55L, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> MapUtil.packChunkCellCoord(55L, 200, 0));
        assertThrows(IllegalArgumentException.class, () -> MapUtil.packChunkCellCoord(55L, 0, 200));
    }
}
