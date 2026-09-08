package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.pathfinding.PathfinderLog.Trace;
import haven.pathfinding.PrototypePathfinder.ClipResult;
import haven.pathfinding.PrototypePathfinder.Grid;
import haven.pathfinding.PrototypePathfinder.Plan;
import haven.pathfinding.PrototypePathfinder.Plan.Status;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PathfinderPlanStatusTest {
   private static Plan planOn(Coord2d start, Coord2d dest, boolean snap, PathfinderPlanStatusTest.Blocker blocker) {
      ClipResult clip = PrototypePathfinder.clipToHorizon(start, List.of(dest));
      Grid grid = PrototypePathfinder.planGrid(start, clip.targets);
      boolean[] solid = new boolean[grid.w * grid.h];
      boolean[] dilated = new boolean[grid.w * grid.h];
      if (blocker != null) {
         blocker.block(grid, solid, dilated);
      }

      return PrototypePathfinder.planCore(start, List.of(dest), clip.targets, clip.clipped, snap, 4.5, grid, solid, dilated, 0, new Trace());
   }

   private static Plan planTo(Coord2d start, Coord2d dest, boolean snap) {
      return planOn(start, dest, snap, null);
   }

   @Test
   void farGoalIsClippedLocalLegNotArrival() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      Coord2d dest = Coord2d.of(0.0, 500.0);
      double maxReach = PrototypePathfinder.maxReach();
      Assertions.assertTrue(dest.dist(start) > maxReach, "fixture must be beyond the horizon");
      Plan plan = planTo(start, dest, true);
      Assertions.assertEquals(Status.CLIPPED, plan.status, "a far goal is a local leg, not destination arrival");
      Assertions.assertTrue(plan.complete, "the local leg still reaches the horizon clip point");
      Assertions.assertFalse(plan.snapped, "legacy booleans are unchanged for far goals");
      Assertions.assertTrue(plan.waypoints.size() >= 2);
      Coord2d end = (Coord2d)plan.waypoints.get(plan.waypoints.size() - 1);
      Coord2d clipPoint = start.add(Coord2d.of(0.0, 1.0).mul(maxReach));
      Assertions.assertEquals(0.0, end.dist(clipPoint), 1.0E-6, "route ends at the horizon clip point");
      Assertions.assertTrue(end.dist(dest) > maxReach * 0.05, "and NOT at the requested destination");
   }

   @Test
   void inRangeGoalIsReached() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      Coord2d dest = Coord2d.of(0.0, 100.0);
      Assertions.assertTrue(dest.dist(start) < PrototypePathfinder.maxReach(), "fixture must be in range");
      Plan plan = planTo(start, dest, true);
      Assertions.assertEquals(Status.REACHED, plan.status);
      Assertions.assertTrue(plan.complete);
      Assertions.assertFalse(plan.snapped);
      Assertions.assertTrue(plan.waypoints.size() >= 2);
      Coord2d end = (Coord2d)plan.waypoints.get(plan.waypoints.size() - 1);
      Assertions.assertEquals(0.0, end.dist(dest), 1.0E-6, "in-range route ends exactly at the destination");
   }

   @Test
   void planGridLeavesRoomToWalkAroundVillageClutter() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      Coord2d dest = Coord2d.of(120.0, 0.0);
      Grid grid = PrototypePathfinder.planGrid(start, List.of(dest));
      Assertions.assertTrue(
         grid.h * 2.75 >= 180.0,
         "start-dest sausage was too skinny to detour a crowded yard (h=" + grid.h + ")"
      );
   }

   @Test
   void blobOnTheStraightLineIsWalkedAround() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      Coord2d dest = Coord2d.of(120.0, 0.0);
      Plan plan = planOn(start, dest, true, (grid, solid, dilated) -> {
         Coord left = PrototypePathfinder.worldCell(grid.origin, Coord2d.of(50.0, -40.0));
         Coord right = PrototypePathfinder.worldCell(grid.origin, Coord2d.of(70.0, 40.0));
         int x0 = Math.max(0, Math.min(left.x, right.x));
         int x1 = Math.min(grid.w - 1, Math.max(left.x, right.x));
         int y0 = Math.max(0, Math.min(left.y, right.y));
         int y1 = Math.min(grid.h - 1, Math.max(left.y, right.y));
         for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
               solid[y * grid.w + x] = true;
               dilated[y * grid.w + x] = true;
               grid.block(x, y);
            }
         }
      });
      Assertions.assertEquals(Status.REACHED, plan.status, "A* must go around a yard blob, not PARTIAL");
      Coord2d end = (Coord2d)plan.waypoints.get(plan.waypoints.size() - 1);
      Assertions.assertEquals(0.0, end.dist(dest), 1.5, "detour still finishes at the stand");
   }

   @Test
   void jammedWalkIsAReplanNotARouteAbort() {
      Assertions.assertTrue(MoveToAutoOpenGroundScenario.retryAfterWalk(WaypointWalker.Result.STUCK));
      Assertions.assertTrue(MoveToAutoOpenGroundScenario.retryAfterWalk(WaypointWalker.Result.SHORT_STOP));
      Assertions.assertTrue(MoveToAutoOpenGroundScenario.retryAfterWalk(WaypointWalker.Result.TIMEOUT));
      Assertions.assertFalse(MoveToAutoOpenGroundScenario.retryAfterWalk(WaypointWalker.Result.ARRIVED));
      Assertions.assertFalse(MoveToAutoOpenGroundScenario.retryAfterWalk(WaypointWalker.Result.REJECTED));
   }

   @Test
   void snappedStandBesideADeskCountsAsArrival() {
      Plan snapped = Plan.fabricated(List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)), true, true, 0, 0, Status.SNAPPED);
      Assertions.assertTrue(
         MoveToAutoOpenGroundScenario.closeEnough(snapped, Coord2d.of(10.0, 0.5), Coord2d.of(20.0, 0.0)),
         "Walk reverse around a desk must not stall after reaching the occupancy snap"
      );
      Assertions.assertFalse(
         MoveToAutoOpenGroundScenario.closeEnough(snapped, Coord2d.of(10.0, 0.5), Coord2d.of(80.0, 0.0)),
         "a snap on the far side of the room is not the stand"
      );
   }

   @Test
   void blockedInRangeGoalSnapsInsteadOfClaimingArrival() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      Coord2d dest = Coord2d.of(0.0, 100.0);
      Plan plan = planOn(start, dest, true, (grid, solid, dilated) -> {
         Coord destCell = PrototypePathfinder.worldCell(grid.origin, dest);
         solid[destCell.y * grid.w + destCell.x] = true;
         dilated[destCell.y * grid.w + destCell.x] = true;
         grid.block(destCell.x, destCell.y);
      });
      Assertions.assertEquals(Status.SNAPPED, plan.status);
      Assertions.assertTrue(plan.complete);
      Assertions.assertTrue(plan.snapped, "legacy snapped flag still reports the blocked goal");
      Coord2d end = (Coord2d)plan.waypoints.get(plan.waypoints.size() - 1);
      Assertions.assertTrue(end.dist(dest) > 1.0E-6, "route does not end at the blocked destination");
   }

   @Test
   void unreachableInRangeGoalIsPartial() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      Coord2d dest = Coord2d.of(0.0, 100.0);
      Plan plan = planOn(start, dest, true, (grid, solid, dilated) -> {
         Coord wallRow = PrototypePathfinder.worldCell(grid.origin, Coord2d.of(0.0, 50.0));

         for (int x = 0; x < grid.w; x++) {
            solid[wallRow.y * grid.w + x] = true;
            dilated[wallRow.y * grid.w + x] = true;
            grid.block(x, wallRow.y);
         }
      });
      Assertions.assertEquals(Status.PARTIAL, plan.status);
      Assertions.assertFalse(plan.complete);
      Assertions.assertTrue(plan.waypoints.size() >= 2, "best-effort path still walks toward the goal");
      Assertions.assertTrue(((Coord2d)plan.waypoints.get(plan.waypoints.size() - 1)).y < 50.0, "best-effort route stops at the wall, not at the goal");
   }

   @Test
   void blockedGoalFailsWithoutSnap() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      Coord2d dest = Coord2d.of(0.0, 100.0);
      Plan plan = planOn(start, dest, false, (grid, solid, dilated) -> {
         Coord destCell = PrototypePathfinder.worldCell(grid.origin, dest);
         solid[destCell.y * grid.w + destCell.x] = true;
         dilated[destCell.y * grid.w + destCell.x] = true;
         grid.block(destCell.x, destCell.y);
      });
      Assertions.assertEquals(Status.FAILED, plan.status);
      Assertions.assertFalse(plan.complete);
      Assertions.assertTrue(plan.waypoints.isEmpty());
   }

   @Test
   void startOutsideGridFails() {
      Grid grid = new Grid(Coord2d.of(0.0, 0.0), 8, 8);
      boolean[] solid = new boolean[64];
      boolean[] dilated = new boolean[64];
      Trace tr = new Trace();
      Plan plan = PrototypePathfinder.planCore(
         Coord2d.of(0.0, -100.0), List.of(Coord2d.of(0.0, -50.0)), List.of(Coord2d.of(0.0, -50.0)), List.of(false), true, 4.5, grid, solid, dilated, 0, tr
      );
      Assertions.assertEquals(Status.FAILED, plan.status);
      Assertions.assertTrue(plan.waypoints.isEmpty());
      Assertions.assertEquals("start_off_grid", tr.reason);
   }

   @Test
   void clipToHorizonPullsFarGoalsBackToMaxReach() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      double maxReach = PrototypePathfinder.maxReach();
      ClipResult far = PrototypePathfinder.clipToHorizon(start, List.of(Coord2d.of(0.0, 500.0)));
      Assertions.assertEquals(1, far.targets.size());
      Assertions.assertTrue((Boolean)far.clipped.get(0));
      Assertions.assertEquals(0.0, ((Coord2d)far.targets.get(0)).x, 1.0E-6);
      Assertions.assertEquals(maxReach, ((Coord2d)far.targets.get(0)).y, 1.0E-6);
      Assertions.assertEquals(maxReach, ((Coord2d)far.targets.get(0)).dist(start), 1.0E-6);
      ClipResult near = PrototypePathfinder.clipToHorizon(start, List.of(Coord2d.of(0.0, 100.0)));
      Assertions.assertFalse((Boolean)near.clipped.get(0));
      Assertions.assertEquals(100.0, ((Coord2d)near.targets.get(0)).y, 1.0E-6, "in-range destinations are untouched");
   }

   private interface Blocker {
      void block(Grid var1, boolean[] var2, boolean[] var3);
   }
}
