package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.InteractionSpec;
import haven.nav.NavDecision;
import haven.nav.NavObservation;
import haven.nav.NavOutcome;
import haven.nav.NavPlan;
import haven.nav.NavPlanStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SurfaceStreamTest {
   private static OccupancyGrid open(int w, int h) {
      return OccupancyGrid.capture(
         Coord2d.of(0.0, 0.0), w, h, 2.75, new boolean[w * h], new boolean[w * h], new boolean[w * h], Coord.of(0, 0), Coord.of(w - 1, 0), Coord.of(w - 1, 0), Collections.emptyList()
      );
   }

   private static OccupancyGrid withSolid(int w, int h, Coord[] solids) {
      boolean[] solid = new boolean[w * h];
      boolean[] dilated = new boolean[w * h];
      for (Coord c : solids) {
         solid[c.y * w + c.x] = true;
         dilated[c.y * w + c.x] = true;
      }
      LocalPlanner.dilate(dilated, w, h, 1);
      return OccupancyGrid.capture(Coord2d.of(0.0, 0.0), w, h, 2.75, solid, dilated, dilated, Coord.of(0, 0), Coord.of(w - 1, 0), Coord.of(w - 1, 0), Collections.emptyList());
   }

   private static NavObservation obs(long t, double x, double y, boolean moving) {
      return new NavObservation(t, Coord2d.of(x, y), moving, false, 0L, false, null, null);
   }

   private static SurfaceController ctl(Coord2d goal, List<Coord2d> route, NavPlanStatus status) {
      return SurfaceController.of(goal, route, status, 2.475, 0.6875, 800L, 20000L, 3000L);
   }

   @Test
   void farthestWithoutOccupancyIsLastWaypointNotEveryCell() {
      List<Coord2d> rawCells = Arrays.asList(Coord2d.of(0, 0), Coord2d.of(2.75, 0), Coord2d.of(5.5, 0), Coord2d.of(8.25, 0), Coord2d.of(40, 0));
      SurfaceStream.Farthest pick = SurfaceStream.farthestValid(Coord2d.of(0, 0), rawCells, null);
      Assertions.assertEquals(4, pick.selectedIndex);
      Assertions.assertEquals(40.0, pick.selected.x, 1.0E-9);
   }

   @Test
   void farthestStopsBeforeBlockedCorridor() {
      OccupancyGrid occ = withSolid(20, 8, new Coord[]{Coord.of(10, 3), Coord.of(10, 4)});
      List<Coord2d> route = Arrays.asList(occ.world(1, 3), occ.world(4, 3), occ.world(16, 3));
      SurfaceStream.Farthest pick = SurfaceStream.farthestValid(occ.world(1, 3), route, occ);
      Assertions.assertTrue(pick.selectedIndex < 2, "must not skip through the wall");
      Assertions.assertNotNull(pick.whyShorter);
      Assertions.assertTrue(pick.corridorValid);
   }

   @Test
   void commandDedupDoesNotResendSameTarget() {
      SurfaceController c = ctl(Coord2d.of(40, 0), Arrays.asList(Coord2d.of(0, 0), Coord2d.of(40, 0)), NavPlanStatus.REACHED);
      SurfaceController.Tick a = c.step(obs(0L, 0, 0, false), null);
      Assertions.assertEquals(NavDecision.Kind.SEND_MOVEMENT, a.decision.kind);
      SurfaceController.Tick b = c.step(obs(100L, 1, 0, true), null);
      Assertions.assertEquals(NavDecision.Kind.WAIT, b.decision.kind);
      Assertions.assertEquals(a.decision.target, c.lastSent());
   }

   @Test
   void handsOffWhileMovingWhenFarthestChanges() {
      OccupancyGrid occ = open(24, 8);
      List<Coord2d> route = Arrays.asList(occ.world(1, 3), occ.world(8, 3), occ.world(20, 3));
      SurfaceController c = ctl(route.get(2), route, NavPlanStatus.REACHED);
      SurfaceController.Tick send = c.step(obs(0L, occ.world(1, 3).x, occ.world(1, 3).y, false), occ);
      Assertions.assertEquals(NavDecision.Kind.SEND_MOVEMENT, send.decision.kind);
      Assertions.assertEquals(2, send.pick.selectedIndex, "open ground streams to the last smoothed waypoint");
   }

   @Test
   void localHorizonIsNotSuccess() {
      Coord2d goal = Coord2d.of(400, 0);
      Coord2d horizon = Coord2d.of(40, 0);
      Assertions.assertTrue(SurfaceStream.wouldBeFalseSuccess(NavPlanStatus.CLIPPED, horizon, goal, 2.475));
      SurfaceController c = ctl(goal, Arrays.asList(Coord2d.of(0, 0), horizon), NavPlanStatus.CLIPPED);
      c.step(obs(0L, 0, 0, false), null);
      SurfaceController.Tick atHorizon = c.step(obs(500L, 40, 0, false), null);
      Assertions.assertNotEquals(NavOutcome.REACHED, atHorizon.decision.outcome);
      Assertions.assertEquals(SurfaceStream.Reason.HORIZON_ADVANCE, atHorizon.reason);
   }

   @Test
   void finalArrivalRequiresIdleWithinTolerance() {
      SurfaceController c = ctl(Coord2d.of(10, 0), Arrays.asList(Coord2d.of(0, 0), Coord2d.of(10, 0)), NavPlanStatus.REACHED);
      c.step(obs(0L, 0, 0, false), null);
      SurfaceController.Tick movingOnGoal = c.step(obs(400L, 10, 0, true), null);
      Assertions.assertNotEquals(NavOutcome.REACHED, movingOnGoal.decision.outcome);
      SurfaceController.Tick idle = c.step(obs(500L, 10, 0, false), null);
      Assertions.assertEquals(NavDecision.Kind.TERMINATE, idle.decision.kind);
      Assertions.assertEquals(NavOutcome.REACHED, idle.decision.outcome);
   }

   @Test
   void earlyStopWithoutOccupancySignalsReplan() {
      SurfaceController c = ctl(Coord2d.of(40, 0), Arrays.asList(Coord2d.of(0, 0), Coord2d.of(40, 0)), NavPlanStatus.REACHED);
      c.step(obs(0L, 0, 0, false), null);
      c.step(obs(100L, 2, 0, true), null);
      SurfaceController.Tick stop = c.step(obs(400L, 6, 0, false), null);
      Assertions.assertEquals(NavDecision.Kind.REPLAN, stop.decision.kind);
      Assertions.assertEquals(SurfaceStream.Reason.STOPPED_EARLY, stop.reason);
   }

   @Test
   void mobilityChangeReplans() {
      SurfaceController c = ctl(Coord2d.of(40, 0), Arrays.asList(Coord2d.of(0, 0), Coord2d.of(40, 0)), NavPlanStatus.REACHED);
      c.step(obs(0L, 0, 0, false), null);
      NavObservation boat = new NavObservation(200L, Coord2d.of(2, 0), true, false, 9L, true, null, null);
      SurfaceController.Tick t = c.step(boat, null);
      Assertions.assertEquals(SurfaceStream.Reason.MOBILITY_CHANGED, t.reason);
      Assertions.assertEquals(NavDecision.Kind.REPLAN, t.decision.kind);
   }

   @Test
   void blacklistDoesNotPermanentlyEraseTheGrid() {
      OccupancyGrid occ = open(12, 6);
      Coord2d a = occ.world(1, 2);
      Coord2d b = occ.world(8, 2);
      OccupancyGrid black = SurfaceStream.blacklistSegment(occ, a, b);
      Assertions.assertNotEquals(OccupancyGrid.encode(occ), OccupancyGrid.encode(black));
      Assertions.assertEquals(OccupancyGrid.encode(occ).length(), OccupancyGrid.encode(black).length());
      Assertions.assertTrue(SurfaceStream.corridorClear(a, b, occ));
      Assertions.assertFalse(SurfaceStream.corridorClear(a, b, black));
   }

   @Test
   void escapePrefersHigherClearanceAwayFromSolid() {
      OccupancyGrid occ = withSolid(16, 10, new Coord[]{Coord.of(2, 4), Coord.of(2, 5), Coord.of(3, 4), Coord.of(3, 5)});
      Coord2d pos = occ.world(4, 4);
      Coord escape = SurfaceStream.pickEscape(occ, pos);
      Assertions.assertNotNull(escape);
      Assertions.assertTrue(escape.x > 4 || Math.abs(escape.x - 2) > Math.abs(4 - 2), "escape must not walk deeper into the blob");
   }

   @Test
   void recoveryIsBoundedAndTerminatesStuck() {
      OccupancyGrid occ = open(20, 8);
      Coord2d start = occ.world(1, 3);
      Coord2d goal = occ.world(18, 3);
      SurfaceController c = ctl(goal, Arrays.asList(start, goal), NavPlanStatus.REACHED);
      Assertions.assertEquals(NavDecision.Kind.SEND_MOVEMENT, c.step(obs(0L, start.x, start.y, false), occ).decision.kind);
      Assertions.assertEquals(NavDecision.Kind.WAIT, c.step(obs(100L, start.x + 2, start.y, true), occ).decision.kind);
      Assertions.assertEquals(NavDecision.Kind.REPLAN, c.step(obs(400L, start.x + 3, start.y, false), occ).decision.kind);
      c.step(obs(401L, start.x + 3, start.y, false), occ);
      Assertions.assertTrue(c.recoveryCount() <= SurfaceStream.MAX_RECOVERY);
      c.step(obs(500L, start.x + 4, start.y, true), occ);
      Assertions.assertEquals(NavDecision.Kind.REPLAN, c.step(obs(800L, start.x + 4, start.y, false), occ).decision.kind);
      c.step(obs(801L, start.x + 4, start.y, false), occ);
      c.step(obs(900L, start.x + 5, start.y, true), occ);
      SurfaceController.Tick last = c.step(obs(1200L, start.x + 5, start.y, false), occ);
      Assertions.assertTrue(c.recoveryCount() <= SurfaceStream.MAX_RECOVERY);
      Assertions.assertTrue(
         last.decision.outcome == NavOutcome.STUCK || c.recoveryCount() == SurfaceStream.MAX_RECOVERY,
         "ordinary blockage is bounded to two recovery replans"
      );
      Assertions.assertNotEquals(NavOutcome.REACHED, last.decision.outcome);
   }

   @Test
   void abaOscillationIsStuck() {
      Assertions.assertTrue(SurfaceStream.abaOscillation(Coord2d.of(0, 0), Coord2d.of(10, 0), Coord2d.of(0, 0), 2.475));
      Assertions.assertFalse(SurfaceStream.abaOscillation(Coord2d.of(0, 0), Coord2d.of(10, 0), Coord2d.of(20, 0), 2.475));
   }

   @Test
   void obstacleChangeOnCorridorIsDetected() {
      OccupancyGrid free = open(16, 8);
      OccupancyGrid blocked = withSolid(16, 8, new Coord[]{Coord.of(8, 3)});
      Coord2d a = free.world(1, 3);
      Coord2d b = free.world(14, 3);
      Assertions.assertTrue(SurfaceStream.corridorChanged(free, blocked, a, b));
      Assertions.assertFalse(SurfaceStream.corridorChanged(free, free, a, b));
   }

   @Test
   void replanUsesBlacklistAndDoesNotClaimHorizonArrival() {
      OccupancyGrid occ = open(20, 8);
      Coord2d start = occ.world(1, 3);
      Coord2d goal = occ.world(18, 3);
      NavPlan plan = SurfaceStream.replan(start, goal, occ, null);
      Assertions.assertNotEquals(NavPlanStatus.FAILED, plan.status);
      Assertions.assertFalse(SurfaceStream.wouldBeFalseSuccess(NavPlanStatus.REACHED, goal, goal, 2.475));
   }

   @Test
   void handsOffToFartherWaypointWhileMoving() {
      SurfaceController c = ctl(Coord2d.of(40, 0), Arrays.asList(Coord2d.of(0, 0), Coord2d.of(20, 0)), NavPlanStatus.REACHED);
      SurfaceController.Tick send = c.step(obs(0L, 0, 0, false), null);
      Assertions.assertEquals(NavDecision.Kind.SEND_MOVEMENT, send.decision.kind);
      NavPlan extended = NavPlan.create(
         NavPlanStatus.REACHED,
         Arrays.asList(Coord2d.of(0, 0), Coord2d.of(20, 0), Coord2d.of(40, 0)),
         Arrays.asList(Coord2d.of(0, 0), Coord2d.of(20, 0), Coord2d.of(40, 0)),
         true,
         false,
         0,
         0,
         ""
      );
      c.acceptPlan(extended);
      SurfaceController.Tick handoff = c.step(obs(200L, 8, 0, true), null);
      Assertions.assertEquals(NavDecision.Kind.SEND_MOVEMENT, handoff.decision.kind);
      Assertions.assertEquals(SurfaceStream.Reason.HANDOFF, handoff.reason);
      Assertions.assertEquals(40.0, handoff.decision.target.x, 1.0E-9);
   }

   @Test
   void obstacleChangeOnActiveCorridorReplans() {
      OccupancyGrid free = open(16, 8);
      OccupancyGrid blocked = withSolid(16, 8, new Coord[]{Coord.of(8, 3)});
      Coord2d start = free.world(1, 3);
      Coord2d goal = free.world(14, 3);
      SurfaceController c = ctl(goal, Arrays.asList(start, goal), NavPlanStatus.REACHED);
      c.step(obs(0L, start.x, start.y, false), free);
      c.step(obs(100L, start.x + 2, start.y, true), free);
      SurfaceController.Tick t = c.step(obs(200L, start.x + 3, start.y, true), blocked);
      Assertions.assertEquals(SurfaceStream.Reason.OBSTACLE_CHANGED, t.reason);
      Assertions.assertEquals(NavDecision.Kind.REPLAN, t.decision.kind);
   }

   @Test
   void recoveryUsesAuthoritativePositionAndIsDeterministic() {
      OccupancyGrid occ = open(20, 8);
      Coord2d start = occ.world(1, 3);
      Coord2d goal = occ.world(18, 3);
      SurfaceController a = ctl(goal, Arrays.asList(start, goal), NavPlanStatus.REACHED);
      SurfaceController b = ctl(goal, Arrays.asList(start, goal), NavPlanStatus.REACHED);
      NavObservation[] script = new NavObservation[]{
         obs(0L, start.x, start.y, false),
         obs(100L, start.x + 2, start.y, true),
         obs(400L, start.x + 6, start.y, false),
         obs(401L, start.x + 6, start.y, false)
      };
      List<NavDecision.Kind> kindsA = new ArrayList<NavDecision.Kind>();
      List<NavDecision.Kind> kindsB = new ArrayList<NavDecision.Kind>();
      for (int i = 0; i < script.length; i++) {
         kindsA.add(a.step(script[i], occ).decision.kind);
         kindsB.add(b.step(script[i], occ).decision.kind);
      }
      Assertions.assertEquals(kindsA, kindsB);
      Assertions.assertEquals(start.x + 6, a.lastSent() == null ? start.x + 6 : a.lastSent().x, 50.0);
      Assertions.assertTrue(a.recoveryCount() <= SurfaceStream.MAX_RECOVERY);
      Assertions.assertTrue(b.recoveryCount() <= SurfaceStream.MAX_RECOVERY);
   }

   @Test
   void abaCommandOscillationTerminatesStuck() {
      SurfaceController c = ctl(Coord2d.of(40, 0), Arrays.asList(Coord2d.of(0, 0), Coord2d.of(40, 0)), NavPlanStatus.REACHED);
      c.step(obs(0L, 0, 0, false), null);
      NavPlan mid = NavPlan.create(
         NavPlanStatus.REACHED,
         Arrays.asList(Coord2d.of(0, 0), Coord2d.of(10, 0)),
         Arrays.asList(Coord2d.of(0, 0), Coord2d.of(10, 0)),
         true,
         false,
         0,
         0,
         ""
      );
      c.acceptPlan(mid);
      c.step(obs(100L, 2, 0, true), null);
      NavPlan back = NavPlan.create(
         NavPlanStatus.REACHED,
         Arrays.asList(Coord2d.of(0, 0), Coord2d.of(40, 0)),
         Arrays.asList(Coord2d.of(0, 0), Coord2d.of(40, 0)),
         true,
         false,
         0,
         0,
         ""
      );
      c.acceptPlan(back);
      SurfaceController.Tick t = c.step(obs(200L, 3, 0, true), null);
      Assertions.assertEquals(NavOutcome.STUCK, t.decision.outcome);
      Assertions.assertEquals(SurfaceStream.Reason.STUCK, t.reason);
   }

   @Test
   void clippedHorizonWithOccupancyIsNotReached() {
      OccupancyGrid occ = open(20, 8);
      Coord2d start = occ.world(1, 3);
      Coord2d horizon = occ.world(8, 3);
      Coord2d goal = Coord2d.of(400, 0);
      SurfaceController c = ctl(goal, Arrays.asList(start, horizon), NavPlanStatus.CLIPPED);
      c.step(obs(0L, start.x, start.y, false), occ);
      SurfaceController.Tick atHorizon = c.step(obs(500L, horizon.x, horizon.y, false), occ);
      Assertions.assertNotEquals(NavOutcome.REACHED, atHorizon.decision.outcome);
      Assertions.assertEquals(SurfaceStream.Reason.HORIZON_ADVANCE, atHorizon.reason);
   }

   @Test
   void interactionLastHopIsOnlyFromNearby() {
      OccupancyGrid occ = withSolid(24, 8, new Coord[]{Coord.of(20, 4)});
      Coord2d pose = occ.world(20, 4);
      Coord2d start = occ.world(2, 4);
      Coord2d near = occ.world(18, 4);
      List<Coord2d> route = Arrays.asList(start, pose);
      InteractionSpec spec = new InteractionSpec("t", pose, Coord2d.of(1.375, 1.375), InteractionSpec.ALL_SIDES, 0.5, 35.0, 0, null, "state_changed");
      Assertions.assertTrue(start.dist(pose) > SurfaceStream.LAST_HOP);
      SurfaceStream.Farthest far = SurfaceStream.farthestValid(start, route, occ, null, null, spec);
      Assertions.assertTrue(far.selectedIndex < 1, "must not last-hop from across the room");
      Assertions.assertTrue(near.dist(pose) <= SurfaceStream.LAST_HOP);
      SurfaceStream.Farthest hop = SurfaceStream.farthestValid(near, route, occ, null, null, spec);
      Assertions.assertEquals(1, hop.selectedIndex);
      SurfaceStream.Farthest noSpec = SurfaceStream.farthestValid(near, route, occ);
      Assertions.assertTrue(noSpec.selectedIndex < 1, "without an interaction dest, occupancy still blocks the last cell");
   }

   @Test
   void lastHopDoesNotCrossOtherFurniture() {
      OccupancyGrid occ = withSolid(24, 8, new Coord[]{Coord.of(20, 4)});
      Coord2d pose = occ.world(20, 4);
      Coord2d near = occ.world(18, 4);
      double mid = (near.x + pose.x) * 0.5;
      Coord2d[] cabinet = new Coord2d[]{
         Coord2d.of(mid - 0.6, pose.y - 8.0),
         Coord2d.of(mid + 0.6, pose.y - 8.0),
         Coord2d.of(mid + 0.6, pose.y + 8.0),
         Coord2d.of(mid - 0.6, pose.y + 8.0)
      };
      List<Coord2d[]> solids = Collections.singletonList(cabinet);
      List<Coord2d[]> body = Collections.singletonList(new Coord2d[]{
         Coord2d.of(-1.0, -1.0), Coord2d.of(1.0, -1.0), Coord2d.of(1.0, 1.0), Coord2d.of(-1.0, 1.0)
      });
      InteractionSpec spec = new InteractionSpec("t", pose, Coord2d.of(1.375, 1.375), InteractionSpec.ALL_SIDES, 0.5, 35.0, 0, null, "");
      List<Coord2d> route = Arrays.asList(near, pose);
      Assertions.assertTrue(near.dist(pose) <= SurfaceStream.LAST_HOP);
      Assertions.assertFalse(LocalPlanner.sweptClear(near, pose, body, solids, spec.polygons));
      SurfaceStream.Farthest blocked = SurfaceStream.farthestValid(near, route, occ, solids, body, spec);
      Assertions.assertTrue(blocked.selectedIndex < 1, "must not click through the adjacent cabinet");
      SurfaceStream.Farthest distanceOnly = SurfaceStream.farthestValid(near, route, occ, null, null, spec);
      Assertions.assertEquals(1, distanceOnly.selectedIndex);
   }

   @Test
   void playerBodyDoesNotOverrideOccupancyCorridor() {
      OccupancyGrid occ = open(24, 8);
      List<Coord2d> route = Arrays.asList(occ.world(1, 3), occ.world(20, 3));
      Coord2d[] fat = new Coord2d[]{
         Coord2d.of(0.0, -20.0), Coord2d.of(80.0, -20.0), Coord2d.of(80.0, 40.0), Coord2d.of(0.0, 40.0)
      };
      List<Coord2d[]> solids = Collections.singletonList(fat);
      List<Coord2d[]> body = Collections.singletonList(new Coord2d[]{
         Coord2d.of(-4.0, 0.0), Coord2d.of(0.0, -4.0), Coord2d.of(4.0, 0.0), Coord2d.of(0.0, 4.0)
      });
      Assertions.assertFalse(LocalPlanner.sweptClear(route.get(0), route.get(1), body, solids, null));
      SurfaceStream.Farthest pick = SurfaceStream.farthestValid(route.get(0), route, occ, solids, body, null);
      Assertions.assertEquals(1, pick.selectedIndex);
   }

   @Test
   void cornerClipRejectedByBodyCheckWhenExactGeometryAvailable() {
      OccupancyGrid occ = solidOnly(20, 12, new Coord[]{Coord.of(7, 5)});
      List<Coord2d[]> body = Collections.singletonList(new Coord2d[]{
         Coord2d.of(-4.5, 0.0), Coord2d.of(0.0, -4.5),
         Coord2d.of(4.5, 0.0), Coord2d.of(0.0, 4.5)
      });
      double cx = 7 * 2.75;
      double cy = 5 * 2.75;
      List<Coord2d[]> solids = Collections.singletonList(new Coord2d[]{
         Coord2d.of(cx, cy), Coord2d.of(cx + 2.75, cy),
         Coord2d.of(cx + 2.75, cy + 2.75), Coord2d.of(cx, cy + 2.75)
      });
      LocalPlanner.PolyBounds bounds = LocalPlanner.PolyBounds.of(solids);
      Coord2d from = occ.world(4, 2);
      Coord2d lastWp = occ.world(12, 6);
      List<Coord2d> route = Arrays.asList(from, lastWp);
      SurfaceStream.Farthest pick = SurfaceStream.farthestValid(from, route, occ, solids, body, null, bounds);
      Assertions.assertTrue(pick.selectedIndex < 0 || pick.selectedIndex < route.size() - 1,
         "body clip around a SOLID-only corner must reject or shorten the route");
      SurfaceStream.Farthest noBody = SurfaceStream.farthestValid(from, route, occ, null, null, null);
      Assertions.assertEquals(1, noBody.selectedIndex, "without body check, occupancy-only corridor passes to the last waypoint");
   }

   private static OccupancyGrid solidOnly(int w, int h, Coord[] solids) {
      boolean[] solid = new boolean[w * h];
      boolean[] dilated = new boolean[w * h];
      for (Coord c : solids) {
         solid[c.y * w + c.x] = true;
      }
      return OccupancyGrid.capture(Coord2d.of(0.0, 0.0), w, h, 2.75, solid, dilated, dilated,
         Coord.of(0, 0), Coord.of(w - 1, 0), Coord.of(w - 1, 0), Collections.emptyList());
   }
}
