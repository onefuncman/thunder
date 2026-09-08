package haven.pathfinding;

import auto.Bot;
import haven.Coord;
import haven.Coord2d;
import haven.Following;
import haven.GameUI;
import haven.Gob;
import haven.Moving;
import haven.OCache;
import haven.nav.GraphNode;
import haven.nav.InteractionSpec;
import haven.nav.NavDecision;
import haven.nav.NavObservation;
import haven.nav.NavOutcome;
import haven.nav.NavPlanStatus;
import java.util.List;

public final class WaypointWalker {
   private WaypointWalker() {
   }

   public static WaypointWalker.Env liveEnv(final GameUI gui) {
      return new WaypointWalker.Env() {
         @Override
         public WaypointGate.Observation observe(boolean cancelled) {
            return WaypointWalker.observe(gui, cancelled);
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

         @Override
         public OccupancyGrid occupancy() {
            OccupancyGrid cached = PathfinderLog.lastOccupancy();
            return cached != null ? cached : refreshOccupancy();
         }

         @Override
         public OccupancyGrid refreshOccupancy() {
            PrototypePathfinder.Scene scene = PrototypePathfinder.observe(gui, false);
            return scene == null ? null : scene.occupancy;
         }
      };
   }

   public static WaypointWalker.Result execute(GameUI gui, Bot bot, List<Coord2d> route, int replan, long walkBudgetMs, WaypointWalker.Listener l) throws InterruptedException {
      return execute(liveEnv(gui), bot, route, replan, walkBudgetMs, WaypointWalker.Params.DEFAULT, l, NavPlanStatus.REACHED);
   }

   public static WaypointWalker.Result execute(WaypointWalker.Env env, Bot bot, List<Coord2d> route, int replan, long walkBudgetMs, WaypointWalker.Listener l) throws InterruptedException {
      return execute(env, bot, route, replan, walkBudgetMs, WaypointWalker.Params.DEFAULT, l, NavPlanStatus.REACHED);
   }

   public static WaypointWalker.Result execute(
      WaypointWalker.Env env, Bot bot, List<Coord2d> route, int replan, long walkBudgetMs, WaypointWalker.Params params, WaypointWalker.Listener l
   ) throws InterruptedException {
      return execute(env, bot, route, replan, walkBudgetMs, params, l, NavPlanStatus.REACHED, null);
   }

   public static WaypointWalker.Result execute(
      WaypointWalker.Env env,
      Bot bot,
      List<Coord2d> route,
      int replan,
      long walkBudgetMs,
      WaypointWalker.Params params,
      WaypointWalker.Listener l,
      NavPlanStatus planStatus
   ) throws InterruptedException {
      return execute(env, bot, route, replan, walkBudgetMs, params, l, planStatus, null, null);
   }

   public static WaypointWalker.Result execute(
      WaypointWalker.Env env,
      Bot bot,
      List<Coord2d> route,
      int replan,
      long walkBudgetMs,
      WaypointWalker.Params params,
      WaypointWalker.Listener l,
      NavPlanStatus planStatus,
      Coord2d originalGoal
   ) throws InterruptedException {
      return execute(env, bot, route, replan, walkBudgetMs, params, l, planStatus, originalGoal, null);
   }

   public static WaypointWalker.Result execute(
      WaypointWalker.Env env,
      Bot bot,
      List<Coord2d> route,
      int replan,
      long walkBudgetMs,
      WaypointWalker.Params params,
      WaypointWalker.Listener l,
      NavPlanStatus planStatus,
      Coord2d originalGoal,
      InteractionSpec interaction
   ) throws InterruptedException {
      if (route == null || route.size() < 2) {
         return interaction != null ? WaypointWalker.Result.READY_TO_INTERACT : WaypointWalker.Result.ARRIVED;
      }
      Coord2d goal = originalGoal != null ? originalGoal : route.get(route.size() - 1);
      WaypointWalker.Params p = params == null ? WaypointWalker.Params.DEFAULT : params;
         SurfaceController ctl = interaction != null
            ? SurfaceController.forInteraction(goal, route, planStatus, p.eps, p.moveProgress, p.startTimeoutMs, p.walkTimeoutMs, p.jiggleWindowMs, interaction)
            : SurfaceController.of(goal, route, planStatus, p.eps, p.moveProgress, p.startTimeoutMs, p.walkTimeoutMs, p.jiggleWindowMs);
         if (interaction != null) {
            ctl.setExactGeometry(PathfinderLog.lastSolids(), PathfinderLog.lastPlayerBody());
         }
      long deadline = env.now() + walkBudgetMs;
      OccupancyGrid occ = env.occupancy();
      if (occ == null) {
         // Occupancy was invalidated (e.g. after a surface transition): rebuild
         // instead of handing a null/stale grid to SurfaceController.
         occ = env.refreshOccupancy();
      }
      boolean first = true;
      WaypointGate.Observation obs = null;
      l.beginWait("waypoint", walkBudgetMs, "stream replan=" + replan);
      while (true) {
         if (env.now() > deadline) {
            l.fail("waypoint budget exhausted (gate " + (obs == null ? "none" : "PROGRESSING") + ")", null, obsBrief(obs));
            l.dumpStuck();
            PathfinderLog.dumpFailure("walk budget exhausted before waypoint progress");
            return WaypointWalker.Result.TIMEOUT;
         }
         obs = env.observe(false);
         if (obs == null) {
            l.fail(first ? "player gone before wp 1/" + (route.size() - 1) : "player gone wp stream");
            return WaypointWalker.Result.REJECTED;
         }
         first = false;
         NavObservation navObs = new NavObservation(obs.tMs, obs.pos, obs.moving, obs.cancelled, obs.vehicleId, obs.passenger, obs.worldId, obs.segmentId);
         SurfaceController.Tick tick = ctl.step(navObs, occ);
         for (int spin = 0; tick.decision.kind == NavDecision.Kind.REPLAN && spin < 4; spin++) {
            OccupancyGrid fresh = env.refreshOccupancy();
            if (fresh != null) {
               occ = fresh;
               tick = ctl.step(navObs, occ);
            } else if (tick.reason == SurfaceStream.Reason.STOPPED_EARLY) {
               l.fail("short stop stream left=" + String.format("%.2f", obs.pos.dist(goal)), WaypointGate.Outcome.STOPPED_SHORT, obsBrief(obs));
               return WaypointWalker.Result.SHORT_STOP;
            } else if (tick.reason == SurfaceStream.Reason.NO_PROGRESS) {
               l.fail("wp stream timed out", WaypointGate.Outcome.NO_PROGRESS, obsBrief(obs));
               l.dumpStuck();
               PathfinderLog.dumpFailure("walk stalled: wp stream NO_PROGRESS timeout");
               return WaypointWalker.Result.TIMEOUT;
            } else {
               break;
            }
         }
         PathfinderLog.setActiveWaypoint(tick.pick == null ? ctl.lastSent() : tick.pick.selected);
         PathfinderLog.recordExec(tick);
         if (tick.decision.kind == NavDecision.Kind.INTERACT) {
            l.event("ready to interact");
            return WaypointWalker.Result.READY_TO_INTERACT;
         }
         if (tick.decision.kind == NavDecision.Kind.TRANSITION) {
            l.event("transition");
            return WaypointWalker.Result.TRANSITION;
         }
         if (tick.decision.kind == NavDecision.Kind.SEND_MOVEMENT && tick.decision.target != null) {
            Coord2d before = obs.pos;
            env.click(tick.decision.target);
            PathfinderLog.setActiveWaypoint(tick.decision.target);
            l.event(String.format("issue stream replan=%d at=(%.1f,%.1f) from=(%.1f,%.1f) reason=%s", replan, tick.decision.target.x, tick.decision.target.y, before.x, before.y, tick.reason));
         }
         if (tick.decision.kind == NavDecision.Kind.TERMINATE) {
            return finish(tick, obs, goal, l);
         }
         try {
            bot.checkCancelled();
            Thread.sleep(50L);
         } catch (InterruptedException ex) {
            WaypointGate.Observation canc = env.observe(true);
            if (canc == null) {
               throw ex;
            }
            NavObservation cobs = new NavObservation(canc.tMs, canc.pos, canc.moving, true, canc.vehicleId, canc.passenger, canc.worldId, canc.segmentId);
            SurfaceController.Tick ct = ctl.step(cobs, occ);
            l.fail("cancelled stream", WaypointGate.Outcome.CANCELLED, obsBrief(canc));
            throw new InterruptedException("Waypoint walk cancelled");
         }
      }
   }

   private static WaypointWalker.Result finish(SurfaceController.Tick tick, WaypointGate.Observation obs, Coord2d goal, WaypointWalker.Listener l) throws InterruptedException {
      NavOutcome out = tick.decision.outcome;
      if (out == NavOutcome.REACHED) {
         l.event(String.format("gate reached stream left=%.2f", obs == null || goal == null ? 0.0 : obs.pos.dist(goal)));
         return WaypointWalker.Result.ARRIVED;
      }
      if (out == NavOutcome.TRANSITION_FAILED) {
         l.fail("transition failed " + tick.decision.reason, null, obsBrief(obs));
         return WaypointWalker.Result.REJECTED;
      }
      if (out == NavOutcome.CANCELLED) {
         l.fail("cancelled stream", WaypointGate.Outcome.CANCELLED, obsBrief(obs));
         throw new InterruptedException("Waypoint walk cancelled");
      }
      if (out == NavOutcome.STUCK) {
         l.fail("stuck " + tick.decision.reason, WaypointGate.Outcome.STOPPED_SHORT, obsBrief(obs));
         l.dumpStuck();
         PathfinderLog.dumpFailure("walk stalled: " + tick.decision.reason);
         return WaypointWalker.Result.STUCK;
      }
      if (tick.reason == SurfaceStream.Reason.HORIZON_ADVANCE) {
         l.fail("horizon is not success stream left=" + String.format("%.2f", obs == null || goal == null ? 0.0 : obs.pos.dist(goal)), WaypointGate.Outcome.STOPPED_SHORT, obsBrief(obs));
         return WaypointWalker.Result.SHORT_STOP;
      }
      if (out == NavOutcome.BUDGET_EXHAUSTED) {
         String gate = tick.reason == SurfaceStream.Reason.WALK_TIMEOUT ? "WALK_TIMEOUT" : tick.reason == SurfaceStream.Reason.NO_PROGRESS ? "NO_PROGRESS" : "TIMEOUT";
         l.fail("wp stream timed out", WaypointGate.Outcome.valueOf(tick.reason == SurfaceStream.Reason.NO_PROGRESS ? "NO_PROGRESS" : "WALK_TIMEOUT"), obsBrief(obs));
         l.dumpStuck();
         PathfinderLog.dumpFailure("walk timed out: " + gate);
         return WaypointWalker.Result.TIMEOUT;
      }
      if (tick.reason == SurfaceStream.Reason.START_TIMEOUT) {
         l.fail("wp stream abandoned", WaypointGate.Outcome.START_TIMEOUT, obsBrief(obs));
         l.dumpStuck();
         PathfinderLog.dumpFailure("walk never started: START_TIMEOUT");
         return WaypointWalker.Result.REJECTED;
      }
      l.fail("wp stream " + tick.reason, null, obsBrief(obs));
      return WaypointWalker.Result.REJECTED;
   }

   public static WaypointGate.Observation observe(GameUI gui) {
      return observe(gui, false);
   }

   public static WaypointGate.Observation observe(GameUI gui, boolean cancelled) {
      Gob me = gui != null && gui.map != null ? gui.map.player() : null;
      if (me != null && me.rc != null) {
         Moving mv = (Moving)me.getattr(Moving.class);
         boolean passenger = false;
         if (mv instanceof Following) {
            passenger = isPassenger((Following)mv);
         }

         PathfinderLog.recordConfirmedPos(me.rc);
         GraphNode node = WorldGraphAdapter.current(gui);
         return new WaypointGate.Observation(
            System.currentTimeMillis(),
            me.rc,
            mv != null,
            me.vehicleId(),
            passenger,
            cancelled,
            node == null ? null : node.worldId,
            node == null ? null : node.segmentId
         );
      } else {
         return null;
      }
   }

   private static boolean isPassenger(Following follow) {
      if (follow != null && follow.tgt() != null) {
         String id = follow.tgt().resid();
         if (id == null) {
            return false;
         } else {
            String pos = follow.xfname;
            if (id.contains("/vehicle/snekkja")) {
               return !"m0".equals(pos);
            } else if (id.contains("/vehicle/knarr")) {
               return !"m9".equals(pos);
            } else if (id.contains("/vehicle/rowboat")) {
               return !"d".equals(pos);
            } else if (id.contains("/vehicle/spark")) {
               return !"d".equals(pos);
            } else {
               return id.contains("/vehicle/wagon") ? !"d0".equals(pos) : false;
            }
         }
      } else {
         return false;
      }
   }

   public static WaypointWalker.GateClass classify(WaypointGate.Outcome oc) {
      switch (oc) {
         case WAYPOINT_COMPLETE:
            return WaypointWalker.GateClass.COMPLETE;
         case STOPPED_SHORT:
            return WaypointWalker.GateClass.SHORT_STOP;
         case START_TIMEOUT:
         case VEHICLE_STATE_CHANGED:
            return WaypointWalker.GateClass.REJECTED;
         case WALK_TIMEOUT:
         case NO_PROGRESS:
            return WaypointWalker.GateClass.TIMEOUT;
         case CANCELLED:
            return WaypointWalker.GateClass.ABORT;
         default:
            throw new IllegalArgumentException("non-terminal outcome " + oc);
      }
   }

   public static String obsBrief(WaypointGate.Observation o) {
      return o == null
         ? "obs=none"
         : String.format("at=(%.1f,%.1f) moving=%s veh=%d pass=%s", o.pos.x, o.pos.y, o.moving ? "yes" : "no", o.vehicleId, o.passenger ? "yes" : "no");
   }

   public static String gateLabel(WaypointGate.Outcome outcome, String reason, String obs) {
      StringBuilder sb = new StringBuilder();
      if (outcome != null) {
         sb.append("gate ").append(outcome);
      }

      if (reason != null && !reason.isEmpty()) {
         if (sb.length() > 0) {
            sb.append("  ");
         }

         sb.append(reason);
      }

      if (obs != null && !obs.isEmpty()) {
         if (sb.length() > 0) {
            sb.append("  ");
         }

         sb.append(obs);
      }

      return sb.toString();
   }

   public interface Env {
      WaypointGate.Observation observe(boolean var1);

      void click(Coord2d var1);

      long now();

      default OccupancyGrid occupancy() {
         return null;
      }

      default OccupancyGrid refreshOccupancy() {
         return occupancy();
      }
   }

   public static enum GateClass {
      COMPLETE,
      SHORT_STOP,
      REJECTED,
      TIMEOUT,
      ABORT;
   }

   public interface Listener {
      void event(String var1);

      void fail(String var1);

      void fail(String var1, WaypointGate.Outcome var2, String var3);

      void beginWait(String var1, long var2, String var4);

      void dumpStuck();
   }

   public static final class Params {
      public final double eps;
      public final double moveProgress;
      public final long startTimeoutMs;
      public final long walkTimeoutMs;
      public final long jiggleWindowMs;
      public static final WaypointWalker.Params DEFAULT = new WaypointWalker.Params(2.475, 0.6875, 800L, 20000L, 3000L);

      public Params(double eps, double moveProgress, long startTimeoutMs, long walkTimeoutMs, long jiggleWindowMs) {
         if (!(eps > 0.0)) {
            throw new IllegalArgumentException("eps must be > 0: " + eps);
         } else if (!(moveProgress > 0.0)) {
            throw new IllegalArgumentException("moveProgress must be > 0: " + moveProgress);
         } else if (startTimeoutMs < 0L) {
            throw new IllegalArgumentException("startTimeoutMs must be >= 0: " + startTimeoutMs);
         } else if (walkTimeoutMs < startTimeoutMs) {
            throw new IllegalArgumentException("walkTimeoutMs must be >= startTimeoutMs: " + walkTimeoutMs + " < " + startTimeoutMs);
         } else if (jiggleWindowMs <= 0L) {
            throw new IllegalArgumentException("jiggleWindowMs must be > 0: " + jiggleWindowMs);
         } else {
            this.eps = eps;
            this.moveProgress = moveProgress;
            this.startTimeoutMs = startTimeoutMs;
            this.walkTimeoutMs = walkTimeoutMs;
            this.jiggleWindowMs = jiggleWindowMs;
         }
      }

      public WaypointGate.Request request(Coord2d target) {
         return new WaypointGate.Request(target, this.eps, this.moveProgress, this.startTimeoutMs, this.walkTimeoutMs, this.jiggleWindowMs);
      }
   }

   public static enum Result {
      ARRIVED,
      READY_TO_INTERACT,
      TRANSITION,
      REJECTED,
      SHORT_STOP,
      TIMEOUT,
      STUCK;
   }
}
