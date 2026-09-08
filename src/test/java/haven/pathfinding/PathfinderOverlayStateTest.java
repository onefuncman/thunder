package haven.pathfinding;

import haven.Coord2d;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PathfinderOverlayStateTest {
   @Test
   void overlayLayersAreExplicitlyRecorded() {
      PathfinderLog.setActiveWaypoint(Coord2d.of(3.0, 4.0));
      PathfinderLog.setReplanReason("coarse_plan_failed");
      PathfinderLog.recordConfirmedPos(Coord2d.of(1.0, 2.0));
      PathfinderLog.recordHazards(List.of(Coord2d.of(9.0, 8.0)));
      Assertions.assertEquals(0.0, PathfinderLog.lastActiveWaypoint().dist(Coord2d.of(3.0, 4.0)), 1.0E-9);
      Assertions.assertEquals("coarse_plan_failed", PathfinderLog.lastReplanReason());
      Assertions.assertEquals(0.0, PathfinderLog.lastConfirmedPos().dist(Coord2d.of(1.0, 2.0)), 1.0E-9);
      Assertions.assertEquals(1, PathfinderLog.lastHazards().size());
      Assertions.assertEquals(0.0, PathfinderLog.lastHazards().get(0).dist(Coord2d.of(9.0, 8.0)), 1.0E-9);
   }
}
