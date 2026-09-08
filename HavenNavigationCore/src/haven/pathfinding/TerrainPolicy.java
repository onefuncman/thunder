package haven.pathfinding;

public final class TerrainPolicy {
   private TerrainPolicy() {
   }

   public static boolean terrainBlocks(String tileName) {
      return tileName == null
         ? false
         : tileName.contains("tiles/nil") || tileName.contains("tiles/cave") || tileName.contains("tiles/deep") || tileName.contains("tiles/rocks/");
   }

   public static boolean isWaterTile(String tileName) {
      return tileName != null
         && (tileName.contains("tiles/water")
            || tileName.contains("tiles/deep")
            || tileName.contains("tiles/owater")
            || tileName.contains("tiles/odeep"));
   }

   public static boolean waterTravelLegal(haven.nav.MobilityProfile mob) {
      return mob != null && (mob.swim || mob.boat);
   }

   public static boolean terrainBlocks(String tileName, haven.nav.MobilityProfile mob) {
      if (isWaterTile(tileName)) {
         return !waterTravelLegal(mob);
      }
      return terrainBlocks(tileName);
   }

   public static double agentRadius(haven.nav.MobilityProfile mob, double landRadius) {
      double r = landRadius > 0.5 ? landRadius : 4.5;
      if (mob != null && mob.boat) {
         return Math.max(r, 11.0);
      }
      if (mob != null && mob.cart) {
         return Math.max(r, 8.0);
      }
      return r;
   }

   public static boolean isUnknownTile(String tileName) {
      return tileName == null ? true : tileName.contains("tiles/notile");
   }
}
