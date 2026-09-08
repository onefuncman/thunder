package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.layout.LayoutFootprint;
import haven.layout.LayoutPlacement;
import haven.layout.LayoutPlanResult;
import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ObjectOrganizerTest {
   private static final int W = 44;
   private static final int H = 44;
   private static final double CELL = 2.75;
   private static final double PITCH = 11.0;
   private static final Coord2d ORIGIN = Coord2d.of(0.0, 0.0);

   private static OccupancyGrid open() {
      boolean[] solid = new boolean[W * H];
      boolean[] dilated = new boolean[W * H];
      return OccupancyGrid.capture(ORIGIN, W, H, CELL, solid, dilated, dilated,
         Coord.of(0, 0), Coord.of(W - 1, 0), Coord.of(W - 1, 0), Collections.emptyList());
   }

   @Test
   void plansRequestedCountInOpenArea() {
      OccupancyGrid occ = open();
      Coord2d areaMax = Coord2d.of(W * CELL, H * CELL);
      LayoutPlanResult r = ObjectOrganizer.plan(
         LayoutFootprint.rect(1, 1, true), occ, ORIGIN, areaMax, Coord2d.of(60.0, 60.0), 4, PITCH);
      Assertions.assertEquals(LayoutPlanResult.Status.PLANNED, r.status, "status " + r.reason);
      Assertions.assertEquals(4, r.placements.size());
      for (LayoutPlacement p : r.placements) {
         Assertions.assertNotNull(p.world, "world");
         Assertions.assertNotNull(p.stand, "stand");
         Assertions.assertFalse(p.standRoute.isEmpty(), "standRoute");
         Assertions.assertEquals(1, p.footprint.bboxW());
         Assertions.assertEquals(1, p.footprint.bboxH());
      }
   }

   @Test
   void plansFootprintAndResnameFormsAgree() {
      OccupancyGrid occ = open();
      Coord2d areaMax = Coord2d.of(W * CELL, H * CELL);
      // A null resname falls back to the one-tile default footprint, so the
      // resname-based entry point must agree with an explicit 1x1 footprint.
      LayoutPlanResult viaRes = ObjectOrganizer.plan(
         (String) null, occ, ORIGIN, areaMax, Coord2d.of(60.0, 60.0), 2, PITCH);
      LayoutPlanResult viaFp = ObjectOrganizer.plan(
         LayoutFootprint.rect(1, 1, true), occ, ORIGIN, areaMax, Coord2d.of(60.0, 60.0), 2, PITCH);
      Assertions.assertEquals(viaFp.status, viaRes.status);
      Assertions.assertEquals(viaFp.placements.size(), viaRes.placements.size());
   }
}
