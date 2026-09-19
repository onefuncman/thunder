package haven.pathfinding;

import auto.Bot;
import haven.Coord;
import haven.Coord2d;
import haven.Following;
import haven.GameUI;
import haven.Gob;
import haven.Moving;
import haven.OCache;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Executes a local route one confirmed command at a time.
 *
 * <p>This runner intentionally has no streaming or moving hand-off. The next
 * click is not sent until {@link WaypointGate} has observed the player idle at
 * the previous waypoint.</p>
 */
public final class ConfirmedRouteRunner {
   private ConfirmedRouteRunner() {
   }

   public enum Status {
      ARRIVED,
      BLOCKED,
      TIMEOUT,
      MOBILITY_CHANGED,
      PLAYER_GONE
   }

   public static final class Result {
      public final Status status;
      public final Coord2d end;
      public final int clicks;
      public final int retries;
      public final String detail;

      Result(Status status, Coord2d end, int clicks, int retries, String detail) {
         this.status = status;
         this.end = end;
         this.clicks = clicks;
         this.retries = retries;
         this.detail = detail == null ? "" : detail;
      }

      public boolean arrived() {
         return status == Status.ARRIVED;
      }
   }

   public static final class Params {
      public static final Params LAND = new Params(2.475, 0.6875, 800L, 20000L, 3000L, 220.0, 2);
      public static final Params BOAT_LOCAL = new Params(4.0, 0.6875, 1200L, 30000L, 3000L, 110.0, 2);
      public static final Params BOAT_APPROACH = new Params(1.5, 0.5, 1200L, 30000L, 3000L, 55.0, 2);
      // Saved water-route points are strategic input owned by Pearler. Do not
      // insert new points between them in the local movement layer.
      public static final Params BOAT_ROUTE = new Params(4.0, 0.6875, 900L, 12000L, 1500L,
         Double.MAX_VALUE, 2);

      public final double eps;
      public final double moveProgress;
      public final long startTimeoutMs;
      public final long walkTimeoutMs;
      public final long jiggleWindowMs;
      public final double maxHop;
      public final int waypointRetries;

      public Params(double eps, double moveProgress, long startTimeoutMs, long walkTimeoutMs,
                    long jiggleWindowMs, double maxHop, int waypointRetries) {
         if (!(eps > 0.0) || !(moveProgress > 0.0) || startTimeoutMs < 0L
            || walkTimeoutMs < startTimeoutMs || jiggleWindowMs <= 0L
            || !(maxHop > 0.0) || waypointRetries < 0) {
            throw new IllegalArgumentException("invalid confirmed route parameters");
         }
         this.eps = eps;
         this.moveProgress = moveProgress;
         this.startTimeoutMs = startTimeoutMs;
         this.walkTimeoutMs = walkTimeoutMs;
         this.jiggleWindowMs = jiggleWindowMs;
         this.maxHop = maxHop;
         this.waypointRetries = waypointRetries;
      }

      WaypointGate.Request request(Coord2d target) {
         return new WaypointGate.Request(target, eps, moveProgress, startTimeoutMs, walkTimeoutMs, jiggleWindowMs);
      }
   }

   public interface Cancellation {
      void check() throws InterruptedException;
   }

   public interface Env {
      WaypointGate.Observation observe(boolean cancelled);
      void click(Coord2d waypoint);
      long now();

      default void pause() throws InterruptedException {
         Thread.sleep(50L);
      }
   }

   public static Env liveEnv(final GameUI gui) {
      return new Env() {
         @Override
         public WaypointGate.Observation observe(boolean cancelled) {
            return observeLive(gui, cancelled);
         }

         @Override
         public void click(Coord2d waypoint) {
            gui.pathQueue.clear();
            gui.map.wdgmsg("click", new Object[]{Coord.z, waypoint.floor(OCache.posres), 1, 0});
         }

         @Override
         public long now() {
            return System.currentTimeMillis();
         }
      };
   }

