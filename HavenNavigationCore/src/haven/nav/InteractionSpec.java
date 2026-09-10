package haven.nav;

import haven.Coord2d;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renderer-independent interaction goal: target footprint plus approach
 * constraints. The target remains an obstacle; callers must not carve it.
 */
public final class InteractionSpec {
   public static final int SIDE_N = 1;
   public static final int SIDE_E = 2;
   public static final int SIDE_S = 4;
   public static final int SIDE_W = 8;
   public static final int ALL_SIDES = SIDE_N | SIDE_E | SIDE_S | SIDE_W;

   public final String targetId;
   public final Coord2d origin;
   public final Coord2d half;
   public final int allowedSides;
   public final double minDist;
   public final double maxDist;
   public final int requiredClearance;
   public final Double facing;
   public final String expectedResult;
   public final List<Coord2d[]> polygons;
   public final String geometrySource;

   public InteractionSpec(
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
      this(targetId, origin, half, allowedSides, minDist, maxDist, requiredClearance, facing, expectedResult, null, "");
   }

   public InteractionSpec(
      String targetId,
      Coord2d origin,
      Coord2d half,
      int allowedSides,
      double minDist,
      double maxDist,
      int requiredClearance,
      Double facing,
      String expectedResult,
      List<Coord2d[]> polygons,
      String geometrySource
   ) {
      if (origin == null) {
         throw new IllegalArgumentException("origin");
      }
      if (minDist < 0.0 || maxDist < minDist) {
         throw new IllegalArgumentException("distance");
      }
      this.targetId = targetId;
      this.origin = origin;
      this.half = half == null ? Coord2d.of(5.5, 5.5) : Coord2d.of(Math.max(0.0, half.x), Math.max(0.0, half.y));
      this.allowedSides = allowedSides;
      this.minDist = minDist;
      this.maxDist = maxDist;
      this.requiredClearance = Math.max(0, requiredClearance);
      this.facing = facing;
      this.expectedResult = expectedResult == null ? "" : expectedResult;
      this.polygons = copyPolys(polygons);
      this.geometrySource = geometrySource == null ? "" : geometrySource;
   }

   public List<Coord2d[]> footprintPolygons() {
      if (!this.polygons.isEmpty()) {
         return this.polygons;
      }
      List<Coord2d[]> box = new ArrayList<Coord2d[]>();
      box.add(new Coord2d[]{
         Coord2d.of(minX(), minY()),
         Coord2d.of(maxX(), minY()),
         Coord2d.of(maxX(), maxY()),
         Coord2d.of(minX(), maxY())
      });
      return box;
   }

   private static List<Coord2d[]> copyPolys(List<Coord2d[]> polygons) {
      if (polygons == null || polygons.isEmpty()) {
         return Collections.emptyList();
      }
      return Collections.unmodifiableList(new ArrayList<Coord2d[]>(polygons));
   }

   public double minX() {
      return this.origin.x - this.half.x;
   }

   public double maxX() {
      return this.origin.x + this.half.x;
   }

   public double minY() {
      return this.origin.y - this.half.y;
   }

   public double maxY() {
      return this.origin.y + this.half.y;
   }

   public static String sideName(int side) {
      if (side == SIDE_N) {
         return "N";
      }
      if (side == SIDE_E) {
         return "E";
      }
      if (side == SIDE_S) {
         return "S";
      }
      if (side == SIDE_W) {
         return "W";
      }
      return "NONE";
   }
}
