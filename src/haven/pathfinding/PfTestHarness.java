package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.HackThread;
import haven.Utils;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared pf-test harness: checks, artifacts, and movement-watch helpers. */
final class PfTestHarness {
   private PfTestHarness() {
   }

   public static JSONObject check(String name, boolean passed, String detail) {
      JSONObject c = new JSONObject();
      c.put("name", name);
      c.put("status", passed ? "pass" : "fail");
      c.put("detail", detail == null ? "" : detail);
      return c;
   }

   public static JSONObject skip(String name, String detail) {
      JSONObject c = new JSONObject();
      c.put("name", name);
      c.put("status", "skip");
      c.put("detail", detail == null ? "" : detail);
      return c;
   }

   public static String verdictOf(List<JSONObject> checks) {
      if (checks != null) {
         for (JSONObject c : checks) {
            if ("fail".equals(c.optString("status"))) {
               return "FAIL";
            }
         }
      }

      return "PASS";
   }

   static void write(PfTestRunner.Run run, JSONObject result) {
      write(run, result, Utils.path(System.getProperty("user.dir", ".")));
   }

   static void write(PfTestRunner.Run run, JSONObject result, Path root) {
      try {
         Path dir = root.resolve("dev-snapshots").resolve("pf").resolve("tests").resolve(run.scenario);
         Files.createDirectories(dir);
         Path file = dir.resolve(run.id + ".jsonl");
         result.put("artifact", file.toString());
         JSONObject header = new JSONObject()
            .put("type", "header")
            .put("feature", "pf-test")
            .put("scenario", run.scenario)
            .put("run_id", run.id)
            .put("generated_at_ms", System.currentTimeMillis());
         Writer w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

         try {
            w.write(header.toString());
            w.write(10);
            w.write(result.toString());
            w.write(10);
         } catch (Throwable var10) {
            if (w != null) {
               try {
                  w.close();
               } catch (Throwable var9) {
                  var10.addSuppressed(var9);
               }
            }

            throw var10;
         }

         if (w != null) {
            w.close();
         }
         Path replay = NavReplayIO.emit(run, result, dir);
         if (replay != null) {
            result.put("navreplay", replay.toString());
         }
      } catch (IOException var11) {
      }
   }

   static List<JSONObject> autoMovePreflightChecks(
      String scenario, boolean inGame, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy
   ) {
      List<JSONObject> checks = new ArrayList<>();
      if (!inGame) {
         checks.add(check("in_game", false, "client is not in game (" + scenario + " requires in-game state)"));
         return checks;
      } else {
         checks.add(check("in_game", true, "in game"));
         if (!playerPresent) {
            checks.add(check("player_present", false, "no player gob in game"));
            return checks;
         } else {
            checks.add(check("player_present", true, "player gob present"));
            if (!mapfileAvailable) {
               checks.add(check("mapfile_available", false, "no map file in game state (in-game readiness required)"));
               return checks;
            } else {
               checks.add(check("mapfile_available", true, "map file present"));
               if (!playerIdle) {
                  checks.add(check("player_idle", false, "player is moving at scenario start; refusing to move (no-move)"));
                  return checks;
               } else {
                  checks.add(check("player_idle", true, "player is idle"));
                  if (botBusy) {
                     checks.add(check("bot_available", false, "another auto.Bot is currently running; refusing to navigate (no-move)"));
                     return checks;
                  } else {
                     checks.add(check("bot_available", true, "no current auto.Bot"));
                     return checks;
                  }
               }
            }
         }
      }
   }

   static <T> T moveWatch(PfTestRunner.Run run, AtomicBoolean timedOut, long totalTimeoutMs, Callable<T> body) throws Exception {
      long deadline = System.currentTimeMillis() + totalTimeoutMs;
      Thread me = Thread.currentThread();
      AtomicBoolean stop = new AtomicBoolean(false);
      Thread watchdog = new HackThread(() -> {
         while (!stop.get()) {
            if (run.cancelled) {
               me.interrupt();
               return;
            }

            if (System.currentTimeMillis() >= deadline) {
               timedOut.set(true);
               me.interrupt();
               return;
            }

            try {
               Thread.sleep(50L);
            } catch (InterruptedException var7x) {
               return;
            }
         }
      }, "pf-auto-move-watchdog");
      watchdog.setDaemon(true);
      watchdog.start();

      Object var10;
      try {
         var10 = body.call();
      } finally {
         stop.set(true);
         watchdog.interrupt();
         Thread.interrupted();
      }

      return (T)var10;
   }

