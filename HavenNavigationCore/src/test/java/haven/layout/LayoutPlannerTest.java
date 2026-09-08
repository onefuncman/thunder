package haven.layout;

import haven.Coord;
import haven.Coord2d;
import haven.pathfinding.LocalPlanner;
import haven.pathfinding.OccupancyGrid;
import haven.pathfinding.PlanningTrace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * LayoutPlanner focused tests: packed rectangle, collision/keep rejection,
 * no-free-space failure, unreachable rejection, lane retention and
 * determinism. These run headless; no live client.
 */
public class LayoutPlannerTest {
   private static final int W = 44;
   private static final int H = 44;
   private static final Coord2d ORIGIN = Coord2d.of(0.0, 0.0);
   private static final double CELL = 2.75;
   private static final double PITCH = 11.0;

   private static OccupancyGrid open() {
      boolean[] solid = new boolean[W * H];
      boolean[] dilated = new boolean[W * H];
      return OccupancyGrid.capture(ORIGIN, W, H, CELL, solid, dilated, dilated, Coord.of(0, 0), Coord.of(W - 1, 0), Coord.of(W - 1, 0), Collections.emptyList());
   }

   private static OccupancyGrid withSolidRect(OccupancyGrid src, double x0, double y0, double x1, double y1) {
      boolean[] solid = new boolean[W * H];
      for (int i = 0; i < W * H; i++) {
         solid[i] = src.occ[i] == OccupancyGrid.SOLID;
      }
      for (int y = 0; y < H; y++) {
         for (int x = 0; x < W; x++) {
            double cx = x * CELL;
            double cy = y * CELL;
            if (cx < x1 && cx + CELL > x0 && cy < y1 && cy + CELL > y0) {
               solid[y * W + x] = true;
            }
         }
      }
      boolean[] dilated = Arrays.copyOf(solid, solid.length);
      return OccupancyGrid.capture(src.origin, W, H, CELL, solid, dilated, dilated, src.start, src.goal, src.freeGoal, src.astar);
   }

   private static void assertRejectCount(Map<String, Integer> rejects, String reason, int expected) {
      Integer v = rejects.get(reason);
      Assertions.assertEquals(expected, v == null ? 0 : v.intValue(), "rejectCounts[" + reason + "]");
   }

   /** Final pathfinding grid = occupancy + reserved shapes + blocking placement footprints. */
   private static OccupancyGrid finalGrid(LayoutRequest req, List<LayoutPlacement> placements) {
      OccupancyGrid occ = req.occupancy;
      boolean[] solid = new boolean[W * H];
      for (int i = 0; i < W * H; i++) {
         solid[i] = occ.occ[i] == OccupancyGrid.SOLID;
      }
      List<LayoutShape> shapes = new ArrayList<LayoutShape>();
      shapes.addAll(req.occupiedShapes);
      shapes.addAll(req.keepShapes);
      for (LayoutShape s : shapes) {
         mark(occ, solid, s.worldAnchor.x, s.worldAnchor.y, s.footprint);
      }
      for (LayoutPlacement p : placements) {
         if (p.footprint.blocksApproach()) {
            mark(occ, solid, p.world.x, p.world.y, p.footprint);
         }
      }
      boolean[] dilated = Arrays.copyOf(solid, solid.length);
      return OccupancyGrid.capture(occ.origin, W, H, CELL, solid, dilated, dilated, occ.start, occ.goal, occ.freeGoal, occ.astar);
   }

