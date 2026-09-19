package haven.pathfinding;

import auto.Bot;
import haven.Coord2d;
import haven.GameUI;
import haven.UI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * Shared staging cycle for distant-object interaction.
 *
 * <p>Stage 1 (STAGING): walk to a safe coordinate within the reliable local
 * observation radius of the target, refresh the occupancy grid and exact
 * collision geometry, then re-resolve the same target object. The target's
 * center is never a walking destination. Repeats are bounded; every walk has
 * a timeout. This class never interacts with the target.</p>
 */
public final class InteractionStaging {
   public static final String PHASE_STAGING_POSE_SELECTED = "STAGING_POSE_SELECTED";
   public static final String PHASE_STAGING_MOVEMENT = "STAGING_MOVEMENT";
   public static final String PHASE_STAGING_ARRIVED = "STAGING_ARRIVED";
   public static final String PHASE_GEOMETRY_REFRESHED = "GEOMETRY_REFRESHED";

   public static final String STAGING_UNREACHABLE = "STAGING_UNREACHABLE";
   public static final String TARGET_DISAPPEARED = "TARGET_DISAPPEARED";
   public static final String TARGET_CHANGED = "TARGET_CHANGED";
   public static final String LOCAL_GEOMETRY_UNAVAILABLE = "LOCAL_GEOMETRY_UNAVAILABLE";

   /** Initial local leg plus two bounded staging replans. */
   public static final int MAX_ROUNDS = 3;
   static final long WALK_BUDGET_MS = 60000L;

   private InteractionStaging() {
   }

   /** Builds the interaction spec for the freshly resolved target. */
   public interface SpecMaker {
      haven.nav.InteractionSpec spec(MovementScene.GobGeom g, Coord2d near);
   }

   /** Live scene observation; injectable for headless regression tests. */
   public interface SceneSupplier {
      MovementScene.Scene get() throws InterruptedException;
   }

   /** Live observation of the game scene. */
   public static SceneSupplier observing(final UI ui, final GameUI gui) {
      return new SceneSupplier() {
         @Override
         public MovementScene.Scene get() throws InterruptedException {
            synchronized (ui) {
               return MovementScene.observe(gui);
            }
         }
      };
   }

   /** Per-phase telemetry hook; implementations record to the pf log. */
   public interface Listener {
      void phase(String phase, InteractionTarget identity, Coord2d player, Coord2d staging, String detail);
   }

   public static final class Result {
      public final String outcome;
      /** Fresh scene after staging; non-null on STAGED. */
      public final MovementScene.Scene scene;
      /** Freshly resolved target geometry; non-null on STAGED. */
      public final MovementScene.GobGeom target;
      /** Identity refreshed with the live observation. */
      public final InteractionTarget identity;
      public final List<Coord2d> stagingPoints;
      public final String detail;

      Result(String outcome, MovementScene.Scene scene, MovementScene.GobGeom target,
             InteractionTarget identity, List<Coord2d> stagingPoints, String detail) {
         this.outcome = outcome;
         this.scene = scene;
         this.target = target;
         this.identity = identity;
         this.stagingPoints = Collections.unmodifiableList(stagingPoints);
         this.detail = detail == null ? "" : detail;
      }

      public boolean staged() {
         return "STAGED".equals(this.outcome);
      }
   }

   /**
    * Live-game staging entry point for an already-running {@code auto.Bot}:
    * the bot's own cancellation object is threaded through every staging walk
    * so Stop/Esc cancels movement instead of racing against an unrelated
    * uncancellable {@code Bot}.
    */
   public static Result stage(
      UI ui, GameUI gui, InteractionTarget identity, SpecMaker specMaker, Listener listener, Bot bot
   ) throws InterruptedException {
      return stage(ui, gui, identity, specMaker, listener, bot, BotMovement.Avoidance.NONE);
   }

   public static Result stage(
      UI ui, GameUI gui, InteractionTarget identity, SpecMaker specMaker, Listener listener, Bot bot,
      BotMovement.Avoidance avoidance
   ) throws InterruptedException {
      return stage(ui, gui, observing(ui, gui), identity, specMaker, listener, bot, avoidance);
   }

