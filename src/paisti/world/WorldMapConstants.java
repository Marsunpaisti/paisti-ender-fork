package paisti.world;

public final class WorldMapConstants {
    public static final int CELL_AXIS = 200;
    public static final int CELL_COUNT = 40000;
    public static final int INVALID_CELL_FLAGS = 0xFF;
    public static final long SHORT_CHUNK_MASK = 0x0000FFFFFFFFFFFFL;

    public static final int CELL_BLOCKED_TERRAIN = 1;
    public static final int CELL_DEEP_WATER = 1 << 1;
    public static final int CELL_OBSERVED = 1 << 2;

    private WorldMapConstants() {
    }
}
