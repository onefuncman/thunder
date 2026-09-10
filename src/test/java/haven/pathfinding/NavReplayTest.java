package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.pathfinding.PathfinderLog.Occupancy;
import haven.pathfinding.PathfinderLog.Trace;
import haven.pathfinding.PrototypePathfinder.Plan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class NavReplayTest {
   @Test
   void occupancyEncodeDecodeIsDeterministic() {
      boolean[] solid = new boolean[9];
      boolean[] dilated = new boolean[9];
      solid[4] = true;
      dilated[4] = true;
      dilated[5] = true;
      Occupancy occ = Occupancy.capture(
         Coord2d.of(1.0, 2.0), 3, 3, 2.75, solid, dilated, dilated, Coord.of(0, 0), Coord.of(2, 2), Coord.of(2, 1), List.of()
      );
      String encoded = Occupancy.encode(occ);
      Assertions.assertEquals(9, encoded.length());
      Occupancy round = Occupancy.decode(occ.origin, 3, 3, 2.75, encoded, occ.start, occ.goal, occ.freeGoal);
      Assertions.assertEquals(encoded, Occupancy.encode(round));
      Assertions.assertEquals(occ.at(1, 1), round.at(1, 1));
      Assertions.assertEquals(occ.at(2, 1), round.at(2, 1));
   }

   @Test
   void documentRoundTripPreservesRequiredFields() {
      Occupancy occ = Occupancy.capture(
         Coord2d.of(0.0, 0.0), 4, 3, 2.75, new boolean[12], new boolean[12], new boolean[12], Coord.of(0, 0), Coord.of(3, 2), Coord.of(3, 2), List.of()
      );
      PathfinderLog.recordOccupancy(occ);
      PathfinderLog.recordConfirmedPos(Coord2d.of(1.25, 1.5));
      PathfinderLog.recordInteraction(null);
      PathfinderLog.recordGraph(null);
      PfTestRunner.Run run = new PfTestRunner.Run("observe");
      JSONObject result = new JSONObject().put("status", "completed").put("verdict", "PASS");
      JSONObject doc = NavReplayIO.document(run, result);
      JSONObject parsed = NavReplay.parseObject(new JSONObject(doc.toString()));
      Assertions.assertEquals(NavReplay.FORMAT, parsed.getString("format"));
      Assertions.assertEquals(1, parsed.getInt("version"));
      Assertions.assertEquals("observe", parsed.getString("scenario"));
      Assertions.assertTrue(parsed.has("world"));
      Assertions.assertTrue(parsed.has("goal"));
      Assertions.assertTrue(parsed.has("raw_route"));
      Assertions.assertTrue(parsed.has("smoothed_route"));
      Assertions.assertTrue(parsed.has("observations"));
      Assertions.assertTrue(parsed.has("decisions"));
      Assertions.assertEquals("PASS", parsed.getJSONObject("outcome").getString("verdict"));
      Assertions.assertEquals(12, parsed.getJSONObject("world").getString("occupancy").length());
   }

   @Test
   void replayReproducesPlanCoreWithoutLiveGame() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      Coord2d dest = Coord2d.of(0.0, 100.0);
      PrototypePathfinder.ClipResult clip = PrototypePathfinder.clipToHorizon(start, List.of(dest));
      PrototypePathfinder.Grid grid = PrototypePathfinder.planGrid(start, clip.targets);
      boolean[] solid = new boolean[grid.w * grid.h];
      boolean[] dilated = new boolean[grid.w * grid.h];
      Trace liveTrace = new Trace();
      Plan live = PrototypePathfinder.planCore(start, List.of(dest), clip.targets, clip.clipped, true, 4.5, grid, solid, dilated, 0, liveTrace);
      Occupancy occ = Occupancy.capture(
         grid.origin, grid.w, grid.h, 2.75, solid, dilated, dilated, liveTrace.startCell, liveTrace.goalCell, liveTrace.freeGoal, List.of()
      );
      JSONObject world = new JSONObject()
         .put("cell", 2.75)
         .put("origin", NavReplay.point(occ.origin))
         .put("grid", new org.json.JSONArray().put(occ.w).put(occ.h))
         .put("occupancy", Occupancy.encode(occ))
         .put("player", NavReplay.point(start))
         .put("radius", 4.5)
         .put("obstacles", 0)
         .put("start_cell", NavReplay.cell(liveTrace.startCell))
         .put("goal_cell", NavReplay.cell(liveTrace.goalCell))
         .put("free_goal_cell", NavReplay.cell(liveTrace.freeGoal));
      JSONObject goal = new JSONObject().put("kind", "POINT").put("position", NavReplay.point(dest));
      JSONObject doc = new JSONObject()
         .put("format", NavReplay.FORMAT)
         .put("version", 1)
         .put("world", world)
         .put("goal", goal)
         .put("raw_route", NavReplay.points(liveTrace.astar))
         .put("smoothed_route", NavReplay.points(live.waypoints))
         .put("observations", new org.json.JSONArray())
         .put("decisions", new org.json.JSONArray())
         .put("outcome", new JSONObject().put("status", "completed").put("verdict", "PASS").put("reason", "ok"));
      JSONObject replay = NavReplayRunner.replay(doc);
      Assertions.assertEquals(live.status.name(), replay.getString("status"));
      Assertions.assertTrue(replay.getBoolean("raw_match"), replay.toString());
      Assertions.assertTrue(replay.getBoolean("smoothed_match"), replay.toString());
   }

   @Test
   void writeEmitsNavReplayArtifact() throws Exception {
      Path root = Files.createTempDirectory("navreplay-");
      try {
         PfTestRunner.Run run = new PfTestRunner.Run("observe");
         JSONObject result = new JSONObject().put("run_id", run.id).put("status", "completed").put("verdict", "PASS");
         PfTestRunner.write(run, result, root);
         Path replay = root.resolve("dev-snapshots/pf/tests/observe").resolve(run.id + ".navreplay.jsonl");
         Assertions.assertEquals(replay.toString(), result.getString("navreplay"));
         Assertions.assertTrue(Files.isRegularFile(replay));
         JSONObject loaded = NavReplay.loadFile(replay);
         Assertions.assertEquals("observe", loaded.getString("scenario"));
         JSONObject offline = NavReplayRunner.replayFile(replay);
         Assertions.assertEquals(NavReplay.FORMAT, replayHeaderFormat(replay));
         Assertions.assertTrue(offline.has("status"));
      } finally {
         try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
               try {
                  Files.deleteIfExists(path);
               } catch (Exception ignored) {
               }
            });
         }
      }
   }

   @Test
   void parseKeepsOptionalPhase3DecisionFields() {
      JSONObject doc = new JSONObject()
         .put("format", NavReplay.FORMAT)
         .put("version", 1)
         .put("decisions", new org.json.JSONArray().put(
            new JSONObject()
               .put("kind", "SEND_MOVEMENT")
               .put("reason", "STOPPED_EARLY")
               .put("recovery", 1)
               .put("selected_index", 2)
               .put("considered_index", 4)
               .put("corridor_valid", true)
               .put("blacklist", true)
         ));
      JSONObject parsed = NavReplay.parseObject(doc);
      JSONObject d = parsed.getJSONArray("decisions").getJSONObject(0);
      Assertions.assertEquals(1, d.getInt("recovery"));
      Assertions.assertTrue(d.getBoolean("blacklist"));
      Assertions.assertEquals("STOPPED_EARLY", d.getString("reason"));
   }

   @Test
   void optionalInteractionEvidenceKeepsV1() {
      PathfinderLog.recordInteraction(
         new JSONObject()
            .put("target_id", "9")
            .put("selected", new org.json.JSONArray().put(3.0).put(4.0))
            .put("min_dist", 0.5)
            .put("max_dist", 16.5)
            .put("expected_result", "window_opened")
            .put("decision", "INTERACT")
      );
      try {
         Occupancy occ = Occupancy.capture(
            Coord2d.of(0.0, 0.0), 2, 2, 2.75, new boolean[4], new boolean[4], new boolean[4], Coord.of(0, 0), Coord.of(1, 1), Coord.of(1, 1), List.of()
         );
         PathfinderLog.recordOccupancy(occ);
         JSONObject doc = NavReplayIO.document(new PfTestRunner.Run("interact_cupboard"), new JSONObject().put("status", "completed").put("verdict", "FAIL"));
         Assertions.assertEquals("INTERACTION", doc.getJSONObject("goal").getString("kind"));
         Assertions.assertEquals("9", doc.getJSONObject("interaction").getString("target_id"));
         JSONObject parsed = NavReplay.parseObject(new JSONObject(doc.toString()));
         Assertions.assertEquals(1, parsed.getInt("version"));
      } finally {
         PathfinderLog.recordInteraction(null);
      }
   }

   @Test
   void resetRunDropsStaleReplayEvidence() {
      Occupancy occ = Occupancy.capture(
         Coord2d.of(0.0, 0.0), 2, 2, 2.75, new boolean[4], new boolean[4], new boolean[4], Coord.of(0, 0), Coord.of(1, 1), Coord.of(1, 1), List.of()
      );
      PathfinderLog.recordOccupancy(occ);
      PathfinderLog.recordGraph(new JSONObject().put("source", "stale"));
      PathfinderLog.recordDest(Coord2d.of(9.0, 9.0));
      PathfinderLog.resetRun();
      JSONObject doc = NavReplayIO.document(new PfTestRunner.Run("observe"), new JSONObject().put("status", "completed").put("verdict", "FAIL"));
      Assertions.assertFalse(doc.has("graph"));
      Assertions.assertEquals(0, doc.getJSONArray("decisions").length());
      Assertions.assertEquals(0, doc.getJSONArray("raw_route").length());
   }

   @Test
   void parseRejectsWrongVersion() {
      JSONObject bad = new JSONObject().put("format", "NavReplay").put("version", 2);
      Assertions.assertThrows(IllegalArgumentException.class, () -> NavReplay.parseObject(bad));
   }

   private static String replayHeaderFormat(Path file) throws Exception {
      String header = Files.readAllLines(file).get(0);
      return new JSONObject(header).getString("format");
   }
}
