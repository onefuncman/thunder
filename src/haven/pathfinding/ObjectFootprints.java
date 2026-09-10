package haven.pathfinding;

import haven.Coord2d;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import haven.layout.LayoutFootprint;
import java.util.Collection;

/**
 * Derives a placement footprint for an arbitrary world object from its real
 * collision geometry — the same {@code Resource.Obstacle} layers the occupancy
 * grid uses via {@code Hitbox} — so a planned placement cannot overlap the
 * object's actual hit box. Falls back to a conservative default when the
 * resource is unreadable or carries no obstacle layer.
 *
 * <p>Object-class-agnostic: works for stockpiles, containers, furniture, or any
 * gob whose resource has an Obstacle layer. The live pick-up/place executor
 * (headless client) supplies only the resname and gets back a
 * {@link LayoutFootprint} it can hand to {@link haven.layout.LayoutPlanner}.
 */
public final class ObjectFootprints {
   /**
    * Default half-extent for unknown/unreadable resources: one 11u tile
    * (Nurgling's {@code _default} hitbox of 11x11 world units).
    */
   public static final Coord2d DEFAULT_HALF = Coord2d.of(5.5, 5.5);

   private ObjectFootprints() {}

   /** Footprint for a resource, using the generic one-tile default fallback. */
   public static LayoutFootprint footprintFor(String resname) {
      return footprintFor(resname, DEFAULT_HALF);
   }

   /**
    * Footprint for a resource; falls back to {@code fallbackHalf} (or
    * {@link #DEFAULT_HALF} if null/invalid) when the obstacle layer is unreadable.
    */
   public static LayoutFootprint footprintFor(String resname, Coord2d fallbackHalf) {
      Coord2d half = obstacleHalfExtents(resname);
      if (half == null || !(half.x > 0.0 && half.y > 0.0)) {
         half = (fallbackHalf != null && fallbackHalf.x > 0.0 && fallbackHalf.y > 0.0) ? fallbackHalf : DEFAULT_HALF;
      }
      return footprintFromHalf(half);
   }

   /** Converts world-space half-extents to a solid cell footprint (minimum 1x1). */
   public static LayoutFootprint footprintFromHalf(Coord2d half) {
      int w = Math.max(1, (int) Math.ceil(2.0 * half.x / MCache.tilesz.x));
      int h = Math.max(1, (int) Math.ceil(2.0 * half.y / MCache.tilesz.y));
      return LayoutFootprint.rect(w, h, true);
   }

   /**
    * Half-extents of a resource's Obstacle polygons (world units), or null if the
    * resource has no obstacle layer or cannot be loaded. Mirrors
    * {@code Hitbox.movementPolygons}, which prefers Obstacle polygons over Neg
    * boxes for collision.
    */
   public static Coord2d obstacleHalfExtents(String resname) {
      if (resname == null) return null;
      try {
         Resource res = Resource.remote().loadwait(resname);
         if (res == null) return null;
         double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
         double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
         Collection<Resource.Obstacle> obstacles = res.layers(Resource.Obstacle.class);
         if (obstacles != null) {
            for (Resource.Obstacle o : obstacles) {
               if (o.p == null) continue;
               for (Coord2d[] poly : o.p) {
                  for (Coord2d pt : poly) {
                     if (pt == null) continue;
                     minX = Math.min(minX, pt.x);
                     maxX = Math.max(maxX, pt.x);
                     minY = Math.min(minY, pt.y);
                     maxY = Math.max(maxY, pt.y);
                  }
               }
            }
         }
         if (Double.isFinite(minX) && Double.isFinite(minY)) {
            return Coord2d.of(Math.max(Math.abs(minX), Math.abs(maxX)), Math.max(Math.abs(minY), Math.abs(maxY)));
         }
      } catch (Loading e) {
         // fall through
      } catch (RuntimeException e) {
         // fall through
      }
      return null;
   }
}
