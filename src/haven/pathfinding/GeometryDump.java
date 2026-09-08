package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Structured dump of the live indoor-stairs / study-desk collision scene.
 * Written on approach selection so Walk fwd leaves an on-disk record.
 */
final class GeometryDump {
   private GeometryDump() {
   }

   static JSONObject dump(
      GameUI gui,
      PrototypePathfinder.Scene scene,
      PrototypePathfinder.GobGeom target,
      InteractionGoals.Result pose
   ) {
      JSONObject o = new JSONObject();
      o.put("t", System.currentTimeMillis());
      if (scene != null && scene.player != null) {
         o.put("player", pt(scene.player));
      }
      o.put("player_body", polys(scene == null ? null : scene.playerBody));
      o.put("player_body_size", size(scene == null ? null : scene.playerBody));
      List<PrototypePathfinder.GobGeom> gobs = collect(gui, scene, target);
      JSONArray desks = new JSONArray();
      JSONArray stairs = new JSONArray();
      for (int i = 0; i < gobs.size(); i++) {
         PrototypePathfinder.GobGeom g = gobs.get(i);
         if (g == null || g.resid == null) {
            continue;
         }
         if (isStudyDesk(g.resid)) {
            desks.put(deskEntry(g));
         }
         if (isStairs(g.resid) || (target != null && g.id == target.id)) {
            stairs.put(stairsEntry(g));
         }
      }
      o.put("study_desks", desks);
      o.put("stairs", stairs);
      if (target != null) {
         o.put("target", stairsEntry(target));
      }
      o.put("solids", nearbySolids(scene, target));
      o.put("solid_count", scene == null || scene.solids == null ? 0 : scene.solids.size());
      o.put("solid_cells", solidCells(scene, target));
      if (pose != null) {
         o.put("reason", pose.reason);
         o.put("dominant_reject", pose.ok() ? JSONObject.NULL : pose.dominantReject());
         o.put("reject_counts", counts(pose));
         o.put("candidates", candidates(pose, scene == null ? null : scene.occupancy));
         if (pose.selected != null && pose.selected.world != null) {
            o.put("selected", pt(pose.selected.world));
            Coord cell = scene != null && scene.occupancy != null ? scene.occupancy.cellOf(pose.selected.world) : null;
            if (cell != null) {
               o.put("selected_cell", new JSONArray().put(cell.x).put(cell.y));
               o.put("selected_cell_occ", PathfinderLog.Occupancy.name(scene.occupancy.at(cell.x, cell.y)));
            }
         }
      }
      PathfinderLog.recordGeometryDump(o);
      return o;
   }

   static boolean isStudyDesk(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && (name.equals("gfx/terobjs/studydesk") || name.equals("gfx/terobjs/studydesk-big"));
   }

   static boolean isStairs(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && (name.contains("downstairs") || name.contains("upstairs") || name.contains("cellarstairs"));
   }

