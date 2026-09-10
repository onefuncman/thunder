package haven.layout;

import haven.Coord2d;
import haven.pathfinding.OccupancyGrid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Value request for a deterministic layout plan. A caller asks the planner to
 * place {@code count} copies of a footprint inside a rectangular world-space
 * area, on a regular grid of {@code pitchX x pitchY} world units, without
 * overlapping anything already there, and in a way that keeps every placement
 * (and the placements accepted before it) reachable from {@code approachFrom}.
 *
 * <p>The planner is renderer- and object-class-agnostic: it does not know about
 * items, stockpiles, furniture or containers. It works from the supplied
 * occupancy grid (existing walk/block state), the reserved shapes (existing
 * occupied footprints to preserve), and a footprint + approach rule supplied by
 * the caller.
 */
public final class LayoutRequest {
   /** World pathfinding occupancy; SOLID cells are obstacles for both placement and approach. */
   public final OccupancyGrid occupancy;
   /** World-space rectangle that placements must stay inside (exclusive max). */
   public final Coord2d areaMin;
   public final Coord2d areaMax;
   /** Placement grid pitch in world units (each footprint cell is pitch x pitch). */
   public final double pitchX;
   public final double pitchY;
   /** Footprint to place (normalized cell shape relative to each placement's anchor). */
   public final LayoutFootprint footprint;
   /** Existing occupied shapes; treated as immovable and blocking. */
   public final List<LayoutShape> occupiedShapes;
   /** Existing kept shapes (things being preserved); treated as immovable and blocking. */
   public final List<LayoutShape> keepShapes;
   /** World position the actor approaches from; every stand must stay reachable from here. */
   public final Coord2d approachFrom;
   /** Number of placements desired (may be partially satisfied). */
   public final int count;

   private LayoutRequest(Builder b) {
      if (b.occupancy == null) {
         throw new IllegalArgumentException("occupancy");
      }
      if (b.footprint == null) {
         throw new IllegalArgumentException("footprint");
      }
      if (b.approachFrom == null) {
         throw new IllegalArgumentException("approachFrom");
      }
      if (b.areaMin == null || b.areaMax == null) {
         throw new IllegalArgumentException("area");
      }
      if (b.areaMax.x < b.areaMin.x || b.areaMax.y < b.areaMin.y) {
         throw new IllegalArgumentException("area");
      }
      if (!(b.pitchX > 0.0) || !(b.pitchY > 0.0) || Double.isNaN(b.pitchX) || Double.isNaN(b.pitchY)
         || Double.isInfinite(b.pitchX) || Double.isInfinite(b.pitchY)) {
         throw new IllegalArgumentException("pitch");
      }
      if (b.count < 0) {
         throw new IllegalArgumentException("count");
      }
      this.occupancy = b.occupancy;
      this.areaMin = b.areaMin;
      this.areaMax = b.areaMax;
      this.pitchX = b.pitchX;
      this.pitchY = b.pitchY;
      this.footprint = b.footprint;
      this.occupiedShapes = copyShapes(b.occupiedShapes);
      this.keepShapes = copyShapes(b.keepShapes);
      this.approachFrom = b.approachFrom;
      this.count = b.count;
   }

   private static List<LayoutShape> copyShapes(List<LayoutShape> shapes) {
      if (shapes == null || shapes.isEmpty()) {
         return Collections.emptyList();
      }
      for (LayoutShape s : shapes) {
         if (s == null) {
            throw new IllegalArgumentException("shape");
         }
      }
      return Collections.unmodifiableList(new ArrayList<LayoutShape>(shapes));
   }

   public static Builder builder(OccupancyGrid occupancy, LayoutFootprint footprint, Coord2d approachFrom, int count) {
      return new Builder(occupancy, footprint, approachFrom, count);
   }

   public static final class Builder {
      private final OccupancyGrid occupancy;
      private final LayoutFootprint footprint;
      private final Coord2d approachFrom;
      private final int count;
      private Coord2d areaMin;
      private Coord2d areaMax;
      private double pitchX;
      private double pitchY;
      private List<LayoutShape> occupiedShapes;
      private List<LayoutShape> keepShapes;

      Builder(OccupancyGrid occupancy, LayoutFootprint footprint, Coord2d approachFrom, int count) {
         this.occupancy = occupancy;
         this.footprint = footprint;
         this.approachFrom = approachFrom;
         this.count = count;
      }

      public Builder area(Coord2d min, Coord2d max) {
         this.areaMin = min;
         this.areaMax = max;
         return this;
      }

      public Builder pitch(double x, double y) {
         this.pitchX = x;
         this.pitchY = y;
         return this;
      }

      public Builder pitch(double p) {
         return pitch(p, p);
      }

      public Builder occupied(List<LayoutShape> shapes) {
         this.occupiedShapes = shapes;
         return this;
      }

      public Builder keep(List<LayoutShape> shapes) {
         this.keepShapes = shapes;
         return this;
      }

      public LayoutRequest build() {
         return new LayoutRequest(this);
      }
   }
}
