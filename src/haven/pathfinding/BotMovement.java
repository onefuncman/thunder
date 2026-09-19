package haven.pathfinding;

import auto.Bot;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.nav.InteractionSpec;
import haven.nav.NavPlanStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * The single movement boundary for automation bots.
 *
 * <p>Bots choose tasks and targets. This class plans and executes only local
 * movement, returning a typed result instead of performing an interaction.</p>
 */
public final class BotMovement {
   static final int MAX_REPLANS = 2;
   private static final long DEFAULT_BUDGET_MS = 60000L;
   private static final double APPROACH_EPS = 2.475;
   private static final String APPROACH_KIND = "approach";
   private static final double APPROACH_MIN_DIST = 0.5;
   private static final double APPROACH_MAX_DIST = 16.5;
   private static final int APPROACH_CLEARANCE = 0;

   private BotMovement() {
   }

   public enum Mode {
      LAND,
      BOAT_ROUTE,
      BOAT_LOCAL,
      BOAT_APPROACH
   }

   public enum Status {
      ARRIVED,
      READY_TO_INTERACT,
      NO_ROUTE,
      BLOCKED,
      TARGET_GONE,
      TARGET_MOVED,
      GEOMETRY_UNAVAILABLE,
      TIMEOUT
   }

   public static final class Avoidance {
      public static final Avoidance NONE = new Avoidance(Collections.<Coord2d>emptyList(), 0.0);

      public final List<Coord2d> centers;
      public final double radius;
      private final Supplier<List<Coord2d>> source;

      public Avoidance(List<Coord2d> centers, double radius) {
         this.centers = centers == null
            ? Collections.<Coord2d>emptyList()
            : Collections.unmodifiableList(new ArrayList<Coord2d>(centers));
         this.radius = Math.max(0.0, radius);
         this.source = null;
      }

      private Avoidance(Supplier<List<Coord2d>> source, double radius) {
         this.centers = Collections.emptyList();
         this.radius = Math.max(0.0, radius);
         this.source = source;
      }

      /** Re-reads moving hazards before every local replan. */
      public static Avoidance dynamic(Supplier<List<Coord2d>> source, double radius) {
         return new Avoidance(source, radius);
      }

      List<Coord2d> currentCenters() {
         if (source == null) return centers;
         List<Coord2d> current = source.get();
         return current == null ? Collections.<Coord2d>emptyList() : new ArrayList<Coord2d>(current);
      }
   }

   public static final class Result {
      public final Status status;
      public final Coord2d end;
      public final Coord2d selectedGoal;
      public final int replans;
      public final String detail;

      Result(Status status, Coord2d end, Coord2d selectedGoal, int replans, String detail) {
         this.status = status;
         this.end = end;
         this.selectedGoal = selectedGoal;
         this.replans = replans;
         this.detail = detail == null ? "" : detail;
      }

      public boolean arrived() {
         return status == Status.ARRIVED;
      }

      public boolean readyToInteract() {
         return status == Status.READY_TO_INTERACT;
      }

      public boolean ok() {
         return arrived() || readyToInteract();
      }
   }

   public static Result moveTo(GameUI gui, Bot bot, Coord2d point, Mode mode) throws InterruptedException {
      return moveTo(gui, bot, point, mode, DEFAULT_BUDGET_MS);
   }

   public static Result moveTo(GameUI gui, Bot bot, Coord2d point, Mode mode, long budgetMs) throws InterruptedException {
      if (point == null) {
         return result(Status.NO_ROUTE, null, null, 0, "destination is null");
      }
      return moveToAny(gui, bot, Collections.singletonList(point), Avoidance.NONE, mode, budgetMs);
   }

   public static Result moveToAny(GameUI gui, Bot bot, List<Coord2d> candidates,
                                  Avoidance avoidance, Mode mode) throws InterruptedException {
      return moveToAny(gui, bot, candidates, avoidance, mode, DEFAULT_BUDGET_MS);
   }

