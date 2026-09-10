package haven.nav;

/**
 * Walkable route or explicit transition. Both endpoints must be observed
 * nodes; this type never infers a destination from layer numbers.
 */
public final class GraphEdge {
   public enum Kind {
      WALK,
      DOOR_GATE,
      CELLAR_STAIRS,
      CAVE,
      LADDER,
      MINEHOLE,
      BOAT,
      VEHICLE,
      HEARTH,
      EXPLORE_FRONTIER;
   }

   public final GraphNode from;
   public final GraphNode to;
   public final Kind kind;
   public final String fixtureId;
   public final boolean requireWater;
   public final boolean requireBoat;
   public final boolean requireCart;
   public final boolean requireEnter;
   private boolean stale;

   public GraphEdge(
      GraphNode from,
      GraphNode to,
      Kind kind,
      String fixtureId,
      boolean requireWater,
      boolean requireBoat,
      boolean requireCart,
      boolean requireEnter
   ) {
      if (from == null || to == null) {
         throw new IllegalArgumentException("edge endpoints must be observed nodes");
      }
      if (kind == null) {
         throw new IllegalArgumentException("kind");
      }
      this.from = from;
      this.to = to;
      this.kind = kind;
      this.fixtureId = fixtureId == null ? "" : fixtureId;
      this.requireWater = requireWater;
      this.requireBoat = requireBoat;
      this.requireCart = requireCart;
      this.requireEnter = requireEnter;
   }

   public static GraphEdge of(GraphNode from, GraphNode to, Kind kind, String fixtureId) {
      return new GraphEdge(from, to, kind, fixtureId, false, false, false, false);
   }

   public static GraphEdge walk(GraphNode from, GraphNode to, boolean water) {
      return new GraphEdge(from, to, Kind.WALK, "", water, false, false, false);
   }

   public boolean stale() {
      return this.stale;
   }

   public void invalidate() {
      this.stale = true;
   }

   public boolean allowed(MobilityProfile p) {
      if (this.stale) {
         return false;
      }
      MobilityProfile m = p == null ? MobilityProfile.land() : p;
      if (this.requireWater && !m.swim && !m.boat) {
         return false;
      }
      if (this.requireBoat && !m.boat && !m.permitVehicleEnter) {
         return false;
      }
      if (this.requireCart && !m.cart && !m.permitVehicleEnter) {
         return false;
      }
      if (this.requireEnter && !m.permitVehicleEnter && !m.boat && !m.cart) {
         return false;
      }
      return true;
   }
}
