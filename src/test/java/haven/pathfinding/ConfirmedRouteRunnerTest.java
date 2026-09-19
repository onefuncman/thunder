package haven.pathfinding;

import haven.Coord2d;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ConfirmedRouteRunnerTest {
   private static final ConfirmedRouteRunner.Cancellation NEVER_CANCEL = new ConfirmedRouteRunner.Cancellation() {
      @Override public void check() { }
   };

   @Test
   void waitsForIdleBeforeSendingTheNextCorner() throws InterruptedException {
      FakeEnv env = new FakeEnv();
      env.add(0L, 0.0, false);
      env.add(100L, 5.0, true);
      env.add(200L, 10.0, false);
      env.add(300L, 15.0, true);
      env.add(400L, 20.0, false);
      env.add(450L, 20.0, false);

      ConfirmedRouteRunner.Result result = ConfirmedRouteRunner.execute(
         env, NEVER_CANCEL,
         List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0), Coord2d.of(20.0, 0.0)),
         Coord2d.of(20.0, 0.0), 5000L, params(2)
      );

      Assertions.assertEquals(ConfirmedRouteRunner.Status.ARRIVED, result.status);
      Assertions.assertEquals(List.of(Coord2d.of(10.0, 0.0), Coord2d.of(20.0, 0.0)), env.clicks);
      Assertions.assertFalse(env.clickedWhileMoving);
   }

   @Test
   void retriesACornerTwiceThenReturnsBlocked() throws InterruptedException {
      FakeEnv env = new FakeEnv();
      env.add(0L, 0.0, false);
      env.add(100L, 2.0, true);
      env.add(200L, 2.0, false);
      env.add(300L, 4.0, true);
      env.add(400L, 4.0, false);
      env.add(500L, 6.0, true);
      env.add(600L, 6.0, false);

      ConfirmedRouteRunner.Result result = ConfirmedRouteRunner.execute(
         env, NEVER_CANCEL, List.of(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)),
         Coord2d.of(10.0, 0.0), 5000L, params(2)
      );

      Assertions.assertEquals(ConfirmedRouteRunner.Status.BLOCKED, result.status);
      Assertions.assertEquals(3, result.clicks);
      Assertions.assertEquals(2, result.retries);
   }

   @Test
   void reachingAClippedEndpointIsNotGoalSuccess() throws InterruptedException {
      FakeEnv env = new FakeEnv();
      env.add(0L, 0.0, false);
      env.add(100L, 3.0, true);
      env.add(200L, 5.0, false);
      env.add(250L, 5.0, false);

      ConfirmedRouteRunner.Result result = ConfirmedRouteRunner.execute(
         env, NEVER_CANCEL, List.of(Coord2d.of(0.0, 0.0), Coord2d.of(5.0, 0.0)),
         Coord2d.of(10.0, 0.0), 5000L, params(0)
      );

      Assertions.assertEquals(ConfirmedRouteRunner.Status.BLOCKED, result.status);
      Assertions.assertEquals("stopped short of goal", result.detail);
   }

   @Test
   void boatRouteKeepsTheStrategicPointsExactly() throws InterruptedException {
      FakeEnv env = new FakeEnv();
      env.add(0L, 0.0, false);
      env.add(100L, 250.0, true);
      env.add(200L, 500.0, false);
      env.add(300L, 750.0, true);
      env.add(400L, 1000.0, false);
      env.add(450L, 1000.0, false);

      ConfirmedRouteRunner.Result result = ConfirmedRouteRunner.execute(
         env, NEVER_CANCEL,
         List.of(Coord2d.of(0.0, 0.0), Coord2d.of(500.0, 0.0), Coord2d.of(1000.0, 0.0)),
         Coord2d.of(1000.0, 0.0), 5000L, ConfirmedRouteRunner.Params.BOAT_ROUTE
      );

      Assertions.assertEquals(ConfirmedRouteRunner.Status.ARRIVED, result.status);
      Assertions.assertEquals(List.of(Coord2d.of(500.0, 0.0), Coord2d.of(1000.0, 0.0)), env.clicks);
   }

   private static ConfirmedRouteRunner.Params params(int retries) {
      return new ConfirmedRouteRunner.Params(0.5, 0.25, 1000L, 5000L, 1000L, 100.0, retries);
   }

   private static final class FakeEnv implements ConfirmedRouteRunner.Env {
      final ArrayDeque<WaypointGate.Observation> observations = new ArrayDeque<WaypointGate.Observation>();
      final List<Coord2d> clicks = new ArrayList<Coord2d>();
      WaypointGate.Observation last;
      boolean clickedWhileMoving;

      void add(long time, double x, boolean moving) {
         observations.add(new WaypointGate.Observation(time, Coord2d.of(x, 0.0), moving));
      }

      @Override
      public WaypointGate.Observation observe(boolean cancelled) {
         if (!observations.isEmpty()) last = observations.removeFirst();
         return last;
      }

      @Override
      public void click(Coord2d waypoint) {
         clickedWhileMoving |= last != null && last.moving;
         clicks.add(waypoint);
      }

      @Override
      public long now() {
         return last == null ? 0L : last.tMs;
      }

      @Override
      public void pause() { }
   }
}
