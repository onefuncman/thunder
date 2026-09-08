package haven.pathfinding;

import auto.Bot;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.MapFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CoarseTileNavigator {
   public static final long DEFAULT_WALK_BUDGET_MS = 20000L;
   private final CoarseTileNavigator.CoarsePlanner coarse;
   private final NamedPlaceNavigator.SessionState state;
   private final RecedingHorizonNavigator.LocalPlanner local;
   private final RecedingHorizonNavigator.LegWalker walker;
   private final RecedingHorizonNavigator.Bounds navBounds;

   public CoarseTileNavigator(
      CoarseTileNavigator.CoarsePlanner coarse,
      NamedPlaceNavigator.SessionState state,
      RecedingHorizonNavigator.LocalPlanner local,
      RecedingHorizonNavigator.LegWalker walker,
      RecedingHorizonNavigator.Bounds navBounds
   ) {
      this.coarse = Objects.requireNonNull(coarse, "coarse");
      this.state = Objects.requireNonNull(state, "state");
      this.local = Objects.requireNonNull(local, "local");
      this.walker = Objects.requireNonNull(walker, "walker");
      this.navBounds = Objects.requireNonNull(navBounds, "navBounds");
   }

   public static CoarseTileNavigator live(GameUI gui, MapFile file, Bot bot) {
      return live(gui, file, bot, RecedingHorizonNavigator.Bounds.DEFAULT, 20000L, NamedPlaceNavigator.NOOP);
   }

   public static CoarseTileNavigator live(
      GameUI gui, MapFile file, Bot bot, RecedingHorizonNavigator.Bounds navBounds, long walkBudgetMs, WaypointWalker.Listener listener
   ) {
      return live(gui, file, bot, navBounds, walkBudgetMs, WaypointWalker.Params.DEFAULT, listener);
   }

   public static CoarseTileNavigator live(
      GameUI gui,
      MapFile file,
      Bot bot,
      RecedingHorizonNavigator.Bounds navBounds,
      long walkBudgetMs,
      WaypointWalker.Params params,
      WaypointWalker.Listener listener
   ) {
      Objects.requireNonNull(file, "file");
      return new CoarseTileNavigator(
         CoarseTileNavigator.CoarsePlanner.overMapFile(file),
         NamedPlaceNavigator.liveState(gui),
         NamedPlaceNavigator.localPlanner(gui),
         NamedPlaceNavigator.liveWalker(gui, bot, walkBudgetMs, params, listener),
         navBounds
      );
   }

   public static CoarseRoutePlanner.Route planRoute(MapFile file, long seg, Coord startTile, Coord goalTile, Area bounds, int maxExpanded) {
      Objects.requireNonNull(file, "file");
      if (startTile == null) {
         throw new IllegalArgumentException("startTile must not be null");
      } else if (goalTile == null) {
         throw new IllegalArgumentException("goalTile must not be null");
      } else {
         Objects.requireNonNull(bounds, "bounds");
         MapFileTileSource src = MapFileTileSource.of(file, seg, bounds);
         CoarseRoutePlanner.Route r = CoarseRoutePlanner.plan(src, startTile.sub(bounds.ul), goalTile.sub(bounds.ul), maxExpanded);
         return !r.reached() ? r : CoarseRoutePlanner.Route.reached(toAbsolute(r.waypoints, bounds.ul), r.expanded);
      }
   }

   public CoarseTileNavigator.Run navigate(Coord goalTile, final Area bounds) {
      Objects.requireNonNull(goalTile, "goalTile");
      Objects.requireNonNull(bounds, "bounds");
      long t0 = System.currentTimeMillis();
      NamedPlaceNavigator.Location start = this.state.current();
      if (start == null) {
         return new CoarseTileNavigator.Run(
            CoarseTileNavigator.RunStatus.UNAVAILABLE, goalTile, bounds, null, null, null, 0, 0, null, 0, 0, 0, null, null, null, elapsed(t0)
         );
      } else {
         CoarseRoutePlanner.Route initial = this.coarse.plan(start.seg, start.tile, goalTile, bounds, this.navBounds.coarseExpanded);
         if (!initial.reached()) {
            return new CoarseTileNavigator.Run(
               CoarseTileNavigator.RunStatus.ROUTE_REJECTED,
               goalTile,
               bounds,
               start,
               initial.status,
               initial.cause,
               0,
               initial.expanded,
               null,
               0,
               0,
               0,
               null,
               start.world,
               null,
               elapsed(t0)
            );
         } else {
            final long runSeg = start.seg;
            final List<CoarseTileNavigator.ReplanFact> replanFacts = new ArrayList<>();
            RecedingHorizonNavigator.TileMap tm = NamedPlaceNavigator.tileMap(NamedPlaceNavigator.translation(start));
            RecedingHorizonNavigator.CoarsePlanner replan = new RecedingHorizonNavigator.CoarsePlanner() {
               @Override
               public CoarseRoutePlanner.Route plan(Coord startTile, Coord goal, int maxExpanded) {
                  NamedPlaceNavigator.Location cur = CoarseTileNavigator.this.state.current();
                  if (cur == null) {
                     CoarseRoutePlanner.Route r = CoarseRoutePlanner.Route.failed(CoarseRoutePlanner.Status.INVALID_START, null);
                     replanFacts.add(new CoarseTileNavigator.ReplanFact(replanFacts.size() + 1, r.status, r.cause, r.expanded));
                     return r;
                  } else if (cur.seg != runSeg) {
                     CoarseRoutePlanner.Route r = CoarseRoutePlanner.Route.failed(CoarseRoutePlanner.Status.CROSS_SEGMENT, null);
                     replanFacts.add(new CoarseTileNavigator.ReplanFact(replanFacts.size() + 1, r.status, r.cause, r.expanded));
                     return r;
                  } else {
                     CoarseRoutePlanner.Route r = CoarseTileNavigator.this.coarse.plan(cur.seg, startTile, goal, bounds, maxExpanded);
                     replanFacts.add(new CoarseTileNavigator.ReplanFact(replanFacts.size() + 1, r.status, r.cause, r.expanded));
                     return r;
                  }
               }
            };
            RecedingHorizonNavigator nav = new RecedingHorizonNavigator(this.local, this.walker, replan, tm, this.navBounds);
            RecedingHorizonNavigator.Result nr = nav.run(goalTile, initial.waypoints, start.world);
            return new CoarseTileNavigator.Run(
               CoarseTileNavigator.RunStatus.NAVIGATED,
               goalTile,
               bounds,
               start,
               initial.status,
               initial.cause,
               initial.waypoints.size(),
               initial.expanded,
               nr.outcome,
               nr.legs,
               nr.replans,
               nr.routeIndex,
               nr.detail,
               nr.endPos,
               replanFacts,
               elapsed(t0)
            );
         }
      }
   }

   private static List<Coord> toAbsolute(List<Coord> local, Coord ul) {
      List<Coord> out = new ArrayList<>(local.size());

      for (Coord c : local) {
         out.add(c.add(ul));
      }

      return Collections.unmodifiableList(out);
   }

   private static long elapsed(long t0) {
      return System.currentTimeMillis() - t0;
   }

   public interface CoarsePlanner {
      CoarseRoutePlanner.Route plan(long var1, Coord var3, Coord var4, Area var5, int var6);

      static CoarseTileNavigator.CoarsePlanner overMapFile(final MapFile file) {
         Objects.requireNonNull(file, "file");
         return new CoarseTileNavigator.CoarsePlanner() {
            @Override
            public CoarseRoutePlanner.Route plan(long seg, Coord startTile, Coord goalTile, Area bounds, int maxExpanded) {
               return CoarseTileNavigator.planRoute(file, seg, startTile, goalTile, bounds, maxExpanded);
            }
         };
      }
   }

   public static final class ReplanFact {
      public final int index;
      public final CoarseRoutePlanner.Status status;
      public final CoarseRoutePlanner.Cause cause;
      public final int expanded;

      public ReplanFact(int index, CoarseRoutePlanner.Status status, CoarseRoutePlanner.Cause cause, int expanded) {
         this.index = index;
         this.status = Objects.requireNonNull(status, "status");
         this.cause = cause;
         this.expanded = expanded;
      }

      @Override
      public String toString() {
         return String.format("#%d %s%s exp=%d", this.index, this.status, this.cause == null ? "" : ":" + this.cause, this.expanded);
      }
   }

   public static final class Run {
      public final CoarseTileNavigator.RunStatus status;
      public final Coord goalTile;
      public final Area bounds;
      public final NamedPlaceNavigator.Location start;
      public final CoarseRoutePlanner.Status routeStatus;
      public final CoarseRoutePlanner.Cause routeCause;
      public final int waypointCount;
      public final int expanded;
      public final RecedingHorizonNavigator.Outcome navOutcome;
      public final int legs;
      public final int replans;
      public final int routeIndex;
      public final String detail;
      public final Coord2d endPos;
      public final List<CoarseTileNavigator.ReplanFact> replanFacts;
      public final long elapsedMs;

      Run(
         CoarseTileNavigator.RunStatus status,
         Coord goalTile,
         Area bounds,
         NamedPlaceNavigator.Location start,
         CoarseRoutePlanner.Status routeStatus,
         CoarseRoutePlanner.Cause routeCause,
         int waypointCount,
         int expanded,
         RecedingHorizonNavigator.Outcome navOutcome,
         int legs,
         int replans,
         int routeIndex,
         String detail,
         Coord2d endPos,
         List<CoarseTileNavigator.ReplanFact> replanFacts,
         long elapsedMs
      ) {
         this.status = Objects.requireNonNull(status, "status");
         this.goalTile = Objects.requireNonNull(goalTile, "goalTile");
         this.bounds = Objects.requireNonNull(bounds, "bounds");
         this.start = start;
         this.routeStatus = routeStatus;
         this.routeCause = routeCause;
         this.waypointCount = waypointCount;
         this.expanded = expanded;
         this.navOutcome = navOutcome;
         this.legs = legs;
         this.replans = replans;
         this.routeIndex = routeIndex;
         this.detail = detail;
         this.endPos = endPos;
         this.replanFacts = replanFacts == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(replanFacts));
         this.elapsedMs = elapsedMs;
      }

      public boolean unavailable() {
         return this.status == CoarseTileNavigator.RunStatus.UNAVAILABLE;
      }

      public boolean routeRejected() {
         return this.status == CoarseTileNavigator.RunStatus.ROUTE_REJECTED;
      }

      public boolean navigated() {
         return this.status == CoarseTileNavigator.RunStatus.NAVIGATED;
      }

      public boolean reached() {
         return this.status == CoarseTileNavigator.RunStatus.NAVIGATED && this.navOutcome == RecedingHorizonNavigator.Outcome.REACHED_DESTINATION;
      }

      public boolean cancelled() {
         return this.status == CoarseTileNavigator.RunStatus.NAVIGATED && this.navOutcome == RecedingHorizonNavigator.Outcome.CANCELLED;
      }

      @Override
      public String toString() {
         StringBuilder sb = new StringBuilder();
         sb.append("Run[").append(this.status).append(" goal=").append(this.goalTile);
         if (this.start != null) {
            sb.append(' ').append(this.start);
         }

         if (this.routeStatus != null) {
            sb.append(" route=").append(this.routeStatus);
            if (this.routeStatus == CoarseRoutePlanner.Status.REACHED) {
               sb.append('(').append(this.waypointCount).append("wps,").append(this.expanded).append("exp)");
            } else if (this.routeStatus == CoarseRoutePlanner.Status.NO_KNOWN_ROUTE) {
               sb.append('(').append(this.expanded).append("exp)");
            }
         }

         if (this.navOutcome != null) {
            sb.append(" nav=")
               .append(this.navOutcome)
               .append(" legs=")
               .append(this.legs)
               .append(" replans=")
               .append(this.replans)
               .append(" idx=")
               .append(this.routeIndex);
            if (this.detail != null) {
               sb.append(" (").append(this.detail).append(')');
            }

            if (this.endPos != null) {
               sb.append(String.format(" end=(%.1f,%.1f)", this.endPos.x, this.endPos.y));
            }
         }

         if (!this.replanFacts.isEmpty()) {
            sb.append(" facts=").append(this.replanFacts);
         }

         sb.append(' ').append(this.elapsedMs).append("ms]");
         return sb.toString();
      }
   }

   public static enum RunStatus {
      UNAVAILABLE,
      ROUTE_REJECTED,
      NAVIGATED;
   }
}
