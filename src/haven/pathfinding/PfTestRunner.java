package haven.pathfinding;

import haven.HackThread;
import haven.Coord2d;
import haven.UI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * Localhost pf-test facade. Allowlisted scenarios live in {@link PfScenarioRegistry};
 * shared checks and artifacts live in {@link PfTestHarness}.
 */
public final class PfTestRunner {
   private static final Object LOCK = new Object();
   private static volatile PfTestRunner.Run current;
   private static final Deque<JSONObject> results = new ArrayDeque<>();
   static final AtomicInteger seq = new AtomicInteger();

   private PfTestRunner() {
   }

   public static List<String> knownScenarios() {
      return PfScenarioRegistry.names();
   }

   public static String validateScenario(String name) {
      if (name != null && !name.trim().isEmpty()) {
         String trimmed = name.trim();
         return !PfScenarioRegistry.known(trimmed)
            ? "unknown scenario \"" + trimmed + "\" (known: " + String.join(", ", PfScenarioRegistry.names()) + ")"
            : null;
      } else {
         return "missing scenario name";
      }
   }

   public static JSONObject check(String name, boolean passed, String detail) {
      return PfTestHarness.check(name, passed, detail);
   }

   public static JSONObject skip(String name, String detail) {
      return PfTestHarness.skip(name, detail);
   }

   public static String verdictOf(List<JSONObject> checks) {
      return PfTestHarness.verdictOf(checks);
   }

   public static boolean beginRun(PfTestRunner.Run r) {
      synchronized (LOCK) {
         if (current != null) {
            return false;
         } else {
            current = r;
            return true;
         }
      }
   }

   public static PfTestRunner.Run currentRun() {
      synchronized (LOCK) {
         return current;
      }
   }

   public static void finishRun(PfTestRunner.Run r) {
      synchronized (LOCK) {
         if (current == r) {
            current = null;
         }
      }
   }

   public static PfTestRunner.Run cancelRun() {
      synchronized (LOCK) {
         PfTestRunner.Run r = current;
         if (r != null) {
            r.cancelled = true;
         }

         return r;
      }
   }

   public static JSONObject start(String scenario, UI ui) {
      PfTestRunner.Run run = new PfTestRunner.Run(scenario);
      if (!beginRun(run)) {
         return new JSONObject().put("ok", false).put("conflict", true).put("error", "a run is already in progress (run_id=" + currentRun().id + ")");
      } else {
         Thread th = new HackThread(() -> execute(run, ui), "pf-test-run");
         th.setDaemon(true);
         th.start();
         return new JSONObject().put("ok", true).put("run_id", run.id).put("scenario", run.scenario).put("status", "running").put("started_ms", run.startedMs);
      }
   }

   public static JSONObject result(String runId) {
      if (runId == null) {
         return null;
      } else {
         synchronized (LOCK) {
            PfTestRunner.Run r = current;
            if (r != null && r.id.equals(runId)) {
               return new JSONObject().put("ok", true).put("run_id", r.id).put("scenario", r.scenario).put("status", "running").put("started_ms", r.startedMs);
            } else {
               for (JSONObject o : results) {
                  if (runId.equals(o.optString("run_id"))) {
                     return o;
                  }
               }

               return null;
            }
         }
      }
   }

   static void rememberResult(JSONObject o) {
      synchronized (LOCK) {
         results.addLast(o);

         while (results.size() > 32) {
            results.removeFirst();
         }
      }
   }

   private static void execute(PfTestRunner.Run run, UI ui) {
      JSONObject result = new JSONObject();
      result.put("ok", true);
      result.put("run_id", run.id);
      result.put("scenario", run.scenario);
      result.put("started_ms", run.startedMs);

      try {
         PathfinderLog.resetRun();
         PfTestRunner.Scenario sc = PfScenarioRegistry.get(run.scenario);
         JSONObject body = sc.execute(run, ui);
         long now = System.currentTimeMillis();
         result.put("status", "completed");
         result.put("finished_ms", now);
         result.put("duration_ms", now - run.startedMs);
         result.put("verdict", body.getString("verdict"));
         result.put("checks", body.getJSONArray("checks"));
         if (body.has("scene")) {
            result.put("scene", body.get("scene"));
         }

         if (body.has("facts")) {
            result.put("facts", body.get("facts"));
         }

         if (body.has("note")) {
            result.put("note", body.getString("note"));
         }
      } catch (PfTestRunner.Cancelled var11) {
         long nowx = System.currentTimeMillis();
         result.put("status", "cancelled");
         result.put("finished_ms", nowx);
         result.put("duration_ms", nowx - run.startedMs);
         if (var11.body != null) {
            if (var11.body.has("verdict")) {
               result.put("verdict", var11.body.getString("verdict"));
            }

            if (var11.body.has("checks")) {
               result.put("checks", var11.body.get("checks"));
            }

            if (var11.body.has("facts")) {
               result.put("facts", var11.body.get("facts"));
            }
         }

         result.put("note", "cancelled by POST /pf/cancel");
      } catch (Exception var12) {
         long nowx = System.currentTimeMillis();
         result.put("status", "error");
         result.put("finished_ms", nowx);
         result.put("duration_ms", nowx - run.startedMs);
         result.put("error", var12.getMessage() == null ? var12.toString() : var12.getMessage());
      } finally {
         try {
            PfTestHarness.write(run, result);
         } finally {
            rememberResult(result);
            finishRun(run);
         }
      }
   }

   static void write(PfTestRunner.Run run, JSONObject result, Path root) {
      PfTestHarness.write(run, result, root);
   }

   static List<PrototypePathfinder.GobGeom> cupboardGobs(PrototypePathfinder.Scene scene) {
      return PfTestHarness.cupboardGobs(scene);
   }

   static List<CupboardCatalog.Node> cupboardNodes(List<PrototypePathfinder.GobGeom> gobs) {
      return PfTestHarness.cupboardNodes(gobs);
   }

   static List<List<CupboardCatalog.Node>> clusterCupboards(List<CupboardCatalog.Node> nodes) {
      return PfTestHarness.clusterCupboards(nodes);
   }

   static List<CupboardCatalog.Node> uniqueLargest(List<List<CupboardCatalog.Node>> clusters) {
      return PfTestHarness.uniqueLargest(clusters);
   }

   static JSONObject cupboardFingerprint(List<CupboardCatalog.Node> cluster) {
      return PfTestHarness.cupboardFingerprint(cluster);
   }

   static JSONObject evaluateCabinets(PrototypePathfinder.Scene scene) {
      return PfTestHarness.evaluateCabinets(scene);
   }

   static JSONObject sceneJson(PrototypePathfinder.Scene scene) {
      return PfTestHarness.sceneJson(scene);
   }

   static double round2(double v) {
      return PfTestHarness.round2(v);
   }

   static String pt(Coord2d p) {
      return PfTestHarness.pt(p);
   }

   static final class Cancelled extends Exception {
      final JSONObject body;

      Cancelled() {
         super("run cancelled");
         this.body = null;
      }

      Cancelled(JSONObject body) {
         super("run cancelled");
         this.body = body;
      }
   }

   public static final class Run {
      public final String id;
      public final String scenario;
      public final long startedMs;
      volatile boolean cancelled;

      Run(String scenario) {
         this.id = String.format("%s-%d-%d", scenario, System.currentTimeMillis(), PfTestRunner.seq.incrementAndGet());
         this.scenario = scenario;
         this.startedMs = System.currentTimeMillis();
      }
   }

   public interface Scenario {
      String name();

      JSONObject execute(PfTestRunner.Run var1, UI var2) throws Exception;
   }
}
