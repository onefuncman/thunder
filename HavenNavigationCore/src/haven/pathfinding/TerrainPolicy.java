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
      if (tileName == null) {
         return false;
      }
      int marker = tileName.indexOf("tiles/");
      if (marker < 0) {
         return false;
      }
      String local = tileName.substring(marker + 6);
      int slash = local.indexOf('/');
      String family = slash < 0 ? local : local.substring(0, slash);
      return family.equals("water") || family.equals("deep") || family.equals("owater")
         || family.equals("odeep") || family.equals("odeeper");
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

   /** A strict boating corridor: unknown tiles, shores, and ordinary land are
    * all walls. Deep water remains legal so separate shallow stretches can be
    * connected without beaching the boat. */
   public static boolean waterOnlyBlocks(String tileName) {
      return !isWaterTile(tileName);
   }

   public static boolean isShallowWaterTile(String tileName) {
      if (!isWaterTile(tileName)) {
         return false;
      }
      int marker = tileName.indexOf("tiles/");
      String local = marker < 0 ? tileName : tileName.substring(marker + 6);
      return !(local.startsWith("deep") || local.startsWith("odeep"));
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
