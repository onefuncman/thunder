package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * NavReplay v1: versioned JSON for reproducing a navigation plan without a
 * live game connection. Stores occupancy, goal, routes, observations,
 * decisions, and outcome. Does not serialize renderer or UI objects.
 */
public final class NavReplayIO {
   private NavReplayIO() {
   }

   public static JSONObject document(PfTestRunner.Run run, JSONObject result) {
      JSONObject o = new JSONObject();
      o.put("format", NavReplay.FORMAT);
      o.put("version", NavReplay.VERSION);
      o.put("scenario", run == null ? JSONObject.NULL : run.scenario);
      o.put("run_id", run == null ? JSONObject.NULL : run.id);
      o.put("generated_at_ms", System.currentTimeMillis());
      o.put("world", worldFromLogs(result));
      o.put("goal", goalFrom(result));
      o.put("raw_route", points(PathfinderLog.lastAStar()));
      o.put("smoothed_route", points(PathfinderLog.lastPath()));
      o.put("observations", observationsFrom(result));
      o.put("decisions", decisionsFrom());
      o.put("outcome", outcomeFrom(result));
      JSONObject interaction = PathfinderLog.lastInteraction();
      if (interaction != null) {
         o.put("interaction", interaction);
      }
      JSONObject graph = PathfinderLog.lastGraph();
      if (graph != null) {
         o.put("graph", graph);
      }
      return o;
   }

   public static Path emit(PfTestRunner.Run run, JSONObject result, Path dir) throws IOException {
      if (run == null || dir == null) {
         return null;
      }
      Files.createDirectories(dir);
      Path file = dir.resolve(run.id + ".navreplay.jsonl");
      JSONObject body = document(run, result);
      JSONObject header = new JSONObject()
         .put("type", "header")
         .put("feature", NavReplay.FEATURE)
         .put("format", NavReplay.FORMAT)
         .put("version", NavReplay.VERSION)
         .put("scenario", run.scenario)
         .put("run_id", run.id)
         .put("generated_at_ms", System.currentTimeMillis());
      Writer w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      try {
         w.write(header.toString());
         w.write(10);
         w.write(body.toString());
         w.write(10);
      } finally {
         w.close();
      }
      return file;
   }




   static JSONObject worldFromLogs(JSONObject result) {
      JSONObject world = new JSONObject();
      PathfinderLog.Occupancy occ = PathfinderLog.lastOccupancy();
      JSONObject scene = result == null ? null : result.optJSONObject("scene");
      if (occ != null) {
         world.put("cell", occ.cell);
         world.put("origin", point(occ.origin));
         world.put("grid", new JSONArray().put(occ.w).put(occ.h));
         world.put("occupancy", PathfinderLog.Occupancy.encode(occ));
         world.put("start_cell", cell(occ.start));
         world.put("goal_cell", cell(occ.goal));
         world.put("free_goal_cell", cell(occ.freeGoal));
      } else if (scene != null) {
         world.put("cell", scene.optDouble("cell", PrototypePathfinder.CELL));
         world.put("origin", scene.optJSONArray("origin"));
         world.put("grid", scene.optJSONArray("grid"));
      } else {
         world.put("cell", PrototypePathfinder.CELL);
         world.put("origin", new JSONArray());
         world.put("grid", new JSONArray().put(0).put(0));
      }
      if (scene != null) {
         world.put("player", scene.optJSONArray("player"));
         world.put("player_cell", scene.opt("player_cell"));
         world.put("radius", scene.optDouble("radius", PrototypePathfinder.DEFAULT_AGENT_RADIUS));
         world.put("moving", scene.optBoolean("moving"));
         world.put("obstacles", scene.optInt("obstacles"));
      } else {
         Coord2d player = PathfinderLog.lastConfirmedPos();
         if (player == null && occ != null && occ.start != null) {
            player = occ.world(occ.start.x, occ.start.y);
         }
         world.put("player", point(player));
         world.put("player_cell", occ == null ? JSONObject.NULL : cell(occ.start));
         world.put("radius", PrototypePathfinder.DEFAULT_AGENT_RADIUS);
         world.put("moving", false);
         world.put("obstacles", 0);
      }
      world.put("hazards", points(PathfinderLog.lastHazards()));
      return world;
   }

   static Object goalFrom(JSONObject result) {
      JSONObject inter = PathfinderLog.lastInteraction();
      Coord2d dest = PathfinderLog.lastDest();
      if (dest == null && result != null) {
         JSONObject facts = result.optJSONObject("facts");
         dest = coord2d(facts == null ? null : facts.opt("target"));
         if (dest == null && facts != null) {
            dest = coord2d(facts.opt("goal"));
         }
      }
      if (inter != null) {
         JSONObject g = new JSONObject();
         g.put("kind", "INTERACTION");
         g.put("position", inter.has("selected") ? inter.get("selected") : point(dest));
         g.put("target_id", inter.opt("target_id"));
         if (inter.has("footprint")) {
            g.put("footprint", inter.get("footprint"));
         }
         g.put("min_dist", inter.optDouble("min_dist"));
         g.put("max_dist", inter.optDouble("max_dist"));
         g.put("allowed_sides", inter.optInt("allowed_sides"));
         if (inter.has("facing")) {
            g.put("facing", inter.get("facing"));
         }
         g.put("expected_result", inter.optString("expected_result", ""));
         return g;
      }
      if (dest == null) {
         return JSONObject.NULL;
      }
      JSONObject g = new JSONObject();
      g.put("kind", "POINT");
      g.put("position", point(dest));
      return g;
   }

