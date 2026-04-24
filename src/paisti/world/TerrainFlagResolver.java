package paisti.world;

import haven.Coord;
import haven.MCache;
import haven.Resource;
import haven.resutil.Ridges;

public final class TerrainFlagResolver {
    static int flagsForTileResource(String tileName) {
        if(tileName == null)
            return(WorldMapConstants.CELL_BLOCKED_TERRAIN);
        if(tileName.equals("gfx/tiles/deepcave"))
            return(0);
        if(tileName.equals("gfx/tiles/deep") || tileName.equals("gfx/tiles/odeep"))
            return(WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER);
        if(tileName.equals("gfx/tiles/nil") || tileName.startsWith("gfx/tiles/cave") || tileName.startsWith("gfx/tiles/rocks"))
            return(WorldMapConstants.CELL_BLOCKED_TERRAIN);
        return(0);
    }

    public int flagsForTile(MCache.Grid grid, int tileX, int tileY) {
        Coord tileCoord = Coord.of(tileX, tileY);
        int flags = flagsForTileResource(tileResourceName(grid, tileCoord));
        try {
            if(Ridges.brokenp(grid, tileCoord))
                flags |= WorldMapConstants.CELL_BLOCKED_TERRAIN;
        } catch(RuntimeException e) {
            return(flags);
        }
        return(flags);
    }

    private static String tileResourceName(MCache.Grid grid, Coord tileCoord) {
        try {
            Resource resource = grid.tileset(grid.gettile(tileCoord)).getres();
            return((resource == null) ? null : resource.name);
        } catch(RuntimeException e) {
            return(null);
        }
    }
}
