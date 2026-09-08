package haven.pathfinding;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.MapView;
import haven.Moving;
import haven.Console.Command;
import haven.GameUI.MsgType;
import haven.dev.DevFeature;
import haven.dev.Feature;
import java.awt.Color;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PathfinderDebug implements Feature {
   private static final Color PATH = new Color(0, 220, 255, 220);
   private static final Color ASTAR = new Color(70, 140, 255, 200);
   private static final Color CLIP = new Color(255, 70, 70, 230);
   private static final Color SOLID = new Color(255, 40, 180, 170);
   private static final Color DILATED = new Color(255, 150, 40, 150);
   private static final Color CARVED = new Color(90, 220, 90, 140);
   private static final Color TEXT = new Color(220, 255, 255);
   private static final Color FAIL = new Color(255, 90, 90);
   private static final Color BODY = new Color(80, 255, 120, 220);
   private static final Color WANT = new Color(255, 180, 40);
   private static final Color END = new Color(255, 255, 255);
   private static final Color START_REGION = new Color(80, 255, 180, 200);
   private static final Color GOAL_REGION = new Color(255, 90, 40, 210);
   private static final Color HAZARD = new Color(255, 40, 40, 200);
   private static final Color ACTIVE_WP = new Color(255, 255, 80, 230);
   private static final Color CONFIRMED = new Color(180, 255, 80, 230);
   private static final Color STAIRS_POLY = new Color(255, 230, 40, 220);
   private static final Color DESK_OBST = new Color(40, 220, 255, 230);
   private static final Color DESK_PLACE = new Color(255, 70, 220, 200);
   private static final Color DESK_FALLBACK = new Color(255, 140, 40, 220);
   private static final Color CAND_OK = new Color(80, 255, 120, 220);
   private static final Color CAND_NO = new Color(255, 70, 70, 180);
   private static final int GRID_R = 26;

   public String name() {
      return "pf";
   }

   public CFG<Boolean> toggle() {
      return CFG.DEBUG_PATHFIND;
   }

   public JSONObject capture() {
      return PathfinderLog.capture();
   }

   public void paint(GOut g, MapView mv) {
      paintGrid(g, mv);
      paintHazards(g, mv);
      paintStartGoalRegions(g, mv);
      paintRoutes(g, mv);
      paintActiveWaypoint(g, mv);
      paintConfirmedPos(g, mv);
      paintBody(g, mv);
      paintGeometryDump(g, mv);
      if (!CatalogDebug.overlayActive()) {
         CatalogDebug.paintCupboardIds(g, mv);
      }

      paintHud(g, mv);
   }

   static void paintGrid(GOut g, MapView mv) {
      PathfinderLog.Occupancy occ = PathfinderLog.lastOccupancy();
      if (mv != null && occ != null && occ.origin != null) {
         Gob me = mv.player();
         Coord focus = me != null && me.rc != null ? occ.cellOf(me.rc) : occ.start;
         if (focus == null) {
            focus = Coord.z;
         }

         for (int y = focus.y - 26; y <= focus.y + 26; y++) {
            for (int x = focus.x - 26; x <= focus.x + 26; x++) {
               if (x >= 0 && y >= 0 && x < occ.w && y < occ.h) {
                  byte v = occ.at(x, y);
                  boolean path = occ.onPath(x, y);
                  if (v != 0 || path) {
                     Color col = SOLID;
                     if (v == 2) {
                        col = DILATED;
                     } else if (v == 3) {
                        col = CARVED;
                     } else if (v == 0) {
                        col = ASTAR;
                     }

                     quad(g, mv, occ, x, y, col);
                  }
               }
            }
         }
      }
   }

   private static void paintRoutes(GOut g, MapView mv) {
      JSONObject last = PathfinderLog.last();
      boolean clipped = last != null && last.optString("clip", "").length() > 0;
      polyline(g, mv, PathfinderLog.lastAStar(), ASTAR, 1, 3);
      polyline(g, mv, PathfinderLog.lastPath(), clipped ? CLIP : PATH, 2, 4);
      marker(g, mv, PathfinderLog.lastDest(), WANT, 8, "want");
      List<Coord2d> path = PathfinderLog.lastPath();
      if (path != null && !path.isEmpty()) {
         marker(g, mv, path.get(path.size() - 1), END, 6, "end");
      }
   }

   private static void paintStartGoalRegions(GOut g, MapView mv) {
      PathfinderLog.Occupancy occ = PathfinderLog.lastOccupancy();
      if (mv == null || occ == null) {
         return;
      }
      if (occ.start != null) {
         quad(g, mv, occ, occ.start.x, occ.start.y, START_REGION);
         marker(g, mv, occ.world(occ.start.x, occ.start.y), START_REGION, 7, "start");
      }
      if (occ.goal != null) {
         quad(g, mv, occ, occ.goal.x, occ.goal.y, GOAL_REGION);
         marker(g, mv, occ.world(occ.goal.x, occ.goal.y), GOAL_REGION, 7, "goal");
      }
      if (occ.freeGoal != null && (occ.goal == null || !occ.freeGoal.equals(occ.goal))) {
         quad(g, mv, occ, occ.freeGoal.x, occ.freeGoal.y, GOAL_REGION);
         marker(g, mv, occ.world(occ.freeGoal.x, occ.freeGoal.y), GOAL_REGION, 6, "free");
      }
   }

   private static void paintHazards(GOut g, MapView mv) {
      List<Coord2d> hazards = PathfinderLog.lastHazards();
      if (mv == null || hazards == null) {
         return;
      }
      for (Coord2d h : hazards) {
         marker(g, mv, h, HAZARD, 9, "hz");
      }
   }

   private static void paintActiveWaypoint(GOut g, MapView mv) {
      Coord2d wp = PathfinderLog.lastActiveWaypoint();
      if (wp != null) {
         marker(g, mv, wp, ACTIVE_WP, 10, "wp");
      }
   }

   private static void paintConfirmedPos(GOut g, MapView mv) {
      Coord2d pos = PathfinderLog.lastConfirmedPos();
      if (pos == null && mv != null && mv.player() != null) {
         pos = mv.player().rc;
      }
      marker(g, mv, pos, CONFIRMED, 6, "srv");
   }

   private static void paintBody(GOut g, MapView mv) {
      if (mv != null) {
         Gob me = mv.player();
         if (me != null && me.rc != null) {
            List<Coord2d[]> body = PrototypePathfinder.playerBodyOrigin(me);
            g.chcolor(BODY);
            if (body != null && !body.isEmpty()) {
               for (Coord2d[] poly : body) {
                  if (poly != null && poly.length >= 2) {
                     Coord prev = null;
                     Coord first = null;

                     for (int i = 0; i <= poly.length; i++) {
                        Coord2d world = me.rc.add(poly[i % poly.length]);
                        Coord s = screen(mv, world);
                        if (s == null) {
                           prev = null;
                        } else {
                           if (first == null) {
                              first = s;
                           }

                           if (prev != null) {
                              g.line(prev, s, 2.0);
                           }

                           prev = s;
                        }
                     }
                  }
               }
            } else {
               double r = 4.5;
               Coord n = screen(mv, me.rc.add(0.0, -r));
               Coord e = screen(mv, me.rc.add(r, 0.0));
               Coord s = screen(mv, me.rc.add(0.0, r));
               Coord w = screen(mv, me.rc.add(-r, 0.0));
               if (n != null && e != null) {
                  g.line(n, e, 2.0);
               }

               if (e != null && s != null) {
                  g.line(e, s, 2.0);
               }

               if (s != null && w != null) {
                  g.line(s, w, 2.0);
               }

               if (w != null && n != null) {
                  g.line(w, n, 2.0);
               }
            }

            g.chcolor();
         }
      }
   }

   private static void paintGeometryDump(GOut g, MapView mv) {
      JSONObject dump = PathfinderLog.lastGeometryDump();
      if (mv == null || dump == null) {
         return;
      }
      paintJsonPolys(g, mv, dump.optJSONArray("player_body"), BODY, 2, scenePlayer(dump));
      JSONArray desks = dump.optJSONArray("study_desks");
      if (desks != null) {
         for (int i = 0; i < desks.length(); i++) {
            JSONObject d = desks.optJSONObject(i);
            if (d == null) {
               continue;
            }
            paintJsonPolys(g, mv, d.optJSONArray("placement"), DESK_PLACE, 1, null);
            paintJsonPolys(g, mv, d.optJSONArray("fallback") == null ? null : wrapPoly(d.opt("fallback")), DESK_FALLBACK, 3, null);
            paintJsonPolys(g, mv, d.optJSONArray("obst"), DESK_OBST, 2, null);
         }
      }
      JSONArray stairs = dump.optJSONArray("stairs");
      if (stairs != null) {
         for (int i = 0; i < stairs.length(); i++) {
            JSONObject s = stairs.optJSONObject(i);
            if (s == null) {
               continue;
            }
            JSONArray mvpoly = s.optJSONArray("movement");
            if (mvpoly == null || mvpoly.length() == 0) {
               mvpoly = s.optJSONArray("obst");
            }
            paintJsonPolys(g, mv, mvpoly, STAIRS_POLY, 2, null);
         }
      }
      JSONArray cands = dump.optJSONArray("candidates");
      if (cands != null) {
         int n = Math.min(cands.length(), 80);
         for (int i = 0; i < n; i++) {
            JSONObject c = cands.optJSONObject(i);
            if (c == null) {
               continue;
            }
            Coord2d w = jsonPt(c.opt("world"));
            boolean ok = c.isNull("reject");
            marker(g, mv, w, ok ? CAND_OK : CAND_NO, ok ? 6 : 4, null);
         }
      }
      marker(g, mv, jsonPt(dump.opt("selected")), CAND_OK, 9, "pose");
   }

   private static Coord2d scenePlayer(JSONObject dump) {
      return jsonPt(dump.opt("player"));
   }

   private static JSONArray wrapPoly(Object v) {
      if (v instanceof JSONArray) {
         JSONArray a = (JSONArray) v;
         if (a.length() > 0 && a.optJSONArray(0) != null && a.optJSONArray(0).length() >= 2 && !(a.optJSONArray(0).optJSONArray(0) != null)) {
            JSONArray wrap = new JSONArray();
            wrap.put(a);
            return wrap;
         }
         return a;
      }
      return null;
   }

   private static void paintJsonPolys(GOut g, MapView mv, JSONArray polys, Color color, int width, Coord2d origin) {
      if (polys == null) {
         return;
      }
      g.chcolor(color);
      for (int p = 0; p < polys.length(); p++) {
         JSONArray poly = polys.optJSONArray(p);
         if (poly == null) {
            continue;
         }
         Coord prev = null;
         Coord first = null;
         for (int i = 0; i <= poly.length(); i++) {
            JSONArray pt = poly.optJSONArray(i % poly.length());
            Coord2d world = jsonPt(pt);
            if (world != null && origin != null) {
               world = origin.add(world);
            }
            Coord s = screen(mv, world);
            if (s == null) {
               prev = null;
            } else {
               if (first == null) {
                  first = s;
               }
               if (prev != null) {
                  g.line(prev, s, (double) width);
               }
               prev = s;
            }
         }
      }
      g.chcolor();
   }

   private static Coord2d jsonPt(Object v) {
      if (!(v instanceof JSONArray)) {
         return null;
      }
      JSONArray a = (JSONArray) v;
      if (a.length() < 2) {
         return null;
      }
      return Coord2d.of(a.optDouble(0), a.optDouble(1));
   }

   private static void paintHud(GOut g, MapView mv) {
      JSONObject last = PathfinderLog.last();
      int y = 12;
      if (CatalogDebug.overlayActive()) {
         y = 110;
      }

      if (MechanicsProbe.lastScene() != null) {
         y = Math.max(y, 140);
      }

      g.chcolor(TEXT);
      if (last == null) {
         g.atext("pf: no executed walk yet  (stand probes are hidden from this overlay)", new Coord(12, y), 0.0, 0.0);
         g.chcolor();
      } else {
         boolean clipped = last.optString("clip", "").length() > 0;
         g.atext(
            "pf: " + last.optString("reason", "?") + "  " + last.optString("target", "") + (clipped ? "  CLIP " + last.optString("clip") : ""),
            new Coord(12, y),
            0.0,
            0.0
         );
         y += 14;
         g.atext(
            String.format(
               "wp=%d  astar=%d  nodes=%d  r=%.1f  dil=%d  obst=%d%s",
               last.optInt("waypoint_count"),
               last.optInt("astar_count"),
               last.optInt("expanded"),
               last.optDouble("agent_radius"),
               last.optInt("dilation"),
               last.optInt("obstacles"),
               last.optBoolean("complete") ? "" : "  PARTIAL"
            ),
            new Coord(12, y),
            0.0,
            0.0
         );
         y += 14;
         String diag = diagnosis(mv);
         g.chcolor(!diag.startsWith("STUCK") && !diag.contains("SOLID") && !diag.contains("INFLATED") ? TEXT : FAIL);
         g.atext(diag, new Coord(12, y), 0.0, 0.0);
         y += 14;
         g.chcolor(TEXT);
         g.atext("grid #solid  +inflated  o carve   cyan=walk  green=body  orange=want  cupboard #id W=walk V=via", new Coord(12, y), 0.0, 0.0);
         y += 14;
         String replan = PathfinderLog.lastReplanReason();
         g.atext(
            "start/goal regions  hz=hazard  wp=active waypoint  srv=server pos  replan=" + (replan == null || replan.isEmpty() ? "-" : replan),
            new Coord(12, y),
            0.0,
            0.0
         );
         y += 14;
         JSONObject geom = PathfinderLog.lastGeometryDump();
         if (geom != null) {
            JSONArray desks = geom.optJSONArray("study_desks");
            String reason = desks != null && desks.length() > 0 ? desks.getJSONObject(0).optString("collision_reason", "") : "";
            g.atext(
               "geom cyan=desk obst  magenta=placement  orange=12x32 fallback  yellow=stairs  red/green=candidates  " + reason,
               new Coord(12, y),
               0.0,
               0.0
            );
         }
         g.chcolor();
      }
   }

   static String diagnosis(MapView mv) {
      JSONObject last = PathfinderLog.last();
      Gob me = mv == null ? null : mv.player();
      PathfinderLog.Occupancy occ = PathfinderLog.lastOccupancy();
      StringBuilder sb = new StringBuilder();
      boolean idle = me == null || me.getattr(Moving.class) == null;
      if (CatalogDebug.waitingWalk() && idle) {
         sb.append("STUCK idle during walk  ");
      } else if (!idle) {
         sb.append("moving  ");
      } else {
         sb.append("idle  ");
      }

      if (me != null && me.rc != null) {
         Coord2d dest = PathfinderLog.lastDest();
         List<Coord2d> path = PathfinderLog.lastPath();
         Coord2d end = path != null && !path.isEmpty() ? path.get(path.size() - 1) : dest;
         if (end != null) {
            sb.append(String.format("%.1ft from end  ", me.rc.dist(end) / MCache.tilesz.x));
         }

         if (dest != null && (end == null || dest.dist(end) > 1.0)) {
            sb.append(String.format("%.1ft from want  ", me.rc.dist(dest) / MCache.tilesz.x));
         }

         if (occ != null) {
            Coord cell = occ.cellOf(me.rc);
            if (cell != null && cell.x >= 0 && cell.y >= 0 && cell.x < occ.w && cell.y < occ.h) {
               byte v = occ.at(cell.x, cell.y);
               sb.append("cell ").append(PathfinderLog.Occupancy.name(v)).append("  ");
            } else {
               sb.append("off last grid  ");
            }
         }
      }

      if (last != null) {
         if (last.optBoolean("start_in_solid")) {
            sb.append("start in SOLID  ");
         } else if (last.optBoolean("start_blocked")) {
            sb.append("start in INFLATED  ");
         }

         if (last.optBoolean("goal_blocked")) {
            sb.append("wanted stand BLOCKED  ");
         }

         if (!last.optBoolean("complete")) {
            sb.append("PARTIAL  ");
         }
      }

      String extra = CatalogDebug.standHint();
      if (extra != null && !extra.isEmpty()) {
         sb.append(extra);
      }

      return sb.toString().trim();
   }

   static void dumpStuck(GameUI gui, PrintWriter out) {
      MapView mv = gui == null ? null : gui.map;
      String diag = diagnosis(mv);
      out.println("=== pf stuck ===");
      out.println(diag);
      CatalogDebug.printLog(out);
      printLog(out);
      PathfinderLog.Occupancy occ = PathfinderLog.lastOccupancy();
      Gob me = mv == null ? null : mv.player();
      if (occ != null) {
         Coord focus = me != null && me.rc != null ? occ.cellOf(me.rc) : occ.start;
         out.println("occupancy around player:");
         out.println(occ.ascii(focus, 12));
      } else {
         out.println("(no occupancy — walk once with :pf debug on)");
      }

      try {
         Path file = PathfinderLog.logFile().getParent().resolve("stuck-" + System.currentTimeMillis() + ".txt");
         Files.createDirectories(file.getParent());
         StringBuilder body = new StringBuilder();
         body.append(diag).append('\n');
         if (occ != null) {
            Coord focus = me != null && me.rc != null ? occ.cellOf(me.rc) : occ.start;
            body.append(occ.ascii(focus, 12)).append('\n');
         }

         JSONObject last = PathfinderLog.last();
         if (last != null) {
            body.append(last.toString(2)).append('\n');
         }

         JSONObject graph = PathfinderLog.lastGraph();
         if (graph != null) {
            body.append(graph.toString(2)).append('\n');
         }

         PrintWriter w = new PrintWriter(Files.newBufferedWriter(file));

         try {
            w.write(body.toString());
         } catch (Throwable var13) {
            try {
               w.close();
            } catch (Throwable var12) {
               var13.addSuppressed(var12);
            }

            throw var13;
         }

         w.close();
         out.println("wrote " + file);
         if (gui != null) {
            gui.msg("PF stuck dump: " + file, MsgType.INFO);
         }
      } catch (Exception var14) {
         out.println("could not write stuck file: " + var14.getMessage());
      }
   }

   private static void polyline(GOut g, MapView mv, List<Coord2d> path, Color color, int width, int dot) {
      if (mv != null && path != null && !path.isEmpty()) {
         g.chcolor(color);
         Coord prev = null;

         for (Coord2d wp : path) {
            Coord c = screen(mv, wp);
            if (c == null) {
               prev = null;
            } else {
               g.fellipse(c, new Coord(dot, dot));
               if (prev != null) {
                  g.line(prev, c, (double)width);
               }

               prev = c;
            }
         }

         g.chcolor();
      }
   }

   private static void marker(GOut g, MapView mv, Coord2d world, Color color, int r, String label) {
      Coord dest = screen(mv, world);
      if (dest != null) {
         g.chcolor(color);
         g.line(dest.add(-r, -r), dest.add(r, r), 2.0);
         g.line(dest.add(-r, r), dest.add(r, -r), 2.0);
         g.fellipse(dest, new Coord(3, 3));
         if (label != null) {
            g.atext(label, dest.add(6, -4), 0.0, 0.0);
         }

         g.chcolor();
      }
   }

   private static void quad(GOut g, MapView mv, PathfinderLog.Occupancy occ, int x, int y, Color color) {
      Coord a = screen(mv, occ.corner(x, y));
      Coord b = screen(mv, occ.corner(x + 1, y));
      Coord c = screen(mv, occ.corner(x + 1, y + 1));
      Coord d = screen(mv, occ.corner(x, y + 1));
      if (a != null || b != null || c != null || d != null) {
         g.chcolor(color);
         if (a != null && b != null) {
            g.line(a, b, 1.0);
         }

         if (b != null && c != null) {
            g.line(b, c, 1.0);
         }

         if (c != null && d != null) {
            g.line(c, d, 1.0);
         }

         if (d != null && a != null) {
            g.line(d, a, 1.0);
         }

         g.chcolor();
      }
   }

   static Coord screen(MapView mv, Coord2d world) {
      if (mv != null && world != null) {
         Coord3f s;
         try {
            s = mv.screenxf(mv.glob.map.getzp(world));
         } catch (RuntimeException var4) {
            s = mv.screenxf(world);
         }

         if (s == null) {
            return null;
         } else {
            Coord c = Coord.of(Math.round(s.x), Math.round(s.y));
            return c.x >= -40 && c.y >= -40 && c.x <= mv.sz.x + 40 && c.y <= mv.sz.y + 40 ? c : null;
         }
      } else {
         return null;
      }
   }

   public void replay(JSONObject body, PrintStream out) {
      JSONArray recent = body == null ? null : body.optJSONArray("recent");
      if (recent != null && recent.length() != 0) {
         out.println("log_file: " + body.optString("log_file", ""));

         for (int i = 0; i < recent.length(); i++) {
            JSONObject o = recent.getJSONObject(i);
            out.printf(
               "%s%s  %s  clip=%s  wp=%d  nodes=%d  r=%.1f%n",
               o.optBoolean("probe") ? "probe " : "",
               o.optString("reason"),
               o.optString("target"),
               o.optString("clip", ""),
               o.optInt("waypoint_count"),
               o.optInt("expanded"),
               o.optDouble("agent_radius")
            );
         }
      } else {
         out.println("no pathfinder plans captured");
      }
   }

   public Map<String, Command> extraVerbs() {
      Map<String, Command> verbs = new LinkedHashMap<>();
      verbs.put("log", (cons, args) -> printLog(cons.out));
      verbs.put("stuck", (cons, args) -> dumpStuck(null, cons.out));
      return verbs;
   }

   static void printLog(PrintWriter out) {
      out.println("PF log file: " + PathfinderLog.logFile());
      List<JSONObject> recent = PathfinderLog.recent();
      if (recent.isEmpty()) {
         out.println("(empty — run :pf gob or :pf catalog first)");
      } else {
         int from = Math.max(0, recent.size() - 8);

         for (int i = from; i < recent.size(); i++) {
            JSONObject o = recent.get(i);
            if (o.has("kind") && !o.has("waypoint_count")) {
               // SurfaceController exec entries: these carry the replan/stream
               // reasons (corner-clip, blocked segment, recovery) needed in a
               // stuck dump.
               out.printf(
                  "  exec %s  reason=%s  outcome=%s%s%n",
                  o.optString("kind"),
                  o.optString("reason"),
                  o.optString("outcome", "-"),
                  o.optBoolean("blacklist") ? "  blacklist" : ""
               );
               continue;
            }
            out.printf(
               "  %s%s  %s  clip=%s  wp=%d  nodes=%d  r=%.1f dil=%d%s%n",
               o.optBoolean("probe") ? "probe " : "",
               o.optString("reason"),
               o.optString("target"),
               o.optString("clip", "-"),
               o.optInt("waypoint_count"),
               o.optInt("expanded"),
               o.optDouble("agent_radius"),
               o.optInt("dilation"),
               o.optBoolean("complete") ? "" : " partial"
            );
         }
      }
   }

   static {
      DevFeature.register(new PathfinderDebug());
   }
}
