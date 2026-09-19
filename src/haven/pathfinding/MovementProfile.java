package haven.pathfinding;

import haven.Following;
import haven.Gob;
import haven.Hitbox;
import haven.Loading;
import haven.Moving;
import haven.Coord2d;
import haven.nav.MobilityProfile;

/** Detects only the local body/terrain profile needed by local planning. */
public final class MovementProfile {
   private MovementProfile() {
   }

   public static MobilityProfile of(Gob player) {
      if (player == null || player.vehicleId() == 0L) {
         return MobilityProfile.land();
      }
      Moving moving = player.getattr(Moving.class);
      if (moving instanceof Following) {
         Gob vehicle = ((Following)moving).tgt();
         String resid = vehicle == null ? null : vehicle.resid();
         if (isCart(resid)) return MobilityProfile.cart();
      }
      return MobilityProfile.boat();
   }

   /** Effective local collision radius for the current land/cart/boat body. */
   public static double agentRadius(Gob player) {
      double radius = 4.5;
      Gob subject = BoatNavigation.movementSubject(player);
      Coord2d origin = BoatNavigation.position(player);
      if (subject != null && subject != player) {
         return TerrainPolicy.agentRadius(of(player), BoatNavigation.travelRadius(player));
      }
      try {
         double measured = subject == null || origin == null ? 0.0
            : LocalPlanner.boundingRadius(origin, Hitbox.worldPolygons(subject));
         if (measured > 0.5) radius = measured;
      } catch (Loading ignored) {
      }
      return TerrainPolicy.agentRadius(of(player), radius);
   }

   static boolean isCart(String resid) {
      String normalized = MovementScene.baseResid(resid);
      return normalized != null && (normalized.contains("/vehicle/wagon") || normalized.contains("/vehicle/cart"));
   }
}
