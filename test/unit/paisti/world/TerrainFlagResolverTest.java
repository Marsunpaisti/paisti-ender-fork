package paisti.world;

import haven.Coord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainFlagResolverTest {
    @Test
    @Tag("unit")
    void nilCaveAndRockTilesAreTerrainBlocked() {
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource("gfx/tiles/nil"));
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource("gfx/tiles/nil/edge"));
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource("gfx/tiles/cave"));
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource("gfx/tiles/cave/edge"));
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource("gfx/tiles/rocks/gray"));
    }

    @Test
    @Tag("unit")
    void deepWaterSetsBothTerrainBlockedAndDeepWater() {
        int expected = WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER;

        assertEquals(expected, TerrainFlagResolver.flagsForTileResource("gfx/tiles/deep"));
        assertEquals(expected, TerrainFlagResolver.flagsForTileResource("gfx/tiles/odeep"));
        assertEquals(expected, TerrainFlagResolver.flagsForTileResource("gfx/tiles/odeeper"));
        assertEquals(expected, TerrainFlagResolver.flagsForTileResource("gfx/tiles/deep/edge"));
    }

    @Test
    @Tag("unit")
    void deepCaveOverridesCaveAndDeepPrefixesAndRemainsPassable() {
        assertEquals(0, TerrainFlagResolver.flagsForTileResource("gfx/tiles/deepcave"));
        assertEquals(0, TerrainFlagResolver.flagsForTileResource("gfx/tiles/grass"));
    }

    @Test
    @Tag("unit")
    void unknownTileResourceIsConservativelyBlocked() {
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource(null));
    }

    @Test
    @Tag("unit")
    void brokenRidgeAddsTerrainBlockedToPassableTile() {
        TerrainFlagResolver resolver = new TerrainFlagResolver((grid, tileCoord) -> true);

        assertEquals(
                WorldMapConstants.CELL_BLOCKED_TERRAIN,
                resolver.flagsForResolvedTileResource("gfx/tiles/grass", null, Coord.of(1, 2))
        );
    }

    @Test
    @Tag("unit")
    void ridgeDetectionExceptionLeavesResourceFlagsUnchanged() {
        TerrainFlagResolver resolver = new TerrainFlagResolver((grid, tileCoord) -> {
            throw new RuntimeException("ridge loading");
        });
        int expected = WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER;

        assertEquals(expected, resolver.flagsForResolvedTileResource("gfx/tiles/deep", null, Coord.of(1, 2)));
        assertEquals(0, resolver.flagsForResolvedTileResource("gfx/tiles/grass", null, Coord.of(1, 2)));
    }

    @Test
    @Tag("unit")
    void unresolvedGridTileLookupIsConservativelyBlocked() {
        TerrainFlagResolver resolver = new TerrainFlagResolver((grid, tileCoord) -> false);

        assertEquals(
                WorldMapConstants.CELL_BLOCKED_TERRAIN,
                resolver.flagsForTile(null, 1, 2)
        );
    }
}
