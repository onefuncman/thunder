package haven.pathfinding;

import haven.Coord2d;
import haven.nav.GraphEdge;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;

/** Thunder-only fixture/resid conversion and authoritative transition detection. */
public final class TransitionAdapter {
   private TransitionAdapter() {
   }

   public static GraphEdge.Kind kind(String resid) {
      TransitionApproachSelector.TransitionKind ck = TransitionApproachSelector.caveTransitionKind(resid);
      if (ck == TransitionApproachSelector.TransitionKind.MINEHOLE) {
         return GraphEdge.Kind.MINEHOLE;
      }
      if (ck == TransitionApproachSelector.TransitionKind.LADDER) {
         return GraphEdge.Kind.LADDER;
      }
      if (ck == TransitionApproachSelector.TransitionKind.CELLAR_DOOR || ck == TransitionApproachSelector.TransitionKind.CELLAR_STAIRS) {
         return GraphEdge.Kind.CELLAR_STAIRS;
      }
      if (TransitionApproachSelector.isDoorGateResid(resid)) {
         return GraphEdge.Kind.DOOR_GATE;
      }
      if (isBoatResid(resid)) {
         return GraphEdge.Kind.BOAT;
      }
      if (isCartResid(resid)) {
         return GraphEdge.Kind.VEHICLE;
      }
      if (isHearthResid(resid)) {
         return GraphEdge.Kind.HEARTH;
      }
      if (isCaveResid(resid)) {
         return GraphEdge.Kind.CAVE;
      }
      return null;
   }

   public static boolean isBoatResid(String resid) {
      String n = PrototypePathfinder.baseResid(resid);
      return n != null
         && (n.contains("/vehicle/rowboat")
            || n.contains("/vehicle/snekkja")
            || n.contains("/vehicle/knarr")
            || n.contains("/vehicle/spark"));
   }

   public static boolean isCartResid(String resid) {
      String n = PrototypePathfinder.baseResid(resid);
      return n != null && (n.contains("/vehicle/wagon") || n.contains("/vehicle/cart"));
   }

   public static boolean isHearthResid(String resid) {
      String n = PrototypePathfinder.baseResid(resid);
      return n != null && (n.contains("hearth") || n.endsWith("/pow"));
   }

   public static boolean isCaveResid(String resid) {
      String n = PrototypePathfinder.baseResid(resid);
      return n != null && n.contains("cave") && !n.contains("cellar");
   }

   public static boolean isHouseDoorResid(String resid) {
      return BuildingDoor.isHouseHull(resid)
         || TransitionApproachSelector.doorGateKind(resid) == TransitionApproachSelector.DoorGateKind.DOOR;
   }

   /** Palisade / pole / stone / brick gates that open in place. Not house instance doors. */
   public static boolean isPassThroughGate(String resid) {
      return TransitionApproachSelector.isGateResid(resid);
   }

   /** World point just past the gate along the approach → threshold line. */
   public static Coord2d gateThroughPoint(Coord2d from, Coord2d gate, double pastU) {
      if (gate == null) {
         return null;
      }
      double past = pastU > 0.0 ? pastU : 12.0;
      if (from == null) {
         return gate.add(past, 0.0);
      }
      double dx = gate.x - from.x;
      double dy = gate.y - from.y;
      double len = Math.hypot(dx, dy);
      if (len < 1.0) {
         return gate.add(past, 0.0);
      }
      return Coord2d.of(gate.x + dx / len * past, gate.y + dy / len * past);
   }

   /** True when {@code at} is strictly beyond the gate center along from → gate. */
   public static boolean pastGate(Coord2d from, Coord2d gate, Coord2d at) {
      if (from == null || gate == null || at == null) {
         return false;
      }
      double tx = gate.x - from.x;
      double ty = gate.y - from.y;
      double travel2 = tx * tx + ty * ty;
      if (travel2 < 1.0) {
         return at.dist(gate) >= 6.0;
      }
      double mx = at.x - from.x;
      double my = at.y - from.y;
      return mx * tx + my * ty > travel2;
   }

   /** House instance doors change map segment; palisade/wall gates only change sdt. */
   public static TransitionMachine.Auth authFor(GraphEdge.Kind kind, boolean vehicleExit, String resid) {
      if (kind == GraphEdge.Kind.DOOR_GATE && isHouseDoorResid(resid)) {
         return TransitionMachine.Auth.TOPOLOGY;
      }
      return TransitionMachine.authFor(kind, vehicleExit);
   }

   public static boolean topologyChanged(GraphNode before, GraphNode after) {
      return before != null && after != null && !before.sameWalkRegion(after);
   }

   public static boolean boarded(long vehicleBefore, long vehicleAfter) {
      return vehicleBefore == 0L && vehicleAfter != 0L;
   }

   public static boolean disembarked(long vehicleBefore, long vehicleAfter) {
      return vehicleBefore != 0L && vehicleAfter == 0L;
   }

   public static boolean doorStateChanged(int sdtBefore, int sdtAfter) {
      return sdtBefore != sdtAfter;
   }

   public static boolean waterLegal(MobilityProfile mob) {
      return TerrainPolicy.waterTravelLegal(mob);
   }
}
