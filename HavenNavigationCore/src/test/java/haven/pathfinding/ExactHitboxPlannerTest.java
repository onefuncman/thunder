package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.NavPlan;
import haven.nav.NavPlanStatus;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ExactHitboxPlannerTest {
   @Test
   public void nearbyHitboxDoesNotBlockAVisiblyClearRoute() {
      NavGrid grid = new NavGrid(Coord2d.of(0.0, 0.0), 8, 5);
      Coord2d[] obstacle = box(9.0, 3.0, 11.0, 5.3);
      List<Coord2d[]> solids = Collections.singletonList(obstacle);
      List<Coord2d[]> body = Collections.singletonList(box(-0.5, -0.5, 0.5, 0.5));

      boolean[] legacyPurple = new boolean[grid.w * grid.h];
      LocalPlanner.rasterPolygon(legacyPurple, grid.origin, grid.w, grid.h, obstacle, LocalPlanner.OVERLAP);
      Assertions.assertTrue(legacyPurple[2 * grid.w + 3], "the old padded object cell reaches into the clear lane");

      applyExact(grid, solids, body);
      Coord startCell = Coord.of(0, 2);
      Coord goalCell = Coord.of(7, 2);
      Assertions.assertFalse(grid.blocked(startCell.x, startCell.y));
      Assertions.assertFalse(grid.blocked(goalCell.x, goalCell.y));

      Coord2d start = grid.world(startCell);
      Coord2d goal = grid.world(goalCell);
      LocalPlanner.ClipResult clip = LocalPlanner.clipToHorizon(start, Collections.singletonList(goal));
      boolean[] terrain = new boolean[grid.w * grid.h];
      boolean[] exact = Arrays.copyOf(grid.blocked, grid.blocked.length);
      NavPlan plan = LocalPlanner.planCore(
         start, Collections.singletonList(goal), clip.targets, clip.clipped,
         false, 0.0, grid, terrain, exact, 1, new PlanningTrace()
      );

      Assertions.assertEquals(NavPlanStatus.REACHED, plan.status);
      Assertions.assertEquals(2, plan.smoothedRoute.size(), "exact geometry keeps the straight visible lane");
      Assertions.assertEquals(start.y, plan.smoothedRoute.get(1).y, 1.0E-6);
   }

   @Test
   public void exactEdgeCheckFindsObstacleBetweenFreeCellCenters() {
      NavGrid grid = new NavGrid(Coord2d.of(0.0, 0.0), 5, 4);
      Coord2d[] obstacle = box(5.35, 3.7, 5.65, 4.55);
      List<Coord2d[]> solids = Collections.singletonList(obstacle);
      List<Coord2d[]> body = Collections.singletonList(box(-0.15, -0.15, 0.15, 0.15));
      applyExact(grid, solids, body);

      Coord2d start = grid.world(Coord.of(0, 1));
      Coord2d goal = grid.world(Coord.of(4, 1));
      Assertions.assertFalse(grid.blocked(1, 1));
      Assertions.assertFalse(grid.blocked(2, 1));
      Assertions.assertFalse(grid.allowsStep(1, 1, 2, 1), "the continuous edge crosses the drawn hitbox");

      LocalPlanner.ClipResult clip = LocalPlanner.clipToHorizon(start, Collections.singletonList(goal));
      boolean[] terrain = new boolean[grid.w * grid.h];
      boolean[] exact = Arrays.copyOf(grid.blocked, grid.blocked.length);
      NavPlan plan = LocalPlanner.planCore(
         start, Collections.singletonList(goal), clip.targets, clip.clipped,
         false, 0.0, grid, terrain, exact, 1, new PlanningTrace()
      );

      Assertions.assertEquals(NavPlanStatus.REACHED, plan.status);
      Assertions.assertTrue(plan.smoothedRoute.size() >= 3, "route bends around a hitbox lying between cell centers");
      for (int i = 1; i < plan.smoothedRoute.size(); i++) {
         Assertions.assertTrue(LocalPlanner.sweptClear(
            plan.smoothedRoute.get(i - 1), plan.smoothedRoute.get(i), body, solids, null
         ));
      }
   }

   @Test
   public void overlappingStartEscapesFromTheRealPosition() {
      NavGrid grid = new NavGrid(Coord2d.of(0.0, 0.0), 8, 5);
      Coord2d[] obstacle = box(2.0, 5.0, 4.6, 8.7);
      List<Coord2d[]> solids = Collections.singletonList(obstacle);
      List<Coord2d[]> body = Collections.singletonList(box(-1.0, -1.0, 1.0, 1.0));
      applyExact(grid, solids, body);

      Coord2d start = Coord2d.of(4.4, 6.9);
      Coord2d goal = grid.world(Coord.of(7, 2));
      Assertions.assertTrue(LocalPlanner.bodyHitsAny(start, body, solids, null));
      LocalPlanner.ClipResult clip = LocalPlanner.clipToHorizon(start, Collections.singletonList(goal));
      boolean[] terrain = new boolean[grid.w * grid.h];
      boolean[] exact = Arrays.copyOf(grid.blocked, grid.blocked.length);
      NavPlan plan = LocalPlanner.planCore(
         start, Collections.singletonList(goal), clip.targets, clip.clipped,
         false, 0.0, grid, terrain, exact, 1, new PlanningTrace()
      );

      Assertions.assertEquals(NavPlanStatus.REACHED, plan.status, plan.reason);
      Assertions.assertTrue(plan.smoothedRoute.size() >= 2, "escape is movement, never fabricated arrival");
      Assertions.assertEquals(start.x, plan.smoothedRoute.get(0).x, 1.0E-9);
      Assertions.assertEquals(start.y, plan.smoothedRoute.get(0).y, 1.0E-9);
      Assertions.assertEquals(goal, plan.smoothedRoute.get(plan.smoothedRoute.size() - 1));
      Assertions.assertTrue(LocalPlanner.sweptClearLeaving(
         start, plan.smoothedRoute.get(1), body, solids, null, null
      ));
   }

   @Test
   public void bodySweepCatchesCollisionThatCenterlineMisses() {
      List<Coord2d[]> body = Collections.singletonList(box(-1.0, -1.0, 1.0, 1.0));
      Coord2d[] obstacle = box(4.5, 0.8, 5.5, 1.2);
      Coord2d a = Coord2d.of(0.0, 0.0);
      Coord2d b = Coord2d.of(10.0, 0.0);

      Assertions.assertFalse(LocalPlanner.segmentHitsPolygon(a, b, obstacle, 0.0));
      Assertions.assertTrue(LocalPlanner.sweptBodyHits(a, b, body, obstacle));
      Assertions.assertFalse(LocalPlanner.sweptClear(a, b, body, Collections.singletonList(obstacle), null));
   }

   private static void applyExact(NavGrid grid, List<Coord2d[]> solids, List<Coord2d[]> body) {
      boolean[] base = Arrays.copyOf(grid.blocked, grid.blocked.length);
      LocalPlanner.PolyBounds bounds = LocalPlanner.PolyBounds.of(solids);
      for (int y = 0; y < grid.h; y++) {
         for (int x = 0; x < grid.w; x++) {
            if (LocalPlanner.bodyHitsAny(grid.world(Coord.of(x, y)), body, solids, null, bounds)) {
               grid.block(x, y);
            }
         }
      }
      grid.configureExactCollision(solids, body, base);
   }

   private static Coord2d[] box(double minx, double miny, double maxx, double maxy) {
      return new Coord2d[]{
         Coord2d.of(minx, miny), Coord2d.of(maxx, miny),
         Coord2d.of(maxx, maxy), Coord2d.of(minx, maxy)
      };
   }
}
