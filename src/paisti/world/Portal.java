package paisti.world;

import haven.Coord;

import java.util.Objects;

public final class Portal {
    public final PortalType type;
    public final Coord sourceLocalCell;
    public final long targetChunkId;
    public final int targetCellX;
    public final int targetCellY;

    public Portal(PortalType type, Coord sourceLocalCell, long targetChunkId, int targetCellX, int targetCellY) {
        this.type = Objects.requireNonNull(type, "type");
        Coord safeSourceLocalCell = Objects.requireNonNull(sourceLocalCell, "sourceLocalCell");
        MapUtil.cellIndex(safeSourceLocalCell.x, safeSourceLocalCell.y);
        this.sourceLocalCell = Coord.of(safeSourceLocalCell);
        MapUtil.cellIndex(targetCellX, targetCellY);
        this.targetChunkId = targetChunkId;
        this.targetCellX = targetCellX;
        this.targetCellY = targetCellY;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Portal)) {
            return false;
        }
        Portal portal = (Portal) other;
        return sourceLocalCell.equals(portal.sourceLocalCell)
                && (targetChunkId == portal.targetChunkId)
                && (targetCellX == portal.targetCellX)
                && (targetCellY == portal.targetCellY)
                && (type == portal.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, sourceLocalCell, targetChunkId, targetCellX, targetCellY);
    }

    @Override
    public String toString() {
        return "Portal["
                + "type=" + type
                + ", sourceLocalCell=" + sourceLocalCell
                + ", targetChunkId=" + targetChunkId
                + ", targetCellX=" + targetCellX
                + ", targetCellY=" + targetCellY
                + ']';
    }
}