   private static List<PrototypePathfinder.GobGeom> collect(
      GameUI gui, PrototypePathfinder.Scene scene, PrototypePathfinder.GobGeom target
   ) {
      List<PrototypePathfinder.GobGeom> out = new ArrayList<PrototypePathfinder.GobGeom>();
      if (gui != null && gui.ui != null && gui.ui.sess != null && gui.map != null) {
         Gob player = gui.map.player();
         synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
               if (gob == null || gob == player || gob.virtual || gob.id < 0L || gob.rc == null) {
                  continue;
               }
               if (player != null && player.rc != null && gob.rc.dist(player.rc) > 220.0) {
                  continue;
               }
               try {
                  String resid = gob.resid() == null ? "" : gob.resid();
                  if (isStudyDesk(resid) || isStairs(resid) || (target != null && gob.id == target.id)) {
                     out.add(PrototypePathfinder.gobGeom(player, gob));
                  }
               } catch (Loading ignored) {
               }
            }
         }
      } else if (scene != null) {
         out.addAll(scene.gobs);
      }
      return out;
   }

   private static JSONObject deskEntry(PrototypePathfinder.GobGeom g) {
      JSONObject o = gobBase(g);
      o.put("obst", polys(g.obst));
      o.put("placement", polys(g.placement));
      o.put("movement", polys(g.movement));
      o.put("fallback", g.fallback == null ? JSONObject.NULL : poly(g.fallback));
      o.put("collision_source", g.collisionSource == null ? "" : g.collisionSource);
      o.put("collision_reason", g.collisionReason == null ? "" : g.collisionReason);
      o.put("obst_meaningful", CollisionGeom.meaningful(g.obst));
      o.put("obst_size", size(g.obst));
      o.put("placement_size", size(g.placement));
      o.put("movement_size", size(g.movement));
      o.put("fallback_size", g.fallback == null ? size((List<Coord2d[]>) null) : CollisionGeom.size(g.fallback));
      o.put("collision_size", size(g.collision));
      return o;
   }

   private static JSONObject stairsEntry(PrototypePathfinder.GobGeom g) {
      JSONObject o = gobBase(g);
      o.put("obst", polys(g.obst));
      o.put("movement", polys(g.movement));
      o.put("collision_source", g.collisionSource == null ? "" : g.collisionSource);
      o.put("size", size(g.collision.isEmpty() ? g.movement : g.collision));
      return o;
   }

   private static JSONObject gobBase(PrototypePathfinder.GobGeom g) {
      JSONObject o = new JSONObject();
      o.put("id", g.id);
      o.put("resid", g.resid == null ? "" : g.resid);
      o.put("rc", g.rc == null ? JSONObject.NULL : pt(g.rc));
      o.put("a", g.a);
      return o;
   }

   private static JSONArray solidCells(PrototypePathfinder.Scene scene, PrototypePathfinder.GobGeom target) {
      JSONArray out = new JSONArray();
      if (scene == null || scene.occupancy == null) {
         return out;
      }
      OccupancyGrid occ = scene.occupancy;
      Coord focus = target != null && target.rc != null ? occ.cellOf(target.rc) : scene.playerCell;
      if (focus == null) {
         focus = Coord.of(occ.w / 2, occ.h / 2);
      }
      int r = 18;
      for (int y = Math.max(0, focus.y - r); y <= Math.min(occ.h - 1, focus.y + r); y++) {
         for (int x = Math.max(0, focus.x - r); x <= Math.min(occ.w - 1, focus.x + r); x++) {
            if (occ.at(x, y) == OccupancyGrid.SOLID) {
               Coord2d w = occ.world(x, y);
               out.put(new JSONArray().put(x).put(y).put(w.x).put(w.y));
            }
         }
      }
      return out;
   }

   private static JSONArray candidates(InteractionGoals.Result pose, OccupancyGrid occ) {
      JSONArray out = new JSONArray();
      if (pose == null) {
         return out;
      }
      int n = Math.min(pose.considered.size(), 80);
      for (int i = 0; i < n; i++) {
         InteractionGoals.Candidate c = pose.considered.get(i);
         JSONObject row = new JSONObject();
         row.put("world", c.world == null ? JSONObject.NULL : pt(c.world));
         row.put("cell", c.cell == null ? JSONObject.NULL : new JSONArray().put(c.cell.x).put(c.cell.y));
         row.put("reject", c.reject == null ? JSONObject.NULL : c.reject);
         if (occ != null && c.cell != null && c.cell.x >= 0 && c.cell.y >= 0 && c.cell.x < occ.w && c.cell.y < occ.h) {
            row.put("occ", PathfinderLog.Occupancy.name(occ.at(c.cell.x, c.cell.y)));
         }
         out.put(row);
      }
      return out;
   }

   private static JSONObject counts(InteractionGoals.Result pose) {
      JSONObject o = new JSONObject();
      if (pose == null || pose.rejectCounts == null) {
         return o;
      }
      for (String k : pose.rejectCounts.keySet()) {
         o.put(k, pose.rejectCounts.get(k).intValue());
      }
      return o;
   }

   private static JSONArray pt(Coord2d p) {
      return new JSONArray().put(p.x).put(p.y);
   }

   private static JSONArray poly(Coord2d[] p) {
      JSONArray a = new JSONArray();
      if (p == null) {
         return a;
      }
      for (int i = 0; i < p.length; i++) {
         if (p[i] != null) {
            a.put(pt(p[i]));
         }
      }
      return a;
   }

   private static JSONArray nearbySolids(PrototypePathfinder.Scene scene, PrototypePathfinder.GobGeom target) {
      JSONArray a = new JSONArray();
      if (scene == null || scene.solids == null) {
         return a;
      }
      Coord2d focus = scene.player;
      if (target != null && target.rc != null) {
         focus = target.rc;
      }
      int n = 0;
      for (int i = 0; i < scene.solids.size() && n < 64; i++) {
         Coord2d[] poly = scene.solids.get(i);
         if (poly == null || poly.length < 2) {
            continue;
         }
         if (focus != null && !nearFocus(poly, focus, 80.0)) {
            continue;
         }
         a.put(poly(poly));
         n++;
      }
      return a;
   }

   private static boolean nearFocus(Coord2d[] poly, Coord2d focus, double reach) {
      for (int i = 0; i < poly.length; i++) {
         if (poly[i] != null && poly[i].dist(focus) <= reach) {
            return true;
         }
      }
      return false;
   }

   private static JSONArray polys(List<Coord2d[]> list) {
      JSONArray a = new JSONArray();
      if (list == null) {
         return a;
      }
      for (int i = 0; i < list.size(); i++) {
         a.put(poly(list.get(i)));
      }
      return a;
   }

   private static JSONArray size(List<Coord2d[]> list) {
      double[] s = CollisionGeom.size(list);
      return new JSONArray().put(s[0]).put(s[1]);
   }
}
