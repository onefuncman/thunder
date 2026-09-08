package haven.pathfinding;

import auto.Bot;
import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.Moving;
import haven.UI;
import haven.WItem;
import haven.Window;
import haven.nav.InteractionSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Allowlisted interaction-goal scenarios. Missing fixtures fail closed.
 * Arrival at a stand pose is not interaction success.
 *
 * <p>Distant targets use a two-stage flow: identity is preserved in an
 * {@link InteractionTarget}, the player first walks to a staging coordinate
 * ({@code target.rc} is never a final walking destination), geometry is
 * refreshed, the target is re-resolved, and only then is the final
 * interaction pose selected from fresh local geometry.</p>
 */
final class InteractScenario implements PfTestRunner.Scenario {
   enum Kind {
      FORAGE("interact_forageable", InteractionVerifier.TARGET_GONE, 1.5, 16.5, 1),
      TREE("interact_tree", InteractionVerifier.STATE_CHANGED, 2.0, 16.5, 1),
      BOULDER("interact_boulder", InteractionVerifier.STATE_CHANGED, 2.0, 16.5, 1),
      CUPBOARD("interact_cupboard", InteractionVerifier.WINDOW_OPENED, 0.5, 16.5, 0),
      DOOR("interact_door_gate", InteractionVerifier.STATE_CHANGED, 1.0, 35.0, 1),
      CROP("interact_field_crop", InteractionVerifier.TARGET_GONE, 1.5, 16.5, 1),
      NARROW("interact_narrow_interior", InteractionVerifier.WINDOW_OPENED, 0.5, 11.0, 0);

      final String name;
      final String expected;
      final double minDist;
      final double maxDist;
      final int clearance;

      Kind(String name, String expected, double minDist, double maxDist, int clearance) {
         this.name = name;
         this.expected = expected;
         this.minDist = minDist;
         this.maxDist = maxDist;
         this.clearance = clearance;
      }
   }

   private static final double POSE_EPS = 2.475;
   private static final long WALK_BUDGET_MS = 60000L;

   private final Kind kind;

   InteractScenario(Kind kind) {
      this.kind = kind;
   }

   @Override
   public String name() {
      return this.kind.name;
   }

