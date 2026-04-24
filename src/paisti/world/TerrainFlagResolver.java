package paisti.world;

import haven.Coord;
import haven.MCache;
import haven.resutil.Ridges;

public final class TerrainFlagResolver {
    public static class UnresolvedTerrainException extends RuntimeException {
        public UnresolvedTerrainException(String message) {
            super(message);
        }
    }

    interface RidgeDetector {
        boolean broken(MCache.Grid grid, Coord tileCoord);
    }

    private final RidgeDetector ridgeDetector;

    public TerrainFlagResolver() {
        this((grid, tileCoord) -> Ridges.brokenp(grid, tileCoord));
    }

    TerrainFlagResolver(RidgeDetector ridgeDetector) {
        this.ridgeDetector = ridgeDetector;
    }

    static int flagsForTileResource(String tileName) {
        if(tileName == null)
            throw(new UnresolvedTerrainException("tile resource name is unresolved"));
        if(tileName.equals("gfx/tiles/deepcave") || tileName.startsWith("gfx/tiles/deepcave/"))
            return(0);
        if(tileName.startsWith("gfx/tiles/deep") || tileName.startsWith("gfx/tiles/odeep"))
            return(WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER);
        if(tileName.startsWith("gfx/tiles/nil") || tileName.startsWith("gfx/tiles/cave") || tileName.startsWith("gfx/tiles/rocks"))
            return(WorldMapConstants.CELL_BLOCKED_TERRAIN);
        return(0);
    }

    public int flagsForTile(MCache.Grid grid, int tileX, int tileY) {
        Coord tileCoord = Coord.of(tileX, tileY);
        return(flagsForResolvedTileResource(tileResourceName(grid, tileCoord), grid, tileCoord));
    }

    int flagsForResolvedTileResource(String tileName, MCache.Grid grid, Coord tileCoord) {
        int flags = flagsForTileResource(tileName);
        try {
            if(ridgeDetector.broken(grid, tileCoord))
                flags |= WorldMapConstants.CELL_BLOCKED_TERRAIN;
        } catch(RuntimeException e) {
            return(flags);
        }
        return(flags);
    }

    private static String tileResourceName(MCache.Grid grid, Coord tileCoord) {
        if(grid == null)
            return(null);
        return(grid.tilesetname(grid.gettile(tileCoord)));
    }
}
