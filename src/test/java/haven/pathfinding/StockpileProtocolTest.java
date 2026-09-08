package haven.pathfinding;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StockpileProtocolTest {

   @Test
   void fullSequenceReachesAcknowledged() {
      StockpileProtocol p = new StockpileProtocol();
      Assertions.assertTrue(p.accept(StockpileProtocol.Signal.HELD_CONFIRMED));
      Assertions.assertTrue(p.accept(StockpileProtocol.Signal.ITEMACT_ACK));
      Assertions.assertTrue(p.accept(StockpileProtocol.Signal.PLACER_READY));
      Assertions.assertTrue(p.placeSent());
      Assertions.assertTrue(p.accept(StockpileProtocol.Signal.PLACE_ACK));
      Assertions.assertEquals(StockpileProtocol.State.ACKNOWLEDGED, p.state());
      Assertions.assertNull(p.failure());
   }

   @Test
   void illegalTransitionIsRejectedAndStateUnchanged() {
      StockpileProtocol p = new StockpileProtocol();
      Assertions.assertFalse(p.accept(StockpileProtocol.Signal.ITEMACT_ACK)); // requires HELD first
      Assertions.assertEquals(StockpileProtocol.State.IDLE, p.state());
   }

   @Test
   void timeoutFailsClosed() {
      StockpileProtocol p = new StockpileProtocol();
      Assertions.assertTrue(p.accept(StockpileProtocol.Signal.HELD_CONFIRMED));
      Assertions.assertTrue(p.accept(StockpileProtocol.Signal.TIMEOUT));
      Assertions.assertEquals(StockpileProtocol.State.FAILED, p.state());
      Assertions.assertNotNull(p.failure());
      // No further transitions allowed after failure.
      Assertions.assertFalse(p.accept(StockpileProtocol.Signal.PLACER_READY));
   }

   @Test
   void cancelledFailsClosed() {
      StockpileProtocol p = new StockpileProtocol();
      Assertions.assertTrue(p.accept(StockpileProtocol.Signal.CANCELLED));
      Assertions.assertEquals(StockpileProtocol.State.FAILED, p.state());
   }

   @Test
   void acknowledgedRejectsFurtherSignals() {
      StockpileProtocol p = new StockpileProtocol();
      p.accept(StockpileProtocol.Signal.HELD_CONFIRMED);
      p.accept(StockpileProtocol.Signal.ITEMACT_ACK);
      p.accept(StockpileProtocol.Signal.PLACER_READY);
      p.placeSent();
      p.accept(StockpileProtocol.Signal.PLACE_ACK);
      Assertions.assertFalse(p.accept(StockpileProtocol.Signal.PLACE_ACK));
      Assertions.assertEquals(StockpileProtocol.State.ACKNOWLEDGED, p.state());
   }

   @Test
   void placeSentRequiresPlacerReady() {
      StockpileProtocol p = new StockpileProtocol();
      Assertions.assertFalse(p.placeSent()); // not allowed from IDLE
      p.accept(StockpileProtocol.Signal.HELD_CONFIRMED);
      Assertions.assertFalse(p.placeSent()); // not allowed from HELD
      p.accept(StockpileProtocol.Signal.ITEMACT_ACK);
      Assertions.assertFalse(p.placeSent()); // not allowed before PLACER_READY
   }
}