   static JSONArray observationsFrom(JSONObject result) {
      JSONArray arr = new JSONArray();
      Coord2d pos = PathfinderLog.lastConfirmedPos();
      JSONObject scene = result == null ? null : result.optJSONObject("scene");
      if (pos == null && scene != null) {
         pos = coord2d(scene.opt("player"));
      }
      JSONObject o = new JSONObject();
      o.put("seq", 0);
      o.put("pos", point(pos));
      o.put("moving", scene != null && scene.optBoolean("moving"));
      o.put("confirmed", true);
      arr.put(o);
      return arr;
   }

   static JSONArray decisionsFrom() {
      JSONArray arr = new JSONArray();
      List<JSONObject> recent = PathfinderLog.recent();
      int seq = 0;
      for (JSONObject src : recent) {
         if (src == null || src.optBoolean("probe")) {
            continue;
         }
         JSONObject d = new JSONObject();
         d.put("seq", seq++);
         if (src.has("kind") && src.has("recovery")) {
            d.put("kind", src.optString("kind", "STREAM"));
            d.put("reason", src.optString("reason", ""));
            d.put("recovery", src.optInt("recovery"));
            d.put("plan_ms", src.optLong("plan_ms"));
            if (src.has("target")) {
               d.put("target", src.get("target"));
            }
            if (src.has("selected_index")) {
               d.put("selected_index", src.get("selected_index"));
               d.put("considered_index", src.opt("considered_index"));
               d.put("corridor_valid", src.opt("corridor_valid"));
            }
            if (src.has("why_shorter")) {
               d.put("why_shorter", src.get("why_shorter"));
            }
            if (src.has("outcome")) {
               d.put("outcome", src.get("outcome"));
            }
            if (src.has("escape")) {
               d.put("escape", src.get("escape"));
            }
            if (src.has("blacklist")) {
               d.put("blacklist", src.get("blacklist"));
            }
         } else {
            d.put("kind", "PLAN");
            d.put("reason", src.optString("reason", ""));
            d.put("clip", src.optString("clip", ""));
            d.put("waypoint_count", src.optInt("waypoint_count"));
            d.put("expanded", src.optInt("expanded"));
         }
         arr.put(d);
      }
      String replan = PathfinderLog.lastReplanReason();
      if (replan != null && !replan.isEmpty()) {
         JSONObject d = new JSONObject();
         d.put("seq", seq);
         d.put("kind", "REPLAN");
         d.put("reason", replan);
         arr.put(d);
      }
      return arr;
   }

   static JSONObject outcomeFrom(JSONObject result) {
      JSONObject o = new JSONObject();
      if (result == null) {
         o.put("status", "unknown");
         o.put("verdict", JSONObject.NULL);
         o.put("reason", "");
         return o;
      }
      o.put("status", result.optString("status", "unknown"));
      o.put("verdict", result.has("verdict") ? result.get("verdict") : JSONObject.NULL);
      String reason = PathfinderLog.lastReason();
      if (reason == null || reason.isEmpty()) {
         reason = result.optString("note", result.optString("error", ""));
      }
      o.put("reason", reason == null ? "" : reason);
      return o;
   }

   static JSONArray points(List<Coord2d> pts) {
      JSONArray arr = new JSONArray();
      if (pts != null) {
         for (Coord2d p : pts) {
            arr.put(point(p));
         }
      }
      return arr;
   }

   static JSONArray point(Coord2d p) {
      JSONArray a = new JSONArray();
      if (p != null) {
         a.put(round4(p.x)).put(round4(p.y));
      }
      return a;
   }

   static JSONArray cell(Coord c) {
      return c == null ? new JSONArray() : new JSONArray().put(c.x).put(c.y);
   }

   static Coord2d coord2d(Object v) {
      if (v instanceof JSONArray) {
         JSONArray a = (JSONArray) v;
         if (a.length() >= 2) {
            return Coord2d.of(a.getDouble(0), a.getDouble(1));
         }
      }
      return null;
   }

   static Coord coord(Object v) {
      if (v instanceof JSONArray) {
         JSONArray a = (JSONArray) v;
         if (a.length() >= 2) {
            return Coord.of(a.getInt(0), a.getInt(1));
         }
      }
      return null;
   }

   static List<Coord2d> coords2d(JSONArray arr) {
      if (arr == null) {
         return Collections.emptyList();
      }
      List<Coord2d> out = new ArrayList<>();
      for (int i = 0; i < arr.length(); i++) {
         Coord2d p = coord2d(arr.opt(i));
         if (p != null) {
            out.add(p);
         }
      }
      return out;
   }

   static double round4(double v) {
      return (double) Math.round(v * 10000.0) / 10000.0;
   }
}