   private static void mark(OccupancyGrid occ, boolean[] solid, double ax, double ay, LayoutFootprint fp) {
      for (Coord c : fp.cells()) {
         double x0 = ax + c.x * PITCH;
         double x1 = x0 + PITCH;
         double y0 = ay + c.y * PITCH;
         double y1 = y0 + PITCH;
         for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
               double cx = x * CELL;
               double cy = y * CELL;
               if (cx < x1 && cx + CELL > x0 && cy < y1 && cy + CELL > y0) {
                  solid[y * W + x] = true;
               }
            }
         }
      }
   }

   /** After a plan, every placement must keep a reachable stand and a route in the final grid. */
   private static void assertLanes(LayoutRequest req, LayoutPlanResult r) {
      OccupancyGrid occ = req.occupancy;
      OccupancyGrid fin = finalGrid(req, r.placements);
      double[] costs = LocalPlanner.occupancyCosts(req.approachFrom, fin);
      Assertions.assertEquals(0, costs.length == 0 ? 1 : 0, "flood must resolve");
      for (LayoutPlacement p : r.placements) {
         Coord c = occ.cellOf(p.stand);
         Assertions.assertNotNull(c, "stand in bounds " + p);
         Assertions.assertEquals(OccupancyGrid.FREE, fin.occ[c.y * W + c.x], "stand cell free " + p);
         Assertions.assertTrue(Double.isFinite(costs[c.y * W + c.x]), "stand reachable " + p);
         Assertions.assertTrue(routeFree(p.standRoute, fin), "stored route survives " + p);
      }
   }

   private static boolean routeFree(List<Coord2d> route, OccupancyGrid fin) {
      if (route == null || route.isEmpty()) {
         return true;
      }
      double step = CELL * 0.5;
      for (int i = 1; i < route.size(); i++) {
         Coord2d a = route.get(i - 1);
         Coord2d b = route.get(i);
         if (a == null || b == null) {
            continue;
         }
         if (pointSolid(a, fin) || pointSolid(b, fin)) {
            return false;
         }
         double len = a.dist(b);
         int n = Math.max(1, (int) Math.ceil(len / step));
         for (int s = 1; s < n; s++) {
            double t = s / (double) n;
            Coord2d p = Coord2d.of(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            if (pointSolid(p, fin)) {
               return false;
            }
         }
      }
      return true;
   }

   private static boolean pointSolid(Coord2d p, OccupancyGrid fin) {
      Coord c = fin.cellOf(p);
      return c == null || c.x < 0 || c.y < 0 || c.x >= W || c.y >= H || fin.occ[c.y * W + c.x] == OccupancyGrid.SOLID;
   }

   private static LayoutRequest.Builder baseRequest(OccupancyGrid occ, LayoutFootprint fp, Coord2d from, int count) {
      return LayoutRequest.builder(occ, fp, from, count)
         .area(Coord2d.of(11.0, 11.0), Coord2d.of(44.0, 33.0))
         .pitch(PITCH, PITCH);
   }

   private static void assertNoOverlap(List<LayoutPlacement> placements, int cols, int rows) {
      boolean[] taken = new boolean[cols * rows];
      for (LayoutPlacement p : placements) {
         for (Coord c : p.footprint.cells()) {
            int gi = p.gridX + c.x;
            int gj = p.gridY + c.y;
            Assertions.assertTrue(gi >= 0 && gj >= 0 && gi < cols && gj < rows, "inside area " + p);
            int idx = gj * cols + gi;
            Assertions.assertFalse(taken[idx], "no overlap " + p);
            taken[idx] = true;
         }
      }
   }

   @Test
   void packedPassableRectangularLayout() {
      LayoutFootprint fp = LayoutFootprint.rect(1, 1, false);
      LayoutRequest req = baseRequest(open(), fp, Coord2d.of(27.5, 2.0), 6).build();
      LayoutPlanResult r = LayoutPlanner.plan(req);
      Assertions.assertEquals(LayoutPlanResult.Status.PLANNED, r.status, r.reason);
      Assertions.assertEquals(6, r.placements.size());
      assertNoOverlap(r.placements, 3, 2);
      for (int idx = 0; idx < 6; idx++) {
         LayoutPlacement p = r.placements.get(idx);
         Assertions.assertEquals(idx % 3, p.gridX, "row-major x " + p);
         Assertions.assertEquals(idx / 3, p.gridY, "row-major y " + p);
         Assertions.assertEquals(11.0 + p.gridX * PITCH + PITCH * 0.5, p.stand.x, 1.0E-9);
         Assertions.assertEquals(11.0 + p.gridY * PITCH + PITCH * 0.5, p.stand.y, 1.0E-9);
      }
      assertLanes(req, r);
   }

   @Test
   void blockingPackedLayoutKeepsLanes() {
      LayoutFootprint fp = LayoutFootprint.rect(1, 1);
      LayoutRequest req = baseRequest(open(), fp, Coord2d.of(27.5, 2.0), 6).build();
      LayoutPlanResult r = LayoutPlanner.plan(req);
      Assertions.assertNotEquals(LayoutPlanResult.Status.FAILED, r.status, r.reason);
      Assertions.assertTrue(r.placements.size() >= 3, "first row always packs: " + r.placements.size());
      assertNoOverlap(r.placements, 3, 2);
      assertLanes(req, r);
   }

   @Test
   void lanePreservationRejectsSealingPlacement() {
      // A 1-wide walled column two tiles tall: the bottom tile's only stand is on
      // the top tile's footprint, so stacking the top tile would orphan it.
      OccupancyGrid occ = open();
      occ = withSolidRect(occ, 0.0, 11.0, 11.0, 121.0);
      occ = withSolidRect(occ, 22.0, 11.0, 121.0, 121.0);
      occ = withSolidRect(occ, 11.0, 0.0, 121.0, 11.0);
      LayoutRequest req = LayoutRequest.builder(occ, LayoutFootprint.rect(1, 1), Coord2d.of(16.5, 99.0), 2)
         .area(Coord2d.of(11.0, 11.0), Coord2d.of(22.0, 33.0))
         .pitch(PITCH, PITCH)
         .build();
      LayoutPlanResult r = LayoutPlanner.plan(req);
      Assertions.assertEquals(LayoutPlanResult.Status.PARTIAL, r.status, r.reason);
      Assertions.assertEquals(1, r.placements.size(), r.reason);
      Assertions.assertEquals(0, r.placements.get(0).gridY);
      assertRejectCount(r.rejectCounts, LayoutPlanner.LANE_BLOCKED, 1);
      Assertions.assertEquals(LayoutPlanner.LANE_BLOCKED, r.reason);
      assertLanes(req, r);
   }

   @Test
   void collisionWithOccupiedAndKeepShapes() {
      OccupancyGrid occ = open();
      occ = withSolidRect(occ, 22.0, 22.0, 33.0, 33.0); // blocks tile (1,1)
      List<LayoutShape> occupied = Collections.singletonList(
         LayoutShape.at(LayoutFootprint.rect(1, 1), Coord2d.of(11.0, 22.0)) // blocks tile (0,1)
      );
      List<LayoutShape> keep = Collections.singletonList(
         LayoutShape.at(LayoutFootprint.rect(1, 1), Coord2d.of(33.0, 22.0)) // blocks tile (2,1)
      );
      LayoutRequest req = baseRequest(occ, LayoutFootprint.rect(1, 1), Coord2d.of(27.5, 2.0), 6)
         .occupied(occupied)
         .keep(keep)
         .build();
      LayoutPlanResult r = LayoutPlanner.plan(req);
      Assertions.assertEquals(LayoutPlanResult.Status.PARTIAL, r.status, r.reason);
      Assertions.assertEquals(3, r.placements.size());
      for (int i = 0; i < 3; i++) {
         Assertions.assertEquals(i, r.placements.get(i).gridX);
         Assertions.assertEquals(0, r.placements.get(i).gridY);
      }
      assertRejectCount(r.rejectCounts, LayoutPlanner.OVERLAP_OCCUPIED, 2);
      assertRejectCount(r.rejectCounts, LayoutPlanner.OVERLAP_KEEP, 1);
      assertLanes(req, r);
   }

   @Test
   void footprintLargerThanAreaFailsNoFreeSpace() {
      LayoutRequest req = baseRequest(open(), LayoutFootprint.rect(2, 2), Coord2d.of(27.5, 2.0), 2)
         .area(Coord2d.of(11.0, 11.0), Coord2d.of(33.0, 22.0))
         .build();
      LayoutPlanResult r = LayoutPlanner.plan(req);
      Assertions.assertEquals(LayoutPlanResult.Status.FAILED, r.status, r.reason);
      Assertions.assertEquals(0, r.placements.size());
      Assertions.assertEquals(LayoutPlanner.NO_FREE_SPACE, r.reason);
   }

   @Test
   void fullySolidAreaFailsOverlapOccupied() {
      OccupancyGrid occ = open();
      occ = withSolidRect(occ, 11.0, 11.0, 33.0, 33.0);
      LayoutRequest req = baseRequest(occ, LayoutFootprint.rect(1, 1), Coord2d.of(27.5, 2.0), 3)
         .area(Coord2d.of(11.0, 11.0), Coord2d.of(33.0, 33.0))
         .build();
      LayoutPlanResult r = LayoutPlanner.plan(req);
      Assertions.assertEquals(LayoutPlanResult.Status.FAILED, r.status, r.reason);
      Assertions.assertEquals(0, r.placements.size());
      Assertions.assertEquals(LayoutPlanner.OVERLAP_OCCUPIED, r.reason);
      assertRejectCount(r.rejectCounts, LayoutPlanner.OVERLAP_OCCUPIED, 4);
   }

   @Test
   void sealedAreaIsRejectedAsUnreachable() {
      // Full-width wall between the approach and the area: stands exist north of
      // the wall but cannot be reached from the south approach.
      OccupancyGrid occ = open();
      occ = withSolidRect(occ, 0.0, 27.5, 121.0, 33.0);
      LayoutRequest req = baseRequest(occ, LayoutFootprint.rect(1, 1), Coord2d.of(27.5, 99.0), 3)
         .area(Coord2d.of(11.0, 11.0), Coord2d.of(44.0, 22.0))
         .build();
      LayoutPlanResult r = LayoutPlanner.plan(req);
      Assertions.assertEquals(LayoutPlanResult.Status.FAILED, r.status, r.reason);
      Assertions.assertEquals(0, r.placements.size());
      Assertions.assertEquals(LayoutPlanner.UNREACHABLE, r.reason);
      assertRejectCount(r.rejectCounts, LayoutPlanner.UNREACHABLE, 3);
   }

   @Test
   void deterministicOutput() {
      LayoutFootprint fp = LayoutFootprint.rect(1, 1);
      OccupancyGrid occ = open();
      occ = withSolidRect(occ, 22.0, 22.0, 33.0, 33.0);
      List<LayoutShape> keep = new ArrayList<LayoutShape>();
      keep.add(LayoutShape.at(LayoutFootprint.rect(1, 1), Coord2d.of(33.0, 22.0)));
      keep.add(LayoutShape.at(LayoutFootprint.rect(1, 1), Coord2d.of(11.0, 22.0)));
      LayoutRequest a = baseRequest(occ, fp, Coord2d.of(27.5, 2.0), 6).keep(keep).build();
      LayoutRequest b = baseRequest(occ, fp, Coord2d.of(27.5, 2.0), 6)
         .keep(new ArrayList<LayoutShape>(Arrays.asList(keep.get(1), keep.get(0))))
         .build();
      LayoutPlanResult ra = LayoutPlanner.plan(a);
      LayoutPlanResult rb = LayoutPlanner.plan(b);
      LayoutPlanResult rc = LayoutPlanner.plan(a);
      Assertions.assertEquals(ra.status, rb.status);
      Assertions.assertEquals(ra.status, rc.status);
      Assertions.assertEquals(ra.reason, rb.reason);
      Assertions.assertEquals(ra.rejectCounts, rb.rejectCounts);
      assertSamePlan(ra.placements, rb.placements);
      assertSamePlan(ra.placements, rc.placements);
   }

   private static void assertSamePlan(List<LayoutPlacement> x, List<LayoutPlacement> y) {
      Assertions.assertEquals(x.size(), y.size());
      for (int i = 0; i < x.size(); i++) {
         LayoutPlacement a = x.get(i);
         LayoutPlacement b = y.get(i);
         Assertions.assertEquals(a.gridX, b.gridX);
         Assertions.assertEquals(a.gridY, b.gridY);
         Assertions.assertEquals(a.world.x, b.world.x, 1.0E-9);
         Assertions.assertEquals(a.world.y, b.world.y, 1.0E-9);
         Assertions.assertEquals(a.stand.x, b.stand.x, 1.0E-9);
         Assertions.assertEquals(a.stand.y, b.stand.y, 1.0E-9);
         Assertions.assertEquals(a.standRoute.size(), b.standRoute.size());
      }
   }

   @Test
   void arbitraryLShapeFootprintPacksWithoutOverlap() {
      List<Coord> cells = new ArrayList<Coord>();
      cells.add(Coord.of(0, 0));
      cells.add(Coord.of(1, 0));
      cells.add(Coord.of(0, 1));
      LayoutFootprint l = LayoutFootprint.ofCells(cells, true);
      Assertions.assertEquals(2, l.bboxW());
      Assertions.assertEquals(2, l.bboxH());
      LayoutRequest req = LayoutRequest.builder(open(), l, Coord2d.of(27.5, 2.0), 2)
         .area(Coord2d.of(11.0, 11.0), Coord2d.of(44.0, 44.0))
         .pitch(PITCH, PITCH)
         .build();
      LayoutPlanResult r = LayoutPlanner.plan(req);
      Assertions.assertEquals(LayoutPlanResult.Status.PLANNED, r.status, r.reason);
      Assertions.assertEquals(2, r.placements.size());
      Assertions.assertEquals(0, r.placements.get(0).gridX);
      Assertions.assertEquals(0, r.placements.get(0).gridY);
      Assertions.assertEquals(1, r.placements.get(1).gridX);
      Assertions.assertEquals(1, r.placements.get(1).gridY);
      assertNoOverlap(r.placements, 3, 3);
      assertLanes(req, r);
   }

   @Test
   void countZeroIsPlannedEmpty() {
      LayoutRequest req = baseRequest(open(), LayoutFootprint.rect(1, 1), Coord2d.of(27.5, 2.0), 0).build();
      LayoutPlanResult r = LayoutPlanner.plan(req);
      Assertions.assertEquals(LayoutPlanResult.Status.PLANNED, r.status);
      Assertions.assertEquals(0, r.placements.size());
      Assertions.assertEquals("", r.reason);
   }
}
