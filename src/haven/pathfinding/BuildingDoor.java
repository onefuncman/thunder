package haven.pathfinding;

import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One stable door target per house. Recording, overlay, planning, click, and
 * revalidation all use {@link Target} — not the hull {@code rc}.
 */
public final class BuildingDoor {
   static final Coord2d DEFAULT_HALF = Coord2d.of(6.0, 6.0);
   /** Stand-off kept in front of a house doorway when walking to a house leg. */
   public static final double DOOR_APPROACH_U = 10.0;
   private static final Map<String, Off[]> OFFSETS = new LinkedHashMap<String, Off[]>();

   static {
      OFFSETS.put("gfx/terobjs/arch/logcabin", new Off[]{new Off(22.0, 0.0, 16)});
      OFFSETS.put("gfx/terobjs/arch/timberhouse", new Off[]{new Off(33.0, 0.0, 16)});
      OFFSETS.put("gfx/terobjs/arch/stonestead", new Off[]{new Off(44.0, 0.0, 16)});
      OFFSETS.put("gfx/terobjs/arch/stonemansion", new Off[]{new Off(48.0, 3.5, 16)});
      OFFSETS.put(
         "gfx/terobjs/arch/greathall",
         new Off[]{new Off(77.0, -28.0, 18), new Off(77.0, 0.0, 17), new Off(77.0, 28.0, 16)}
      );
      OFFSETS.put(
         "gfx/terobjs/arch/greathall-door",
         new Off[]{new Off(0.0, -30.0, 18), new Off(0.0, 0.0, 17), new Off(0.0, 30.0, 16)}
      );
      OFFSETS.put("gfx/terobjs/arch/stonetower", new Off[]{new Off(36.0, 0.0, 16)});
      OFFSETS.put("gfx/terobjs/arch/windmill", new Off[]{new Off(0.0, 28.0, 16)});
      OFFSETS.put("gfx/terobjs/arch/greenhouse", new Off[]{new Off(22.0, 0.0, 16)});
      OFFSETS.put("gfx/terobjs/arch/stonehut", new Off[]{new Off(20.0, 0.0, 16)});
   }

   private BuildingDoor() {
   }

   static final class Off {
      final Coord2d off;
      final int mesh;

      Off(double x, double y, int mesh) {
         this.off = Coord2d.of(x, y);
         this.mesh = mesh;
      }
   }

   /** Compact doorway used for planning, overlay, click, and revalidation. */
   public static final class Target {
      public final long gobId;
      public final String resid;
      public final Coord2d origin;
      public final Coord2d half;
      public final Coord2d[] polygon;
      public final int mesh;

      Target(long gobId, String resid, Coord2d origin, Coord2d half, Coord2d[] polygon, int mesh) {
         this.gobId = gobId;
         this.resid = resid == null ? "" : resid;
         this.origin = origin;
         this.half = half == null ? DEFAULT_HALF : half;
         this.polygon = polygon;
         this.mesh = mesh;
      }
   }

   public static boolean usesDoorTarget(String resid) {
      if (isHouseHull(resid)) {
         return true;
      }
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && OFFSETS.containsKey(name);
   }

   public static Target target(String resid, Coord2d rc, double a, long id, Coord2d near) {
      if (!usesDoorTarget(resid) || rc == null) {
         return null;
      }
      Off[] offs = OFFSETS.get(PrototypePathfinder.baseResid(resid));
      Coord2d origin = rc;
      int mesh = -1;
      if (offs != null && offs.length > 0) {
         Coord2d hint = near != null ? near : rc;
         double bestD = Double.POSITIVE_INFINITY;
         for (int i = 0; i < offs.length; i++) {
            Coord2d world = rc.add(rotate(offs[i].off, a));
            double d = world.dist(hint);
            if (d < bestD) {
               bestD = d;
               origin = world;
               mesh = offs[i].mesh;
            }
         }
      }
      Coord2d half = interactHalf(resid);
      return new Target(id, resid, origin, half, doorBox(origin, half), mesh);
   }

