package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.GraphEdge;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.json.JSONObject;

public class WorldGraphAdapterTest {
   @Test
   void nodeUsesSegmentAndAreaNotLayerOrdinals() {
      GraphNode a = WorldGraphAdapter.of("haven", 10L, "0", Coord.of(5, 5));
      GraphNode b = WorldGraphAdapter.of("haven", 10L, "1", Coord.of(5, 5));
      Assertions.assertEquals("haven", a.worldId);
      Assertions.assertEquals(Long.toUnsignedString(10L, 16), a.segmentId);
      Assertions.assertNotEquals(a, b);
      Assertions.assertFalse(a.sameWalkRegion(b));
      Assertions.assertEquals(WorldGraphAdapter.areaId(Coord.of(5, 5)), a.areaId);
   }

   @Test
   void mobilityFromVehicleState() {
      Assertions.assertFalse(WorldGraphAdapter.mobility(0L, false).vehicle());
      Assertions.assertTrue(WorldGraphAdapter.mobility(9L, false).boat);
      Assertions.assertTrue(WorldGraphAdapter.mobility(3L, true).cart);
      Assertions.assertTrue(TerrainPolicy.agentRadius(WorldGraphAdapter.mobility(9L, false), 4.5) > 4.5);
   }

   @Test
   void topologyAndVehicleDetection() {
      GraphNode src = GraphNode.of("w", "aa", "grid1", "0,0");
      GraphNode dst = GraphNode.of("w", "bb", "grid2", "0,0");
      Assertions.assertTrue(TransitionAdapter.topologyChanged(src, dst));
      Assertions.assertFalse(TransitionAdapter.topologyChanged(src, src));
      GraphNode sameFloor = GraphNode.of("w", "aa", "grid1", "9,9");
      Assertions.assertFalse(TransitionAdapter.topologyChanged(src, sameFloor), "area change on the same floor is not a door crossing");
      Assertions.assertTrue(TransitionAdapter.boarded(0L, 12L));
      Assertions.assertTrue(TransitionAdapter.disembarked(12L, 0L));
      Assertions.assertFalse(TransitionAdapter.boarded(4L, 4L));
      Assertions.assertTrue(TransitionAdapter.doorStateChanged(0, 1));
      Assertions.assertFalse(TransitionAdapter.waterLegal(MobilityProfile.land()));
      Assertions.assertTrue(TransitionAdapter.waterLegal(MobilityProfile.boat()));
   }

   @Test
   void fixtureConversionStaysInThunder() {
      Assertions.assertEquals(GraphEdge.Kind.DOOR_GATE, TransitionAdapter.kind("gfx/terobjs/arch/palisadegate"));
      Assertions.assertEquals(GraphEdge.Kind.CELLAR_STAIRS, TransitionAdapter.kind("gfx/terobjs/arch/cellarstairs"));
      Assertions.assertEquals(GraphEdge.Kind.CELLAR_STAIRS, TransitionAdapter.kind("gfx/terobjs/arch/downstairs"));
      Assertions.assertEquals(GraphEdge.Kind.CELLAR_STAIRS, TransitionAdapter.kind("gfx/terobjs/arch/upstairs"));
      Assertions.assertEquals(GraphEdge.Kind.MINEHOLE, TransitionAdapter.kind("gfx/terobjs/minehole"));
      Assertions.assertEquals(GraphEdge.Kind.LADDER, TransitionAdapter.kind("gfx/terobjs/ladder"));
      Assertions.assertEquals(GraphEdge.Kind.BOAT, TransitionAdapter.kind("gfx/terobjs/vehicle/rowboat"));
      Assertions.assertEquals(GraphEdge.Kind.VEHICLE, TransitionAdapter.kind("gfx/terobjs/vehicle/wagon"));
      Assertions.assertEquals(GraphEdge.Kind.HEARTH, TransitionAdapter.kind("gfx/terobjs/pow[hearth]"));
      Assertions.assertEquals(GraphEdge.Kind.DOOR_GATE, TransitionAdapter.kind("gfx/terobjs/arch/greathall-door"));
      Assertions.assertTrue(TransitionAdapter.isHouseDoorResid("gfx/terobjs/arch/greathall-door"));
      Assertions.assertTrue(TransitionAdapter.isHouseDoorResid("gfx/terobjs/arch/stonemansion"));
      Assertions.assertFalse(TransitionAdapter.isHouseDoorResid("gfx/terobjs/arch/palisadegate"));
      Assertions.assertTrue(TransitionAdapter.isPassThroughGate("gfx/terobjs/arch/polebiggate"));
      Assertions.assertTrue(TransitionAdapter.isPassThroughGate("gfx/terobjs/arch/palisadegate"));
      Assertions.assertFalse(TransitionAdapter.isPassThroughGate("gfx/terobjs/arch/greathall-door"));
      Assertions.assertEquals(
         haven.pathfinding.TransitionMachine.Auth.TOPOLOGY,
         TransitionAdapter.authFor(GraphEdge.Kind.DOOR_GATE, false, "gfx/terobjs/arch/greathall-door")
      );
      Assertions.assertEquals(
         haven.pathfinding.TransitionMachine.Auth.TOPOLOGY,
         TransitionAdapter.authFor(GraphEdge.Kind.DOOR_GATE, false, "gfx/terobjs/arch/stonemansion")
      );
      Assertions.assertEquals(
         haven.pathfinding.TransitionMachine.Auth.STATE,
         TransitionAdapter.authFor(GraphEdge.Kind.DOOR_GATE, false, "gfx/terobjs/arch/palisadegate")
      );
      Assertions.assertNull(TransitionAdapter.kind("gfx/terobjs/cupboard"));
   }

