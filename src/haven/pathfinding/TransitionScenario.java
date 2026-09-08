package haven.pathfinding;

import auto.Bot;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Moving;
import haven.UI;
import haven.nav.GraphEdge;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;
import haven.nav.NavGoal;
import haven.nav.NavOutcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Phase 5 allowlisted transition / mobility / explore scenarios.
 * Missing fixtures fail closed. Click is not transition success.
 */
final class TransitionScenario implements PfTestRunner.Scenario {
   enum Kind {
      DOOR_GATE("transition_door_gate", GraphEdge.Kind.DOOR_GATE, false),
      CELLAR_STAIRS("transition_cellar_stairs", GraphEdge.Kind.CELLAR_STAIRS, false),
      CAVE("transition_cave", GraphEdge.Kind.CAVE, false),
      LADDER("transition_ladder", GraphEdge.Kind.LADDER, false),
      MINEHOLE("transition_minehole", GraphEdge.Kind.MINEHOLE, false),
      BOAT_BOARD("boat_board", GraphEdge.Kind.BOAT, false),
      BOAT_TRAVEL("boat_travel", GraphEdge.Kind.BOAT, false),
      BOAT_DISEMBARK("boat_disembark", GraphEdge.Kind.BOAT, true),
      VEHICLE_ENTER("vehicle_enter", GraphEdge.Kind.VEHICLE, false),
      VEHICLE_TRAVEL("vehicle_travel", GraphEdge.Kind.VEHICLE, false),
      VEHICLE_EXIT("vehicle_exit", GraphEdge.Kind.VEHICLE, true),
      HEARTH("hearth_travel", GraphEdge.Kind.HEARTH, false),
      EXPLORE("explore_frontier", GraphEdge.Kind.EXPLORE_FRONTIER, false);

      final String name;
      final GraphEdge.Kind edge;
      final boolean vehicleExit;

      Kind(String name, GraphEdge.Kind edge, boolean vehicleExit) {
         this.name = name;
         this.edge = edge;
         this.vehicleExit = vehicleExit;
      }
   }

   private final Kind kind;

   TransitionScenario(Kind kind) {
      this.kind = kind;
   }

   @Override
   public String name() {
      return this.kind.name;
   }