   public static Target target(PrototypePathfinder.GobGeom g, Coord2d near) {
      return g == null ? null : target(g.resid, g.rc, g.a, g.id, near);
   }

   /** Live door origin for the same doorway {@code spec} already planned. */
   public static Coord2d liveOrigin(PrototypePathfinder.GobGeom live, haven.nav.InteractionSpec spec) {
      if (live == null || live.rc == null) {
         return null;
      }
      Coord2d near = spec != null && spec.origin != null ? spec.origin : live.rc;
      Target t = target(live.resid, live.rc, live.a, live.id, near);
      return t != null ? t.origin : live.rc;
   }

   public static boolean isHouseHull(String resid) {
      if (!PrototypePathfinder.solidFootprint(resid)) {
         return false;
      }
      if (TransitionApproachSelector.doorGateKind(resid) == TransitionApproachSelector.DoorGateKind.DOOR) {
         return false;
      }
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && !name.endsWith("-door");
   }

   public static boolean isDoorGob(String resid) {
      return TransitionApproachSelector.doorGateKind(resid) == TransitionApproachSelector.DoorGateKind.DOOR;
   }

   public static Coord2d nearestDoorWorld(String resid, Coord2d rc, double a, Coord2d near) {
      Target t = target(resid, rc, a, -1L, near);
      if (t != null) {
         return t.origin;
      }
      return isHouseHull(resid) ? rc : null;
   }

   public static Coord2d interactHalf(String resid) {
      if ("gfx/terobjs/arch/greathall-door".equals(PrototypePathfinder.baseResid(resid))) {
         return Coord2d.of(8.0, 8.0);
      }
      return DEFAULT_HALF;
   }

   public static Coord2d[] doorBox(Coord2d origin, Coord2d half) {
      if (origin == null) {
         return null;
      }
      Coord2d h = half == null ? DEFAULT_HALF : half;
      return new Coord2d[]{
         Coord2d.of(origin.x - h.x, origin.y - h.y),
         Coord2d.of(origin.x + h.x, origin.y - h.y),
         Coord2d.of(origin.x + h.x, origin.y + h.y),
         Coord2d.of(origin.x - h.x, origin.y + h.y)
      };
   }

   public static Gob preferDoorGob(GameUI gui, Gob gob) {
      if (gob == null || !isHouseHull(CriticalRouteBook.gobResid(gob))) {
         return gob;
      }
      Gob door = nearestDoorGob(gui, gob);
      return door != null ? door : gob;
   }

   public static Gob nearestDoorGob(GameUI gui, Gob hull) {
      if (gui == null || gui.map == null || gui.map.glob == null || gui.ui == null || hull == null || hull.rc == null) {
         return null;
      }
      Coord2d near = gui.map.player() == null ? hull.rc : gui.map.player().rc;
      double max = searchRadius(CriticalRouteBook.gobResid(hull));
      Gob best = null;
      double bestD = Double.POSITIVE_INFINITY;
      synchronized (gui.ui) {
         for (Gob g : gui.map.glob.oc) {
            if (g == null || g.rc == null || g.id == hull.id) {
               continue;
            }
            String resid = CriticalRouteBook.gobResid(g);
            if (!isDoorGob(resid)) {
               continue;
            }
            if (!sameBuildingFamily(CriticalRouteBook.gobResid(hull), resid) && g.rc.dist(hull.rc) > max) {
               continue;
            }
            double d = g.rc.dist(near);
            if (d < bestD && g.rc.dist(hull.rc) <= max) {
               bestD = d;
               best = g;
            }
         }
      }
      return best;
   }

   public static PrototypePathfinder.GobGeom preferDoor(PrototypePathfinder.Scene scene, PrototypePathfinder.GobGeom gob) {
      if (scene == null || gob == null || !isHouseHull(gob.resid)) {
         return gob;
      }
      PrototypePathfinder.GobGeom door = nearestDoor(scene, gob);
      return door != null ? door : gob;
   }

