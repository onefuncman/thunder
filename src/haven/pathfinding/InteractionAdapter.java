package haven.pathfinding;

import haven.Coord2d;
import haven.nav.InteractionSpec;
import java.util.List;

/**
 * Converts live gob geometry into a renderer-independent InteractionSpec.
 * Resource names stay in Thunder; HavenNavigationCore never sees them.
 */
public final class InteractionAdapter {
   private InteractionAdapter() {
   }

   public static InteractionSpec fromGob(
      MovementScene.GobGeom g,
      int allowedSides,
      double minDist,
      double maxDist,
      int requiredClearance,
      Double facing,
      String expectedResult
   ) {
      if (g == null || g.rc == null) {
         return null;
      }
      return fromGob(g, allowedSides, minDist, maxDist, requiredClearance, facing, expectedResult, null);
   }

   public static InteractionSpec fromGob(
      MovementScene.GobGeom g,
      int allowedSides,
      double minDist,
      double maxDist,
      int requiredClearance,
      Double facing,
      String expectedResult,
      Coord2d near
   ) {
      if (g == null || g.rc == null) {
         return null;
      }
      Coord2d origin = g.rc;
      Coord2d half = Coord2d.of(5.5, 5.5);
      // Use the same authoritative footprint that was put into the scene's
      // solid list.  This is especially important for catalog fallbacks: LOS
      // deliberately ignores the target polygon, and that comparison only
      // works when both sides use the same shape.
      CollisionGeom geom = g.collision != null && !g.collision.isEmpty()
         ? new CollisionGeom(g.collision, g.collisionSource)
         : CollisionGeom.target(g.obst, g.movement);
      List<Coord2d[]> polys = geom.polygons;
      Coord2d[] box = aabbBox(polys.isEmpty() ? g.hitbox : polys);
      if (box != null) {
         origin = Coord2d.of((box[0].x + box[1].x) * 0.5, (box[0].y + box[1].y) * 0.5);
         half = Coord2d.of(Math.max(0.5, (box[1].x - box[0].x) * 0.5), Math.max(0.5, (box[1].y - box[0].y) * 0.5));
      }
      return new InteractionSpec(
         Long.toString(g.id), origin, half, allowedSides, minDist, maxDist, requiredClearance, facing, expectedResult, polys, geom.source,
         faceCentersOnly(g.resid), preferredSides(g.resid, g.a)
      );
   }

   /** Nurgling runs drying racks in hard mode: approach on a face center, not
    * an arbitrary edge/corner pose between tightly packed neighbors. */
   static boolean faceCentersOnly(String resid) {
      return "gfx/terobjs/dframe".equals(MovementScene.baseResid(resid));
   }

   /** Drying racks are normally operated as a row. Prefer the two narrow ends,
    * which keeps every rack on the same aisle even when the first rack is
    * approached diagonally. This is soft: a side face remains a fallback when
    * both aisle ends are genuinely blocked. */
   static int preferredSides(String resid, double angle) {
      if (!faceCentersOnly(resid)) {
         return 0;
      }
      return Math.abs(Math.cos(angle)) >= Math.abs(Math.sin(angle))
         ? InteractionSpec.SIDE_N | InteractionSpec.SIDE_S
         : InteractionSpec.SIDE_E | InteractionSpec.SIDE_W;
   }

   public static InteractionSpec fromFootprint(
      String targetId,
      Coord2d origin,
      Coord2d half,
      int allowedSides,
      double minDist,
      double maxDist,
      int requiredClearance,
      Double facing,
      String expectedResult
   ) {
      return new InteractionSpec(targetId, origin, half, allowedSides, minDist, maxDist, requiredClearance, facing, expectedResult);
   }

   static Coord2d[] aabbBox(List<Coord2d[]> polys) {
      if (polys == null || polys.isEmpty()) {
         return null;
      }
      double minx = Double.POSITIVE_INFINITY;
      double miny = Double.POSITIVE_INFINITY;
      double maxx = Double.NEGATIVE_INFINITY;
      double maxy = Double.NEGATIVE_INFINITY;
      boolean any = false;
      for (int i = 0; i < polys.size(); i++) {
         Coord2d[] poly = polys.get(i);
         if (poly == null) {
            continue;
         }
         for (int j = 0; j < poly.length; j++) {
            Coord2d p = poly[j];
            if (p == null) {
               continue;
            }
            any = true;
            minx = Math.min(minx, p.x);
            miny = Math.min(miny, p.y);
            maxx = Math.max(maxx, p.x);
            maxy = Math.max(maxy, p.y);
         }
      }
      return any ? new Coord2d[]{Coord2d.of(minx, miny), Coord2d.of(maxx, maxy)} : null;
   }
}
