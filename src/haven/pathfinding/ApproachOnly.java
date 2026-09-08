package haven.pathfinding;

import auto.Bot;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.UI;
import haven.nav.InteractionSpec;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Critical-route {@code APPROACH_ONLY}: walk to a legal stand around a live
 * object and stop. No menu, right-click, transfer, or transition.
 */
public final class ApproachOnly {
   public static final String KIND = "approach";
   public static final String ROLE = "approach_only";
   public static final double MIN_DIST = 0.5;
   public static final double MAX_DIST = 16.5;
   public static final int CLEARANCE = 0;
   public static final double POSE_EPS = 2.475;

   public enum Outcome {
      APPROACH_READY,
      NO_VALID_POSE,
      UNREACHABLE,
      TARGET_MOVED,
      TARGET_DISAPPEARED,
      GEOMETRY_UNAVAILABLE
   }

   public static final class Result {
      public final Outcome outcome;
      public final Coord2d pose;
      public final InteractionSpec spec;
      public final boolean interacted;
      public final String detail;

      Result(Outcome outcome, Coord2d pose, InteractionSpec spec, String detail) {
         this.outcome = outcome;
         this.pose = pose;
         this.spec = spec;
         this.interacted = false;
         this.detail = detail == null ? "" : detail;
      }
   }

   private ApproachOnly() {
   }

   public static InteractionSpec spec(PrototypePathfinder.GobGeom g, Coord2d near) {
      return InteractionAdapter.fromGob(
         g, InteractionSpec.ALL_SIDES, MIN_DIST, MAX_DIST, CLEARANCE, null, "", near
      );
   }

   public static Outcome mapPlan(ApproachGoals.Status status) {
      if (status == null) {
         return Outcome.NO_VALID_POSE;
      }
      switch (status) {
         case POSE_OK:
            return Outcome.APPROACH_READY;
         case UNREACHABLE:
            return Outcome.UNREACHABLE;
         case GEOMETRY_UNAVAILABLE:
            return Outcome.GEOMETRY_UNAVAILABLE;
         case NO_VALID_POSE:
         default:
            return Outcome.NO_VALID_POSE;
      }
   }

   public static Outcome revalidate(Coord2d pos, Coord2d pose, InteractionSpec spec, boolean targetPresent, Coord2d liveOrigin) {
      if (!targetPresent) {
         return Outcome.TARGET_DISAPPEARED;
      }
      if (!InteractionVerifier.arrivedConfirmed(true, pos, pose, POSE_EPS)) {
         return Outcome.UNREACHABLE;
      }
      if (!InteractionVerifier.poseStillValid(pos, pose, POSE_EPS, spec, liveOrigin, spec == null ? null : spec.half, true)) {
         return Outcome.TARGET_MOVED;
      }
      return Outcome.APPROACH_READY;
   }

