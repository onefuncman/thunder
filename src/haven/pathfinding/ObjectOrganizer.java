package haven.pathfinding;

import haven.Coord2d;
import haven.layout.LayoutFootprint;
import haven.layout.LayoutPlanResult;
import haven.layout.LayoutPlanner;
import haven.layout.LayoutRequest;
import haven.layout.LayoutShape;
import java.util.List;

/**
 * Generic, object-class-agnostic placement planner: the seam a pick-up/place-down
 * executor (headless client) calls to decide where to put N copies of an object.
 *
 * <p>Unlike {@link StockpileOrganizer} (which couples planning to the stockpile
 * itemact/placer/place wire), this class only plans: it derives a footprint from
 * the object's resname (or accepts one directly), runs {@link LayoutPlanner}, and
 * returns placements (world anchor + proven stand + stand route). The caller owns
 * taking, carrying, and placing — so the same logic drives stockpiles, containers,
 * furniture, or any gob with a readable collision resource.
 */
public final class ObjectOrganizer {
   private ObjectOrganizer() {}

   /** Footprint the planner will reserve for the given object resname. */
   public static LayoutFootprint footprint(String resname) {
      return ObjectFootprints.footprintFor(resname);
   }

   /** Plan {@code count} placements of {@code resname} in {@code [areaMin, areaMax]}. */
   public static LayoutPlanResult plan(
      String resname, OccupancyGrid occ, Coord2d areaMin, Coord2d areaMax,
      Coord2d approachFrom, int count, double pitch
   ) {
      return plan(ObjectFootprints.footprintFor(resname), occ, areaMin, areaMax, approachFrom, count, pitch);
   }

   /** Plan {@code count} placements of an explicit footprint (no resource lookup). */
   public static LayoutPlanResult plan(
      LayoutFootprint fp, OccupancyGrid occ, Coord2d areaMin, Coord2d areaMax,
      Coord2d approachFrom, int count, double pitch
   ) {
      return plan(fp, occ, areaMin, areaMax, approachFrom, count, pitch, null, null);
   }

   /**
    * Full form: also preserve existing occupied/kept shapes (objects that must
    * stay in place). {@code pitch} is the placement grid pitch in world units
    * (each footprint cell is pitch x pitch).
    */
   public static LayoutPlanResult plan(
      LayoutFootprint fp, OccupancyGrid occ, Coord2d areaMin, Coord2d areaMax,
      Coord2d approachFrom, int count, double pitch,
      List<LayoutShape> keep, List<LayoutShape> occupied
   ) {
      LayoutRequest req = LayoutRequest.builder(occ, fp, approachFrom, count)
         .area(areaMin, areaMax)
         .pitch(pitch)
         .keep(keep)
         .occupied(occupied)
         .build();
      return LayoutPlanner.plan(req);
   }
}
