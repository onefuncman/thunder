package haven.nav;

/**
 * Receives planning and execution events. Implementations must not
 * influence navigation decisions.
 */
public interface NavigationTelemetrySink {
   void onEvent(String type, String detail);

   NavigationTelemetrySink NONE = new NavigationTelemetrySink() {
      @Override
      public void onEvent(String type, String detail) {
      }
   };
}
