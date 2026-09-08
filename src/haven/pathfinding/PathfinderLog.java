package haven.pathfinding;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Utils;
import haven.GameUI.MsgType;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PathfinderLog {
   private static final int KEEP = 24;
   private static final Deque<JSONObject> recent = new ArrayDeque<>();
   private static volatile JSONObject last;
   private static volatile List<Coord2d> lastPath = Collections.emptyList();
   private static volatile List<Coord2d> lastAStar = Collections.emptyList();
   private static volatile List<Coord2d[]> lastPolys = Collections.emptyList();
   private static volatile Coord2d lastDest;
   private static volatile OccupancyGrid lastOcc;
   private static volatile String lastReason = "";
   private static volatile String lastReplanReason = "";
   private static volatile Coord2d lastActiveWaypoint;
   private static volatile Coord2d lastConfirmedPos;
   private static volatile List<Coord2d> lastHazards = Collections.emptyList();
   private static volatile JSONObject lastInteraction;
   private static volatile JSONObject lastGraph;
   private static volatile JSONObject lastGeometry;
   private static volatile List<Coord2d[]> lastSolids = Collections.emptyList();
   private static volatile List<Coord2d[]> lastPlayerBody = Collections.emptyList();
   private static final ThreadLocal<String> target = new ThreadLocal<>();
   private static final ThreadLocal<Integer> probeDepth = ThreadLocal.withInitial(() -> 0);

   private PathfinderLog() {
   }

   static {
      RecedingHorizonNavigator.setTelemetry(new haven.nav.NavigationTelemetrySink() {
         @Override
         public void onEvent(String type, String detail) {
            if ("replan".equals(type)) {
               PathfinderLog.setReplanReason(detail);
            }
         }
      });
   }

   public static void resetRun() {
      synchronized (recent) {
         recent.clear();
         last = null;
         lastPath = Collections.emptyList();
         lastAStar = Collections.emptyList();
         lastPolys = Collections.emptyList();
         lastDest = null;
         lastOcc = null;
         lastReason = "";
         lastReplanReason = "";
         lastActiveWaypoint = null;
         lastConfirmedPos = null;
         lastHazards = Collections.emptyList();
         lastInteraction = null;
         lastGraph = null;
      }
   }

   public static void setTarget(String label) {
      target.set(label);
   }

   public static void clearTarget() {
      target.remove();
   }

   public static void beginProbe() {
      probeDepth.set(probeDepth.get() + 1);
   }

   public static void endProbe() {
      int n = probeDepth.get() - 1;
      if (n <= 0) {
         probeDepth.remove();
      } else {
         probeDepth.set(n);
      }
   }

   public static boolean probing() {
      Integer n = probeDepth.get();
      return n != null && n > 0;
   }

   public static void record(GameUI gui, PathfinderLog.Trace trace) {
      if (trace != null) {
         JSONObject o = trace.toJson();
         boolean probe = probing();
         synchronized (recent) {
            recent.addLast(o);

            while (recent.size() > 24) {
               recent.removeFirst();
            }

            if (!probe) {
               last = o;
               lastPath = new ArrayList<>(trace.waypoints);
               lastAStar = (List<Coord2d>)(trace.astar == null ? Collections.emptyList() : new ArrayList<>(trace.astar));
               lastPolys = (List<Coord2d[]>)(trace.polys == null ? Collections.emptyList() : new ArrayList<>(trace.polys));
               lastDest = Coord2d.of(trace.dx, trace.dy);
               lastOcc = trace.occupancy;
               lastReason = trace.reason;
               lastHazards = (List<Coord2d>)(trace.hazards == null ? Collections.emptyList() : new ArrayList<>(trace.hazards));
               if (gui != null && gui.map != null && gui.map.player() != null) {
                  lastConfirmedPos = gui.map.player().rc;
               }
            }
         }

         append(o);
         if (!probe && gui != null && Boolean.TRUE.equals(CFG.DEBUG_PATHFIND.get())) {
            gui.msg("PF " + trace.summary(), MsgType.INFO);
         }
      }
   }

   public static void recordExec(haven.pathfinding.SurfaceController.Tick tick) {
      if (tick == null || tick.decision == null) {
         return;
      }
      org.json.JSONObject o = new org.json.JSONObject();
      o.put("kind", tick.decision.kind.name());
      o.put("reason", tick.reason == null ? tick.decision.reason : tick.reason.name());
      o.put("recovery", tick.recoveryCount);
      o.put("plan_ms", tick.planMs);
      if (tick.decision.target != null) {
         o.put("target", new org.json.JSONArray().put(tick.decision.target.x).put(tick.decision.target.y));
      }
      if (tick.pick != null) {
         o.put("selected_index", tick.pick.selectedIndex);
         o.put("considered_index", tick.pick.consideredIndex);
         o.put("corridor_valid", tick.pick.corridorValid);
         if (tick.pick.whyShorter != null) {
            o.put("why_shorter", tick.pick.whyShorter);
         }
      }
      if (tick.escapeCell != null) {
         o.put("escape", new org.json.JSONArray().put(tick.escapeCell.x).put(tick.escapeCell.y));
      }
      if (tick.reason == haven.pathfinding.SurfaceStream.Reason.RECOVERY && tick.escapeCell == null) {
         o.put("blacklist", true);
      }
      if (tick.decision.outcome != null) {
         o.put("outcome", tick.decision.outcome.name());
      }
      synchronized (recent) {
         recent.addLast(o);
         while (recent.size() > 24) {
            recent.removeFirst();
         }
      }
      if (tick.reason != null) {
         lastReplanReason = tick.reason.name();
      }
   }

   public static JSONObject last() {
      return last;
   }

   public static List<Coord2d> lastPath() {
      return lastPath;
   }

   public static List<Coord2d> lastAStar() {
      return lastAStar;
   }

   public static List<Coord2d[]> lastPolys() {
      return lastPolys;
   }

   public static Coord2d lastDest() {
      return lastDest;
   }

   public static PathfinderLog.Occupancy lastOccupancy() {
      return PathfinderLog.Occupancy.wrap(lastOcc);
   }

   public static void recordOccupancy(OccupancyGrid occ) {
      lastOcc = occ;
   }

   /**
    * Drops the cached occupancy snapshot and the exact solid/body geometry so
    * the next leg is forced to rebuild local geometry instead of planning
    * against the previous floor after an authoritative surface change.
    */
   public static void invalidateOccupancy() {
      lastOcc = null;
      lastSolids = Collections.emptyList();
      lastPlayerBody = Collections.emptyList();
   }

   public static String lastReason() {
      return lastReason;
   }

   public static void setReplanReason(String reason) {
      lastReplanReason = reason == null ? "" : reason;
   }

   public static String lastReplanReason() {
      return lastReplanReason;
   }

   public static void setActiveWaypoint(Coord2d waypoint) {
      lastActiveWaypoint = waypoint;
   }

   public static Coord2d lastActiveWaypoint() {
      return lastActiveWaypoint;
   }

   public static Coord2d lastConfirmedPos() {
      return lastConfirmedPos;
   }

   public static void recordConfirmedPos(Coord2d pos) {
      lastConfirmedPos = pos;
   }

   public static void recordHazards(List<Coord2d> hazards) {
      lastHazards = hazards == null ? Collections.emptyList() : new ArrayList<>(hazards);
   }

   public static List<Coord2d> lastHazards() {
      return lastHazards;
   }

   public static void recordDest(Coord2d dest) {
      lastDest = dest;
   }

   public static void recordInteraction(JSONObject interaction) {
      lastInteraction = interaction;
      if (interaction != null) {
         Coord2d sel = point2d(interaction.opt("selected"));
         if (sel != null) {
            lastDest = sel;
         }
      }
   }

   public static JSONObject lastInteraction() {
      return lastInteraction;
   }

   public static void recordGraph(JSONObject graph) {
      lastGraph = graph;
   }

   /** Compact transition diagnostic entry; same slot as the graph evidence, tagged with a stage. */
   public static void recordTransition(String stage, JSONObject o) {
      if (o != null) {
         o.put("stage", stage == null ? "" : stage);
         recordGraph(o);
      }
   }

   public static JSONObject lastGraph() {
      return lastGraph;
   }

   public static void recordExactGeometry(List<Coord2d[]> solids, List<Coord2d[]> playerBody) {
      lastSolids = solids == null ? Collections.<Coord2d[]>emptyList() : solids;
      lastPlayerBody = playerBody == null ? Collections.<Coord2d[]>emptyList() : playerBody;
   }

   public static List<Coord2d[]> lastSolids() {
      return lastSolids;
   }

   public static List<Coord2d[]> lastPlayerBody() {
      return lastPlayerBody;
   }

   public static void recordGeometryDump(JSONObject dump) {
      lastGeometry = dump;
      if (dump != null) {
         appendGeometry(dump);
      }
   }

   public static JSONObject lastGeometryDump() {
      return lastGeometry;
   }

   /** Durable terminal-failure dump target: one JSONL file per failure. */
   public static Path failureDumpFile() {
      return Utils.path(System.getProperty("user.dir", ".")).resolve("dev-snapshots").resolve("pf").resolve("stuck")
         .resolve(System.currentTimeMillis() + ".jsonl");
   }

   /**
    * Terminal-failure dump: writes one JSON line capturing whatever diagnostic
    * state is in memory (exec-tick ring, occupancy, staging/transition records)
    * so a failed navigation can be inspected later. Degrades gracefully — every
    * field is omitted/nulled if unavailable — and never throws.
    */
   public static void dumpFailure(String reason) {
      try {
         JSONObject o = new JSONObject();
         o.put("t", System.currentTimeMillis());
         o.put("reason", reason == null ? "" : reason);
         Coord2d pos = lastConfirmedPos;
         o.put("player", pos == null ? JSONObject.NULL : new JSONArray().put(round(pos.x)).put(round(pos.y)));
         JSONArray arr = new JSONArray();
         for (JSONObject e : recent()) {
            arr.put(e);
         }
         o.put("recent", arr);
         PathfinderLog.Occupancy occ = PathfinderLog.Occupancy.wrap(lastOcc);
         if (occ != null) {
            o.put("occupancy_ascii", occ.ascii(occ.start, 10));
         }
         o.put("last_interaction", lastInteraction == null ? JSONObject.NULL : lastInteraction);
         o.put("last_graph", lastGraph == null ? JSONObject.NULL : lastGraph);
         Path file = failureDumpFile();
         Files.createDirectories(file.getParent());
         Writer w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
         try {
            w.write(o.toString());
            w.write(10);
         } finally {
            w.close();
         }
      } catch (IOException | RuntimeException ignored) {
      }
   }

   public static Path geometryDumpFile() {
      return Utils.path(System.getProperty("user.dir", ".")).resolve("dev-snapshots").resolve("pf").resolve("geometry-dump.jsonl");
   }

   private static void appendGeometry(JSONObject o) {
      try {
         Path file = geometryDumpFile();
         Files.createDirectories(file.getParent());
         Writer w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
         try {
            w.write(o.toString());
            w.write(10);
         } finally {
            w.close();
         }
      } catch (IOException ignored) {
      }
   }

   private static Coord2d point2d(Object v) {
      if (v instanceof JSONArray) {
         JSONArray a = (JSONArray) v;
         if (a.length() >= 2) {
            return Coord2d.of(a.getDouble(0), a.getDouble(1));
         }
      }
      return null;
   }

   public static List<JSONObject> recent() {
      synchronized (recent) {
         return new ArrayList<>(recent);
      }
   }

   public static Path logFile() {
      return Utils.path(System.getProperty("user.dir", ".")).resolve("dev-snapshots").resolve("pf").resolve("plans.jsonl");
   }

   public static JSONObject capture() {
      JSONObject body = new JSONObject();
      body.put("log_file", logFile().toString());
      body.put("last", last == null ? JSONObject.NULL : last);
      PathfinderLog.Occupancy occ = PathfinderLog.Occupancy.wrap(lastOcc);
      if (occ != null) {
         body.put("occupancy_ascii", occ.ascii(occ.start, 10));
      }

      JSONArray arr = new JSONArray();

      for (JSONObject o : recent()) {
         arr.put(o);
      }

      body.put("recent", arr);
      return body;
   }

   private static void append(JSONObject o) {
      try {
         Path file = logFile();
         Files.createDirectories(file.getParent());
         Writer w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

         try {
            w.write(o.toString());
            w.write(10);
         } catch (Throwable var6) {
            if (w != null) {
               try {
                  w.close();
               } catch (Throwable var5) {
                  var6.addSuppressed(var5);
               }
            }

            throw var6;
         }

         if (w != null) {
            w.close();
         }
      } catch (IOException var7) {
      }
   }

   private static JSONArray cell(Coord c) {
      return c == null ? new JSONArray() : new JSONArray().put(c.x).put(c.y);
   }

   private static double round(double v) {
      return (double)Math.round(v * 10.0) / 10.0;
   }

   public static final class Occupancy extends OccupancyGrid {
      public Occupancy(Coord2d origin, int w, int h, double cell, byte[] occ, Coord start, Coord goal, Coord freeGoal, List<Coord> astar) {
         super(origin, w, h, cell, occ, start, goal, freeGoal, astar);
      }

      public static Occupancy wrap(OccupancyGrid g) {
         if (g == null) {
            return null;
         }
         if (g instanceof Occupancy) {
            return (Occupancy) g;
         }
         return new Occupancy(g.origin, g.w, g.h, g.cell, g.occ, g.start, g.goal, g.freeGoal, g.astar);
      }

      public static Occupancy capture(
         Coord2d origin,
         int w,
         int h,
         double cell,
         boolean[] solid,
         boolean[] dilated,
         boolean[] blockedAfter,
         Coord start,
         Coord goal,
         Coord freeGoal,
         List<Coord> astar
      ) {
         return wrap(OccupancyGrid.capture(origin, w, h, cell, solid, dilated, blockedAfter, start, goal, freeGoal, astar));
      }

      public static String encode(OccupancyGrid occ) {
         return OccupancyGrid.encode(occ);
      }

      public static Occupancy decode(
         Coord2d origin, int w, int h, double cell, String encoded, Coord start, Coord goal, Coord freeGoal
      ) {
         return wrap(OccupancyGrid.decode(origin, w, h, cell, encoded, start, goal, freeGoal));
      }
   }


   public static final class Trace extends PlanningTrace {
      JSONObject toJson() {
         JSONObject o = new JSONObject();
         o.put("t", System.currentTimeMillis());
         String tgt = PathfinderLog.target.get();
         o.put("target", tgt == null ? "" : tgt);
         o.put("reason", this.reason);
         o.put("clip", this.clip == null ? "" : this.clip);
         o.put("start", new JSONArray().put(PathfinderLog.round(this.sx)).put(PathfinderLog.round(this.sy)));
         o.put("dest", new JSONArray().put(PathfinderLog.round(this.dx)).put(PathfinderLog.round(this.dy)));
         o.put("agent_radius", PathfinderLog.round(this.radius));
         o.put("dilation", this.dilation);
         o.put("grid", new JSONArray().put(this.gridW).put(this.gridH));
         o.put("start_in_solid", this.startInSolid);
         o.put("start_blocked", this.startBlocked);
         o.put("start_blocked_after_carve", this.startBlockedAfter);
         o.put("goal_blocked", this.goalBlocked);
         o.put("start_cell", PathfinderLog.cell(this.startCell));
         o.put("goal_cell", PathfinderLog.cell(this.goalCell));
         o.put("free_goal", PathfinderLog.cell(this.freeGoal));
         o.put("expanded", this.expanded);
         o.put("obstacles", this.obstacles);
         o.put("complete", this.complete);
         o.put("probe", PathfinderLog.probing());
         o.put("waypoint_count", this.waypoints.size());
         o.put("astar_count", this.astar == null ? 0 : this.astar.size());
         JSONArray wps = new JSONArray();

         for (Coord2d p : this.waypoints) {
            wps.put(new JSONArray().put(PathfinderLog.round(p.x)).put(PathfinderLog.round(p.y)));
         }

         o.put("waypoints", wps);
         JSONArray nearArr = new JSONArray();

         for (String n : this.near) {
            nearArr.put(n);
         }

         o.put("near", nearArr);
         return o;
      }

      public String summary() {
         String tgt = PathfinderLog.target.get();
         if (tgt == null || tgt.isEmpty()) {
            tgt = this.destLabel();
         }

         String clipBit = this.clip != null && !this.clip.isEmpty() ? " clip=" + this.clip : "";
         return String.format(
            "%s  %s  %d wp  %d nodes  r=%.1f dil=%d%s%s",
            this.reason,
            tgt,
            Math.max(0, this.waypoints.size() - 1),
            this.expanded,
            this.radius,
            this.dilation,
            this.complete ? "" : " partial",
            clipBit
         );
      }

      private String destLabel() {
         return String.format("%.0f,%.0f", this.dx, this.dy);
      }
   }
}