   public static Result moveToAny(GameUI gui, Bot bot, List<Coord2d> candidates,
                                  Avoidance avoidance, Mode mode, long budgetMs) throws InterruptedException {
      if (gui == null || gui.map == null || bot == null || candidates == null || candidates.isEmpty()) {
         return result(Status.NO_ROUTE, null, null, 0, "movement input unavailable");
      }
      if (mode == Mode.BOAT_ROUTE) {
         return result(Status.NO_ROUTE, currentPosition(gui), null, 0, "BOAT_ROUTE requires followKnownRoute");
      }
      Avoidance avoid = avoidance == null ? Avoidance.NONE : avoidance;
      long deadline = System.currentTimeMillis() + Math.max(0L, budgetMs);
      Coord2d selected = null;
      Result last = null;

      for (int replan = 0; replan <= MAX_REPLANS; replan++) {
         bot.checkCancelled();
         Coord2d at = currentPosition(gui);
         if (at == null) {
            return result(Status.BLOCKED, null, selected, replan, "player unavailable");
         }
         Coord2d reached = reachedCandidate(at, candidates, params(mode).eps);
         WaypointGate.Observation observation = ConfirmedRouteRunner.observeLive(gui);
         if (reached != null && observation != null && !observation.moving) {
            return result(Status.ARRIVED, at, reached, replan, "");
         }
         MovementScene.Plan plan = plan(gui, candidates, avoid.currentCenters(), avoid.radius, mode);
         if (plan == null || plan.status == MovementScene.Plan.Status.FAILED
            || plan.waypoints == null || plan.waypoints.size() < 2) {
            last = result(Status.NO_ROUTE, at, selected, replan, plan == null ? "planner unavailable" : plan.status.name());
            continue;
         }
         selected = plan.waypoints.get(plan.waypoints.size() - 1);
         long remaining = deadline - System.currentTimeMillis();
         if (remaining <= 0L) {
            return result(Status.TIMEOUT, at, selected, replan, "movement budget exhausted");
         }
         // A clipped local leg is a staging step, not success. The runner
         // confirms that endpoint, then this method observes and replans.
         ConfirmedRouteRunner.Result walked = ConfirmedRouteRunner.execute(
            gui, bot, plan.waypoints, selected, remaining, params(mode)
         );
         at = currentPosition(gui);
         reached = reachedCandidate(at, candidates, params(mode).eps);
         observation = ConfirmedRouteRunner.observeLive(gui);
         if (walked.arrived() && reached != null && observation != null && !observation.moving) {
            return result(Status.ARRIVED, at, reached, replan, "");
         }
         if (walked.status == ConfirmedRouteRunner.Status.TIMEOUT) {
            last = result(Status.TIMEOUT, at, selected, replan, walked.detail);
         } else if (walked.status == ConfirmedRouteRunner.Status.BLOCKED) {
            last = result(Status.BLOCKED, at, selected, replan, walked.detail);
         } else {
            last = result(Status.BLOCKED, at, selected, replan, walked.status.name());
         }
      }
      return last == null ? result(Status.NO_ROUTE, currentPosition(gui), selected, MAX_REPLANS, "no route") : last;
   }

   public static Result approach(GameUI gui, Bot bot, Gob gob, Mode mode) throws InterruptedException {
      return approach(gui, bot, gob, mode, Avoidance.NONE);
   }