   public static PrototypePathfinder.GobGeom nearestDoor(PrototypePathfinder.Scene scene, PrototypePathfinder.GobGeom hull) {
      if (scene == null || scene.gobs == null || hull == null || hull.rc == null) {
         return null;
      }
      Coord2d near = scene.player != null ? scene.player : hull.rc;
      double max = searchRadius(hull.resid);
      PrototypePathfinder.GobGeom best = null;
      double bestD = Double.POSITIVE_INFINITY;
      for (int i = 0; i < scene.gobs.size(); i++) {
         PrototypePathfinder.GobGeom g = scene.gobs.get(i);
         if (g == null || g.rc == null || g.id == hull.id || !isDoorGob(g.resid)) {
            continue;
         }
         if (!sameBuildingFamily(hull.resid, g.resid) && g.rc.dist(hull.rc) > max) {
            continue;
         }
         double d = g.rc.dist(near);
         if (d < bestD && g.rc.dist(hull.rc) <= max) {
            bestD = d;
            best = g;
         }
      }
      return best;
   }

   /**
    * Resolves the door gob for a house hull, falling back from the local scene
    * window (~220u) to the full object cache when the door is not yet observed.
    * A door gob carries the exact doorway position and hitbox; the hull centre
    * and hardcoded offsets are never as accurate. Returns the door gob when
    * found, otherwise the original gob.
    */
   public static PrototypePathfinder.GobGeom resolveDoor(GameUI gui, PrototypePathfinder.Scene scene, PrototypePathfinder.GobGeom gob) {
      if (scene == null || gob == null || !isHouseHull(gob.resid)) {
         return gob;
      }
      PrototypePathfinder.GobGeom door = nearestDoor(scene, gob);
      if (door != null) {
         return door;
      }
      if (gui == null || gui.map == null || gui.map.glob == null || gui.ui == null) {
         return gob;
      }
      Gob hullLive;
      synchronized (gui.ui) {
         hullLive = gui.map.glob.oc.getgob(gob.id);
      }
      if (hullLive != null) {
         Gob doorLive = nearestDoorGob(gui, hullLive);
         if (doorLive != null) {
            PrototypePathfinder.GobGeom resolved = PrototypePathfinder.gobGeom(gui, doorLive.id);
            if (resolved != null && resolved.rc != null && isDoorGob(resolved.resid)) {
               return resolved;
            }
         }
      }
      return gob;
   }

   public static Coord2d markerWorld(GameUI gui, CriticalRouteBook.Leg leg, Coord2d stored) {
      if (stored == null || leg == null || !isHouseHull(leg.resid)) {
         return stored;
      }
      Gob hull = CriticalRouteBook.resolveGob(gui, leg.gobId, leg.resid, stored);
      if (hull == null) {
         return stored;
      }
      Gob door = nearestDoorGob(gui, hull);
      if (door != null && door.rc != null) {
         if (stored.dist(door.rc) <= SurfaceStream.LAST_HOP) {
            return stored;
         }
         return door.rc;
      }
      Target t = target(CriticalRouteBook.gobResid(hull), hull.rc, hull.a, hull.id, stored);
      if (t == null || t.origin == null) {
         return stored;
      }
      if (stored.dist(t.origin) <= SurfaceStream.LAST_HOP) {
         return stored;
      }
      return t.origin;
   }

   /**
    * Walk-to point for a house leg: the doorway centre pushed outward by
    * {@link #DOOR_APPROACH_U}. Walking all the way to the doorway centre
    * leaves the bot inside the doorway's interaction box and the hull's
    * inflated wall, so the transition's pose selection rejects every stand
    * as unreachable ("no reachable interaction pose").
    */
   public static Coord2d walkWorld(GameUI gui, CriticalRouteBook.Leg leg, Coord2d stored) {
      if (leg == null || stored == null) {
         return stored;
      }
      Gob hull = CriticalRouteBook.resolveGob(gui, leg.gobId, leg.resid, stored);
      if (hull == null || hull.rc == null) {
         // Hull not streamed in yet (bot far): walk toward the recorded doorway
         // but stop DOOR_APPROACH_U short of it on the approach line, so the
         // goal stays in free space once the hull appears. Walking to the
         // doorway centre itself would target a solid cell as soon as the hull
         // loads, leaving followTo with "no route around obstacles".
         Coord2d at = gui != null && gui.map != null && gui.map.player() != null ? gui.map.player().rc : null;
         if (at == null) {
            return stored;
         }
         return beforePoint(at, stored, DOOR_APPROACH_U);
      }
      Coord2d doorWorld;
      Gob door = nearestDoorGob(gui, hull);
      if (door != null && door.rc != null) {
         doorWorld = door.rc;
      } else {
         Target t = target(CriticalRouteBook.gobResid(hull), hull.rc, hull.a, hull.id, stored);
         if (t == null || t.origin == null) {
            return stored;
         }
         doorWorld = t.origin;
      }
      return standoff(doorWorld, hull.rc, DOOR_APPROACH_U);
   }

