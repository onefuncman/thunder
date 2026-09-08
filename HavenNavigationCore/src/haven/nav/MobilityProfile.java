package haven.nav;

public final class MobilityProfile {
   public final boolean land;
   public final boolean swim;
   public final boolean boat;
   public final boolean cart;
   public final boolean permitLandToWater;
   public final boolean permitVehicleEnter;
   public final boolean permitVehicleExit;

   public MobilityProfile(
      boolean land,
      boolean swim,
      boolean boat,
      boolean cart,
      boolean permitLandToWater,
      boolean permitVehicleEnter,
      boolean permitVehicleExit
   ) {
      this.land = land;
      this.swim = swim;
      this.boat = boat;
      this.cart = cart;
      this.permitLandToWater = permitLandToWater;
      this.permitVehicleEnter = permitVehicleEnter;
      this.permitVehicleExit = permitVehicleExit;
   }

   public static MobilityProfile land() {
      return new MobilityProfile(true, false, false, false, false, false, false);
   }

   public static MobilityProfile swim() {
      return new MobilityProfile(true, true, false, false, true, false, false);
   }

   public static MobilityProfile boat() {
      return new MobilityProfile(false, false, true, false, true, true, true);
   }

   public static MobilityProfile cart() {
      return new MobilityProfile(true, false, false, true, false, true, true);
   }

   public boolean vehicle() {
      return this.boat || this.cart;
   }
}