   static Result run(PfTestRunner.Run run, UI ui, GameUI gui, Gob gob) throws Exception {
      if (run != null && run.cancelled) {
         throw new PfTestRunner.Cancelled();
      }
      if (gui == null || gui.map == null || gob == null) {
         PathfinderLog.dumpFailure("approach TARGET_DISAPPEARED: no live object");
         return new Result(Outcome.TARGET_DISAPPEARED, null, null, "no live object");
      }
      PrototypePathfinder.Scene scene;
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
      }
      PrototypePathfinder.GobGeom geom = find(scene, gob.id);
      if (geom == null) {
         geom = PrototypePathfinder.gobGeom(gui, gob.id);
      }
      if (geom == null || geom.rc == null) {
         PathfinderLog.dumpFailure("approach TARGET_DISAPPEARED: object not in scene");
         return new Result(Outcome.TARGET_DISAPPEARED, null, null, "object not in scene");
      }
      InteractionSpec spec = spec(geom, scene.player);
      if (spec == null || CollisionGeom.UNAVAILABLE.equals(spec.geometrySource)) {
         PathfinderLog.dumpFailure("approach GEOMETRY_UNAVAILABLE: target geometry unavailable");
         return new Result(Outcome.GEOMETRY_UNAVAILABLE, null, spec, "target geometry unavailable");
      }
      long targetId = gob.id;
      if (StagingPlanner.required(scene.player, scene.occupancy, spec)) {
         InteractionTarget target = InteractionTarget.of(geom, KIND, null, scene.terrain);
         InteractionStaging.Result st = InteractionStaging.stage(run, ui, gui, target, (g, near) -> spec(g, near), null);
         if (!st.staged()) {
            return new Result(mapStaging(st.outcome), null, spec, st.detail);
         }
         scene = st.scene;
         geom = st.target;
         spec = spec(geom, scene.player);
         targetId = st.target.id;
         if (spec == null || CollisionGeom.UNAVAILABLE.equals(spec.geometrySource)) {
            PathfinderLog.dumpFailure("approach GEOMETRY_UNAVAILABLE: geometry unavailable after staging");
            return new Result(Outcome.GEOMETRY_UNAVAILABLE, null, spec, "geometry unavailable after staging");
         }
      }
      InteractionGoals.Geometry poseGeom = new InteractionGoals.Geometry(scene.solids, scene.playerBody);
      ApproachGoals.Result planned = ApproachGoals.plan(scene.player, spec, scene.occupancy, poseGeom);
      PathfinderLog.recordOccupancy(scene.occupancy);
      GeometryDump.dump(gui, scene, geom, planned.pose);
      if (!planned.ok()) {
         Outcome out = mapPlan(planned.status);
         if (out == Outcome.APPROACH_READY) {
            out = Outcome.NO_VALID_POSE;
         }
         PathfinderLog.dumpFailure("approach " + out.name() + ": " + planned.status.name());
         return new Result(out, null, spec, planned.status.name());
      }
      Coord2d pose = planned.pose.selected.world;
      List<Coord2d> route = planned.pose.plan.smoothedRoute;
      if (route == null || route.size() < 2) {
         route = new ArrayList<Coord2d>();
         route.add(scene.player);
         route.add(pose);
      }
      Bot bot = Bot.execute(new Bot.BotAction[0]);
      WaypointWalker.Result walk = WaypointWalker.execute(
         WaypointWalker.liveEnv(gui),
         bot,
         route,
         0,
         60000L,
         WaypointWalker.Params.DEFAULT,
         NamedPlaceNavigator.NOOP,
         planned.pose.plan.status,
         pose,
         spec
      );
      if (walk != WaypointWalker.Result.READY_TO_INTERACT && walk != WaypointWalker.Result.ARRIVED) {
         PathfinderLog.dumpFailure("approach UNREACHABLE: walk " + walk);
         return new Result(Outcome.UNREACHABLE, pose, spec, "walk " + walk);
      }
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
      }
      PrototypePathfinder.GobGeom live = find(scene, targetId);
      if (live == null) {
         PathfinderLog.dumpFailure("approach TARGET_DISAPPEARED: object gone after walk");
         return new Result(Outcome.TARGET_DISAPPEARED, pose, spec, "object gone after walk");
      }
      InteractionSpec fresh = spec(live, spec.origin);
      if (fresh == null || CollisionGeom.UNAVAILABLE.equals(fresh.geometrySource)) {
         PathfinderLog.dumpFailure("approach GEOMETRY_UNAVAILABLE: geometry gone after walk");
         return new Result(Outcome.GEOMETRY_UNAVAILABLE, pose, spec, "geometry gone after walk");
      }
      Outcome checked = revalidate(scene.player, pose, spec, true, fresh.origin);
      if (checked != Outcome.APPROACH_READY) {
         PathfinderLog.dumpFailure("approach " + checked.name() + ": revalidation failed");
      }
      return new Result(checked, pose, spec, checked.name());
   }

   private static PrototypePathfinder.GobGeom find(PrototypePathfinder.Scene scene, long id) {
      if (scene == null || scene.gobs == null) {
         return null;
      }
      for (int i = 0; i < scene.gobs.size(); i++) {
         PrototypePathfinder.GobGeom g = scene.gobs.get(i);
         if (g != null && g.id == id) {
            return g;
         }
      }
      return null;
   }

   private static Outcome mapStaging(String outcome) {
      if (InteractionStaging.TARGET_DISAPPEARED.equals(outcome)) return Outcome.TARGET_DISAPPEARED;
      if (InteractionStaging.TARGET_CHANGED.equals(outcome)) return Outcome.TARGET_MOVED;
      if (InteractionStaging.LOCAL_GEOMETRY_UNAVAILABLE.equals(outcome)) return Outcome.GEOMETRY_UNAVAILABLE;
      if (InteractionStaging.STAGING_UNREACHABLE.equals(outcome)) return Outcome.UNREACHABLE;
      return Outcome.UNREACHABLE;
   }

   static JSONObject check(Result r) {
      boolean ok = r != null && r.outcome == Outcome.APPROACH_READY && !r.interacted;
      String note = r == null ? "no result" : r.outcome.name() + (r.interacted ? " interacted" : " no interaction");
      return PfTestRunner.check("approach_only", ok, note);
   }
}
