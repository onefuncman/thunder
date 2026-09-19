package haven.pathfinding;

import haven.Coord2d;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chooses one occupancy polygon source. Never unions fallback AABBs with
 * live obst, and never treats placement/negative geometry as movement-solid.
 */
public final class CollisionGeom {
   public static final String OBST = "obst";
   public static final String MOVEMENT = "movement";
   public static final String FALLBACK = "fallback";
   public static final String UNAVAILABLE = "unavailable";
   /** Extent below half a tile is a stub, not a movement solid. */
   public static final double MIN_EXTENT = 5.5;

   public final List<Coord2d[]> polygons;
   public final String source;

   public CollisionGeom(List<Coord2d[]> polygons, String source) {
      this.polygons = polygons == null ? Collections.<Coord2d[]>emptyList() : Collections.unmodifiableList(new ArrayList<Coord2d[]>(polygons));
      this.source = source == null ? UNAVAILABLE : source;
   }

   public boolean unavailable() {
      return UNAVAILABLE.equals(this.source);
   }

   public static CollisionGeom none() {
      return new CollisionGeom(Collections.<Coord2d[]>emptyList(), UNAVAILABLE);
   }

   /**
    * Furniture occupancy: meaningful obst, else a rotated known fallback, else
    * nothing. Placement/movement is not consulted.
    */
   public static CollisionGeom furniture(List<Coord2d[]> obst, Coord2d[] knownFallback) {
      if (meaningful(obst)) {
         return new CollisionGeom(obst, OBST);
      }
      if (knownFallback != null && knownFallback.length >= 3) {
         List<Coord2d[]> one = new ArrayList<Coord2d[]>();
         one.add(knownFallback);
         return new CollisionGeom(one, FALLBACK);
      }
      return none();
   }

   /**
    * Target / non-furniture: obst if present, otherwise the movement layer
    * (which is obst-or-neg in the client). Empty is unavailable.
    */
   public static CollisionGeom target(List<Coord2d[]> obst, List<Coord2d[]> movement) {
      if (meaningful(obst)) {
         return new CollisionGeom(obst, OBST);
      }
      if (meaningful(movement)) {
         return new CollisionGeom(movement, MOVEMENT);
      }
      return none();
   }

   public static boolean meaningful(List<Coord2d[]> polygons) {
      double[] box = LocalPlanner.aabb(polygons);
      if (box == null) {
         return false;
      }
      return box[2] - box[0] >= MIN_EXTENT || box[3] - box[1] >= MIN_EXTENT;
   }

   /** World AABB width, height. Zeroes when empty. */
   public static double[] size(List<Coord2d[]> polygons) {
      double[] box = LocalPlanner.aabb(polygons);
      if (box == null) {
         return new double[]{0.0, 0.0};
      }
      return new double[]{box[2] - box[0], box[3] - box[1]};
   }

   public static double[] size(Coord2d[] polygon) {
      if (polygon == null) {
         return new double[]{0.0, 0.0};
      }
      List<Coord2d[]> one = new ArrayList<Coord2d[]>();
      one.add(polygon);
      return size(one);
   }

   /**
    * Why furniture occupancy chose obst, the known fallback, or nothing.
    * Placement/Neg is never a reason to skip fallback.
    */
   public static String furnitureChoiceReason(List<Coord2d[]> obst, Coord2d[] knownFallback) {
      if (meaningful(obst)) {
         return "obst";
      }
      boolean hasStub = obst != null && !obst.isEmpty();
      boolean hasFallback = knownFallback != null && knownFallback.length >= 3;
      if (hasStub && hasFallback) {
         return "stub_obst_fallback";
      }
      if (hasStub) {
         return "stub_obst_unavailable";
      }
      if (hasFallback) {
         return "empty_obst_fallback";
      }
      return UNAVAILABLE;
   }
}
