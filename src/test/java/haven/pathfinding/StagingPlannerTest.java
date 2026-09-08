package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Headless checks for distant-object staging: requirement detection and pose selection. */
class StagingPlannerTest {
   private static final double CELL = 2.75;

   private static OccupancyGrid grid(Coord2d center, int w, int h) {
      return new OccupancyGrid(
         center.sub(w * CELL / 2.0, h * CELL / 2.0), w, h, CELL, new byte[w * h], null, null, null, null
      );
   }

   private static void solidBlob(OccupancyGrid grid, Coord2d at, int r) {
      Coord c = grid.cellOf(at);
      for (int dx = -r; dx <= r; dx++) {
         for (int dy = -r; dy <= r; dy++) {
            grid.occ[(c.y + dy) * grid.w + (c.x + dx)] = OccupancyGrid.SOLID;
         }
      }
   }

   private static haven.nav.InteractionSpec spec(Coord2d origin) {
      return InteractionAdapter.fromFootprint(
         "t-1", origin, Coord2d.of(2.0, 2.0), haven.nav.InteractionSpec.ALL_SIDES, 0.5, 16.5, 0, null,
         InteractionVerifier.STATE_CHANGED
      );
   }

   @Test
   void nearbyTargetInsideFreshGridDoesNotNeedStaging() {
      Coord2d player = Coord2d.of(20.0, 0.0);
      Coord2d target = Coord2d.of(40.0, 0.0);
      OccupancyGrid occ = grid(player, 72, 72);
      haven.nav.InteractionSpec spec = spec(target);
      Assertions.assertFalse(StagingPlanner.required(player, occ, spec), "nearby target within planning range");
   }

   @Test
   void targetOutsideObservedGridRequiresStaging() {
      Coord2d player = Coord2d.of(0.0, 0.0);
      Coord2d target = Coord2d.of(130.0, 0.0); // beyond the 198-unit grid centered on the player
      OccupancyGrid occ = grid(player, 72, 72);
      haven.nav.InteractionSpec spec = spec(target);
      Assertions.assertTrue(
         StagingPlanner.required(player, occ, spec), "missing distant geometry must route through staging"
      );
   }

   @Test
   void missingOccupancyRequiresStaging() {
      Coord2d player = Coord2d.of(0.0, 0.0);
      Assertions.assertTrue(
         StagingPlanner.required(player, null, spec(Coord2d.of(10.0, 0.0))), "no occupancy grid means stage first"
      );
   }

   @Test
   void inGridDistinguishesInsideFromOutside() {
      Coord2d player = Coord2d.of(0.0, 0.0);
      OccupancyGrid occ = grid(player, 72, 72);
      Assertions.assertTrue(StagingPlanner.inGrid(occ, Coord2d.of(0.0, 0.0)), "player cell in grid");
      Assertions.assertTrue(StagingPlanner.inGrid(occ, Coord2d.of(90.0, 0.0)), "within the 99u half-extent");
      Assertions.assertFalse(StagingPlanner.inGrid(occ, Coord2d.of(130.0, 0.0)), "beyond the east edge");
      Assertions.assertFalse(StagingPlanner.inGrid(occ, Coord2d.of(0.0, -120.0)), "beyond the south edge");
      Assertions.assertFalse(StagingPlanner.inGrid(null, Coord2d.of(0.0, 0.0)), "null grid");
   }

   @Test
   void outOfGridTargetIsNotContained() {
      Coord2d player = Coord2d.of(0.0, 0.0);
      OccupancyGrid occ = grid(player, 72, 72);
      haven.nav.InteractionSpec spec = spec(Coord2d.of(130.0, 0.0));
      Assertions.assertFalse(StagingPlanner.containsTarget(occ, spec), "target beyond the grid cannot be contained");
      Assertions.assertTrue(StagingPlanner.containsTarget(occ, spec(Coord2d.of(40.0, 0.0))), "nearby target contained");
   }

