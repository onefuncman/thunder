package haven.pathfinding;

import haven.Coord2d;
import haven.nav.InteractionSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class InteractionAdapterTest {
   @Test
   void footprintFromPolygonsBecomesAabb() {
      PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
      g.id = 42L;
      g.rc = Coord2d.of(100.0, 50.0);
      List<Coord2d[]> polys = new ArrayList<>();
      polys.add(new Coord2d[]{
         Coord2d.of(95.0, 45.0),
         Coord2d.of(105.0, 45.0),
         Coord2d.of(105.0, 55.0),
         Coord2d.of(95.0, 55.0)
      });
      g.movement = polys;
      InteractionSpec spec = InteractionAdapter.fromGob(g, InteractionSpec.ALL_SIDES, 0.5, 16.5, 0, null, InteractionVerifier.WINDOW_OPENED);
      Assertions.assertEquals("42", spec.targetId);
      Assertions.assertEquals(100.0, spec.origin.x, 1.0E-9);
      Assertions.assertEquals(50.0, spec.origin.y, 1.0E-9);
      Assertions.assertEquals(5.0, spec.half.x, 1.0E-9);
      Assertions.assertEquals(5.0, spec.half.y, 1.0E-9);
      Assertions.assertEquals(InteractionVerifier.WINDOW_OPENED, spec.expectedResult);
      Assertions.assertEquals(CollisionGeom.MOVEMENT, spec.geometrySource);
      Assertions.assertEquals(1, spec.polygons.size());
   }

   @Test
   void missingPolygonsFallBackToDefaultHalf() {
      PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
      g.id = 7L;
      g.rc = Coord2d.of(1.0, 2.0);
      InteractionSpec spec = InteractionAdapter.fromGob(g, InteractionSpec.SIDE_E, 2.0, 11.0, 1, null, InteractionVerifier.TARGET_GONE);
      Assertions.assertEquals(5.5, spec.half.x, 1.0E-9);
      Assertions.assertEquals(InteractionSpec.SIDE_E, spec.allowedSides);
      Assertions.assertEquals(CollisionGeom.UNAVAILABLE, spec.geometrySource);
   }

   @Test
   void fromFootprintBuildsCandidateGoal() {
      InteractionSpec spec = InteractionAdapter.fromFootprint(
         "cup-1", Coord2d.of(0.0, 0.0), Coord2d.of(5.5, 5.5), InteractionSpec.ALL_SIDES, 0.5, 16.5, 0, null, InteractionVerifier.WINDOW_OPENED
      );
      Assertions.assertEquals("cup-1", spec.targetId);
      Assertions.assertEquals(0.5, spec.minDist, 1.0E-9);
   }

   @Test
   void interactScenarioWithoutGameFailsClosed() throws Exception {
      InteractScenario s = new InteractScenario(InteractScenario.Kind.FORAGE);
      org.json.JSONObject r = s.execute(new PfTestRunner.Run("interact_forageable"), null);
      Assertions.assertEquals("FAIL", r.getString("verdict"));
      Assertions.assertEquals("NO_GAME", r.getJSONObject("facts").getString("refusal"));
   }
}
