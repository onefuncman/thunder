package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Utils;
import haven.nav.GraphNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * User-authored critical routes saved from the in-game editor.
 * Stands are stored in map-file space (same as minimap markers), not
 * session {@code gob.rc}, so they survive relog. Replay must not walk a
 * basement stand after the player has moved to the house, cave, or surface.
 */
final class CriticalRouteBook {
   static Path overrideDir;

   final String id;
   final List<Leg> legs;

   CriticalRouteBook(String id) {
      this.id = sanitize(id);
      this.legs = new ArrayList<Leg>();
   }

   static String sanitize(String raw) {
      if (raw == null) {
         return "untitled";
      }
      String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
      s = s.replaceAll("^_+", "").replaceAll("_+$", "");
      if (s.isEmpty()) {
         return "untitled";
      }
      if (s.length() > 48) {
         s = s.substring(0, 48);
      }
      return s;
   }

   static Path dir() {
      if (overrideDir != null) {
         return overrideDir;
      }
      Path cwd = Utils.path(System.getProperty("user.dir", "."));
      Path underCwd = cwd.resolve("dev-snapshots").resolve("pf").resolve("routes");
      if (Files.isDirectory(underCwd) || cwd.getFileName() != null && "bin".equals(cwd.getFileName().toString())) {
         return underCwd;
      }
      return cwd.resolve("dev-snapshots").resolve("pf").resolve("routes");
   }

   static Path currentFile() {
      return dir().resolve("current.txt");
   }

   static void setCurrent(String id) throws IOException {
      Files.createDirectories(dir());
      Files.write(currentFile(), (sanitize(id) + "\n").getBytes(StandardCharsets.UTF_8));
   }

   static String currentId() {
      Path f = currentFile();
      if (!Files.isRegularFile(f)) {
         return "";
      }
      try {
         List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
         return lines.isEmpty() ? "" : sanitize(lines.get(0));
      } catch (IOException e) {
         return "";
      }
   }

   static List<String> listIds() {
      Path d = dir();
      if (!Files.isDirectory(d)) {
         return Collections.emptyList();
      }
      try {
         List<String> out = new ArrayList<String>();
         for (Path p : Files.newDirectoryStream(d, "*.json")) {
            String n = p.getFileName().toString();
            out.add(n.substring(0, n.length() - 5));
         }
         Collections.sort(out);
         return out;
      } catch (IOException e) {
         return Collections.emptyList();
      }
   }

