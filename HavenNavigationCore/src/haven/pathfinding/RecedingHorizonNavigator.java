package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RecedingHorizonNavigator {
   private static volatile haven.nav.NavigationTelemetrySink telemetry = haven.nav.NavigationTelemetrySink.NONE;
   private final RecedingHorizonNavigator.LocalPlanner localPlanner;
   private final RecedingHorizonNavigator.LegWalker walker;
   private final RecedingHorizonNavigator.CoarsePlanner coarsePlanner;
   private final RecedingHorizonNavigator.TileMap tileMap;
   private final RecedingHorizonNavigator.Bounds bounds;

   public RecedingHorizonNavigator(
      RecedingHorizonNavigator.LocalPlanner localPlanner,
      RecedingHorizonNavigator.LegWalker walker,
      RecedingHorizonNavigator.CoarsePlanner coarsePlanner,
      RecedingHorizonNavigator.TileMap tileMap,
      RecedingHorizonNavigator.Bounds bounds
   ) {
      this.localPlanner = Objects.requireNonNull(localPlanner, "localPlanner");
      this.walker = Objects.requireNonNull(walker, "walker");
      this.coarsePlanner = Objects.requireNonNull(coarsePlanner, "coarsePlanner");
      this.tileMap = Objects.requireNonNull(tileMap, "tileMap");
      this.bounds = Objects.requireNonNull(bounds, "bounds");
   }

   public RecedingHorizonNavigator(
      RecedingHorizonNavigator.LocalPlanner localPlanner,
      RecedingHorizonNavigator.LegWalker walker,
      RecedingHorizonNavigator.CoarsePlanner coarsePlanner,
      RecedingHorizonNavigator.TileMap tileMap
   ) {
      this(localPlanner, walker, coarsePlanner, tileMap, RecedingHorizonNavigator.Bounds.DEFAULT);
   }

   public RecedingHorizonNavigator.Result run(Coord goalTile, List<Coord> route, Coord2d startPos) {
      Objects.requireNonNull(goalTile, "goalTile");
      Objects.requireNonNull(startPos, "startPos");
      Coord startTile = this.tileMap.toTile(startPos);
      if (!validRoute(route, goalTile, startTile)) {
         return new RecedingHorizonNavigator.Result(
            RecedingHorizonNavigator.Outcome.COARSE_PLAN_INVALID, 0, 0, routeReason(route, goalTile, startTile), startPos, 0
         );
      } else {
         RecedingHorizonNavigator.State st = new RecedingHorizonNavigator.State(route, startPos);

         while (true) {
            Coord at = this.tileMap.toTile(st.pos);
            if (at != null && at.equals(goalTile) && st.idx >= st.route.size()) {
               return new RecedingHorizonNavigator.Result(RecedingHorizonNavigator.Outcome.REACHED_DESTINATION, st.legs, st.replans, null, st.pos, st.idx);
            }
            if (st.idx >= st.route.size()) {
               RecedingHorizonNavigator.Result extra = this.replan(st, goalTile);
               if (extra != null) {
                  return extra;
               }
               continue;
            }
            Coord2d target = this.tileMap.toWorld(st.route.get(st.idx));
            RecedingHorizonNavigator.LocalPlan local = this.localPlanner.plan(st.pos, target);
            switch (local.status) {
               case REACHED:
                  RecedingHorizonNavigator.Result termxxx = this.walkLeg(st, local, goalTile);
                  if (termxxx != null) {
                     return termxxx;
                  }

                  if (!st.skipAdvance) {
                     st.idx++;
                  }
                  st.skipAdvance = false;
                  break;
               case CLIPPED:
                  RecedingHorizonNavigator.Result termxx = this.walkLeg(st, local, goalTile);
                  if (termxx != null) {
                     return termxx;
                  }
                  st.skipAdvance = false;
                  break;
               case SNAPPED:
               case PARTIAL:
                  RecedingHorizonNavigator.Result termx = this.walkLeg(st, local, goalTile);
                  if (termx != null) {
                     return termx;
                  }
                  st.skipAdvance = false;

                  termx = this.replan(st, goalTile);
                  if (termx != null) {
                     return termx;
                  }
                  break;
               case FAILED:
                  RecedingHorizonNavigator.Result term = this.replan(st, goalTile);
                  if (term != null) {
                     return term;
                  }
                  break;
               default:
                  throw new AssertionError(local.status);
            }
         }
      }
   }

   private RecedingHorizonNavigator.Result walkLeg(RecedingHorizonNavigator.State st, RecedingHorizonNavigator.LocalPlan plan, Coord goalTile) {
      if (st.legs >= this.bounds.maxLegs) {
         return new RecedingHorizonNavigator.Result(RecedingHorizonNavigator.Outcome.LEG_LIMIT_EXHAUSTED, st.legs, st.replans, null, st.pos, st.idx);
      } else {
         RecedingHorizonNavigator.LegResult r = this.walker.walk(st.pos, plan);
         st.legs++;
         switch (r.outcome) {
            case SUCCESS:
               st.pos = r.end;
               return null;
            case STOPPED_EARLY:
               st.pos = r.end;
               noteReplan("STOPPED_EARLY");
               RecedingHorizonNavigator.Result early = this.replan(st, goalTile);
               if (early == null) {
                  st.skipAdvance = true;
               }
               return early;
            case STUCK:
               return new RecedingHorizonNavigator.Result(RecedingHorizonNavigator.Outcome.STUCK, st.legs, st.replans, r.failure, r.end, st.idx);
            case FAILED:
               return new RecedingHorizonNavigator.Result(RecedingHorizonNavigator.Outcome.TERMINAL_FAILURE, st.legs, st.replans, r.failure, r.end, st.idx);
            case CANCELLED:
               return new RecedingHorizonNavigator.Result(RecedingHorizonNavigator.Outcome.CANCELLED, st.legs, st.replans, r.failure, r.end, st.idx);
            default:
               throw new AssertionError(r.outcome);
         }
      }
   }

   private RecedingHorizonNavigator.Result replan(RecedingHorizonNavigator.State st, Coord goalTile) {
      if (st.replans >= this.bounds.maxReplans) {
         noteReplan("replan_limit");
         return new RecedingHorizonNavigator.Result(RecedingHorizonNavigator.Outcome.REPLAN_LIMIT_EXHAUSTED, st.legs, st.replans, null, st.pos, st.idx);
      } else {
         st.replans++;
         Coord curTile = this.tileMap.toTile(st.pos);
         CoarseRoutePlanner.Route nr = this.coarsePlanner.plan(curTile, goalTile, this.bounds.coarseExpanded);
         if (nr == null || !nr.reached()) {
            noteReplan("coarse_plan_failed");
            return new RecedingHorizonNavigator.Result(
               RecedingHorizonNavigator.Outcome.COARSE_PLAN_FAILED, st.legs, st.replans, coarseDetail(nr), st.pos, st.idx
            );
         } else if (!validRoute(nr.waypoints, goalTile, curTile)) {
            noteReplan("coarse_plan_invalid");
            return new RecedingHorizonNavigator.Result(
               RecedingHorizonNavigator.Outcome.COARSE_PLAN_INVALID, st.legs, st.replans, routeReason(nr.waypoints, goalTile, curTile), st.pos, st.idx
            );
         } else {
            noteReplan("replan");
            st.route = nr.waypoints;
            st.idx = 1;
            return null;
         }
      }
   }

   public static void setTelemetry(haven.nav.NavigationTelemetrySink sink) {
      telemetry = sink == null ? haven.nav.NavigationTelemetrySink.NONE : sink;
   }

   private static void noteReplan(String reason) {
      telemetry.onEvent("replan", reason);
   }

   private static String coarseDetail(CoarseRoutePlanner.Route r) {
      return r == null ? "null coarse route" : "coarse " + r.status + (r.cause == null ? "" : ":" + r.cause);
   }

   private static boolean validRoute(List<Coord> route, Coord goalTile, Coord startTile) {
      if (route != null && !route.isEmpty()) {
         return !route.get(route.size() - 1).equals(goalTile) ? false : route.get(0).equals(startTile);
      } else {
         return false;
      }
   }

   private static String routeReason(List<Coord> route, Coord goalTile, Coord startTile) {
      if (route != null && !route.isEmpty()) {
         return !route.get(route.size() - 1).equals(goalTile)
            ? "route does not end at the goal tile " + goalTile
            : "route does not start at the current tile " + startTile;
      } else {
         return "empty route";
      }
   }

   public static final class Bounds {
      public static final RecedingHorizonNavigator.Bounds DEFAULT = new RecedingHorizonNavigator.Bounds(100, 5, 1000000);
      public final int maxLegs;
      public final int maxReplans;
      public final int coarseExpanded;

      public Bounds(int maxLegs, int maxReplans, int coarseExpanded) {
         if (maxLegs <= 0) {
            throw new IllegalArgumentException("maxLegs must be > 0: " + maxLegs);
         } else if (maxReplans <= 0) {
            throw new IllegalArgumentException("maxReplans must be > 0: " + maxReplans);
         } else if (coarseExpanded <= 0) {
            throw new IllegalArgumentException("coarseExpanded must be > 0: " + coarseExpanded);
         } else {
            this.maxLegs = maxLegs;
            this.maxReplans = maxReplans;
            this.coarseExpanded = coarseExpanded;
         }
      }
   }

   public interface CoarsePlanner {
      CoarseRoutePlanner.Route plan(Coord var1, Coord var2, int var3);

      static RecedingHorizonNavigator.CoarsePlanner of(final CoarseTileSource src) {
         Objects.requireNonNull(src, "src");
         return new RecedingHorizonNavigator.CoarsePlanner() {
            @Override
            public CoarseRoutePlanner.Route plan(Coord startTile, Coord goalTile, int maxExpanded) {
               return CoarseRoutePlanner.plan(src, startTile, goalTile, maxExpanded);
            }
         };
      }
   }

   public static final class LegResult {
      public final RecedingHorizonNavigator.LegResult.Outcome outcome;
      public final String failure;
      public final Coord2d end;

      public LegResult(RecedingHorizonNavigator.LegResult.Outcome outcome, String failure, Coord2d end) {
         this.outcome = Objects.requireNonNull(outcome, "outcome");
         this.failure = failure;
         this.end = end;
      }

      public static RecedingHorizonNavigator.LegResult success(Coord2d end) {
         return new RecedingHorizonNavigator.LegResult(RecedingHorizonNavigator.LegResult.Outcome.SUCCESS, null, end);
      }

      public static RecedingHorizonNavigator.LegResult failed(String failure, Coord2d end) {
         return new RecedingHorizonNavigator.LegResult(RecedingHorizonNavigator.LegResult.Outcome.FAILED, failure, end);
      }

      public static RecedingHorizonNavigator.LegResult cancelled(String failure, Coord2d end) {
         return new RecedingHorizonNavigator.LegResult(RecedingHorizonNavigator.LegResult.Outcome.CANCELLED, failure, end);
      }

      public static RecedingHorizonNavigator.LegResult stoppedEarly(String failure, Coord2d end) {
         return new RecedingHorizonNavigator.LegResult(RecedingHorizonNavigator.LegResult.Outcome.STOPPED_EARLY, failure, end);
      }

      public static RecedingHorizonNavigator.LegResult stuck(String failure, Coord2d end) {
         return new RecedingHorizonNavigator.LegResult(RecedingHorizonNavigator.LegResult.Outcome.STUCK, failure, end);
      }

      @Override
      public String toString() {
         return String.format("LegResult[%s%s, end=%s]", this.outcome, this.failure == null ? "" : " (" + this.failure + ")", this.end);
      }

      public static enum Outcome {
         SUCCESS,
         FAILED,
         CANCELLED,
         STOPPED_EARLY,
         STUCK;
      }
   }

   public interface LegWalker {
      RecedingHorizonNavigator.LegResult walk(Coord2d var1, RecedingHorizonNavigator.LocalPlan var2);
   }

   public static final class LocalPlan {
      public final RecedingHorizonNavigator.LocalPlan.Status status;
      public final List<Coord2d> waypoints;

      public LocalPlan(RecedingHorizonNavigator.LocalPlan.Status status, List<Coord2d> waypoints) {
         this.status = Objects.requireNonNull(status, "status");
         this.waypoints = waypoints == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(waypoints));
      }

      public static RecedingHorizonNavigator.LocalPlan from(haven.nav.NavPlan plan) {
         Objects.requireNonNull(plan, "plan");
         return new RecedingHorizonNavigator.LocalPlan(RecedingHorizonNavigator.LocalPlan.Status.valueOf(plan.status.name()), plan.smoothedRoute);
      }

      public static RecedingHorizonNavigator.LocalPlan from(Enum<?> status, List<Coord2d> waypoints) {
         Objects.requireNonNull(status, "status");
         return new RecedingHorizonNavigator.LocalPlan(RecedingHorizonNavigator.LocalPlan.Status.valueOf(status.name()), waypoints);
      }

      @Override
      public String toString() {
         return String.format("LocalPlan[%s, %d waypoints]", this.status, this.waypoints.size());
      }

      public static enum Status {
         REACHED,
         CLIPPED,
         SNAPPED,
         PARTIAL,
         FAILED;
      }
   }

   public interface LocalPlanner {
      RecedingHorizonNavigator.LocalPlan plan(Coord2d var1, Coord2d var2);
   }

   public static enum Outcome {
      REACHED_DESTINATION,
      LEG_LIMIT_EXHAUSTED,
      REPLAN_LIMIT_EXHAUSTED,
      COARSE_PLAN_FAILED,
      COARSE_PLAN_INVALID,
      TERMINAL_FAILURE,
      STUCK,
      CANCELLED;
   }

   public static final class Result {
      public final RecedingHorizonNavigator.Outcome outcome;
      public final int legs;
      public final int replans;
      public final String detail;
      public final Coord2d endPos;
      public final int routeIndex;

      private Result(RecedingHorizonNavigator.Outcome outcome, int legs, int replans, String detail, Coord2d endPos, int routeIndex) {
         this.outcome = Objects.requireNonNull(outcome, "outcome");
         this.legs = legs;
         this.replans = replans;
         this.detail = detail;
         this.endPos = endPos;
         this.routeIndex = routeIndex;
      }

      public boolean reached() {
         return this.outcome == RecedingHorizonNavigator.Outcome.REACHED_DESTINATION;
      }

      public boolean cancelled() {
         return this.outcome == RecedingHorizonNavigator.Outcome.CANCELLED;
      }

      public boolean terminalFailure() {
         return this.outcome == RecedingHorizonNavigator.Outcome.TERMINAL_FAILURE;
      }

      @Override
      public String toString() {
         return String.format(
            "NavigatorResult[%s%s, %d legs, %d replans, idx=%d, end=%s]",
            this.outcome,
            this.detail == null ? "" : " (" + this.detail + ")",
            this.legs,
            this.replans,
            this.routeIndex,
            this.endPos
         );
      }
   }

   private static final class State {
      List<Coord> route;
      Coord2d pos;
      int idx = 1;
      int legs = 0;
      int replans = 0;
      boolean skipAdvance;

      State(List<Coord> route, Coord2d pos) {
         this.route = route;
         this.pos = pos;
      }
   }

   public interface TileMap {
      Coord2d toWorld(Coord var1);

      Coord toTile(Coord2d var1);

      static RecedingHorizonNavigator.TileMap scale(final double s) {
         if (!(s > 0.0)) {
            throw new IllegalArgumentException("scale must be > 0: " + s);
         } else {
            return new RecedingHorizonNavigator.TileMap() {
               @Override
               public Coord2d toWorld(Coord tile) {
                  return tile == null ? null : Coord2d.of((double)tile.x * s, (double)tile.y * s);
               }

               @Override
               public Coord toTile(Coord2d pos) {
                  return pos == null ? null : Coord.of((int)Math.floor(pos.x / s), (int)Math.floor(pos.y / s));
               }
            };
         }
      }
   }
}