   @Override
   public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
      return execute(run, ui, null);
   }

   JSONObject execute(PfTestRunner.Run run, UI ui, NavGoal originalOverride) throws Exception {
      return execute(run, ui, originalOverride, -1L);
   }

   JSONObject execute(PfTestRunner.Run run, UI ui, NavGoal originalOverride, long preferGobId) throws Exception {
      return execute(run, ui, originalOverride, preferGobId, false);
   }

   JSONObject execute(PfTestRunner.Run run, UI ui, NavGoal originalOverride, long preferGobId, boolean deferGateClose)
      throws Exception {
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
         return failClosed(checks, "NO_GAME", "not in game");
      }
      GraphNode source = WorldGraphAdapter.current(gui);
      if (source == null) {
         source = GraphNode.of(WorldGraphAdapter.worldId(gui), "unknown", "unknown", "unknown");
      }
      WorldGraph graph = new WorldGraph();
      graph.observe(source);
      Gob me;
      synchronized (ui) {
         me = gui.map.player();
      }
      if (me == null || me.rc == null) {
         return failClosed(checks, "NO_GAME", "player gob missing after preflight");
      }
      NavGoal original = originalOverride != null && originalOverride.kind == NavGoal.Kind.POINT && originalOverride.position != null
         ? originalOverride
         : NavGoal.point(me.rc);
      MobilityProfile mob = WorldGraphAdapter.mobility(me);
      PathfinderLog.recordGraph(evidence(source, null, this.kind.edge, "", original, mob, mob, null, "", false));
      if (this.kind == Kind.EXPLORE) {
         return explore(run, ui, gui, checks, graph, source, original);
      }
      if (this.kind == Kind.BOAT_TRAVEL || this.kind == Kind.VEHICLE_TRAVEL) {
         return travel(checks, graph, source, original, me, mob);
      }
      if ((this.kind == Kind.BOAT_DISEMBARK || this.kind == Kind.VEHICLE_EXIT) && me.vehicleId() == 0L) {
         return failClosed(checks, "NO_FIXTURE", "not aboard");
      }
      PrototypePathfinder.Scene scene;
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
      }
      PrototypePathfinder.GobGeom gob = pick(gui, scene, preferGobId);
      gob = BuildingDoor.resolveDoor(gui, scene, gob);
      if (gob == null) {
         return failClosed(checks, "NO_FIXTURE", "no automatic fixture for " + this.kind.name);
      }
      checks.add(PfTestRunner.check("fixture", true, this.kind.name + " #" + gob.id));
      // Distant targets: stage beside the target and refresh geometry before
      // any pose selection. The gob center is never a walking destination.
      haven.nav.InteractionSpec preSpec = InteractionAdapter.fromGob(
         gob, haven.nav.InteractionSpec.ALL_SIDES, 1.0, 35.0, 1, null, InteractionVerifier.STATE_CHANGED, scene.player
      );
      if (CollisionGeom.UNAVAILABLE.equals(preSpec.geometrySource)
         || StagingPlanner.required(scene.player, scene.occupancy, preSpec)) {
         PathfinderLog.recordInteraction(InteractionStaging.phaseRecord(
            "STAGING_REQUIRED",
            InteractionTarget.of(gob, "transition:" + this.kind.edge.name(), InteractionVerifier.STATE_CHANGED, scene.terrain),
            scene.player, null, gob, 0, ""
         ));
         InteractionStaging.Result st = InteractionStaging.stage(
            run, ui, gui,
            InteractionTarget.of(gob, "transition:" + this.kind.edge.name(), InteractionVerifier.STATE_CHANGED, scene.terrain),
            (g, near) -> InteractionAdapter.fromGob(
               g, haven.nav.InteractionSpec.ALL_SIDES, 1.0, 35.0, 1, null, InteractionVerifier.STATE_CHANGED, near
            ),
            null
         );
         if (!st.staged()) {
            return failClosed(checks, st.outcome, "staging failed: " + st.detail);
         }
         scene = st.scene;
         gob = st.target;
         // Re-resolve the door now that the fixture is inside the local
         // observation window: the exact door gob position beats the hull's
         // hardcoded doorway offset, especially beside a cluttered wall.
         gob = BuildingDoor.resolveDoor(gui, scene, gob);
      }
      TransitionMachine machine = new TransitionMachine(
         original, source, Long.toString(gob.id), this.kind.edge, null, TransitionAdapter.authFor(this.kind.edge, this.kind.vehicleExit, gob.resid)
      );
      Approach ap = pickApproach(gui, scene, gob);
      PathfinderLog.recordOccupancy(scene.occupancy);
      machine.selectApproach(ap != null);
      recordTransition(
         "start", source, null, this.kind.edge, gob.resid, original, mob, mob, machine.state(), "approach_selected", false, scene.player, gob.rc
      );
      if (ap == null) {
         PathfinderLog.recordGraph(evidence(source, null, this.kind.edge, gob.resid, original, mob, mob, machine.state(), "approach_unavailable", false));
         org.json.JSONObject last = PathfinderLog.lastInteraction();
         String refusal = last != null && InteractionGoals.GEOMETRY_UNAVAILABLE.equals(last.optString("reason"))
            ? "GEOMETRY_UNAVAILABLE"
            : "NO_POSE";
         return failClosed(checks, refusal, refusal.equals("GEOMETRY_UNAVAILABLE") ? "target geometry unavailable" : "no reachable interaction pose");
      }
      checks.add(PfTestRunner.check("pose_selected", true, "stand " + PfTestHarness.pt(ap.stand)));
      List<Coord2d> route = ap.route;
      if (route.size() < 2) {
         route = new ArrayList<Coord2d>();
         route.add(scene.player);
         route.add(ap.stand);
      }
      PathfinderLog.setTarget("transition " + this.kind.name + " #" + gob.id + " FINAL>INTERACT");
      Bot bot = Bot.execute(new Bot.BotAction[0]);
      WaypointWalker.Result walk = WaypointWalker.execute(
         WaypointWalker.liveEnv(gui), bot, route, 0, 60000L, WaypointWalker.Params.DEFAULT, NamedPlaceNavigator.NOOP, ap.status, ap.stand, ap.spec
      );
      PathfinderLog.clearTarget();
      boolean arrived = walk == WaypointWalker.Result.READY_TO_INTERACT;
      checks.add(PfTestRunner.check("arrival", arrived, arrived ? "server-confirmed pose" : "walk " + walk));
      if (!arrived) {
         machine.timeout();
         PathfinderLog.dumpFailure("transition NO_ARRIVAL: walk " + walk);
         return failClosed(checks, "NO_ARRIVAL", "did not arrive at approach");
      }
      machine.arrived();
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
         me = gui.map.player();
      }
      PrototypePathfinder.GobGeom live = find(scene, gob.id);
      Coord2d liveOrigin = BuildingDoor.liveOrigin(live, ap.spec);
      boolean still = InteractionVerifier.arrivedConfirmed(!scene.moving, scene.player, ap.stand, 2.475)
         && InteractionVerifier.poseStillValid(scene.player, ap.stand, 2.475, ap.spec, liveOrigin, ap.spec.half, live != null);
      machine.revalidate(live != null, live != null, still);
      if (machine.state().phase != TransitionMachine.Phase.INTERACT) {
         PathfinderLog.recordGraph(evidence(source, null, this.kind.edge, gob.resid, original, mob, mob, machine.state(), machine.state().reason, false));
         return failClosed(checks, machine.state().reason, "revalidation refused");
      }
      Gob target;
      synchronized (ui) {
         target = gui.map.glob.oc.getgob(live.id);
      }
      if (target == null) {
         return failClosed(checks, "NO_FIXTURE", "fixture gone at interact");
      }
      int sdt0 = live.gateState;
      long veh0 = me.vehicleId();
      boolean passThroughGate = TransitionAdapter.isPassThroughGate(gob.resid);
      boolean alreadyOpen = passThroughGate
         && TransitionApproachSelector.transitionState(sdt0) == TransitionApproachSelector.TransitionState.OPEN;
      machine.interact();
      Coord2d originRc;
      synchronized (ui) {
         originRc = me == null ? null : me.rc;
      }
      AuthWait wait;
      if (alreadyOpen) {
         checks.add(PfTestRunner.check("interaction_issued", true, "gate already open"));
         pendingInteraction.resolve(gob.id);
         wait = new AuthWait(source, false, false);
      } else {
         boolean issued = pendingInteraction.begin(gob.id, System.currentTimeMillis());
         if (issued) {
            BuildingDoor.interact(gui, target, ap.spec);
            checks.add(PfTestRunner.check("interaction_issued", true, "one interaction"));
         } else {
            // A transition on this fixture is still pending (e.g. a previous
            // attempt timed out spuriously): re-clicking would queue a second
            // interaction on top of the one already resolving.
            PathfinderLog.recordTransition(
               "duplicate_interaction_suppressed",
               evidence(source, null, this.kind.edge, gob.resid, original, mob, mob, machine.state(), machine.state().reason, false)
                  .put(
                     "player",
                     scene.player == null ? JSONObject.NULL : new org.json.JSONArray().put(scene.player.x).put(scene.player.y)
                  )
                  .put("target_rc", gob.rc == null ? JSONObject.NULL : new org.json.JSONArray().put(gob.rc.x).put(gob.rc.y))
            );
            checks.add(PfTestRunner.check("interaction_issued", true, "transition already pending; click suppressed"));
         }
         wait = awaitAuth(run, ui, gui, gob.id, sdt0, veh0, source, machine.state().auth, originRc);
         if (wait.resolved) {
            pendingInteraction.resolve(gob.id);
         }
      }
      GraphNode landing = wait.node;
      if (landing != null) {
         graph.observe(landing);
      }
      MobilityProfile after;
      Coord2d landedAt;
      synchronized (ui) {
         after = WorldGraphAdapter.mobility(gui.map.player());
         Gob landed = gui.map.player();
         landedAt = landed == null ? null : landed.rc;
      }
      machine.capture(landing, after, wait.ambiguous, wait.relocated);
      if (wait.resolved && wait.relocated && landing != null) {
         // The server confirmed a new surface: drop the previous floor's cached
         // occupancy so the next leg rebuilds local geometry instead of planning
         // against stale half-loaded cells from the segment swap.
         PathfinderLog.invalidateOccupancy();
      }
      if (machine.state().phase == TransitionMachine.Phase.SUCCEEDED && landing != null) {
         graph.learn(source, landing, this.kind.edge, Long.toString(gob.id));
      }
      boolean ok = machine.state().phase == TransitionMachine.Phase.SUCCEEDED;
      checks.add(PfTestRunner.check("transition", ok, ok ? "authoritative landing" : machine.state().reason));
      if (!ok) {
         PathfinderLog.dumpFailure("transition " + this.kind.name + " failed: " + machine.state().reason);
      }
      if (ok && passThroughGate && !alreadyOpen) {
         waitProg(run, ui, gui, 800L, 30000L);
      }
      String closeErr = null;
      if (ok && passThroughGate && !deferGateClose) {
         closeErr = passThroughAndClose(run, ui, gui, gob.id, originRc);
         boolean closed = closeErr == null;
         checks.add(closed ? PfTestRunner.check("gate_closed", true, "closed behind") : PfTestHarness.skip("gate_closed", closeErr));
      }
      boolean held = original.position != null
         && original.kind == NavGoal.Kind.POINT
         && machine.state().originalGoal != null
         && machine.state().originalGoal.kind == NavGoal.Kind.POINT
         && original.position.equals(machine.state().originalGoal.position);
      checks.add(PfTestRunner.check("original_goal", held, held ? "original POINT goal preserved" : "original destination dropped"));
      PathfinderLog.recordTransition(
         "result",
         evidence(source, landing, this.kind.edge, gob.resid, original, mob, after, machine.state(), machine.state().reason, ok)
            .put("world_id", source == null ? JSONObject.NULL : source.worldId)
            .put("segment_id", source == null ? JSONObject.NULL : source.segmentId)
            .put("landing_segment_id", landing == null ? JSONObject.NULL : landing.segmentId)
            .put(
               "player",
               landedAt == null ? JSONObject.NULL : new org.json.JSONArray().put(landedAt.x).put(landedAt.y)
            )
            .put("target_rc", gob.rc == null ? JSONObject.NULL : new org.json.JSONArray().put(gob.rc.x).put(gob.rc.y))
      );
      JSONObject facts = facts(source, landing, original, machine, ok);
      if (closeErr != null) {
         facts.put("gate_closed", false);
      } else if (passThroughGate && ok && !deferGateClose) {
         facts.put("gate_closed", true);
      }
      return PfTestHarness.body(checks, ok ? "transition observed" : machine.state().reason, facts);
   }

   private JSONObject travel(List<JSONObject> checks, WorldGraph graph, GraphNode source, NavGoal original, Gob me, MobilityProfile mob) {
      boolean aboard = me.vehicleId() != 0L;
      if (!aboard) {
         return failClosed(checks, "NO_FIXTURE", "not aboard");
      }
      boolean waterOk = this.kind.edge != GraphEdge.Kind.BOAT || TerrainPolicy.waterTravelLegal(mob);
      boolean terrainOk = this.kind.edge != GraphEdge.Kind.VEHICLE || mob.cart || mob.land;
      checks.add(PfTestRunner.check("aboard", true, "vehicleId=" + me.vehicleId()));
      checks.add(PfTestRunner.check("mobility", waterOk && terrainOk, waterOk ? "policy matches vehicle" : "water forbidden"));
      checks.add(PfTestRunner.check("original_goal", original.kind == NavGoal.Kind.POINT && original.position != null, "original goal preserved"));
      PathfinderLog.recordGraph(evidence(source, source, this.kind.edge, "", original, mob, mob, null, waterOk ? "" : "mobility_unavailable", waterOk));
      JSONObject facts = new JSONObject()
         .put("refusal", waterOk ? JSONObject.NULL : "mobility_unavailable")
         .put("kind", this.kind.name())
         .put("reached", false)
         .put("source", source.toString())
         .put("original_goal", original.position == null ? JSONObject.NULL : PfTestHarness.pt(original.position));
      return PfTestHarness.body(checks, waterOk ? "travel policy confirmed" : "mobility unavailable", facts);
   }

   private JSONObject explore(
      PfTestRunner.Run run, UI ui, GameUI gui, List<JSONObject> checks, WorldGraph graph, GraphNode source, NavGoal original
   ) throws Exception {
      NamedPlaceNavigator.Location loc = NamedPlaceNavigator.liveState(gui).current();
      if (loc == null || gui.mapfile == null || gui.mapfile.file == null) {
         return failClosed(checks, "NO_FIXTURE", "no map segment for frontier");
      }
      Area bounds = NavigationTestSpotScenario.selectBounds(loc.tile);
      MapFileTileSource tiles = MapFileTileSource.of(gui.mapfile.file, loc.seg, bounds);
      Coord start = loc.tile.sub(bounds.ul);
      ExploreFrontier.Result pick = ExploreFrontier.select(tiles, start, null, 4096);
      if (!pick.picked() || pick.cell.equals(start)) {
         String why = !pick.picked() ? pick.reason : "already on the selected frontier tile";
         return failClosed(checks, pick.outcome == NavOutcome.BUDGET_EXHAUSTED ? "BUDGET_EXHAUSTED" : "NO_FIXTURE", why);
      }
      checks.add(PfTestRunner.check("frontier", true, "cell " + pick.cell + " tile " + bounds.ul.add(pick.cell)));
      checks.add(PfTestRunner.check("original_goal", original.kind == NavGoal.Kind.POINT, "original goal preserved"));
      Coord goalTile = bounds.ul.add(pick.cell);
      Bot bot = Bot.execute(new Bot.BotAction[0]);
      AtomicBoolean timedOut = new AtomicBoolean(false);
      CoarseTileNavigator.Run navRun = PfTestHarness.moveWatch(
         run, timedOut, 240000L, () -> MoveToAutoKnownLongLegScenario.liveNavigation(gui, goalTile, bounds, bot)
      );
      checks.addAll(MoveToAutoKnownLongLegScenario.outcomeChecks(navRun, timedOut.get()));
      boolean present = false;
      boolean idle = false;
      Coord2d at = null;
      synchronized (ui) {
         Gob me2 = gui.map.player();
         present = me2 != null && me2.rc != null;
         idle = me2 == null || me2.getattr(Moving.class) == null;
         at = present ? me2.rc : null;
      }
      Coord2d goalWorld = navRun.start == null ? null : NamedPlaceNavigator.worldPos(goalTile, NamedPlaceNavigator.translation(navRun.start));
      checks.add(MoveToAutoKnownLongLegScenario.arrivalCheck(navRun.reached(), present, idle, at, goalWorld));
      PathfinderLog.recordGraph(
         evidence(source, source, GraphEdge.Kind.EXPLORE_FRONTIER, "", original, MobilityProfile.land(), MobilityProfile.land(), null, pick.reason, navRun.reached())
      );
      JSONObject facts = new JSONObject()
         .put("kind", this.kind.name())
         .put("frontier", pick.cell.x + "," + pick.cell.y)
         .put("reason", pick.reason)
         .put("reached", navRun.reached())
         .put("source", source.toString())
         .put("original_goal", original.position == null ? JSONObject.NULL : PfTestHarness.pt(original.position));
      return PfTestHarness.body(checks, navRun.reached() ? "frontier arrival confirmed" : "frontier walk failed", facts);
   }

   private static Approach pickApproach(GameUI gui, PrototypePathfinder.Scene scene, PrototypePathfinder.GobGeom gob) {
      CollisionGeom geom = CollisionGeom.target(gob.obst, gob.movement);
      if (geom.unavailable() && gui != null) {
         PrototypePathfinder.Scene fresh;
         synchronized (gui.ui) {
            fresh = PrototypePathfinder.observe(gui);
         }
         PrototypePathfinder.GobGeom again = fresh == null ? null : find(fresh, gob.id);
         if (again != null) {
            gob = again;
            scene = fresh;
            geom = CollisionGeom.target(gob.obst, gob.movement);
         }
      }
      // Door-side hint must be the player's live position: BuildingDoor.target
      // picks the doorway nearest this point, and passing the target center
      // instead routes the player to the wrong side of the building.
      Coord2d near = scene == null ? null : scene.player;
      haven.nav.InteractionSpec tight = InteractionAdapter.fromGob(
         gob, haven.nav.InteractionSpec.ALL_SIDES, 1.0, 35.0, 1, null, InteractionVerifier.STATE_CHANGED, near
      );
      if (CollisionGeom.UNAVAILABLE.equals(tight.geometrySource)) {
         GeometryDump.dump(gui, scene, gob, null);
         PathfinderLog.recordInteraction(poseEvidence(null, tight, "geometry_unavailable"));
         return null;
      }
      InteractionGoals.Geometry poseGeom = sceneGeom(scene);
      InteractionGoals.Result pose = InteractionGoals.select(scene.player, tight, scene.occupancy, poseGeom);
      GeometryDump.dump(gui, scene, gob, pose);
      PathfinderLog.recordInteraction(poseEvidence(pose, tight, pose.reason));
      if (pose.ok() && pose.selected != null) {
         return Approach.fromPose(pose);
      }
      haven.nav.InteractionSpec loose = InteractionAdapter.fromGob(
         gob, haven.nav.InteractionSpec.ALL_SIDES, 0.5, 35.0, 0, null, InteractionVerifier.STATE_CHANGED, near
      );
      pose = InteractionGoals.select(scene.player, loose, scene.occupancy, poseGeom);
      GeometryDump.dump(gui, scene, gob, pose);
      PathfinderLog.recordInteraction(poseEvidence(pose, loose, pose.reason));
      if (pose.ok() && pose.selected != null) {
         return Approach.fromPose(pose);
      }
      return null;
   }

   private static JSONObject poseEvidence(InteractionGoals.Result pose, haven.nav.InteractionSpec spec, String reason) {
      JSONObject o = new JSONObject();
      if (spec != null) {
         o.put("target_id", spec.targetId);
         o.put("geometry_source", spec.geometrySource);
         o.put("footprint", new org.json.JSONArray().put(spec.origin.x).put(spec.origin.y).put(spec.half.x).put(spec.half.y));
         o.put("min_dist", spec.minDist);
         o.put("max_dist", spec.maxDist);
         o.put("required_clearance", spec.requiredClearance);
      }
      o.put("reason", reason == null ? "" : reason);
      java.util.Map<String, Integer> counts = pose == null ? InteractionGoals.tally(null) : pose.rejectCounts;
      JSONObject tally = new JSONObject();
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
         int n = counts.containsKey(keys[i]) ? counts.get(keys[i]).intValue() : 0;
         tally.put(keys[i], n);
      }
      o.put("reject_counts", tally);
      if (pose != null) {
         o.put("dominant_reject", pose.ok() ? JSONObject.NULL : pose.dominantReject());
         o.put("candidate_count", pose.considered.size());
         JSONObject samples = new JSONObject();
         for (int i = 0; i < keys.length; i++) {
            org.json.JSONArray arr = new org.json.JSONArray();
            List<Coord2d> pts = pose.rejectSamples.get(keys[i]);
            if (pts != null) {
               for (int p = 0; p < pts.size(); p++) {
                  Coord2d pt = pts.get(p);
                  arr.put(new org.json.JSONArray().put(pt.x).put(pt.y));
               }
            }
            samples.put(keys[i], arr);
         }
         o.put("reject_samples", samples);
         if (pose.selected != null) {
            o.put("selected", new org.json.JSONArray().put(pose.selected.world.x).put(pose.selected.world.y));
            o.put("selected_side", haven.nav.InteractionSpec.sideName(pose.selected.side));
            o.put("route_cost", pose.selected.routeCost);
         }
      }
      return o;
   }

   private static final class Approach {
      final haven.nav.InteractionSpec spec;
      final Coord2d stand;
      final List<Coord2d> route;
      final haven.nav.NavPlanStatus status;

      Approach(haven.nav.InteractionSpec spec, Coord2d stand, List<Coord2d> route, haven.nav.NavPlanStatus status) {
         this.spec = spec;
         this.stand = stand;
         this.route = route == null ? new ArrayList<Coord2d>() : route;
         this.status = status == null ? haven.nav.NavPlanStatus.REACHED : status;
      }

      static Approach fromPose(InteractionGoals.Result pose) {
         List<Coord2d> route = pose.plan.smoothedRoute;
         return new Approach(pose.spec, pose.selected.world, route, pose.plan.status);
      }
   }

   private PrototypePathfinder.GobGeom pick(GameUI gui, PrototypePathfinder.Scene scene, long preferId) {
      if (preferId >= 0L) {
         PrototypePathfinder.GobGeom named = find(scene, preferId);
         if (named == null) {
            named = PrototypePathfinder.gobGeom(gui, preferId);
         }
         if (named != null && BuildingDoor.isHouseHull(named.resid) && this.kind == Kind.DOOR_GATE) {
            PrototypePathfinder.GobGeom door = BuildingDoor.nearestDoor(scene, named);
            if (door != null) {
               named = door;
            }
         }
         if (named != null && matches(named)) {
            return named;
         }
         if (named != null && BuildingDoor.isHouseHull(named.resid) && this.kind == Kind.DOOR_GATE) {
            return named;
         }
      }
      return pick(scene);
   }

   private PrototypePathfinder.GobGeom pick(PrototypePathfinder.Scene scene) {
      PrototypePathfinder.GobGeom found = null;
      for (PrototypePathfinder.GobGeom g : scene.gobs) {
         if (!matches(g)) {
            continue;
         }
         if (found != null) {
            return null;
         }
         found = g;
      }
      return found;
   }

   private boolean matches(PrototypePathfinder.GobGeom g) {
      GraphEdge.Kind k = TransitionAdapter.kind(g.resid);
      if (this.kind == Kind.DOOR_GATE) {
         return k == GraphEdge.Kind.DOOR_GATE;
      }
      if (this.kind == Kind.CELLAR_STAIRS) {
         return k == GraphEdge.Kind.CELLAR_STAIRS;
      }
      if (this.kind == Kind.CAVE) {
         return k == GraphEdge.Kind.CAVE;
      }
      if (this.kind == Kind.LADDER) {
         return k == GraphEdge.Kind.LADDER;
      }
      if (this.kind == Kind.MINEHOLE) {
         return k == GraphEdge.Kind.MINEHOLE;
      }
      if (this.kind == Kind.BOAT_BOARD || this.kind == Kind.BOAT_DISEMBARK) {
         return k == GraphEdge.Kind.BOAT;
      }
      if (this.kind == Kind.VEHICLE_ENTER || this.kind == Kind.VEHICLE_EXIT) {
         return k == GraphEdge.Kind.VEHICLE;
      }
      if (this.kind == Kind.HEARTH) {
         return k == GraphEdge.Kind.HEARTH;
      }
      return false;
   }

   private static InteractionGoals.Geometry sceneGeom(PrototypePathfinder.Scene scene) {
      if (scene == null) {
         return null;
      }
      return new InteractionGoals.Geometry(scene.solids, scene.playerBody);
   }

   private static PrototypePathfinder.GobGeom find(PrototypePathfinder.Scene scene, long id) {
      for (PrototypePathfinder.GobGeom g : scene.gobs) {
         if (g.id == id) {
            return g;
         }
      }
      return null;
   }

   private AuthWait awaitAuth(
      PfTestRunner.Run run,
      UI ui,
      GameUI gui,
      long sourceId,
      int sdt0,
      long veh0,
      GraphNode source,
      TransitionMachine.Auth auth,
      Coord2d originRc
   ) throws InterruptedException {
      long deadline = System.currentTimeMillis() + (auth == TransitionMachine.Auth.STATE ? 8000L : 30000L);
      GraphNode last = source;
      int goneTicks = 0;
      boolean sawFloorChange = false;
      boolean sawTeleport = false;
      for (; System.currentTimeMillis() < deadline; Thread.sleep(100L)) {
         if (run != null && run.cancelled) {
            return new AuthWait(null, false, false, false);
         }
         GraphNode now;
         int sdt = sdt0;
         long veh = veh0;
         boolean present = false;
         boolean idle = false;
         Coord2d rc = null;
         synchronized (ui) {
            Gob me = gui.map.player();
            veh = me == null ? 0L : me.vehicleId();
            idle = me == null || me.getattr(Moving.class) == null;
            rc = me == null ? null : me.rc;
            now = WorldGraphAdapter.current(gui);
            PrototypePathfinder.Scene scene = PrototypePathfinder.observe(gui);
            PrototypePathfinder.GobGeom g = find(scene, sourceId);
            present = g != null;
            if (g != null) {
               sdt = g.gateState;
            }
         }
         if (!present && idle) {
            goneTicks++;
         } else {
            goneTicks = 0;
         }
         if (now == null) {
            continue;
         }
         last = now;
         boolean floorChanged = TransitionAdapter.topologyChanged(source, now);
         boolean teleported = originRc != null && rc != null && originRc.dist(rc) > 55.0;
         sawFloorChange |= floorChanged;
         sawTeleport |= teleported;
         // Only authoritative signals (segment/surface identity change or a large
         // displacement) may declare the transition complete. A vanished fixture
         // gob (goneTicks) is NOT proof: during cellar→stairs the stairs gob is
         // unobserved before the new segment is reported, and treating that as
         // success used to plan the next leg against stale, half-loaded geometry.
         boolean relocated = authoritativeRelocated(floorChanged, teleported, goneTicks >= 5, auth);
         if (auth == TransitionMachine.Auth.STATE && (TransitionAdapter.doorStateChanged(sdt0, sdt) || relocated)) {
            return new AuthWait(now, false, relocated, true);
         }
         if (auth == TransitionMachine.Auth.TOPOLOGY && idle && relocated) {
            return new AuthWait(now, false, true, true);
         }
         if (auth == TransitionMachine.Auth.VEHICLE_ENTER && TransitionAdapter.boarded(veh0, veh)) {
            return new AuthWait(now, false, true, true);
         }
         if (auth == TransitionMachine.Auth.VEHICLE_EXIT && TransitionAdapter.disembarked(veh0, veh)) {
            return new AuthWait(now, false, true, true);
         }
      }
      // Fail-closed timeout: only an authoritative signal observed during the
      // window counts. A fixture that merely vanished, or a graph node that was
      // null across the segment swap, must NOT report relocated=true.
      boolean authoritative = authoritativeRelocated(sawFloorChange, sawTeleport, goneTicks >= 5, auth);
      if (auth == TransitionMachine.Auth.TOPOLOGY || auth == TransitionMachine.Auth.STATE) {
         GraphNode node = last.equals(source) && !authoritative ? null : last;
         return new AuthWait(node, false, authoritative && node != null, authoritative && node != null);
      }
      return new AuthWait(null, false, false, false);
   }

   /**
    * Pure decision for transition completeness: only a floor/segment change or a
    * teleport-sized displacement is authoritative. {@code goneTicksSeen} is
    * deliberately ignored for every auth kind — an unobserved fixture gob also
    * happens while the new segment loads, and must never complete the wait on
    * its own. {@code auth} is part of the signature so callers state intent per
    * transition kind (the rule is currently uniform); it is asserted in tests.
    */
   static boolean authoritativeRelocated(boolean floorChanged, boolean teleported, boolean goneTicksSeen, TransitionMachine.Auth auth) {
      return floorChanged || teleported;
   }

   /** Diagnostic transition record with explicit surface identity and player/target positions. */
   private static void recordTransition(
      String stage,
      GraphNode source,
      GraphNode landing,
      GraphEdge.Kind kind,
      String fixture,
      NavGoal original,
      MobilityProfile before,
      MobilityProfile after,
      TransitionMachine.State st,
      String reason,
      boolean learned,
      Coord2d playerAt,
      Coord2d targetAt
   ) {
      JSONObject o = evidence(source, landing, kind, fixture, original, before, after, st, reason, learned);
      o.put("world_id", source == null ? JSONObject.NULL : source.worldId);
      o.put("segment_id", source == null ? JSONObject.NULL : source.segmentId);
      o.put("landing_segment_id", landing == null ? JSONObject.NULL : landing.segmentId);
      o.put("player", playerAt == null ? JSONObject.NULL : new org.json.JSONArray().put(playerAt.x).put(playerAt.y));
      o.put("target_rc", targetAt == null ? JSONObject.NULL : new org.json.JSONArray().put(targetAt.x).put(targetAt.y));
      PathfinderLog.recordTransition(stage, o);
   }

   static String passThroughAndClose(PfTestRunner.Run run, UI ui, GameUI gui, long gobId, Coord2d originRc)
      throws Exception {
      waitIdle(run, ui, gui);
      Thread.sleep(150L);
      Coord2d gateAt = null;
      Coord2d at = null;
      synchronized (ui) {
         Gob me = gui.map.player();
         Gob g = gui.map.glob.oc.getgob(gobId);
         at = me == null ? null : me.rc;
         gateAt = g == null ? null : g.rc;
      }
      if (gateAt == null || at == null) {
         return "gate gone after open";
      }
      Coord2d from = originRc != null ? originRc : at;
      if (!TransitionAdapter.pastGate(from, gateAt, at)) {
         Coord2d far = TransitionAdapter.gateThroughPoint(from, gateAt, 12.0);
         String walkErr = walkThrough(run, ui, gui, far);
         if (walkErr != null) {
            return walkErr;
         }
         synchronized (ui) {
            Gob me = gui.map.player();
            at = me == null ? null : me.rc;
         }
         if (!TransitionAdapter.pastGate(from, gateAt, at)) {
            return "did not pass through gate";
         }
      }
      return closeGate(run, ui, gui, gobId);
   }

   private static String walkThrough(PfTestRunner.Run run, UI ui, GameUI gui, Coord2d far) throws Exception {
      waitIdle(run, ui, gui);
      Coord2d at = PfTestHarness.observePos(gui);
      if (at != null && far != null && at.dist(far) <= 3.0) {
         return null;
      }
      PrototypePathfinder.Plan plan = PrototypePathfinder.planAny(gui, Collections.singletonList(far), true);
      if (plan == null || plan.status != PrototypePathfinder.Plan.Status.REACHED || plan.waypoints == null || plan.waypoints.size() < 2) {
         Thread.sleep(250L);
         plan = PrototypePathfinder.planAny(gui, Collections.singletonList(far), true);
      }
      if (plan == null || plan.status != PrototypePathfinder.Plan.Status.REACHED || plan.waypoints == null || plan.waypoints.size() < 2) {
         plan = PrototypePathfinder.Plan.direct(at, far);
      }
      Bot bot = Bot.execute(new Bot.BotAction[0]);
      MoveToAutoOpenGroundScenario.MoveResult mv = MoveToAutoOpenGroundScenario.walkPlan(gui, plan, far, bot, 15000L, 15000L);
      if (mv != null && (mv.walk == WaypointWalker.Result.STUCK || mv.walk == WaypointWalker.Result.TIMEOUT)) {
         return "stuck walking through gate";
      }
      waitIdle(run, ui, gui);
      return null;
   }

   static String closeGate(PfTestRunner.Run run, UI ui, GameUI gui, long gobId) throws Exception {
      waitIdle(run, ui, gui);
      Gob target;
      int sdt;
      Coord2d at;
      Coord2d gateAt;
      synchronized (ui) {
         Gob me = gui.map.player();
         at = me == null ? null : me.rc;
         target = gui.map.glob.oc.getgob(gobId);
         gateAt = target == null ? null : target.rc;
         sdt = target == null ? -1 : target.sdt();
      }
      if (target == null || gateAt == null) {
         return "gate gone before close";
      }
      if (TransitionApproachSelector.transitionState(sdt) == TransitionApproachSelector.TransitionState.CLOSED) {
         return null;
      }
      if (at == null || at.dist(gateAt) > 35.0) {
         return "too far to close gate";
      }
      int sdt0 = sdt;
      target.rclick(0);
      waitProg(run, ui, gui, 1500L, 30000L);
      long deadline = System.currentTimeMillis() + 4000L;
      for (; System.currentTimeMillis() < deadline; Thread.sleep(100L)) {
         if (run != null && run.cancelled) {
            throw new PfTestRunner.Cancelled();
         }
         synchronized (ui) {
            Gob g = gui.map.glob.oc.getgob(gobId);
            sdt = g == null ? -1 : g.sdt();
         }
         if (TransitionApproachSelector.transitionState(sdt) == TransitionApproachSelector.TransitionState.CLOSED) {
            waitIdle(run, ui, gui);
            return null;
         }
         if (sdt != sdt0 && TransitionApproachSelector.transitionState(sdt) != TransitionApproachSelector.TransitionState.OPEN) {
            waitIdle(run, ui, gui);
            return null;
         }
      }
      return "gate did not close";
   }

   /** Mirror of {@link #closeGate}: right-click the gate and wait for it to read OPEN. */
   static String openGate(PfTestRunner.Run run, UI ui, GameUI gui, long gobId) throws Exception {
      waitIdle(run, ui, gui);
      Gob target;
      int sdt;
      Coord2d at;
      synchronized (ui) {
         Gob me = gui.map.player();
         at = me == null ? null : me.rc;
         target = gui.map.glob.oc.getgob(gobId);
         sdt = target == null ? -1 : target.sdt();
      }
      if (target == null || target.rc == null) {
         return "gate gone before open";
      }
      if (TransitionApproachSelector.transitionState(sdt) == TransitionApproachSelector.TransitionState.OPEN) {
         return null;
      }
      if (at == null || at.dist(target.rc) > 35.0) {
         return "too far to open gate";
      }
      target.rclick(0);
      waitProg(run, ui, gui, 1500L, 30000L);
      long deadline = System.currentTimeMillis() + 4000L;
      for (; System.currentTimeMillis() < deadline; Thread.sleep(100L)) {
         if (run != null && run.cancelled) {
            throw new PfTestRunner.Cancelled();
         }
         synchronized (ui) {
            Gob g = gui.map.glob.oc.getgob(gobId);
            sdt = g == null ? -1 : g.sdt();
         }
         if (TransitionApproachSelector.transitionState(sdt) == TransitionApproachSelector.TransitionState.OPEN) {
            waitIdle(run, ui, gui);
            return null;
         }
      }
      return "gate did not open";
   }

   static void waitIdle(PfTestRunner.Run run, UI ui, GameUI gui) throws InterruptedException, PfTestRunner.Cancelled {
      long deadline = System.currentTimeMillis() + 8000L;
      while (System.currentTimeMillis() < deadline) {
         if (run != null && run.cancelled) {
            throw new PfTestRunner.Cancelled();
         }
         boolean idle;
         boolean prog;
         synchronized (ui) {
            Gob me = gui.map.player();
            idle = me == null || me.getattr(Moving.class) == null;
            prog = gui.prog != null;
         }
         if (idle && !prog && !Bot.hasCurrent()) {
            return;
         }
         Thread.sleep(50L);
      }
   }

   static void waitProg(PfTestRunner.Run run, UI ui, GameUI gui, long appearMs, long finishMs)
      throws InterruptedException, PfTestRunner.Cancelled {
      long appearUntil = System.currentTimeMillis() + appearMs;
      boolean saw = false;
      while (System.currentTimeMillis() < appearUntil) {
         if (run != null && run.cancelled) {
            throw new PfTestRunner.Cancelled();
         }
         synchronized (ui) {
            saw = gui.prog != null;
         }
         if (saw) {
            break;
         }
         Thread.sleep(50L);
      }
      if (!saw) {
         return;
      }
      long finishUntil = System.currentTimeMillis() + finishMs;
      while (System.currentTimeMillis() < finishUntil) {
         if (run != null && run.cancelled) {
            throw new PfTestRunner.Cancelled();
         }
         boolean prog;
         synchronized (ui) {
            prog = gui.prog != null;
         }
         if (!prog) {
            return;
         }
         Thread.sleep(50L);
      }
   }

   private static JSONObject failClosed(List<JSONObject> checks, String refusal, String note) {
      checks.add(PfTestRunner.check("fixture", false, note));
      JSONObject facts = new JSONObject().put("refusal", refusal).put("selected", false).put("reached", false);
      return PfTestHarness.body(checks, note, facts);
   }

   private JSONObject facts(GraphNode source, GraphNode landing, NavGoal original, TransitionMachine machine, boolean ok) {
      JSONObject f = new JSONObject();
      f.put("kind", this.kind.name());
      f.put("source", source == null ? JSONObject.NULL : source.toString());
      f.put("landing", landing == null ? JSONObject.NULL : landing.toString());
      f.put("reason", machine.state().reason);
      f.put("phase", machine.state().phase.name());
      f.put("reached", false);
      f.put("original_goal", original.position == null ? JSONObject.NULL : PfTestHarness.pt(original.position));
      f.put("ok", ok);
      return f;
   }

   static JSONObject evidence(
      GraphNode source,
      GraphNode landing,
      GraphEdge.Kind kind,
      String fixture,
      NavGoal original,
      MobilityProfile before,
      MobilityProfile after,
      TransitionMachine.State st,
      String reason,
      boolean learned
   ) {
      JSONObject o = new JSONObject();
      o.put("source", source == null ? JSONObject.NULL : source.toString());
      o.put("landing", landing == null ? JSONObject.NULL : landing.toString());
      o.put("transition_type", kind == null ? JSONObject.NULL : kind.name());
      o.put("fixture", fixture == null ? "" : fixture);
      o.put("reason", reason == null ? "" : reason);
      o.put("learned", learned);
      o.put("reached", false);
      if (original != null) {
         o.put("original_goal_kind", original.kind.name());
      }
      if (before != null) {
         o.put("mobility_before", before.boat ? "boat" : before.cart ? "cart" : before.swim ? "swim" : "land");
      }
      if (after != null) {
         o.put("mobility_after", after.boat ? "boat" : after.cart ? "cart" : after.swim ? "swim" : "land");
      }
      if (st != null) {
         o.put("phase", st.phase.name());
         o.put("retries", st.retries);
      }
      return o;
   }

   private static final long PENDING_STALE_MS = 45000L;

   /**
    * At most one interaction click per fixture while a transition is pending:
    * a re-entered execute() for the same fixture must not queue a second
    * interaction click before the previous one resolved. The marker is cleared
    * on authoritative resolution and goes stale after the auth window plus
    * slack, so a genuinely lost click can be retried later.
    */
   static final class PendingInteraction {
      private long fixtureId = -1L;
      private long sinceMs;

      synchronized boolean begin(long fixtureId, long nowMs) {
         if (this.fixtureId == fixtureId && nowMs - this.sinceMs < PENDING_STALE_MS) {
            return false;
         }
         this.fixtureId = fixtureId;
         this.sinceMs = nowMs;
         return true;
      }

      synchronized void resolve(long fixtureId) {
         if (this.fixtureId == fixtureId) {
            this.fixtureId = -1L;
            this.sinceMs = 0L;
         }
      }

      synchronized boolean pending(long fixtureId) {
         return this.fixtureId == fixtureId;
      }
   }

   private static final PendingInteraction pendingInteraction = new PendingInteraction();

   private static final class AuthWait {
      final GraphNode node;
      final boolean ambiguous;
      final boolean relocated;
      /** True when an authoritative signal (topology, door state, vehicle) resolved the wait. */
      final boolean resolved;

      AuthWait(GraphNode node, boolean ambiguous) {
         this(node, ambiguous, false, false);
      }

      AuthWait(GraphNode node, boolean ambiguous, boolean relocated) {
         this(node, ambiguous, relocated, false);
      }

      AuthWait(GraphNode node, boolean ambiguous, boolean relocated, boolean resolved) {
         this.node = node;
         this.ambiguous = ambiguous;
         this.relocated = relocated;
         this.resolved = resolved;
      }
   }
}
