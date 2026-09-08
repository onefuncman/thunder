package haven.pathfinding;

import haven.Coord2d;
import haven.pathfinding.WaypointGate.Observation;
import haven.pathfinding.WaypointGate.Outcome;
import haven.pathfinding.WaypointWalker.GateClass;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CupboardBotGateMappingTest {
   @Test
   void terminalOutcomesMapToWalkRetryClasses() {
      Assertions.assertEquals(GateClass.COMPLETE, WaypointWalker.classify(Outcome.WAYPOINT_COMPLETE));
      Assertions.assertEquals(GateClass.SHORT_STOP, WaypointWalker.classify(Outcome.STOPPED_SHORT));
      Assertions.assertEquals(GateClass.REJECTED, WaypointWalker.classify(Outcome.START_TIMEOUT));
      Assertions.assertEquals(GateClass.REJECTED, WaypointWalker.classify(Outcome.VEHICLE_STATE_CHANGED));
      Assertions.assertEquals(GateClass.TIMEOUT, WaypointWalker.classify(Outcome.WALK_TIMEOUT));
      Assertions.assertEquals(GateClass.TIMEOUT, WaypointWalker.classify(Outcome.NO_PROGRESS));
      Assertions.assertEquals(GateClass.ABORT, WaypointWalker.classify(Outcome.CANCELLED));
   }

   @Test
   void nonTerminalOutcomesAreNotClassifiable() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> WaypointWalker.classify(Outcome.WAITING_START));
      Assertions.assertThrows(IllegalArgumentException.class, () -> WaypointWalker.classify(Outcome.PROGRESSING));
   }

   @Test
   void obsBriefRendersPositionAndState() {
      Observation o = new Observation(1000L, Coord2d.of(10.24, 3.12), true, 42L, true, false);
      String brief = WaypointWalker.obsBrief(o);
      Assertions.assertTrue(brief.contains("at=(10.2,3.1)"), brief);
      Assertions.assertTrue(brief.contains("moving=yes"), brief);
      Assertions.assertTrue(brief.contains("veh=42"), brief);
      Assertions.assertTrue(brief.contains("pass=yes"), brief);
   }

   @Test
   void obsBriefHandlesNullAndFoot() {
      Assertions.assertEquals("obs=none", WaypointWalker.obsBrief(null));
      Observation o = new Observation(2000L, Coord2d.of(0.0, 0.0), false, 0L, false, false);
      Assertions.assertTrue(WaypointWalker.obsBrief(o).contains("moving=no"), WaypointWalker.obsBrief(o));
      Assertions.assertTrue(WaypointWalker.obsBrief(o).contains("veh=0"), WaypointWalker.obsBrief(o));
      Assertions.assertTrue(WaypointWalker.obsBrief(o).contains("pass=no"), WaypointWalker.obsBrief(o));
   }

   @Test
   void gateLabelIsTypedAndConcise() {
      Assertions.assertEquals(
         "gate STOPPED_SHORT  short stop wp 2/5  at=(10.2,3.1) moving=no",
         WaypointWalker.gateLabel(Outcome.STOPPED_SHORT, "short stop wp 2/5", "at=(10.2,3.1) moving=no")
      );
      Assertions.assertEquals("no gate", WaypointWalker.gateLabel(null, "no gate", null), "non-gate failures keep their plain label");
      Assertions.assertEquals("gate CANCELLED", WaypointWalker.gateLabel(Outcome.CANCELLED, null, null));
   }
}
