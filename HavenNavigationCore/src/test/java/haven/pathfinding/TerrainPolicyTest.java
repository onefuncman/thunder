package haven.pathfinding;

import haven.nav.MobilityProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TerrainPolicyTest {
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