   static Result stage(
      UI ui, GameUI gui, SceneSupplier scenes, InteractionTarget identity,
      SpecMaker specMaker, Listener listener, Bot bot, BotMovement.Avoidance avoidance
   ) throws InterruptedException {
      List<Coord2d> stagingPoints = new ArrayList<Coord2d>();
      BotMovement.Avoidance avoid = avoidance == null ? BotMovement.Avoidance.NONE : avoidance;
      PathfinderLog.setTarget("interact " + identity.label() + " STAGING>INTERACT");
      try {
         for (int round = 0; round < MAX_ROUNDS; round++) {
            bot.checkCancelled();
            MovementScene.Scene scene = scenes.get();
            if (scene == null || scene.player == null || scene.occupancy == null) {
               return new Result(LOCAL_GEOMETRY_UNAVAILABLE, null, null, identity, stagingPoints, "no local scene");
            }
            OccupancyGrid safeOccupancy = BotMovement.withAvoidance(
               scene.occupancy, avoid.currentCenters(), avoid.radius);
            MovementScene.GobGeom live = identity.resolveIn(scene);
            StagingPlanner.Result pick;
            if (live == null) {
               // Target not yet observed: hop toward its last-known position.
               if (identity.lastRc == null) {
                  PathfinderLog.dumpFailure("staging TARGET_DISAPPEARED: target not in scene and no last position");
                  return new Result(TARGET_DISAPPEARED, null, null, identity, stagingPoints, "target not in scene and no last position");
               }
               pick = StagingPlanner.selectToward(scene.player, identity.lastRc, safeOccupancy);
               if (!pick.ok()) {
                  PathfinderLog.dumpFailure("staging STAGING_UNREACHABLE: no walkable progress toward unobserved target " + identity.label());
                  return new Result(STAGING_UNREACHABLE, null, null, identity, stagingPoints, "no walkable progress toward unobserved target");
               }
            } else {
               if (!identity.compatibleWith(live)) {
                  return new Result(TARGET_CHANGED, null, null, identity, stagingPoints, "resource/class changed to " + live.resid);
               }
               boolean byId = live.id == identity.gobId;
               identity = identity.refreshed(live, scene.terrain, byId);
               PathfinderLog.recordInteraction(phaseRecord(
                  PHASE_GEOMETRY_REFRESHED, identity, scene.player, stagingPoints, live, round, "byId=" + byId
               ));
               if (listener != null) {
                  listener.phase(PHASE_GEOMETRY_REFRESHED, identity, scene.player, null, "byId=" + byId);
               }
               haven.nav.InteractionSpec spec = specMaker.spec(live, scene.player);
               if (spec == null) {
                  return new Result(LOCAL_GEOMETRY_UNAVAILABLE, null, null, identity, stagingPoints, "spec unavailable");
               }
               if (!StagingPlanner.required(scene.player, safeOccupancy, spec)) {
                  return new Result("STAGED", scene, live, identity, stagingPoints, "");
               }
               if (!StagingPlanner.inGrid(safeOccupancy, spec.origin)) {
                  // Observed but target origin lies outside the grid: hop toward it.
                  pick = StagingPlanner.selectToward(scene.player, live.rc, safeOccupancy);
                  if (!pick.ok()) {
                     PathfinderLog.dumpFailure("staging STAGING_UNREACHABLE: no walkable progress toward target outside grid " + identity.label());
                     return new Result(STAGING_UNREACHABLE, null, null, identity, stagingPoints, "no walkable progress toward target outside grid");
                  }
               } else {
                  pick = StagingPlanner.select(scene.player, spec, safeOccupancy);
                  if (!pick.ok()) {
                     PathfinderLog.dumpFailure("staging STAGING_UNREACHABLE: no walkable staging coordinate beside " + identity.label());
                     return new Result(STAGING_UNREACHABLE, null, null, identity, stagingPoints,
                        "no walkable staging coordinate beside " + identity.label());
                  }
               }
            }
            PathfinderLog.recordInteraction(phaseRecord(
               PHASE_STAGING_POSE_SELECTED, identity, scene.player, stagingPoints, live, round,
               "angle=" + pick.selected.angle + " radius=" + Math.round(pick.selected.radius)
                  + " candidates=" + pick.goals.size()
            ));
            if (listener != null) {
               listener.phase(PHASE_STAGING_POSE_SELECTED, identity, scene.player, pick.selected.world,
                  "angle=" + pick.selected.angle + " radius=" + Math.round(pick.selected.radius)
                     + " candidates=" + pick.goals.size());
            }
            BotMovement.Result walk = walkTo(
               ui, gui, scene, pick.goals, pick.selected.world, identity, bot, avoid
            );
            Coord2d staging = walk.selectedGoal == null ? pick.selected.world : walk.selectedGoal;
            stagingPoints.add(staging);
            if (!walk.arrived()) {
               String walkErr = "staging walk " + walk.status
                  + (walk.detail.isEmpty() ? "" : " (" + walk.detail + ")");
               PathfinderLog.dumpFailure("staging STAGING_UNREACHABLE: " + walkErr);
               return new Result(STAGING_UNREACHABLE, null, null, identity, stagingPoints, walkErr);
            }
            PathfinderLog.recordInteraction(phaseRecord(
               PHASE_STAGING_ARRIVED, identity, staging, stagingPoints, live, round, ""
            ));
            if (listener != null) {
               listener.phase(PHASE_STAGING_ARRIVED, identity, staging, staging, "");
            }
         }
      } finally {
         PathfinderLog.clearTarget();
      }
      return new Result(LOCAL_GEOMETRY_UNAVAILABLE, null, null, identity, stagingPoints, "target still outside local planning range after " + MAX_ROUNDS + " staging rounds");
   }

