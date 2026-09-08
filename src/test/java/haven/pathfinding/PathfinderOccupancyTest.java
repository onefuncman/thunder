package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.pathfinding.PathfinderLog.Occupancy;
import haven.pathfinding.PathfinderLog.Trace;
import haven.pathfinding.PrototypePathfinder.Scene;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PathfinderOccupancyTest {
   @Test
   void asciiMarksSolidInflatedPathAndFocus() {
      boolean[] solid = new boolean[25];
      boolean[] dilated = new boolean[25];
      boolean[] walk = new boolean[25];
      solid[12] = true;
      dilated[12] = true;
      dilated[13] = true;
      walk[12] = true;
      walk[13] = true;
      Occupancy occ = Occupancy.capture(
         Coord2d.of(0.0, 0.0),
         5,
         5,
         2.75,
         solid,
         dilated,
         walk,
         Coord.of(0, 2),
         Coord.of(4, 2),
         Coord.of(3, 2),
         List.of(Coord.of(0, 2), Coord.of(1, 2), Coord.of(3, 2))
      );
      Assertions.assertEquals((byte)1, occ.at(2, 2));
      Assertions.assertEquals((byte)2, occ.at(3, 2));
      String ascii = occ.ascii(Coord.of(1, 2), 2);
      Assertions.assertTrue(ascii.contains("#"), ascii);
      Assertions.assertTrue(ascii.contains("+") || ascii.contains("G") || ascii.contains("F"), ascii);
      Assertions.assertTrue(ascii.contains("@"), ascii);
      Assertions.assertTrue(ascii.contains("S") || ascii.contains("@"), ascii);
   }

   @Test
   void probeDoesNotReplaceOverlayWalk() {
      Trace walk = new Trace();
      walk.reason = "ok";
      walk.dx = 10.0;
      walk.waypoints = List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0));
      PathfinderLog.record(null, walk);
      Assertions.assertEquals("ok", PathfinderLog.lastReason());
      Assertions.assertEquals(2, PathfinderLog.lastPath().size());
      PathfinderLog.beginProbe();

      try {
         Trace probe = new Trace();
         probe.reason = "snapped";
         probe.dx = 99.0;
         probe.waypoints = List.of(Coord2d.of(1.0, 1.0));
         PathfinderLog.record(null, probe);
      } finally {
         PathfinderLog.endProbe();
      }

      Assertions.assertEquals("ok", PathfinderLog.lastReason());
      Assertions.assertEquals(10.0, PathfinderLog.lastDest().x, 0.01);
   }

   @Test
   void aisleStandRequiresInflatedCellToTouchOpenGround() {
      boolean[] solid = new boolean[25];
      boolean[] dilated = new boolean[25];
      boolean[] walk = new boolean[25];

      for (int x = 0; x < 5; x++) {
         solid[0 + x] = true;
         dilated[0 + x] = true;
         walk[0 + x] = true;
         dilated[5 + x] = true;
         walk[5 + x] = true;
      }

      solid[0] = false;
      Occupancy occ = Occupancy.capture(Coord2d.of(0.0, 0.0), 5, 5, 1.0, solid, dilated, walk, Coord.of(2, 4), Coord.of(2, 1), Coord.of(2, 1), List.of());
      Scene scene = new Scene();
      scene.origin = Coord2d.of(0.0, 0.0);
      scene.cell = 1.0;
      scene.w = 5;
      scene.h = 5;
      scene.occupancy = occ;
      Assertions.assertTrue(scene.aisleStand(Coord2d.of(2.5, 1.5)), "inflated cell on the aisle face must be a legal stand");
      Assertions.assertFalse(scene.aisleStand(Coord2d.of(0.5, 0.5)), "inflated pocket that only touches solid is not an aisle stand");
      Assertions.assertFalse(scene.aisleStand(Coord2d.of(2.5, 0.5)), "solid furniture/wall is never a stand");
      boolean[] s2 = new boolean[25];
      boolean[] d2 = new boolean[25];
      boolean[] w2 = new boolean[25];

      for (int i = 0; i < s2.length; i++) {
         s2[i] = true;
         d2[i] = true;
         w2[i] = true;
      }

      s2[6] = false;
      d2[12] = false;
      w2[12] = false;
      s2[12] = false;
      Occupancy occ2 = Occupancy.capture(Coord2d.of(0.0, 0.0), 5, 5, 1.0, s2, d2, w2, Coord.of(2, 2), Coord.of(1, 1), Coord.of(1, 1), List.of());
      scene.occupancy = occ2;
      Assertions.assertEquals((byte)2, occ2.at(1, 1));
      Assertions.assertEquals((byte)0, occ2.at(2, 2));
      Assertions.assertFalse(scene.aisleStand(Coord2d.of(1.5, 1.5)), "a hug cell that only meets the aisle on the diagonal is the far side of a wall");
   }

   @Test
   void approachStandsIncludeThePlayerFacingSide() {
      int w = 11;
      int h = 11;
      boolean[] solid = new boolean[w * h];
      boolean[] dilated = new boolean[w * h];
      Occupancy occ = Occupancy.capture(Coord2d.of(0.0, 0.0), w, h, 1.0, solid, dilated, dilated, Coord.of(5, 5), null, null, List.of());
      Scene scene = new Scene();
      scene.origin = Coord2d.of(0.0, 0.0);
      scene.cell = 1.0;
      scene.w = w;
      scene.h = h;
      scene.occupancy = occ;
      List<Coord2d> stands = scene.approachStands(Coord2d.of(5.5, 5.5), 2.0);
      Assertions.assertFalse(stands.isEmpty());
      boolean sawWest = false;

      for (Coord2d p : stands) {
         Assertions.assertTrue(scene.aisleStand(p));
         if (p.x < 5.5) {
            sawWest = true;
         }
      }

      Assertions.assertTrue(sawWest, "west is a legal aisle face, same as Nurgling's four cardinals");
   }
}
