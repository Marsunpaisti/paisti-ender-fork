package paisti.world;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainFlagResolverTest {
    @Test
    @Tag("unit")
    void nilCaveAndRockTilesAreTerrainBlocked() {
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource("gfx/tiles/nil"));
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource("gfx/tiles/cave"));
        assertEquals(WorldMapConstants.CELL_BLOCKED_TERRAIN, TerrainFlagResolver.flagsForTileResource("gfx/tiles/rocks/gray"));
    }

    @Test
    @Tag("unit")
    void deepWaterSetsBothTerrainBlockedAndDeepWater() {
        int expected = WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER;

        assertEquals(expected, TerrainFlagResolver.flagsForTileResource("gfx/tiles/deep"));
        assertEquals(expected, TerrainFlagResolver.flagsForTileResource("gfx/tiles/odeep"));
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
}
