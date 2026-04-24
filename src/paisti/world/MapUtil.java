package paisti.world;

public final class MapUtil {
    private MapUtil() {
    }

    public static long packChunkCellCoord(long fullChunkId, int cellX, int cellY) {
        validateCellCoordinates(cellX, cellY);
        return (shortenChunkId(fullChunkId) << 16) | ((long) cellX << 8) | (long) cellY;
    }

    public static int unpackChunkCellCoordX(long packedCell) {
        return (int) ((packedCell >>> 8) & 0xFFL);
    }

    public static int unpackChunkCellCoordY(long packedCell) {
        return (int) (packedCell & 0xFFL);
    }

    public static long unpackChunkCellCoordShortChunkKey(long packedCell) {
        return (packedCell >>> 16) & WorldMapConstants.SHORT_CHUNK_MASK;
    }

    public static long shortenChunkId(long fullChunkId) {
        return fullChunkId & WorldMapConstants.SHORT_CHUNK_MASK;
    }

    public static int cellIndex(int cellX, int cellY) {
        validateCellCoordinates(cellX, cellY);
        return (cellY * WorldMapConstants.CELL_AXIS) + cellX;
    }

    private static void validateCellCoordinates(int cellX, int cellY) {
        if ((cellX < 0) || (cellX >= WorldMapConstants.CELL_AXIS)) {
            throw new IllegalArgumentException("cellX out of range: " + cellX);
        }
        if ((cellY < 0) || (cellY >= WorldMapConstants.CELL_AXIS)) {
            throw new IllegalArgumentException("cellY out of range: " + cellY);
        }
    }
}
