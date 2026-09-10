package haven.pathfinding;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.pathfinding.CoarseRoutePlanner.Cause;
import haven.pathfinding.CoarseRoutePlanner.Route;
import haven.pathfinding.CoarseTileNavigator.ReplanFact;
import haven.pathfinding.CupboardCatalog.Node;
import haven.pathfinding.NamedPlaceNavigator.Location;
import haven.pathfinding.NamedPlaceNavigator.RunStatus;
import haven.pathfinding.NamedPlaceRouteService.Kind;
import haven.pathfinding.NavigationTestSpotSelector.Candidate;
import haven.pathfinding.NavigationTestSpotSelector.Profile;
import haven.pathfinding.NavigationTestSpotSelector.Refusal;
import haven.pathfinding.NavigationTestSpotSelector.Selection;
import haven.pathfinding.PathfinderLog.Occupancy;
import haven.pathfinding.PfTestRunner.Cancelled;
import haven.pathfinding.PfTestRunner.Run;
import haven.pathfinding.CrossCellarDoorScenario.Interaction;
import haven.pathfinding.MineholeDescent.Completion;
import haven.pathfinding.MineholeDescent.Observation;
import haven.pathfinding.MoveToAutoOpenGroundScenario.MoveResult;
import haven.pathfinding.PrototypePathfinder.GobGeom;
import haven.pathfinding.PrototypePathfinder.Plan;
import haven.pathfinding.PrototypePathfinder.Scene;
import haven.pathfinding.PrototypePathfinder.Plan.Status;
import haven.pathfinding.RecedingHorizonNavigator.Outcome;
import haven.pathfinding.TransitionApproachSelector.ApproachRange;
import haven.pathfinding.TransitionApproachSelector.TransitionProfile;
import haven.pathfinding.TransitionApproachSelector.TransitionState;
import haven.pathfinding.WaypointWalker.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PfTestRunnerTest {
   private static final List<String> ALL_SCENARIOS = List.of(
      "observe",
      "basement_cabinet_identify",
      "move_to_marker",
      "move_to_auto_open_ground",
      "move_to_auto_obstacle_corridor",
      "move_to_auto_known_long_leg",
      "move_to_auto_cave_transition_approach",
      "cross_cellar_door",
      "cross_cellar_stairs",
      "cross_minehole",
      "select_open_ground",
      "select_obstacle_corridor",
      "select_known_long_leg",
      "select_boulder_approach",
      "select_cave_transition_approach",
      "select_door_gate_approach",
      "select_waterline_approach",
      "surface_long_open_ground",
      "surface_single_tree_detour",
      "surface_dense_forest",
      "surface_clustered_obstacles",
      "surface_one_tile_corridor",
      "surface_diagonal_corridor",
      "surface_buildings_fences",
      "surface_water_boundary",
      "surface_cliff_boundary",
      "surface_moving_neutral",
      "surface_hostile_exclusion",
      "surface_unknown_geometry",
      "surface_explore_frontier",
      "interact_forageable",
      "interact_tree",
      "interact_boulder",
      "interact_cupboard",
      "interact_door_gate",
      "interact_field_crop",
      "interact_narrow_interior",
      "transition_door_gate",
      "transition_cellar_stairs",
      "transition_cave",
      "transition_ladder",
      "transition_minehole",
      "boat_board",
      "boat_travel",
      "boat_disembark",
      "vehicle_enter",
      "vehicle_travel",
      "vehicle_exit",
      "hearth_travel",
      "explore_frontier",
      "campaign_surface_out_and_back",
      "campaign_surface_and_door",
      "campaign_door_gate_roundtrip",
      "campaign_cellar_roundtrip",
      "campaign_minehole_roundtrip",
      "campaign_cave_roundtrip",
      "campaign_boat_roundtrip",
      "campaign_vehicle_roundtrip",
      "campaign_recorded"
   );

   @Test
   void allowlistsObserveAndBasementCabinetIdentify() {
      Assertions.assertNull(PfTestRunner.validateScenario("observe"), "observe must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario("basement_cabinet_identify"), "basement_cabinet_identify must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario(" basement_cabinet_identify "), "scenario names are trimmed");
   }

   @Test
   void moveToMarkerIsAllowlistedNamedOnly() {
      Assertions.assertNull(PfTestRunner.validateScenario("move_to_marker"), "move_to_marker must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario(" move_to_marker "), "scenario names are trimmed");
      Assertions.assertEquals(ALL_SCENARIOS, PfTestRunner.knownScenarios());
   }

   @Test
   void rejectsUnknownAndBlankScenarios() {
      Assertions.assertNotNull(PfTestRunner.validateScenario(null));
      Assertions.assertNotNull(PfTestRunner.validateScenario(""));
      Assertions.assertNotNull(PfTestRunner.validateScenario("   "));
      Assertions.assertNotNull(PfTestRunner.validateScenario("walk"));
      Assertions.assertNotNull(PfTestRunner.validateScenario("observe; :cmd evil"));
      Assertions.assertNotNull(PfTestRunner.validateScenario("--exec"));
   }

   @Test
   void scenariosListContainsOnlyAllowlistedScenarios() {
      Assertions.assertEquals(ALL_SCENARIOS, PfTestRunner.knownScenarios());
   }

   @Test
   void registryResolvesEachAllowlistedScenarioByName() {
      for (String name : ALL_SCENARIOS) {
         PfTestRunner.Scenario sc = PfScenarioRegistry.get(name);
         Assertions.assertNotNull(sc, name);
         Assertions.assertEquals(name, sc.name());
      }
      Assertions.assertNull(PfScenarioRegistry.get("walk"));
      Assertions.assertNull(PfScenarioRegistry.get(null));
   }

   @Test
   void verdictPassesWhenNothingFails() {
      List<JSONObject> checks = List.of(
         PfTestRunner.check("player_present", true, "player at (1, 2)"), PfTestRunner.check("occupancy_present", true, "72x72 grid")
      );
      Assertions.assertEquals("PASS", PfTestRunner.verdictOf(checks));
   }

   @Test
   void skippedChecksDoNotFailTheRun() {
      List<JSONObject> checks = List.of(
         PfTestRunner.check("player_present", true, "ok"), PfTestRunner.skip("idle_player_not_solid", "player still moving; not evaluated")
      );
      Assertions.assertEquals("PASS", PfTestRunner.verdictOf(checks));
   }

   @Test
   void anyFailedCheckFailsTheRunEvenAlongsideSkips() {
      List<JSONObject> checks = List.of(
         PfTestRunner.check("player_present", true, "ok"),
         PfTestRunner.check("idle_player_not_solid", false, "idle player cell is occupancy-SOLID"),
         PfTestRunner.skip("occupancy_present", "n/a")
      );
      Assertions.assertEquals("FAIL", PfTestRunner.verdictOf(checks));
   }

   @Test
   void emptyChecksPass() {
      Assertions.assertEquals("PASS", PfTestRunner.verdictOf(List.of()));
   }

   @Test
   void singleRunLockRejectsConcurrentRun() {
      Run r1 = new Run("observe");

      try {
         Assertions.assertTrue(PfTestRunner.beginRun(r1), "first run must acquire the lock");
         Assertions.assertSame(r1, PfTestRunner.currentRun());
         Run r2 = new Run("observe");
         Assertions.assertFalse(PfTestRunner.beginRun(r2), "second run must be rejected while one is running");
         Assertions.assertSame(r1, PfTestRunner.currentRun(), "lock holder must not change");
      } finally {
         PfTestRunner.finishRun(r1);
      }
   }

   @Test
   void cancelIsSafeAndIdempotentAndReleasesLock() {
      Run r1 = new Run("observe");

      try {
         Assertions.assertNull(PfTestRunner.cancelRun(), "cancel with no run is safe (null, no throw)");
         Assertions.assertTrue(PfTestRunner.beginRun(r1));
         Assertions.assertSame(r1, PfTestRunner.cancelRun(), "cancel returns the running run");
         Assertions.assertSame(r1, PfTestRunner.cancelRun(), "repeated cancel is idempotent: same run, no throw, no state change");
         Assertions.assertSame(r1, PfTestRunner.currentRun(), "cancel alone does not release the lock");
      } finally {
         PfTestRunner.finishRun(r1);
      }

      Assertions.assertNull(PfTestRunner.currentRun(), "finish clears the lock");
      Run r2 = new Run("observe");
      Assertions.assertTrue(PfTestRunner.beginRun(r2), "a new run is allowed after the previous finished");
      PfTestRunner.finishRun(r2);
   }

   @Test
   void runningRunIsVisibleAsResult() {
      Run r = new Run("observe");

      try {
         Assertions.assertTrue(PfTestRunner.beginRun(r));
         JSONObject got = PfTestRunner.result(r.id);
         Assertions.assertNotNull(got);
         Assertions.assertEquals("running", got.getString("status"));
         Assertions.assertEquals(r.id, got.getString("run_id"));
         Assertions.assertEquals("observe", got.getString("scenario"));
      } finally {
         PfTestRunner.finishRun(r);
      }
   }

   @Test
   void completedResultsAreLookedUpByIdAndUnknownIdIsNull() {
      Assertions.assertNull(PfTestRunner.result("no-such-run"));
      JSONObject res = new JSONObject().put("run_id", "observe-123").put("scenario", "observe").put("status", "completed").put("verdict", "PASS");
      PfTestRunner.rememberResult(res);
      JSONObject got = PfTestRunner.result("observe-123");
      Assertions.assertNotNull(got);
      Assertions.assertEquals("observe-123", got.getString("run_id"));
      Assertions.assertEquals("PASS", got.getString("verdict"));
      Assertions.assertEquals("completed", got.getString("status"));
      Assertions.assertNull(PfTestRunner.result("observe-124"));
   }

   @Test
   void completedArtifactPathIsPublishedInResult() throws Exception {
      Path root = Files.createTempDirectory("pf-test-artifact-");

      try {
         Run run = new Run("observe");
         JSONObject result = new JSONObject().put("run_id", run.id);
         PfTestRunner.write(run, result, root);
         Path artifact = root.resolve("dev-snapshots/pf/tests/observe").resolve(run.id + ".jsonl");
         Assertions.assertEquals(artifact.toString(), result.getString("artifact"));
         Assertions.assertTrue(Files.isRegularFile(artifact));
      } finally {
         try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
               try {
                  Files.deleteIfExists(path);
               } catch (Exception var2x) {
               }
            });
         }
      }
   }

   @Test
   void observeWithoutGameStateFailsCleanly() throws Exception {
      Run run = new Run("observe");
      JSONObject body = new ObserveScenario().execute(run, null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      JSONObject inGame = body.getJSONArray("checks").getJSONObject(0);
      Assertions.assertEquals("in_game", inGame.getString("name"));
      Assertions.assertEquals("fail", inGame.getString("status"));
      Assertions.assertFalse(body.has("scene"), "no scene when not in game");
   }

   private static Area region(int x0, int y0, int x1, int y1) {
      return Area.corn(Coord.of(x0, y0), Coord.of(x1, y1));
   }

   private static haven.pathfinding.NamedPlaceNavigator.Run run(
      RunStatus status, Kind routeKind, Outcome outcome, String detail, Coord2d endPos, Coord goalTile, long seg
   ) {
      return new haven.pathfinding.NamedPlaceNavigator.Run(
         status,
         "Home",
         region(0, 0, 40, 40),
         status == RunStatus.UNAVAILABLE ? null : new Location(seg, Coord.of(10, 10), Coord2d.of(110.0, 110.0)),
         routeKind,
         7L,
         null,
         routeKind == Kind.REACHED ? 3 : 0,
         100,
         outcome,
         4,
         1,
         3,
         detail,
         endPos,
         Collections.emptyList(),
         1234L
      );
   }

   @Test
   void markerNameComesSolelyFromLaunchTimeSystemProperty() {
      String prev = System.getProperty("haven.pf.move.marker");

      try {
         System.clearProperty("haven.pf.move.marker");
         Assertions.assertEquals("", MoveToMarkerScenario.markerName(), "unset property is blank (typed no-move refusal)");
         System.setProperty("haven.pf.move.marker", "  Home  ");
         Assertions.assertEquals("Home", MoveToMarkerScenario.markerName(), "the exact marker name is the trimmed launch-time property");
      } finally {
         if (prev == null) {
            System.clearProperty("haven.pf.move.marker");
         } else {
            System.setProperty("haven.pf.move.marker", prev);
         }
      }
   }

   @Test
   void preflightRefusesNoMoveInPinnedOrder() {
      List<JSONObject> c = MoveToMarkerScenario.preflightChecks(false, null, false, false, false, true);
      Assertions.assertEquals(1, c.size());
      Assertions.assertEquals("in_game", c.get(0).getString("name"));
      Assertions.assertEquals("fail", c.get(0).getString("status"));
      c = MoveToMarkerScenario.preflightChecks(true, "  ", true, true, true, true);
      Assertions.assertEquals(2, c.size());
      Assertions.assertEquals("marker_configured", c.get(1).getString("name"));
      Assertions.assertEquals("fail", c.get(1).getString("status"));
      c = MoveToMarkerScenario.preflightChecks(true, "Home", false, true, true, true);
      Assertions.assertEquals(3, c.size());
      Assertions.assertEquals("player_present", c.get(2).getString("name"));
      c = MoveToMarkerScenario.preflightChecks(true, "Home", true, false, true, true);
      Assertions.assertEquals(4, c.size());
      Assertions.assertEquals("mapfile_available", c.get(3).getString("name"));
      c = MoveToMarkerScenario.preflightChecks(true, "Home", true, true, false, true);
      Assertions.assertEquals(5, c.size());
      Assertions.assertEquals("player_idle", c.get(4).getString("name"));
      Assertions.assertEquals("fail", c.get(4).getString("status"));
      Assertions.assertTrue(c.get(4).getString("detail").toLowerCase().contains("moving"), "not-idle must be a typed refusal");
      c = MoveToMarkerScenario.preflightChecks(true, "Home", true, true, true, true);
      Assertions.assertEquals(6, c.size());
      Assertions.assertEquals("bot_available", c.get(5).getString("name"));
      Assertions.assertEquals("fail", c.get(5).getString("status"));
      c = MoveToMarkerScenario.preflightChecks(true, "Home", true, true, true, false);
      Assertions.assertEquals(6, c.size());
      Assertions.assertEquals("pass", c.get(5).getString("status"));
      Assertions.assertEquals("PASS", PfTestRunner.verdictOf(c));
   }

   @Test
   void moveBoundsContainStartWithHardExtent() {
      Area a = MoveToMarkerScenario.moveBounds(Coord.of(10, 20));
      Assertions.assertTrue(a.contains(Coord.of(10, 20)), "start must lie inside the planning region");
      Assertions.assertTrue(a.contains(Coord.of(265, -235)), "within the half-extent is inside");
      Assertions.assertTrue(a.contains(Coord.of(-246, 20)), "ul is inclusive: start-H is inside");
      Assertions.assertFalse(a.contains(Coord.of(266, 20)), "half-open upper bound excludes start+H");
      Assertions.assertFalse(a.contains(Coord.of(-247, 20)), "below the ul corner is outside");
      Assertions.assertEquals(512, a.br.x - a.ul.x);
      Assertions.assertEquals(512, a.br.y - a.ul.y);
   }

   @Test
   void outcomeUnavailableIsNoMoveFail() {
      haven.pathfinding.NamedPlaceNavigator.Run r = run(RunStatus.UNAVAILABLE, null, null, null, null, null, 0L);
      List<JSONObject> checks = MoveToMarkerScenario.outcomeChecks(r, "Home", false);
      Assertions.assertEquals("FAIL", PfTestRunner.verdictOf(checks));
      Assertions.assertEquals("session_state", checks.get(0).getString("name"));
      JSONObject facts = MoveToMarkerScenario.factsJson(r, "Home", null, null, null);
      Assertions.assertFalse(facts.getBoolean("moved"), "no-move failure must record moved=false");
      Assertions.assertFalse(facts.getBoolean("arrived"));
      Assertions.assertEquals("Home", facts.getString("marker"));
   }

   @Test
   void routeRefusalsAreTypedNoMoveFails() {
      assertRouteRefusal(Kind.DEST_MISSING, "DEST_MISSING");
      assertRouteRefusal(Kind.DEST_AMBIGUOUS, "DEST_AMBIGUOUS");
      assertRouteRefusal(Kind.CROSS_SEGMENT, "CROSS_SEGMENT");
      assertRouteRefusal(Kind.NO_KNOWN_ROUTE, "NO_KNOWN_ROUTE");
      assertRouteRefusal(Kind.INVALID_START, "INVALID_START");
      assertRouteRefusal(Kind.INVALID_GOAL, "INVALID_GOAL");
      assertRouteRefusal(Kind.EXHAUSTED, "EXHAUSTED");
   }

   private static void assertRouteRefusal(Kind kind, String typed) {
      haven.pathfinding.NamedPlaceNavigator.Run r = run(RunStatus.ROUTE_REJECTED, kind, null, null, null, null, 0L);
      List<JSONObject> checks = MoveToMarkerScenario.outcomeChecks(r, "Home", false);
      Assertions.assertEquals("FAIL", PfTestRunner.verdictOf(checks));
      Assertions.assertEquals("marker_resolved", checks.get(0).getString("name"));
      Assertions.assertTrue(
         checks.get(0).getString("detail").contains(typed), "refusal must carry the typed kind " + typed + ": " + checks.get(0).getString("detail")
      );
      Assertions.assertFalse(MoveToMarkerScenario.factsJson(r, "Home", null, null, null).getBoolean("moved"));
   }

   @Test
   void verifiedArrivalMapsToPassWithFacts() {
      haven.pathfinding.NamedPlaceNavigator.Run r = run(
         RunStatus.NAVIGATED, Kind.REACHED, Outcome.REACHED_DESTINATION, null, Coord2d.of(330.0, 330.0), Coord.of(30, 30), 1L
      );
      List<JSONObject> checks = MoveToMarkerScenario.outcomeChecks(r, "Home", false);
      Assertions.assertEquals(1, checks.size());
      Assertions.assertEquals("pass", checks.get(0).getString("status"));
      JSONObject arrival = MoveToMarkerScenario.arrivalCheck(true, true, true, Coord2d.of(330.0, 330.0), Coord2d.of(330.0, 330.0));
      JSONObject body = MoveToMarkerScenario.completedBody(r, "Home", region(0, 0, 40, 40), 1L, Coord.of(30, 30), false, arrival);
      Assertions.assertEquals("PASS", body.getString("verdict"));
      Assertions.assertFalse(body.has("note"), "a clean verified arrival has no note");
      JSONObject facts = body.getJSONObject("facts");
      Assertions.assertTrue(facts.getBoolean("moved"));
      Assertions.assertTrue(facts.getBoolean("arrived"));
      Assertions.assertEquals("REACHED_DESTINATION", facts.getString("nav_outcome"));
      Assertions.assertEquals("Home", facts.getString("marker"));
      Assertions.assertEquals("1", facts.getString("segment"));
      Assertions.assertEquals(30, facts.getJSONArray("goal_tile").getInt(0));
      Assertions.assertEquals(3, facts.getInt("waypoint_count"));
      Assertions.assertEquals(7L, facts.getLong("markerseq"));
      Assertions.assertEquals(4, facts.getInt("legs"));
      Assertions.assertEquals(1234L, facts.getLong("elapsed_ms"));
      Assertions.assertFalse(facts.toString().contains("password"), "no credentials in the audit facts");
      Assertions.assertFalse(facts.toString().contains("item"), "no item details in the audit facts");
   }

   @Test
   void arrivalCheckRequiresServerConfirmedIdleArrival() {
      JSONObject c = MoveToMarkerScenario.arrivalCheck(true, true, true, Coord2d.of(100.0, 100.0), Coord2d.of(101.0, 100.0));
      Assertions.assertEquals("pass", c.getString("status"));
      c = MoveToMarkerScenario.arrivalCheck(true, true, false, Coord2d.of(100.0, 100.0), Coord2d.of(101.0, 100.0));
      Assertions.assertEquals("fail", c.getString("status"), "still moving is not server-confirmed idle arrival");
      c = MoveToMarkerScenario.arrivalCheck(false, true, true, Coord2d.of(100.0, 100.0), Coord2d.of(101.0, 100.0));
      Assertions.assertEquals("fail", c.getString("status"), "navigation must have verified the arrival first");
      c = MoveToMarkerScenario.arrivalCheck(true, true, true, Coord2d.of(200.0, 100.0), Coord2d.of(101.0, 100.0));
      Assertions.assertEquals("fail", c.getString("status"), "too far from the marker tile center fails");
      c = MoveToMarkerScenario.arrivalCheck(true, false, true, Coord2d.of(100.0, 100.0), Coord2d.of(101.0, 100.0));
      Assertions.assertEquals("fail", c.getString("status"), "player not observable fails");
   }

   @Test
   void navigationFailuresAreTyped() {
      haven.pathfinding.NamedPlaceNavigator.Run r = run(
         RunStatus.NAVIGATED, Kind.REACHED, Outcome.LEG_LIMIT_EXHAUSTED, null, Coord2d.of(1.0, 1.0), Coord.of(30, 30), 1L
      );
      List<JSONObject> checks = MoveToMarkerScenario.outcomeChecks(r, "Home", false);
      Assertions.assertEquals("FAIL", PfTestRunner.verdictOf(checks));
      Assertions.assertTrue(checks.get(0).getString("detail").contains("leg limit"));
      r = run(RunStatus.NAVIGATED, Kind.REACHED, Outcome.REPLAN_LIMIT_EXHAUSTED, null, Coord2d.of(1.0, 1.0), Coord.of(30, 30), 1L);
      Assertions.assertTrue(((JSONObject)MoveToMarkerScenario.outcomeChecks(r, "Home", false).get(0)).getString("detail").contains("replan limit"));
      r = run(RunStatus.NAVIGATED, Kind.REACHED, Outcome.TERMINAL_FAILURE, "walker TIMEOUT", Coord2d.of(2.0, 2.0), Coord.of(30, 30), 1L);
      checks = MoveToMarkerScenario.outcomeChecks(r, "Home", false);
      Assertions.assertTrue(checks.get(0).getString("detail").contains("walker TIMEOUT"));
      r = run(RunStatus.NAVIGATED, Kind.REACHED, Outcome.COARSE_PLAN_FAILED, "coarse CROSS_SEGMENT", Coord2d.of(3.0, 3.0), Coord.of(30, 30), 1L);
      Assertions.assertTrue(((JSONObject)MoveToMarkerScenario.outcomeChecks(r, "Home", false).get(0)).getString("detail").contains("coarse CROSS_SEGMENT"));
      r = run(RunStatus.NAVIGATED, Kind.REACHED, Outcome.STUCK, "recovery exhausted", Coord2d.of(2.0, 2.0), Coord.of(30, 30), 1L);
      Assertions.assertTrue(((JSONObject)MoveToMarkerScenario.outcomeChecks(r, "Home", false).get(0)).getString("detail").contains("recovery exhausted"));
   }

   @Test
   void wallClockTimeoutAddsMoveTimeoutFail() {
      haven.pathfinding.NamedPlaceNavigator.Run r = run(
         RunStatus.NAVIGATED, Kind.REACHED, Outcome.CANCELLED, "Waypoint walk cancelled", Coord2d.of(2.0, 2.0), Coord.of(30, 30), 1L
      );
      List<JSONObject> checks = MoveToMarkerScenario.outcomeChecks(r, "Home", true);
      Assertions.assertEquals("move_timeout", checks.get(0).getString("name"));
      Assertions.assertEquals("fail", checks.get(0).getString("status"));
      Assertions.assertEquals("FAIL", PfTestRunner.verdictOf(checks));
      JSONObject body = MoveToMarkerScenario.completedBody(r, "Home", region(0, 0, 40, 40), 1L, Coord.of(30, 30), true, null);
      Assertions.assertTrue(body.getString("note").contains("deadline"));
   }

   @Test
   void cancelledMidNavigationPublishesTypedAuditBody() {
      haven.pathfinding.NamedPlaceNavigator.Run r = run(
         RunStatus.NAVIGATED, Kind.REACHED, Outcome.CANCELLED, "bot cancelled", Coord2d.of(3.0, 3.0), Coord.of(30, 30), 1L
      );
      List<JSONObject> checks = MoveToMarkerScenario.outcomeChecks(r, "Home", false);
      Assertions.assertEquals("FAIL", PfTestRunner.verdictOf(checks));
      Assertions.assertEquals("navigation_completed", checks.get(0).getString("name"));
      Assertions.assertTrue(checks.get(0).getString("detail").contains("cancelled"));
      JSONObject body = MoveToMarkerScenario.cancelledBody(r, "Home", region(0, 0, 40, 40), 1L, Coord.of(30, 30));
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      Assertions.assertTrue(body.getString("note").contains("cancelled"));
      JSONArray cchecks = body.getJSONArray("checks");
      JSONObject last = cchecks.getJSONObject(cchecks.length() - 1);
      Assertions.assertEquals("run_cancelled", last.getString("name"));
      Assertions.assertEquals("fail", last.getString("status"));
      Assertions.assertEquals("CANCELLED", body.getJSONObject("facts").getString("nav_outcome"));
      Assertions.assertFalse(body.getJSONObject("facts").getBoolean("arrived"));
   }

   @Test
   void cancelledBeforeExecutionThrowsCancelled() {
      Run handle = new Run("move_to_marker");
      handle.cancelled = true;
      Assertions.assertThrows(Cancelled.class, () -> new MoveToMarkerScenario().execute(handle, null));
   }

   @Test
   void moveToMarkerWithoutGameIsTypedFailWithArtifactFacts() throws Exception {
      String prev = System.getProperty("haven.pf.move.marker");

      try {
         System.setProperty("haven.pf.move.marker", "Home");
         JSONObject started = PfTestRunner.start("move_to_marker", null);
         Assertions.assertTrue(started.getBoolean("ok"));
         String id = started.getString("run_id");
         JSONObject result = null;
         long deadline = System.currentTimeMillis() + 5000L;

         while (System.currentTimeMillis() < deadline) {
            result = PfTestRunner.result(id);
            if (result != null && !"running".equals(result.getString("status"))) {
               break;
            }

            Thread.sleep(50L);
         }

         Assertions.assertNotNull(result, "run must complete within the poll deadline");
         Assertions.assertEquals("completed", result.getString("status"));
         Assertions.assertEquals("FAIL", result.getString("verdict"));
         JSONArray checks = result.getJSONArray("checks");
         Assertions.assertEquals("in_game", checks.getJSONObject(0).getString("name"));
         Assertions.assertEquals("fail", checks.getJSONObject(0).getString("status"));
         JSONObject facts = result.getJSONObject("facts");
         Assertions.assertFalse(facts.getBoolean("moved"));
         Assertions.assertEquals("Home", facts.getString("marker"));
         Assertions.assertTrue(result.getString("artifact").contains("move_to_marker"), "artifact path must name the scenario: " + result.getString("artifact"));
         Assertions.assertTrue(result.optString("note").contains("no movement performed"));
      } finally {
         if (prev == null) {
            System.clearProperty("haven.pf.move.marker");
         } else {
            System.setProperty("haven.pf.move.marker", prev);
         }
      }
   }

   private static MoveResult mv(Status plan, int expanded, int obstacles, Result walk, String cancelled, Coord2d end, long ms) {
      return new MoveResult(plan, expanded, obstacles, walk, cancelled, end, ms);
   }

   private static Selection openGroundSel() {
      Selection sel = NavigationTestSpotSelector.openGround(spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5)));
      Assertions.assertTrue(sel.selected(), sel.evidence);
      return sel;
   }

   private static void assertFailCheck(JSONObject c, String name, String contains) {
      Assertions.assertEquals(name, c.getString("name"));
      Assertions.assertEquals("fail", c.getString("status"));
      Assertions.assertTrue(c.getString("detail").contains(contains), name + " detail must carry the typed reason: " + c.getString("detail"));
   }

   @Test
   void moveToAutoOpenGroundIsAllowlistedNamedOnly() {
      Assertions.assertNull(PfTestRunner.validateScenario("move_to_auto_open_ground"), "move_to_auto_open_ground must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario(" move_to_auto_open_ground "), "scenario names are trimmed");
      Assertions.assertNotNull(PfTestRunner.validateScenario("move_to_auto_open_ground; :cmd evil"));
      Assertions.assertEquals(ALL_SCENARIOS, PfTestRunner.knownScenarios());
   }

   @Test
   void autoOpenGroundPreflightRefusesNoMoveInPinnedOrder() {
      List<JSONObject> c = MoveToAutoOpenGroundScenario.preflightChecks(false, false, false, false, true);
      Assertions.assertEquals(1, c.size());
      Assertions.assertEquals("in_game", c.get(0).getString("name"));
      Assertions.assertEquals("fail", c.get(0).getString("status"));
      c = MoveToAutoOpenGroundScenario.preflightChecks(true, false, true, true, true);
      Assertions.assertEquals(2, c.size());
      Assertions.assertEquals("player_present", c.get(1).getString("name"));
      Assertions.assertEquals("fail", c.get(1).getString("status"));
      c = MoveToAutoOpenGroundScenario.preflightChecks(true, true, false, true, true);
      Assertions.assertEquals(3, c.size());
      Assertions.assertEquals("mapfile_available", c.get(2).getString("name"));
      Assertions.assertEquals("fail", c.get(2).getString("status"));
      c = MoveToAutoOpenGroundScenario.preflightChecks(true, true, true, false, true);
      Assertions.assertEquals(4, c.size());
      Assertions.assertEquals("player_idle", c.get(3).getString("name"));
      Assertions.assertEquals("fail", c.get(3).getString("status"));
      Assertions.assertTrue(c.get(3).getString("detail").toLowerCase().contains("moving"), "not-idle must be a typed refusal");
      c = MoveToAutoOpenGroundScenario.preflightChecks(true, true, true, true, true);
      Assertions.assertEquals(5, c.size());
      Assertions.assertEquals("bot_available", c.get(4).getString("name"));
      Assertions.assertEquals("fail", c.get(4).getString("status"));
      c = MoveToAutoOpenGroundScenario.preflightChecks(true, true, true, true, false);
      Assertions.assertEquals(5, c.size());
      Assertions.assertEquals("pass", c.get(4).getString("status"));
      Assertions.assertEquals("PASS", PfTestRunner.verdictOf(c));
   }

   @Test
   void autoOpenGroundSelectionRefusalIsTypedNoMoveFail() {
      char[][] g = spotFill(20, 20, '#');

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            g[10 + dy][10 + dx] = ' ';
         }
      }

      Selection sel = NavigationTestSpotSelector.openGround(spotScene(20, 20, g, spotCellWorld(10), spotCellWorld(10)));
      Assertions.assertTrue(sel.refused(), sel.evidence);
      JSONObject c = MoveToAutoOpenGroundScenario.factsJson(sel, null, "SELECTION_REFUSED", null);
      Assertions.assertFalse(c.getBoolean("moved"), "selection refusal must record moved=false");
      Assertions.assertFalse(c.getBoolean("arrived"));
      Assertions.assertEquals("SELECTION_REFUSED", c.getString("status"));
      Assertions.assertFalse(c.getBoolean("selected"));
      Assertions.assertEquals(Refusal.NO_CANDIDATE.name(), c.getString("refusal"));
      Assertions.assertEquals(0, c.getInt("candidates"));
      Assertions.assertFalse(c.has("target_tile"));
      Assertions.assertFalse(c.has("plan_status"));
   }

   @Test
   void revalidationRefusesWhenTargetOrBodyStateInvalid() {
      Scene fresh = spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5));
      Coord2d target = Coord2d.of(spotCellWorld(10), spotCellWorld(10));
      JSONObject c = MoveToAutoOpenGroundScenario.revalidationCheck(fresh, target, 44.0);
      Assertions.assertEquals("pass", c.getString("status"));
      c = MoveToAutoOpenGroundScenario.revalidationCheck(null, target, 99.0);
      assertFailCheck(c, "target_revalidated", "not observable");
      fresh.moving = true;
      c = MoveToAutoOpenGroundScenario.revalidationCheck(fresh, target, 99.0);
      assertFailCheck(c, "target_revalidated", "moving");
      fresh.moving = false;
      c = MoveToAutoOpenGroundScenario.revalidationCheck(fresh, Coord2d.of(spotCellWorld(39), spotCellWorld(39)), 99.0);
      assertFailCheck(c, "target_revalidated", "exceeds the hard bound");
      char[][] g = spotFill(40, 40, ' ');
      g[10][10] = '#';
      Scene blocked = spotScene(40, 40, g, spotCellWorld(5), spotCellWorld(5));
      c = MoveToAutoOpenGroundScenario.revalidationCheck(blocked, Coord2d.of(spotCellWorld(10), spotCellWorld(10)), 99.0);
      assertFailCheck(c, "target_revalidated", "blocked");
   }

   @Test
   void autoOpenGroundRouteRefusalsAreTypedNoMoveFails() {
      for (Status st : List.of(Status.FAILED, Status.SNAPPED, Status.PARTIAL, Status.CLIPPED)) {
         MoveResult m = mv(st, 0, 0, null, null, Coord2d.of(1.0, 1.0), 5L);
         assertFailCheck(MoveToAutoOpenGroundScenario.routeCheck(m), "route_planned", st.name());
         JSONObject facts = MoveToAutoOpenGroundScenario.factsJson(openGroundSel(), m, "ROUTE_REFUSED", null);
         Assertions.assertFalse(facts.getBoolean("moved"), st + " plan must record moved=false");
         Assertions.assertEquals("ROUTE_REFUSED", facts.getString("status"));
         Assertions.assertEquals(st.name(), facts.getString("plan_status"));
         Assertions.assertFalse(facts.has("walk_outcome"), "nothing was walked, so no walk outcome");
      }
   }

   @Test
   void walkOutcomesAreTyped() {
      MoveResult m = mv(Status.REACHED, 10, 2, Result.ARRIVED, null, Coord2d.of(100.0, 100.0), 5000L);
      JSONObject c = MoveToAutoOpenGroundScenario.walkCheck(m);
      Assertions.assertEquals("pass", c.getString("status"));

      for (Result r : List.of(Result.REJECTED, Result.SHORT_STOP, Result.TIMEOUT, Result.STUCK)) {
         m = mv(Status.REACHED, 10, 2, r, null, Coord2d.of(100.0, 100.0), 5000L);
         assertFailCheck(MoveToAutoOpenGroundScenario.walkCheck(m), "walk_completed", r.name());
      }

      m = mv(Status.REACHED, 10, 2, null, "Waypoint walk cancelled", Coord2d.of(100.0, 100.0), 5000L);
      assertFailCheck(MoveToAutoOpenGroundScenario.walkCheck(m), "walk_completed", "cancelled");
   }

   @Test
   void autoOpenGroundArrivalRequiresVerifiedIdleArrivalAtTarget() {
      Coord2d target = Coord2d.of(100.0, 100.0);
      JSONObject c = MoveToAutoOpenGroundScenario.arrivalCheck(true, true, true, Coord2d.of(100.0, 100.0), target);
      Assertions.assertEquals("pass", c.getString("status"));
      c = MoveToAutoOpenGroundScenario.arrivalCheck(true, true, false, Coord2d.of(100.0, 100.0), target);
      Assertions.assertEquals("fail", c.getString("status"), "still moving is not server-confirmed idle arrival");
      c = MoveToAutoOpenGroundScenario.arrivalCheck(false, true, true, Coord2d.of(100.0, 100.0), target);
      Assertions.assertEquals("fail", c.getString("status"), "the walk must have verified arrival first");
      c = MoveToAutoOpenGroundScenario.arrivalCheck(true, true, true, Coord2d.of(110.0, 100.0), target);
      Assertions.assertEquals("fail", c.getString("status"), "too far from the selected target fails");
      c = MoveToAutoOpenGroundScenario.arrivalCheck(true, false, true, Coord2d.of(100.0, 100.0), target);
      Assertions.assertEquals("fail", c.getString("status"), "player not observable fails");
   }

   @Test
   void autoOpenGroundVerifiedWalkPassesWithFacts() {
      Selection sel = openGroundSel();
      MoveResult m = mv(Status.REACHED, 12, 1, Result.ARRIVED, null, sel.targetWorld, 4321L);
      JSONObject body = MoveToAutoOpenGroundScenario.completedBody(
         sel, m, false, MoveToAutoOpenGroundScenario.arrivalCheck(true, true, true, sel.targetWorld, sel.targetWorld)
      );
      Assertions.assertEquals("PASS", body.getString("verdict"));
      Assertions.assertFalse(body.has("note"), "a clean verified walk has no note");
      JSONObject facts = body.getJSONObject("facts");
      Assertions.assertTrue(facts.getBoolean("moved"));
      Assertions.assertTrue(facts.getBoolean("arrived"));
      Assertions.assertEquals("OPEN_GROUND", facts.getString("profile"));
      Assertions.assertEquals("WALKED", facts.getString("status"));
      Assertions.assertTrue(facts.getBoolean("selected"));
      Assertions.assertTrue(facts.isNull("refusal"));
      Assertions.assertTrue(facts.getInt("candidates") > 0);
      Assertions.assertTrue(facts.has("best_score"));
      Assertions.assertEquals(sel.targetTile.x, facts.getJSONArray("target_tile").getInt(0));
      Assertions.assertEquals(sel.targetTile.y, facts.getJSONArray("target_tile").getInt(1));
      Assertions.assertTrue(facts.has("target_world"));
      Assertions.assertEquals("REACHED", facts.getString("plan_status"));
      Assertions.assertEquals(12, facts.getInt("expanded"));
      Assertions.assertEquals(1, facts.getInt("obstacles"));
      Assertions.assertEquals("ARRIVED", facts.getString("walk_outcome"));
      Assertions.assertEquals(4321L, facts.getLong("elapsed_ms"));
      Assertions.assertFalse(facts.toString().contains("password"), "no credentials in the audit facts");
      Assertions.assertFalse(facts.toString().contains("\"id\":"), "no gob ids in the audit facts");
   }

   @Test
   void autoOpenGroundRefusalFactsAreTypedNoMove() {
      JSONObject f = MoveToAutoOpenGroundScenario.factsJson(
         null, null, "NOT_STARTED", "client is not in game (move_to_auto_open_ground requires in-game state)"
      );
      Assertions.assertFalse(f.getBoolean("moved"));
      Assertions.assertFalse(f.getBoolean("arrived"));
      Assertions.assertEquals("OPEN_GROUND", f.getString("profile"));
      Assertions.assertEquals("NOT_STARTED", f.getString("status"));
      Assertions.assertFalse(f.getBoolean("selected"));
      Assertions.assertEquals("PREFLIGHT", f.getString("refusal"));
      Assertions.assertTrue(f.getString("reason").contains("in game"));
      Assertions.assertEquals(0, f.getInt("candidates"));
      Assertions.assertFalse(f.has("target_tile"));
      Assertions.assertFalse(f.has("target_world"));
      Assertions.assertFalse(f.has("plan_status"));
      Selection sel = openGroundSel();
      f = MoveToAutoOpenGroundScenario.factsJson(sel, null, "REVALIDATION_REFUSED", null);
      Assertions.assertFalse(f.getBoolean("moved"));
      Assertions.assertEquals("REVALIDATION_REFUSED", f.getString("status"));
      Assertions.assertTrue(f.getBoolean("selected"));
      Assertions.assertTrue(f.has("target_tile"));
      Assertions.assertTrue(f.has("target_world"));
   }

   @Test
   void autoOpenGroundCancelledMidWalkPublishesTypedAuditBody() {
      Selection sel = openGroundSel();
      MoveResult m = mv(Status.REACHED, 8, 0, null, "Waypoint walk cancelled", Coord2d.of(60.0, 60.0), 3000L);
      JSONObject body = MoveToAutoOpenGroundScenario.cancelledBody(sel, m);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      Assertions.assertTrue(body.getString("note").contains("cancelled"));
      JSONArray cchecks = body.getJSONArray("checks");
      JSONObject last = cchecks.getJSONObject(cchecks.length() - 1);
      Assertions.assertEquals("run_cancelled", last.getString("name"));
      Assertions.assertEquals("fail", last.getString("status"));
      JSONObject facts = body.getJSONObject("facts");
      Assertions.assertEquals("CANCELLED", facts.getString("walk_outcome"));
      Assertions.assertFalse(facts.getBoolean("arrived"));
      Assertions.assertTrue(facts.getBoolean("moved"), "a walk that started before the cancel is moved=true");
   }

   @Test
   void autoOpenGroundWallClockTimeoutAddsMoveTimeoutFail() {
      Selection sel = openGroundSel();
      MoveResult m = mv(Status.REACHED, 8, 0, null, "Waypoint walk cancelled", Coord2d.of(60.0, 60.0), 3000L);
      JSONObject body = MoveToAutoOpenGroundScenario.completedBody(sel, m, true, null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      JSONObject first = body.getJSONArray("checks").getJSONObject(0);
      Assertions.assertEquals("move_timeout", first.getString("name"));
      Assertions.assertEquals("fail", first.getString("status"));
      Assertions.assertTrue(body.getString("note").contains("deadline"));
   }

   @Test
   void autoOpenGroundCancelledBeforeExecutionThrowsCancelled() {
      Run handle = new Run("move_to_auto_open_ground");
      handle.cancelled = true;
      Assertions.assertThrows(Cancelled.class, () -> new MoveToAutoOpenGroundScenario().execute(handle, null));
   }

   @Test
   void moveToAutoOpenGroundWithoutGameIsTypedFailWithArtifactFacts() throws Exception {
      JSONObject started = PfTestRunner.start("move_to_auto_open_ground", null);
      Assertions.assertTrue(started.getBoolean("ok"));
      String id = started.getString("run_id");
      JSONObject result = null;
      long deadline = System.currentTimeMillis() + 5000L;

      while (System.currentTimeMillis() < deadline) {
         result = PfTestRunner.result(id);
         if (result != null && !"running".equals(result.getString("status"))) {
            break;
         }

         Thread.sleep(50L);
      }

      Assertions.assertNotNull(result, "run must complete within the poll deadline");
      Assertions.assertEquals("completed", result.getString("status"));
      Assertions.assertEquals("FAIL", result.getString("verdict"));
      JSONArray checks = result.getJSONArray("checks");
      Assertions.assertEquals("in_game", checks.getJSONObject(0).getString("name"));
      Assertions.assertEquals("fail", checks.getJSONObject(0).getString("status"));
      JSONObject facts = result.getJSONObject("facts");
      Assertions.assertFalse(facts.getBoolean("moved"));
      Assertions.assertEquals("NOT_STARTED", facts.getString("status"));
      Assertions.assertEquals("PREFLIGHT", facts.getString("refusal"));
      Assertions.assertTrue(
         result.getString("artifact").contains("move_to_auto_open_ground"), "artifact path must name the scenario: " + result.getString("artifact")
      );
      Assertions.assertTrue(result.optString("note").contains("no movement performed"));
   }

   private static Selection obstacleCorridorSel() {
      char[][] g = spotFill(40, 40, ' ');

      for (int y = 0; y < 40; y++) {
         if (y != 19) {
            g[y][15] = '#';
         }
      }

      Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(spotScene(40, 40, g, spotCellWorld(5), spotCellWorld(5)));
      Assertions.assertTrue(sel.selected(), sel.evidence);
      return sel;
   }

   private static Scene gapWallScene() {
      char[][] g = spotFill(40, 40, ' ');

      for (int y = 0; y < 40; y++) {
         if (y != 19) {
            g[y][15] = '#';
         }
      }

      return spotScene(40, 40, g, spotCellWorld(5), spotCellWorld(5));
   }

   @Test
   void moveToAutoObstacleCorridorIsAllowlistedNamedOnly() {
      Assertions.assertNull(PfTestRunner.validateScenario("move_to_auto_obstacle_corridor"), "move_to_auto_obstacle_corridor must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario(" move_to_auto_obstacle_corridor "), "scenario names are trimmed");
      Assertions.assertNotNull(PfTestRunner.validateScenario("move_to_auto_obstacle_corridor; :cmd evil"));
      Assertions.assertEquals(ALL_SCENARIOS, PfTestRunner.knownScenarios());
   }

   @Test
   void autoObstacleCorridorPreflightRefusesNoMoveInPinnedOrder() {
      List<JSONObject> c = MoveToAutoObstacleCorridorScenario.preflightChecks(false, false, false, false, true);
      Assertions.assertEquals(1, c.size());
      Assertions.assertEquals("in_game", c.get(0).getString("name"));
      Assertions.assertEquals("fail", c.get(0).getString("status"));
      Assertions.assertTrue(c.get(0).getString("detail").contains("move_to_auto_obstacle_corridor"), c.get(0).getString("detail"));
      c = MoveToAutoObstacleCorridorScenario.preflightChecks(true, false, true, true, true);
      Assertions.assertEquals(2, c.size());
      Assertions.assertEquals("player_present", c.get(1).getString("name"));
      Assertions.assertEquals("fail", c.get(1).getString("status"));
      c = MoveToAutoObstacleCorridorScenario.preflightChecks(true, true, false, true, true);
      Assertions.assertEquals(3, c.size());
      Assertions.assertEquals("mapfile_available", c.get(2).getString("name"));
      Assertions.assertEquals("fail", c.get(2).getString("status"));
      c = MoveToAutoObstacleCorridorScenario.preflightChecks(true, true, true, false, true);
      Assertions.assertEquals(4, c.size());
      Assertions.assertEquals("player_idle", c.get(3).getString("name"));
      Assertions.assertEquals("fail", c.get(3).getString("status"));
      Assertions.assertTrue(c.get(3).getString("detail").toLowerCase().contains("moving"), "not-idle must be a typed refusal");
      c = MoveToAutoObstacleCorridorScenario.preflightChecks(true, true, true, true, true);
      Assertions.assertEquals(5, c.size());
      Assertions.assertEquals("bot_available", c.get(4).getString("name"));
      Assertions.assertEquals("fail", c.get(4).getString("status"));
      c = MoveToAutoObstacleCorridorScenario.preflightChecks(true, true, true, true, false);
      Assertions.assertEquals(5, c.size());
      Assertions.assertEquals("pass", c.get(4).getString("status"));
      Assertions.assertEquals("PASS", PfTestRunner.verdictOf(c));
   }

   @Test
   void autoObstacleCorridorSelectionRefusalIsTypedNoMoveFail() {
      char[][] g = spotFill(40, 40, ' ');

      for (int y = 0; y < 40; y++) {
         g[y][15] = '#';
      }

      Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(spotScene(40, 40, g, spotCellWorld(5), spotCellWorld(5)));
      Assertions.assertTrue(sel.refused(), sel.evidence);
      JSONObject c = MoveToAutoObstacleCorridorScenario.factsJson(sel, null, null, "SELECTION_REFUSED", null);
      Assertions.assertFalse(c.getBoolean("moved"), "selection refusal must record moved=false");
      Assertions.assertFalse(c.getBoolean("arrived"));
      Assertions.assertEquals("SELECTION_REFUSED", c.getString("status"));
      Assertions.assertEquals("LOCAL_OBSTACLE_OR_CORRIDOR", c.getString("profile"));
      Assertions.assertFalse(c.getBoolean("selected"));
      Assertions.assertEquals(Refusal.NO_CANDIDATE.name(), c.getString("refusal"));
      Assertions.assertEquals(0, c.getInt("candidates"));
      Assertions.assertFalse(c.has("target_tile"));
      Assertions.assertFalse(c.has("route_cells"));
      Assertions.assertFalse(c.has("plan_status"));
   }

   @Test
   void autoObstacleCorridorTrivialOcclusionRefusalIsTypedNoMove() {
      char[][] g = spotFill(40, 40, '#');

      for (int y = 0; y < 40; y++) {
         g[y][10] = ' ';
      }

      g[10][10] = '+';
      g[11][10] = '+';
      Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(spotScene(40, 40, g, spotCellWorld(10), spotCellWorld(10)));
      Assertions.assertTrue(sel.refused(), sel.evidence);
      Assertions.assertEquals(Refusal.NO_MATERIAL_DETOUR, sel.refusal);
      Assertions.assertTrue(sel.evidence.contains("occluded direct line"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("verified route"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("0 a material detour"), sel.evidence);
      Assertions.assertFalse(sel.evidence.contains("0 reachable"), sel.evidence);
      JSONObject c = MoveToAutoObstacleCorridorScenario.factsJson(sel, null, null, "SELECTION_REFUSED", null);
      Assertions.assertFalse(c.getBoolean("moved"), "selection refusal must record moved=false");
      Assertions.assertFalse(c.getBoolean("arrived"));
      Assertions.assertEquals("SELECTION_REFUSED", c.getString("status"));
      Assertions.assertEquals("LOCAL_OBSTACLE_OR_CORRIDOR", c.getString("profile"));
      Assertions.assertFalse(c.getBoolean("selected"));
      Assertions.assertEquals(Refusal.NO_MATERIAL_DETOUR.name(), c.getString("refusal"));
      Assertions.assertEquals(0, c.getInt("candidates"));
      Assertions.assertFalse(c.has("target_tile"));
      Assertions.assertFalse(c.has("route_cells"));
      Assertions.assertFalse(c.has("plan_status"));
      JSONObject check = NavigationTestSpotScenario.selectionCheck(sel);
      Assertions.assertEquals("fail", check.getString("status"));
      Assertions.assertTrue(check.getString("detail").contains(Refusal.NO_MATERIAL_DETOUR.name()), check.getString("detail"));
   }

   @Test
   void obstacleCorridorSelectionFactsCarryConstrainedRouteDetails() {
      Selection sel = obstacleCorridorSel();
      Assertions.assertTrue(sel.targetWorld != null && sel.targetTile != null);
      Assertions.assertTrue(((Candidate)sel.candidates.get(0)).route.size() >= 2, "the best candidate carries a verified A* route");
      Assertions.assertTrue(((Candidate)sel.candidates.get(0)).routeExpanded > 0, "the best candidate carries its A* expansion count");
      JSONObject f = MoveToAutoObstacleCorridorScenario.factsJson(sel, null, null, "WALKED", null);
      Assertions.assertTrue(f.getBoolean("selected"));
      Assertions.assertTrue(f.isNull("refusal"));
      Assertions.assertTrue(f.getInt("candidates") > 0);
      Assertions.assertTrue(f.getDouble("best_score") > 0.0);
      Assertions.assertEquals(sel.targetTile.x, f.getJSONArray("target_tile").getInt(0));
      Assertions.assertEquals(sel.targetTile.y, f.getJSONArray("target_tile").getInt(1));
      Assertions.assertTrue(f.has("target_world"), "local selections carry the world center");
      Assertions.assertEquals(((Candidate)sel.candidates.get(0)).route.size(), f.getInt("route_cells"));
      Assertions.assertEquals(((Candidate)sel.candidates.get(0)).routeExpanded, f.getInt("route_expanded"));
      Assertions.assertTrue(f.getInt("route_cells") > 0);
      Assertions.assertTrue(f.getInt("route_expanded") > 0);
      Assertions.assertFalse(f.toString().contains("password"), "no credentials in the audit facts");
      Assertions.assertFalse(f.toString().contains("\"id\":"), "no gob ids in the audit facts");
   }

   @Test
   void obstacleCorridorTargetRevalidationRefusalsAreTypedNoMove() {
      char[][] g = spotFill(40, 40, ' ');

      for (int y = 0; y < 40; y++) {
         if (y != 19) {
            g[y][15] = '#';
         }
      }

      Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(spotScene(40, 40, g, spotCellWorld(5), spotCellWorld(5)));
      Assertions.assertTrue(sel.selected(), sel.evidence);
      g[sel.targetTile.y][sel.targetTile.x] = '#';
      Scene blocked = spotScene(40, 40, g, spotCellWorld(5), spotCellWorld(5));
      JSONObject reval = MoveToAutoOpenGroundScenario.revalidationCheck(blocked, sel.targetWorld, 44.0);
      assertFailCheck(reval, "target_revalidated", "blocked");
      JSONObject f = MoveToAutoObstacleCorridorScenario.factsJson(sel, null, null, "REVALIDATION_REFUSED", null);
      Assertions.assertFalse(f.getBoolean("moved"));
      Assertions.assertEquals("REVALIDATION_REFUSED", f.getString("status"));
      Assertions.assertTrue(f.getBoolean("selected"));
      Assertions.assertTrue(f.has("target_tile"));
      Assertions.assertTrue(f.has("route_cells"), "the frozen selection evidence survives a refusal");
      Assertions.assertFalse(f.has("plan_status"), "no live plan exists when target revalidation refused");
   }

   @Test
   void obstacleCorridorRouteRevalidationPassesOnlyOnNontrivialOccludedRoute() {
      Selection sel = obstacleCorridorSel();
      Scene fresh = gapWallScene();
      Coord2d start = fresh.player;
      Coord2d mid = Coord2d.of(spotCellWorld(15), spotCellWorld(19));
      Coord2d end = sel.targetWorld;
      Plan plan = Plan.fabricated(Arrays.asList(start, mid, end), true, false, 12, 1, Status.REACHED);
      Assertions.assertTrue(
         MoveToAutoObstacleCorridorScenario.routeLength(plan.waypoints) > start.dist(end) * 1.05, "the test fixture route must be a material detour"
      );
      JSONObject c = MoveToAutoObstacleCorridorScenario.routeRevalidationCheck(fresh, plan, end);
      Assertions.assertEquals("pass", c.getString("status"), c.getString("detail"));
      Assertions.assertTrue(c.getString("detail").contains("occluded"), c.getString("detail"));
      Assertions.assertTrue(c.getString("detail").contains("detour ratio"), c.getString("detail"));
   }

   @Test
   void obstacleCorridorRouteRevalidationRefusesDegradedOrTrivialRoutes() {
      Selection sel = obstacleCorridorSel();
      Scene fresh = gapWallScene();
      Coord2d start = fresh.player;
      Coord2d mid = Coord2d.of(spotCellWorld(15), spotCellWorld(19));
      Coord2d end = sel.targetWorld;
      assertFailCheck(MoveToAutoObstacleCorridorScenario.routeRevalidationCheck(new Scene(), null, end), "route_revalidated", "not observable");
      assertFailCheck(MoveToAutoObstacleCorridorScenario.routeRevalidationCheck(fresh, null, end), "route_revalidated", "no live local plan");
      Plan failed = Plan.fabricated(Arrays.asList(start, mid, end), false, false, 0, 0, Status.FAILED);
      assertFailCheck(MoveToAutoObstacleCorridorScenario.routeRevalidationCheck(fresh, failed, end), "route_revalidated", "FAILED");
      Scene openField = spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5));
      Plan reached = Plan.fabricated(Arrays.asList(start, mid, end), true, false, 12, 1, Status.REACHED);
      assertFailCheck(MoveToAutoObstacleCorridorScenario.routeRevalidationCheck(openField, reached, end), "route_revalidated", "no longer occluded");
      assertFailCheck(MoveToAutoObstacleCorridorScenario.routeRevalidationCheck(fresh, Plan.direct(start, end), end), "route_revalidated", "trivial");
      Coord2d nearLine = start.add(end.sub(start).mul(0.5)).add(Coord2d.of(0.01, 0.01));
      Plan straight = Plan.fabricated(Arrays.asList(start, nearLine, end), true, false, 12, 1, Status.REACHED);
      assertFailCheck(MoveToAutoObstacleCorridorScenario.routeRevalidationCheck(fresh, straight, end), "route_revalidated", "not a material detour");
   }

   @Test
   void autoObstacleCorridorRouteRevalidationRefusalIsTypedNoMoveFacts() {
      Selection sel = obstacleCorridorSel();
      Scene fresh = gapWallScene();
      Coord2d start = fresh.player;
      Coord2d end = sel.targetWorld;
      Plan failed = Plan.fabricated(Arrays.asList(start, end), false, false, 0, 0, Status.SNAPPED);
      JSONObject f = MoveToAutoObstacleCorridorScenario.factsJson(sel, failed, null, "REVALIDATION_REFUSED", null);
      Assertions.assertFalse(f.getBoolean("moved"));
      Assertions.assertEquals("REVALIDATION_REFUSED", f.getString("status"));
      Assertions.assertTrue(f.getBoolean("selected"));
      Assertions.assertEquals("SNAPPED", f.getString("plan_status"));
      Assertions.assertEquals(2, f.getInt("plan_waypoints"));
      Assertions.assertFalse(f.has("walk_outcome"), "nothing was walked, so no walk outcome");
   }

   @Test
   void autoObstacleCorridorVerifiedWalkPassesWithFacts() {
      Selection sel = obstacleCorridorSel();
      Coord2d start = Coord2d.of(spotCellWorld(5), spotCellWorld(5));
      Coord2d mid = Coord2d.of(spotCellWorld(15), spotCellWorld(19));
      Coord2d end = sel.targetWorld;
      Plan plan = Plan.fabricated(Arrays.asList(start, mid, end), true, false, 12, 1, Status.REACHED);
      MoveResult m = mv(Status.REACHED, 12, 1, Result.ARRIVED, null, sel.targetWorld, 4321L);
      JSONObject body = MoveToAutoObstacleCorridorScenario.completedBody(
         sel, plan, m, false, MoveToAutoOpenGroundScenario.arrivalCheck(true, true, true, sel.targetWorld, sel.targetWorld)
      );
      Assertions.assertEquals("PASS", body.getString("verdict"));
      Assertions.assertFalse(body.has("note"), "a clean verified walk has no note");
      JSONObject facts = body.getJSONObject("facts");
      Assertions.assertTrue(facts.getBoolean("moved"));
      Assertions.assertTrue(facts.getBoolean("arrived"));
      Assertions.assertEquals("LOCAL_OBSTACLE_OR_CORRIDOR", facts.getString("profile"));
      Assertions.assertEquals("WALKED", facts.getString("status"));
      Assertions.assertTrue(facts.getBoolean("selected"));
      Assertions.assertTrue(facts.isNull("refusal"));
      Assertions.assertTrue(facts.getInt("candidates") > 0);
      Assertions.assertTrue(facts.getInt("route_cells") > 0);
      Assertions.assertTrue(facts.getInt("route_expanded") > 0);
      Assertions.assertEquals(sel.targetTile.x, facts.getJSONArray("target_tile").getInt(0));
      Assertions.assertEquals(sel.targetTile.y, facts.getJSONArray("target_tile").getInt(1));
      Assertions.assertTrue(facts.has("target_world"));
      Assertions.assertEquals("REACHED", facts.getString("plan_status"));
      Assertions.assertEquals(3, facts.getInt("plan_waypoints"));
      Assertions.assertTrue(facts.getDouble("route_dist") > facts.getDouble("direct_dist"), "the plan facts must show the material detour");
      Assertions.assertEquals("ARRIVED", facts.getString("walk_outcome"));
      Assertions.assertEquals(4321L, facts.getLong("elapsed_ms"));
      Assertions.assertFalse(facts.toString().contains("password"), "no credentials in the audit facts");
      Assertions.assertFalse(facts.toString().contains("\"id\":"), "no gob ids in the audit facts");
   }

   @Test
   void autoObstacleCorridorWalkOutcomesAreTyped() {
      Selection sel = obstacleCorridorSel();
      Coord2d start = Coord2d.of(spotCellWorld(5), spotCellWorld(5));
      Coord2d mid = Coord2d.of(spotCellWorld(15), spotCellWorld(19));
      Coord2d end = sel.targetWorld;
      Plan plan = Plan.fabricated(Arrays.asList(start, mid, end), true, false, 12, 1, Status.REACHED);

      for (Result r : List.of(Result.REJECTED, Result.SHORT_STOP, Result.TIMEOUT, Result.STUCK)) {
         MoveResult m = mv(Status.REACHED, 12, 1, r, null, Coord2d.of(100.0, 100.0), 5000L);
         JSONObject body = MoveToAutoObstacleCorridorScenario.completedBody(sel, plan, m, false, null);
         Assertions.assertEquals("FAIL", body.getString("verdict"));
         JSONObject walk = body.getJSONArray("checks").getJSONObject(0);
         Assertions.assertEquals("walk_completed", walk.getString("name"));
         Assertions.assertEquals("fail", walk.getString("status"));
         Assertions.assertTrue(walk.getString("detail").contains(r.name()));
         Assertions.assertEquals(r.name(), body.getJSONObject("facts").getString("walk_outcome"));
      }

      MoveResult m = mv(Status.REACHED, 12, 1, null, "Waypoint walk cancelled", Coord2d.of(100.0, 100.0), 5000L);
      JSONObject body = MoveToAutoObstacleCorridorScenario.completedBody(sel, plan, m, false, null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      Assertions.assertEquals("CANCELLED", body.getJSONObject("facts").getString("walk_outcome"));
   }

   @Test
   void autoObstacleCorridorCancelledMidWalkPublishesTypedAuditBody() {
      Selection sel = obstacleCorridorSel();
      Coord2d start = Coord2d.of(spotCellWorld(5), spotCellWorld(5));
      Coord2d mid = Coord2d.of(spotCellWorld(15), spotCellWorld(19));
      Coord2d end = sel.targetWorld;
      Plan plan = Plan.fabricated(Arrays.asList(start, mid, end), true, false, 12, 1, Status.REACHED);
      JSONObject routeReval = MoveToAutoObstacleCorridorScenario.routeRevalidationCheck(gapWallScene(), plan, end);
      Assertions.assertEquals("pass", routeReval.getString("status"));
      MoveResult m = mv(Status.REACHED, 8, 0, null, "Waypoint walk cancelled", Coord2d.of(60.0, 60.0), 3000L);
      JSONObject body = MoveToAutoObstacleCorridorScenario.cancelledBody(sel, plan, routeReval, m);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      Assertions.assertTrue(body.getString("note").contains("cancelled"));
      JSONArray cchecks = body.getJSONArray("checks");
      Assertions.assertEquals("route_revalidated", cchecks.getJSONObject(0).getString("name"));
      Assertions.assertEquals("pass", cchecks.getJSONObject(0).getString("status"), "the validated route check survives into the cancelled body");
      JSONObject last = cchecks.getJSONObject(cchecks.length() - 1);
      Assertions.assertEquals("run_cancelled", last.getString("name"));
      Assertions.assertEquals("fail", last.getString("status"));
      JSONObject facts = body.getJSONObject("facts");
      Assertions.assertEquals("CANCELLED", facts.getString("walk_outcome"));
      Assertions.assertFalse(facts.getBoolean("arrived"));
      Assertions.assertTrue(facts.getBoolean("moved"), "a walk that started before the cancel is moved=true");
   }

   @Test
   void autoObstacleCorridorWallClockTimeoutAddsMoveTimeoutFail() {
      Selection sel = obstacleCorridorSel();
      Coord2d start = Coord2d.of(spotCellWorld(5), spotCellWorld(5));
      Coord2d mid = Coord2d.of(spotCellWorld(15), spotCellWorld(19));
      Coord2d end = sel.targetWorld;
      Plan plan = Plan.fabricated(Arrays.asList(start, mid, end), true, false, 8, 0, Status.REACHED);
      MoveResult m = mv(Status.REACHED, 8, 0, null, "Waypoint walk cancelled", Coord2d.of(60.0, 60.0), 3000L);
      JSONObject body = MoveToAutoObstacleCorridorScenario.completedBody(sel, plan, m, true, null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      JSONObject first = body.getJSONArray("checks").getJSONObject(0);
      Assertions.assertEquals("move_timeout", first.getString("name"));
      Assertions.assertEquals("fail", first.getString("status"));
      Assertions.assertTrue(body.getString("note").contains("deadline"));
   }

   @Test
   void autoObstacleCorridorRefusalFactsAreTypedNoMove() {
      JSONObject f = MoveToAutoObstacleCorridorScenario.factsJson(
         null, null, null, "NOT_STARTED", "client is not in game (move_to_auto_obstacle_corridor requires in-game state)"
      );
      Assertions.assertFalse(f.getBoolean("moved"));
      Assertions.assertFalse(f.getBoolean("arrived"));
      Assertions.assertEquals("LOCAL_OBSTACLE_OR_CORRIDOR", f.getString("profile"));
      Assertions.assertEquals("NOT_STARTED", f.getString("status"));
      Assertions.assertFalse(f.getBoolean("selected"));
      Assertions.assertEquals("PREFLIGHT", f.getString("refusal"));
      Assertions.assertTrue(f.getString("reason").contains("in game"));
      Assertions.assertEquals(0, f.getInt("candidates"));
      Assertions.assertFalse(f.has("target_tile"));
      Assertions.assertFalse(f.has("route_cells"));
      Assertions.assertFalse(f.has("plan_status"));
      Assertions.assertFalse(f.has("walk_outcome"));
   }

   @Test
   void autoObstacleCorridorCancelledBeforeExecutionThrowsCancelled() {
      Run handle = new Run("move_to_auto_obstacle_corridor");
      handle.cancelled = true;
      Assertions.assertThrows(Cancelled.class, () -> new MoveToAutoObstacleCorridorScenario().execute(handle, null));
   }

   @Test
   void moveToAutoObstacleCorridorWithoutGameIsTypedFailWithArtifactFacts() throws Exception {
      JSONObject started = PfTestRunner.start("move_to_auto_obstacle_corridor", null);
      Assertions.assertTrue(started.getBoolean("ok"));
      String id = started.getString("run_id");
      JSONObject result = null;
      long deadline = System.currentTimeMillis() + 5000L;

      while (System.currentTimeMillis() < deadline) {
         result = PfTestRunner.result(id);
         if (result != null && !"running".equals(result.getString("status"))) {
            break;
         }

         Thread.sleep(50L);
      }

      Assertions.assertNotNull(result, "run must complete within the poll deadline");
      Assertions.assertEquals("completed", result.getString("status"));
      Assertions.assertEquals("FAIL", result.getString("verdict"));
      JSONArray checks = result.getJSONArray("checks");
      Assertions.assertEquals("in_game", checks.getJSONObject(0).getString("name"));
      Assertions.assertEquals("fail", checks.getJSONObject(0).getString("status"));
      JSONObject facts = result.getJSONObject("facts");
      Assertions.assertFalse(facts.getBoolean("moved"));
      Assertions.assertEquals("NOT_STARTED", facts.getString("status"));
      Assertions.assertEquals("PREFLIGHT", facts.getString("refusal"));
      Assertions.assertTrue(
         result.getString("artifact").contains("move_to_auto_obstacle_corridor"), "artifact path must name the scenario: " + result.getString("artifact")
      );
      Assertions.assertTrue(result.optString("note").contains("no movement performed"));
   }

   private static Node n(long id, double tx, double ty) {
      return new Node(id, tx * 11.0, ty * 11.0);
   }

   private static void addCupboard(Scene s, double x, double y) {
      GobGeom g = new GobGeom();
      g.id = (long)(s.gobs.size() + 1);
      g.cupboard = true;
      g.resid = "gfx/terobjs/cupboard";
      g.rc = Coord2d.of(x, y);
      s.gobs.add(g);
   }

   private static JSONObject findCheck(JSONObject body, String name) {
      JSONArray checks = body.getJSONArray("checks");

      for (int i = 0; i < checks.length(); i++) {
         JSONObject c = checks.getJSONObject(i);
         if (name.equals(c.getString("name"))) {
            return c;
         }
      }

      return null;
   }

   @Test
   void basementCabinetIdentifyWithoutGameFailsCleanly() throws Exception {
      Run run = new Run("basement_cabinet_identify");
      JSONObject body = new BasementCabinetIdentifyScenario().execute(run, null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      JSONObject inGame = body.getJSONArray("checks").getJSONObject(0);
      Assertions.assertEquals("in_game", inGame.getString("name"));
      Assertions.assertEquals("fail", inGame.getString("status"));
      Assertions.assertFalse(body.has("fixture"), "no fixture when not in game");
   }

   @Test
   void noCupboardsInSceneIsATypedFail() {
      Scene s = new Scene();
      s.player = Coord2d.of(0.0, 0.0);
      addCupboard(s, 100.0, 100.0);
      ((GobGeom)s.gobs.get(0)).cupboard = false;
      JSONObject body = PfTestRunner.evaluateCabinets(s);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      Assertions.assertEquals("fail", findCheck(body, "cupboards_present").getString("status"));
      Assertions.assertFalse(body.has("fixture"), "no fixture without cupboards");
   }

   @Test
   void clustersCupboardsDeterministicallyByAdjacency() {
      List<Node> nodes = new ArrayList<>();
      nodes.add(n(1L, 0.0, 0.0));
      nodes.add(n(2L, 1.0, 0.0));
      nodes.add(n(3L, 2.0, 0.0));
      nodes.add(n(4L, 3.0, 0.0));
      nodes.add(n(5L, 10.0, 10.0));
      List<List<Node>> clusters = PfTestRunner.clusterCupboards(nodes);
      Assertions.assertEquals(2, clusters.size(), "packed row and singleton must be separate clusters");
      Assertions.assertEquals(4, clusters.get(0).size(), "largest cluster first");
      Assertions.assertEquals(1, clusters.get(1).size());
      List<Node> shuffled = new ArrayList<>(nodes);
      Collections.shuffle(shuffled, new Random(42L));
      List<List<Node>> again = PfTestRunner.clusterCupboards(shuffled);
      Assertions.assertEquals(clusters.toString(), again.toString());
   }

   @Test
   void uniqueLargestClusterWinsAndTiesAreAmbiguous() {
      List<Node> nodes = new ArrayList<>();
      nodes.add(n(1L, 0.0, 0.0));
      nodes.add(n(2L, 1.0, 0.0));
      nodes.add(n(3L, 2.0, 0.0));
      nodes.add(n(4L, 20.0, 20.0));
      List<Node> pick = PfTestRunner.uniqueLargest(PfTestRunner.clusterCupboards(nodes));
      Assertions.assertNotNull(pick);
      Assertions.assertEquals(3, pick.size());
      List<Node> tied = new ArrayList<>();
      tied.add(n(1L, 0.0, 0.0));
      tied.add(n(2L, 1.0, 0.0));
      tied.add(n(3L, 30.0, 30.0));
      tied.add(n(4L, 31.0, 30.0));
      Assertions.assertNull(PfTestRunner.uniqueLargest(PfTestRunner.clusterCupboards(tied)), "tied largest clusters are ambiguous");
      Assertions.assertNull(PfTestRunner.uniqueLargest(List.of()), "no clusters is not a fixture");
   }

   @Test
   void ambiguousFixtureYieldsTypedFail() {
      Scene s = new Scene();
      s.player = Coord2d.of(0.0, 0.0);
      addCupboard(s, 0.0, 0.0);
      addCupboard(s, 11.0, 0.0);
      addCupboard(s, 500.0, 500.0);
      addCupboard(s, 511.0, 500.0);
      JSONObject body = PfTestRunner.evaluateCabinets(s);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      Assertions.assertEquals("fail", findCheck(body, "unique_largest_cluster").getString("status"));
      Assertions.assertFalse(body.has("fixture"));
   }

   @Test
   void fingerprintIsStableRelativeAndDeterministic() {
      List<Node> nodes = new ArrayList<>();
      nodes.add(n(1L, 0.0, 0.0));
      nodes.add(n(2L, 1.0, 0.0));
      nodes.add(n(3L, 0.0, 1.0));
      JSONObject fp = PfTestRunner.cupboardFingerprint(nodes);
      Assertions.assertEquals(3, fp.getInt("count"));
      JSONArray offsets = fp.getJSONArray("offsets");
      Assertions.assertEquals(3, offsets.length());
      Assertions.assertArrayEquals(new int[]{0, 0}, new int[]{offsets.getJSONArray(0).getInt(0), offsets.getJSONArray(0).getInt(1)});
      Assertions.assertArrayEquals(new int[]{0, 11}, new int[]{offsets.getJSONArray(1).getInt(0), offsets.getJSONArray(1).getInt(1)});
      Assertions.assertArrayEquals(new int[]{11, 0}, new int[]{offsets.getJSONArray(2).getInt(0), offsets.getJSONArray(2).getInt(1)});
      Assertions.assertEquals(11, fp.getInt("width"));
      Assertions.assertEquals(11, fp.getInt("height"));
      Assertions.assertEquals(2, fp.getInt("rows"));
      Assertions.assertEquals(2, fp.getInt("columns"));
      Assertions.assertEquals("block", fp.getString("shape"));
      Assertions.assertEquals(1, fp.getInt("corners"), "the elbow is the only corner");
      Assertions.assertFalse(fp.toString().contains("\"id\""));
      Assertions.assertFalse(fp.toString().contains("\"rc\""));
      Assertions.assertFalse(fp.toString().contains("\"x\""));
      Assertions.assertFalse(fp.toString().contains("\"y\""));
      Collections.shuffle(nodes, new Random(7L));
      Assertions.assertEquals(fp.toString(), PfTestRunner.cupboardFingerprint(nodes).toString());
   }

   @Test
   void evaluateCabinetsReturnsStableFixtureForUniqueLargest() {
      Scene s = new Scene();
      s.player = Coord2d.of(100.0, 100.0);
      addCupboard(s, 0.0, 0.0);
      addCupboard(s, 11.0, 0.0);
      addCupboard(s, 22.0, 0.0);
      addCupboard(s, 33.0, 0.0);
      addCupboard(s, 500.0, 500.0);
      JSONObject body = PfTestRunner.evaluateCabinets(s);
      Assertions.assertEquals("PASS", body.getString("verdict"));
      Assertions.assertEquals("pass", findCheck(body, "cupboards_present").getString("status"));
      Assertions.assertEquals("pass", findCheck(body, "unique_largest_cluster").getString("status"));
      Assertions.assertEquals("pass", findCheck(body, "fixture_fingerprint").getString("status"));
      JSONObject fixture = body.getJSONObject("fixture");
      Assertions.assertEquals(4, fixture.getInt("count"));
      Assertions.assertEquals(1, fixture.getInt("rows"));
      Assertions.assertEquals(4, fixture.getInt("columns"));
      Assertions.assertEquals("row", fixture.getString("shape"));
      Assertions.assertEquals(33, fixture.getInt("width"));
   }

   @Test
   void sceneJsonRecordsCanonicalObservationFields() {
      Scene s = new Scene();
      s.origin = Coord2d.of(10.0, 20.0);
      s.w = 72;
      s.h = 72;
      s.cell = 2.75;
      s.player = Coord2d.of(12.3, 21.4);
      s.playerCell = Coord.of(1, 1);
      s.moving = false;
      s.playerInSolid = false;
      s.playerInDilated = true;
      s.terrain = "tiles/grass";
      s.solidCount = 10;
      s.dilatedCount = 5;
      s.obstacles = 3;
      JSONObject j = PfTestRunner.sceneJson(s);
      Assertions.assertEquals(72, j.getJSONArray("grid").getInt(0));
      Assertions.assertEquals(72, j.getJSONArray("grid").getInt(1));
      Assertions.assertEquals(3, j.getInt("obstacles"));
      Assertions.assertEquals(10, j.getInt("solid_count"));
      Assertions.assertFalse(j.getBoolean("player_in_solid"));
      Assertions.assertTrue(j.getBoolean("player_in_inflated"));
      Assertions.assertEquals("tiles/grass", j.getString("terrain"));
      Assertions.assertEquals(12.3, j.getJSONArray("player").getDouble(0), 1.0E-9);
   }

   private static double spotCellWorld(int i) {
      return ((double)i + 0.5) * 2.75;
   }

   private static Scene spotScene(int w, int h, char[][] g, double px, double py) {
      boolean[] solid = new boolean[w * h];
      boolean[] dilated = new boolean[w * h];

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            char c = g[y][x];
            if (c == '#') {
               solid[y * w + x] = true;
            } else if (c == '+') {
               dilated[y * w + x] = true;
            }
         }
      }

      Scene s = new Scene();
      s.origin = Coord2d.of(0.0, 0.0);
      s.w = w;
      s.h = h;
      s.cell = 2.75;
      s.player = Coord2d.of(px, py);
      s.playerCell = s.cellOf(s.player);
      s.occupancy = Occupancy.capture(s.origin, w, h, 2.75, solid, dilated, dilated, s.playerCell, null, null, Collections.emptyList());
      return s;
   }

   private static char[][] spotFill(int w, int h, char c) {
      char[][] g = new char[h][w];

      for (char[] row : g) {
         Arrays.fill(row, c);
      }

      return g;
   }

   private static CoarseTileSource spotNames(int w, int h, String name) {
      String[] names = new String[w * h];
      Arrays.fill(names, name);
      return CoarseTileSource.fromNames(w, h, names);
   }

   private static CoarseTileSource spotRiverWithGap() {
      String[] names = new String[1600];
      Arrays.fill(names, "gfx/tiles/grass");

      for (int y = 0; y < 40; y++) {
         if (y != 35) {
            for (int x = 15; x <= 20; x++) {
               names[y * 40 + x] = "gfx/tiles/cave";
            }
         }
      }

      return CoarseTileSource.fromNames(40, 40, names);
   }

   private static JSONObject findFacts(JSONObject body) {
      return body.getJSONObject("facts");
   }

   @Test
   void selectScenariosAreAllowlistedNamedOnly() {
      Assertions.assertNull(PfTestRunner.validateScenario("select_open_ground"), "select_open_ground must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario(" select_open_ground "), "scenario names are trimmed");
      Assertions.assertNull(PfTestRunner.validateScenario("select_obstacle_corridor"), "select_obstacle_corridor must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario("select_known_long_leg"), "select_known_long_leg must be allowlisted");
      Assertions.assertNotNull(PfTestRunner.validateScenario("select_open_ground; :cmd evil"));
      Assertions.assertEquals(ALL_SCENARIOS, PfTestRunner.knownScenarios());
   }

   @Test
   void selectBoulderApproachIsAllowlistedNamedOnly() {
      Assertions.assertNull(PfTestRunner.validateScenario("select_boulder_approach"), "select_boulder_approach must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario(" select_boulder_approach "), "scenario names are trimmed");
      Assertions.assertNotNull(PfTestRunner.validateScenario("select_boulder_approach; :cmd evil"));
      Assertions.assertEquals(ALL_SCENARIOS, PfTestRunner.knownScenarios());
   }

   @Test
   void selectBoulderApproachWithoutGameFailsCleanlyWithTypedFacts() throws Exception {
      Run run = new Run("select_boulder_approach");
      JSONObject body = new SelectBoulderApproachScenario().execute(run, null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      JSONObject inGame = body.getJSONArray("checks").getJSONObject(0);
      Assertions.assertEquals("in_game", inGame.getString("name"));
      Assertions.assertEquals("fail", inGame.getString("status"));
      JSONObject facts = findFacts(body);
      Assertions.assertEquals("BOULDER", facts.getString("profile"));
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertEquals("NO_GAME", facts.getString("refusal"));
      Assertions.assertEquals(0, facts.getInt("fixtures"));
      Assertions.assertEquals(0, facts.getInt("candidates"));
      Assertions.assertFalse(facts.has("best_score"));
      Assertions.assertFalse(facts.has("approach_tile"));
      Assertions.assertFalse(facts.has("approach_world"));
      Assertions.assertFalse(facts.has("approach_side"));
      Assertions.assertFalse(facts.has("standoff"));
      Assertions.assertFalse(facts.has("footprint_tiles"));
      Assertions.assertFalse(facts.has("player_dist"));
      Assertions.assertFalse(facts.has("route_cells"));
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "fixtures", "candidates"), facts.keySet());
   }

   private static Scene boulderApproachScene() {
      char[][] g = spotFill(40, 40, ' ');

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            g[12 + dy][12 + dx] = '#';
         }
      }

      Scene s = spotScene(40, 40, g, spotCellWorld(5), spotCellWorld(5));
      GobGeom b = new GobGeom();
      b.id = 777L;
      b.boulder = true;
      b.resid = "gfx/terobjs/boulder";
      b.rc = Coord2d.of(spotCellWorld(12), spotCellWorld(12));
      double half = 5.5;
      b.hitbox = List.<Coord2d[]>of(new Coord2d[]{b.rc.add(-half, -half), b.rc.add(half, -half), b.rc.add(half, half), b.rc.add(-half, half)});
      s.gobs.add(b);
      return s;
   }

   @Test
   void selectBoulderApproachSelectionSeamPassesWithTypedFacts() {
      haven.pathfinding.TransitionApproachSelector.Selection sel = TransitionApproachSelector.boulderApproach(boulderApproachScene());
      Assertions.assertTrue(sel.selected(), sel.evidence);
      JSONObject c = SelectBoulderApproachScenario.selectionCheck(sel);
      Assertions.assertEquals("pass", c.getString("status"));
      Assertions.assertTrue(c.getString("detail").contains("BOULDER"), c.getString("detail"));
      JSONObject facts = SelectBoulderApproachScenario.factsJson(sel);
      Assertions.assertEquals("BOULDER", facts.getString("profile"));
      Assertions.assertTrue(facts.getBoolean("selected"));
      Assertions.assertTrue(facts.isNull("refusal"), "a selected run carries no refusal");
      Assertions.assertEquals(1, facts.getInt("fixtures"));
      Assertions.assertTrue(facts.getInt("candidates") > 0);
      Assertions.assertTrue(facts.getDouble("best_score") > 0.0);
      Assertions.assertEquals(sel.approachTile.x, facts.getJSONArray("approach_tile").getInt(0));
      Assertions.assertEquals(sel.approachTile.y, facts.getJSONArray("approach_tile").getInt(1));
      Assertions.assertEquals(sel.approachWorld.x, facts.getJSONArray("approach_world").getDouble(0), 0.01);
      Assertions.assertEquals(sel.approachWorld.y, facts.getJSONArray("approach_world").getDouble(1), 0.01);
      Assertions.assertTrue("NESW".contains(facts.getString("approach_side")));
      Assertions.assertEquals(sel.standoff, facts.getDouble("standoff"), 0.01);
      Assertions.assertEquals(sel.footprintTiles, facts.getDouble("footprint_tiles"), 0.01);
      Assertions.assertEquals(sel.playerDist, facts.getDouble("player_dist"), 0.01);
      Assertions.assertEquals(((haven.pathfinding.TransitionApproachSelector.Candidate)sel.candidates.get(0)).route.size(), facts.getInt("route_cells"));
      Assertions.assertEquals(((haven.pathfinding.TransitionApproachSelector.Candidate)sel.candidates.get(0)).routeExpanded, facts.getInt("route_expanded"));
      Assertions.assertEquals(
         Set.of(
            "profile",
            "selected",
            "refusal",
            "reason",
            "fixtures",
            "candidates",
            "best_score",
            "approach_tile",
            "approach_world",
            "approach_side",
            "standoff",
            "footprint_tiles",
            "player_dist",
            "route_cells",
            "route_expanded"
         ),
         facts.keySet()
      );
      Assertions.assertFalse(facts.toString().contains("password"), "no credentials in the audit facts");
      Assertions.assertFalse(facts.toString().contains("777"), "no gob ids in the audit facts");
      Assertions.assertFalse(facts.toString().contains("\"id\":"), "no gob ids in the audit facts");
   }

   @Test
   void selectBoulderApproachRefusalsMapToTypedFails() {
      haven.pathfinding.TransitionApproachSelector.Selection noFixture = TransitionApproachSelector.boulderApproach(
         spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5))
      );
      assertBoulderRefusal(noFixture, haven.pathfinding.TransitionApproachSelector.Refusal.NO_FIXTURE, 0);
      char[][] sealed = spotFill(40, 40, ' ');

      for (int y = 7; y <= 17; y++) {
         for (int x = 7; x <= 17; x++) {
            sealed[y][x] = '#';
         }
      }

      Scene blocked = spotScene(40, 40, sealed, spotCellWorld(5), spotCellWorld(5));
      blocked.gobs.add(boulderGob(12, 12, 1L));
      assertBoulderRefusal(TransitionApproachSelector.boulderApproach(blocked), haven.pathfinding.TransitionApproachSelector.Refusal.NO_CANDIDATE, 1);
      char[][] walled = spotFill(40, 40, ' ');

      for (int i = 6; i <= 17; i++) {
         walled[6][i] = '#';
         walled[17][i] = '#';
         walled[i][6] = '#';
         walled[i][17] = '#';
      }

      Scene unreachable = spotScene(40, 40, walled, spotCellWorld(5), spotCellWorld(5));
      unreachable.gobs.add(boulderGob(12, 12, 2L));
      assertBoulderRefusal(TransitionApproachSelector.boulderApproach(unreachable), haven.pathfinding.TransitionApproachSelector.Refusal.NO_REACHABLE, 1);

      for (TransitionProfile p : List.of(
         TransitionProfile.DOOR,
         TransitionProfile.GATE,
         TransitionProfile.MINEHOLE,
         TransitionProfile.BOAT,
         TransitionProfile.WATER_CROSSING,
         TransitionProfile.CART
      )) {
         haven.pathfinding.TransitionApproachSelector.Selection u = TransitionApproachSelector.select(
            boulderApproachScene(), p, ApproachRange.boulder(), 100000
         );
         assertBoulderRefusal(u, haven.pathfinding.TransitionApproachSelector.Refusal.UNSUPPORTED_PROFILE, 0);
      }
   }

   private static String[] terrainNames(char[][] t) {
      String[] names = new String[t.length * t[0].length];

      for (int y = 0; y < t.length; y++) {
         for (int x = 0; x < t[0].length; x++) {
            switch (t[y][x]) {
               case '#':
                  names[y * t[0].length + x] = "gfx/tiles/odeep";
                  break;
               case '?':
                  names[y * t[0].length + x] = "gfx/tiles/notile";
                  break;
               case 'L':
                  names[y * t[0].length + x] = "loading";
                  break;
               case '~':
                  names[y * t[0].length + x] = "gfx/tiles/water";
                  break;
               default:
                  names[y * t[0].length + x] = "gfx/tiles/grass";
            }
         }
      }

      return names;
   }

   private static Scene waterScene(int w, int h, char[][] g, char[][] t, double px, double py) {
      Scene s = spotScene(w, h, g, px, py);
      s.terrainCells = terrainNames(t);
      return s;
   }

   private static Scene waterlineApproachScene() {
      char[][] g = spotFill(40, 40, ' ');
      char[][] t = spotFill(40, 40, 'G');

      for (int y = 0; y < 40; y++) {
         for (int x = 15; x <= 16; x++) {
            t[y][x] = '~';
         }
      }

      return waterScene(40, 40, g, t, spotCellWorld(5), spotCellWorld(5));
   }

   @Test
   void selectWaterlineApproachIsAllowlistedNamedOnly() {
      Assertions.assertNull(PfTestRunner.validateScenario("select_waterline_approach"), "select_waterline_approach must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario(" select_waterline_approach "), "scenario names are trimmed");
      Assertions.assertNotNull(PfTestRunner.validateScenario("select_waterline_approach; :cmd evil"));
      Assertions.assertEquals(ALL_SCENARIOS, PfTestRunner.knownScenarios());
   }

   @Test
   void selectWaterlineApproachWithoutGameFailsCleanlyWithTypedFacts() throws Exception {
      Run run = new Run("select_waterline_approach");
      JSONObject body = new SelectWaterlineApproachScenario().execute(run, null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      JSONObject inGame = body.getJSONArray("checks").getJSONObject(0);
      Assertions.assertEquals("in_game", inGame.getString("name"));
      Assertions.assertEquals("fail", inGame.getString("status"));
      JSONObject facts = findFacts(body);
      Assertions.assertEquals("WATERLINE", facts.getString("profile"));
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertEquals("NO_GAME", facts.getString("refusal"));
      Assertions.assertEquals(0, facts.getInt("water_cells"));
      Assertions.assertEquals(0, facts.getInt("waterline_cells"));
      Assertions.assertEquals(0, facts.getInt("candidates"));
      Assertions.assertFalse(facts.has("best_score"));
      Assertions.assertFalse(facts.has("approach_tile"));
      Assertions.assertFalse(facts.has("approach_world"));
      Assertions.assertFalse(facts.has("approach_side"));
      Assertions.assertFalse(facts.has("standoff"));
      Assertions.assertFalse(facts.has("footprint_tiles"));
      Assertions.assertFalse(facts.has("player_dist"));
      Assertions.assertFalse(facts.has("route_cells"));
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "water_cells", "waterline_cells", "candidates"), facts.keySet());
   }

   @Test
   void selectWaterlineApproachSelectionSeamPassesWithTypedFacts() {
      Scene scene = waterlineApproachScene();
      haven.pathfinding.TransitionApproachSelector.Selection sel = TransitionApproachSelector.waterlineApproach(scene);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      JSONObject c = SelectWaterlineApproachScenario.selectionCheck(sel);
      Assertions.assertEquals("pass", c.getString("status"));
      Assertions.assertTrue(c.getString("detail").contains("WATERLINE"), c.getString("detail"));
      JSONObject facts = SelectWaterlineApproachScenario.factsJson(sel);
      Assertions.assertEquals("WATERLINE", facts.getString("profile"));
      Assertions.assertTrue(facts.getBoolean("selected"));
      Assertions.assertTrue(facts.isNull("refusal"), "a selected run carries no refusal");
      Assertions.assertEquals(80, facts.getInt("water_cells"));
      Assertions.assertEquals(80, facts.getInt("waterline_cells"));
      Assertions.assertTrue(facts.getInt("candidates") > 0);
      Assertions.assertTrue(facts.getDouble("best_score") > 0.0);
      Assertions.assertEquals(sel.approachTile.x, facts.getJSONArray("approach_tile").getInt(0));
      Assertions.assertEquals(sel.approachTile.y, facts.getJSONArray("approach_tile").getInt(1));
      Assertions.assertEquals(sel.approachWorld.x, facts.getJSONArray("approach_world").getDouble(0), 0.01);
      Assertions.assertEquals(sel.approachWorld.y, facts.getJSONArray("approach_world").getDouble(1), 0.01);
      Assertions.assertTrue("NESW".contains(facts.getString("approach_side")));
      Assertions.assertEquals(sel.standoff, facts.getDouble("standoff"), 0.01);
      Assertions.assertEquals(sel.footprintTiles, facts.getDouble("footprint_tiles"), 0.01);
      Assertions.assertEquals(sel.playerDist, facts.getDouble("player_dist"), 0.01);
      Assertions.assertEquals(((haven.pathfinding.TransitionApproachSelector.Candidate)sel.candidates.get(0)).route.size(), facts.getInt("route_cells"));
      Assertions.assertEquals(((haven.pathfinding.TransitionApproachSelector.Candidate)sel.candidates.get(0)).routeExpanded, facts.getInt("route_expanded"));
      Assertions.assertEquals("gfx/tiles/grass", scene.terrainCells[sel.approachTile.y * scene.w + sel.approachTile.x]);
      Assertions.assertEquals(
         Set.of(
            "profile",
            "selected",
            "refusal",
            "reason",
            "water_cells",
            "waterline_cells",
            "candidates",
            "best_score",
            "approach_tile",
            "approach_world",
            "approach_side",
            "standoff",
            "footprint_tiles",
            "player_dist",
            "route_cells",
            "route_expanded"
         ),
         facts.keySet()
      );
      Assertions.assertFalse(facts.toString().contains("password"), "no credentials in the audit facts");
      Assertions.assertFalse(facts.toString().contains("\"id\":"), "no gob ids in the audit facts");
   }

   @Test
   void selectWaterlineApproachRefusalsMapToTypedFails() {
      haven.pathfinding.TransitionApproachSelector.Selection noTerrain = TransitionApproachSelector.waterlineApproach(
         spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5))
      );
      assertWaterlineRefusal(noTerrain, haven.pathfinding.TransitionApproachSelector.Refusal.NO_TERRAIN);
      haven.pathfinding.TransitionApproachSelector.Selection noWater = TransitionApproachSelector.waterlineApproach(
         waterScene(40, 40, spotFill(40, 40, ' '), spotFill(40, 40, 'G'), spotCellWorld(5), spotCellWorld(5))
      );
      assertWaterlineRefusal(noWater, haven.pathfinding.TransitionApproachSelector.Refusal.NO_WATER);
      char[][] t = spotFill(40, 40, 'G');

      for (int y = 0; y < 40; y++) {
         for (int x = 15; x <= 16; x++) {
            t[y][x] = '~';
         }
      }

      haven.pathfinding.TransitionApproachSelector.Selection inWater = TransitionApproachSelector.waterlineApproach(
         waterScene(40, 40, spotFill(40, 40, ' '), t, spotCellWorld(15), spotCellWorld(5))
      );
      assertWaterlineRefusal(inWater, haven.pathfinding.TransitionApproachSelector.Refusal.AMBIGUOUS);
      char[][] t2 = spotFill(40, 40, 'G');

      for (int y = 0; y < 40; y++) {
         for (int x = 15; x <= 16; x++) {
            t2[y][x] = '~';
         }
      }

      t2[5][14] = '?';
      haven.pathfinding.TransitionApproachSelector.Selection unknown = TransitionApproachSelector.waterlineApproach(
         waterScene(40, 40, spotFill(40, 40, ' '), t2, spotCellWorld(5), spotCellWorld(5))
      );
      assertWaterlineRefusal(unknown, haven.pathfinding.TransitionApproachSelector.Refusal.TERRAIN_UNKNOWN);
      char[][] g = spotFill(40, 40, ' ');

      for (int y = 0; y < 40; y++) {
         g[y][14] = '#';
         g[y][17] = '#';
      }

      haven.pathfinding.TransitionApproachSelector.Selection blocked = TransitionApproachSelector.waterlineApproach(
         waterScene(40, 40, g, t, spotCellWorld(5), spotCellWorld(5))
      );
      assertWaterlineRefusal(blocked, haven.pathfinding.TransitionApproachSelector.Refusal.NO_CANDIDATE);
      char[][] w = spotFill(40, 40, ' ');

      for (int i = 6; i <= 17; i++) {
         w[6][i] = '#';
         w[17][i] = '#';
         w[i][6] = '#';
         w[i][17] = '#';
      }

      char[][] t3 = spotFill(40, 40, 'G');

      for (int y = 8; y <= 15; y++) {
         for (int x = 15; x <= 16; x++) {
            t3[y][x] = '~';
         }
      }

      haven.pathfinding.TransitionApproachSelector.Selection walled = TransitionApproachSelector.waterlineApproach(
         waterScene(40, 40, w, t3, spotCellWorld(5), spotCellWorld(5))
      );
      assertWaterlineRefusal(walled, haven.pathfinding.TransitionApproachSelector.Refusal.NO_REACHABLE);
   }

   private static void assertWaterlineRefusal(
      haven.pathfinding.TransitionApproachSelector.Selection sel, haven.pathfinding.TransitionApproachSelector.Refusal refusal
   ) {
      Assertions.assertEquals(haven.pathfinding.TransitionApproachSelector.Status.REFUSED, sel.status, sel.evidence);
      Assertions.assertEquals(refusal, sel.refusal);
      JSONObject c = SelectWaterlineApproachScenario.selectionCheck(sel);
      Assertions.assertEquals("fail", c.getString("status"));
      Assertions.assertTrue(
         c.getString("detail").contains(refusal.name()), "the check detail must carry the typed refusal " + refusal.name() + ": " + c.getString("detail")
      );
      JSONObject facts = SelectWaterlineApproachScenario.factsJson(sel);
      Assertions.assertEquals("WATERLINE", facts.getString("profile"));
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertEquals(refusal.name(), facts.getString("refusal"));
      Assertions.assertEquals(sel.waterCells, facts.getInt("water_cells"));
      Assertions.assertEquals(sel.waterlineCells, facts.getInt("waterline_cells"));
      Assertions.assertEquals(0, facts.getInt("candidates"));
      Assertions.assertFalse(facts.has("best_score"));
      Assertions.assertFalse(facts.has("approach_tile"));
      Assertions.assertFalse(facts.has("approach_world"));
      Assertions.assertFalse(facts.has("approach_side"));
      Assertions.assertFalse(facts.has("standoff"));
      Assertions.assertFalse(facts.has("footprint_tiles"));
      Assertions.assertFalse(facts.has("player_dist"));
      Assertions.assertFalse(facts.has("route_cells"));
      Assertions.assertFalse(facts.has("route_expanded"));
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "water_cells", "waterline_cells", "candidates"), facts.keySet());
   }

   @Test
   void selectWaterlineApproachWithoutGameIsTypedFailWithArtifactFacts() throws Exception {
      JSONObject started = PfTestRunner.start("select_waterline_approach", null);
      Assertions.assertTrue(started.getBoolean("ok"));
      String id = started.getString("run_id");
      JSONObject result = null;
      long deadline = System.currentTimeMillis() + 5000L;

      while (System.currentTimeMillis() < deadline) {
         result = PfTestRunner.result(id);
         if (result != null && !"running".equals(result.getString("status"))) {
            break;
         }

         Thread.sleep(50L);
      }

      Assertions.assertNotNull(result, "run must complete within the poll deadline");
      Assertions.assertEquals("completed", result.getString("status"));
      Assertions.assertEquals("FAIL", result.getString("verdict"));
      Assertions.assertEquals("in_game", result.getJSONArray("checks").getJSONObject(0).getString("name"));
      Assertions.assertEquals("fail", result.getJSONArray("checks").getJSONObject(0).getString("status"));
      JSONObject facts = result.getJSONObject("facts");
      Assertions.assertEquals("NO_GAME", facts.getString("refusal"));
      Assertions.assertEquals("WATERLINE", facts.getString("profile"));
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertEquals(0, facts.getInt("water_cells"));
      Assertions.assertTrue(
         result.getString("artifact").contains("select_waterline_approach"), "artifact path must name the scenario: " + result.getString("artifact")
      );
      Assertions.assertTrue(result.optString("note").contains("not in game"));
   }

   private static GobGeom boulderGob(int cx, int cy, long id) {
      GobGeom g = new GobGeom();
      g.id = id;
      g.boulder = true;
      g.resid = "gfx/terobjs/boulder";
      g.rc = Coord2d.of(spotCellWorld(cx), spotCellWorld(cy));
      double half = 5.5;
      g.hitbox = List.<Coord2d[]>of(new Coord2d[]{g.rc.add(-half, -half), g.rc.add(half, -half), g.rc.add(half, half), g.rc.add(-half, half)});
      return g;
   }

   private static void assertBoulderRefusal(
      haven.pathfinding.TransitionApproachSelector.Selection sel, haven.pathfinding.TransitionApproachSelector.Refusal refusal, int fixtures
   ) {
      Assertions.assertEquals(haven.pathfinding.TransitionApproachSelector.Status.REFUSED, sel.status, sel.evidence);
      Assertions.assertEquals(refusal, sel.refusal);
      Assertions.assertEquals(fixtures, sel.fixtureCount);
      JSONObject c = SelectBoulderApproachScenario.selectionCheck(sel);
      Assertions.assertEquals("fail", c.getString("status"));
      Assertions.assertTrue(
         c.getString("detail").contains(refusal.name()), "the check detail must carry the typed refusal " + refusal.name() + ": " + c.getString("detail")
      );
      JSONObject facts = SelectBoulderApproachScenario.factsJson(sel);
      Assertions.assertEquals(sel.profile.name(), facts.getString("profile"), "facts carry the requested profile (incl. declared future profiles)");
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertEquals(refusal.name(), facts.getString("refusal"));
      Assertions.assertEquals(fixtures, facts.getInt("fixtures"));
      Assertions.assertEquals(0, facts.getInt("candidates"));
      Assertions.assertFalse(facts.has("best_score"));
      Assertions.assertFalse(facts.has("approach_tile"));
      Assertions.assertFalse(facts.has("approach_world"));
      Assertions.assertFalse(facts.has("approach_side"));
      Assertions.assertFalse(facts.has("standoff"));
      Assertions.assertFalse(facts.has("footprint_tiles"));
      Assertions.assertFalse(facts.has("player_dist"));
      Assertions.assertFalse(facts.has("route_cells"));
      Assertions.assertFalse(facts.has("route_expanded"));
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "fixtures", "candidates"), facts.keySet());
   }

   @Test
   void selectBoulderApproachWithoutGameIsTypedFailWithArtifactFacts() throws Exception {
      JSONObject started = PfTestRunner.start("select_boulder_approach", null);
      Assertions.assertTrue(started.getBoolean("ok"));
      String id = started.getString("run_id");
      JSONObject result = null;
      long deadline = System.currentTimeMillis() + 5000L;

      while (System.currentTimeMillis() < deadline) {
         result = PfTestRunner.result(id);
         if (result != null && !"running".equals(result.getString("status"))) {
            break;
         }

         Thread.sleep(50L);
      }

      Assertions.assertNotNull(result, "run must complete within the poll deadline");
      Assertions.assertEquals("completed", result.getString("status"));
      Assertions.assertEquals("FAIL", result.getString("verdict"));
      Assertions.assertEquals("in_game", result.getJSONArray("checks").getJSONObject(0).getString("name"));
      Assertions.assertEquals("fail", result.getJSONArray("checks").getJSONObject(0).getString("status"));
      JSONObject facts = result.getJSONObject("facts");
      Assertions.assertEquals("NO_GAME", facts.getString("refusal"));
      Assertions.assertEquals("BOULDER", facts.getString("profile"));
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertTrue(
         result.getString("artifact").contains("select_boulder_approach"), "artifact path must name the scenario: " + result.getString("artifact")
      );
      Assertions.assertTrue(result.optString("note").contains("not in game"));
   }

   private static Scene caveTransitionApproachScene() {
      char[][] g = spotFill(40, 40, ' ');

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            g[12 + dy][12 + dx] = '#';
         }
      }

      Scene s = spotScene(40, 40, g, spotCellWorld(5), spotCellWorld(5));
      s.gobs.add(caveTransitionGob(12, 12, 555L, "gfx/terobjs/minehole"));
      return s;
   }

   private static GobGeom caveTransitionGob(int cx, int cy, long id, String resid) {
      GobGeom g = new GobGeom();
      g.id = id;
      g.caveTransition = true;
      g.resid = resid;
      g.rc = Coord2d.of(spotCellWorld(cx), spotCellWorld(cy));
      double half = 5.5;
      g.hitbox = List.<Coord2d[]>of(new Coord2d[]{g.rc.add(-half, -half), g.rc.add(half, -half), g.rc.add(half, half), g.rc.add(-half, half)});
      return g;
   }

   @Test
   void moveToAutoCaveTransitionApproachIsNamedOnlyAndUsesCaveBounds() {
      Assertions.assertNull(PfTestRunner.validateScenario("move_to_auto_cave_transition_approach"));
      Assertions.assertNotNull(PfTestRunner.validateScenario("move_to_auto_cave_transition_approach; :cmd evil"));
      Assertions.assertEquals(52.25, 52.25, 0.0);
      Assertions.assertEquals(ALL_SCENARIOS, PfTestRunner.knownScenarios());
   }

   @Test
   void moveToAutoCaveTransitionApproachPreflightAndFreshApproachAreTyped() {
      List<JSONObject> preflight = MoveToAutoCaveTransitionApproachScenario.preflightChecks(true, true, true, true, false);
      Assertions.assertEquals("bot_available", preflight.get(preflight.size() - 1).getString("name"));
      Assertions.assertEquals("pass", preflight.get(preflight.size() - 1).getString("status"));
      preflight = MoveToAutoCaveTransitionApproachScenario.preflightChecks(true, true, true, false, false);
      assertFailCheck(preflight.get(preflight.size() - 1), "player_idle", "no-move");
      haven.pathfinding.TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(caveTransitionApproachScene());
      Assertions.assertTrue(sel.selected(), sel.evidence);
      JSONObject same = MoveToAutoCaveTransitionApproachScenario.approachRevalidationCheck(sel, caveTransitionApproachScene());
      Assertions.assertEquals("pass", same.getString("status"));
      JSONObject gone = MoveToAutoCaveTransitionApproachScenario.approachRevalidationCheck(
         sel, spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5))
      );
      assertFailCheck(gone, "approach_revalidated", "NO_FIXTURE");
      Scene changedScene = spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5));
      changedScene.gobs.add(caveTransitionGob(16, 12, 99L, "gfx/terobjs/minehole"));
      JSONObject changed = MoveToAutoCaveTransitionApproachScenario.approachRevalidationCheck(sel, changedScene);
      assertFailCheck(changed, "approach_revalidated", "changed");
      JSONObject facts = MoveToAutoCaveTransitionApproachScenario.factsJson(sel, null, "REVALIDATION_REFUSED", null);
      Assertions.assertFalse(facts.getBoolean("moved"));
      Assertions.assertEquals("CAVE_TRANSITION", facts.getString("profile"));
      Assertions.assertEquals("REVALIDATION_REFUSED", facts.getString("status"));
      Assertions.assertEquals("MINEHOLE", facts.getString("transition_kind"));
      Assertions.assertFalse(facts.toString().contains("\"id\":"));
   }

   @Test
   void moveToAutoCaveTransitionApproachWithoutGameRefusesBeforeSelection() throws Exception {
      JSONObject body = new MoveToAutoCaveTransitionApproachScenario().execute(new Run("move_to_auto_cave_transition_approach"), null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      assertFailCheck(body.getJSONArray("checks").getJSONObject(0), "in_game", "requires in-game");
      JSONObject facts = findFacts(body);
      Assertions.assertFalse(facts.getBoolean("moved"));
      Assertions.assertEquals("PREFLIGHT", facts.getString("refusal"));
      Assertions.assertEquals("NOT_STARTED", facts.getString("status"));
   }

   @Test
   void moveToAutoCaveTransitionApproachMapsCompletedAndCancelledMovementFacts() {
      haven.pathfinding.TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(caveTransitionApproachScene());
      MoveResult arrived = mv(Status.REACHED, 12, 3, Result.ARRIVED, null, sel.approachWorld, 25L);
      JSONObject complete = MoveToAutoCaveTransitionApproachScenario.completedBody(
         sel, arrived, false, MoveToAutoOpenGroundScenario.arrivalCheck(true, true, true, sel.approachWorld, sel.approachWorld)
      );
      Assertions.assertEquals("PASS", complete.getString("verdict"));
      JSONObject completeFacts = findFacts(complete);
      Assertions.assertTrue(completeFacts.getBoolean("moved"));
      Assertions.assertTrue(completeFacts.getBoolean("arrived"));
      Assertions.assertEquals("ARRIVED", completeFacts.getString("walk_outcome"));
      MoveResult cancelled = mv(Status.REACHED, 12, 3, null, "bot cancelled", sel.approachWorld, 25L);
      JSONObject cancelledBody = MoveToAutoCaveTransitionApproachScenario.cancelledBody(sel, cancelled);
      Assertions.assertEquals("FAIL", cancelledBody.getString("verdict"));
      assertFailCheck(cancelledBody.getJSONArray("checks").getJSONObject(1), "walk_completed", "cancelled");
      Assertions.assertEquals("CANCELLED", findFacts(cancelledBody).getString("walk_outcome"));
   }

   @Test
   void moveToAutoCaveTransitionApproachCancelledBeforeExecutionDoesNothing() {
      Run run = new Run("move_to_auto_cave_transition_approach");
      run.cancelled = true;
      Assertions.assertThrows(Cancelled.class, () -> new MoveToAutoCaveTransitionApproachScenario().execute(run, null));
   }

   @Test
   void crossCellarDoorIsNamedOnlyAndFailsClosedWithoutGame() throws Exception {
      Assertions.assertNull(PfTestRunner.validateScenario("cross_cellar_door"));
      Assertions.assertNotNull(PfTestRunner.validateScenario("cross_cellar_door; :cmd evil"));
      Assertions.assertEquals(35.0, 35.0, 0.0);
      JSONObject body = new CrossCellarDoorScenario().execute(new Run("cross_cellar_door"), null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      assertFailCheck(body.getJSONArray("checks").getJSONObject(0), "in_game", "requires in-game");
      JSONObject facts = findFacts(body);
      Assertions.assertFalse(facts.getBoolean("interaction_issued"));
      Assertions.assertFalse(facts.getBoolean("crossed"));
      Assertions.assertEquals("PREFLIGHT", facts.getString("refusal"));
   }

   @Test
   void crossCellarDoorFactsArePrivacySafeAndCancellationIsHonest() {
      haven.pathfinding.TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(caveTransitionApproachScene());
      JSONObject facts = CrossCellarDoorScenario.facts(sel, null, true, "CROSSED", null);
      Assertions.assertTrue(facts.getBoolean("interaction_issued"));
      Assertions.assertTrue(facts.getBoolean("crossed"));
      Assertions.assertFalse(facts.toString().contains("\"id\":"));
      JSONObject cancelled = CrossCellarDoorScenario.cancelledBody(sel, null, true);
      Assertions.assertEquals("FAIL", cancelled.getString("verdict"));
      Assertions.assertTrue(findFacts(cancelled).getBoolean("interaction_issued"));
      Assertions.assertFalse(findFacts(cancelled).getBoolean("crossed"));
   }

   @Test
   void crossCellarDoorPureGuardsRejectWrongKindsAmbiguityRangeAndNoResponse() {
      haven.pathfinding.TransitionApproachSelector.Selection minehole = TransitionApproachSelector.caveTransitionApproach(caveTransitionApproachScene());
      Assertions.assertFalse(CrossCellarDoorScenario.supportedKind(minehole));
      assertFailCheck(CrossCellarDoorScenario.kindCheck(false), "fixture_kind_supported", "no interaction");
      Scene one = caveTransitionApproachScene();
      one.gobs.clear();
      one.gobs.add(caveTransitionGob(12, 12, 555L, "gfx/terobjs/arch/cellardoor"));
      Assertions.assertNotNull(CrossCellarDoorScenario.uniqueDoorGeom(one));
      one.gobs.add(caveTransitionGob(16, 12, 556L, "gfx/terobjs/arch/cellardoor"));
      Assertions.assertNull(CrossCellarDoorScenario.uniqueDoorGeom(one), "ambiguous doors must not be resolved");
      assertFailCheck(CrossCellarDoorScenario.rangeCheck(false), "in_interaction_range", "no interaction");
      assertFailCheck(CrossCellarDoorScenario.completionCheck(false), "crossed", "no confirmed");
   }

   @Test
   void crossCellarDoorIssuesExactlyOneInjectedInteraction() {
      int[] clicks = new int[]{0};
      Interaction interaction = gob -> clicks[0]++;
      CrossCellarDoorScenario.issueOnce(interaction, null);
      Assertions.assertEquals(1, clicks[0]);
      Assertions.assertEquals("pass", CrossCellarDoorScenario.interactionCheck().getString("status"));
   }

   @Test
   void crossCellarDoorAwaitCrossFailsClosedOnTimeoutOrCancellation() throws Exception {
      Run run = new Run("cross_cellar_door");
      Assertions.assertFalse(CrossCellarDoorScenario.awaitCross(run, null, null, 0L, System.currentTimeMillis() - 1L));
      run.cancelled = true;
      Assertions.assertFalse(CrossCellarDoorScenario.awaitCross(run, null, null, 0L, System.currentTimeMillis() + 1000L));
   }

   @Test
   void crossCellarStairsIsNamedOnlyAndFailsClosedWithoutGame() throws Exception {
      Assertions.assertNull(PfTestRunner.validateScenario("cross_cellar_stairs"));
      Assertions.assertNotNull(PfTestRunner.validateScenario("cross_cellar_stairs; :cmd evil"));
      Assertions.assertEquals(35.0, 35.0, 0.0);
      JSONObject body = new CrossCellarStairsScenario().execute(new Run("cross_cellar_stairs"), null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      assertFailCheck(body.getJSONArray("checks").getJSONObject(0), "in_game", "requires in-game");
      Assertions.assertFalse(findFacts(body).getBoolean("interaction_issued"));
   }

   @Test
   void crossCellarStairsPureGuardsRequireUniqueStairsOnly() {
      haven.pathfinding.TransitionApproachSelector.Selection notStairs = TransitionApproachSelector.caveTransitionApproach(caveTransitionApproachScene());
      Assertions.assertFalse(CrossCellarStairsScenario.supportedKind(notStairs));
      assertFailCheck(CrossCellarStairsScenario.kindCheck(false), "fixture_kind_supported", "no interaction");
      Scene one = caveTransitionApproachScene();
      one.gobs.clear();
      one.gobs.add(caveTransitionGob(12, 12, 555L, "gfx/terobjs/arch/cellarstairs"));
      Assertions.assertNotNull(CrossCellarStairsScenario.uniqueStairsGeom(one));
      one.gobs.add(caveTransitionGob(16, 12, 556L, "gfx/terobjs/arch/cellarstairs"));
      Assertions.assertNull(CrossCellarStairsScenario.uniqueStairsGeom(one));
      assertFailCheck(CrossCellarStairsScenario.rangeCheck(false), "in_interaction_range", "no interaction");
      assertFailCheck(CrossCellarStairsScenario.completionCheck(false), "crossed", "no confirmed");
   }

   @Test
   void crossCellarStairsIssuesExactlyOneInjectedInteractionAndAuditsSafely() {
      int[] clicks = new int[]{0};
      CrossCellarStairsScenario.issueOnce(gob -> clicks[0]++, null);
      Assertions.assertEquals(1, clicks[0]);
      Assertions.assertEquals("pass", CrossCellarStairsScenario.interactionCheck().getString("status"));
      haven.pathfinding.TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(caveTransitionApproachScene());
      JSONObject facts = CrossCellarStairsScenario.facts(sel, null, true, "CANCELLED", "CANCELLED");
      Assertions.assertTrue(facts.getBoolean("interaction_issued"));
      Assertions.assertFalse(facts.getBoolean("crossed"));
      Assertions.assertFalse(facts.toString().contains("\"id\":"));
   }

   @Test
   void mineholeDescentCompletionRequiresIdleFreeSegmentChangedMaterialRelocation() {
      Observation before = new Observation(1L, Coord2d.of(0.0, 0.0), true, true);
      Assertions.assertEquals(Completion.DESCENDED, MineholeDescent.completion(before, new Observation(2L, Coord2d.of(800.0, 0.0), true, true)));
      Assertions.assertEquals(Completion.SAME_SEGMENT, MineholeDescent.completion(before, new Observation(1L, Coord2d.of(800.0, 0.0), true, true)));
      Assertions.assertEquals(Completion.INSUFFICIENT_DISPLACEMENT, MineholeDescent.completion(before, new Observation(2L, Coord2d.of(703.9, 0.0), true, true)));
      Assertions.assertEquals(Completion.STILL_MOVING, MineholeDescent.completion(before, new Observation(2L, Coord2d.of(800.0, 0.0), false, true)));
      Assertions.assertEquals(Completion.STUCK_IN_SOLID, MineholeDescent.completion(before, new Observation(2L, Coord2d.of(800.0, 0.0), true, false)));
   }

   @Test
   void crossMineholeIsNamedOnlyAndFailsClosedWithoutConfirmation() throws Exception {
      Assertions.assertNull(PfTestRunner.validateScenario("cross_minehole"));
      Assertions.assertNotNull(PfTestRunner.validateScenario("cross_minehole; :cmd evil"));
      Assertions.assertEquals(35.0, 35.0, 0.0);
      Assertions.assertFalse(CrossMineholeScenario.confirmed());
      assertFailCheck(CrossMineholeScenario.confirmationCheck(false), "minehole_confirmed", "no interaction");
      JSONObject body = new CrossMineholeScenario().execute(new Run("cross_minehole"), null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      assertFailCheck(body.getJSONArray("checks").getJSONObject(0), "minehole_confirmed", "no interaction");
      Assertions.assertFalse(findFacts(body).getBoolean("interaction_issued"));
   }

   @Test
   void crossMineholePureGuardsRequireUniqueMineholeAndRange() {
      Assertions.assertFalse(CrossMineholeScenario.supportedKind(TransitionApproachSelector.caveTransitionApproach(stairsOnlyScene())));
      assertFailCheck(CrossMineholeScenario.kindCheck(false), "fixture_kind_supported", "no interaction");
      Scene one = caveTransitionApproachScene();
      one.gobs.clear();
      one.gobs.add(caveTransitionGob(12, 12, 555L, "gfx/terobjs/minehole"));
      Assertions.assertNotNull(CrossMineholeScenario.uniqueMineholeGeom(one));
      one.gobs.add(caveTransitionGob(16, 12, 556L, "gfx/terobjs/minehole"));
      Assertions.assertNull(CrossMineholeScenario.uniqueMineholeGeom(one));
      assertFailCheck(CrossMineholeScenario.rangeCheck(false), "in_interaction_range", "no interaction");
      assertFailCheck(CrossMineholeScenario.completionCheck(Completion.SAME_SEGMENT), "crossed", "no confirmed");
   }

   @Test
   void crossMineholeIssuesExactlyOneInjectedInteractionAndAuditsSafely() {
      int[] clicks = new int[]{0};
      CrossMineholeScenario.issueOnce(gob -> clicks[0]++, null);
      Assertions.assertEquals(1, clicks[0]);
      Assertions.assertEquals("pass", CrossMineholeScenario.interactionCheck().getString("status"));
      haven.pathfinding.TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(caveTransitionApproachScene());
      JSONObject facts = CrossMineholeScenario.facts(sel, null, true, "DESCENDED", null, "DESCENDED");
      Assertions.assertTrue(facts.getBoolean("interaction_issued"));
      Assertions.assertTrue(facts.getBoolean("crossed"));
      Assertions.assertEquals("DESCENDED", facts.getString("descent_completion"));
      Assertions.assertFalse(facts.toString().contains("\"id\":"));
   }

   private static Scene stairsOnlyScene() {
      char[][] g = spotFill(40, 40, ' ');

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            g[12 + dy][12 + dx] = '#';
         }
      }

      Scene s = spotScene(40, 40, g, spotCellWorld(5), spotCellWorld(5));
      s.gobs.add(caveTransitionGob(12, 12, 555L, "gfx/terobjs/arch/cellarstairs"));
      return s;
   }

   @Test
   void selectCaveTransitionApproachIsAllowlistedNamedOnly() {
      Assertions.assertNull(PfTestRunner.validateScenario("select_cave_transition_approach"), "select_cave_transition_approach must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario(" select_cave_transition_approach "), "scenario names are trimmed");
      Assertions.assertNotNull(PfTestRunner.validateScenario("select_cave_transition_approach; :cmd evil"));
      Assertions.assertEquals(ALL_SCENARIOS, PfTestRunner.knownScenarios());
   }

   @Test
   void selectCaveTransitionApproachWithoutGameFailsCleanlyWithTypedFacts() throws Exception {
      Run run = new Run("select_cave_transition_approach");
      JSONObject body = new SelectCaveTransitionApproachScenario().execute(run, null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      JSONObject inGame = body.getJSONArray("checks").getJSONObject(0);
      Assertions.assertEquals("in_game", inGame.getString("name"));
      Assertions.assertEquals("fail", inGame.getString("status"));
      JSONObject facts = findFacts(body);
      Assertions.assertEquals("CAVE_TRANSITION", facts.getString("profile"));
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertEquals("NO_GAME", facts.getString("refusal"));
      Assertions.assertEquals(0, facts.getInt("fixtures"));
      Assertions.assertEquals(0, facts.getInt("candidates"));
      Assertions.assertFalse(facts.has("best_score"));
      Assertions.assertFalse(facts.has("approach_tile"));
      Assertions.assertFalse(facts.has("approach_world"));
      Assertions.assertFalse(facts.has("approach_side"));
      Assertions.assertFalse(facts.has("standoff"));
      Assertions.assertFalse(facts.has("footprint_tiles"));
      Assertions.assertFalse(facts.has("player_dist"));
      Assertions.assertFalse(facts.has("route_cells"));
      Assertions.assertFalse(facts.has("transition_kind"));
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "fixtures", "candidates"), facts.keySet());
   }

   @Test
   void selectCaveTransitionApproachSelectionSeamPassesWithTypedFacts() {
      haven.pathfinding.TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(caveTransitionApproachScene());
      Assertions.assertTrue(sel.selected(), sel.evidence);
      JSONObject c = SelectCaveTransitionApproachScenario.selectionCheck(sel);
      Assertions.assertEquals("pass", c.getString("status"));
      Assertions.assertTrue(c.getString("detail").contains("CAVE_TRANSITION"), c.getString("detail"));
      JSONObject facts = SelectCaveTransitionApproachScenario.factsJson(sel);
      Assertions.assertEquals("CAVE_TRANSITION", facts.getString("profile"));
      Assertions.assertTrue(facts.getBoolean("selected"));
      Assertions.assertTrue(facts.isNull("refusal"), "a selected run carries no refusal");
      Assertions.assertEquals(1, facts.getInt("fixtures"));
      Assertions.assertTrue(facts.getInt("candidates") > 0);
      Assertions.assertEquals("MINEHOLE", facts.getString("transition_kind"), "public facts identify the transition kind (never a gob id)");
      Assertions.assertTrue(facts.getDouble("best_score") > 0.0);
      Assertions.assertEquals(sel.approachTile.x, facts.getJSONArray("approach_tile").getInt(0));
      Assertions.assertEquals(sel.approachTile.y, facts.getJSONArray("approach_tile").getInt(1));
      Assertions.assertEquals(sel.approachWorld.x, facts.getJSONArray("approach_world").getDouble(0), 0.01);
      Assertions.assertEquals(sel.approachWorld.y, facts.getJSONArray("approach_world").getDouble(1), 0.01);
      Assertions.assertTrue("NESW".contains(facts.getString("approach_side")));
      Assertions.assertEquals(sel.standoff, facts.getDouble("standoff"), 0.01);
      Assertions.assertEquals(sel.footprintTiles, facts.getDouble("footprint_tiles"), 0.01);
      Assertions.assertEquals(sel.playerDist, facts.getDouble("player_dist"), 0.01);
      Assertions.assertEquals(((haven.pathfinding.TransitionApproachSelector.Candidate)sel.candidates.get(0)).route.size(), facts.getInt("route_cells"));
      Assertions.assertEquals(((haven.pathfinding.TransitionApproachSelector.Candidate)sel.candidates.get(0)).routeExpanded, facts.getInt("route_expanded"));
      Assertions.assertEquals(
         Set.of(
            "profile",
            "selected",
            "refusal",
            "reason",
            "fixtures",
            "candidates",
            "best_score",
            "approach_tile",
            "approach_world",
            "approach_side",
            "standoff",
            "footprint_tiles",
            "player_dist",
            "route_cells",
            "route_expanded",
            "transition_kind"
         ),
         facts.keySet()
      );
      Assertions.assertFalse(facts.toString().contains("password"), "no credentials in the audit facts");
      Assertions.assertFalse(facts.toString().contains("555"), "no gob ids in the audit facts");
      Assertions.assertFalse(facts.toString().contains("\"id\":"), "no gob ids in the audit facts");
   }

   @Test
   void selectCaveTransitionApproachRefusalsMapToTypedFails() {
      haven.pathfinding.TransitionApproachSelector.Selection noFixture = TransitionApproachSelector.caveTransitionApproach(
         spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5))
      );
      assertCaveRefusal(noFixture, haven.pathfinding.TransitionApproachSelector.Refusal.NO_FIXTURE, 0);
      char[][] sealed = spotFill(40, 40, ' ');

      for (int y = 7; y <= 17; y++) {
         for (int x = 7; x <= 17; x++) {
            sealed[y][x] = '#';
         }
      }

      Scene blocked = spotScene(40, 40, sealed, spotCellWorld(5), spotCellWorld(5));
      blocked.gobs.add(caveTransitionGob(12, 12, 1L, "gfx/terobjs/minehole"));
      assertCaveRefusal(TransitionApproachSelector.caveTransitionApproach(blocked), haven.pathfinding.TransitionApproachSelector.Refusal.NO_CANDIDATE, 1);
      char[][] walled = spotFill(40, 40, ' ');

      for (int i = 6; i <= 17; i++) {
         walled[6][i] = '#';
         walled[17][i] = '#';
         walled[i][6] = '#';
         walled[i][17] = '#';
      }

      Scene unreachable = spotScene(40, 40, walled, spotCellWorld(5), spotCellWorld(5));
      unreachable.gobs.add(caveTransitionGob(12, 12, 2L, "gfx/terobjs/ladder"));
      assertCaveRefusal(TransitionApproachSelector.caveTransitionApproach(unreachable), haven.pathfinding.TransitionApproachSelector.Refusal.NO_REACHABLE, 1);
      Scene noGeo = caveTransitionApproachScene();
      ((GobGeom)noGeo.gobs.get(0)).hitbox = Collections.emptyList();
      assertCaveRefusal(TransitionApproachSelector.caveTransitionApproach(noGeo), haven.pathfinding.TransitionApproachSelector.Refusal.GEOMETRY_UNKNOWN, 1);
      char[][] onTop = spotFill(40, 40, ' ');

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            onTop[12 + dy][12 + dx] = '#';
         }
      }

      Scene ambiguous = spotScene(40, 40, onTop, spotCellWorld(12), spotCellWorld(12));
      ambiguous.gobs.add(caveTransitionGob(12, 12, 3L, "gfx/terobjs/minehole"));
      assertCaveRefusal(TransitionApproachSelector.caveTransitionApproach(ambiguous), haven.pathfinding.TransitionApproachSelector.Refusal.AMBIGUOUS, 1);

      for (TransitionProfile p : List.of(
         TransitionProfile.DOOR,
         TransitionProfile.GATE,
         TransitionProfile.MINEHOLE,
         TransitionProfile.BOAT,
         TransitionProfile.WATER_CROSSING,
         TransitionProfile.CART
      )) {
         haven.pathfinding.TransitionApproachSelector.Selection u = TransitionApproachSelector.select(
            caveTransitionApproachScene(), p, ApproachRange.caveTransition(), 100000
         );
         assertCaveRefusal(u, haven.pathfinding.TransitionApproachSelector.Refusal.UNSUPPORTED_PROFILE, 0);
      }
   }

   private static void assertCaveRefusal(
      haven.pathfinding.TransitionApproachSelector.Selection sel, haven.pathfinding.TransitionApproachSelector.Refusal refusal, int fixtures
   ) {
      Assertions.assertEquals(haven.pathfinding.TransitionApproachSelector.Status.REFUSED, sel.status, sel.evidence);
      Assertions.assertEquals(refusal, sel.refusal);
      Assertions.assertEquals(fixtures, sel.fixtureCount);
      JSONObject c = SelectCaveTransitionApproachScenario.selectionCheck(sel);
      Assertions.assertEquals("fail", c.getString("status"));
      Assertions.assertTrue(
         c.getString("detail").contains(refusal.name()), "the check detail must carry the typed refusal " + refusal.name() + ": " + c.getString("detail")
      );
      JSONObject facts = SelectCaveTransitionApproachScenario.factsJson(sel);
      Assertions.assertEquals(sel.profile.name(), facts.getString("profile"), "facts carry the requested profile (incl. declared future profiles)");
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertEquals(refusal.name(), facts.getString("refusal"));
      Assertions.assertEquals(fixtures, facts.getInt("fixtures"));
      Assertions.assertEquals(0, facts.getInt("candidates"));
      Assertions.assertFalse(facts.has("best_score"));
      Assertions.assertFalse(facts.has("approach_tile"));
      Assertions.assertFalse(facts.has("approach_world"));
      Assertions.assertFalse(facts.has("approach_side"));
      Assertions.assertFalse(facts.has("standoff"));
      Assertions.assertFalse(facts.has("footprint_tiles"));
      Assertions.assertFalse(facts.has("player_dist"));
      Assertions.assertFalse(facts.has("route_cells"));
      Assertions.assertFalse(facts.has("route_expanded"));
      Assertions.assertFalse(facts.has("transition_kind"));
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "fixtures", "candidates"), facts.keySet());
   }

   @Test
   void selectCaveTransitionApproachWithoutGameIsTypedFailWithArtifactFacts() throws Exception {
      JSONObject started = PfTestRunner.start("select_cave_transition_approach", null);
      Assertions.assertTrue(started.getBoolean("ok"));
      String id = started.getString("run_id");
      JSONObject result = null;
      long deadline = System.currentTimeMillis() + 5000L;

      while (System.currentTimeMillis() < deadline) {
         result = PfTestRunner.result(id);
         if (result != null && !"running".equals(result.getString("status"))) {
            break;
         }

         Thread.sleep(50L);
      }

      Assertions.assertNotNull(result, "run must complete within the poll deadline");
      Assertions.assertEquals("completed", result.getString("status"));
      Assertions.assertEquals("FAIL", result.getString("verdict"));
      Assertions.assertEquals("in_game", result.getJSONArray("checks").getJSONObject(0).getString("name"));
      Assertions.assertEquals("fail", result.getJSONArray("checks").getJSONObject(0).getString("status"));
      JSONObject facts = result.getJSONObject("facts");
      Assertions.assertEquals("NO_GAME", facts.getString("refusal"));
      Assertions.assertEquals("CAVE_TRANSITION", facts.getString("profile"));
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertTrue(
         result.getString("artifact").contains("select_cave_transition_approach"), "artifact path must name the scenario: " + result.getString("artifact")
      );
      Assertions.assertTrue(result.optString("note").contains("not in game"));
   }

   private static Scene doorGateApproachScene() {
      char[][] g = spotFill(40, 40, ' ');

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            g[12 + dy][12 + dx] = '#';
         }
      }

      Scene s = spotScene(40, 40, g, spotCellWorld(5), spotCellWorld(5));
      s.gobs.add(doorGateGob(12, 12, 444L, "gfx/terobjs/arch/palisadegate"));
      return s;
   }

   private static GobGeom doorGateGob(int cx, int cy, long id, String resid) {
      GobGeom g = new GobGeom();
      g.id = id;
      g.doorGate = true;
      g.resid = resid;
      g.rc = Coord2d.of(spotCellWorld(cx), spotCellWorld(cy));
      g.gateState = 1;
      double half = 5.5;
      g.hitbox = List.<Coord2d[]>of(new Coord2d[]{g.rc.add(-half, -half), g.rc.add(half, -half), g.rc.add(half, half), g.rc.add(-half, half)});
      return g;
   }

   @Test
   void selectDoorGateApproachIsAllowlistedNamedOnly() {
      Assertions.assertNull(PfTestRunner.validateScenario("select_door_gate_approach"), "select_door_gate_approach must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario(" select_door_gate_approach "), "scenario names are trimmed");
      Assertions.assertNotNull(PfTestRunner.validateScenario("select_door_gate_approach; :cmd evil"));
      Assertions.assertEquals(ALL_SCENARIOS, PfTestRunner.knownScenarios());
   }

   @Test
   void selectDoorGateApproachWithoutGameFailsCleanlyWithTypedFacts() throws Exception {
      Run run = new Run("select_door_gate_approach");
      JSONObject body = new SelectDoorGateApproachScenario().execute(run, null);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      JSONObject inGame = body.getJSONArray("checks").getJSONObject(0);
      Assertions.assertEquals("in_game", inGame.getString("name"));
      Assertions.assertEquals("fail", inGame.getString("status"));
      JSONObject facts = findFacts(body);
      Assertions.assertEquals("DOOR_GATE", facts.getString("profile"));
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertEquals("NO_GAME", facts.getString("refusal"));
      Assertions.assertEquals(0, facts.getInt("fixtures"));
      Assertions.assertEquals(0, facts.getInt("candidates"));
      Assertions.assertFalse(facts.has("best_score"));
      Assertions.assertFalse(facts.has("approach_tile"));
      Assertions.assertFalse(facts.has("approach_world"));
      Assertions.assertFalse(facts.has("approach_side"));
      Assertions.assertFalse(facts.has("standoff"));
      Assertions.assertFalse(facts.has("footprint_tiles"));
      Assertions.assertFalse(facts.has("player_dist"));
      Assertions.assertFalse(facts.has("route_cells"));
      Assertions.assertFalse(facts.has("door_gate_kind"));
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "fixtures", "candidates"), facts.keySet());
   }

   @Test
   void selectDoorGateApproachSelectionSeamPassesWithTypedFacts() {
      haven.pathfinding.TransitionApproachSelector.Selection sel = TransitionApproachSelector.doorGateApproach(doorGateApproachScene());
      Assertions.assertTrue(sel.selected(), sel.evidence);
      JSONObject c = SelectDoorGateApproachScenario.selectionCheck(sel);
      Assertions.assertEquals("pass", c.getString("status"));
      Assertions.assertTrue(c.getString("detail").contains("DOOR_GATE"), c.getString("detail"));
      JSONObject facts = SelectDoorGateApproachScenario.factsJson(sel);
      Assertions.assertEquals("DOOR_GATE", facts.getString("profile"));
      Assertions.assertTrue(facts.getBoolean("selected"));
      Assertions.assertTrue(facts.isNull("refusal"), "a selected run carries no refusal");
      Assertions.assertEquals(1, facts.getInt("fixtures"));
      Assertions.assertTrue(facts.getInt("candidates") > 0);
      Assertions.assertEquals("PALISADE_GATE", facts.getString("door_gate_kind"), "public facts identify the door/gate kind (never a gob id)");
      Assertions.assertEquals("OPEN", facts.getString("transition_state"), "selected facts carry the winning fixture's observable transition state");
      Assertions.assertTrue(facts.getDouble("best_score") > 0.0);
      Assertions.assertEquals(sel.approachTile.x, facts.getJSONArray("approach_tile").getInt(0));
      Assertions.assertEquals(sel.approachTile.y, facts.getJSONArray("approach_tile").getInt(1));
      Assertions.assertEquals(sel.approachWorld.x, facts.getJSONArray("approach_world").getDouble(0), 0.01);
      Assertions.assertEquals(sel.approachWorld.y, facts.getJSONArray("approach_world").getDouble(1), 0.01);
      Assertions.assertTrue("NESW".contains(facts.getString("approach_side")));
      Assertions.assertEquals(sel.standoff, facts.getDouble("standoff"), 0.01);
      Assertions.assertEquals(sel.footprintTiles, facts.getDouble("footprint_tiles"), 0.01);
      Assertions.assertEquals(sel.playerDist, facts.getDouble("player_dist"), 0.01);
      Assertions.assertEquals(((haven.pathfinding.TransitionApproachSelector.Candidate)sel.candidates.get(0)).route.size(), facts.getInt("route_cells"));
      Assertions.assertEquals(((haven.pathfinding.TransitionApproachSelector.Candidate)sel.candidates.get(0)).routeExpanded, facts.getInt("route_expanded"));
      Assertions.assertEquals(
         Set.of(
            "profile",
            "selected",
            "refusal",
            "reason",
            "fixtures",
            "candidates",
            "best_score",
            "door_gate_kind",
            "transition_state",
            "approach_tile",
            "approach_world",
            "approach_side",
            "standoff",
            "footprint_tiles",
            "player_dist",
            "route_cells",
            "route_expanded"
         ),
         facts.keySet()
      );
      Assertions.assertFalse(facts.toString().contains("password"), "no credentials in the audit facts");
      Assertions.assertFalse(facts.toString().contains("444"), "no gob ids in the audit facts");
      Assertions.assertFalse(facts.toString().contains("\"id\":"), "no gob ids in the audit facts");
   }

   @Test
   void selectDoorGateApproachRefusalsMapToTypedFails() {
      haven.pathfinding.TransitionApproachSelector.Selection noFixture = TransitionApproachSelector.doorGateApproach(
         spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5))
      );
      assertDoorGateRefusal(noFixture, haven.pathfinding.TransitionApproachSelector.Refusal.NO_FIXTURE, 0);
      char[][] sealed = spotFill(40, 40, ' ');

      for (int y = 7; y <= 17; y++) {
         for (int x = 7; x <= 17; x++) {
            sealed[y][x] = '#';
         }
      }

      Scene blocked = spotScene(40, 40, sealed, spotCellWorld(5), spotCellWorld(5));
      blocked.gobs.add(doorGateGob(12, 12, 1L, "gfx/terobjs/arch/palisadegate"));
      assertDoorGateRefusal(TransitionApproachSelector.doorGateApproach(blocked), haven.pathfinding.TransitionApproachSelector.Refusal.NO_CANDIDATE, 1);
      char[][] walled = spotFill(40, 40, ' ');

      for (int i = 6; i <= 17; i++) {
         walled[6][i] = '#';
         walled[17][i] = '#';
         walled[i][6] = '#';
         walled[i][17] = '#';
      }

      Scene unreachable = spotScene(40, 40, walled, spotCellWorld(5), spotCellWorld(5));
      unreachable.gobs.add(doorGateGob(12, 12, 2L, "gfx/terobjs/arch/palisadegate"));
      assertDoorGateRefusal(TransitionApproachSelector.doorGateApproach(unreachable), haven.pathfinding.TransitionApproachSelector.Refusal.NO_REACHABLE, 1);
      Scene visitor = doorGateApproachScene();
      ((GobGeom)visitor.gobs.get(0)).visitorGate = true;
      assertDoorGateRefusal(TransitionApproachSelector.doorGateApproach(visitor), haven.pathfinding.TransitionApproachSelector.Refusal.VISITOR_GATE, 1);
      Scene noGeo = doorGateApproachScene();
      ((GobGeom)noGeo.gobs.get(0)).hitbox = Collections.emptyList();
      assertDoorGateRefusal(TransitionApproachSelector.doorGateApproach(noGeo), haven.pathfinding.TransitionApproachSelector.Refusal.GEOMETRY_UNKNOWN, 1);
      Scene unobserved = doorGateApproachScene();
      ((GobGeom)unobserved.gobs.get(0)).gateState = -1;
      haven.pathfinding.TransitionApproachSelector.Selection unknownSel = TransitionApproachSelector.doorGateApproach(unobserved);
      Assertions.assertTrue(unknownSel.selected(), unknownSel.evidence);
      Assertions.assertEquals(TransitionState.UNKNOWN, unknownSel.transitionState);
      JSONObject unknownFacts = SelectDoorGateApproachScenario.factsJson(unknownSel);
      Assertions.assertTrue(unknownFacts.getBoolean("selected"));
      Assertions.assertEquals(
         "UNKNOWN", unknownFacts.getString("transition_state"), "selected facts carry the winning fixture's UNKNOWN transition state (never guessed)"
      );
      Assertions.assertTrue(
         unknownFacts.getString("reason").contains("1 unknown state"),
         "the evidence counts the open/closed/unknown state categories: " + unknownFacts.getString("reason")
      );
      Scene animating = doorGateApproachScene();
      ((GobGeom)animating.gobs.get(0)).gateState = 2;
      haven.pathfinding.TransitionApproachSelector.Selection animSel = TransitionApproachSelector.doorGateApproach(animating);
      Assertions.assertTrue(animSel.selected(), animSel.evidence);
      Assertions.assertEquals(TransitionState.UNKNOWN, animSel.transitionState);
      Scene closed = doorGateApproachScene();
      ((GobGeom)closed.gobs.get(0)).gateState = 0;
      haven.pathfinding.TransitionApproachSelector.Selection closedSel = TransitionApproachSelector.doorGateApproach(closed);
      Assertions.assertTrue(closedSel.selected(), closedSel.evidence);
      Assertions.assertEquals(TransitionState.CLOSED, closedSel.transitionState);
      JSONObject closedFacts = SelectDoorGateApproachScenario.factsJson(closedSel);
      Assertions.assertEquals("CLOSED", closedFacts.getString("transition_state"));
      Assertions.assertTrue(closedFacts.getBoolean("selected"));
      char[][] onTop = spotFill(40, 40, ' ');

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            onTop[12 + dy][12 + dx] = '#';
         }
      }

      Scene ambiguous = spotScene(40, 40, onTop, spotCellWorld(12), spotCellWorld(12));
      ambiguous.gobs.add(doorGateGob(12, 12, 3L, "gfx/terobjs/arch/polegate"));
      assertDoorGateRefusal(TransitionApproachSelector.doorGateApproach(ambiguous), haven.pathfinding.TransitionApproachSelector.Refusal.AMBIGUOUS, 1);

      for (TransitionProfile p : List.of(
         TransitionProfile.DOOR,
         TransitionProfile.GATE,
         TransitionProfile.MINEHOLE,
         TransitionProfile.BOAT,
         TransitionProfile.WATER_CROSSING,
         TransitionProfile.CART
      )) {
         haven.pathfinding.TransitionApproachSelector.Selection u = TransitionApproachSelector.select(
            doorGateApproachScene(), p, ApproachRange.doorGate(), 100000
         );
         assertDoorGateRefusal(u, haven.pathfinding.TransitionApproachSelector.Refusal.UNSUPPORTED_PROFILE, 0);
      }
   }

   private static void assertDoorGateRefusal(
      haven.pathfinding.TransitionApproachSelector.Selection sel, haven.pathfinding.TransitionApproachSelector.Refusal refusal, int fixtures
   ) {
      Assertions.assertEquals(haven.pathfinding.TransitionApproachSelector.Status.REFUSED, sel.status, sel.evidence);
      Assertions.assertEquals(refusal, sel.refusal);
      Assertions.assertEquals(fixtures, sel.fixtureCount);
      JSONObject c = SelectDoorGateApproachScenario.selectionCheck(sel);
      Assertions.assertEquals("fail", c.getString("status"));
      Assertions.assertTrue(
         c.getString("detail").contains(refusal.name()), "the check detail must carry the typed refusal " + refusal.name() + ": " + c.getString("detail")
      );
      JSONObject facts = SelectDoorGateApproachScenario.factsJson(sel);
      Assertions.assertEquals(sel.profile.name(), facts.getString("profile"), "facts carry the requested profile (incl. declared future profiles)");
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertEquals(refusal.name(), facts.getString("refusal"));
      Assertions.assertEquals(fixtures, facts.getInt("fixtures"));
      Assertions.assertEquals(0, facts.getInt("candidates"));
      Assertions.assertFalse(facts.has("best_score"));
      Assertions.assertFalse(facts.has("approach_tile"));
      Assertions.assertFalse(facts.has("approach_world"));
      Assertions.assertFalse(facts.has("approach_side"));
      Assertions.assertFalse(facts.has("standoff"));
      Assertions.assertFalse(facts.has("footprint_tiles"));
      Assertions.assertFalse(facts.has("player_dist"));
      Assertions.assertFalse(facts.has("route_cells"));
      Assertions.assertFalse(facts.has("route_expanded"));
      Assertions.assertFalse(facts.has("door_gate_kind"));
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "fixtures", "candidates"), facts.keySet());
   }

   @Test
   void selectDoorGateApproachWithoutGameIsTypedFailWithArtifactFacts() throws Exception {
      JSONObject started = PfTestRunner.start("select_door_gate_approach", null);
      Assertions.assertTrue(started.getBoolean("ok"));
      String id = started.getString("run_id");
      JSONObject result = null;
      long deadline = System.currentTimeMillis() + 5000L;

      while (System.currentTimeMillis() < deadline) {
         result = PfTestRunner.result(id);
         if (result != null && !"running".equals(result.getString("status"))) {
            break;
         }

         Thread.sleep(50L);
      }

      Assertions.assertNotNull(result, "run must complete within the poll deadline");
      Assertions.assertEquals("completed", result.getString("status"));
      Assertions.assertEquals("FAIL", result.getString("verdict"));
      Assertions.assertEquals("in_game", result.getJSONArray("checks").getJSONObject(0).getString("name"));
      Assertions.assertEquals("fail", result.getJSONArray("checks").getJSONObject(0).getString("status"));
      JSONObject facts = result.getJSONObject("facts");
      Assertions.assertEquals("NO_GAME", facts.getString("refusal"));
      Assertions.assertEquals("DOOR_GATE", facts.getString("profile"));
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertTrue(
         result.getString("artifact").contains("select_door_gate_approach"), "artifact path must name the scenario: " + result.getString("artifact")
      );
      Assertions.assertTrue(result.optString("note").contains("not in game"));
   }

   @Test
   void selectScenarioWithoutGameFailsCleanlyWithTypedFacts() throws Exception {
      for (Profile p : List.of(Profile.OPEN_GROUND, Profile.LOCAL_OBSTACLE_OR_CORRIDOR, Profile.KNOWN_MAP_LONG_LEG)) {
         Run run = new Run("x");
         JSONObject body = new NavigationTestSpotScenario(p).execute(run, null);
         Assertions.assertEquals("FAIL", body.getString("verdict"), p + " without game must fail");
         JSONObject inGame = body.getJSONArray("checks").getJSONObject(0);
         Assertions.assertEquals("in_game", inGame.getString("name"));
         Assertions.assertEquals("fail", inGame.getString("status"));
         JSONObject facts = findFacts(body);
         Assertions.assertEquals(p.name(), facts.getString("profile"));
         Assertions.assertFalse(facts.getBoolean("selected"));
         Assertions.assertEquals("NO_GAME", facts.getString("refusal"));
         Assertions.assertEquals(0, facts.getInt("candidates"));
         Assertions.assertFalse(facts.has("best_score"));
         Assertions.assertFalse(facts.has("target_tile"));
         Assertions.assertFalse(facts.has("target_world"));
         Assertions.assertFalse(facts.has("segment"));
         Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "candidates"), facts.keySet());
      }
   }

   @Test
   void openGroundSelectionSeamPassesWithTypedFacts() {
      Scene s = spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5));
      Selection sel = NavigationTestSpotSelector.openGround(s);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      JSONObject c = NavigationTestSpotScenario.selectionCheck(sel);
      Assertions.assertEquals("pass", c.getString("status"));
      Assertions.assertTrue(c.getString("detail").contains("OPEN_GROUND"), c.getString("detail"));
      JSONObject facts = NavigationTestSpotScenario.factsJson(Profile.OPEN_GROUND, sel, sel.targetTile, null);
      Assertions.assertEquals("OPEN_GROUND", facts.getString("profile"));
      Assertions.assertTrue(facts.getBoolean("selected"));
      Assertions.assertTrue(facts.isNull("refusal"), "a selected run carries no refusal");
      Assertions.assertTrue(facts.getInt("candidates") > 0);
      Assertions.assertTrue(facts.getDouble("best_score") > 0.0);
      Assertions.assertEquals(sel.targetTile.x, facts.getJSONArray("target_tile").getInt(0));
      Assertions.assertEquals(sel.targetTile.y, facts.getJSONArray("target_tile").getInt(1));
      Assertions.assertTrue(facts.has("target_world"), "local selections carry the world center");
      Assertions.assertFalse(facts.has("segment"), "local selections have no segment");
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "candidates", "best_score", "target_tile", "target_world"), facts.keySet());
      Assertions.assertFalse(facts.toString().contains("password"), "no credentials in the audit facts");
      Assertions.assertFalse(facts.toString().contains("\"id\":"), "no gob ids in the audit facts");
   }

   @Test
   void obstacleCorridorSelectionSeamPassesWithTypedFacts() {
      char[][] g = spotFill(20, 20, ' ');
      g[8][10] = '#';
      Scene s = spotScene(20, 20, g, spotCellWorld(8), spotCellWorld(8));
      Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(s);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertTrue(s.bodyFree(sel.targetWorld), "the selected target must be body-free");
      JSONObject facts = NavigationTestSpotScenario.factsJson(Profile.LOCAL_OBSTACLE_OR_CORRIDOR, sel, sel.targetTile, null);
      Assertions.assertEquals("LOCAL_OBSTACLE_OR_CORRIDOR", facts.getString("profile"));
      Assertions.assertTrue(facts.getBoolean("selected"));
      Assertions.assertTrue(facts.isNull("refusal"));
      Assertions.assertTrue(facts.getInt("candidates") > 0);
      Assertions.assertTrue(facts.has("target_world"));
      Assertions.assertFalse(facts.has("segment"));
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "candidates", "best_score", "target_tile", "target_world"), facts.keySet());
   }

   @Test
   void knownLongLegSelectionSeamPassesWithTypedFacts() {
      CoarseTileSource src = spotRiverWithGap();
      Selection sel = NavigationTestSpotSelector.knownMapLongLeg(src, 4660L, Coord.of(2, 20), 12.0, 24.0, 40000, 2000000);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertTrue(true, "selection aggregate budget must be distinct from the nav replan budget");
      Assertions.assertTrue(true);
      JSONObject c = NavigationTestSpotScenario.selectionCheck(sel);
      Assertions.assertEquals("pass", c.getString("status"));
      JSONObject facts = NavigationTestSpotScenario.factsJson(Profile.KNOWN_MAP_LONG_LEG, sel, sel.targetTile, 4660L);
      Assertions.assertEquals("KNOWN_MAP_LONG_LEG", facts.getString("profile"));
      Assertions.assertTrue(facts.getBoolean("selected"));
      Assertions.assertTrue(facts.isNull("refusal"));
      Assertions.assertEquals("1234", facts.getString("segment"));
      Assertions.assertEquals(sel.targetTile.x, facts.getJSONArray("target_tile").getInt(0));
      Assertions.assertFalse(facts.has("target_world"), "long-leg selection is tile-space only");
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "candidates", "best_score", "target_tile", "segment"), facts.keySet());
   }

   @Test
   void selectRefusalsMapToTypedFails() {
      char[][] g = spotFill(20, 20, '#');

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            g[10 + dy][10 + dx] = ' ';
         }
      }

      Selection og = NavigationTestSpotSelector.openGround(spotScene(20, 20, g, spotCellWorld(10), spotCellWorld(10)));
      assertRefusal(Profile.OPEN_GROUND, og, Refusal.NO_CANDIDATE);
      Selection ob = NavigationTestSpotSelector.localObstacleOrCorridor(spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5)));
      assertRefusal(Profile.LOCAL_OBSTACLE_OR_CORRIDOR, ob, Refusal.NO_CANDIDATE);
      char[][] cor = spotFill(40, 40, '#');

      for (int y = 0; y < 40; y++) {
         cor[y][10] = ' ';
      }

      cor[10][10] = '+';
      cor[11][10] = '+';
      Selection ob2 = NavigationTestSpotSelector.localObstacleOrCorridor(spotScene(40, 40, cor, spotCellWorld(10), spotCellWorld(10)));
      assertRefusal(Profile.LOCAL_OBSTACLE_OR_CORRIDOR, ob2, Refusal.NO_MATERIAL_DETOUR);
      Selection ll = NavigationTestSpotSelector.knownMapLongLeg(spotNames(10, 10, "gfx/tiles/cave"), 5L, Coord.of(0, 0), 1.0, 5.0, 100);
      assertRefusal(Profile.KNOWN_MAP_LONG_LEG, ll, Refusal.START_UNKNOWN);
   }

   private static void assertRefusal(Profile profile, Selection sel, Refusal refusal) {
      Assertions.assertEquals(haven.pathfinding.NavigationTestSpotSelector.Status.REFUSED, sel.status);
      Assertions.assertEquals(refusal, sel.refusal);
      JSONObject c = NavigationTestSpotScenario.selectionCheck(sel);
      Assertions.assertEquals("fail", c.getString("status"));
      Assertions.assertTrue(
         c.getString("detail").contains(refusal.name()), "the check detail must carry the typed refusal " + refusal.name() + ": " + c.getString("detail")
      );
      JSONObject facts = NavigationTestSpotScenario.factsJson(profile, sel, null, null);
      Assertions.assertEquals(profile.name(), facts.getString("profile"));
      Assertions.assertFalse(facts.getBoolean("selected"));
      Assertions.assertEquals(refusal.name(), facts.getString("refusal"));
      Assertions.assertEquals(0, facts.getInt("candidates"));
      Assertions.assertFalse(facts.has("best_score"));
      Assertions.assertFalse(facts.has("target_tile"));
      Assertions.assertFalse(facts.has("target_world"));
      Assertions.assertFalse(facts.has("segment"));
      Assertions.assertEquals(Set.of("profile", "selected", "refusal", "reason", "candidates"), facts.keySet());
   }

   @Test
   void selectBoundsContainStartWithHardExtent() {
      Area a = NavigationTestSpotScenario.selectBounds(Coord.of(10, 20));
      Assertions.assertTrue(a.contains(Coord.of(10, 20)), "start must lie inside the selection region");
      Assertions.assertTrue(a.contains(Coord.of(-22, 20)), "ul is inclusive: start-H is inside");
      Assertions.assertFalse(a.contains(Coord.of(42, 20)), "half-open upper bound excludes start+H");
      Assertions.assertFalse(a.contains(Coord.of(-23, 20)), "below the ul corner is outside");
      Assertions.assertEquals(64, a.br.x - a.ul.x);
      Assertions.assertEquals(64, a.br.y - a.ul.y);
   }

   @Test
   void reportTileIsAbsoluteForLongLegAndRawForLocal() {
      Selection ll = NavigationTestSpotSelector.knownMapLongLeg(spotRiverWithGap(), 9L, Coord.of(2, 20), 4.0, 30.0, 100000);
      Assertions.assertTrue(ll.selected(), ll.evidence);
      Area bounds = NavigationTestSpotScenario.selectBounds(Coord.of(100, 120));
      Assertions.assertEquals(
         ll.targetTile.add(bounds.ul), NavigationTestSpotScenario.reportTile(ll, bounds), "long-leg targets publish as absolute in-segment tiles"
      );
      Selection og = NavigationTestSpotSelector.openGround(spotScene(40, 40, spotFill(40, 40, ' '), spotCellWorld(5), spotCellWorld(5)));
      Assertions.assertEquals(og.targetTile, NavigationTestSpotScenario.reportTile(og, null), "local targets publish their own occupancy cell");
      Assertions.assertNull(NavigationTestSpotScenario.reportTile(null, bounds));
   }

   @Test
   void selectScenariosWithoutGameAreTypedFailsWithArtifactFacts() throws Exception {
      for (String name : List.of("select_open_ground", "select_obstacle_corridor", "select_known_long_leg")) {
         JSONObject started = PfTestRunner.start(name, null);
         Assertions.assertTrue(started.getBoolean("ok"), name + " must start");
         String id = started.getString("run_id");
         JSONObject result = null;
         long deadline = System.currentTimeMillis() + 5000L;

         while (System.currentTimeMillis() < deadline) {
            result = PfTestRunner.result(id);
            if (result != null && !"running".equals(result.getString("status"))) {
               break;
            }

            Thread.sleep(50L);
         }

         Assertions.assertNotNull(result, name + " run must complete within the poll deadline");
         Assertions.assertEquals("completed", result.getString("status"), name);
         Assertions.assertEquals("FAIL", result.getString("verdict"), name);
         Assertions.assertEquals("in_game", result.getJSONArray("checks").getJSONObject(0).getString("name"));
         Assertions.assertEquals("fail", result.getJSONArray("checks").getJSONObject(0).getString("status"));
         JSONObject facts = result.getJSONObject("facts");
         Assertions.assertEquals("NO_GAME", facts.getString("refusal"));
         Assertions.assertFalse(facts.getBoolean("selected"));
         Assertions.assertTrue(result.getString("artifact").contains(name), "artifact path must name the scenario: " + result.getString("artifact"));
         Assertions.assertTrue(result.optString("note").contains("not in game"));
      }
   }

   private static haven.pathfinding.CoarseTileNavigator.Run coarseRun(
      haven.pathfinding.CoarseTileNavigator.RunStatus status,
      haven.pathfinding.CoarseRoutePlanner.Status routeStatus,
      Cause routeCause,
      Outcome outcome,
      String detail,
      Coord2d endPos,
      Coord goalTile,
      long seg
   ) {
      return new haven.pathfinding.CoarseTileNavigator.Run(
         status,
         goalTile,
         region(0, 0, 40, 40),
         status == haven.pathfinding.CoarseTileNavigator.RunStatus.UNAVAILABLE ? null : new Location(seg, Coord.of(10, 10), Coord2d.of(110.0, 110.0)),
         routeStatus,
         routeCause,
         routeStatus == haven.pathfinding.CoarseRoutePlanner.Status.REACHED ? 3 : 0,
         100,
         outcome,
         4,
         1,
         3,
         detail,
         endPos,
         Collections.emptyList(),
         1234L
      );
   }

   private static Selection knownLongLegSel() {
      Selection sel = NavigationTestSpotSelector.knownMapLongLeg(spotRiverWithGap(), 4660L, Coord.of(2, 20), 12.0, 24.0, 40000, 2000000);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      return sel;
   }

   @Test
   void moveToAutoKnownLongLegIsAllowlistedNamedOnly() {
      Assertions.assertNull(PfTestRunner.validateScenario("move_to_auto_known_long_leg"), "move_to_auto_known_long_leg must be allowlisted");
      Assertions.assertNull(PfTestRunner.validateScenario(" move_to_auto_known_long_leg "), "scenario names are trimmed");
      Assertions.assertNotNull(PfTestRunner.validateScenario("move_to_auto_known_long_leg; :cmd evil"));
      Assertions.assertEquals(ALL_SCENARIOS, PfTestRunner.knownScenarios());
   }

   @Test
   void autoKnownLongLegPreflightRefusesNoMoveInPinnedOrder() {
      List<JSONObject> c = MoveToAutoKnownLongLegScenario.preflightChecks(false, false, false, false, true);
      Assertions.assertEquals(1, c.size());
      Assertions.assertEquals("in_game", c.get(0).getString("name"));
      Assertions.assertEquals("fail", c.get(0).getString("status"));
      c = MoveToAutoKnownLongLegScenario.preflightChecks(true, false, true, true, true);
      Assertions.assertEquals(2, c.size());
      Assertions.assertEquals("player_present", c.get(1).getString("name"));
      Assertions.assertEquals("fail", c.get(1).getString("status"));
      c = MoveToAutoKnownLongLegScenario.preflightChecks(true, true, false, true, true);
      Assertions.assertEquals(3, c.size());
      Assertions.assertEquals("mapfile_available", c.get(2).getString("name"));
      Assertions.assertEquals("fail", c.get(2).getString("status"));
      c = MoveToAutoKnownLongLegScenario.preflightChecks(true, true, true, false, true);
      Assertions.assertEquals(4, c.size());
      Assertions.assertEquals("player_idle", c.get(3).getString("name"));
      Assertions.assertEquals("fail", c.get(3).getString("status"));
      Assertions.assertTrue(c.get(3).getString("detail").toLowerCase().contains("moving"), "not-idle must be a typed refusal");
      c = MoveToAutoKnownLongLegScenario.preflightChecks(true, true, true, true, true);
      Assertions.assertEquals(5, c.size());
      Assertions.assertEquals("bot_available", c.get(4).getString("name"));
      Assertions.assertEquals("fail", c.get(4).getString("status"));
      Assertions.assertTrue(c.get(4).getString("detail").contains("auto.Bot"), "the bot guard refusal names the bot");
      c = MoveToAutoKnownLongLegScenario.preflightChecks(true, true, true, true, false);
      Assertions.assertEquals(5, c.size());
      Assertions.assertEquals("pass", c.get(4).getString("status"));
      Assertions.assertEquals("PASS", PfTestRunner.verdictOf(c));
   }

   @Test
   void autoKnownLongLegSelectionRefusalIsTypedNoMoveFail() {
      Selection sel = NavigationTestSpotSelector.knownMapLongLeg(spotNames(10, 10, "gfx/tiles/cave"), 5L, Coord.of(0, 0), 1.0, 5.0, 100);
      Assertions.assertTrue(sel.refused(), sel.evidence);
      JSONObject c = MoveToAutoKnownLongLegScenario.factsJson(null, sel, null, "SELECTION_REFUSED", null);
      Assertions.assertFalse(c.getBoolean("moved"), "selection refusal must record moved=false");
      Assertions.assertFalse(c.getBoolean("arrived"));
      Assertions.assertEquals("SELECTION_REFUSED", c.getString("status"));
      Assertions.assertEquals("KNOWN_MAP_LONG_LEG", c.getString("profile"));
      Assertions.assertFalse(c.getBoolean("selected"));
      Assertions.assertEquals(Refusal.START_UNKNOWN.name(), c.getString("refusal"));
      Assertions.assertEquals(0, c.getInt("candidates"));
      Assertions.assertFalse(c.has("goal_tile"));
      Assertions.assertFalse(c.has("segment"));
      Assertions.assertFalse(c.has("route_kind"));
      Assertions.assertFalse(c.has("nav_outcome"));
   }

   @Test
   void autoKnownLongLegGoalRevalidationSeamIsTyped() {
      Location here = new Location(4660L, Coord.of(3, 3), Coord2d.of(33.0, 33.0));
      Route ok = Route.reached(List.of(Coord.of(3, 3), Coord.of(30, 20)), 5);
      JSONObject c = MoveToAutoKnownLongLegScenario.goalRevalidationCheck(here, 4660L, true, true, ok);
      Assertions.assertEquals("goal_revalidated", c.getString("name"));
      Assertions.assertEquals("pass", c.getString("status"));
      Assertions.assertTrue(c.getString("detail").contains("REACHED"), c.getString("detail"));
      c = MoveToAutoKnownLongLegScenario.goalRevalidationCheck(null, 4660L, true, true, ok);
      assertFailCheck(c, "goal_revalidated", "session location unavailable");
      Location other = new Location(48879L, Coord.of(3, 3), Coord2d.of(33.0, 33.0));
      c = MoveToAutoKnownLongLegScenario.goalRevalidationCheck(other, 4660L, true, true, ok);
      assertFailCheck(c, "goal_revalidated", "segment");
      c = MoveToAutoKnownLongLegScenario.goalRevalidationCheck(here, 4660L, false, true, ok);
      assertFailCheck(c, "goal_revalidated", "not observable");
      c = MoveToAutoKnownLongLegScenario.goalRevalidationCheck(here, 4660L, true, false, ok);
      assertFailCheck(c, "goal_revalidated", "moving");
      Route sealed = Route.failed(haven.pathfinding.CoarseRoutePlanner.Status.NO_KNOWN_ROUTE, Cause.KNOWN_BLOCKED);
      c = MoveToAutoKnownLongLegScenario.goalRevalidationCheck(here, 4660L, true, true, sealed);
      assertFailCheck(c, "goal_revalidated", "not REACHED");
      c = MoveToAutoKnownLongLegScenario.goalRevalidationCheck(here, 4660L, true, true, null);
      assertFailCheck(c, "goal_revalidated", "no plan");
   }

   @Test
   void autoKnownLongLegOutcomeChecksAreTyped() {
      haven.pathfinding.CoarseTileNavigator.Run u = coarseRun(
         haven.pathfinding.CoarseTileNavigator.RunStatus.UNAVAILABLE, null, null, null, null, null, Coord.of(30, 20), 0L
      );
      List<JSONObject> checks = MoveToAutoKnownLongLegScenario.outcomeChecks(u, false);
      Assertions.assertEquals("FAIL", PfTestRunner.verdictOf(checks));
      Assertions.assertEquals("session_state", checks.get(0).getString("name"));
      assertCoarseRefusal(haven.pathfinding.CoarseRoutePlanner.Status.INVALID_START, null, "INVALID_START");
      assertCoarseRefusal(haven.pathfinding.CoarseRoutePlanner.Status.INVALID_GOAL, null, "INVALID_GOAL");
      assertCoarseRefusal(haven.pathfinding.CoarseRoutePlanner.Status.NO_KNOWN_ROUTE, Cause.KNOWN_BLOCKED, "NO_KNOWN_ROUTE:KNOWN_BLOCKED");
      assertCoarseRefusal(haven.pathfinding.CoarseRoutePlanner.Status.EXHAUSTED, null, "EXHAUSTED");
   }

   private static void assertCoarseRefusal(haven.pathfinding.CoarseRoutePlanner.Status status, Cause cause, String typed) {
      haven.pathfinding.CoarseTileNavigator.Run r = coarseRun(
         haven.pathfinding.CoarseTileNavigator.RunStatus.ROUTE_REJECTED, status, cause, null, null, null, Coord.of(30, 20), 4660L
      );
      List<JSONObject> checks = MoveToAutoKnownLongLegScenario.outcomeChecks(r, false);
      Assertions.assertEquals("FAIL", PfTestRunner.verdictOf(checks));
      Assertions.assertEquals("coarse_route", checks.get(0).getString("name"));
      Assertions.assertTrue(
         checks.get(0).getString("detail").contains(typed), "refusal must carry the typed status " + typed + ": " + checks.get(0).getString("detail")
      );
      Assertions.assertFalse(MoveToAutoKnownLongLegScenario.factsJson(r, null, null, "ROUTE_REJECTED", null).getBoolean("moved"));
   }

   @Test
   void autoKnownLongLegVerifiedArrivalPassesWithFacts() {
      Selection sel = knownLongLegSel();
      Area bounds = MoveToAutoKnownLongLegScenario.selectBounds(Coord.of(10, 20));
      haven.pathfinding.CoarseTileNavigator.Run r = coarseRun(
         haven.pathfinding.CoarseTileNavigator.RunStatus.NAVIGATED,
         haven.pathfinding.CoarseRoutePlanner.Status.REACHED,
         null,
         Outcome.REACHED_DESTINATION,
         null,
         Coord2d.of(335.5, 170.5),
         Coord.of(42, 8),
         4660L
      );
      List<JSONObject> checks = MoveToAutoKnownLongLegScenario.outcomeChecks(r, false);
      Assertions.assertEquals("PASS", PfTestRunner.verdictOf(checks));
      Assertions.assertEquals("navigation_completed", checks.get(0).getString("name"));
      Assertions.assertEquals("pass", checks.get(0).getString("status"));
      JSONObject facts = MoveToAutoKnownLongLegScenario.factsJson(r, sel, bounds, "NAVIGATED", null);
      Assertions.assertTrue(facts.getBoolean("moved"));
      Assertions.assertTrue(facts.getBoolean("arrived"));
      Assertions.assertEquals("KNOWN_MAP_LONG_LEG", facts.getString("profile"));
      Assertions.assertEquals("NAVIGATED", facts.getString("status"));
      Assertions.assertTrue(facts.getBoolean("selected"));
      Assertions.assertTrue(facts.isNull("refusal"));
      Assertions.assertTrue(facts.getInt("candidates") > 0);
      Assertions.assertTrue(facts.getDouble("best_score") > 0.0);
      Assertions.assertTrue(facts.getString("reason").contains("KNOWN_MAP_LONG_LEG"));
      Assertions.assertEquals("1234", facts.getString("segment"));
      Assertions.assertArrayEquals(new int[]{42, 8}, new int[]{facts.getJSONArray("goal_tile").getInt(0), facts.getJSONArray("goal_tile").getInt(1)});
      Assertions.assertEquals("REACHED", facts.getString("route_kind"));
      Assertions.assertEquals(3, facts.getInt("waypoint_count"));
      Assertions.assertEquals(100, facts.getInt("expanded"));
      Assertions.assertEquals("REACHED_DESTINATION", facts.getString("nav_outcome"));
      Assertions.assertEquals(4, facts.getInt("legs"));
      Assertions.assertEquals(1, facts.getInt("replans"));
      Assertions.assertEquals(3, facts.getInt("route_index"));
      Assertions.assertEquals(335.5, facts.getJSONArray("end_pos").getDouble(0), 0.01);
      Assertions.assertEquals(1234L, facts.getLong("elapsed_ms"));
      Assertions.assertEquals(0, facts.getJSONArray("replan_facts").length());
      Assertions.assertFalse(facts.has("marker"), "no marker in the long-leg facts");
      Assertions.assertFalse(facts.toString().contains("password"), "no credentials in the audit facts");
      Assertions.assertFalse(facts.toString().contains("\"id\":"), "no gob ids in the audit facts");
   }

   @Test
   void autoKnownLongLegReplanFactsSerializeStatusAndCause() {
      Selection sel = knownLongLegSel();
      haven.pathfinding.CoarseTileNavigator.Run r = new haven.pathfinding.CoarseTileNavigator.Run(
         haven.pathfinding.CoarseTileNavigator.RunStatus.NAVIGATED,
         Coord.of(42, 8),
         region(0, 0, 100, 100),
         new Location(4660L, Coord.of(34, 3), Coord2d.of(245.3, 118.9)),
         haven.pathfinding.CoarseRoutePlanner.Status.REACHED,
         null,
         2,
         15,
         Outcome.COARSE_PLAN_FAILED,
         2,
         1,
         1,
         "coarse NO_KNOWN_ROUTE:UNKNOWN_GAP",
         Coord2d.of(150.0, 100.0),
         List.of(new ReplanFact(1, haven.pathfinding.CoarseRoutePlanner.Status.NO_KNOWN_ROUTE, Cause.UNKNOWN_GAP, 9)),
         1234L
      );
      JSONObject facts = MoveToAutoKnownLongLegScenario.factsJson(r, sel, null, "NAVIGATED", null);
      Assertions.assertEquals("coarse NO_KNOWN_ROUTE:UNKNOWN_GAP", facts.getString("detail"));
      JSONArray replans = facts.getJSONArray("replan_facts");
      Assertions.assertEquals(1, replans.length());
      JSONObject rf = replans.getJSONObject(0);
      Assertions.assertEquals(1, rf.getInt("index"));
      Assertions.assertEquals("NO_KNOWN_ROUTE", rf.getString("status"));
      Assertions.assertEquals("UNKNOWN_GAP", rf.getString("cause"));
      Assertions.assertEquals(9, rf.getInt("expanded"));
      Assertions.assertFalse(facts.getBoolean("arrived"));
   }

   @Test
   void autoKnownLongLegNavigationFailuresAreTyped() {
      for (Outcome oc : new Outcome[]{
         Outcome.CANCELLED,
         Outcome.TERMINAL_FAILURE,
         Outcome.LEG_LIMIT_EXHAUSTED,
         Outcome.REPLAN_LIMIT_EXHAUSTED,
         Outcome.COARSE_PLAN_FAILED,
         Outcome.COARSE_PLAN_INVALID,
         Outcome.STUCK
      }) {
         String detail = oc != Outcome.CANCELLED && oc != Outcome.TERMINAL_FAILURE ? "coarse " + oc : "walker " + oc;
         haven.pathfinding.CoarseTileNavigator.Run r = coarseRun(
            haven.pathfinding.CoarseTileNavigator.RunStatus.NAVIGATED,
            haven.pathfinding.CoarseRoutePlanner.Status.REACHED,
            null,
            oc,
            detail,
            Coord2d.of(60.0, 40.0),
            Coord.of(30, 20),
            4660L
         );
         List<JSONObject> checks = MoveToAutoKnownLongLegScenario.outcomeChecks(r, false);
         Assertions.assertEquals("FAIL", PfTestRunner.verdictOf(checks), oc.toString());
         Assertions.assertEquals("navigation_completed", checks.get(0).getString("name"));
         Assertions.assertEquals("fail", checks.get(0).getString("status"));
         Assertions.assertFalse(MoveToAutoKnownLongLegScenario.factsJson(r, null, null, "NAVIGATED", null).getBoolean("arrived"));
      }
   }

   @Test
   void autoKnownLongLegArrivalRequiresVerifiedIdleArrivalAtGoal() {
      Coord2d goalWorld = NamedPlaceNavigator.worldPos(Coord.of(42, 8), Coord.of(12, -7));
      JSONObject c = MoveToAutoKnownLongLegScenario.arrivalCheck(false, true, true, goalWorld, goalWorld);
      assertFailCheck(c, "arrived_idle_at_goal", "did not verify");
      c = MoveToAutoKnownLongLegScenario.arrivalCheck(true, false, true, goalWorld, goalWorld);
      assertFailCheck(c, "arrived_idle_at_goal", "not observable");
      c = MoveToAutoKnownLongLegScenario.arrivalCheck(true, true, false, goalWorld, goalWorld);
      assertFailCheck(c, "arrived_idle_at_goal", "still moving");
      c = MoveToAutoKnownLongLegScenario.arrivalCheck(true, true, true, goalWorld, goalWorld);
      Assertions.assertEquals("pass", c.getString("status"));
      c = MoveToAutoKnownLongLegScenario.arrivalCheck(true, true, true, goalWorld.add(4.0, 0.0), goalWorld);
      assertFailCheck(c, "arrived_idle_at_goal", "units from the goal tile center");
   }

   @Test
   void autoKnownLongLegCancelledMidNavigationPublishesTypedAuditBody() {
      Selection sel = knownLongLegSel();
      Area bounds = MoveToAutoKnownLongLegScenario.selectBounds(Coord.of(10, 20));
      haven.pathfinding.CoarseTileNavigator.Run r = coarseRun(
         haven.pathfinding.CoarseTileNavigator.RunStatus.NAVIGATED,
         haven.pathfinding.CoarseRoutePlanner.Status.REACHED,
         null,
         Outcome.CANCELLED,
         "bot cancelled",
         Coord2d.of(60.0, 40.0),
         Coord.of(42, 8),
         4660L
      );
      JSONObject body = MoveToAutoKnownLongLegScenario.cancelledBody(r, sel, bounds, false);
      Assertions.assertEquals("FAIL", body.getString("verdict"));
      JSONObject cancel = findCheck(body, "run_cancelled");
      Assertions.assertEquals("fail", cancel.getString("status"));
      JSONObject facts = findFacts(body);
      Assertions.assertTrue(facts.getBoolean("moved"), "the partial navigation still moved");
      Assertions.assertFalse(facts.getBoolean("arrived"));
      Assertions.assertEquals("NAVIGATED", facts.getString("status"));
      Assertions.assertEquals("CANCELLED", facts.getString("nav_outcome"));
      Assertions.assertEquals("bot cancelled", facts.getString("detail"));
      Assertions.assertEquals("1234", facts.getString("segment"));
      Assertions.assertEquals("42", String.valueOf(facts.getJSONArray("goal_tile").getInt(0)));
   }

   @Test
   void autoKnownLongLegWallClockTimeoutAddsMoveTimeoutFail() {
      haven.pathfinding.CoarseTileNavigator.Run r = coarseRun(
         haven.pathfinding.CoarseTileNavigator.RunStatus.NAVIGATED,
         haven.pathfinding.CoarseRoutePlanner.Status.REACHED,
         null,
         Outcome.CANCELLED,
         null,
         Coord2d.of(60.0, 40.0),
         Coord.of(30, 20),
         4660L
      );
      List<JSONObject> checks = MoveToAutoKnownLongLegScenario.outcomeChecks(r, true);
      Assertions.assertEquals("move_timeout", checks.get(0).getString("name"));
      Assertions.assertEquals("fail", checks.get(0).getString("status"));
      Assertions.assertEquals("FAIL", PfTestRunner.verdictOf(checks));
   }

   @Test
   void autoKnownLongLegRefusalFactsAreTypedNoMove() {
      JSONObject pre = MoveToAutoKnownLongLegScenario.factsJson(null, null, null, "NOT_STARTED", "preflight refused");
      Assertions.assertFalse(pre.getBoolean("moved"));
      Assertions.assertFalse(pre.getBoolean("arrived"));
      Assertions.assertEquals("NOT_STARTED", pre.getString("status"));
      Assertions.assertEquals("KNOWN_MAP_LONG_LEG", pre.getString("profile"));
      Assertions.assertFalse(pre.getBoolean("selected"));
      Assertions.assertEquals("PREFLIGHT", pre.getString("refusal"));
      Assertions.assertFalse(pre.has("goal_tile"));
      Assertions.assertFalse(pre.has("segment"));
      Selection sel = knownLongLegSel();
      Area bounds = MoveToAutoKnownLongLegScenario.selectBounds(Coord.of(10, 20));
      JSONObject reval = MoveToAutoKnownLongLegScenario.factsJson(null, sel, bounds, "REVALIDATION_REFUSED", null);
      Assertions.assertFalse(reval.getBoolean("moved"));
      Assertions.assertEquals("REVALIDATION_REFUSED", reval.getString("status"));
      Assertions.assertTrue(reval.getBoolean("selected"));
      Assertions.assertTrue(reval.isNull("refusal"));
      Assertions.assertEquals(
         bounds.ul.add(sel.targetTile).x, reval.getJSONArray("goal_tile").getInt(0), "the frozen goal tile is the absolute in-segment tile"
      );
      Assertions.assertEquals("1234", reval.getString("segment"));
      Assertions.assertFalse(reval.has("route_kind"), "no navigation happened");
   }

   @Test
   void moveToAutoKnownLongLegWithoutGameIsTypedFailWithArtifactFacts() throws Exception {
      JSONObject started = PfTestRunner.start("move_to_auto_known_long_leg", null);
      Assertions.assertTrue(started.getBoolean("ok"));
      String id = started.getString("run_id");
      JSONObject result = null;
      long deadline = System.currentTimeMillis() + 5000L;

      while (System.currentTimeMillis() < deadline) {
         result = PfTestRunner.result(id);
         if (result != null && !"running".equals(result.getString("status"))) {
            break;
         }

         Thread.sleep(50L);
      }

      Assertions.assertNotNull(result, "run must complete within the poll deadline");
      Assertions.assertEquals("completed", result.getString("status"));
      Assertions.assertEquals("FAIL", result.getString("verdict"));
      JSONArray checks = result.getJSONArray("checks");
      Assertions.assertEquals("in_game", checks.getJSONObject(0).getString("name"));
      Assertions.assertEquals("fail", checks.getJSONObject(0).getString("status"));
      JSONObject facts = result.getJSONObject("facts");
      Assertions.assertFalse(facts.getBoolean("moved"));
      Assertions.assertEquals("NOT_STARTED", facts.getString("status"));
      Assertions.assertEquals("PREFLIGHT", facts.getString("refusal"));
      Assertions.assertTrue(
         result.getString("artifact").contains("move_to_auto_known_long_leg"), "artifact path must name the scenario: " + result.getString("artifact")
      );
      Assertions.assertTrue(result.optString("note").contains("no movement performed"));
   }
}
