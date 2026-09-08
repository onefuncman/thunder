package haven.nav;

import haven.Coord;
import haven.Coord2d;
import haven.pathfinding.GridAStar;
import haven.pathfinding.LocalPlanner;
import haven.pathfinding.NavGrid;
import haven.pathfinding.OccupancyGrid;
import haven.pathfinding.PlanningTrace;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class NavContractTest {
   @Test
   void goalKindsAndPlanStatusesExist() {
      Assertions.assertEquals(5, NavGoal.Kind.values().length);
      Assertions.assertEquals(5, NavPlanStatus.values().length);
      Assertions.assertEquals(10, NavOutcome.values().length);
      Assertions.assertEquals(6, NavDecision.Kind.values().length);
   }

   @Test
   void occupancyEncodeIsDeterministic() {
      boolean[] solid = new boolean[9];
      boolean[] dilated = new boolean[9];
      solid[4] = true;
      dilated[4] = true;
      dilated[5] = true;
      OccupancyGrid occ = OccupancyGrid.capture(
         Coord2d.of(1.0, 2.0), 3, 3, 2.75, solid, dilated, dilated, Coord.of(0, 0), Coord.of(2, 2), Coord.of(2, 1), Collections.emptyList()
      );
      String encoded = OccupancyGrid.encode(occ);
      Assertions.assertEquals(9, encoded.length());
      OccupancyGrid round = OccupancyGrid.decode(occ.origin, 3, 3, 2.75, encoded, occ.start, occ.goal, occ.freeGoal);
      Assertions.assertEquals(encoded, OccupancyGrid.encode(round));
      Assertions.assertEquals(occ.at(1, 1), round.at(1, 1));
      Assertions.assertEquals(occ.at(2, 1), round.at(2, 1));
   }

   @Test
   void localPlannerOpenGroundReaches() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      Coord2d dest = Coord2d.of(0.0, 100.0);
      LocalPlanner.ClipResult clip = LocalPlanner.clipToHorizon(start, Collections.singletonList(dest));
      NavGrid grid = LocalPlanner.planGrid(start, clip.targets);
      boolean[] solid = new boolean[grid.width() * grid.height()];
      boolean[] dilated = new boolean[grid.width() * grid.height()];
      NavPlan plan = LocalPlanner.planCore(
         start, Collections.singletonList(dest), clip.targets, clip.clipped, true, 4.5, grid, solid, dilated, 0, new PlanningTrace()
      );
      Assertions.assertEquals(NavPlanStatus.REACHED, plan.status);
      Assertions.assertTrue(plan.complete);
      Assertions.assertTrue(plan.smoothedRoute.size() >= 2);
      Assertions.assertEquals(0.0, plan.smoothedRoute.get(plan.smoothedRoute.size() - 1).dist(dest), 1.0E-6);
   }

   @Test
   void noCornerCuttingOnBlockedCorner() {
      GridAStar.Grid grid = new GridAStar.Grid() {
         @Override
         public int width() { return 3; }
         @Override
         public int height() { return 3; }
         @Override
         public boolean blocked(int x, int y) {
            return x == 1 && y == 0 || x == 0 && y == 1;
         }
      };
      GridAStar.Result result = GridAStar.find(grid, Coord.of(0, 0), Coord.of(2, 2), 100);
      Assertions.assertFalse(result.complete);
      Assertions.assertEquals(Collections.singletonList(Coord.of(0, 0)), result.cells);
   }

   @Test
   void coreVersionIsReadable() {
      Assertions.assertEquals("HavenNavigationCore", NavigationCore.TITLE);
      Assertions.assertNotNull(NavigationCore.gitHash());
      Assertions.assertFalse(NavigationCore.gitHash().isEmpty());
      Assertions.assertNotNull(NavigationCore.version());
      Assertions.assertFalse(NavigationCore.version().isEmpty());
   }

   @Test
   void telemetrySinkMustNotBeRequiredForPlanning() {
      NavigationTelemetrySink.NONE.onEvent("plan", "ok");
      NavPlan failed = NavPlan.failed(0, "no_goal");
      Assertions.assertEquals(NavPlanStatus.FAILED, failed.status);
   }
}
