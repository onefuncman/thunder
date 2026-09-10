package haven.pathfinding;

import auto.Bot;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.MapFile;
import haven.NamedPlaceResolver.Place;
import haven.nav.NavPlanStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class NamedPlaceNavigator {
   public static final long DEFAULT_WALK_BUDGET_MS = 20000L;
   public static final WaypointWalker.Listener NOOP = new WaypointWalker.Listener() {
      @Override
      public void event(String msg) {
      }

      @Override
      public void fail(String msg) {
      }

      @Override
      public void fail(String msg, WaypointGate.Outcome oc, String obs) {
      }

      @Override
      public void beginWait(String what, long ms, String detail) {
      }

      @Override
      public void dumpStuck() {
      }
   };
   private final NamedPlaceRouteService routes;
   private final NamedPlaceNavigator.SessionState state;
   private final RecedingHorizonNavigator.LocalPlanner local;
   private final RecedingHorizonNavigator.LegWalker walker;
   private final RecedingHorizonNavigator.Bounds navBounds;

   public static NamedPlaceNavigator.SessionState liveState(final GameUI gui) {
      return new NamedPlaceNavigator.SessionState() {
         @Override
         public NamedPlaceNavigator.Location current() {
            if (gui != null && gui.mapfile != null && gui.mapfile.view != null) {
               haven.MiniMap.Location sessloc = gui.mapfile.view.sessloc;
               if (sessloc == null) {
                  return null;
               } else {
                  Gob player = gui.map == null ? null : gui.map.player();
                  return player != null && player.rc != null
                     ? new NamedPlaceNavigator.Location(sessloc.seg.id, NamedPlaceNavigator.playerTile(player.rc, sessloc.tc), player.rc)
                     : null;
               }
            } else {
               return null;
            }
         }
      };
   }

   public static Coord playerTile(Coord2d worldPos, Coord sesslocTc) {
      if (worldPos == null) {
         throw new IllegalArgumentException("worldPos must not be null");
      } else if (sesslocTc == null) {
         throw new IllegalArgumentException("sesslocTc must not be null");
      } else {
         return worldPos.floor(MCache.tilesz).add(sesslocTc);
      }
   }

   public static Coord2d worldPos(Coord tile, Coord sesslocTc) {
      if (tile == null) {
         throw new IllegalArgumentException("tile must not be null");
      } else if (sesslocTc == null) {
         throw new IllegalArgumentException("sesslocTc must not be null");
      } else {
         return tile.sub(sesslocTc).mul(MCache.tilesz).add(MCache.tilesz.div(2.0));
      }
   }

   public static Coord translation(NamedPlaceNavigator.Location location) {
      return location.tile.sub(location.world.floor(MCache.tilesz));
   }

   public static RecedingHorizonNavigator.TileMap tileMap(final Coord sesslocTc) {
      return new RecedingHorizonNavigator.TileMap() {
         @Override
         public Coord2d toWorld(Coord tile) {
            return NamedPlaceNavigator.worldPos(tile, sesslocTc);
         }

         @Override
         public Coord toTile(Coord2d pos) {
            return NamedPlaceNavigator.playerTile(pos, sesslocTc);
         }
      };
   }

   public static RecedingHorizonNavigator.LocalPlanner localPlanner(final GameUI gui) {
      return new RecedingHorizonNavigator.LocalPlanner() {
         @Override
         public RecedingHorizonNavigator.LocalPlan plan(Coord2d from, Coord2d target) {
            PrototypePathfinder.Plan p = PrototypePathfinder.planAny(gui, Collections.singletonList(target), true);
            return RecedingHorizonNavigator.LocalPlan.from(p.status, p.waypoints);
         }
      };
   }

   public static RecedingHorizonNavigator.LegWalker liveWalker(GameUI gui, Bot bot, long walkBudgetMs, WaypointWalker.Listener listener) {
      return liveWalker(gui, bot, walkBudgetMs, WaypointWalker.Params.DEFAULT, listener);
   }

   public static RecedingHorizonNavigator.LegWalker liveWalker(
      final GameUI gui, final Bot bot, final long walkBudgetMs, final WaypointWalker.Params params, final WaypointWalker.Listener listener
   ) {
      Objects.requireNonNull(bot, "bot");
      return new RecedingHorizonNavigator.LegWalker() {
         @Override
         public RecedingHorizonNavigator.LegResult walk(Coord2d from, RecedingHorizonNavigator.LocalPlan plan) {
            Coord2d before = NamedPlaceNavigator.observePos(gui);

            try {
               WaypointWalker.Result r;
               List<GatePassage.Crossing> crossings = GatePassage.observedCrossings(gui, plan.waypoints);
               if (crossings.isEmpty()) {
                  r = WaypointWalker.execute(
                     WaypointWalker.liveEnv(gui),
                     bot,
                     plan.waypoints,
                     0,
                     walkBudgetMs,
                     params == null ? WaypointWalker.Params.DEFAULT : params,
                     listener == null ? NamedPlaceNavigator.NOOP : listener,
                     NavPlanStatus.valueOf(plan.status.name())
                  );
               } else {
                  // The leg crosses a pass-through gate: open/pass/close around it.
                  r = GatePassage.resultFor(
                     GatePassage.walk(gui, bot, plan.waypoints, walkBudgetMs, listener == null ? NamedPlaceNavigator.NOOP : listener)
                  );
               }
               return NamedPlaceNavigator.walkerResult(r, NamedPlaceNavigator.observePos(gui), before != null ? before : from);
            } catch (InterruptedException var5) {
               return NamedPlaceNavigator.walkerCancelled(var5, NamedPlaceNavigator.observePos(gui), before != null ? before : from);
            }
         }
      };
   }

   private static Coord2d observePos(GameUI gui) {
      Gob player = gui != null && gui.map != null ? gui.map.player() : null;
      return player != null && player.rc != null ? player.rc : null;
   }

   static RecedingHorizonNavigator.LegResult walkerResult(WaypointWalker.Result r, Coord2d end, Coord2d fallback) {
      Coord2d at = end != null ? end : fallback;
      switch (r) {
         case ARRIVED:
         case READY_TO_INTERACT:
            return RecedingHorizonNavigator.LegResult.success(at);
         case SHORT_STOP:
            return RecedingHorizonNavigator.LegResult.stoppedEarly("walker " + r, at);
         case STUCK:
            return RecedingHorizonNavigator.LegResult.stuck("walker " + r, at);
         case REJECTED:
         case TIMEOUT:
            return RecedingHorizonNavigator.LegResult.failed("walker " + r, at);
         default:
            throw new AssertionError(r);
      }
   }

   static RecedingHorizonNavigator.LegResult walkerCancelled(InterruptedException e, Coord2d end, Coord2d fallback) {
      Coord2d at = end != null ? end : fallback;
      String detail = e != null && e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage() : "bot cancelled";
      return RecedingHorizonNavigator.LegResult.cancelled(detail, at);
   }

   static CoarseRoutePlanner.Route toCoarseRoute(NamedPlaceRouteService.Result r) {
      switch (r.kind) {
         case REACHED:
            return CoarseRoutePlanner.Route.reached(r.waypoints, r.expanded);
         case NO_KNOWN_ROUTE:
            return CoarseRoutePlanner.Route.failed(CoarseRoutePlanner.Status.NO_KNOWN_ROUTE, r.cause);
         case INVALID_START:
            return CoarseRoutePlanner.Route.failed(CoarseRoutePlanner.Status.INVALID_START, null);
         case INVALID_GOAL:
            return CoarseRoutePlanner.Route.failed(CoarseRoutePlanner.Status.INVALID_GOAL, null);
         case EXHAUSTED:
            return CoarseRoutePlanner.Route.failed(CoarseRoutePlanner.Status.EXHAUSTED, null);
         case CROSS_SEGMENT:
            return CoarseRoutePlanner.Route.failed(CoarseRoutePlanner.Status.CROSS_SEGMENT, null);
         case DEST_MISSING:
         case DEST_AMBIGUOUS:
            return CoarseRoutePlanner.Route.failed(CoarseRoutePlanner.Status.INVALID_GOAL, null);
         default:
            throw new AssertionError(r.kind);
      }
   }

   public NamedPlaceNavigator(
      NamedPlaceRouteService routes,
      NamedPlaceNavigator.SessionState state,
      RecedingHorizonNavigator.LocalPlanner local,
      RecedingHorizonNavigator.LegWalker walker,
      RecedingHorizonNavigator.Bounds navBounds
   ) {
      this.routes = Objects.requireNonNull(routes, "routes");
      this.state = Objects.requireNonNull(state, "state");
      this.local = Objects.requireNonNull(local, "local");
      this.walker = Objects.requireNonNull(walker, "walker");
      this.navBounds = Objects.requireNonNull(navBounds, "navBounds");
   }

   public static NamedPlaceNavigator live(GameUI gui, MapFile file, Bot bot) {
      return live(gui, file, bot, RecedingHorizonNavigator.Bounds.DEFAULT, 20000L, NOOP);
   }

   public static NamedPlaceNavigator live(
      GameUI gui, MapFile file, Bot bot, RecedingHorizonNavigator.Bounds navBounds, long walkBudgetMs, WaypointWalker.Listener listener
   ) {
      return live(gui, file, bot, navBounds, walkBudgetMs, WaypointWalker.Params.DEFAULT, listener);
   }

   public static NamedPlaceNavigator live(
      GameUI gui,
      MapFile file,
      Bot bot,
      RecedingHorizonNavigator.Bounds navBounds,
      long walkBudgetMs,
      WaypointWalker.Params params,
      WaypointWalker.Listener listener
   ) {
      Objects.requireNonNull(file, "file");
      return new NamedPlaceNavigator(
         new NamedPlaceRouteService(file), liveState(gui), localPlanner(gui), liveWalker(gui, bot, walkBudgetMs, params, listener), navBounds
      );
   }

   public NamedPlaceNavigator.Run navigate(final String destName, final Area bounds) {
      long t0 = System.currentTimeMillis();
      NamedPlaceNavigator.Location start = this.state.current();
      if (start == null) {
         return new NamedPlaceNavigator.Run(
            NamedPlaceNavigator.RunStatus.UNAVAILABLE, destName, bounds, null, null, 0L, null, 0, 0, null, 0, 0, 0, null, null, null, elapsed(t0)
         );
      } else {
         NamedPlaceRouteService.Result res = this.routes.route(start.seg, start.tile, destName, bounds, this.navBounds.coarseExpanded);
         if (!res.reached()) {
            return new NamedPlaceNavigator.Run(
               NamedPlaceNavigator.RunStatus.ROUTE_REJECTED,
               destName,
               bounds,
               start,
               res.kind,
               res.markerseq,
               res.place,
               0,
               res.expanded,
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
            final List<NamedPlaceNavigator.ReplanFact> replanFacts = new ArrayList<>();
            RecedingHorizonNavigator.TileMap tm = tileMap(translation(start));
            RecedingHorizonNavigator.CoarsePlanner coarse = new RecedingHorizonNavigator.CoarsePlanner() {
               @Override
               public CoarseRoutePlanner.Route plan(Coord startTile, Coord goalTile, int maxExpanded) {
                  NamedPlaceNavigator.Location cur = NamedPlaceNavigator.this.state.current();
                  if (cur == null) {
                     return CoarseRoutePlanner.Route.failed(CoarseRoutePlanner.Status.INVALID_START, null);
                  } else {
                     NamedPlaceRouteService.Result r = NamedPlaceNavigator.this.routes.route(cur.seg, startTile, destName, bounds, maxExpanded);
                     replanFacts.add(new NamedPlaceNavigator.ReplanFact(replanFacts.size() + 1, r.kind, r.markerseq, r.expanded));
                     return NamedPlaceNavigator.toCoarseRoute(r);
                  }
               }
            };
            RecedingHorizonNavigator nav = new RecedingHorizonNavigator(this.local, this.walker, coarse, tm, this.navBounds);
            RecedingHorizonNavigator.Result nr = nav.run(res.place.tc, res.waypoints, start.world);
            return new NamedPlaceNavigator.Run(
               NamedPlaceNavigator.RunStatus.NAVIGATED,
               destName,
               bounds,
               start,
               res.kind,
               res.markerseq,
               res.place,
               res.waypoints.size(),
               res.expanded,
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

   private static long elapsed(long t0) {
      return System.currentTimeMillis() - t0;
   }

   public static final class Location {
      public final long seg;
      public final Coord tile;
      public final Coord2d world;

      public Location(long seg, Coord tile, Coord2d world) {
         this.seg = seg;
         this.tile = Objects.requireNonNull(tile, "tile");
         this.world = Objects.requireNonNull(world, "world");
      }

      @Override
      public String toString() {
         return String.format("seg=%x tile=%s world=(%.1f,%.1f)", this.seg, this.tile, this.world.x, this.world.y);
      }
   }

   public static final class ReplanFact {
      public final int index;
      public final NamedPlaceRouteService.Kind kind;
      public final long markerseq;
      public final int expanded;

      public ReplanFact(int index, NamedPlaceRouteService.Kind kind, long markerseq, int expanded) {
         this.index = index;
         this.kind = Objects.requireNonNull(kind, "kind");
         this.markerseq = markerseq;
         this.expanded = expanded;
      }

      @Override
      public String toString() {
         return String.format("#%d %s seq=%d exp=%d", this.index, this.kind, this.markerseq, this.expanded);
      }
   }

   public static final class Run {
      public final NamedPlaceNavigator.RunStatus status;
      public final String destName;
      public final Area bounds;
      public final NamedPlaceNavigator.Location start;
      public final NamedPlaceRouteService.Kind routeKind;
      public final long markerseq;
      public final Place place;
      public final int waypointCount;
      public final int expanded;
      public final RecedingHorizonNavigator.Outcome navOutcome;
      public final int legs;
      public final int replans;
      public final int routeIndex;
      public final String detail;
      public final Coord2d endPos;
      public final List<NamedPlaceNavigator.ReplanFact> replanFacts;
      public final long elapsedMs;

      Run(
         NamedPlaceNavigator.RunStatus status,
         String destName,
         Area bounds,
         NamedPlaceNavigator.Location start,
         NamedPlaceRouteService.Kind routeKind,
         long markerseq,
         Place place,
         int waypointCount,
         int expanded,
         RecedingHorizonNavigator.Outcome navOutcome,
         int legs,
         int replans,
         int routeIndex,
         String detail,
         Coord2d endPos,
         List<NamedPlaceNavigator.ReplanFact> replanFacts,
         long elapsedMs
      ) {
         this.status = Objects.requireNonNull(status, "status");
         this.destName = destName;
         this.bounds = Objects.requireNonNull(bounds, "bounds");
         this.start = start;
         this.routeKind = routeKind;
         this.markerseq = markerseq;
         this.place = place;
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
         return this.status == NamedPlaceNavigator.RunStatus.UNAVAILABLE;
      }

      public boolean routeRejected() {
         return this.status == NamedPlaceNavigator.RunStatus.ROUTE_REJECTED;
      }

      public boolean navigated() {
         return this.status == NamedPlaceNavigator.RunStatus.NAVIGATED;
      }

      public boolean reached() {
         return this.status == NamedPlaceNavigator.RunStatus.NAVIGATED && this.navOutcome == RecedingHorizonNavigator.Outcome.REACHED_DESTINATION;
      }

      public boolean cancelled() {
         return this.status == NamedPlaceNavigator.RunStatus.NAVIGATED && this.navOutcome == RecedingHorizonNavigator.Outcome.CANCELLED;
      }

      @Override
      public String toString() {
         StringBuilder sb = new StringBuilder();
         sb.append("Run[").append(this.status);
         if (this.destName != null) {
            sb.append(" dest=\"").append(this.destName).append('"');
         }

         if (this.start != null) {
            sb.append(' ').append(this.start);
         }

         if (this.routeKind != null) {
            sb.append(" route=").append(this.routeKind);
            if (this.routeKind == NamedPlaceRouteService.Kind.REACHED) {
               sb.append('(').append(this.waypointCount).append("wps,").append(this.expanded).append("exp)");
            }

            if (this.place != null) {
               sb.append(" goal=").append(this.place.tc).append('@').append(String.format("%x", this.place.seg));
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

   public interface SessionState {
      NamedPlaceNavigator.Location current();
   }
}
