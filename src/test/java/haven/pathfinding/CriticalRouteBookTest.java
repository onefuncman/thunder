package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CriticalRouteBookTest {
   @Test
   void roundTripPreservesStandAndGobLegs(@TempDir Path dir) throws Exception {
      CriticalRouteBook.overrideDir = dir;
      try {
         CriticalRouteBook b = new CriticalRouteBook("To the Gate!");
         Assertions.assertEquals("to_the_gate", b.id);
         b.addStand(Coord2d.of(10.5, 20.25), "cellarseg");
         JSONObject gob = new JSONObject()
            .put("kind", "gob")
            .put("role", "door_gate")
            .put("gob_id", 99L)
            .put("resid", "gfx/terobjs/arch/palisadegate")
            .put("x", 30.0)
            .put("y", 40.0)
            .put("seg", "houseseg");
         b.legs.add(CriticalRouteBook.Leg.fromJson(gob));
         b.save();
         Assertions.assertEquals("to_the_gate", CriticalRouteBook.currentId());
         CriticalRouteBook loaded = CriticalRouteBook.load("to_the_gate");
         Assertions.assertEquals(2, loaded.legs.size());
         Assertions.assertEquals("stand", loaded.legs.get(0).kind);
         Assertions.assertEquals(10.5, loaded.legs.get(0).world.x, 1e-9);
         Assertions.assertTrue(loaded.legs.get(0).map);
         Assertions.assertEquals(Coord.of(0, 1), loaded.legs.get(0).tile);
         Assertions.assertEquals("map", loaded.toJson().getJSONArray("legs").getJSONObject(0).getString("space"));
         Assertions.assertEquals("cellarseg", loaded.legs.get(0).seg);
         Assertions.assertTrue(loaded.legs.get(0).onFloor("cellarseg"));
         Assertions.assertFalse(loaded.legs.get(0).onFloor("houseseg"));
         Assertions.assertFalse(loaded.legs.get(0).onFloor(""));
         Assertions.assertEquals("door_gate", loaded.legs.get(1).role);
         Assertions.assertEquals(99L, loaded.legs.get(1).gobId);
         Assertions.assertEquals("houseseg", loaded.legs.get(1).seg);
         Assertions.assertEquals(Coord2d.of(30.0, 40.0), loaded.originalDest());
         Assertions.assertTrue(CriticalRouteBook.listIds().contains("to_the_gate"));
         Assertions.assertFalse(CriticalRouteBook.isApproach(loaded.legs.get(0)));
         Assertions.assertFalse(CriticalRouteBook.isApproach(loaded.legs.get(1)));
         Assertions.assertTrue(CriticalRouteBook.isGround(loaded.legs.get(0)));
      } finally {
         CriticalRouteBook.overrideDir = null;
      }
   }

   @Test
   void mapWorldRoundTripsThroughSessionOrigin() {
      Coord tc = Coord.of(12, -7);
      Coord2d session = Coord2d.of(245.3, 118.9);
      Coord2d map = CriticalRouteBook.toMap(session, tc);
      Assertions.assertEquals(session.add(tc.mul(MCache.tilesz)), map);
      Assertions.assertEquals(session.x, CriticalRouteBook.toSession(map, tc).x, 1e-9);
      Assertions.assertEquals(session.y, CriticalRouteBook.toSession(map, tc).y, 1e-9);
      Assertions.assertEquals(session.floor(MCache.tilesz).add(tc), CriticalRouteBook.mapTile(session, tc));
      Coord2d otherOrigin = CriticalRouteBook.toSession(map, Coord.of(40, 2));
      Assertions.assertNotEquals(session.x, otherOrigin.x, 1.0, "a new login origin must not reuse the old session numbers");
      Assertions.assertTrue(CriticalRouteBook.residMatches("gfx/terobjs/arch/polebiggate[oak]", "gfx/terobjs/arch/polebiggate"));
      Assertions.assertFalse(CriticalRouteBook.residMatches("gfx/terobjs/arch/palisadegate", "gfx/terobjs/arch/polebiggate"));
      JSONObject legacy = new JSONObject().put("kind", "stand").put("x", 1.0).put("y", 2.0).put("seg", "a");
      Assertions.assertFalse(CriticalRouteBook.Leg.fromJson(legacy).map, "old session-space files stay session-space");
   }

   @Test
   void recordedScenarioFailsClosedWithoutGame() throws Exception {
      JSONObject r = new RecordedRouteScenario().execute(new PfTestRunner.Run("campaign_recorded"), null);
      Assertions.assertEquals("FAIL", r.getString("verdict"));
      Assertions.assertEquals("NO_GAME", r.getJSONObject("facts").getString("refusal"));
   }

   @Test
   void inGameWalkSkipsLegsOnOtherFloors() {
      CriticalRouteBook.Leg basement = new CriticalRouteBook.Leg("stand", Coord2d.of(1, 1), -1L, "", "stand", "cellar");
      CriticalRouteBook.Leg stairs = new CriticalRouteBook.Leg("gob", Coord2d.of(2, 2), 9L, "gfx/terobjs/arch/cellarstairs", "cellar_stairs", "cellar");
      CriticalRouteBook.Leg house = new CriticalRouteBook.Leg("stand", Coord2d.of(3, 3), -1L, "", "stand", "house");
      Assertions.assertTrue(RecordedRouteScenario.skipOtherFloor(basement, "house", false, true));
      Assertions.assertTrue(RecordedRouteScenario.skipOtherFloor(stairs, "house", false, true));
      Assertions.assertFalse(RecordedRouteScenario.skipOtherFloor(house, "house", false, true));
      Assertions.assertFalse(RecordedRouteScenario.skipOtherFloor(basement, "house", false, false));
      Assertions.assertFalse(RecordedRouteScenario.skipOtherFloor(stairs, "house", true, true));
      Assertions.assertTrue(
         RecordedRouteScenario.closeBehind(
            new CriticalRouteBook.Leg("gob", Coord2d.of(0, 0), 2L, "gfx/terobjs/arch/polebiggate", "door_gate", "yard")
         )
      );
      Assertions.assertFalse(
         RecordedRouteScenario.closeBehind(
            new CriticalRouteBook.Leg("gob", Coord2d.of(0, 0), 3L, "gfx/terobjs/arch/greathall-door", "door_gate", "house")
         )
      );
      Assertions.assertEquals(
         TransitionScenario.Kind.CELLAR_STAIRS,
         RecordedRouteScenario.transitionKind(
            new CriticalRouteBook.Leg("gob", Coord2d.of(0, 0), 4L, "gfx/terobjs/arch/downstairs", "gob", "house")
         ),
         "a saved downstairs click must be stairs even if the file still says role gob"
      );
   }

   @Test
   void recordedScenarioIsAllowlisted() {
      Assertions.assertNull(PfTestRunner.validateScenario("campaign_recorded"));
   }
}