   @Override
   public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
      GameUI gui = ui == null ? null : ui.gui;
      boolean inGame = gui != null && gui.map != null;
      boolean playerPresent = false;
      boolean idle = true;
      if (inGame) {
         synchronized (ui) {
            Gob me = gui.map.player();
            playerPresent = me != null && me.rc != null;
            idle = me == null || me.getattr(Moving.class) == null;
         }
      }
      List<JSONObject> checks = new ArrayList<JSONObject>(
         PfTestHarness.autoMovePreflightChecks(this.kind.name, inGame, playerPresent, true, idle, Bot.hasCurrent())
      );
      if (!inGame || !playerPresent) {
         JSONObject facts = new JSONObject().put("refusal", "NO_GAME").put("selected", false).put("kind", this.kind.name());
         return PfTestHarness.body(checks, "not in game", facts);
      }
      PrototypePathfinder.Scene scene;
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
      }
      PrototypePathfinder.GobGeom gob = pick(scene);
      if (gob == null) {
         gob = distantFixture(gui, scene);
      }
      if (gob == null) {
         return failClosed(checks, "NO_FIXTURE", "no automatic fixture for " + this.kind.name);
      }
      InteractionTarget target = InteractionTarget.of(gob, this.kind.name, this.kind.expected, scene.terrain);
      List<Coord2d> staging = new ArrayList<Coord2d>();
      String observed = "";
      String retry = "";
      String outcome = "INTERACTION_FAILED";
      boolean arrived = false;
      boolean interacted = false;
      boolean confirmed = false;
      PathfinderLog.setTarget("interact " + target.label() + " " + this.kind.name);
      try {
         InteractionSpec spec = spec(gob, scene.player);
         if (CollisionGeom.UNAVAILABLE.equals(spec.geometrySource) || StagingPlanner.required(scene.player, scene.occupancy, spec)) {
            PathfinderLog.recordInteraction(phaseEvidence("STAGING_REQUIRED", target, null, null, scene.player, staging, "", "", "0", ""));
            if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            }
            InteractionStaging.Result st = InteractionStaging.stage(
               run, ui, gui, target, (g, near) -> spec(g, near), null
            );
            for (Coord2d p : st.stagingPoints) {
               staging.add(p);
            }
            if (!st.staged()) {
               return failOutcome(checks, st.outcome, st.detail, st.identity, staging);
            }
            scene = st.scene;
            gob = st.target;
            target = st.identity;
            spec = spec(gob, scene.player);
            if (CollisionGeom.UNAVAILABLE.equals(spec.geometrySource)) {
               return failOutcome(checks, "LOCAL_GEOMETRY_UNAVAILABLE", "target geometry unavailable after staging", target, staging);
            }
            if (this.kind == Kind.NARROW && !narrowFixture(scene, gob)) {
               return failClosed(checks, "NO_FIXTURE", "cupboard is not narrow-access");
            }
         }
         PathfinderLog.setTarget("interact " + target.label() + " FINAL>INTERACT");
         checks.add(PfTestRunner.check("fixture", true, this.kind.name + " target #" + gob.id));
         InteractionGoals.Geometry geom = new InteractionGoals.Geometry(scene.solids, scene.playerBody);
         InteractionGoals.Result pose = InteractionGoals.select(scene.player, spec, scene.occupancy, geom);
         PathfinderLog.recordOccupancy(scene.occupancy);
         PathfinderLog.recordInteraction(phaseEvidence(
            "INTERACTION_POSE_SELECTED", target, spec, pose, scene.player, staging, "", "", "0", ""
         ));
         if (!pose.ok() || pose.selected == null) {
            return failOutcome(checks, "NO_INTERACTION_POSE",
               "no reachable interaction pose (" + pose.dominantReject() + ")", target, staging);
         }
         if (this.kind == Kind.NARROW && !narrowAccess(pose)) {
            return failClosed(checks, "NO_FIXTURE", "cupboard is not narrow-access");
         }
         checks.add(PfTestRunner.check("pose_selected", true, "stand " + PfTestHarness.pt(pose.selected.world)));
         Bot bot = Bot.execute(new Bot.BotAction[0]);
         for (int attempt = 0; InteractionVerifier.retryAllowed(attempt); attempt++) {
            if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            }
            if (attempt > 0) {
               retry = "revalidate_replan";
               synchronized (ui) {
                  scene = PrototypePathfinder.observe(gui);
               }
               PrototypePathfinder.GobGeom live = target.resolveIn(scene);
               if (live == null) {
                  outcome = "TARGET_DISAPPEARED";
                  break;
               }
               if (!target.sameIdentity(live)) {
                  outcome = "TARGET_CHANGED";
                  break;
               }
               boolean byId = live.id == target.gobId;
               target = target.refreshed(live, scene.terrain, byId);
               PathfinderLog.recordInteraction(phaseEvidence(
                  "GEOMETRY_REFRESHED", target, null, null, scene.player, staging, "", observed, String.valueOf(attempt), ""
               ));
               spec = spec(live, spec.origin);
               InteractionGoals.Geometry fg = new InteractionGoals.Geometry(scene.solids, scene.playerBody);
               pose = InteractionGoals.select(scene.player, spec, scene.occupancy, fg);
               PathfinderLog.recordInteraction(phaseEvidence(
                  "INTERACTION_POSE_SELECTED", target, spec, pose, scene.player, staging, "", observed, String.valueOf(attempt), ""
               ));
               if (!pose.ok()) {
                  outcome = "NO_INTERACTION_POSE";
                  break;
               }
            }
            List<Coord2d> route = pose.plan.smoothedRoute;
            if (route.size() < 2) {
               route = new ArrayList<Coord2d>();
               route.add(scene.player);
               route.add(pose.selected.world);
            }
            WaypointWalker.Result walk = WaypointWalker.execute(
               WaypointWalker.liveEnv(gui),
               bot,
               route,
               attempt,
               WALK_BUDGET_MS,
               WaypointWalker.Params.DEFAULT,
               NamedPlaceNavigator.NOOP,
               pose.plan.status,
               pose.selected.world,
               spec
            );
            arrived = walk == WaypointWalker.Result.READY_TO_INTERACT;
            if (!arrived) {
               outcome = "FINAL_POSE_UNREACHABLE";
               continue;
            }
            synchronized (ui) {
               scene = PrototypePathfinder.observe(gui);
            }
            PrototypePathfinder.GobGeom live = target.resolveIn(scene);
            Coord2d pos = scene.player;
            boolean stillIdle = !scene.moving;
            if (!InteractionVerifier.mayInteract(InteractionVerifier.arrivedConfirmed(stillIdle, pos, pose.selected.world, POSE_EPS))) {
               retry = "not_idle_at_pose";
               outcome = "POSE_INVALIDATED";
               continue;
            }
            if (live == null) {
               outcome = "TARGET_DISAPPEARED";
               break;
            }
            Coord2d liveOrigin = BuildingDoor.liveOrigin(live, spec);
            if (!InteractionVerifier.poseStillValid(pos, pose.selected.world, POSE_EPS, spec, liveOrigin, spec.half, true)) {
               retry = "footprint_changed";
               outcome = "POSE_INVALIDATED";
               continue;
            }
            int invBefore = inventoryCount(gui);
            Set<Integer> windowsBefore = windowIds(gui);
            int gateBefore = live.gateState;
            Gob interactTarget;
            synchronized (ui) {
               interactTarget = gui.map.glob.oc.getgob(live.id);
            }
            if (interactTarget == null) {
               outcome = "TARGET_DISAPPEARED";
               break;
            }
            interacted = true;
            PathfinderLog.recordInteraction(phaseEvidence(
               "INTERACTION_SENT", target, spec, pose, scene.player, staging, "INTERACT", observed, String.valueOf(attempt), ""
            ));
            BuildingDoor.interact(gui, interactTarget, spec);
            long t0 = System.currentTimeMillis();
            while (!InteractionVerifier.timedOut(System.currentTimeMillis() - t0, InteractionVerifier.WAIT_MS)) {
               bot.checkCancelled();
               Thread.sleep(50L);
               if (checkConfirmed(gui, spec.expectedResult, live.id, invBefore, windowsBefore, gateBefore)) {
                  confirmed = true;
                  observed = spec.expectedResult;
                  break;
               }
            }
            outcome = confirmed ? "INTERACTION_CONFIRMED" : "INTERACTION_TIMEOUT";
            PathfinderLog.recordInteraction(phaseEvidence(
               confirmed ? "INTERACTION_CONFIRMED" : "INTERACTION_TIMEOUT",
               target, spec, pose, scene.player, staging, "INTERACT", observed, String.valueOf(attempt), outcome
            ));
            if (confirmed) {
               break;
            }
            retry = "timeout";
         }
      } finally {
         PathfinderLog.clearTarget();
      }
      checks.add(PfTestRunner.check("arrival", arrived, arrived ? "server-confirmed pose" : outcome));
      if (!confirmed) {
         PathfinderLog.dumpFailure("interact " + outcome + (retry.isEmpty() ? "" : " retry=" + retry));
      }
      checks.add(PfTestRunner.check("no_click_before_arrival", !interacted || arrived, arrived ? "clicked after arrival" : "no click"));
      checks.add(PfTestRunner.check("interaction_confirmed", confirmed, confirmed ? observed : outcome));
      JSONObject facts = new JSONObject()
         .put("kind", this.kind.name())
         .put("selected", true)
         .put("target_id", target.gobId)
         .put("target_resource", target.resid)
         .put("staging_points", pts(staging))
         .put("expected", this.kind.expected)
         .put("observed", observed)
         .put("retry", retry)
         .put("arrived", arrived)
         .put("interacted", interacted)
         .put("status", confirmed ? "CONFIRMED" : outcome);
      PathfinderLog.recordInteraction(phaseEvidence(
         confirmed ? "INTERACTION_CONFIRMED" : outcome, target, null, null, null, staging, arrived ? "INTERACT" : "", observed, retry, outcome
      ));
      return PfTestHarness.body(checks, confirmed ? "interaction confirmed" : outcome, facts);
   }

   private InteractionSpec spec(PrototypePathfinder.GobGeom g, Coord2d near) {
      return InteractionAdapter.fromGob(
         g, InteractionSpec.ALL_SIDES, this.kind.minDist, this.kind.maxDist, this.kind.clearance, null, this.kind.expected, near
      );
   }

   private JSONObject failClosed(List<JSONObject> checks, String refusal, String why) {
      checks.add(PfTestRunner.check("fixture", false, why));
      JSONObject facts = new JSONObject().put("refusal", refusal).put("selected", false).put("kind", this.kind.name());
      return PfTestHarness.body(checks, why, facts);
   }

   /** Bounded failure outcome; telemetry keeps the full target identity. */
   private JSONObject failOutcome(List<JSONObject> checks, String refusal, String why, InteractionTarget target, List<Coord2d> staging) {
      checks.add(PfTestRunner.check("fixture", false, why));
      PathfinderLog.dumpFailure("interact " + refusal + ": " + why);
      PathfinderLog.recordInteraction(phaseEvidence(refusal, target, null, null, null, staging, "", "", "", refusal));
      JSONObject facts = new JSONObject()
         .put("refusal", refusal)
         .put("selected", false)
         .put("kind", this.kind.name())
         .put("target_id", target == null ? JSONObject.NULL : target.gobId)
         .put("target_resource", target == null ? JSONObject.NULL : target.resid)
         .put("staging_points", pts(staging))
         .put("why", why);
      return PfTestHarness.body(checks, why, facts);
   }

   private static JSONArray pts(List<Coord2d> staging) {
      JSONArray a = new JSONArray();
      for (Coord2d p : staging) {
         a.put(PfTestHarness.pt(p));
      }
      return a;
   }

   /**
    * Broader fixture scan for interaction targets outside the observed scene
    * list (e.g. a distant container). Same kind rules; nearest match wins.
    */
   private PrototypePathfinder.GobGeom distantFixture(GameUI gui, PrototypePathfinder.Scene scene) {
      if (gui == null || gui.ui == null || gui.ui.sess == null || gui.map == null) {
         return null;
      }
      Gob player = gui.map.player();
      if (player == null || player.rc == null) {
         return null;
      }
      PrototypePathfinder.GobGeom best = null;
      synchronized (gui.ui.sess.glob.oc) {
         for (Gob gob : gui.ui.sess.glob.oc) {
            if (gob == null || gob == player || gob.virtual || gob.id < 0L || gob.rc == null) {
               continue;
            }
            if (gob.rc.dist(player.rc) > 220.0) {
               continue;
            }
            try {
               if (gob.resid() == null) {
                  continue;
               }
               PrototypePathfinder.GobGeom g = PrototypePathfinder.gobGeom(player, gob);
               if (g != null && matches(g, scene) && (best == null || g.gobDist < best.gobDist)) {
                  best = g;
               }
            } catch (Loading ignored) {
            }
         }
      }
      return best;
   }

   private boolean narrowFixture(PrototypePathfinder.Scene scene, PrototypePathfinder.GobGeom gob) {
      return looksNarrow(scene.occupancy, gob);
   }

   private PrototypePathfinder.GobGeom pick(PrototypePathfinder.Scene scene) {
      if (scene == null || scene.gobs == null) {
         return null;
      }
      PrototypePathfinder.GobGeom best = null;
      for (int i = 0; i < scene.gobs.size(); i++) {
         PrototypePathfinder.GobGeom g = scene.gobs.get(i);
         if (!matches(g, scene)) {
            continue;
         }
         if (best == null || g.gobDist < best.gobDist) {
            best = g;
         }
      }
      return best;
   }

   private boolean matches(PrototypePathfinder.GobGeom g, PrototypePathfinder.Scene scene) {
      if (g == null) {
         return false;
      }
      switch (this.kind) {
         case FORAGE:
            return isForage(g.resid);
         case TREE:
            return isTree(g.resid);
         case BOULDER:
            return g.boulder || TransitionApproachSelector.isBoulderResid(g.resid);
         case CUPBOARD:
            return g.cupboard;
         case DOOR:
            return g.doorGate || TransitionApproachSelector.isDoorGateResid(g.resid);
         case CROP:
            return isCrop(g.resid);
         case NARROW:
            return g.cupboard && looksNarrow(scene.occupancy, g);
         default:
            return false;
      }
   }

   static boolean isForage(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && (name.contains("/herbs/") || name.contains("/forage"));
   }

   static boolean isTree(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && (name.contains("/trees/") || name.contains("/bushes/"));
   }

   static boolean isCrop(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && (name.contains("/plants/") || name.contains("/field"));
   }

   static boolean looksNarrow(OccupancyGrid occ, PrototypePathfinder.GobGeom g) {
      if (occ == null || g == null || g.rc == null) {
         return false;
      }
      Coord c = occ.cellOf(g.rc);
      if (c == null) {
         return false;
      }
      int open = 0;
      int[] dx = new int[]{0, 1, 0, -1};
      int[] dy = new int[]{-1, 0, 1, 0};
      for (int i = 0; i < 4; i++) {
         if (occ.at(c.x + dx[i], c.y + dy[i]) != OccupancyGrid.SOLID) {
            open++;
         }
      }
      return open <= 2;
   }

   static boolean narrowAccess(InteractionGoals.Result r) {
      Set<Integer> sides = new HashSet<Integer>();
      for (int i = 0; i < r.considered.size(); i++) {
         InteractionGoals.Candidate c = r.considered.get(i);
         if (c.reject == null || "unreachable".equals(c.reject)) {
            sides.add(Integer.valueOf(c.side));
         }
      }
      return sides.size() <= 2;
   }

   static PrototypePathfinder.GobGeom find(PrototypePathfinder.Scene scene, long id) {
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

   static int inventoryCount(GameUI gui) {
      if (gui == null || gui.maininv == null) {
         return 0;
      }
      int n = 0;
      for (WItem ignored : gui.maininv.children(WItem.class)) {
         n++;
      }
      return n;
   }

   static Set<Integer> windowIds(GameUI gui) {
      Set<Integer> ids = new HashSet<Integer>();
      if (gui == null) {
         return ids;
      }
      for (Window w : gui.children(Window.class)) {
         if (w != null && !w.disposed()) {
            ids.add(Integer.valueOf(w.wdgid()));
         }
      }
      return ids;
   }

   static boolean checkConfirmed(GameUI gui, String expected, long gobId, int invBefore, Set<Integer> windowsBefore, int gateBefore) {
      boolean present = gui != null && gui.map != null && gui.map.glob.oc.getgob(gobId) != null;
      boolean inv = inventoryCount(gui) != invBefore;
      boolean window = false;
      boolean flower = false;
      int gate = gateBefore;
      if (gui != null) {
         for (Window w : gui.children(Window.class)) {
            if (w != null && !w.disposed() && (w.gobId() == gobId || !windowsBefore.contains(Integer.valueOf(w.wdgid())))) {
               window = true;
               break;
            }
         }
         for (FlowerMenu ignored : gui.children(FlowerMenu.class)) {
            flower = true;
            break;
         }
         Gob g = gui.map.glob.oc.getgob(gobId);
         if (g != null) {
            gate = g.sdt();
         }
      }
      boolean state = flower || gate != gateBefore;
      return InteractionVerifier.confirmed(expected, present, inv, window, state);
   }

   /**
    * Full interaction evidence for a phase. Every record retains the target
    * gob ID, resource, coordinates, player/staging positions, rejection
    * counts, retry number, and outcome — target identity is never dropped.
    */
   static JSONObject phaseEvidence(
      String phase,
      InteractionTarget target,
      InteractionSpec spec,
      InteractionGoals.Result r,
      Coord2d player,
      List<Coord2d> staging,
      String decision,
      String observed,
      String retry,
      String outcome
   ) {
      JSONObject o = evidence(r, spec, decision != null && decision.equals("INTERACT"), decision, observed, retry, outcome);
      o.put("phase", phase == null ? "" : phase);
      if (target != null) {
         o.put("target_id", target.gobId);
         o.put("target_resource", target.resid);
         o.put("target_kind", target.kind);
         o.put("surface", target.surface);
         o.put("resolved_by_id", target.resolvedById);
         if (target.lastRc != null) {
            o.put("target_coord", new JSONArray().put(target.lastRc.x).put(target.lastRc.y));
         }
      }
      if (player != null) {
         o.put("player", new JSONArray().put(player.x).put(player.y));
      }
      if (staging != null && !staging.isEmpty()) {
         JSONArray arr = new JSONArray();
         for (Coord2d p : staging) {
            arr.put(new JSONArray().put(p.x).put(p.y));
         }
         o.put("staging_path", arr);
         o.put("staging_coord", arr.get(arr.length() - 1));
      }
      return o;
   }

   static JSONObject evidence(
      InteractionGoals.Result r,
      InteractionSpec spec,
      boolean arrived,
      String decision,
      String observed,
      String retry,
      String outcome
   ) {
      JSONObject o = new JSONObject();
      if (spec != null) {
         o.put("target_id", spec.targetId);
         o.put("footprint", new JSONArray().put(spec.origin.x).put(spec.origin.y).put(spec.half.x).put(spec.half.y));
         o.put("min_dist", spec.minDist);
         o.put("max_dist", spec.maxDist);
         o.put("allowed_sides", spec.allowedSides);
         o.put("required_clearance", spec.requiredClearance);
         o.put("expected_result", spec.expectedResult);
         if (spec.facing != null) {
            o.put("facing", spec.facing.doubleValue());
         }
      }
      if (r != null && r.selected != null) {
         o.put("selected", new JSONArray().put(r.selected.world.x).put(r.selected.world.y));
         o.put("selected_side", InteractionSpec.sideName(r.selected.side));
      }
      JSONArray cands = new JSONArray();
      if (r != null) {
         int n = Math.min(48, r.considered.size());
         for (int i = 0; i < n; i++) {
            InteractionGoals.Candidate c = r.considered.get(i);
            JSONObject cj = new JSONObject()
               .put("x", c.cell.x)
               .put("y", c.cell.y)
               .put("side", InteractionSpec.sideName(c.side))
               .put("dist", c.dist)
               .put("clearance", c.clearance);
            if (c.reject != null) {
               cj.put("reject", c.reject);
            }
            cands.put(cj);
         }
      }
      o.put("candidates", cands);
      o.put("geometry_source", spec == null ? "" : spec.geometrySource);
      JSONObject tally = new JSONObject();
      java.util.Map<String, Integer> counts = r == null ? InteractionGoals.tally(null) : r.rejectCounts;
      String[] keys = new String[] {
         "body_collision",
         "target_overlap",
         "other_obstacle_overlap",
         "no_los",
         "insufficient_clearance",
         "unreachable",
         "outside_range",
         "wrong_side",
         "geometry_unavailable"
      };
      for (int i = 0; i < keys.length; i++) {
         tally.put(keys[i], counts.containsKey(keys[i]) ? counts.get(keys[i]).intValue() : 0);
      }
      o.put("reject_counts", tally);
      o.put("arrival", arrived);
      o.put("decision", decision);
      o.put("observed_result", observed);
      o.put("retry", retry);
      o.put("outcome", outcome);
      return o;
   }
}
