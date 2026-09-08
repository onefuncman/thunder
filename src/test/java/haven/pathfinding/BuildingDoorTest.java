package haven.pathfinding;

import haven.Coord2d;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BuildingDoorTest {
   @Test
   void hullIsNotTheDoorGob() {
      Assertions.assertTrue(BuildingDoor.isHouseHull("gfx/terobjs/arch/greathall"));
      Assertions.assertTrue(BuildingDoor.isHouseHull("gfx/terobjs/arch/stonemansion"));
      Assertions.assertFalse(BuildingDoor.isHouseHull("gfx/terobjs/arch/greathall-door"));
      Assertions.assertFalse(BuildingDoor.isHouseHull("gfx/terobjs/arch/palisadegate"));
      Assertions.assertTrue(BuildingDoor.isDoorGob("gfx/terobjs/arch/greathall-door"));
      Assertions.assertFalse(BuildingDoor.isDoorGob("gfx/terobjs/arch/greathall"));
   }

   @Test
   void doorPointIsOnTheFacadeNotTheRoofCenter() {
      Coord2d hall = Coord2d.of(1000.0, 2000.0);
      Coord2d door = BuildingDoor.nearestDoorWorld("gfx/terobjs/arch/greathall", hall, 0.0, hall.add(77.0, 0.0));
      Assertions.assertNotNull(door);
      Assertions.assertEquals(1077.0, door.x, 1.0E-6);
      Assertions.assertEquals(2000.0, door.y, 1.0E-6);
      Assertions.assertTrue(door.dist(hall) > 50.0, "must not use the hall origin");
   }

   @Test
   void nearestOfThreeGreatHallDoorsFollowsThePlayer() {
      Coord2d hall = Coord2d.of(0.0, 0.0);
      Coord2d south = BuildingDoor.nearestDoorWorld("gfx/terobjs/arch/greathall", hall, 0.0, Coord2d.of(80.0, 40.0));
      Assertions.assertEquals(28.0, south.y, 1.0E-6);
      Coord2d north = BuildingDoor.nearestDoorWorld("gfx/terobjs/arch/greathall", hall, 0.0, Coord2d.of(80.0, -40.0));
      Assertions.assertEquals(-28.0, north.y, 1.0E-6);
   }

   @Test
   void houseInteractionSpecIsTheDoorNotTheBuildingAabb() {
      PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
      g.id = 9L;
      g.resid = "gfx/terobjs/arch/greathall";
      g.rc = Coord2d.of(0.0, 0.0);
      g.a = 0.0;
      List<Coord2d[]> hull = new ArrayList<Coord2d[]>();
      hull.add(new Coord2d[]{
         Coord2d.of(-80.0, -40.0), Coord2d.of(80.0, -40.0), Coord2d.of(80.0, 40.0), Coord2d.of(-80.0, 40.0)
      });
      g.obst = hull;
      haven.nav.InteractionSpec spec = InteractionAdapter.fromGob(
         g, haven.nav.InteractionSpec.ALL_SIDES, 1.0, 35.0, 1, null, InteractionVerifier.STATE_CHANGED, Coord2d.of(80.0, 0.0)
      );
      Assertions.assertTrue(spec.half.x <= 8.0 && spec.half.y <= 8.0, "half=" + spec.half);
      Assertions.assertTrue(spec.origin.dist(g.rc) > 50.0);
      Assertions.assertTrue(spec.origin.x > 50.0);
   }

   @Test
   void stoneMansionTargetIsTheDoorNotTheHull() {
      Coord2d hull = Coord2d.of(100.0, 200.0);
      BuildingDoor.Target t = BuildingDoor.target("gfx/terobjs/arch/stonemansion", hull, 0.0, 9L, hull.add(48.0, 3.5));
      Assertions.assertNotNull(t);
      Assertions.assertEquals(148.0, t.origin.x, 1.0E-6);
      Assertions.assertEquals(203.5, t.origin.y, 1.0E-6);
      Assertions.assertEquals(16, t.mesh);
      Assertions.assertTrue(t.half.x <= 6.0 && t.half.y <= 6.0);
      PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
      g.id = 9L;
      g.resid = "gfx/terobjs/arch/stonemansion";
      g.rc = hull;
      g.a = 0.0;
      List<Coord2d[]> obst = new ArrayList<Coord2d[]>();
      obst.add(new Coord2d[]{
         Coord2d.of(20.0, 140.0), Coord2d.of(180.0, 140.0), Coord2d.of(180.0, 260.0), Coord2d.of(20.0, 260.0)
      });
      g.obst = obst;
      haven.nav.InteractionSpec spec = InteractionAdapter.fromGob(
         g, haven.nav.InteractionSpec.ALL_SIDES, 1.0, 35.0, 1, null, InteractionVerifier.STATE_CHANGED, hull.add(48.0, 3.5)
      );
      Assertions.assertEquals(t.origin.x, spec.origin.x, 1.0E-6);
      Assertions.assertTrue(spec.half.x <= 6.0);
      Assertions.assertEquals(CollisionGeom.FALLBACK, spec.geometrySource);
      Coord2d live = BuildingDoor.liveOrigin(g, spec);
      Assertions.assertEquals(spec.origin.x, live.x, 1.0E-6);
      Assertions.assertTrue(live.dist(g.rc) > 40.0);
   }

   @Test
   void scenePrefersTheDoorGobOverTheHall() {
      PrototypePathfinder.Scene scene = new PrototypePathfinder.Scene();
      scene.player = Coord2d.of(90.0, 0.0);
      scene.gobs = new ArrayList<PrototypePathfinder.GobGeom>();
      PrototypePathfinder.GobGeom hall = new PrototypePathfinder.GobGeom();
      hall.id = 1L;
      hall.resid = "gfx/terobjs/arch/greathall";
      hall.rc = Coord2d.of(0.0, 0.0);
      PrototypePathfinder.GobGeom door = new PrototypePathfinder.GobGeom();
      door.id = 2L;
      door.resid = "gfx/terobjs/arch/greathall-door";
      door.rc = Coord2d.of(77.0, 0.0);
      scene.gobs.add(hall);
      scene.gobs.add(door);
      PrototypePathfinder.GobGeom picked = BuildingDoor.preferDoor(scene, hall);
      Assertions.assertEquals(2L, picked.id);
      Assertions.assertEquals("gfx/terobjs/arch/greathall-door", picked.resid);
   }

   @Test
   void resolveDoorPrefersTheDoorGobInScene() {
      PrototypePathfinder.Scene scene = new PrototypePathfinder.Scene();
      scene.player = Coord2d.of(90.0, 0.0);
      scene.gobs = new ArrayList<PrototypePathfinder.GobGeom>();
      PrototypePathfinder.GobGeom hall = new PrototypePathfinder.GobGeom();
      hall.id = 1L;
      hall.resid = "gfx/terobjs/arch/greathall";
      hall.rc = Coord2d.of(0.0, 0.0);
      PrototypePathfinder.GobGeom door = new PrototypePathfinder.GobGeom();
      door.id = 2L;
      door.resid = "gfx/terobjs/arch/greathall-door";
      door.rc = Coord2d.of(77.0, 0.0);
      scene.gobs.add(hall);
      scene.gobs.add(door);
      PrototypePathfinder.GobGeom picked = BuildingDoor.resolveDoor(null, scene, hall);
      Assertions.assertEquals(2L, picked.id);
      Assertions.assertEquals("gfx/terobjs/arch/greathall-door", picked.resid);
   }

   @Test
   void resolveDoorKeepsHullWhenNoDoorObservable() {
      PrototypePathfinder.Scene scene = new PrototypePathfinder.Scene();
      scene.player = Coord2d.of(90.0, 0.0);
      scene.gobs = new ArrayList<PrototypePathfinder.GobGeom>();
      PrototypePathfinder.GobGeom hall = new PrototypePathfinder.GobGeom();
      hall.id = 1L;
      hall.resid = "gfx/terobjs/arch/greathall";
      hall.rc = Coord2d.of(0.0, 0.0);
      scene.gobs.add(hall);
      PrototypePathfinder.GobGeom picked = BuildingDoor.resolveDoor(null, scene, hall);
      Assertions.assertEquals(1L, picked.id);
      Assertions.assertEquals("gfx/terobjs/arch/greathall", picked.resid);
   }

   @Test
   void resolveDoorIgnoresDoorGobFromAnotherBuildingFamily() {
      PrototypePathfinder.Scene scene = new PrototypePathfinder.Scene();
      scene.player = Coord2d.of(0.0, 0.0);
      scene.gobs = new ArrayList<PrototypePathfinder.GobGeom>();
      PrototypePathfinder.GobGeom hall = new PrototypePathfinder.GobGeom();
      hall.id = 1L;
      hall.resid = "gfx/terobjs/arch/greathall";
      hall.rc = Coord2d.of(0.0, 0.0);
      // A stone hut door far outside the greathall search radius must not be
      // mistaken for the greathall's own door.
      PrototypePathfinder.GobGeom otherDoor = new PrototypePathfinder.GobGeom();
      otherDoor.id = 3L;
      otherDoor.resid = "gfx/terobjs/arch/stonehut-door";
      otherDoor.rc = Coord2d.of(500.0, 0.0);
      scene.gobs.add(hall);
      scene.gobs.add(otherDoor);
      PrototypePathfinder.GobGeom picked = BuildingDoor.resolveDoor(null, scene, hall);
      Assertions.assertEquals(1L, picked.id);
      Assertions.assertEquals("gfx/terobjs/arch/greathall", picked.resid);
   }

   @Test
   void standoffPushesDoorwayAwayFromHullCentre() {
      Coord2d hull = Coord2d.of(0.0, 0.0);
      Coord2d door = Coord2d.of(48.0, 3.5);
      Coord2d out = BuildingDoor.standoff(door, hull, BuildingDoor.DOOR_APPROACH_U);
      // Outward along the hull-centre-to-door ray, past the door, never back toward the hull.
      Assertions.assertTrue(out.dist(hull) > door.dist(hull));
      double ray = Math.hypot(48.0, 3.5);
      Assertions.assertEquals(48.0 + 48.0 / ray * BuildingDoor.DOOR_APPROACH_U, out.x, 1.0E-6);
      Assertions.assertEquals(3.5 + 3.5 / ray * BuildingDoor.DOOR_APPROACH_U, out.y, 1.0E-6);
   }

   @Test
   void standoffDegenerateRayKeepsDoor() {
      Coord2d door = Coord2d.of(5.0, 5.0);
      Assertions.assertSame(door, BuildingDoor.standoff(door, door, BuildingDoor.DOOR_APPROACH_U));
   }

   @Test
   void beforePointStopsShortOfDoorOnApproachLine() {
      Coord2d from = Coord2d.of(100.0, 0.0);
      Coord2d door = Coord2d.of(0.0, 0.0);
      Coord2d p = BuildingDoor.beforePoint(from, door, BuildingDoor.DOOR_APPROACH_U);
      Assertions.assertEquals(BuildingDoor.DOOR_APPROACH_U, p.x, 1.0E-6);
      Assertions.assertEquals(0.0, p.y, 1.0E-6);
      Assertions.assertTrue(p.dist(from) < from.dist(door));
   }
}