   static Coord2d observePos(GameUI gui) {
      Gob player = gui != null && gui.map != null ? gui.map.player() : null;
      return player != null && player.rc != null ? player.rc : null;
   }

   static long elapsed(long t0) {
      return System.currentTimeMillis() - t0;
   }

   static JSONObject body(List<JSONObject> checks, String note, JSONObject facts) {
      JSONObject o = new JSONObject();
      o.put("verdict", verdictOf(checks));
      JSONArray arr = new JSONArray();

      for (JSONObject c : checks) {
         arr.put(c);
      }

      o.put("checks", arr);
      if (facts != null) {
         o.put("facts", facts);
      }

      if (note != null && !note.isEmpty()) {
         o.put("note", note);
      }

      return o;
   }

   static String firstFailDetail(List<JSONObject> checks) {
      for (JSONObject c : checks) {
         if ("fail".equals(c.optString("status"))) {
            return c.optString("detail", "preflight refused");
         }
      }

      return "preflight refused";
   }

   static List<PrototypePathfinder.GobGeom> cupboardGobs(PrototypePathfinder.Scene scene) {
      List<PrototypePathfinder.GobGeom> out = new ArrayList<>();
      if (scene != null && scene.gobs != null) {
         for (PrototypePathfinder.GobGeom g : scene.gobs) {
            if (g != null && g.cupboard && g.rc != null) {
               out.add(g);
            }
         }
      }

      out.sort(Comparator.<PrototypePathfinder.GobGeom>comparingDouble(gx -> gx.rc.x).thenComparingDouble(gx -> gx.rc.y));
      return out;
   }

   static List<CupboardCatalog.Node> cupboardNodes(List<PrototypePathfinder.GobGeom> gobs) {
      List<CupboardCatalog.Node> out = new ArrayList<>();
      if (gobs != null) {
         for (PrototypePathfinder.GobGeom g : gobs) {
            if (g != null && g.rc != null) {
               out.add(new CupboardCatalog.Node(g.id, g.rc.x, g.rc.y));
            }
         }
      }

      return out;
   }

   static List<List<CupboardCatalog.Node>> clusterCupboards(List<CupboardCatalog.Node> nodes) {
      List<CupboardCatalog.Node> sorted = new ArrayList<>();
      if (nodes != null) {
         for (CupboardCatalog.Node n : nodes) {
            if (n != null) {
               sorted.add(n);
            }
         }
      }

      sorted.sort(Comparator.<CupboardCatalog.Node>comparingDouble(nx -> nx.x).thenComparingDouble(nx -> nx.y));
      int[] parent = new int[sorted.size()];
      int i = 0;

      while (i < parent.length) {
         parent[i] = i++;
      }

      for (int ix = 0; ix < sorted.size(); ix++) {
         for (int j = ix + 1; j < sorted.size(); j++) {
            if (CupboardCatalog.adjacent(sorted.get(ix), sorted.get(j))) {
               union(parent, ix, j);
            }
         }
      }

      Map<Integer, List<CupboardCatalog.Node>> groups = new LinkedHashMap<>();

      for (int ix = 0; ix < sorted.size(); ix++) {
         int root = find(parent, ix);
         groups.computeIfAbsent(root, k -> new ArrayList<>()).add(sorted.get(ix));
      }

      List<List<CupboardCatalog.Node>> out = new ArrayList<>(groups.values());

      for (List<CupboardCatalog.Node> c : out) {
         c.sort(Comparator.<CupboardCatalog.Node>comparingDouble(nx -> nx.x).thenComparingDouble(nx -> nx.y));
      }

      out.sort(Comparator.<List<CupboardCatalog.Node>>comparingInt(List::size).reversed().thenComparingDouble(PfTestHarness::clusterMinX).thenComparingDouble(PfTestHarness::clusterMinY));
      return out;
   }