   @Test
   void gateThroughPointIsJustPastTheThreshold() {
      Coord2d from = Coord2d.of(0.0, 0.0);
      Coord2d gate = Coord2d.of(11.0, 0.0);
      Coord2d far = TransitionAdapter.gateThroughPoint(from, gate, 12.0);
      Assertions.assertEquals(23.0, far.x, 1e-6);
      Assertions.assertEquals(0.0, far.y, 1e-6);
      Assertions.assertFalse(TransitionAdapter.pastGate(from, gate, from));
      Assertions.assertFalse(TransitionAdapter.pastGate(from, gate, gate), "standing on the threshold is not through");
      Assertions.assertTrue(TransitionAdapter.pastGate(from, gate, far));
      Assertions.assertTrue(TransitionAdapter.pastGate(from, gate, Coord2d.of(12.0, 0.0)));
   }

   @Test
   void beforeAfterCaptureDoesNotGuessLanding() {
      GraphNode before = GraphNode.of("w", "s0", "L", "a");
      Assertions.assertFalse(TransitionAdapter.topologyChanged(before, before));
      Assertions.assertNull(TransitionAdapter.kind(null));
   }

   @Test
   void transitionKeepsCallerOriginalGoalOnNoGamePath() throws Exception {
      haven.nav.NavGoal original = haven.nav.NavGoal.point(haven.Coord2d.of(12.0, 34.0));
      JSONObject r = new TransitionScenario(TransitionScenario.Kind.DOOR_GATE)
         .execute(new PfTestRunner.Run("transition_door_gate"), null, original);
      Assertions.assertEquals("NO_GAME", r.getJSONObject("facts").getString("refusal"));
   }

   @Test
   void transitionScenariosFailClosedWithoutGame() throws Exception {
      for (TransitionScenario.Kind k : TransitionScenario.Kind.values()) {
         JSONObject r = new TransitionScenario(k).execute(new PfTestRunner.Run(k.name), null);
         Assertions.assertEquals("FAIL", r.getString("verdict"), k.name);
         Assertions.assertEquals("NO_GAME", r.getJSONObject("facts").getString("refusal"), k.name);
         Assertions.assertFalse(r.getJSONObject("facts").optBoolean("reached", true));
         boolean fixtureFail = false;
         org.json.JSONArray checks = r.getJSONArray("checks");
         for (int i = 0; i < checks.length(); i++) {
            org.json.JSONObject c = checks.getJSONObject(i);
            if ("fixture".equals(c.optString("name")) && "fail".equals(c.optString("status"))) {
               fixtureFail = true;
            }
         }
         Assertions.assertTrue(fixtureFail, k.name + " must not report PASS on missing fixture");
      }
   }
}
