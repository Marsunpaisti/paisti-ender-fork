package paisti.world;

import java.util.List;

public interface IPortalMap {
    void getCellPortals(long packedCell, List<Portal> out);

    void getCellPortals(long fullChunkId, int cellX, int cellY, List<Portal> out);
}
