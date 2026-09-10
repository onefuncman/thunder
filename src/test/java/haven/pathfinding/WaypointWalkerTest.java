package haven.pathfinding;

import auto.Bot;
import auto.Bot.BotAction;
import haven.Coord2d;
import haven.nav.InteractionSpec;
import haven.nav.NavPlanStatus;
import haven.pathfinding.WaypointGate.Observation;
import haven.pathfinding.WaypointGate.Outcome;
import haven.pathfinding.WaypointGate.Request;
import haven.pathfinding.WaypointWalker.Env;
import haven.pathfinding.WaypointWalker.GateClass;
import haven.pathfinding.WaypointWalker.Listener;
import haven.pathfinding.WaypointWalker.Params;
import haven.pathfinding.WaypointWalker.Result;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WaypointWalkerTest {
   private static final double EPS = 2.475;
   private static final double PROG = 0.6875;
   private static final long START = 800L;
   private static final long WALK = 20000L;
   private static final long JIGGLE = 3000L;

   private static Bot bot() {
      return Bot.execute(new BotAction[0]);
   }

   @Test
   void classifiesEveryTerminalOutcome() {
      Assertions.assertEquals(GateClass.COMPLETE, WaypointWalker.classify(Outcome.WAYPOINT_COMPLETE));
      Assertions.assertEquals(GateClass.SHORT_STOP, WaypointWalker.classify(Outcome.STOPPED_SHORT));
      Assertions.assertEquals(GateClass.REJECTED, WaypointWalker.classify(Outcome.START_TIMEOUT));
      Assertions.assertEquals(GateClass.REJECTED, WaypointWalker.classify(Outcome.VEHICLE_STATE_CHANGED));
      Assertions.assertEquals(GateClass.TIMEOUT, WaypointWalker.classify(Outcome.WALK_TIMEOUT));
      Assertions.assertEquals(GateClass.TIMEOUT, WaypointWalker.classify(Outcome.NO_PROGRESS));
      Assertions.assertEquals(GateClass.ABORT, WaypointWalker.classify(Outcome.CANCELLED));
   }

   @Test
   void rejectsNonTerminalOutcomes() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> WaypointWalker.classify(Outcome.WAITING_START));
      Assertions.assertThrows(IllegalArgumentException.class, () -> WaypointWalker.classify(Outcome.PROGRESSING));
   }

   @Test
   void formatsObservationBriefs() {
      Observation o = new Observation(1000L, Coord2d.of(10.24, 3.12), true, 42L, true, false);
      String brief = WaypointWalker.obsBrief(o);
      Assertions.assertTrue(brief.contains("at=(10.2,3.1)"), brief);
      Assertions.assertTrue(brief.contains("moving=yes"), brief);
      Assertions.assertTrue(brief.contains("veh=42"), brief);
      Assertions.assertTrue(brief.contains("pass=yes"), brief);
      Assertions.assertEquals("obs=none", WaypointWalker.obsBrief(null));
   }

   @Test
   void composesGateLabels() {
      Assertions.assertEquals(
         "gate STOPPED_SHORT  short stop wp 2/5  at=(10.2,3.1) moving=no",
         WaypointWalker.gateLabel(Outcome.STOPPED_SHORT, "short stop wp 2/5", "at=(10.2,3.1) moving=no")
      );
      Assertions.assertEquals("no gate", WaypointWalker.gateLabel(null, "no gate", null));
      Assertions.assertEquals("gate CANCELLED", WaypointWalker.gateLabel(Outcome.CANCELLED, null, null));
   }

   @Test
   void paramsDefaultsMirrorGateDefaults() {
      Assertions.assertEquals(2.475, Params.DEFAULT.eps, 1.0E-9);
      Assertions.assertEquals(0.6875, Params.DEFAULT.moveProgress, 1.0E-9);
      Assertions.assertEquals(800L, Params.DEFAULT.startTimeoutMs);
      Assertions.assertEquals(20000L, Params.DEFAULT.walkTimeoutMs);
      Assertions.assertEquals(3000L, Params.DEFAULT.jiggleWindowMs);
   }

   @Test
   void paramsRejectInvalidValues() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Params(0.0, 0.6875, 800L, 20000L, 3000L));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Params(2.475, 0.0, 800L, 20000L, 3000L));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Params(2.475, 0.6875, -1L, 20000L, 3000L));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Params(2.475, 0.6875, 800L, 799L, 3000L));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Params(2.475, 0.6875, 800L, 20000L, 0L));
   }

   @Test
   void paramsRequestCarriesConfiguredValues() {
      Request r = Params.DEFAULT.request(Coord2d.of(10.0, 0.0));
      Assertions.assertEquals(2.475, r.eps, 1.0E-9);
      Assertions.assertEquals(0.6875, r.moveProgress, 1.0E-9);
      Assertions.assertEquals(800L, r.startTimeoutMs);
      Assertions.assertEquals(20000L, r.walkTimeoutMs);
      Assertions.assertEquals(3000L, r.jiggleWindowMs);
      Assertions.assertEquals(10.0, r.target.x, 1.0E-9);
   }

   @Test
   void arrivesWalkingEveryWaypoint() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 2.0, 0.0, true);
      env.obs(800L, 10.0, 0.0, true);
      env.obs(1500L, 16.0, 0.0, true);
      env.obs(2000L, 20.0, 0.0, true);
      env.obs(2500L, 20.0, 0.0, false);
      Assertions.assertEquals(
         Result.ARRIVED,
         WaypointWalker.execute(env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0), Coord2d.of(20.0, 0.0)), 3, 20000L, Params.DEFAULT, rec)
      );
      Assertions.assertEquals(List.of(Coord2d.of(20.0, 0.0)), env.clicks, "open-ground streaming clicks the farthest smoothed waypoint once");
      Assertions.assertTrue(rec.events.stream().anyMatch(e -> e.contains("issue stream replan=3 at=(20.0,0.0)")), rec.events.toString());
      Assertions.assertTrue(rec.events.stream().anyMatch(e -> e.contains("gate reached stream")), rec.events.toString());
      Assertions.assertEquals("waypoint", rec.waits.get(0)[0]);
      Assertions.assertEquals("stream replan=3", rec.waits.get(0)[2]);
      Assertions.assertTrue(rec.fails.isEmpty(), rec.fails.toString());
      Assertions.assertTrue(rec.gateFails.isEmpty(), rec.gateFails.toString());
      Assertions.assertEquals(0, rec.dumpStuckCalls);
   }

   @Test
   void skipsWaypointsAlreadyWithinEps() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 0.5, 0.0, true);
      env.obs(300L, 2.0, 0.0, true);
      env.obs(800L, 8.0, 0.0, true);
      env.obs(1000L, 10.0, 0.0, true);
      env.obs(1500L, 10.0, 0.0, false);
      Assertions.assertEquals(
         Result.ARRIVED,
         WaypointWalker.execute(env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(0.5, 0.0), Coord2d.of(10.0, 0.0)), 0, 20000L, Params.DEFAULT, rec)
      );
      Assertions.assertEquals(List.of(Coord2d.of(10.0, 0.0)), env.clicks, "the waypoint already within eps is skipped without a click");
   }

   @Test
   void shortStopReturnsShortStop() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 0.5, 0.0, true);
      env.obs(500L, 5.0, 0.0, true);
      env.obs(800L, 6.5, 0.0, false);
      Assertions.assertEquals(
         Result.SHORT_STOP, WaypointWalker.execute(env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)), 0, 20000L, Params.DEFAULT, rec)
      );
      String[] gf = rec.gateFails.get(0);
      Assertions.assertTrue(gf[0].startsWith("short stop stream left=3.50"), gf[0]);
      Assertions.assertEquals("STOPPED_SHORT", gf[1]);
      Assertions.assertTrue(gf[2].contains("moving=no"), gf[2]);
      Assertions.assertEquals(0, rec.dumpStuckCalls, "a short stop is a retry signal, not a stuck dump");
   }

   @Test
   void startTimeoutRejectsTheLeg() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(400L, 0.0, 0.0, false);
      env.obs(800L, 0.0, 0.0, false);
      Assertions.assertEquals(
         Result.REJECTED, WaypointWalker.execute(env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)), 0, 20000L, Params.DEFAULT, rec)
      );
      String[] gf = rec.gateFails.get(0);
      Assertions.assertTrue(gf[0].startsWith("wp stream abandoned"), gf[0]);
      Assertions.assertEquals("START_TIMEOUT", gf[1]);
      Assertions.assertEquals(1, rec.dumpStuckCalls);
   }

   @Test
   void walkTimeoutTimesOutTheLeg() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 1.0, 0.0, true);
      env.obs(19900L, 8.0, 0.0, true);
      env.obs(20000L, 8.1, 0.0, true);
      Assertions.assertEquals(
         Result.TIMEOUT, WaypointWalker.execute(env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)), 2, 20000L, Params.DEFAULT, rec)
      );
      String[] gf = rec.gateFails.get(0);
      Assertions.assertTrue(gf[0].startsWith("wp stream timed out"), gf[0]);
      Assertions.assertEquals("WALK_TIMEOUT", gf[1]);
      Assertions.assertEquals(1, rec.dumpStuckCalls);
   }

   @Test
   void noProgressTimesOutTheLeg() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 0.5, 0.0, true);
      env.obs(3100L, 0.5, 0.0, true);
      Assertions.assertEquals(
         Result.TIMEOUT, WaypointWalker.execute(env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)), 0, 20000L, Params.DEFAULT, rec)
      );
      Assertions.assertEquals("NO_PROGRESS", rec.gateFails.get(0)[1]);
      Assertions.assertEquals(1, rec.dumpStuckCalls);
   }

   @Test
   void budgetExhaustedBeforeStartReturnsTimeout() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 0.0, 0.0, false);
      env.clockAt(1, 20001L);
      Assertions.assertEquals(
         Result.TIMEOUT, WaypointWalker.execute(env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)), 0, 20000L, Params.DEFAULT, rec)
      );
      String[] gf = rec.gateFails.get(0);
      Assertions.assertTrue(gf[0].startsWith("waypoint budget exhausted"), gf[0]);
      Assertions.assertEquals(1, env.clicks.size());
      Assertions.assertEquals(1, rec.dumpStuckCalls);
   }

   @Test
   void midWalkDeadlineBreakTimesOut() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 1.0, 0.0, true);
      env.obs(150L, 1.0, 0.0, true);
      env.clockAt(2, 25000L);
      Assertions.assertEquals(
         Result.TIMEOUT, WaypointWalker.execute(env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)), 0, 20000L, Params.DEFAULT, rec)
      );
      Assertions.assertTrue(rec.gateFails.get(0)[0].startsWith("waypoint budget exhausted"), rec.gateFails.get(0)[0]);
      Assertions.assertEquals(1, rec.dumpStuckCalls);
   }

   @Test
   void cancelledObservationAbortsTheWalk() {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 1.0, 0.0, true);
      env.obs(200L, 2.0, 0.0, true, 0L, false, true);
      InterruptedException e = (InterruptedException)Assertions.assertThrows(
         InterruptedException.class,
         () -> WaypointWalker.execute(env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)), 0, 20000L, Params.DEFAULT, rec)
      );
      Assertions.assertEquals("Waypoint walk cancelled", e.getMessage());
      String[] gf = rec.gateFails.get(0);
      Assertions.assertTrue(gf[0].startsWith("cancelled stream"), gf[0]);
      Assertions.assertEquals("CANCELLED", gf[1]);
      Assertions.assertEquals(1, env.clicks.size());
      Assertions.assertEquals(0, rec.dumpStuckCalls);
   }

   @Test
   void playerGoneBeforeWaypointReturnsRejected() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 0.0, 0.0, false);
      Assertions.assertEquals(
         Result.REJECTED, WaypointWalker.execute(env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)), 0, 20000L, Params.DEFAULT, rec)
      );
      Assertions.assertEquals("player gone wp stream", rec.fails.get(0));
      Assertions.assertEquals(1, env.clicks.size());
      Assertions.assertEquals(0, rec.dumpStuckCalls);
   }

   @Test
   void playerGoneMidWaitReturnsRejected() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 1.0, 0.0, true);
      Assertions.assertEquals(
         Result.REJECTED, WaypointWalker.execute(env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)), 0, 20000L, Params.DEFAULT, rec)
      );
      Assertions.assertEquals("player gone wp stream", rec.fails.get(0));
      Assertions.assertEquals(1, env.clicks.size());
      Assertions.assertEquals(0, rec.dumpStuckCalls);
   }

   @Test
   void emptySingleAndNullRoutesArriveImmediately() throws InterruptedException {
      List<List<Coord2d>> routes = new ArrayList<>();
      routes.add(List.of());
      routes.add(List.of(Coord2d.of(0.0, 0.0)));
      routes.add(null);

      for (List<Coord2d> route : routes) {
         WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
         WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
         Assertions.assertEquals(
            Result.ARRIVED,
            WaypointWalker.execute(env, bot(), route, 0, 20000L, Params.DEFAULT, rec),
            "route with fewer than two waypoints has nothing to walk"
         );
         Assertions.assertTrue(env.clicks.isEmpty());
         Assertions.assertTrue(rec.events.isEmpty());
         Assertions.assertTrue(rec.fails.isEmpty());
         Assertions.assertTrue(rec.gateFails.isEmpty());
      }
   }

   @Test
   void interactionArrivalIsNotMovementSuccessAndDoesNotClick() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      WaypointWalkerTest.Recorder rec = new WaypointWalkerTest.Recorder();
      env.obs(0L, 20.0, 0.0, false);
      InteractionSpec spec = new InteractionSpec(
         "t1", Coord2d.of(20.0, 0.0), Coord2d.of(1.375, 1.375), InteractionSpec.ALL_SIDES, 0.5, 16.5, 0, null, "target_gone"
      );
      Assertions.assertEquals(
         Result.READY_TO_INTERACT,
         WaypointWalker.execute(
            env, bot(), List.of(Coord2d.of(0.0, 0.0), Coord2d.of(20.0, 0.0)), 0, 20000L, Params.DEFAULT, rec,
            NavPlanStatus.REACHED, Coord2d.of(20.0, 0.0), spec
         )
      );
      Assertions.assertTrue(env.clicks.isEmpty(), env.clicks.toString());
      Assertions.assertTrue(rec.events.stream().anyMatch(e -> e.contains("ready to interact")), rec.events.toString());
   }

   @Test
   void interactionWithTooShortRouteIsReadyWithoutClick() throws InterruptedException {
      WaypointWalkerTest.FakeEnv env = new WaypointWalkerTest.FakeEnv();
      InteractionSpec spec = new InteractionSpec(
         "t1", Coord2d.of(0.0, 0.0), Coord2d.of(1.375, 1.375), InteractionSpec.ALL_SIDES, 0.5, 16.5, 0, null, "target_gone"
      );
      Assertions.assertEquals(
         Result.READY_TO_INTERACT,
         WaypointWalker.execute(
            env, bot(), List.of(Coord2d.of(0.0, 0.0)), 0, 20000L, Params.DEFAULT, new WaypointWalkerTest.Recorder(),
            NavPlanStatus.REACHED, Coord2d.of(0.0, 0.0), spec
         )
      );
      Assertions.assertTrue(env.clicks.isEmpty());
   }

   private static final class FakeEnv implements Env {
      final List<Coord2d> clicks = new ArrayList<>();
      final List<Observation> script = new ArrayList<>();
      final List<Long> clock = new ArrayList<>();
      long nowMs = 0L;
      int at = 0;

      void obs(long t, double x, double y, boolean moving) {
         this.script.add(new Observation(t, Coord2d.of(x, y), moving));
      }

      void obs(long t, double x, double y, boolean moving, long veh, boolean pass, boolean cancelled) {
         this.script.add(new Observation(t, Coord2d.of(x, y), moving, veh, pass, cancelled));
      }

      void clockAt(int idx, long ms) {
         while (this.clock.size() <= idx) {
            this.clock.add(null);
         }

         this.clock.set(idx, ms);
      }

      public Observation observe(boolean cancelled) {
         if (this.at >= this.script.size()) {
            return null;
         } else {
            Observation o = this.script.get(this.at);
            if (this.at < this.clock.size() && this.clock.get(this.at) != null) {
               this.nowMs = Math.max(this.nowMs, this.clock.get(this.at));
            }

            this.at++;
            if (cancelled) {
               o = new Observation(o.tMs, o.pos, o.moving, o.vehicleId, o.passenger, true);
            }

            return o;
         }
      }

      public void click(Coord2d waypoint) {
         this.clicks.add(waypoint);
      }

      public long now() {
         return this.nowMs;
      }
   }

   private static final class Recorder implements Listener {
      final List<String> events = new ArrayList<>();
      final List<String> fails = new ArrayList<>();
      final List<String[]> gateFails = new ArrayList<>();
      final List<Object[]> waits = new ArrayList<>();
      int dumpStuckCalls = 0;

      public void event(String msg) {
         this.events.add(msg);
      }

      public void fail(String msg) {
         this.fails.add(msg);
      }

      public void fail(String msg, Outcome oc, String obs) {
         this.gateFails.add(new String[]{msg, oc == null ? null : oc.name(), obs});
      }

      public void beginWait(String what, long ms, String detail) {
         this.waits.add(new Object[]{what, ms, detail});
      }

      public void dumpStuck() {
         this.dumpStuckCalls++;
      }
   }
}
