package haven.pathfinding;

import haven.Coord2d;
import haven.dev.DebugBoot;
import haven.dev.DebugReplay;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Offline NavReplay v1 runner. Replays planning against the captured occupancy
 * using {@link PrototypePathfinder#planFromOccupancy} — the same planCore used
 * by live scenarios. Does not connect to the game.
 */
public final class NavReplayRunner {
   private NavReplayRunner() {
   }

   public static JSONObject replay(JSONObject doc) {
      JSONObject body = NavReplay.parseObject(doc);
      JSONObject out = new JSONObject();
      out.put("format", NavReplay.FORMAT);
      out.put("version", NavReplay.VERSION);
      out.put("scenario", body.opt("scenario"));
      out.put("run_id", body.opt("run_id"));
      JSONObject world = body.optJSONObject("world");
      JSONObject goal = body.optJSONObject("goal");
      PathfinderLog.Occupancy occ = occupancyOf(world);
      Coord2d start = NavReplay.coord2d(world == null ? null : world.opt("player"));
      Coord2d dest = goal == null ? null : NavReplay.coord2d(goal.opt("position"));
      double radius = world == null ? PrototypePathfinder.DEFAULT_AGENT_RADIUS : world.optDouble("radius", PrototypePathfinder.DEFAULT_AGENT_RADIUS);
      int obstacles = world == null ? 0 : world.optInt("obstacles");
      PathfinderLog.Trace tr = new PathfinderLog.Trace();
      PrototypePathfinder.Plan plan = PrototypePathfinder.planFromOccupancy(start, dest, true, radius, occ, obstacles, tr);
      List<Coord2d> recordedRaw = NavReplay.coords2d(body.optJSONArray("raw_route"));
      List<Coord2d> recordedSmooth = NavReplay.coords2d(body.optJSONArray("smoothed_route"));
      out.put("reason", tr.reason == null ? "" : tr.reason);
      out.put("status", plan.status.name());
      out.put("raw_route", NavReplay.points(tr.astar));
      out.put("smoothed_route", NavReplay.points(plan.waypoints));
      out.put("recorded_raw_route", NavReplay.points(recordedRaw));
      out.put("recorded_smoothed_route", NavReplay.points(recordedSmooth));
      out.put("raw_match", routesMatch(tr.astar, recordedRaw));
      out.put("smoothed_match", routesMatch(plan.waypoints, recordedSmooth));
      out.put("expanded", plan.expanded);
      out.put("complete", plan.complete);
      out.put("decisions", body.optJSONArray("decisions"));
      out.put("outcome", body.optJSONObject("outcome"));
      return out;
   }

   public static JSONObject replayFile(Path file) throws Exception {
      return replay(NavReplay.loadFile(file));
   }

   public static void print(JSONObject replay, PrintStream out) {
      if (replay == null) {
         out.println("no NavReplay");
         return;
      }
      out.printf(
         "NavReplay v%d scenario=%s status=%s reason=%s raw_match=%s smoothed_match=%s expanded=%d%n",
         replay.optInt("version"),
         replay.optString("scenario"),
         replay.optString("status"),
         replay.optString("reason"),
         replay.optBoolean("raw_match"),
         replay.optBoolean("smoothed_match"),
         replay.optInt("expanded")
      );
   }

   public static void replay(JSONObject body, PrintStream out) {
      print(replay(body), out);
   }

   public static void main(String[] args) throws Exception {
      if (args.length < 1) {
         System.err.println("usage: java -cp hafen.jar:HavenNavigationCore.jar haven.pathfinding.NavReplayRunner <navreplay.jsonl>");
         System.exit(2);
         return;
      }
      DebugBoot.init();
      JSONObject result = replayFile(Paths.get(args[0]));
      print(result, System.out);
   }

   static PathfinderLog.Occupancy occupancyOf(JSONObject world) {
      if (world == null) {
         return null;
      }
      String encoded = world.optString("occupancy", "");
      if (encoded.isEmpty()) {
         return null;
      }
      JSONArray originArr = world.optJSONArray("origin");
      JSONArray grid = world.optJSONArray("grid");
      int w = grid == null || grid.length() < 1 ? 0 : grid.getInt(0);
      int h = grid == null || grid.length() < 2 ? 0 : grid.getInt(1);
      Coord2d origin = NavReplay.coord2d(originArr);
      double cell = world.optDouble("cell", PrototypePathfinder.CELL);
      return PathfinderLog.Occupancy.decode(
         origin,
         w,
         h,
         cell,
         encoded,
         NavReplay.coord(world.opt("start_cell")),
         NavReplay.coord(world.opt("goal_cell")),
         NavReplay.coord(world.opt("free_goal_cell"))
      );
   }

   static boolean routesMatch(List<Coord2d> a, List<Coord2d> b) {
      if (a == null) {
         a = java.util.Collections.emptyList();
      }
      if (b == null) {
         b = java.util.Collections.emptyList();
      }
      if (a.size() != b.size()) {
         return false;
      }
      for (int i = 0; i < a.size(); i++) {
         Coord2d p = a.get(i);
         Coord2d q = b.get(i);
         if (p == null || q == null || p.dist(q) > 1.0E-4) {
            return false;
         }
      }
      return true;
   }

   static {
      DebugReplay.register(NavReplay.FEATURE, NavReplayRunner::replay);
   }
}
