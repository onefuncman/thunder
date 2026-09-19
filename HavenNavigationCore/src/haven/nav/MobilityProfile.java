package haven.nav;

public final class MobilityProfile {
   public final boolean land;
   public final boolean swim;
   public final boolean boat;
   public final boolean cart;
   public MobilityProfile(boolean land, boolean swim, boolean boat, boolean cart) {
      this.land = land;
      this.swim = swim;
      this.boat = boat;
      this.cart = cart;
   }

   public static MobilityProfile land() {
      return new MobilityProfile(true, false, false, false);
   }

   public static MobilityProfile swim() {
      return new MobilityProfile(true, true, false, false);
   }

   public static MobilityProfile boat() {
      return new MobilityProfile(false, false, true, false);
   }

   public static MobilityProfile cart() {
      return new MobilityProfile(true, false, false, true);
   }

   public boolean vehicle() {
      return this.boat || this.cart;
   }
}
