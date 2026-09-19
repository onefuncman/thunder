package haven.pathfinding;

/** Optional diagnostics emitted by confirmed local route execution. */
public interface MovementListener {
   void event(String message);
   void fail(String message);
   void fail(String message, WaypointGate.Outcome outcome, String observation);
   void beginWait(String what, long ms, String detail);
   void dumpStuck();
}
