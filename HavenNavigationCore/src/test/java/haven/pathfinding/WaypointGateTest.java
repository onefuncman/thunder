package haven.pathfinding;

import haven.Coord2d;
import haven.pathfinding.WaypointGate.Observation;
import haven.pathfinding.WaypointGate.Outcome;
import haven.pathfinding.WaypointGate.Request;
import haven.pathfinding.WaypointGate.State;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WaypointGateTest {
   private static final double EPS = 2.475;
   private static final double PROG = 0.6875;
   private static final long START = 800L;
   private static final long WALK = 20000L;
   private static final long JIGGLE = 3000L;

   private static Request req(Coord2d target) {
      return new Request(target);
   }

   private static Request req(Coord2d target, long startMs, long walkMs) {
      return new Request(target, 2.475, 0.6875, startMs, walkMs, 3000L);
   }

   private static Request req(Coord2d target, long startMs, long walkMs, long jiggleMs) {
      return new Request(target, 2.475, 0.6875, startMs, walkMs, jiggleMs);
   }

   private static Observation obs(long t, double x, double y, boolean moving) {
      return new Observation(t, Coord2d.of(x, y), moving);
   }

   private static Observation obs(long t, double x, double y, boolean moving, long veh, boolean pass, boolean cancelled) {
      return new Observation(t, Coord2d.of(x, y), moving, veh, pass, cancelled);
   }

   @Test
   void clickIssuedStaysInWaitingForStartUntilSignal() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      Assertions.assertEquals(Outcome.WAITING_START, g.start(obs(0L, 0.0, 0.0, false)));

      for (long t = 100L; t < 800L; t += 100L) {
         Assertions.assertEquals(Outcome.WAITING_START, g.observe(obs(t, 0.0, 0.0, false)), "still waiting at t=" + t);
      }

      Assertions.assertFalse(g.isDone());
      Assertions.assertEquals(700L, g.elapsedMs());
      Assertions.assertEquals(10.0, g.lastDistance(), 1.0E-9);
      Assertions.assertEquals(State.WAITING_START, g.state());
   }

   @Test
   void alreadyAtTargetAndStoppedCompletesAtStart() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(0.0, 0.0)));
      Assertions.assertEquals(Outcome.WAYPOINT_COMPLETE, g.start(obs(0L, 0.0, 0.0, false)));
      Assertions.assertTrue(g.isDone());
      Assertions.assertTrue(g.outcome().isSuccess());
   }

   @Test
   void startTimeoutWhenClickNeverAccepted() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      Assertions.assertEquals(Outcome.WAITING_START, g.observe(obs(700L, 0.0, 0.0, false)), "just under the start window is still waiting");
      Assertions.assertEquals(Outcome.START_TIMEOUT, g.observe(obs(800L, 0.0, 0.0, false)), "the start window elapses exactly at startTimeoutMs");
      Assertions.assertTrue(g.isDone());
      Assertions.assertEquals(800L, g.elapsedMs());
   }

   @Test
   void movingSignalStartsTheWalk() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      Assertions.assertEquals(Outcome.PROGRESSING, g.observe(obs(100L, 0.1, 0.0, true)));
      Assertions.assertEquals(State.PROGRESSING, g.state());
      Assertions.assertFalse(g.isDone());
   }

   @Test
   void displacementWithoutMovingIsAnAcceptedStart() {
      WaypointGate shortG = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      shortG.start(obs(0L, 0.0, 0.0, false));
      Assertions.assertEquals(Outcome.STOPPED_SHORT, shortG.observe(obs(100L, 2.0, 0.0, false)), "snapped 2.0 cells, still beyond eps");
      WaypointGate hitG = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      hitG.start(obs(0L, 0.0, 0.0, false));
      Assertions.assertEquals(Outcome.WAYPOINT_COMPLETE, hitG.observe(obs(100L, 10.0, 0.0, false)), "snapped onto the target while stopped");
   }

   @Test
   void alreadyMovingAtBaselineNeedsDisplacementOrTimeout() {
      WaypointGate moving = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      moving.start(obs(0L, 0.0, 0.0, true));
      Assertions.assertEquals(Outcome.PROGRESSING, moving.observe(obs(100L, 1.0, 0.0, true)), "displacement from the click-time position starts the walk");
      WaypointGate stuck = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      stuck.start(obs(0L, 0.0, 0.0, true));
      Assertions.assertEquals(Outcome.WAITING_START, stuck.observe(obs(100L, 0.0, 0.0, true)));
      Assertions.assertEquals(Outcome.START_TIMEOUT, stuck.observe(obs(800L, 0.0, 0.0, true)));
   }

   @Test
   void walkTimeoutMustNotBeLessThanStartTimeout() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> req(Coord2d.of(10.0, 0.0), 800L, 799L));
      new Request(Coord2d.of(10.0, 0.0), 2.475, 0.6875, 800L, 800L, 3000L);
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Request(Coord2d.of(10.0, 0.0), 0.0, 0.6875, 800L, 20000L, 3000L));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Request(Coord2d.of(10.0, 0.0), 2.475, 0.0, 800L, 20000L, 3000L));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Request(Coord2d.of(10.0, 0.0), 2.475, 0.6875, 800L, 20000L, 0L));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Request(null));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Observation(0L, null, false));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WaypointGate(null));
   }

   @Test
   void distanceAloneWhileMovingNeverCompletes() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(100L, 1.0, 0.0, true));
      Assertions.assertEquals(Outcome.PROGRESSING, g.observe(obs(200L, 10.0, 0.0, true)), "at target but moving -> progressing");
      Assertions.assertEquals(Outcome.PROGRESSING, g.observe(obs(300L, 10.0, 0.0, true)), "still at target, still moving -> progressing");
      Assertions.assertFalse(g.isDone(), "never completed while Moving is attached");
      Assertions.assertEquals(Outcome.WAYPOINT_COMPLETE, g.observe(obs(400L, 10.0, 0.0, false)), "only the authoritative stop completes the waypoint");
      Assertions.assertTrue(g.outcome().isSuccess());
   }

   @Test
   void completeOnlyAfterAuthoritativeStopNearTarget() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(100L, 1.0, 0.0, true));
      g.observe(obs(500L, 5.0, 0.0, true));
      g.observe(obs(1000L, 8.0, 0.0, true));
      Assertions.assertEquals(Outcome.WAYPOINT_COMPLETE, g.observe(obs(1500L, 10.0, 0.0, false)));
      Assertions.assertEquals(0.0, g.lastDistance(), 1.0E-9);
   }

   @Test
   void stoppedShortBeyondTolerance() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(100L, 1.0, 0.0, true));
      g.observe(obs(500L, 5.0, 0.0, true));
      Assertions.assertEquals(Outcome.STOPPED_SHORT, g.observe(obs(1000L, 6.5, 0.0, false)), "stopped 3.5 from the target, beyond eps 2.475");
      Assertions.assertTrue(g.isDone());
      Assertions.assertFalse(g.outcome().isSuccess());
   }

   @Test
   void stopInsideToleranceCompletesAndOutsideDoesNot() {
      WaypointGate in = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      in.start(obs(0L, 0.0, 0.0, false));
      in.observe(obs(100L, 1.0, 0.0, true));
      Assertions.assertEquals(Outcome.WAYPOINT_COMPLETE, in.observe(obs(200L, 7.7, 0.0, false)), "7.7 is 2.3 from the target, within eps");
      WaypointGate out = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      out.start(obs(0L, 0.0, 0.0, false));
      out.observe(obs(100L, 1.0, 0.0, true));
      Assertions.assertEquals(Outcome.STOPPED_SHORT, out.observe(obs(200L, 7.4, 0.0, false)), "7.4 is 2.6 from the target, beyond eps");
   }

   @Test
   void walkTimeoutWhileStillMoving() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(100L, 1.0, 0.0, true));
      Assertions.assertEquals(Outcome.PROGRESSING, g.observe(obs(19900L, 8.0, 0.0, true)), "just under the walk budget is still progressing");
      Assertions.assertEquals(Outcome.WALK_TIMEOUT, g.observe(obs(20000L, 8.1, 0.0, true)), "the budget elapses exactly at walkTimeoutMs");
      Assertions.assertTrue(g.isDone());
      Assertions.assertEquals(20000L, g.elapsedMs());
   }

   @Test
   void walkBudgetIncludesTheStartWait() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0), 3000L, 8000L));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(1000L, 0.0, 0.0, false));
      g.observe(obs(3000L, 0.0, 0.0, true));
      Assertions.assertEquals(Outcome.PROGRESSING, g.observe(obs(7900L, 5.0, 0.0, true)), "walking since 3000, total elapsed 7900 < 8000");
      Assertions.assertEquals(Outcome.WALK_TIMEOUT, g.observe(obs(8000L, 5.1, 0.0, true)), "total elapsed from start() crosses the budget");
   }

   @Test
   void authoritativeStopWinsOverAnExpiredBudget() {
      WaypointGate ok = new WaypointGate(req(Coord2d.of(10.0, 0.0), 800L, 20000L));
      ok.start(obs(0L, 0.0, 0.0, false));
      ok.observe(obs(100L, 1.0, 0.0, true));
      Assertions.assertEquals(Outcome.WAYPOINT_COMPLETE, ok.observe(obs(25000L, 10.0, 0.0, false)), "stopped on target past the budget still completes");
      WaypointGate shortStop = new WaypointGate(req(Coord2d.of(10.0, 0.0), 800L, 20000L));
      shortStop.start(obs(0L, 0.0, 0.0, false));
      shortStop.observe(obs(100L, 1.0, 0.0, true));
      Assertions.assertEquals(
         Outcome.STOPPED_SHORT, shortStop.observe(obs(25000L, 6.5, 0.0, false)), "stopped short past the budget is a short stop, not a timeout"
      );
      WaypointGate stillMoving = new WaypointGate(req(Coord2d.of(10.0, 0.0), 800L, 20000L));
      stillMoving.start(obs(0L, 0.0, 0.0, false));
      stillMoving.observe(obs(100L, 1.0, 0.0, true));
      Assertions.assertEquals(Outcome.WALK_TIMEOUT, stillMoving.observe(obs(25000L, 9.9, 0.0, true)), "still moving past the budget is a timeout");
   }

   @Test
   void jiggleDetectedWhenMovingWithNoDisplacement() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(100L, 0.1, 0.0, true));
      Assertions.assertEquals(Outcome.PROGRESSING, g.observe(obs(3000L, 0.2, 0.0, true)), "small motion, but the 3s window has not elapsed since the anchor");
      WaypointGate stuck = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      stuck.start(obs(0L, 0.0, 0.0, false));
      stuck.observe(obs(100L, 0.1, 0.0, true));
      Assertions.assertEquals(Outcome.PROGRESSING, stuck.observe(obs(3099L, 0.1, 0.0, true)), "just under the jiggle window is still progressing");
      Assertions.assertEquals(Outcome.NO_PROGRESS, stuck.observe(obs(3100L, 0.1, 0.0, true)), "3.0s of zero displacement while Moving stays attached");
      Assertions.assertTrue(stuck.isDone());
   }

   @Test
   void jiggleDetectedOnOscillation() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(100L, 0.1, 0.0, true));
      g.observe(obs(500L, 0.4, 0.0, true));
      g.observe(obs(1000L, 0.1, 0.0, true));
      g.observe(obs(1500L, 0.4, 0.0, true));
      g.observe(obs(2000L, 0.1, 0.0, true));
      Assertions.assertEquals(Outcome.PROGRESSING, g.observe(obs(2500L, 0.4, 0.0, true)));
      Assertions.assertEquals(Outcome.NO_PROGRESS, g.observe(obs(3100L, 0.1, 0.0, true)), "oscillation since 100 with no net progress reaches the window");
   }

   @Test
   void jiggleNearTargetIsNotCompletion() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(100L, 1.0, 0.0, true));
      Assertions.assertEquals(Outcome.PROGRESSING, g.observe(obs(200L, 10.0, 0.0, true)), "on target, moving -> progressing, never complete");
      Assertions.assertEquals(Outcome.NO_PROGRESS, g.observe(obs(3200L, 10.0, 0.0, true)), "on target, moving, no displacement -> jiggle");
   }

   @Test
   void steadySlowProgressNeverFalseTriggersJiggle() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(100L, 0.1, 0.0, true));
      double x = 0.5;

      for (long t = 600L; t <= 9600L; t += 500L) {
         Assertions.assertEquals(Outcome.PROGRESSING, g.observe(obs(t, x, 0.0, true)), "steady walk at t=" + t);
         x += 0.4;
      }

      Assertions.assertEquals(Outcome.WAYPOINT_COMPLETE, g.observe(obs(10100L, 10.0, 0.0, false)));
   }

   @Test
   void jiggleVariesWithConfiguredWindow() {
      WaypointGate fast = new WaypointGate(req(Coord2d.of(10.0, 0.0), 800L, 20000L, 1000L));
      fast.start(obs(0L, 0.0, 0.0, false));
      fast.observe(obs(100L, 0.1, 0.0, true));
      Assertions.assertEquals(Outcome.PROGRESSING, fast.observe(obs(1099L, 0.1, 0.0, true)));
      Assertions.assertEquals(Outcome.NO_PROGRESS, fast.observe(obs(1100L, 0.1, 0.0, true)));
   }

   @Test
   void cancelledWinsInBothStatesAndBeatsEverything() {
      WaypointGate waiting = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      waiting.start(obs(0L, 0.0, 0.0, false));
      Assertions.assertEquals(Outcome.CANCELLED, waiting.observe(obs(100L, 0.0, 0.0, false, 0L, false, true)));
      WaypointGate walking = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      walking.start(obs(0L, 0.0, 0.0, false));
      walking.observe(obs(100L, 1.0, 0.0, true));
      Assertions.assertEquals(Outcome.CANCELLED, walking.observe(obs(200L, 10.0, 0.0, false, 55L, false, true)));
      WaypointGate atStart = new WaypointGate(req(Coord2d.of(0.0, 0.0)));
      Assertions.assertEquals(Outcome.CANCELLED, atStart.start(obs(0L, 0.0, 0.0, false, 0L, false, true)));
   }

   @Test
   void vehicleStateChangeDetectedInWaitingPhase() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      Assertions.assertEquals(
         Outcome.VEHICLE_STATE_CHANGED, g.observe(obs(100L, 0.0, 0.0, false, 1234L, false, false)), "boarding a vehicle while waiting for the click"
      );
   }

   @Test
   void vehicleStateChangeDetectedWhileProgressing() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(100L, 1.0, 0.0, true));
      Assertions.assertEquals(Outcome.VEHICLE_STATE_CHANGED, g.observe(obs(200L, 3.0, 0.0, true, 1234L, false, false)), "vehicle took over mid-walk");
   }

   @Test
   void passengerStateChangeIsAVehicleChange() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false, 1234L, false, false));
      Assertions.assertEquals(Outcome.VEHICLE_STATE_CHANGED, g.observe(obs(100L, 0.0, 0.0, false, 1234L, true, false)), "same vehicle, passenger flag flipped");
   }

   @Test
   void vehicleChangeBeatsTimeout() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      Assertions.assertEquals(
         Outcome.VEHICLE_STATE_CHANGED, g.observe(obs(900L, 0.0, 0.0, false, 7L, false, false)), "past startTimeoutMs, but the vehicle change is reported"
      );
   }

   @Test
   void noVehicleChangeIsNotReported() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false, 0L, false, false));
      Assertions.assertEquals(Outcome.PROGRESSING, g.observe(obs(100L, 1.0, 0.0, true, 0L, false, false)));
      WaypointGate riding = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      riding.start(obs(0L, 0.0, 0.0, false, 99L, false, false));
      Assertions.assertEquals(
         Outcome.PROGRESSING, riding.observe(obs(100L, 1.0, 0.0, true, 99L, false, false)), "driving the same vehicle all along is not a change"
      );
   }

   @Test
   void observeBeforeStartThrows() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      Assertions.assertThrows(IllegalStateException.class, () -> g.observe(obs(0L, 0.0, 0.0, false)));
   }

   @Test
   void doubleStartThrows() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      Assertions.assertThrows(IllegalStateException.class, () -> g.start(obs(0L, 0.0, 0.0, false)));
   }

   @Test
   void terminalOutcomeIsIdempotent() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(700L, 0.0, 0.0, false));
      Outcome first = g.observe(obs(800L, 0.0, 0.0, false));
      Assertions.assertEquals(Outcome.START_TIMEOUT, first);
      Assertions.assertEquals(first, g.observe(obs(900L, 5.0, 0.0, true)), "further observations keep returning the terminal outcome");
      Assertions.assertTrue(g.isDone());
   }

   @Test
   void backwardsTimestampsAreClamped() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0), 800L, 20000L));
      g.start(obs(1000L, 0.0, 0.0, false));
      g.observe(obs(900L, 0.0, 0.0, false));
      Assertions.assertEquals(0L, g.elapsedMs(), "clamped to the start timestamp");
      Assertions.assertEquals(Outcome.WAITING_START, g.observe(obs(0L, 0.0, 0.0, false)));
      WaypointGate walk = new WaypointGate(req(Coord2d.of(10.0, 0.0), 800L, 20000L));
      walk.start(obs(100L, 0.0, 0.0, false));
      walk.observe(obs(200L, 1.0, 0.0, true));
      walk.observe(obs(50L, 1.0, 0.0, true));
      Assertions.assertEquals(100L, walk.elapsedMs(), "clamped to the last seen timestamp");
      Assertions.assertEquals(Outcome.WALK_TIMEOUT, walk.observe(obs(20500L, 1.0, 0.0, true)), "the clamped clock still advances past the budget");
   }

   @Test
   void zeroStartTimeoutTimesOutImmediately() {
      WaypointGate g = new WaypointGate(new Request(Coord2d.of(10.0, 0.0), 2.475, 0.6875, 0L, 20000L, 3000L));
      Assertions.assertEquals(Outcome.START_TIMEOUT, g.start(obs(0L, 0.0, 0.0, false)));
   }

   @Test
   void lastDistanceReflectsLastObservation() {
      WaypointGate g = new WaypointGate(req(Coord2d.of(10.0, 0.0)));
      g.start(obs(0L, 0.0, 0.0, false));
      g.observe(obs(100L, 4.0, 0.0, true));
      Assertions.assertEquals(6.0, g.lastDistance(), 1.0E-9);
      Assertions.assertEquals(10.0, g.target().x, 1.0E-9);
      Assertions.assertEquals(100L, g.elapsedMs());
   }
}