   public static Result execute(GameUI gui, final Bot bot, List<Coord2d> route, Coord2d goal,
                                long budgetMs, Params params) throws InterruptedException {
      return execute(gui, bot, route, goal, budgetMs, params, MovementListeners.NOOP);
   }

   public static Result execute(GameUI gui, final Bot bot, List<Coord2d> route, Coord2d goal,
                                long budgetMs, Params params, MovementListener listener) throws InterruptedException {
      if (bot == null) {
         throw new IllegalArgumentException("bot is null");
      }
      return execute(liveEnv(gui), new Cancellation() {
         @Override
         public void check() throws InterruptedException {
            bot.checkCancelled();
         }
      }, route, goal, budgetMs, params, listener);
   }

   static Result execute(Env env, Cancellation cancellation, List<Coord2d> route, Coord2d goal,
                         long budgetMs, Params params) throws InterruptedException {
      return execute(env, cancellation, route, goal, budgetMs, params, MovementListeners.NOOP);
   }

   static Result execute(Env env, Cancellation cancellation, List<Coord2d> route, Coord2d goal,
                         long budgetMs, Params params, MovementListener listener) throws InterruptedException {
      if (env == null || cancellation == null || goal == null) {
         throw new IllegalArgumentException("env, cancellation, and goal are required");
      }
      Params p = params == null ? Params.LAND : params;
      MovementListener out = listener == null ? MovementListeners.NOOP : listener;
      out.beginWait("route", budgetMs, "confirmed sequential route");
      WaypointGate.Observation obs = env.observe(false);
      if (obs == null) {
         out.fail("player unavailable");
         return result(Status.PLAYER_GONE, null, 0, 0, "player unavailable");
      }
      List<Coord2d> hops = shortHops(route, p.maxHop);
      if (hops.isEmpty()) {
         hops = Collections.singletonList(obs.pos);
      }
      long deadline = env.now() + Math.max(0L, budgetMs);
      int clicks = 0;
      int totalRetries = 0;

      for (int wi = 1; wi < hops.size(); wi++) {
         Coord2d target = hops.get(wi);
         if (!obs.moving && obs.pos.dist(target) <= p.eps) {
            continue;
         }
         int retries = 0;
         while (true) {
            cancellation.check();
            if (env.now() > deadline) {
               return result(Status.TIMEOUT, obs.pos, clicks, totalRetries, "route budget exhausted");
            }
            env.click(target);
            clicks++;
            out.event(String.format("click waypoint %d/%d", wi, hops.size() - 1));
            PathfinderLog.setActiveWaypoint(target);
            WaypointGate gate = new WaypointGate(p.request(target));
            WaypointGate.Outcome outcome = gate.start(obs);
            while (!outcome.isTerminal()) {
               cancellation.check();
               env.pause();
               obs = env.observe(false);
               if (obs == null) {
                  out.fail("player disappeared");
                  return result(Status.PLAYER_GONE, null, clicks, totalRetries, "player disappeared");
               }
               outcome = gate.observe(obs);
               if (env.now() > deadline) {
                  outcome = WaypointGate.Outcome.WALK_TIMEOUT;
                  break;
               }
            }
            if (outcome == WaypointGate.Outcome.WAYPOINT_COMPLETE) {
               break;
            }
            if (outcome == WaypointGate.Outcome.CANCELLED) {
               throw new InterruptedException("Confirmed route cancelled");
            }
            if (outcome == WaypointGate.Outcome.VEHICLE_STATE_CHANGED) {
               out.fail("mobility changed", outcome, observationBrief(obs));
               return result(Status.MOBILITY_CHANGED, obs.pos, clicks, totalRetries, outcome.name());
            }
            boolean retryable = outcome == WaypointGate.Outcome.STOPPED_SHORT
               || outcome == WaypointGate.Outcome.START_TIMEOUT
               || outcome == WaypointGate.Outcome.NO_PROGRESS;
            if (retryable && retries < p.waypointRetries) {
               retries++;
               totalRetries++;
               continue;
            }
            Status failed = outcome == WaypointGate.Outcome.WALK_TIMEOUT
               || outcome == WaypointGate.Outcome.NO_PROGRESS ? Status.TIMEOUT : Status.BLOCKED;
            out.fail("confirmed route " + outcome, outcome, observationBrief(obs));
            if (failed == Status.BLOCKED) out.dumpStuck();
            return result(failed, obs.pos, clicks, totalRetries, outcome.name());
         }
      }

      obs = env.observe(false);
      PathfinderLog.setActiveWaypoint(null);
      if (obs == null) {
         out.fail("player unavailable after route");
         return result(Status.PLAYER_GONE, null, clicks, totalRetries, "player unavailable after route");
      }
      if (obs.moving || obs.pos.dist(goal) > p.eps) {
         out.fail(obs.moving ? "still moving at route end" : "stopped short of goal");
         return result(Status.BLOCKED, obs.pos, clicks, totalRetries,
            obs.moving ? "still moving at route end" : "stopped short of goal");
      }
      out.event("confirmed route arrived");
      return result(Status.ARRIVED, obs.pos, clicks, totalRetries, "");
   }