   @Test
   void stagingSelectsWalkablePointOutsideTargetCollisionNotTargetCenter() {
      Coord2d player = Coord2d.of(0.0, 0.0);
      Coord2d target = Coord2d.of(40.0, 0.0);
      OccupancyGrid occ = grid(player, 72, 72);
      solidBlob(occ, target, 2);
      StagingPlanner.Result r = StagingPlanner.select(player, spec(target), occ);
      Assertions.assertTrue(r.ok(), "staging candidate found, reason=" + r.reason);
      double standoff = Math.max(2.0, 2.0) + LocalPlanner.DEFAULT_AGENT_RADIUS + StagingPlanner.SAFETY_MARGIN;
      Assertions.assertTrue(
         r.selected.world.dist(target) >= standoff - 1.0E-9,
         "candidate outside expanded target collision: " + r.selected.world.dist(target)
      );
      Coord cell = occ.cellOf(r.selected.world);
      Assertions.assertNotNull(cell);
      Assertions.assertEquals(OccupancyGrid.FREE, occ.at(cell.x, cell.y), "candidate cell walkable");
      Assertions.assertTrue(Double.isFinite(r.selected.cost), "candidate reachable from player");
   }

   @Test
   void stagingSelectionIsDeterministic() {
      Coord2d player = Coord2d.of(0.0, 0.0);
      Coord2d target = Coord2d.of(40.0, 0.0);
      OccupancyGrid occ = grid(player, 72, 72);
      solidBlob(occ, target, 2);
      StagingPlanner.Result a = StagingPlanner.select(player, spec(target), occ);
      StagingPlanner.Result b = StagingPlanner.select(player, spec(target), occ);
      Assertions.assertEquals(a.selected.world, b.selected.world);
      Assertions.assertEquals(a.selected.angle, b.selected.angle, 1.0E-9);
   }

   @Test
   void stagingFallsBackToArcWhenFacingRayOccupied() {
      Coord2d player = Coord2d.of(0.0, 0.0);
      Coord2d target = Coord2d.of(40.0, 0.0);
      OccupancyGrid occ = grid(player, 72, 72);
      solidBlob(occ, target, 2);
      // Block the player-facing band west of the target so the ray candidate
      // is occupied and the pick must fall back to the angular arc.
      Coord tc = occ.cellOf(target);
      for (int x = tc.x - 5; x <= tc.x - 1; x++) {
         for (int y = tc.y - 2; y <= tc.y + 2; y++) {
            occ.occ[y * occ.w + x] = OccupancyGrid.SOLID;
         }
      }
      StagingPlanner.Result r = StagingPlanner.select(player, spec(target), occ);
      Assertions.assertTrue(r.ok(), "arc alternative found, reason=" + r.reason);
      Assertions.assertNotEquals(0.0, r.selected.angle, 1.0E-9, "facing-ray candidate occupied, arc used");
      Assertions.assertTrue(Double.isFinite(r.selected.cost), "arc alternative reachable");
   }

   @Test
   void edgeStagingTowardUnseenTargetProgressesAndStaysInGrid() {
      Coord2d player = Coord2d.of(0.0, 0.0);
      Coord2d target = Coord2d.of(130.0, 0.0);
      OccupancyGrid occ = grid(player, 72, 72);
      StagingPlanner.Result r = StagingPlanner.selectToward(player, target, occ);
      Assertions.assertTrue(r.ok(), "conservative edge staging found, reason=" + r.reason);
      Coord cell = occ.cellOf(r.selected.world);
      Assertions.assertNotNull(cell, "staging point inside the observed region");
      Assertions.assertTrue(
         r.selected.world.dist(target) < player.dist(target), "staging point progresses toward the target"
      );
      Assertions.assertEquals(OccupancyGrid.FREE, occ.at(cell.x, cell.y));
   }

   @Test
   void unreachableEnclosedTargetFailsCleanly() {
      Coord2d player = Coord2d.of(0.0, 0.0);
      Coord2d target = Coord2d.of(40.0, 0.0);
      OccupancyGrid occ = grid(player, 72, 72);
      // Fully seal the target so no staging coordinate is reachable.
      int cx = occ.cellOf(target).x;
      int cy = occ.cellOf(target).y;
      for (int x = cx - 6; x <= cx + 6; x++) {
         for (int y = cy - 6; y <= cy + 6; y++) {
            occ.occ[y * occ.w + x] = OccupancyGrid.SOLID;
         }
      }
      StagingPlanner.Result r = StagingPlanner.select(player, spec(target), occ);
      Assertions.assertFalse(r.ok());
      Assertions.assertEquals(StagingPlanner.STAGING_UNREACHABLE, r.reason);
   }
}
