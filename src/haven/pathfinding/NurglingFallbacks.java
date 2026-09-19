package haven.pathfinding;

import haven.Coord2d;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Empirical hitboxes imported from Nurgling's NHitBox catalog. These are the
 * preferred pathfinding geometry for catalogued resources; live resource
 * collision geometry remains the fallback for uncatalogued resources.
 */
public final class NurglingFallbacks {
   private static final Map<String, Coord2d> HALF = new LinkedHashMap<>();

   static {
HALF.put("gfx/terobjs/stockpile-branch", Coord2d.of(4.125, 4.125));
      HALF.put("gfx/terobjs/barrel", Coord2d.of(4.125, 4.125));
      HALF.put("gfx/terobjs/churn", Coord2d.of(4.125, 4.125));
      HALF.put("gfx/borka/body", Coord2d.of(4.125, 4.125));
      HALF.put("gfx/terobjs/arch/logcabin", Coord2d.of(26.0, 26.0));
      HALF.put("gfx/terobjs/arch/stonemansion-door", Coord2d.of(2.75, 8.25));
      HALF.put("gfx/terobjs/arch/greathall-door", Coord2d.of(2.75, 8.25));
      HALF.put("gfx/terobjs/arch/stonestead-door", Coord2d.of(2.75, 8.25));
      HALF.put("gfx/terobjs/hitchingpost", Coord2d.of(2.75, 8.25));
      HALF.put("gfx/terobjs/arch/windmill-door", Coord2d.of(2.75, 8.25));
      HALF.put("gfx/terobjs/arch/stonetower-door", Coord2d.of(2.75, 8.25));
      HALF.put("gfx/terobjs/plants/trellis", Coord2d.of(1.375, 5.5));
      HALF.put("gfx/terobjs/arch/hwall", Coord2d.of(0.02, 5.5));
      HALF.put("gfx/terobjs/pow", Coord2d.of(5.5, 5.5));
      HALF.put("gfx/terobjs/vflag", Coord2d.of(2.75, 2.75));
      HALF.put("gfx/terobjs/gardenpot", Coord2d.of(2.75, 2.75));
      HALF.put("gfx/terobjs/vehicle/dugout", Coord2d.of(11.0, 2.75));
      HALF.put("gfx/terobjs/trees/oldtrunk", Coord2d.of(11.0, 2.75));
      HALF.put("gfx/terobjs/furn/boughbed", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/terobjs/htable", Coord2d.of(5.5, 8.25));
      HALF.put("gfx/kritter/cattle/calf", Coord2d.of(9.625, 5.5));
      HALF.put("gfx/kritter/greyseal", Coord2d.of(9.625, 5.5));
      HALF.put("gfx/kritter/horse/foal", Coord2d.of(8.25, 5.5));
      HALF.put("gfx/kritter/horse/stallion", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/kritter/horse/mare", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/kritter/pig/piglet", Coord2d.of(8.25, 5.5));
      HALF.put("gfx/kritter/pig/sow", Coord2d.of(8.25, 5.5));
      HALF.put("gfx/kritter/pig/hog", Coord2d.of(8.25, 5.5));
      HALF.put("gfx/kritter/wolf/wolf", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/terobjs/stockpile-board", Coord2d.of(11.0, 11.0));
      HALF.put("gfx/terobjs/stockpile-soil", Coord2d.of(11.0, 11.0));
      HALF.put("gfx/terobjs/stockpile-pumpkin", Coord2d.of(11.0, 11.0));
      HALF.put("gfx/terobjs/stockpile-metal", Coord2d.of(5.5, 8.25));
      HALF.put("gfx/terobjs/stockpile-straw", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/stockpile-brick", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/terobjs/stockpile-leaf", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/ttub", Coord2d.of(5.5, 5.5));
      HALF.put("gfx/terobjs/cupboard", Coord2d.of(5.5, 5.5));
      HALF.put("gfx/terobjs/cheeserack", Coord2d.of(4.125, 4.125));
      HALF.put("gfx/terobjs/vehicle/cart", Coord2d.of(8.25, 6.875));
      HALF.put("gfx/terobjs/steelcrucible", Coord2d.of(4.75, 5.5));
      HALF.put("gfx/terobjs/crucible", Coord2d.of(2.75, 2.75));
      HALF.put("gfx/terobjs/candelabrum", Coord2d.of(2.75, 2.75));
      HALF.put("gfx/terobjs/arch/downstairs", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/terobjs/trough", Coord2d.of(5.5, 12.375));
      HALF.put("gfx/terobjs/vehicle/rowboat", Coord2d.of(21.0, 8.25));
      HALF.put("gfx/terobjs/claypit", Coord2d.of(27.5, 27.5));
      HALF.put("gfx/terobjs/geyser", Coord2d.of(27.5, 27.5));
      HALF.put("gfx/terobjs/woodheart", Coord2d.of(27.5, 27.5));
      HALF.put("gfx/terobjs/saltbasin", Coord2d.of(27.5, 27.5));
      HALF.put("gfx/terobjs/tarkiln", Coord2d.of(25.0, 25.0));
      HALF.put("gfx/terobjs/primsmelter", Coord2d.of(12.375, 8.25));
      HALF.put("gfx/terobjs/smelter", Coord2d.of(12.375, 22.0));
      HALF.put("gfx/terobjs/minehole", Coord2d.of(12.375, 12.375));
      HALF.put("gfx/terobjs/trees/stonepine", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/trees/blackpine", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/trees/blackpinestump", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/trees/carobtree", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/trees/strawberrytree", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/trees/alder", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/trees/birch", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/trees/spruce", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/trees/sycamore", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/arch/timberhouse", Coord2d.of(33.0, 33.0));
      HALF.put("gfx/terobjs/ropewalk", Coord2d.of(22.0, 8.25));
      HALF.put("gfx/terobjs/loom", Coord2d.of(8.25, 8.25));
      HALF.put("gfx/terobjs/kiln", Coord2d.of(13.0, 13.0));
      HALF.put("gfx/terobjs/arch/palisadebiggate", Coord2d.of(5.5, 12.375));
      HALF.put("gfx/terobjs/arch/polebiggate", Coord2d.of(5.5, 12.375));
      HALF.put("gfx/terobjs/stonepillar", Coord2d.of(13.0, 13.0));
      HALF.put("gfx/terobjs/leanto", Coord2d.of(13.0, 13.0));
      HALF.put("gfx/terobjs/crate", Coord2d.of(5.5, 8.25));
      HALF.put("gfx/terobjs/dframe", Coord2d.of(3.4375, 11.0));
      HALF.put("gfx/terobjs/arch/stonemansion", Coord2d.of(49.5, 49.5));
      HALF.put("gfx/terobjs/arch/greathall", Coord2d.of(79.75, 55.0));
      HALF.put("gfx/terobjs/oven", Coord2d.of(11.0, 11.0));
      HALF.put("gfx/terobjs/arch/stonetower", Coord2d.of(38.75, 38.75));
      HALF.put("gfx/terobjs/arch/windmill", Coord2d.of(27.5, 27.5));
      HALF.put("gfx/terobjs/villageidol", Coord2d.of(11.0, 16.5));
      HALF.put("gfx/terobjs/iconsign", Coord2d.of(2.75, 2.75));
      HALF.put("gfx/terobjs/barterhand", Coord2d.of(2.75, 2.75));
      HALF.put("gfx/terobjs/chest", Coord2d.of(4.125, 4.125));
      HALF.put("gfx/terobjs/metalcabinet", Coord2d.of(4.125, 4.125));
      HALF.put("gfx/kritter/reddeer", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/kritter/boar", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/kritter/horse", Coord2d.of(13.75, 5.5));
      HALF.put("gfx/kritter/horse/horse", Coord2d.of(13.75, 5.5));
      HALF.put("gfx/kritter/reindeer", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/kritter/wolf", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/terobjs/barterstand", Coord2d.of(8.25, 11.0));
      HALF.put("gfx/kritter/moose", Coord2d.of(13.75, 8.25));
      HALF.put("gfx/kritter/walrus/walrus", Coord2d.of(13.75, 8.25));
      HALF.put("gfx/kritter/bear/bear", Coord2d.of(13.75, 8.25));
      HALF.put("gfx/kritter/caveangler/caveangler", Coord2d.of(13.75, 8.25));
      HALF.put("gfx/kritter/moose/moose", Coord2d.of(13.75, 8.25));
      HALF.put("gfx/kritter/reddeer/reddeer", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/kritter/roedeer/roedeer", Coord2d.of(8.25, 5.5));
      HALF.put("gfx/kritter/boar/boar", Coord2d.of(11.0, 5.5));
      HALF.put("gfx/kritter/cattle/cattle", Coord2d.of(13.75, 8.25));
      HALF.put("gfx/kritter/caveangler", Coord2d.of(13.75, 8.25));
      HALF.put("gfx/kritter/beaverking", Coord2d.of(13.75, 8.25));
      HALF.put("gfx/terobjs/smokeshed", Coord2d.of(7.5625, 7.5625));
      HALF.put("gfx/terobjs/shed", Coord2d.of(11.0, 11.0));
      HALF.put("gfx/terobjs/chickencoop", Coord2d.of(11.0, 11.0));
      HALF.put("gfx/terobjs/rabbithutch", Coord2d.of(11.0, 11.0));
      HALF.put("gfx/terobjs/arch/stonestead", Coord2d.of(48.125, 27.5));
      HALF.put("gfx/terobjs/vehicle/wheelbarrow", Coord2d.of(8.25, 5.5));
      HALF.put("gfx/terobjs/vehicle/plow", Coord2d.of(8.25, 5.5));
      HALF.put("gfx/terobjs/cistern", Coord2d.of(9.625, 9.625));
      HALF.put("gfx/terobjs/ladder", Coord2d.of(2.75, 8.25));
   }

   private NurglingFallbacks() {
   }

   public static int size() {
      return HALF.size();
   }

   public static Map<String, Coord2d> entries() {
      return Collections.unmodifiableMap(HALF);
   }

   public static Coord2d half(String resid) {
      if (resid == null) {
         return null;
      }
      Coord2d exact = HALF.get(MovementScene.baseResid(resid));
      if (exact != null) {
         return exact;
      }
      String name = MovementScene.baseResid(resid);
      if (name != null && name.startsWith("gfx/terobjs/trees/") && name.endsWith("stump")) {
         // Most stump resources do not expose a live movement/obstacle layer.
         // Use the catalogued black-pine stump footprint for the whole family
         // so interaction planning can still choose a proven stand position.
         return Coord2d.of(8.25, 8.25);
      }
      if (name != null && name.contains("trellis")) {
         return Coord2d.of(1.375, 5.5);
      }
      if (name != null && ((name.startsWith("gfx/terobjs/trees/") && name.endsWith("log")) ||
                           name.contains("/logs/") || name.endsWith("/oldtrunk"))) {
         return Coord2d.of(11.0, 2.75);
      }
      return null;
   }

   public static Coord2d[] polygon(String resid, Coord2d rc, double angle) {
      Coord2d half = half(resid);
      if (half == null || rc == null) {
         return null;
      }
      double cs = Math.cos(angle);
      double sn = Math.sin(angle);
      Coord2d[] local = new Coord2d[]{
         Coord2d.of(-half.x, -half.y), Coord2d.of(half.x, -half.y),
         Coord2d.of(half.x, half.y), Coord2d.of(-half.x, half.y)
      };
      Coord2d[] world = new Coord2d[local.length];
      for (int i = 0; i < local.length; i++) {
         Coord2d p = local[i];
         world[i] = Coord2d.of(rc.x + p.x * cs - p.y * sn, rc.y + p.x * sn + p.y * cs);
      }
      return world;
   }
}
