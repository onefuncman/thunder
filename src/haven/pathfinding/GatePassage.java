package haven.pathfinding;

import auto.Bot;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Bot-driven pass-through-gate handling for bot-based walks: when a planned
 * route crosses a closed palisade/pole/stone/brick gate, the walker opens it
 * (right-click), passes through, then closes it behind from the far side.
 * Gates that were already open when we arrived are left alone.
 *
 * Route plans pass through gates because {@code PrototypePathfinder.rasterGobs}
 * carves gate gobs out of the occupancy grid; the gate polygons stay in
 * {@code scene.solids} so beside-the-gate pose selection is unaffected.
 */
public final class GatePassage {
   /** Route-segment pad: a segment crossing within this distance of a gate edge counts as passing it. */
   public static final double GATE_PAD = 1.0;

   /** How far before the gate center to stand to open/close it (well within the 35u interact range). */
   public static final double GATE_APPROACH_U = 8.0;

   private GatePassage() {
   }

   /** One gate the route passes through, with the waypoints bracketing it. */
   public static final class Crossing {
      public final PrototypePathfinder.GobGeom gate;
      /** Route waypoint just before the gate (start of the crossing segment). */
      public final int nearIndex;
      /** Route waypoint just after the gate (end of the crossing segment). */
      public final int farIndex;

      Crossing(PrototypePathfinder.GobGeom gate, int nearIndex, int farIndex) {
         this.gate = gate;
         this.nearIndex = nearIndex;
         this.farIndex = farIndex;
      }

      @Override
      public String toString() {
         return String.format("Crossing[gate #%d %s near=%d far=%d]", gate == null ? -1L : gate.id, gate == null ? "?" : gate.resid, nearIndex, farIndex);
      }
   }

   /** True for pass-through gate gobs (not house doors) that are NOT already open:
    * the 8 isGateResid gate types with a position and a non-open state. An
    * already-open gate needs no open/close handling (the walk simply goes
    * through it), so it is filtered out — which also implements the
    * "leave already-open gates alone" rule at detection time. */
   public static boolean isGateGob(PrototypePathfinder.GobGeom g) {
      return g != null
         && g.doorGate
         && g.rc != null
         && g.gateState != 1
         && TransitionApproachSelector.isGateResid(g.resid);
   }

   /**
    * Pure detection: which gates does the polyline route cross, and which
    * waypoints bracket each crossing. Detection is segment-based: a gate is
    * crossed when one of its route segments intersects the gate's collision
    * polygon. This is necessary because the carve lets a plan run a straight
    * two-waypoint segment straight through a closed gate, so there is often
    * no waypoint sitting ON the gate center to match against. Crossings are
    * returned in route order.
    */
   public static List<GatePassage.Crossing> detectCrossings(List<Coord2d> route, List<PrototypePathfinder.GobGeom> gates) {
      List<GatePassage.Crossing> out = new ArrayList<>();
      if (route == null || route.size() < 2 || gates == null || gates.isEmpty()) {
         return out;
      }
      for (PrototypePathfinder.GobGeom gate : gates) {
         if (!isGateGob(gate)) {
            continue;
         }
         List<Coord2d[]> polys = gatePolygons(gate);
         if (polys.isEmpty()) {
            continue;
         }
         for (int i = 0; i < route.size() - 1; i++) {
            Coord2d a = route.get(i);
            Coord2d b = route.get(i + 1);
            if (a != null && b != null && segmentHitsGate(a, b, polys)) {
               out.add(new GatePassage.Crossing(gate, i, i + 1));
               break;
            }
         }
      }
      out.sort(Comparator.comparingInt(c -> c.nearIndex));
      return out;
   }

   /** The polygons a route segment is tested against: collision first, then movement, then the known footprint. */
   private static List<Coord2d[]> gatePolygons(PrototypePathfinder.GobGeom gate) {
      if (gate.collision != null && !gate.collision.isEmpty()) {
         return gate.collision;
      }
      if (gate.movement != null && !gate.movement.isEmpty()) {
         return gate.movement;
      }
      if (gate.fallback != null) {
         List<Coord2d[]> single = new ArrayList<>(1);
         single.add(gate.fallback);
         return single;
      }
      return Collections.emptyList();
   }

