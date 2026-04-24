package paisti.world;

public interface IPathfindingMap {
    int getCellFlags(long packedCell);

    int getCellFlags(long fullChunkId, int cellX, int cellY);
}