   public static Result approach(GameUI gui, Bot bot, Gob gob, Mode mode,
                                 Avoidance avoidance) throws InterruptedException {
      if (gui == null || gui.map == null || bot == null || gob == null) {
         return result(Status.TARGET_GONE, currentPosition(gui), null, 0, "target unavailable");
      }
      long targetId = gob.id;
      Avoidance avoid = avoidance == null ? Avoidance.NONE : avoidance;
      Result last = null;
      for (int replan = 0; replan <= MAX_REPLANS; replan++) {
         bot.checkCancelled();
         MovementScene.Scene scene = observe(gui);
         MovementScene.GobGeom geom = find(scene, targetId);
         if (geom == null) {
            geom = MovementScene.gobGeom(gui, targetId);
         }
         if (geom == null || geom.rc == null) {
            return result(Status.TARGET_GONE, scene == null ? null : scene.player, null, replan, "target disappeared");
         }
         InteractionSpec spec = interactionSpec(geom, scene == null ? null : scene.player);
         if (spec == null || CollisionGeom.UNAVAILABLE.equals(spec.geometrySource)) {
            return result(Status.GEOMETRY_UNAVAILABLE, scene == null ? null : scene.player, null, replan,
               "target geometry unavailable");
         }
         if (scene == null || scene.player == null || scene.occupancy == null) {
            return result(Status.GEOMETRY_UNAVAILABLE, null, null, replan, "local scene unavailable");
         }
         List<Coord2d> hazards = avoid.currentCenters();
         OccupancyGrid safeOccupancy = withAvoidance(scene.occupancy, hazards, avoid.radius);

         if (StagingPlanner.required(scene.player, safeOccupancy, spec)) {
            InteractionTarget target = InteractionTarget.of(geom, APPROACH_KIND, null, scene.terrain);
            InteractionStaging.Result staged = InteractionStaging.stage(
               gui.ui, gui, target, new InteractionStaging.SpecMaker() {
                  @Override
                  public InteractionSpec spec(MovementScene.GobGeom live, Coord2d near) {
                     return interactionSpec(live, near);
                  }
               }, null, bot, avoid
            );
            if (!staged.staged()) {
               return result(mapStaging(staged.outcome), currentPosition(gui), null, replan, staged.detail);
            }
            scene = staged.scene;
            geom = staged.target;
            targetId = geom.id;
            spec = interactionSpec(geom, scene.player);
            safeOccupancy = withAvoidance(scene.occupancy, avoid.currentCenters(), avoid.radius);
         }

         ApproachGoals.Result planned = ApproachGoals.plan(scene.player, spec, safeOccupancy,
            new InteractionGoals.Geometry(scene.solids, scene.playerBody));
         PathfinderLog.recordOccupancy(scene.occupancy);
         GeometryDump.dump(gui, scene, geom, planned.pose);
         if (!planned.ok()) {
            return result(mapApproach(planned.status), scene.player, null, replan, planned.status.name());
         }
         final Coord2d pose = planned.pose.selected.world;
         List<Coord2d> route = planned.pose.plan.smoothedRoute;
         if (route == null || route.size() < 2) {
            route = new ArrayList<Coord2d>();
            route.add(scene.player);
            route.add(pose);
         }
         ConfirmedRouteRunner.Result walked = ConfirmedRouteRunner.execute(
            gui, bot, route, pose, DEFAULT_BUDGET_MS, params(mode)
         );
         if (!walked.arrived()) {
            Status failed = walked.status == ConfirmedRouteRunner.Status.TIMEOUT ? Status.TIMEOUT : Status.BLOCKED;
            last = result(failed, walked.end, pose, replan, walked.detail);
            continue;
         }

         MovementScene.Scene freshScene = observe(gui);
         MovementScene.GobGeom freshGeom = find(freshScene, targetId);
         if (freshGeom == null) {
            return result(Status.TARGET_GONE, freshScene == null ? null : freshScene.player, pose, replan,
               "target disappeared after movement");
         }
         InteractionSpec freshSpec = interactionSpec(freshGeom, freshScene.player);
         if (freshSpec == null || CollisionGeom.UNAVAILABLE.equals(freshSpec.geometrySource)) {
            return result(Status.GEOMETRY_UNAVAILABLE, freshScene.player, pose, replan,
               "target geometry unavailable after movement");
         }
         boolean valid = InteractionVerifier.arrivedConfirmed(true, freshScene.player, pose, APPROACH_EPS)
            && InteractionVerifier.poseStillValid(freshScene.player, pose, APPROACH_EPS, spec,
               freshSpec.origin, freshSpec.half, true,
               withAvoidance(freshScene.occupancy, avoid.currentCenters(), avoid.radius),
               freshScene.solids, freshScene.playerBody);
         if (valid) {
            return result(Status.READY_TO_INTERACT, freshScene.player, pose, replan, "");
         }
         last = result(Status.TARGET_MOVED, freshScene.player, pose, replan, "approach pose changed");
      }
      return last == null ? result(Status.BLOCKED, currentPosition(gui), null, MAX_REPLANS, "approach failed") : last;
   }

   public static Result followKnownRoute(GameUI gui, Bot bot, List<Coord2d> route, Mode mode) throws InterruptedException {
      return followKnownRoute(gui, bot, route, mode, DEFAULT_BUDGET_MS);
   }

   public static Result followKnownRoute(GameUI gui, Bot bot, List<Coord2d> route,
                                         Mode mode, long budgetMs) throws InterruptedException {
      if (route == null || route.isEmpty()) {
         return result(Status.NO_ROUTE, currentPosition(gui), null, 0, "route is empty");
      }
      Coord2d goal = route.get(route.size() - 1);
      ConfirmedRouteRunner.Result walked = ConfirmedRouteRunner.execute(
         gui, bot, route, goal, budgetMs, params(mode)
      );
      Status status = walked.arrived() ? Status.ARRIVED
         : walked.status == ConfirmedRouteRunner.Status.TIMEOUT ? Status.TIMEOUT : Status.BLOCKED;
      return result(status, walked.end, goal, 0, walked.detail);
   }

