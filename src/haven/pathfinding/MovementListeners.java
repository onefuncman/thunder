package haven.pathfinding;

/** Shared inert diagnostics hooks for movement callers that do not display progress. */
public final class MovementListeners {
   private MovementListeners() {
   }

   public static final MovementListener NOOP = new MovementListener() {
      @Override public void event(String msg) { }
      @Override public void fail(String msg) { }
      @Override public void fail(String msg, WaypointGate.Outcome outcome, String observation) { }
      @Override public void beginWait(String what, long ms, String detail) { }
      @Override public void dumpStuck() { }
   };

   public static String gateLabel(WaypointGate.Outcome outcome, String reason, String observation) {
      StringBuilder text = new StringBuilder();
      if (outcome != null) text.append("gate ").append(outcome);
      if (reason != null && !reason.isEmpty()) {
         if (text.length() > 0) text.append("  ");
         text.append(reason);
      }
      if (observation != null && !observation.isEmpty()) {
         if (text.length() > 0) text.append("  ");
         text.append(observation);
      }
      return text.toString();
   }
}