   private static boolean segmentHitsGate(Coord2d a, Coord2d b, List<Coord2d[]> polys) {
      for (Coord2d[] poly : polys) {
         if (poly != null && poly.length >= 2 && LocalPlanner.segmentHitsPolygon(a, b, poly, GATE_PAD)) {
            return true;
         }
      }
      return false;
   }

   /** A point on the approach line, {@code beforeU} units before the gate center (near side). */
   private static Coord2d nearPoint(Coord2d from, Coord2d gate, double beforeU) {
      if (gate == null) {
         return null;
      }
      if (from == null) {
         return gate.add(-beforeU, 0.0);
      }
      double dx = gate.x - from.x;
      double dy = gate.y - from.y;
      double len = Math.hypot(dx, dy);
      if (len < 1.0) {
         return gate.add(-beforeU, 0.0);
      }
      return Coord2d.of(gate.x - dx / len * beforeU, gate.y - dy / len * beforeU);
   }

   /** Live gate gobs observed around the player. */
   public static List<PrototypePathfinder.GobGeom> observedGates(GameUI gui) {
      List<PrototypePathfinder.GobGeom> out = new ArrayList<>();
      if (gui != null && gui.map != null) {
         PrototypePathfinder.Scene scene = PrototypePathfinder.observe(gui);
         if (scene != null && scene.gobs != null) {
            for (PrototypePathfinder.GobGeom g : scene.gobs) {
               if (isGateGob(g)) {
                  out.add(g);
               }
            }
         }
      }
      return out;
   }

   /**
    * Convenience for walkers: observe once and detect crossings on a planned
    * route. Returns an empty list for gate-free routes so callers can keep
    * their existing direct-walk path unchanged.
    */
   public static List<GatePassage.Crossing> observedCrossings(GameUI gui, List<Coord2d> route) {
      if (route == null || route.size() < 2) {
         return Collections.emptyList();
      }
      List<PrototypePathfinder.GobGeom> gates = observedGates(gui);
      return gates.isEmpty() ? Collections.emptyList() : detectCrossings(route, gates);
   }

   /**
    * Map a {@link #walk} error string to the walker result a caller's existing
    * contract expects: null -> arrived, stuck -> stuck, gate-open failure ->
    * rejected (lets the caller replan/fail), anything else -> timeout.
    */
   public static WaypointWalker.Result resultFor(String err) {
      if (err == null) {
         return WaypointWalker.Result.ARRIVED;
      }
      if (err.startsWith("stuck")) {
         return WaypointWalker.Result.STUCK;
      }
      if (err.contains("open failed")) {
         return WaypointWalker.Result.REJECTED;
      }
      return WaypointWalker.Result.TIMEOUT;
   }

