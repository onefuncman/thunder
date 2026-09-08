package haven.pathfinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import haven.Coord2d;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure geometry checks for bot-based gate passage detection (no live client). */
class GatePassageTest {
   private static final String GATE_RESID = "gfx/terobjs/arch/palisadegate";
   private static final String DOOR_RESID = "gfx/terobjs/arch/logcabin-door";

   /** A gate gob with a square collision footprint (±3u) around its center. */
   private static PrototypePathfinder.GobGeom gate(long id, String resid, double x, double y) {
      PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
      g.id = id;
      g.resid = resid;
      g.rc = Coord2d.of(x, y);
      g.doorGate = true;
      g.collision = Collections.singletonList(new Coord2d[]{
         Coord2d.of(x - 3.0, y - 3.0),
         Coord2d.of(x + 3.0, y - 3.0),
         Coord2d.of(x + 3.0, y + 3.0),
         Coord2d.of(x - 3.0, y + 3.0)
      });
      return g;
   }

   private static List<Coord2d> route(Coord2d... pts) {
      return new ArrayList<>(Arrays.asList(pts));
   }

   @Test
   void routeThroughGateYieldsOneCrossingWithSegmentIndices() {
      PrototypePathfinder.GobGeom gate = gate(42L, GATE_RESID, 100.0, 100.0);
      List<Coord2d> route = route(
         Coord2d.of(60.0, 100.0),
         Coord2d.of(95.0, 100.0),   // near side
         Coord2d.of(120.0, 100.0),  // far side
         Coord2d.of(140.0, 100.0)
      );
      List<GatePassage.Crossing> crossings = GatePassage.detectCrossings(route, Collections.singletonList(gate));
      assertEquals(1, crossings.size());
      GatePassage.Crossing c = crossings.get(0);
      assertSame(gate, c.gate);
      assertEquals(1, c.nearIndex);
      assertEquals(2, c.farIndex);
   }

   @Test
   void twoWaypointStraightThroughGateIsDetected() {
      // The real failure case: the carve lets a plan run one straight segment
      // through a closed gate with no waypoint sitting on the gate center.
      PrototypePathfinder.GobGeom gate = gate(42L, GATE_RESID, 100.0, 100.0);
      List<Coord2d> route = route(
         Coord2d.of(90.0, 100.0),
         Coord2d.of(110.0, 100.0)
      );
      List<GatePassage.Crossing> crossings = GatePassage.detectCrossings(route, Collections.singletonList(gate));
      assertEquals(1, crossings.size());
      assertEquals(0, crossings.get(0).nearIndex);
      assertEquals(1, crossings.get(0).farIndex);
      assertEquals(42L, crossings.get(0).gate.id);
   }

   @Test
   void diagonalRouteCrossingGateIsDetected() {
      PrototypePathfinder.GobGeom gate = gate(7L, GATE_RESID, 50.0, 50.0);
      List<Coord2d> route = route(
         Coord2d.of(30.0, 30.0),
         Coord2d.of(60.0, 60.0),
         Coord2d.of(70.0, 70.0)
      );
      List<GatePassage.Crossing> crossings = GatePassage.detectCrossings(route, Collections.singletonList(gate));
      assertEquals(1, crossings.size());
      assertEquals(0, crossings.get(0).nearIndex);
      assertEquals(1, crossings.get(0).farIndex);
      assertEquals(7L, crossings.get(0).gate.id);
   }

   @Test
   void routeFarFromGateYieldsNoCrossing() {
      PrototypePathfinder.GobGeom gate = gate(1L, GATE_RESID, 100.0, 100.0);
      List<Coord2d> route = route(
         Coord2d.of(60.0, 100.0),
         Coord2d.of(90.0, 80.0),
         Coord2d.of(120.0, 60.0)
      );
      assertTrue(GatePassage.detectCrossings(route, Collections.singletonList(gate)).isEmpty());
   }

   @Test
   void emptyRouteAndEmptyGateListYieldNoCrossings() {
      assertTrue(GatePassage.detectCrossings(Collections.emptyList(), Collections.singletonList(gate(1L, GATE_RESID, 0.0, 0.0))).isEmpty());
      assertTrue(GatePassage.detectCrossings(
         route(Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0)), Collections.emptyList()
      ).isEmpty());
      assertTrue(GatePassage.detectCrossings(null, Collections.singletonList(gate(1L, GATE_RESID, 0.0, 0.0))).isEmpty());
   }

   @Test
   void gateWithoutPositionIsIgnored() {
      PrototypePathfinder.GobGeom gate = new PrototypePathfinder.GobGeom();
      gate.id = 5L;
      gate.resid = GATE_RESID;
      gate.doorGate = true;
      gate.rc = null;
      List<Coord2d> route = route(
         Coord2d.of(30.0, 30.0),
         Coord2d.of(40.0, 40.0),
         Coord2d.of(50.0, 50.0),
         Coord2d.of(60.0, 60.0)
      );
      assertTrue(GatePassage.detectCrossings(route, Collections.singletonList(gate)).isEmpty());
   }

   @Test
   void nonGateDoorResidIsIgnored() {
      PrototypePathfinder.GobGeom door = gate(3L, DOOR_RESID, 50.0, 50.0);
      List<Coord2d> route = route(
         Coord2d.of(30.0, 30.0),
         Coord2d.of(40.0, 40.0),
         Coord2d.of(50.0, 50.0),
         Coord2d.of(60.0, 60.0)
      );
      assertTrue(GatePassage.detectCrossings(route, Collections.singletonList(door)).isEmpty());
   }

   @Test
   void alreadyOpenGateIsIgnored() {
      // A gate already open (sdt == 1) needs no handling: leave it alone.
      PrototypePathfinder.GobGeom gate = gate(9L, GATE_RESID, 100.0, 100.0);
      gate.gateState = 1;
      List<Coord2d> route = route(
         Coord2d.of(90.0, 100.0),
         Coord2d.of(110.0, 100.0)
      );
      assertTrue(GatePassage.detectCrossings(route, Collections.singletonList(gate)).isEmpty());
   }

   @Test
   void crossingsComeBackInRouteOrder() {
      PrototypePathfinder.GobGeom far = gate(2L, GATE_RESID, 200.0, 0.0);
      PrototypePathfinder.GobGeom near = gate(1L, "gfx/terobjs/arch/polegate", 100.0, 0.0);
      List<Coord2d> route = route(
         Coord2d.of(0.0, 0.0),
         Coord2d.of(95.0, 0.0),
         Coord2d.of(150.0, 0.0),
         Coord2d.of(205.0, 0.0)
      );
      List<GatePassage.Crossing> crossings = GatePassage.detectCrossings(route, Arrays.asList(far, near));
      assertEquals(2, crossings.size());
      assertEquals(1L, crossings.get(0).gate.id);
      assertEquals(1, crossings.get(0).nearIndex);
      assertEquals(2, crossings.get(0).farIndex);
      assertEquals(2L, crossings.get(1).gate.id);
      assertEquals(2, crossings.get(1).nearIndex);
      assertEquals(3, crossings.get(1).farIndex);
   }

   @Test
   void resultForMapsWalkErrorsToWalkerResults() {
      assertSame(WaypointWalker.Result.ARRIVED, GatePassage.resultFor(null));
      assertSame(WaypointWalker.Result.STUCK, GatePassage.resultFor("stuck before gate"));
      assertSame(WaypointWalker.Result.REJECTED, GatePassage.resultFor("gate open failed: gate did not open"));
      assertSame(WaypointWalker.Result.TIMEOUT, GatePassage.resultFor("walk budget exhausted"));
   }
}