   /**
    * Walks a staging leg to any candidate accepted by the exact live
    * pathfinder. The plan log carries the interaction target label and phase,
    * so the movement is never mistaken for a plain go-to.
    */
   private static BotMovement.Result walkTo(
      UI ui, GameUI gui, MovementScene.Scene scene, List<Coord2d> candidates,
      Coord2d preferred,
      InteractionTarget identity, Bot bot, BotMovement.Avoidance avoidance
   ) throws InterruptedException {
      PathfinderLog.recordInteraction(phaseRecord(
         PHASE_STAGING_MOVEMENT, identity, scene.player, Collections.singletonList(preferred), null, -1,
         "candidates=" + candidates.size()
      ));
      bot.checkCancelled();
      return BotMovement.moveToAny(
         gui, bot, candidates, avoidance, BotMovement.Mode.LAND, WALK_BUDGET_MS
      );
   }

   /** Compact phase record retaining target identity and context for telemetry. */
   public static JSONObject phaseRecord(
      String phase, InteractionTarget identity, Coord2d player, List<Coord2d> stagingPoints,
      MovementScene.GobGeom live, int retry, String detail
   ) {
      JSONObject o = new JSONObject();
      o.put("phase", phase);
      if (identity != null) {
         o.put("target_id", identity.gobId);
         o.put("target_resource", identity.resid);
         o.put("target_kind", identity.kind);
         o.put("expected_result", identity.expectedResult);
         o.put("surface", identity.surface);
         if (identity.lastRc != null) {
            o.put("target_coord", new org.json.JSONArray().put(identity.lastRc.x).put(identity.lastRc.y));
         }
         if (live != null && live.rc != null && player != null) {
            o.put("target_dist", Math.round(player.dist(live.rc)));
         }
      }
      if (player != null) {
         o.put("player", new org.json.JSONArray().put(player.x).put(player.y));
      }
      if (stagingPoints != null && !stagingPoints.isEmpty()) {
         org.json.JSONArray arr = new org.json.JSONArray();
         Coord2d last = null;
         for (Coord2d p : stagingPoints) {
            arr.put(new org.json.JSONArray().put(p.x).put(p.y));
            last = p;
         }
         o.put("staging_coord", new org.json.JSONArray().put(last.x).put(last.y));
         o.put("staging_path", arr);
      }
      o.put("retry", retry);
      if (detail != null && !detail.isEmpty()) {
         o.put("detail", detail);
      }
      return o;
   }
}
