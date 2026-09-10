package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.NavSnapshot;
import haven.pathfinding.PathfinderLog.Occupancy;
import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ThunderNavCoreBindingTest {
   @Test
   void gridAStarIsLoadedFromNavigationCoreJar() {
      java.net.URL loc = GridAStar.class.getProtectionDomain().getCodeSource().getLocation();
      Assertions.assertNotNull(loc);
      String path = loc.getPath();
      Assertions.assertTrue(
         path.contains("HavenNavigationCore") || path.contains("HavenNavigationCore.jar"),
         "GridAStar must come from HavenNavigationCore, not a Thunder copy: " + path
      );
   }

   @Test
   void runtimeHashMatchesCoreClass() {
      Assertions.assertEquals("HavenNavigationCore", haven.nav.NavigationCore.TITLE);
      Assertions.assertNotNull(haven.nav.NavigationCore.gitHash());
      Assertions.assertFalse(haven.nav.NavigationCore.gitHash().isEmpty());
   }

   @Test
   void adapterSnapshotCopiesPlayerOccupancyAndMobility() {
      PrototypePathfinder.Scene scene = new PrototypePathfinder.Scene();
      scene.origin = Coord2d.of(10.0, 20.0);
      scene.w = 3;
      scene.h = 3;
      scene.cell = 2.75;
      scene.radius = 4.5;
      scene.player = Coord2d.of(11.0, 21.0);
      scene.playerCell = Coord.of(0, 0);
      scene.moving = true;
      scene.occupancy = Occupancy.capture(
         scene.origin, 3, 3, 2.75, new boolean[9], new boolean[9], new boolean[9], Coord.of(0, 0), Coord.of(2, 2), Coord.of(2, 2), Collections.emptyList()
      );
      NavSnapshot snap = ThunderNavAdapter.snapshot(scene);
      Assertions.assertEquals("world", snap.coordinateFrame);
      Assertions.assertEquals(scene.player, snap.player);
      Assertions.assertEquals(scene.playerCell, snap.playerCell);
      Assertions.assertTrue(snap.moving);
      Assertions.assertEquals(4.5, snap.agentRadius, 1.0E-9);
      Assertions.assertNotNull(snap.occupancy);
      Assertions.assertEquals(9, snap.occupancy.occ.length);
      Assertions.assertTrue(snap.mobility.land);
      Assertions.assertFalse(snap.mobility.swim);
   }
}
