package haven.pathfinding;

import haven.Coord2d;
import haven.nav.InteractionSpec;

/**
 * Pose-only approach. The core finds a stand around a supplied footprint.
 * It does not open, take, enter, board, or otherwise act on the target.
 */
public final class ApproachGoals {
   private ApproachGoals() {
   }

   public enum Status {
      POSE_OK,
      NO_VALID_POSE,
      UNREACHABLE,
      GEOMETRY_UNAVAILABLE
   }

   public static final class Result {
      public final Status status;
      public final InteractionGoals.Result pose;

      Result(Status status, InteractionGoals.Result pose) {
         this.status = status;
         this.pose = pose;
      }

      public boolean ok() {
         return this.status == Status.POSE_OK && this.pose != null && this.pose.ok();
      }
   }

   public static Result plan(Coord2d from, InteractionSpec spec, OccupancyGrid occ) {
      return plan(from, spec, occ, null);
   }

   public static Result plan(Coord2d from, InteractionSpec spec, OccupancyGrid occ, InteractionGoals.Geometry geom) {
      if (spec == null) {
         return new Result(Status.NO_VALID_POSE, null);
      }
      if (CollisionGeom.UNAVAILABLE.equals(spec.geometrySource)) {
         return new Result(Status.GEOMETRY_UNAVAILABLE, InteractionGoals.select(from, spec, occ, geom));
      }
      InteractionGoals.Result pose = InteractionGoals.preferOccupancyStand(
         from, InteractionGoals.selectOccupancy(from, spec, occ, geom), occ, geom
      );
      pose = InteractionGoals.ensureSweptRoute(from, pose, occ, geom);
      return new Result(classify(pose), pose);
   }

   public static Status classify(InteractionGoals.Result pose) {
      if (pose == null || pose.spec == null) {
         return Status.NO_VALID_POSE;
      }
      if (CollisionGeom.UNAVAILABLE.equals(pose.spec.geometrySource) || InteractionGoals.GEOMETRY_UNAVAILABLE.equals(pose.reason)) {
         return Status.GEOMETRY_UNAVAILABLE;
      }
      if (pose.ok()) {
         return Status.POSE_OK;
      }
      if ("unreachable".equals(pose.reason) || "unreachable".equals(pose.dominantReject())) {
         return Status.UNREACHABLE;
      }
      return Status.NO_VALID_POSE;
   }
}
