package haven.pathfinding;

import haven.nav.MobilityProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TerrainPolicyTest {
   @Test
   void caveFloorIsWalkableButUnminedRockIsNot() {
      Assertions.assertFalse(TerrainPolicy.terrainBlocks("gfx/tiles/cave"));
      Assertions.assertFalse(TerrainPolicy.terrainBlocks("gfx/tiles/cavefloor"));
      Assertions.assertFalse(TerrainPolicy.terrainBlocks("gfx/tiles/mine"));
      Assertions.assertFalse(TerrainPolicy.terrainBlocks("gfx/tiles/rough"));
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/cavewall"));
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/caveobsidian"));
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/rocks/granite"));
   }

   @Test
   void brokenRidgeBlocksEvenWhenItsCaveFloorResourceIsWalkable() {
      Assertions.assertFalse(TerrainPolicy.terrainBlocks(
         "gfx/tiles/cave", MobilityProfile.land(), false));
      Assertions.assertTrue(TerrainPolicy.terrainBlocks(
         "gfx/tiles/cave", MobilityProfile.land(), true));
   }

   @Test
   void landCartAndBoatKeepDistinctClearance() {
      Assertions.assertEquals(4.5, TerrainPolicy.agentRadius(MobilityProfile.land(), 4.5), 0.0);
      Assertions.assertEquals(8.0, TerrainPolicy.agentRadius(MobilityProfile.cart(), 4.5), 0.0);
      Assertions.assertEquals(11.0, TerrainPolicy.agentRadius(MobilityProfile.boat(), 4.5), 0.0);
   }

   @Test
   void onlySwimmingAndBoatProfilesMayCrossWater() {
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/water", MobilityProfile.land()));
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/water", MobilityProfile.cart()));
      Assertions.assertFalse(TerrainPolicy.terrainBlocks("gfx/tiles/water", MobilityProfile.swim()));
      Assertions.assertFalse(TerrainPolicy.terrainBlocks("gfx/tiles/water", MobilityProfile.boat()));
   }
}
