package paisti.world;

import haven.Coord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ChunkData {
    public final long gridId;
    public long segmentId;
    public Coord chunkCoord;
    public final byte[] cells;
    public final Map<Integer, List<Portal>> portalsByCell;
    public String layer = "outside";
    public long lastUpdated;
    public long version;
    public boolean dirty;

    public ChunkData(long gridId, long segmentId, Coord chunkCoord) {
        this.gridId = gridId;
        this.segmentId = segmentId;
        this.chunkCoord = Objects.requireNonNull(chunkCoord, "chunkCoord");
        this.cells = new byte[WorldMapConstants.CELL_COUNT];
        this.portalsByCell = new HashMap<>();
    }

    public int getCellFlags(int cellX, int cellY) {
        return getCellFlags(MapUtil.cellIndex(cellX, cellY));
    }

    public int getCellFlags(int cellIndex) {
        validateCellIndex(cellIndex);
        return Byte.toUnsignedInt(cells[cellIndex]);
    }

    public void setCellFlags(int cellX, int cellY, int flags) {
        validateFlags(flags);
        cells[MapUtil.cellIndex(cellX, cellY)] = (byte) flags;
        dirty = true;
    }

    public void addPortal(Portal portal) {
        Portal safePortal = Objects.requireNonNull(portal, "portal");
        int cellIndex = MapUtil.cellIndex(safePortal.sourceLocalCell.x, safePortal.sourceLocalCell.y);
        List<Portal> portals = portalsByCell.computeIfAbsent(cellIndex, ignored -> new ArrayList<>());
        if (!portals.contains(safePortal)) {
            portals.add(safePortal);
        }
        dirty = true;
    }

    public void getCellPortals(int cellX, int cellY, List<Portal> out) {
        getCellPortals(MapUtil.cellIndex(cellX, cellY), out);
    }

    public void getCellPortals(int cellIndex, List<Portal> out) {
        Objects.requireNonNull(out, "out");
        validateCellIndex(cellIndex);
        out.clear();
        List<Portal> portals = portalsByCell.get(cellIndex);
        if (portals != null) {
            out.addAll(portals);
        }
    }

    private static void validateFlags(int flags) {
        if ((flags < 0) || (flags > WorldMapConstants.INVALID_CELL_FLAGS)) {
            throw new IllegalArgumentException("flags out of range: " + flags);
        }
    }

    private static void validateCellIndex(int cellIndex) {
        if ((cellIndex < 0) || (cellIndex >= WorldMapConstants.CELL_COUNT)) {
            throw new IllegalArgumentException("cellIndex out of range: " + cellIndex);
        }
    }
}