   static int find(int[] parent, int i) {
      while (parent[i] != i) {
         parent[i] = parent[parent[i]];
         i = parent[i];
      }

      return i;
   }

   static void union(int[] parent, int a, int b) {
      int ra = find(parent, a);
      int rb = find(parent, b);
      if (ra != rb) {
         parent[ra] = rb;
      }
   }

   static double clusterMinX(List<CupboardCatalog.Node> c) {
      double best = Double.POSITIVE_INFINITY;

      for (CupboardCatalog.Node n : c) {
         best = Math.min(best, n.x);
      }

      return best;
   }

   static double clusterMinY(List<CupboardCatalog.Node> c) {
      double best = Double.POSITIVE_INFINITY;

      for (CupboardCatalog.Node n : c) {
         best = Math.min(best, n.y);
      }

      return best;
   }

   static List<CupboardCatalog.Node> uniqueLargest(List<List<CupboardCatalog.Node>> clusters) {
      if (clusters != null && !clusters.isEmpty()) {
         List<CupboardCatalog.Node> first = clusters.get(0);

         for (List<CupboardCatalog.Node> c : clusters) {
            if (c.size() == first.size() && c != first) {
               return null;
            }
         }

         return first;
      } else {
         return null;
      }
   }

   static JSONObject cupboardFingerprint(List<CupboardCatalog.Node> cluster) {
      JSONObject o = new JSONObject();
      List<CupboardCatalog.Node> sorted = new ArrayList<>();
      if (cluster != null) {
         for (CupboardCatalog.Node n : cluster) {
            if (n != null) {
               sorted.add(n);
            }
         }
      }

      sorted.sort(Comparator.<CupboardCatalog.Node>comparingDouble(nx -> nx.x).thenComparingDouble(nx -> nx.y));
      o.put("count", sorted.size());
      JSONArray offsets = new JSONArray();
      if (sorted.isEmpty()) {
         o.put("offsets", offsets);
         return o;
      } else {
         double minx = Double.POSITIVE_INFINITY;
         double miny = Double.POSITIVE_INFINITY;
         double maxx = Double.NEGATIVE_INFINITY;
         double maxy = Double.NEGATIVE_INFINITY;

         for (CupboardCatalog.Node nx : sorted) {
            minx = Math.min(minx, nx.x);
            miny = Math.min(miny, nx.y);
            maxx = Math.max(maxx, nx.x);
            maxy = Math.max(maxy, nx.y);
         }

         Set<Integer> rows = new TreeSet<>();
         Set<Integer> cols = new TreeSet<>();

         for (CupboardCatalog.Node nx : sorted) {
            JSONArray p = new JSONArray();
            p.put((int)Math.round(nx.x - minx));
            p.put((int)Math.round(nx.y - miny));
            offsets.put(p);
            rows.add((int)Math.round(nx.y - miny));
            cols.add((int)Math.round(nx.x - minx));
         }

         o.put("offsets", offsets);
         o.put("width", (int)Math.round(maxx - minx));
         o.put("height", (int)Math.round(maxy - miny));
         o.put("tiles_w", round2((maxx - minx) / 11.0));
         o.put("tiles_h", round2((maxy - miny) / 11.0));
         o.put("rows", rows.size());
         o.put("columns", cols.size());
         o.put("shape", rows.size() == 1 ? "row" : (cols.size() == 1 ? "column" : "block"));
         JSONArray kinds = new JSONArray();
         int corners = 0;

         for (CupboardCatalog.Node nx : sorted) {
            boolean corner = CupboardCatalog.isCorner(nx, sorted);
            if (corner) {
               corners++;
            }

            kinds.put(corner ? "corner" : "aisle");
         }

         o.put("corners", corners);
         o.put("kinds", kinds);
         return o;
      }
   }

