package haven.pathfinding;

import haven.Coord2d;
import haven.nav.GraphEdge;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;
import haven.nav.NavGoal;
import haven.nav.NavOutcome;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TransitionMachineTest {
   private static final GraphNode SRC = GraphNode.of("w", "s0", "L", "a");
   private static final GraphNode DST = GraphNode.of("w", "s1", "M", "a");
   private static final NavGoal GOAL = NavGoal.point(Coord2d.of(88.0, 12.0));

   private static TransitionMachine machine(GraphEdge.Kind kind) {
      return new TransitionMachine(GOAL, SRC, "fix-1", kind, null);
   }

   private static TransitionMachine.State happyPath(TransitionMachine m, GraphNode landing, MobilityProfile after) {
      Assertions.assertEquals(TransitionMachine.Phase.SELECT_APPROACH, m.state().phase);
      m.selectApproach(true);
      m.arrived();
      m.revalidate(true, true, true);
      m.interact();
      return m.capture(landing, after, false);
   }

   @Test
   void interactBeforeArrivalIsForbidden() {
      TransitionMachine m = machine(GraphEdge.Kind.DOOR_GATE);
      TransitionMachine.State s = m.interact();
      Assertions.assertEquals(TransitionMachine.Phase.FAILED, s.phase);
      Assertions.assertEquals(TransitionMachine.INTERACT_BEFORE_ARRIVAL, s.reason);
      Assertions.assertEquals(NavOutcome.TRANSITION_FAILED, s.outcome);
      Assertions.assertSame(GOAL, s.originalGoal);
   }

   @Test
   void orderingSelectNavigateRevalidateInteractThenAuth() {
      TransitionMachine m = machine(GraphEdge.Kind.DOOR_GATE);
      Assertions.assertEquals(TransitionMachine.Phase.NAVIGATE, m.selectApproach(true).phase);
      Assertions.assertEquals(TransitionMachine.Phase.NAVIGATE, m.state().phase);
      Assertions.assertEquals(TransitionMachine.Phase.REVALIDATE, m.arrived().phase);
      Assertions.assertEquals(TransitionMachine.Phase.INTERACT, m.revalidate(true, true, true).phase);
      Assertions.assertEquals(TransitionMachine.Phase.WAIT_AUTH, m.interact().phase);
      TransitionMachine.State done = m.capture(SRC, MobilityProfile.land(), false);
      Assertions.assertEquals(TransitionMachine.Phase.SUCCEEDED, done.phase);
      Assertions.assertNotEquals(NavOutcome.REACHED, done.outcome);
      Assertions.assertNull(done.outcome);
      Assertions.assertSame(GOAL, done.originalGoal);
      Assertions.assertEquals(88.0, done.originalGoal.position.x, 1.0E-9);
   }

   @Test
   void closedDoorAndTimeoutAndStale() {
      TransitionMachine missing = machine(GraphEdge.Kind.DOOR_GATE);
      missing.selectApproach(true);
      missing.arrived();
      Assertions.assertEquals(TransitionMachine.FIXTURE_UNAVAILABLE, missing.revalidate(false, true, true).reason);
      TransitionMachine changed = machine(GraphEdge.Kind.DOOR_GATE);
      changed.selectApproach(true);
      changed.arrived();
      Assertions.assertEquals(TransitionMachine.FIXTURE_CHANGED, changed.revalidate(true, false, true).reason);
      TransitionMachine to = machine(GraphEdge.Kind.DOOR_GATE);
      to.selectApproach(true);
      to.arrived();
      to.revalidate(true, true, true);
      to.interact();
      Assertions.assertEquals(TransitionMachine.TRANSITION_TIMEOUT, to.timeout().reason);
      GraphEdge stale = GraphEdge.of(SRC, DST, GraphEdge.Kind.CAVE, "old");
      stale.invalidate();
      TransitionMachine sm = new TransitionMachine(GOAL, SRC, "old", GraphEdge.Kind.CAVE, stale);
      Assertions.assertEquals(TransitionMachine.STALE_GRAPH_EDGE, sm.state().reason);
      Assertions.assertEquals(TransitionMachine.Phase.FAILED, sm.state().phase);
   }

   @Test
   void caveRequiresObservedDistinctLanding() {
      TransitionMachine unknown = machine(GraphEdge.Kind.CAVE);
      happyPrep(unknown);
      Assertions.assertEquals(TransitionMachine.LANDING_UNKNOWN, unknown.capture(null, MobilityProfile.land(), false).reason);
      TransitionMachine same = machine(GraphEdge.Kind.LADDER);
      happyPrep(same);
      Assertions.assertEquals(TransitionMachine.LANDING_UNKNOWN, same.capture(SRC, MobilityProfile.land(), false).reason);
      TransitionMachine amb = machine(GraphEdge.Kind.MINEHOLE);
      happyPrep(amb);
      Assertions.assertEquals(TransitionMachine.LANDING_AMBIGUOUS, amb.capture(DST, MobilityProfile.land(), true).reason);
      TransitionMachine ok = machine(GraphEdge.Kind.CELLAR_STAIRS);
      Assertions.assertEquals(TransitionMachine.Phase.SUCCEEDED, happyPath(ok, DST, MobilityProfile.land()).phase);
      Assertions.assertSame(GOAL, ok.originalGoal());
      Assertions.assertNotEquals(NavOutcome.REACHED, ok.state().outcome);
   }

   @Test
   void hearthLandingMustBeObserved() {
      TransitionMachine m = machine(GraphEdge.Kind.HEARTH);
      happyPrep(m);
      Assertions.assertEquals(TransitionMachine.LANDING_UNKNOWN, m.capture(null, MobilityProfile.land(), false).reason);
      TransitionMachine ok = machine(GraphEdge.Kind.HEARTH);
      Assertions.assertEquals(DST, happyPath(ok, DST, MobilityProfile.land()).landing);
   }

   @Test
   void boatRequiresVehicleConfirmationAndFootprintPolicy() {
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/deep", MobilityProfile.land()));
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/water", MobilityProfile.land()));
      Assertions.assertFalse(TerrainPolicy.terrainBlocks("gfx/tiles/water", MobilityProfile.boat()));
      double landR = TerrainPolicy.agentRadius(MobilityProfile.land(), 4.5);
      double boatR = TerrainPolicy.agentRadius(MobilityProfile.boat(), 4.5);
      Assertions.assertTrue(boatR > landR);
      TransitionMachine board = machine(GraphEdge.Kind.BOAT);
      happyPrep(board);
      Assertions.assertEquals(TransitionMachine.INTERACTION_FAILED, board.capture(SRC, MobilityProfile.land(), false).reason);
      TransitionMachine ok = machine(GraphEdge.Kind.BOAT);
      TransitionMachine.State boarded = happyPath(ok, SRC, MobilityProfile.boat());
      Assertions.assertEquals(TransitionMachine.Phase.SUCCEEDED, boarded.phase);
      Assertions.assertTrue(boarded.mobilityAfter.boat);
      Assertions.assertSame(GOAL, boarded.originalGoal);
      TransitionMachine exit = new TransitionMachine(GOAL, SRC, "boat", GraphEdge.Kind.BOAT, null, true);
      happyPrep(exit);
      Assertions.assertEquals(TransitionMachine.INTERACTION_FAILED, exit.capture(SRC, MobilityProfile.boat(), false).reason);
      TransitionMachine land = new TransitionMachine(GOAL, SRC, "boat", GraphEdge.Kind.BOAT, null, true);
      Assertions.assertFalse(happyPath(land, SRC, MobilityProfile.land()).mobilityAfter.vehicle());
   }

   @Test
   void vehicleEntryAndExitReplaceFootprint() {
      double landR = TerrainPolicy.agentRadius(MobilityProfile.land(), 4.5);
      double cartR = TerrainPolicy.agentRadius(MobilityProfile.cart(), 4.5);
      Assertions.assertTrue(cartR > landR);
      TransitionMachine enter = machine(GraphEdge.Kind.VEHICLE);
      Assertions.assertTrue(happyPath(enter, SRC, MobilityProfile.cart()).mobilityAfter.cart);
      TransitionMachine leave = new TransitionMachine(GOAL, SRC, "cart", GraphEdge.Kind.VEHICLE, null, true);
      Assertions.assertEquals(TransitionMachine.Phase.SUCCEEDED, happyPath(leave, SRC, MobilityProfile.land()).phase);
   }

   @Test
   void approachUnavailableAndMobilityTerrain() {
      TransitionMachine m = machine(GraphEdge.Kind.DOOR_GATE);
      Assertions.assertEquals(TransitionMachine.APPROACH_UNAVAILABLE, m.selectApproach(false).reason);
      TransitionMachine mob = machine(GraphEdge.Kind.BOAT);
      Assertions.assertEquals(NavOutcome.UNAVAILABLE, mob.mobilityUnavailable().outcome);
      TransitionMachine ter = machine(GraphEdge.Kind.BOAT);
      Assertions.assertEquals(NavOutcome.UNKNOWN_TERRAIN, ter.terrainUnavailable().outcome);
   }

   @Test
   void retriesAreBounded() {
      TransitionMachine m = machine(GraphEdge.Kind.DOOR_GATE);
      happyPrep(m);
      m.interact();
      m.timeout();
      Assertions.assertEquals(TransitionMachine.Phase.FAILED, m.state().phase);
   }

   @Test
   void houseDoorRequiresDistinctLanding() {
      TransitionMachine house = new TransitionMachine(GOAL, SRC, "door", GraphEdge.Kind.DOOR_GATE, null, TransitionMachine.Auth.TOPOLOGY);
      happyPrep(house);
      Assertions.assertEquals(TransitionMachine.LANDING_UNKNOWN, house.capture(SRC, MobilityProfile.land(), false).reason);
      TransitionMachine crossed = new TransitionMachine(GOAL, SRC, "door", GraphEdge.Kind.DOOR_GATE, null, TransitionMachine.Auth.TOPOLOGY);
      Assertions.assertEquals(TransitionMachine.Phase.SUCCEEDED, happyPath(crossed, DST, MobilityProfile.land()).phase);
      TransitionMachine stairs = new TransitionMachine(GOAL, SRC, "stairs", GraphEdge.Kind.CELLAR_STAIRS, null);
      happyPrep(stairs);
      Assertions.assertEquals(
         TransitionMachine.Phase.SUCCEEDED, stairs.capture(SRC, MobilityProfile.land(), false, true).phase, "cellar stairs may keep a stale graph node if the stairs gob is gone and the player jumped"
      );
   }

   private static void happyPrep(TransitionMachine m) {
      m.selectApproach(true);
      m.arrived();
      m.revalidate(true, true, true);
      m.interact();
   }
}
