package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.pathfinding.PathfinderLog.Occupancy;
import haven.pathfinding.PrototypePathfinder.Scene;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PathfinderProbeTest {
   @Test
   void edgeDistanceIsZeroInsideAndOnBoundary() {
      Coord2d[] box = new Coord2d[]{Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0), Coord2d.of(10.0, 10.0), Coord2d.of(0.0, 10.0)};
      Assertions.assertTrue(PrototypePathfinder.pointInside(Coord2d.of(5.0, 5.0), box));
      Assertions.assertEquals(0.0, PrototypePathfinder.minPolyDist(Coord2d.of(5.0, 5.0), List.<Coord2d[]>of(box)), 1.0E-9);
      Assertions.assertEquals(0.0, PrototypePathfinder.edgeDistance(Coord2d.of(0.0, 5.0), box), 1.0E-9);
      Assertions.assertEquals(3.0, PrototypePathfinder.edgeDistance(Coord2d.of(-3.0, 5.0), box), 1.0E-9);
   }

   @Test
   void idleCellMustStayFreeWhenOnlyNeighboursAreInflated() {
      int w = 9;
      int h = 9;
      boolean[] solid = new boolean[w * h];
      boolean[] dilated = new boolean[w * h];
      solid[4 * w + 2] = true;
      solid[4 * w + 6] = true;
      dilated[4 * w + 2] = true;
      dilated[4 * w + 6] = true;
      dilated[4 * w + 3] = true;
      dilated[4 * w + 5] = true;
      Occupancy occ = Occupancy.capture(Coord2d.of(0.0, 0.0), w, h, 2.75, solid, dilated, dilated, Coord.of(4, 4), null, null, Collections.emptyList());
      Assertions.assertEquals((byte)0, occ.at(4, 4), "an idle player in a 1-tile aisle must not be occupancy-solid");
      Assertions.assertEquals((byte)2, occ.at(3, 4));
      Assertions.assertEquals((byte)1, occ.at(2, 4));
   }

   @Test
   void bodyFreeRejectsInflatedCells() {
      Scene scene = new Scene();
      scene.origin = Coord2d.of(0.0, 0.0);
      scene.w = 4;
      scene.h = 4;
      scene.cell = 2.75;
      boolean[] solid = new boolean[16];
      boolean[] dilated = new boolean[16];
      dilated[1] = true;
      scene.occupancy = Occupancy.capture(scene.origin, 4, 4, scene.cell, solid, dilated, dilated, Coord.of(0, 0), null, null, Collections.emptyList());
      Assertions.assertTrue(scene.bodyFree(scene.occupancy.world(0, 0)));
      Assertions.assertFalse(scene.bodyFree(scene.occupancy.world(1, 0)));
   }

   @Test
   void occupancyMarks032TileStandFreeAfterOverlapRaster() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-22.0, -22.0);
      int w = 24;
      int h = 24;
      boolean[] solid = new boolean[w * h];
      Coord2d[] box = new Coord2d[]{Coord2d.of(-5.0, -5.0), Coord2d.of(5.0, -5.0), Coord2d.of(5.0, 5.0), Coord2d.of(-5.0, 5.0)};
      PrototypePathfinder.rasterPolygon(solid, origin, w, h, box, PrototypePathfinder.OVERLAP);
      boolean[] dilated = Arrays.copyOf(solid, solid.length);
      PrototypePathfinder.dilate(dilated, w, h, 1);
      Coord stand = PrototypePathfinder.worldCell(origin, Coord2d.of(8.5, 0.0));
      Occupancy occ = Occupancy.capture(origin, w, h, 2.75, solid, dilated, dilated, stand, null, null, Collections.emptyList());
      Assertions.assertNotEquals((byte)1, occ.at(stand.x, stand.y), "idle 0.32-tile stand must not be occupancy-solid");
   }
}
