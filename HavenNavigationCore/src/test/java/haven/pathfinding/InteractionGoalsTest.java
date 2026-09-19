package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.InteractionSpec;
import haven.nav.NavPlanStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class InteractionGoalsTest {
   @Test
   public void stablePortsProduceAtMostOneCandidatePerSide() {
      OccupancyGrid occ = open(21, 21);
      InteractionSpec ordinary = new InteractionSpec(
         "log", occ.world(10, 10), Coord2d.of(11.0, 2.75), InteractionSpec.ALL_SIDES,
         0.5, 16.5, 0, null, "", null, "fallback", false, 0, true
      );
      List<Coord2d> ports = InteractionGoals.samplePoses(ordinary, occ);
      Assertions.assertEquals(4, ports.size());
      java.util.Set<Integer> sides = new java.util.HashSet<Integer>();
      for (Coord2d port : ports) sides.add(InteractionGoals.sideOf(ordinary.origin, port));
      Assertions.assertEquals(4, sides.size());
   }

   @Test
   public void stablePortsKeepAllFourSidesForARotatedFootprint() {
      OccupancyGrid occ = open(21, 21);
      Coord2d origin = occ.world(10, 10);
      List<Coord2d[]> diamond = Collections.singletonList(new Coord2d[]{
         origin.add(0.0, -8.0), origin.add(8.0, 0.0),
         origin.add(0.0, 8.0), origin.add(-8.0, 0.0)
      });
      InteractionSpec ordinary = new InteractionSpec(
         "rotated", origin, Coord2d.of(8.0, 8.0), InteractionSpec.ALL_SIDES,
         0.5, 16.5, 0, null, "", diamond, "live", false, 0, true
      );
      List<Coord2d> ports = InteractionGoals.samplePoses(ordinary, occ);
      java.util.Set<Integer> sides = new java.util.HashSet<Integer>();
      for (Coord2d port : ports) sides.add(InteractionGoals.sideOf(origin, port));
      Assertions.assertEquals(4, ports.size());
      Assertions.assertEquals(4, sides.size());
   }

   private static OccupancyGrid open(int w, int h) {
      return OccupancyGrid.capture(
         Coord2d.of(0.0, 0.0), w, h, 2.75, new boolean[w * h], new boolean[w * h], new boolean[w * h], Coord.of(0, 0), Coord.of(w - 1, 0), Coord.of(w - 1, 0), Collections.emptyList()
      );
   }

   private static OccupancyGrid solids(int w, int h, Coord[] blocks, boolean dilate) {
      boolean[] solid = new boolean[w * h];
      boolean[] dilated = new boolean[w * h];
      for (int i = 0; i < blocks.length; i++) {
         Coord c = blocks[i];
         solid[c.y * w + c.x] = true;
         dilated[c.y * w + c.x] = true;
      }
      if (dilate) {
         LocalPlanner.dilate(dilated, w, h, 1);
      }
      return OccupancyGrid.capture(Coord2d.of(0.0, 0.0), w, h, 2.75, solid, dilated, dilated, Coord.of(0, 0), Coord.of(w - 1, 0), Coord.of(w - 1, 0), Collections.emptyList());
   }

   private static InteractionSpec spec(Coord2d origin, int sides, double min, double max, int clearance, Double facing) {
      return new InteractionSpec("t1", origin, Coord2d.of(1.375, 1.375), sides, min, max, clearance, facing, "target_gone");
   }

   @Test
   void targetRemainsObstacleAndCandidatesAreAroundFootprint() {
      OccupancyGrid occ = solids(20, 8, new Coord[]{Coord.of(10, 4)}, false);
      String before = OccupancyGrid.encode(occ);
      InteractionSpec s = spec(occ.world(10, 4), InteractionSpec.ALL_SIDES, 0.5, 8.0, 0, null);
      InteractionGoals.Result r = InteractionGoals.select(occ.world(2, 4), s, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertEquals(before, OccupancyGrid.encode(occ));
      Assertions.assertEquals(OccupancyGrid.SOLID, occ.at(10, 4));
      Assertions.assertFalse(InteractionGoals.overlapsFootprint(r.selected.world, s));
      Assertions.assertFalse(r.selected.cell.equals(Coord.of(10, 4)));
      int around = 0;
      for (int i = 0; i < r.considered.size(); i++) {
         InteractionGoals.Candidate c = r.considered.get(i);
         if (c.reject == null || "unreachable".equals(c.reject)) {
            Assertions.assertFalse(InteractionGoals.overlapsFootprint(c.world, s));
            around++;
         }
      }
      Assertions.assertTrue(around > 0);
   }

   @Test
   void minAndMaxInteractionDistance() {
      OccupancyGrid occ = solids(20, 8, new Coord[]{Coord.of(10, 4)}, false);
      InteractionSpec far = spec(occ.world(10, 4), InteractionSpec.ALL_SIDES, 3.0, 8.0, 0, null);
      InteractionGoals.Result r = InteractionGoals.select(occ.world(2, 4), far, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertTrue(r.selected.dist + 1.0E-6 >= 3.0);
      InteractionSpec near = spec(occ.world(10, 4), InteractionSpec.ALL_SIDES, 0.5, 2.0, 0, null);
      InteractionGoals.Result n = InteractionGoals.select(occ.world(2, 4), near, occ);
      Assertions.assertTrue(n.ok(), n.reason);
      Assertions.assertTrue(n.selected.dist - 1.0E-6 <= 2.0);
   }

   @Test
   void bodyClearanceRejectsDilatedRing() {
      OccupancyGrid occ = solids(20, 8, new Coord[]{Coord.of(10, 4)}, true);
      InteractionSpec s = spec(occ.world(10, 4), InteractionSpec.ALL_SIDES, 0.5, 11.0, 1, null);
      InteractionGoals.Result r = InteractionGoals.select(occ.world(2, 4), s, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertTrue(r.selected.clearance >= 1);
      Assertions.assertNotEquals(OccupancyGrid.DILATED, occ.at(r.selected.cell.x, r.selected.cell.y));
   }

   @Test
   void allowedAndDisallowedSides() {
      OccupancyGrid occ = solids(20, 8, new Coord[]{Coord.of(10, 4)}, false);
      InteractionSpec east = spec(occ.world(10, 4), InteractionSpec.SIDE_E, 0.5, 8.0, 0, null);
      InteractionGoals.Result r = InteractionGoals.select(occ.world(2, 4), east, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertEquals(InteractionSpec.SIDE_E, r.selected.side);
      int disallowed = 0;
      for (int i = 0; i < r.considered.size(); i++) {
         if ("wrong_side".equals(r.considered.get(i).reject) || "disallowed_side".equals(r.considered.get(i).reject)) {
            disallowed++;
         }
      }
      Assertions.assertTrue(disallowed > 0);
   }

   @Test
   void reachableSideWinsAndUnreachableNearestIsRejected() {
      OccupancyGrid occ = solids(20, 8, new Coord[]{
         Coord.of(10, 4),
         Coord.of(9, 5),
         Coord.of(11, 5),
         Coord.of(10, 6)
      }, false);
      InteractionSpec s = spec(occ.world(10, 4), InteractionSpec.SIDE_N | InteractionSpec.SIDE_S, 0.5, 8.0, 0, null);
      InteractionGoals.Result r = InteractionGoals.select(occ.world(2, 4), s, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertEquals(InteractionSpec.SIDE_N, r.selected.side);
      Assertions.assertFalse(r.selected.cell.equals(Coord.of(10, 5)));
      boolean southUnreachable = false;
      for (int i = 0; i < r.considered.size(); i++) {
         InteractionGoals.Candidate c = r.considered.get(i);
         if (c.cell.equals(Coord.of(10, 5)) && ("unreachable".equals(c.reject) || "unplanned".equals(c.reject))) {
            southUnreachable = true;
         }
      }
      Assertions.assertTrue(southUnreachable);
   }

   @Test
   void deterministicTieBreakRepeats() {
      OccupancyGrid occ = solids(20, 8, new Coord[]{Coord.of(10, 4)}, false);
      InteractionSpec s = spec(occ.world(10, 4), InteractionSpec.ALL_SIDES, 0.5, 8.0, 0, null);
      Coord2d from = occ.world(10, 0);
      InteractionGoals.Result a = InteractionGoals.select(from, s, occ);
      InteractionGoals.Result b = InteractionGoals.select(from, s, occ);
      Assertions.assertTrue(a.ok());
      Assertions.assertEquals(a.selected.cell, b.selected.cell);
   }

   @Test
   void exactGeometryUsesRouteCostWithinACloseStandBand() {
      InteractionGoals.Candidate farFace = new InteractionGoals.Candidate(
         Coord.of(12, 4), Coord2d.of(33.0, 11.0), InteractionSpec.SIDE_E, 0.5, 4, true, 40.0, null, null
      );
      InteractionGoals.Candidate nearFace = new InteractionGoals.Candidate(
         Coord.of(8, 4), Coord2d.of(22.0, 11.0), InteractionSpec.SIDE_W, 1.0, 4, true, 8.0, null, null
      );
      Assertions.assertTrue(
         InteractionGoals.compareReachable(nearFace, farFace, true, 0.5) < 0,
         "a marginally looser near-face stand must beat an expensive orbit to the far face"
      );
   }

   @Test
   void exactGeometryDoesNotChooseAConvenientButDistantStand() {
      InteractionGoals.Candidate close = new InteractionGoals.Candidate(
         Coord.of(10, 3), Coord2d.of(27.5, 8.25), InteractionSpec.SIDE_N, 0.5, 2, true, 30.0, null, null
      );
      InteractionGoals.Candidate distant = new InteractionGoals.Candidate(
         Coord.of(15, 4), Coord2d.of(41.25, 11.0), InteractionSpec.SIDE_E, 10.0, 8, true, 2.0, null, null
      );
      Assertions.assertTrue(
         InteractionGoals.compareReachable(close, distant, true, 0.5) < 0,
         "route cost must not turn the interaction range into a far-away stopping pose"
      );
   }

   @Test
   void nearestRouteWinsWithoutSoftClearancePenalty() {
      OccupancyGrid occ = solids(10, 9, new Coord[]{Coord.of(2, 1)}, false);
      Coord2d from = occ.world(0, 2);
      Coord near = Coord.of(3, 2);
      Coord far = Coord.of(0, 6);
      double[] weighted = LocalPlanner.occupancyCosts(from, occ);
      double[] lengths = LocalPlanner.occupancyRouteLengths(from, occ);
      double nearLength = lengths[near.y * occ.w + near.x];
      double farLength = lengths[far.y * occ.w + far.x];

      Assertions.assertTrue(nearLength < farLength,
         "the near candidate must have the shorter legal route");
      Assertions.assertTrue(weighted[near.y * occ.w + near.x] > weighted[far.y * occ.w + far.x],
         "the old soft-clearance score would have selected the farther candidate");

      InteractionGoals.Candidate nearCandidate = new InteractionGoals.Candidate(
         near, occ.world(near.x, near.y), InteractionSpec.SIDE_E, 0.5, 1, true, nearLength, null, null
      );
      InteractionGoals.Candidate farCandidate = new InteractionGoals.Candidate(
         far, occ.world(far.x, far.y), InteractionSpec.SIDE_S, 0.5, 8, true, farLength, null, null
      );
      Assertions.assertTrue(
         InteractionGoals.compareReachable(nearCandidate, farCandidate, true, 0.5) < 0,
         "a legal near side must beat a farther side even when the farther side has more open space"
      );
   }

   @Test
   void wallAdjacentTargetUsesOpenSide() {
      Coord[] blocks = new Coord[8 + 1];
      for (int y = 0; y < 8; y++) {
         blocks[y] = Coord.of(9, y);
      }
      blocks[8] = Coord.of(10, 4);
      OccupancyGrid occ = solids(20, 8, blocks, false);
      InteractionSpec s = spec(occ.world(10, 4), InteractionSpec.ALL_SIDES, 0.5, 8.0, 0, null);
      InteractionGoals.Result r = InteractionGoals.select(occ.world(16, 4), s, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertEquals(InteractionSpec.SIDE_E, r.selected.side);
   }

   @Test
   void narrowCorridorSelectsReachableStand() {
      Coord[] blocks = new Coord[20 * 8];
      int n = 0;
      for (int y = 0; y < 8; y++) {
         for (int x = 0; x < 20; x++) {
            if (y != 4 && !(x == 10 && y == 3)) {
               blocks[n++] = Coord.of(x, y);
            }
         }
      }
      Coord[] slim = Arrays.copyOf(blocks, n);
      OccupancyGrid occ = solids(20, 8, slim, false);
      InteractionSpec s = spec(occ.world(10, 3), InteractionSpec.ALL_SIDES, 0.5, 8.0, 0, null);
      InteractionGoals.Result r = InteractionGoals.select(occ.world(2, 4), s, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertEquals(4, r.selected.cell.y);
   }

   @Test
   void diagonalObstructionDoesNotPickBlockedCorner() {
      OccupancyGrid occ = solids(20, 8, new Coord[]{
         Coord.of(10, 4),
         Coord.of(11, 3),
         Coord.of(11, 5),
         Coord.of(12, 3),
         Coord.of(12, 5)
      }, false);
      InteractionSpec s = spec(occ.world(10, 4), InteractionSpec.ALL_SIDES, 0.5, 8.0, 0, null);
      InteractionGoals.Result r = InteractionGoals.select(occ.world(2, 4), s, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertFalse(r.selected.cell.equals(Coord.of(11, 3)));
      Assertions.assertFalse(r.selected.cell.equals(Coord.of(11, 5)));
   }

   @Test
   void optionalFacingFiltersOppositeSide() {
      OccupancyGrid occ = solids(20, 8, new Coord[]{Coord.of(10, 4)}, false);
      InteractionSpec s = spec(occ.world(10, 4), InteractionSpec.ALL_SIDES, 0.5, 8.0, 0, Double.valueOf(0.0));
      InteractionGoals.Result r = InteractionGoals.select(occ.world(2, 4), s, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertEquals(InteractionSpec.SIDE_W, r.selected.side);
      int facing = 0;
      for (int i = 0; i < r.considered.size(); i++) {
         if ("wrong_side".equals(r.considered.get(i).reject) || "facing".equals(r.considered.get(i).reject)) {
            facing++;
         }
      }
      Assertions.assertTrue(facing > 0);
   }

   @Test
   void clickThroughDeskPrefersTheNearSide() {
      int w = 24;
      int h = 12;
      List<Coord> blocks = new ArrayList<Coord>();
      blocks.add(Coord.of(18, 6));
      for (int y = 1; y < h; y++) {
         blocks.add(Coord.of(12, y));
         blocks.add(Coord.of(13, y));
      }
      OccupancyGrid occ = solids(w, h, blocks.toArray(new Coord[0]), false);
      Coord2d gob = occ.world(18, 6);
      Coord2d from = occ.world(4, 6);
      InteractionSpec s = spec(gob, InteractionSpec.ALL_SIDES, 0.5, 35.0, 0, null);
      Assertions.assertFalse(InteractionGoals.losClear(from, s, occ), "open-room click through the slab");
      Assertions.assertTrue(InteractionGoals.losClear(occ.world(15, 6), s, occ), "stand beside the gob has LOS");
      InteractionGoals.Result r = InteractionGoals.select(from, s, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertTrue(r.selected.cell.x > 13, "must stand on the gob side of the desk, not click through it");
      Assertions.assertTrue(InteractionGoals.losClear(r.selected.world, s, occ));
   }

   @Test
   void noValidPoseIsTypedFailure() {
      Coord[] wall = new Coord[20 * 8];
      int n = 0;
      for (int y = 0; y < 8; y++) {
         for (int x = 0; x < 20; x++) {
            if (x != 2 || y != 4) {
               wall[n++] = Coord.of(x, y);
            }
         }
      }
      OccupancyGrid occ = solids(20, 8, Arrays.copyOf(wall, n), false);
      InteractionSpec s = spec(occ.world(10, 4), InteractionSpec.ALL_SIDES, 0.5, 8.0, 0, null);
      InteractionGoals.Result r = InteractionGoals.select(occ.world(2, 4), s, occ);
      Assertions.assertFalse(r.ok());
      Assertions.assertEquals(InteractionGoals.NO_POSE, r.reason);
      Assertions.assertEquals(NavPlanStatus.FAILED, r.plan.status);
      Assertions.assertNull(r.selected);
      int total = 0;
      for (Integer count : r.rejectCounts.values()) {
         total += count.intValue();
      }
      Assertions.assertTrue(total > 0, "typed failure must tally reject reasons, not only NO_POSE");
   }

   @Test
   void dynamicInvalidationSelectsNewPose() {
      OccupancyGrid occ = solids(20, 8, new Coord[]{Coord.of(10, 4)}, false);
      InteractionSpec s = spec(occ.world(10, 4), InteractionSpec.ALL_SIDES, 0.5, 8.0, 0, null);
      InteractionGoals.Result first = InteractionGoals.select(occ.world(2, 4), s, occ);
      Assertions.assertTrue(first.ok());
      Coord picked = first.selected.cell;
      Coord[] next = new Coord[]{Coord.of(10, 4), picked};
      OccupancyGrid blocked = solids(20, 8, next, false);
      InteractionGoals.Result second = InteractionGoals.select(occ.world(2, 4), s, blocked);
      Assertions.assertTrue(second.ok(), second.reason);
      Assertions.assertNotEquals(picked, second.selected.cell);
   }

}
