package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.InteractionSpec;
import haven.nav.NavPlanStatus;
import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Door-side selection must be driven by the player's live approach position.
 * Passing the target/door CENTER as the side hint picks the wrong doorway on
 * multi-door buildings and biases single-door houses to the wrong face.
 */
public class DoorApproachSideTest {
   private static PrototypePathfinder.GobGeom gob(String resid, long id, Coord2d rc, double a) {
      PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
      g.id = id;
      g.resid = resid;
      g.rc = rc;
      g.a = a;
      return g;
   }

   /** House interior west of {@code wallX}, solid wall column with a doorway gap between gapY0..gapY1. */
   private static OccupancyGrid houseWall(int w, int h, int wallX, int gapY0, int gapY1) {
      boolean[] solid = new boolean[w * h];
      boolean[] dilated = new boolean[w * h];
      for (int y = 0; y < h; y++) {
         for (int x = 0; x < wallX; x++) {
            solid[y * w + x] = true;
            dilated[y * w + x] = true;
         }
      }
      for (int y = 0; y < h; y++) {
         if (y < gapY0 || y > gapY1) {
            solid[y * w + wallX] = true;
            dilated[y * w + wallX] = true;
         }
      }
      return OccupancyGrid.capture(
         Coord2d.of(0.0, 0.0), w, h, 2.75, solid, dilated, dilated,
         Coord.of(w - 2, gapY0), Coord.of(w - 2, gapY0), Coord.of(w - 2, gapY0), Collections.emptyList()
      );
   }

   @Test
   void multiDoorGobFollowsThePlayerNotTheDoorCenter() {
      // greathall-door carries three doorways at offsets (0,-30),(0,0),(0,30)
      Coord2d rc = Coord2d.of(1000.0, 2000.0);
      PrototypePathfinder.GobGeom g = gob("gfx/terobjs/arch/greathall-door", 7L, rc, 0.0);
      Coord2d player = Coord2d.of(1000.0, 1880.0); // north of the building
      BuildingDoor.Target t = BuildingDoor.target(g, player);
      Assertions.assertNotNull(t);
      Assertions.assertEquals(1000.0, t.origin.x, 1.0E-6);
      Assertions.assertEquals(1970.0, t.origin.y, 1.0E-6, "must pick the north doorway nearest the player");
      // the old behavior (hint = the gob center) picked the middle doorway instead
      BuildingDoor.Target centerHint = BuildingDoor.target(g, rc);
      Assertions.assertEquals(2000.0, centerHint.origin.y, 1.0E-6, "documents the pre-fix behavior");
      // and the interaction spec built the same way is the player-side doorway
      InteractionSpec spec = InteractionAdapter.fromGob(
         g, InteractionSpec.ALL_SIDES, 1.0, 35.0, 1, null, InteractionVerifier.STATE_CHANGED, player
      );
      Assertions.assertEquals(1970.0, spec.origin.y, 1.0E-6);
   }

   @Test
   void rotatedHallDoorFollowsThePlayerAroundTheCorner() {
      // greathall at a=pi/2: east-face doorways (77,-28),(77,0),(77,28) rotate to
      // (28,77),(0,77),(-28,77) on the south face. A player at the southeast
      // corner must get the corner doorway, not the one nearest the hull center.
      Coord2d hall = Coord2d.of(0.0, 0.0);
      PrototypePathfinder.GobGeom g = gob("gfx/terobjs/arch/greathall", 11L, hall, Math.PI / 2.0);
      Coord2d player = Coord2d.of(40.0, 120.0);
      BuildingDoor.Target t = BuildingDoor.target(g, player);
      Assertions.assertNotNull(t);
      Assertions.assertEquals(28.0, t.origin.x, 1.0E-6);
      Assertions.assertEquals(77.0, t.origin.y, 1.0E-6);
      // the old center hint picked the geometrically-middle doorway
      BuildingDoor.Target centerHint = BuildingDoor.target(g, hall);
      Assertions.assertEquals(0.0, centerHint.origin.x, 1.0E-6, "documents the pre-fix behavior");
      Assertions.assertEquals(77.0, centerHint.origin.y, 1.0E-6);
   }

   @Test
   void standIsOnThePlayersSideOfTheWallAndReachable() {
      int wallX = 20;
      OccupancyGrid occ = houseWall(40, 32, wallX, 15, 16);
      PrototypePathfinder.GobGeom door = gob("gfx/terobjs/arch/greathall-door", 21L, occ.world(wallX, 15), 0.0);
      Coord2d player = occ.world(24, 15); // east of the wall
      InteractionSpec spec = InteractionAdapter.fromGob(
         door, InteractionSpec.ALL_SIDES, 1.0, 35.0, 0, null, InteractionVerifier.STATE_CHANGED, player
      );
      // player hint keeps the doorway at the gap in the wall
      Assertions.assertEquals(occ.world(wallX, 15).x, spec.origin.x, 0.01);
      Assertions.assertEquals(occ.world(wallX, 15).y, spec.origin.y, 0.01);
      InteractionGoals.Result r = InteractionGoals.select(player, spec, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertTrue(r.selected.reachable);
      Assertions.assertNotNull(r.selected.plan);
      Assertions.assertEquals(NavPlanStatus.REACHED, r.selected.plan.status);
      Assertions.assertTrue(
         r.selected.world.x > occ.world(wallX, 15).x,
         "stand must be on the player's (east) side of the wall, got " + r.selected.world
      );
      // a center-like hint far north along the wall moves the doorway away from
      // the gap — exactly the pre-fix flaw when the target center was used
      InteractionSpec wrongHint = InteractionAdapter.fromGob(
         door, InteractionSpec.ALL_SIDES, 1.0, 35.0, 0, null, InteractionVerifier.STATE_CHANGED, occ.world(wallX, 4)
      );
      Assertions.assertTrue(
         wrongHint.origin.y < spec.origin.y - 20.0,
         "a hint away from the player moves the doorway, got " + wrongHint.origin
      );
   }
}
