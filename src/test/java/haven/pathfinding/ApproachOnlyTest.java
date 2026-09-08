package haven.pathfinding;

import haven.Coord2d;
import haven.nav.InteractionSpec;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

public class ApproachOnlyTest {
   @Test
   void approachLegRoundTripsAndDoesNotBecomeATransition(@TempDir Path dir) throws Exception {
      CriticalRouteBook.overrideDir = dir;
      try {
         CriticalRouteBook b = new CriticalRouteBook("cupboard_row");
         b.addStand(Coord2d.of(10.0, 20.0), "house");
         b.legs.add(new CriticalRouteBook.Leg(
            ApproachOnly.KIND, Coord2d.of(30.0, 40.0), 11L, "gfx/terobjs/cupboard", ApproachOnly.ROLE, "house", true, null
         ));
         b.legs.add(new CriticalRouteBook.Leg(
            "gob", Coord2d.of(50.0, 60.0), 12L, "gfx/terobjs/arch/downstairs", "gob", "house"
         ));
         b.save();
         CriticalRouteBook loaded = CriticalRouteBook.load("cupboard_row");
         Assertions.assertEquals(3, loaded.legs.size());
         Assertions.assertTrue(CriticalRouteBook.isGround(loaded.legs.get(0)));
         Assertions.assertTrue(CriticalRouteBook.isApproach(loaded.legs.get(1)));
         Assertions.assertEquals(ApproachOnly.KIND, loaded.legs.get(1).kind);
         Assertions.assertEquals(ApproachOnly.ROLE, loaded.legs.get(1).role);
         Assertions.assertEquals("approach_only gfx/terobjs/cupboard · floor house", loaded.legs.get(1).label());
         Assertions.assertNull(RecordedRouteScenario.transitionKind(loaded.legs.get(1)));
         Assertions.assertEquals(TransitionScenario.Kind.CELLAR_STAIRS, RecordedRouteScenario.transitionKind(loaded.legs.get(2)));
         JSONObject json = loaded.toJson().getJSONArray("legs").getJSONObject(1);
         Assertions.assertEquals("approach", json.getString("kind"));
         Assertions.assertEquals("approach_only", json.getString("role"));
      } finally {
         CriticalRouteBook.overrideDir = null;
      }
   }

   @Test
   void roleApproachOnlyNormalizesKindOnLoad() {
      JSONObject o = new JSONObject()
         .put("kind", "gob")
         .put("role", "approach_only")
         .put("gob_id", 4L)
         .put("resid", "gfx/terobjs/cheeserack")
         .put("x", 1.0)
         .put("y", 2.0)
         .put("seg", "house");
      CriticalRouteBook.Leg leg = CriticalRouteBook.Leg.fromJson(o);
      Assertions.assertTrue(CriticalRouteBook.isApproach(leg));
      Assertions.assertEquals(ApproachOnly.KIND, leg.kind);
      Assertions.assertNull(RecordedRouteScenario.transitionKind(leg));
   }

   @Test
   void savedObjectStairsRemainTransitions() {
      CriticalRouteBook.Leg stairs = new CriticalRouteBook.Leg(
         "gob", Coord2d.of(2, 2), 9L, "gfx/terobjs/arch/cellarstairs", "cellar_stairs", "cellar"
      );
      CriticalRouteBook.Leg approachStairs = new CriticalRouteBook.Leg(
         ApproachOnly.KIND, Coord2d.of(2, 2), 9L, "gfx/terobjs/arch/cellarstairs", ApproachOnly.ROLE, "cellar"
      );
      Assertions.assertEquals(TransitionScenario.Kind.CELLAR_STAIRS, RecordedRouteScenario.transitionKind(stairs));
      Assertions.assertNull(RecordedRouteScenario.transitionKind(approachStairs), "Approach must not run the stairs transition");
      Assertions.assertFalse(CriticalRouteBook.isApproach(stairs));
   }

   @Test
   void genericSpecIsNotAnAction() {
      PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
      g.id = 3L;
      g.resid = "gfx/terobjs/cupboard";
      g.rc = Coord2d.of(100.0, 50.0);
      List<Coord2d[]> obst = new ArrayList<Coord2d[]>();
      obst.add(new Coord2d[]{
         Coord2d.of(95.0, 45.0), Coord2d.of(105.0, 45.0), Coord2d.of(105.0, 55.0), Coord2d.of(95.0, 55.0)
      });
      g.obst = obst;
      InteractionSpec spec = ApproachOnly.spec(g, g.rc);
      Assertions.assertEquals("", spec.expectedResult);
      Assertions.assertEquals(0.5, spec.minDist, 1.0E-9);
      Assertions.assertEquals(16.5, spec.maxDist, 1.0E-9);
      Assertions.assertEquals(CollisionGeom.OBST, spec.geometrySource);
      Assertions.assertEquals(100.0, spec.origin.x, 1.0E-9);
   }

   @Test
   void revalidateRequiresTheSameTargetAndReportsNoInteraction() {
      PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
      g.id = 3L;
      g.resid = "gfx/terobjs/barrel";
      g.rc = Coord2d.of(10.0, 10.0);
      List<Coord2d[]> obst = new ArrayList<Coord2d[]>();
      obst.add(new Coord2d[]{
         Coord2d.of(4.5, 4.5), Coord2d.of(15.5, 4.5), Coord2d.of(15.5, 15.5), Coord2d.of(4.5, 15.5)
      });
      g.obst = obst;
      InteractionSpec spec = ApproachOnly.spec(g, g.rc);
      Coord2d pose = Coord2d.of(22.0, 10.0);
      Assertions.assertEquals(
         ApproachOnly.Outcome.TARGET_DISAPPEARED,
         ApproachOnly.revalidate(pose, pose, spec, false, spec.origin)
      );
      Assertions.assertEquals(
         ApproachOnly.Outcome.TARGET_MOVED,
         ApproachOnly.revalidate(pose, pose, spec, true, Coord2d.of(80.0, 10.0))
      );
      Assertions.assertEquals(
         ApproachOnly.Outcome.APPROACH_READY,
         ApproachOnly.revalidate(pose, pose, spec, true, spec.origin)
      );
      ApproachOnly.Result ready = new ApproachOnly.Result(ApproachOnly.Outcome.APPROACH_READY, pose, spec, "");
      Assertions.assertFalse(ready.interacted);
      Assertions.assertEquals("PASS", PfTestRunner.verdictOf(java.util.Collections.singletonList(ApproachOnly.check(ready))));
   }

   @Test
   void planFailuresMapOntoThePublishedOutcomes() {
      Assertions.assertEquals(ApproachOnly.Outcome.UNREACHABLE, ApproachOnly.mapPlan(ApproachGoals.Status.UNREACHABLE));
      Assertions.assertEquals(ApproachOnly.Outcome.NO_VALID_POSE, ApproachOnly.mapPlan(ApproachGoals.Status.NO_VALID_POSE));
      Assertions.assertEquals(ApproachOnly.Outcome.GEOMETRY_UNAVAILABLE, ApproachOnly.mapPlan(ApproachGoals.Status.GEOMETRY_UNAVAILABLE));
   }
}
