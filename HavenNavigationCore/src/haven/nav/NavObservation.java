package haven.nav;

import haven.Coord2d;

public final class NavObservation {
   public final long tMs;
   public final Coord2d position;
   public final boolean moving;
   public final boolean cancelled;
   public final long vehicleId;
   public final boolean passenger;
   public final String worldId;
   public final String segmentId;

   public NavObservation(
      long tMs,
      Coord2d position,
      boolean moving,
      boolean cancelled,
      long vehicleId,
      boolean passenger,
      String worldId,
      String segmentId
   ) {
      this.tMs = tMs;
      this.position = position;
      this.moving = moving;
      this.cancelled = cancelled;
      this.vehicleId = vehicleId;
      this.passenger = passenger;
      this.worldId = worldId;
      this.segmentId = segmentId;
   }
}
