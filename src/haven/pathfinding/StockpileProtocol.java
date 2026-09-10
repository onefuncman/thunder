package haven.pathfinding;

/**
 * Fail-closed seam for the real stockpile creation wire sequence. It has no
 * UI dependencies, so illegal acknowledgements are testable without a game.
 */
public final class StockpileProtocol {
   public enum State { IDLE, HELD, ITEMACT_SENT, PLACER_READY, PLACE_SENT, ACKNOWLEDGED, FAILED }
   public enum Signal { HELD_CONFIRMED, ITEMACT_ACK, PLACER_READY, PLACE_ACK, TIMEOUT, CANCELLED }

   private State state = State.IDLE;
   private String failure;

   public State state() { return state; }
   public String failure() { return failure; }

   public boolean accept(Signal signal) {
      if (signal == null || state == State.FAILED || state == State.ACKNOWLEDGED) return false;
      switch (signal) {
         case HELD_CONFIRMED:
            return move(State.IDLE, State.HELD);
         case ITEMACT_ACK:
            return move(State.HELD, State.ITEMACT_SENT);
         case PLACER_READY:
            return move(State.ITEMACT_SENT, State.PLACER_READY);
         case PLACE_ACK:
            return move(State.PLACE_SENT, State.ACKNOWLEDGED);
         case TIMEOUT:
            return fail("timeout while " + state);
         case CANCELLED:
            return fail("cancelled while " + state);
         default:
            return false;
      }
   }

   /** Marks the outbound place message only after the server entered the placer. */
   public boolean placeSent() {
      return move(State.PLACER_READY, State.PLACE_SENT);
   }

   public boolean fail(String reason) {
      if (state == State.ACKNOWLEDGED || state == State.FAILED) return false;
      state = State.FAILED;
      failure = reason == null || reason.isEmpty() ? "protocol failure" : reason;
      return true;
   }

   private boolean move(State expected, State next) {
      if (state != expected) return false;
      state = next;
      return true;
   }
}