   private static MovementScene.Plan plan(GameUI gui, List<Coord2d> candidates,
                                                List<Coord2d> hazards, double hazardRadius, Mode mode) {
      // BotMovement promises arrival at an original candidate. Snapping a
      // blocked candidate to the player's current cell can manufacture a
      // zero-length REACHED plan and hide another valid candidate.
      switch (mode == null ? Mode.LAND : mode) {
         case BOAT_LOCAL:
            return MovementScene.planAnyWaterAvoiding(gui, candidates, false, hazards, hazardRadius);
         case BOAT_APPROACH:
            return MovementScene.planAnyWaterApproachAvoiding(gui, candidates, false, hazards, hazardRadius);
         case LAND:
         default:
            return MovementScene.planAnyAvoiding(gui, candidates, false, hazards, hazardRadius);
      }
   }

   private static ConfirmedRouteRunner.Params params(Mode mode) {
      if (mode == Mode.BOAT_ROUTE) return ConfirmedRouteRunner.Params.BOAT_ROUTE;
      if (mode == Mode.BOAT_LOCAL) return ConfirmedRouteRunner.Params.BOAT_LOCAL;
      if (mode == Mode.BOAT_APPROACH) return ConfirmedRouteRunner.Params.BOAT_APPROACH;
      return ConfirmedRouteRunner.Params.LAND;
   }

   private static InteractionSpec interactionSpec(MovementScene.GobGeom geom, Coord2d near) {
      InteractionSpec spec = InteractionAdapter.fromGob(
         geom, InteractionSpec.ALL_SIDES, APPROACH_MIN_DIST, APPROACH_MAX_DIST,
         APPROACH_CLEARANCE, null, "", near
      );
      ObjectSpatialProfile profile = ObjectSpatialProfiles.resolve(geom == null ? null : geom.resid);
      return spec == null || !profile.stableApproachPorts ? spec : spec.withStablePorts();
   }

   private static MovementScene.Scene observe(GameUI gui) throws InterruptedException {
      synchronized (gui.ui) {
         return MovementScene.observe(gui);
      }
   }

   private static MovementScene.GobGeom find(MovementScene.Scene scene, long id) {
      if (scene == null || scene.gobs == null) return null;
      for (MovementScene.GobGeom geom : scene.gobs) {
         if (geom != null && geom.id == id) return geom;
      }
      return null;
   }

   private static Coord2d currentPosition(GameUI gui) {
      Gob player = gui != null && gui.map != null ? gui.map.player() : null;
      return BoatNavigation.position(player);
   }

   private static Coord2d reachedCandidate(Coord2d at, List<Coord2d> candidates, double eps) {
      if (at == null || candidates == null) return null;
      for (Coord2d candidate : candidates) {
         if (candidate != null && at.dist(candidate) <= eps) return candidate;
      }
      return null;
   }

   static OccupancyGrid withAvoidance(OccupancyGrid occupancy,
                                      List<Coord2d> centers, double radius) {
      if (occupancy == null || occupancy.occ == null || centers == null
         || centers.isEmpty() || !(radius > 0.0)) return occupancy;
      byte[] cells = occupancy.occ.clone();
      double radius2 = radius * radius;
      for (int y = 0; y < occupancy.h; y++) {
         for (int x = 0; x < occupancy.w; x++) {
            Coord2d point = occupancy.world(x, y);
            for (Coord2d center : centers) {
               double distance = center == null ? Double.POSITIVE_INFINITY : point.dist(center);
               if (distance * distance <= radius2) {
                  cells[y * occupancy.w + x] = OccupancyGrid.SOLID;
                  break;
               }
            }
         }
      }
      return new OccupancyGrid(occupancy.origin, occupancy.w, occupancy.h, occupancy.cell,
         cells, occupancy.start, occupancy.goal, occupancy.freeGoal, occupancy.astar);
   }

   private static Status mapApproach(ApproachGoals.Status status) {
      if (status == ApproachGoals.Status.GEOMETRY_UNAVAILABLE) return Status.GEOMETRY_UNAVAILABLE;
      if (status == ApproachGoals.Status.UNREACHABLE) return Status.BLOCKED;
      return Status.NO_ROUTE;
   }

   private static Status mapStaging(String status) {
      if (InteractionStaging.TARGET_DISAPPEARED.equals(status)) return Status.TARGET_GONE;
      if (InteractionStaging.TARGET_CHANGED.equals(status)) return Status.TARGET_MOVED;
      if (InteractionStaging.LOCAL_GEOMETRY_UNAVAILABLE.equals(status)) return Status.GEOMETRY_UNAVAILABLE;
      return Status.BLOCKED;
   }

   private static Result result(Status status, Coord2d end, Coord2d selectedGoal, int replans, String detail) {
      return new Result(status, end, selectedGoal, replans, detail);
   }
}