   static JSONObject evaluateCabinets(PrototypePathfinder.Scene scene) {
      List<JSONObject> checks = new ArrayList<>();
      JSONObject fixture = null;
      String note = null;
      if (scene != null && scene.player != null) {
         checks.add(check("player_present", true, "player gob present"));
      } else {
         checks.add(check("player_present", false, "no player gob in observed scene"));
      }

      List<PrototypePathfinder.GobGeom> cups = cupboardGobs(scene);
      if (cups.isEmpty()) {
         checks.add(check("cupboards_present", false, "no cupboards in observed scene (within 220.0 world units of player)"));
      } else {
         checks.add(check("cupboards_present", true, cups.size() + (cups.size() == 1 ? " cupboard" : " cupboards") + " in observed scene"));
         List<List<CupboardCatalog.Node>> clusters = clusterCupboards(cupboardNodes(cups));
         List<CupboardCatalog.Node> pick = uniqueLargest(clusters);
         if (pick == null) {
            checks.add(check("unique_largest_cluster", false, "largest cupboard cluster is not unique: sizes " + clusterSizes(clusters)));
            note = "no stable fixture: largest cupboard cluster is ambiguous";
         } else {
            checks.add(check("unique_largest_cluster", true, pick.size() + " cupboards in the unique largest cluster"));
            fixture = cupboardFingerprint(pick);
            checks.add(check("fixture_fingerprint", true, fixtureBrief(fixture)));
         }
      }

      JSONObject body = new JSONObject();
      body.put("verdict", verdictOf(checks));
      JSONArray arr = new JSONArray();

      for (JSONObject c : checks) {
         arr.put(c);
      }

      body.put("checks", arr);
      if (fixture != null) {
         body.put("fixture", fixture);
      }

      if (note != null) {
         body.put("note", note);
      }

      return body;
   }

   static String clusterSizes(List<List<CupboardCatalog.Node>> clusters) {
      StringBuilder sb = new StringBuilder("[");

      for (int i = 0; i < clusters.size(); i++) {
         if (i > 0) {
            sb.append(", ");
         }

         sb.append(clusters.get(i).size());
      }

      return sb.append("]").toString();
   }

   static String fixtureBrief(JSONObject fixture) {
      StringBuilder sb = new StringBuilder();
      sb.append("count=").append(fixture.getInt("count"));
      sb.append(" offsets=").append(fixture.getJSONArray("offsets").toString());
      sb.append(" ").append(fixture.optString("shape"));
      return sb.toString();
   }

   static JSONObject sceneJson(PrototypePathfinder.Scene scene) {
      JSONObject o = new JSONObject();
      if (scene == null) {
         return o;
      } else {
         o.put("origin", arr(scene.origin));
         o.put("grid", new JSONArray().put(scene.w).put(scene.h));
         o.put("cell", scene.cell);
         o.put("radius", round2(scene.radius));
         o.put("player", arr(scene.player));
         o.put("player_cell", scene.playerCell == null ? JSONObject.NULL : new JSONArray().put(scene.playerCell.x).put(scene.playerCell.y));
         o.put("moving", scene.moving);
         o.put("player_in_solid", scene.playerInSolid);
         o.put("player_in_inflated", scene.playerInDilated);
         o.put("terrain", scene.terrain);
         o.put("solid_count", scene.solidCount);
         o.put("inflated_count", scene.dilatedCount);
         o.put("obstacles", scene.obstacles);
         if (scene.occupancy != null && scene.playerCell != null) {
            o.put("ascii", scene.occupancy.ascii(scene.playerCell, 8));
         }

         return o;
      }
   }

   static JSONArray arr(Coord2d p) {
      JSONArray a = new JSONArray();
      if (p != null) {
         a.put(round2(p.x)).put(round2(p.y));
      }

      return a;
   }

   static String pt(Coord2d p) {
      return p == null ? "?" : String.format("(%.1f, %.1f)", p.x, p.y);
   }

   static double round2(double v) {
      return (double)Math.round(v * 100.0) / 100.0;
   }
}