   /** {@code beforeU} units before {@code door} on the approach line from {@code from}. */
   static Coord2d beforePoint(Coord2d from, Coord2d door, double beforeU) {
      if (door == null || from == null) {
         return door;
      }
      double dx = from.x - door.x;
      double dy = from.y - door.y;
      double len = Math.hypot(dx, dy);
      if (len < 1.0) {
         return door;
      }
      return Coord2d.of(door.x + dx / len * beforeU, door.y + dy / len * beforeU);
   }

   /** Pure outward stand-off: {@code doorWorld} pushed {@code standoffU} along the hull-centre-to-door ray. */
   static Coord2d standoff(Coord2d doorWorld, Coord2d hullRc, double standoffU) {
      if (doorWorld == null || hullRc == null) {
         return doorWorld;
      }
      double dx = doorWorld.x - hullRc.x;
      double dy = doorWorld.y - hullRc.y;
      double len = Math.hypot(dx, dy);
      if (len < 1.0) {
         return doorWorld;
      }
      return Coord2d.of(doorWorld.x + dx / len * standoffU, doorWorld.y + dy / len * standoffU);
   }

   public static void interact(GameUI gui, Gob gob, haven.nav.InteractionSpec spec) {
      if (gob == null) {
         return;
      }
      if (gui == null || gui.map == null || gob.rc == null) {
         gob.rclick(0);
         return;
      }
      Coord2d near = spec != null && spec.origin != null ? spec.origin : gob.rc;
      Target t = target(CriticalRouteBook.gobResid(gob), gob.rc, gob.a, gob.id, near);
      if (t == null || t.origin == null || t.mesh < 0) {
         gob.rclick(0);
         return;
      }
      haven.Coord mc = t.origin.floor(haven.OCache.posres);
      gui.map.click(t.origin, 3, haven.Coord.z, mc, 3, 0, 0, (int) gob.id, mc, 0, t.mesh);
   }

   static double searchRadius(String resid) {
      Coord2d[] known = PrototypePathfinder.knownBuildingFootprint(resid, Coord2d.of(0.0, 0.0), 0.0);
      if (known == null || known.length < 2) {
         return 120.0;
      }
      double r = 0.0;
      for (int i = 0; i < known.length; i++) {
         r = Math.max(r, Math.hypot(known[i].x, known[i].y));
      }
      return r + 22.0;
   }

   static boolean sameBuildingFamily(String hull, String door) {
      String h = PrototypePathfinder.baseResid(hull);
      String d = PrototypePathfinder.baseResid(door);
      return h != null && d != null && d.startsWith(h);
   }

   static Coord2d rotate(Coord2d c, double a) {
      if (c == null) {
         return Coord2d.of(0.0, 0.0);
      }
      double cs = Math.cos(a);
      double sn = Math.sin(a);
      return Coord2d.of(c.x * cs - c.y * sn, c.x * sn + c.y * cs);
   }

   static List<Coord2d> doorWorlds(String resid, Coord2d rc, double a) {
      Off[] offs = OFFSETS.get(PrototypePathfinder.baseResid(resid));
      if (rc == null || offs == null) {
         return Collections.emptyList();
      }
      List<Coord2d> out = new ArrayList<Coord2d>(offs.length);
      for (int i = 0; i < offs.length; i++) {
         out.add(rc.add(rotate(offs[i].off, a)));
      }
      return out;
   }
}