   /**
    * Walk the route with bot-based gate passage. Gate-free routes are walked
    * as one straight WaypointWalker run. Returns null on success, or a terse
    * error string on failure ("stuck before gate", "gate open failed: ...",
    * "stuck passing through gate", "walk budget exhausted").
    */
   public static String walk(GameUI gui, Bot bot, List<Coord2d> route, long walkBudgetMs, WaypointWalker.Listener listener) throws InterruptedException {
      if (route == null || route.size() < 2) {
         return null;
      }
      WaypointWalker.Listener l = listener == null ? NamedPlaceNavigator.NOOP : listener;
      List<GatePassage.Crossing> crossings = observedCrossings(gui, route);
      long deadline = System.currentTimeMillis() + walkBudgetMs;
      if (crossings.isEmpty()) {
         WaypointWalker.Result r = WaypointWalker.execute(WaypointWalker.liveEnv(gui), bot, route, 0, walkBudgetMs, WaypointWalker.Params.DEFAULT, l);
         return (r == WaypointWalker.Result.ARRIVED || r == WaypointWalker.Result.READY_TO_INTERACT) ? null : r.name();
      }
      int cursor = 0;
      for (GatePassage.Crossing c : crossings) {
         long remaining = deadline - System.currentTimeMillis();
         if (remaining <= 0L) {
            return "walk budget exhausted";
         }
         // 1. Walk to a point just before the gate (not necessarily a route
         // waypoint — the crossing segment may be long, so the gate can sit far
         // from both of its endpoints).
         Coord2d pos = PfTestHarness.observePos(gui);
         if (pos == null) {
            return "stuck before gate";
         }
         Coord2d nearPt = nearPoint(route.get(c.nearIndex), c.gate.rc, GATE_APPROACH_U);
         if (nearPt == null) {
            return "stuck before gate";
         }
         List<Coord2d> approach = new ArrayList<>();
         approach.add(pos);
         for (int i = cursor + 1; i <= c.nearIndex; i++) {
            approach.add(route.get(i));
         }
         approach.add(nearPt);
         if (approach.size() >= 2) {
            WaypointWalker.Result r = WaypointWalker.execute(WaypointWalker.liveEnv(gui), bot, approach, 0, remaining, WaypointWalker.Params.DEFAULT, l);
            if (r == WaypointWalker.Result.STUCK || r == WaypointWalker.Result.TIMEOUT || r == WaypointWalker.Result.REJECTED) {
               return "stuck before gate";
            }
         }
         // 2. Open the gate if it is not already open (a gate someone else
         // opened in the meantime is left alone — we did not open it).
         boolean openedByUs = false;
         int sdt = gateState(gui, c.gate.id);
         if (TransitionApproachSelector.transitionState(sdt) != TransitionApproachSelector.TransitionState.OPEN) {
            String err;
            try {
               err = TransitionScenario.openGate(null, gui.ui, gui, c.gate.id);
            } catch (InterruptedException e) {
               throw e;
            } catch (Exception e) {
               err = e.toString();
            }
            if (err != null) {
               PathfinderLog.dumpFailure("gate passage open failed: " + err);
               return "gate open failed: " + err;
            }
            openedByUs = true;
         }
         remaining = deadline - System.currentTimeMillis();
         if (remaining <= 0L) {
            return "walk budget exhausted";
         }
         if (openedByUs) {
            // 3+4. We opened it: walk through to a point just past the gate AND
            // close it. passThroughAndClose stops ~12u past the gate before
            // closing, so the close is always within range even when the far
            // waypoint is much farther on. A failed close is a warning only.
            try {
               String closeErr = TransitionScenario.passThroughAndClose(null, gui.ui, gui, c.gate.id, nearPt);
               if (closeErr != null) {
                  PathfinderLog.dumpFailure("gate passage close warning: " + closeErr);
               }
            } catch (InterruptedException e) {
               throw e;
            } catch (Exception e) {
               PathfinderLog.dumpFailure("gate passage close warning: " + e);
            }
         } else {
            // 3. Already open: just walk through to the far waypoint; do not close.
            List<Coord2d> through = new ArrayList<>();
            through.add(nearPt);
            through.add(route.get(c.farIndex));
            WaypointWalker.Result tr = WaypointWalker.execute(WaypointWalker.liveEnv(gui), bot, through, 0, remaining, WaypointWalker.Params.DEFAULT, l);
            if (tr == WaypointWalker.Result.STUCK || tr == WaypointWalker.Result.TIMEOUT || tr == WaypointWalker.Result.REJECTED) {
               return "stuck passing through gate";
            }
         }
         // Resume the tail from the far waypoint (inclusive), since the
         // open-and-close path only advances us ~12u past the gate.
         cursor = c.farIndex - 1;
      }
      // 5. Walk the remaining route after the last gate.
      List<Coord2d> tail = new ArrayList<>();
      Coord2d pos = PfTestHarness.observePos(gui);
      if (pos == null) {
         return "stuck after gate";
      }
      tail.add(pos);
      for (int i = cursor + 1; i < route.size(); i++) {
         tail.add(route.get(i));
      }
      if (tail.size() >= 2) {
         long remaining = deadline - System.currentTimeMillis();
         if (remaining <= 0L) {
            return "walk budget exhausted";
         }
         WaypointWalker.Result r = WaypointWalker.execute(WaypointWalker.liveEnv(gui), bot, tail, 0, remaining, WaypointWalker.Params.DEFAULT, l);
         return (r == WaypointWalker.Result.ARRIVED || r == WaypointWalker.Result.READY_TO_INTERACT) ? null : r.name();
      }
      return null;
   }

   private static int gateState(GameUI gui, long gobId) {
      synchronized (gui.ui) {
         Gob g = gui.map.glob.oc.getgob(gobId);
         return g == null ? -1 : g.sdt();
      }
   }
}