   private static String observationBrief(WaypointGate.Observation observation) {
      return observation == null ? "obs=none" : String.format(
         "at=(%.1f,%.1f) moving=%s veh=%d pass=%s", observation.pos.x, observation.pos.y,
         observation.moving ? "yes" : "no", observation.vehicleId,
         observation.passenger ? "yes" : "no"
      );
   }

   static List<Coord2d> shortHops(List<Coord2d> route, double maxHop) {
      List<Coord2d> out = new ArrayList<Coord2d>();
      if (route == null || route.isEmpty()) {
         return out;
      }
      out.add(route.get(0));
      for (int i = 1; i < route.size(); i++) {
         Coord2d from = out.get(out.size() - 1);
         Coord2d to = route.get(i);
         if (to == null) {
            continue;
         }
         double distance = from.dist(to);
         int pieces = Math.max(1, (int)Math.ceil(distance / maxHop));
         for (int piece = 1; piece <= pieces; piece++) {
            double f = (double)piece / pieces;
            out.add(Coord2d.of(from.x + ((to.x - from.x) * f), from.y + ((to.y - from.y) * f)));
         }
      }
      return out;
   }

   public static WaypointGate.Observation observeLive(GameUI gui) {
      return observeLive(gui, false);
   }

   private static WaypointGate.Observation observeLive(GameUI gui, boolean cancelled) {
      Gob player = gui != null && gui.map != null ? gui.map.player() : null;
      Coord2d position = BoatNavigation.position(player);
      if (player == null || position == null) {
         return null;
      }
      Moving moving = (Moving)player.getattr(Moving.class);
      boolean passenger = moving instanceof Following && isPassenger((Following)moving);
      PathfinderLog.recordConfirmedPos(position);
      return new WaypointGate.Observation(System.currentTimeMillis(), position,
         BoatNavigation.moving(player), player.vehicleId(), passenger, cancelled);
   }

   private static boolean isPassenger(Following follow) {
      if (follow == null || follow.tgt() == null) {
         return false;
      }
      String id = follow.tgt().resid();
      if (id == null) {
         return false;
      }
      String pos = follow.xfname;
      if (id.contains("/vehicle/snekkja")) return !"m0".equals(pos);
      if (id.contains("/vehicle/knarr")) return !"m9".equals(pos);
      if (id.contains("/vehicle/rowboat") || id.contains("/vehicle/spark")) return !"d".equals(pos);
      return id.contains("/vehicle/wagon") && !"d0".equals(pos);
   }

   private static Result result(Status status, Coord2d end, int clicks, int retries, String detail) {
      return new Result(status, end, clicks, retries, detail);
   }
}