   static CriticalRouteBook load(String id) throws IOException {
      Path f = dir().resolve(sanitize(id) + ".json");
      if (!Files.isRegularFile(f)) {
         throw new IOException("no saved route " + id);
      }
      return fromJson(new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
   }

   void save() throws IOException {
      Files.createDirectories(dir());
      Files.write(dir().resolve(this.id + ".json"), toJson().toString(2).getBytes(StandardCharsets.UTF_8));
      setCurrent(this.id);
   }

   JSONObject toJson() {
      JSONObject o = new JSONObject();
      o.put("id", this.id);
      JSONArray arr = new JSONArray();
      for (Leg leg : this.legs) {
         arr.put(leg.toJson());
      }
      o.put("legs", arr);
      return o;
   }

   static CriticalRouteBook fromJson(JSONObject o) {
      CriticalRouteBook b = new CriticalRouteBook(o.optString("id", "untitled"));
      JSONArray arr = o.optJSONArray("legs");
      if (arr != null) {
         for (int i = 0; i < arr.length(); i++) {
            Leg leg = Leg.fromJson(arr.getJSONObject(i));
            if (leg != null) {
               b.legs.add(leg);
            }
         }
      }
      return b;
   }

   static String segmentId(GameUI gui) {
      GraphNode n = WorldGraphAdapter.current(gui);
      if (n == null || "unknown".equals(n.segmentId)) {
         return "";
      }
      return n.segmentId;
   }

   static Coord sessTc(GameUI gui) {
      if (gui == null || gui.mapfile == null || gui.mapfile.view == null || gui.mapfile.view.sessloc == null) {
         return null;
      }
      return gui.mapfile.view.sessloc.tc;
   }

   /** Session world → map-file world ({@code rc + tc * tilesz}), same as GobMarker. */
   static Coord2d toMap(Coord2d sessionRc, Coord tc) {
      if (sessionRc == null) {
         return null;
      }
      if (tc == null) {
         return sessionRc;
      }
      return sessionRc.add(tc.mul(MCache.tilesz));
   }

   static Coord2d toSession(Coord2d mapRc, Coord tc) {
      if (mapRc == null) {
         return null;
      }
      if (tc == null) {
         return mapRc;
      }
      return mapRc.sub(tc.mul(MCache.tilesz));
   }

   static Coord mapTile(Coord2d sessionRc, Coord tc) {
      if (sessionRc == null || tc == null) {
         return null;
      }
      return sessionRc.floor(MCache.tilesz).add(tc);
   }

   static Coord2d sessionWorld(GameUI gui, Leg leg) {
      if (leg == null || leg.world == null) {
         return null;
      }
      if (!leg.map) {
         return leg.world;
      }
      Coord tc = sessTc(gui);
      if (tc == null) {
         return null;
      }
      return toSession(leg.world, tc);
   }

   static Gob resolveGob(GameUI gui, long hintId, String resid, Coord2d sessionExpect) {
      if (gui == null || gui.map == null || gui.ui == null || gui.map.glob == null) {
         return null;
      }
      synchronized (gui.ui) {
         if (hintId >= 0L) {
            Gob byId = gui.map.glob.oc.getgob(hintId);
            if (byId != null && residMatches(gobResid(byId), resid)) {
               return byId;
            }
         }
         if (resid == null || resid.isEmpty()) {
            return null;
         }
         Gob best = null;
         double bestD = Double.POSITIVE_INFINITY;
         int n = 0;
         for (Gob g : gui.map.glob.oc) {
            if (g == null || g.rc == null || g.id < 0L) {
               continue;
            }
            if (!residMatches(gobResid(g), resid)) {
               continue;
            }
            n++;
            double d = sessionExpect == null ? 0.0 : g.rc.dist(sessionExpect);
            if (d < bestD) {
               bestD = d;
               best = g;
            }
         }
         if (best == null) {
            return null;
         }
         if (n > 1 && sessionExpect != null && bestD > 66.0) {
            return null;
         }
         return best;
      }
   }

   static boolean residMatches(String live, String saved) {
      String a = PrototypePathfinder.baseResid(live);
      String b = PrototypePathfinder.baseResid(saved);
      return a != null && b != null && !a.isEmpty() && a.equals(b);
   }

   void addStand(GameUI gui, Coord2d sessionRc) {
      if (sessionRc == null) {
         return;
      }
      Coord tc = sessTc(gui);
      String seg = segmentId(gui);
      if (tc == null) {
         this.legs.add(new Leg("stand", sessionRc, -1L, "", "stand", seg, false, null));
         return;
      }
      this.legs.add(new Leg("stand", toMap(sessionRc, tc), -1L, "", "stand", seg, true, mapTile(sessionRc, tc)));
   }

   void addStand(Coord2d world, String seg) {
      if (world == null) {
         return;
      }
      this.legs.add(new Leg("stand", world, -1L, "", "stand", seg == null ? "" : seg, true, world.floor(MCache.tilesz)));
   }

   static boolean isApproach(Leg leg) {
      return leg != null && (ApproachOnly.KIND.equals(leg.kind) || ApproachOnly.ROLE.equals(leg.role));
   }

   static boolean isGround(Leg leg) {
      return leg != null && "stand".equals(leg.kind);
   }

   void addApproach(GameUI gui, Gob gob) {
      if (gob == null || gob.rc == null) {
         return;
      }
      String resid = gobResid(gob);
      Coord2d at = gob.rc;
      Coord tc = sessTc(gui);
      String seg = segmentId(gui);
      if (tc == null) {
         this.legs.add(new Leg(ApproachOnly.KIND, at, gob.id, resid, ApproachOnly.ROLE, seg, false, null));
         return;
      }
      this.legs.add(new Leg(ApproachOnly.KIND, toMap(at, tc), gob.id, resid, ApproachOnly.ROLE, seg, true, mapTile(at, tc)));
   }

   void addGob(GameUI gui, Gob gob) {
      if (gob == null || gob.rc == null) {
         return;
      }
      gob = BuildingDoor.preferDoorGob(gui, gob);
      if (gob == null || gob.rc == null) {
         return;
      }
      String resid = gobResid(gob);
      haven.nav.GraphEdge.Kind k = TransitionAdapter.kind(resid);
      String role;
      if (k != null) {
         role = k.name().toLowerCase(Locale.ROOT);
      } else if (BuildingDoor.isHouseHull(resid)) {
         role = "door_gate";
      } else {
         role = "gob";
      }
      Coord2d at = gob.rc;
      if (BuildingDoor.usesDoorTarget(resid)) {
         Coord2d near = gui != null && gui.map != null && gui.map.player() != null ? gui.map.player().rc : gob.rc;
         BuildingDoor.Target door = BuildingDoor.target(resid, gob.rc, gob.a, gob.id, near);
         if (door != null && door.origin != null) {
            at = door.origin;
         }
      }
      Coord tc = sessTc(gui);
      String seg = segmentId(gui);
      if (tc == null) {
         this.legs.add(new Leg("gob", at, gob.id, resid, role, seg, false, null));
         return;
      }
      this.legs.add(new Leg("gob", toMap(at, tc), gob.id, resid, role, seg, true, mapTile(at, tc)));
   }

   static String gobResid(Gob gob) {
      try {
         String r = gob == null ? null : gob.resid();
         return r == null ? "" : r;
      } catch (RuntimeException ignored) {
         return "";
      }
   }

   void undo() {
      if (!this.legs.isEmpty()) {
         this.legs.remove(this.legs.size() - 1);
      }
   }

   Coord2d originalDest() {
      for (int i = this.legs.size() - 1; i >= 0; i--) {
         Coord2d w = this.legs.get(i).world;
         if (w != null) {
            return w;
         }
      }
      return null;
   }

   static final class Leg {
      final String kind;
      final Coord2d world;
      final long gobId;
      final String resid;
      final String role;
      final String seg;
      final boolean map;
      final Coord tile;

      Leg(String kind, Coord2d world, long gobId, String resid, String role) {
         this(kind, world, gobId, resid, role, "", false, null);
      }

      Leg(String kind, Coord2d world, long gobId, String resid, String role, String seg) {
         this(kind, world, gobId, resid, role, seg, false, null);
      }

      Leg(String kind, Coord2d world, long gobId, String resid, String role, String seg, boolean map, Coord tile) {
         this.kind = kind;
         this.world = world;
         this.gobId = gobId;
         this.resid = resid == null ? "" : resid;
         this.role = role == null ? "stand" : role;
         this.seg = seg == null ? "" : seg;
         this.map = map;
         this.tile = tile;
      }

      boolean onFloor(String liveSeg) {
         if (this.seg.isEmpty() || liveSeg == null || liveSeg.isEmpty()) {
            return false;
         }
         return this.seg.equals(liveSeg);
      }

      String label() {
         String floor = this.seg.isEmpty() ? "no floor" : "floor " + this.seg;
         if (ApproachOnly.KIND.equals(this.kind) || ApproachOnly.ROLE.equals(this.role)) {
            String res = this.resid.isEmpty() ? "" : " " + PrototypePathfinder.baseResid(this.resid);
            return ApproachOnly.ROLE + res + " · " + floor;
         }
         if ("gob".equals(this.kind)) {
            String res = this.resid.isEmpty() ? "" : " " + PrototypePathfinder.baseResid(this.resid);
            return this.role + res + " · " + floor;
         }
         if (this.tile != null) {
            return "stand tile " + this.tile.x + "," + this.tile.y + " · " + floor;
         }
         return (this.world == null ? "stand" : "stand " + PfTestHarness.pt(this.world)) + " · " + floor;
      }

      JSONObject toJson() {
         JSONObject o = new JSONObject();
         o.put("kind", this.kind);
         o.put("role", this.role);
         o.put("gob_id", this.gobId);
         o.put("resid", this.resid);
         o.put("seg", this.seg);
         o.put("space", this.map ? "map" : "session");
         if (this.world != null) {
            o.put("x", this.world.x);
            o.put("y", this.world.y);
         }
         if (this.tile != null) {
            o.put("tx", this.tile.x);
            o.put("ty", this.tile.y);
         }
         return o;
      }

      static Leg fromJson(JSONObject o) {
         if (o == null) {
            return null;
         }
         Coord2d w = o.has("x") && o.has("y") ? Coord2d.of(o.getDouble("x"), o.getDouble("y")) : null;
         boolean map = "map".equals(o.optString("space")) || o.has("tx");
         Coord tile = null;
         if (o.has("tx") && o.has("ty")) {
            tile = Coord.of(o.getInt("tx"), o.getInt("ty"));
         } else if (map && w != null) {
            tile = w.floor(MCache.tilesz);
         }
         String kind = o.optString("kind", "stand");
         String role = o.optString("role", "stand");
         if (ApproachOnly.KIND.equals(kind) || ApproachOnly.ROLE.equals(role)) {
            kind = ApproachOnly.KIND;
            role = ApproachOnly.ROLE;
         }
         return new Leg(
            kind,
            w,
            o.optLong("gob_id", -1L),
            o.optString("resid"),
            role,
            o.optString("seg", ""),
            map,
            tile
         );
      }
   }
}
