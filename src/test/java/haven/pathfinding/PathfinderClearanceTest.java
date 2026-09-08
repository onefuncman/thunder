package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.Hitbox;
import haven.MCache;
import haven.pathfinding.GridAStar.Grid;
import haven.pathfinding.GridAStar.Result;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PathfinderClearanceTest {
   @Test
   void boundingRadiusUsesFarthestVertex() {
      Coord2d origin = Coord2d.of(10.0, 10.0);
      Coord2d[] box = new Coord2d[]{Coord2d.of(7.0, 7.0), Coord2d.of(13.0, 7.0), Coord2d.of(13.0, 13.0), Coord2d.of(7.0, 13.0)};
      Assertions.assertEquals(Math.sqrt(18.0), PrototypePathfinder.boundingRadius(origin, List.<Coord2d[]>of(box)), 1.0E-9);
   }

   @Test
   void dilationCellsMatchPlayerRadius() {
      Assertions.assertEquals(1, PrototypePathfinder.dilationCells(0.0));
      Assertions.assertEquals(2, PrototypePathfinder.dilationCells(4.5), "carve pocket covers the ~4.5 body; occupancy uses Minkowski, not this cap");
   }

   @Test
   void selectMovementLayersDropsNegWhenObstacleExists() {
      Coord2d[] neg = new Coord2d[]{Coord2d.of(-5.0, -5.0), Coord2d.of(5.0, -5.0), Coord2d.of(5.0, 5.0), Coord2d.of(-5.0, 5.0)};
      Coord2d[] obst = new Coord2d[]{Coord2d.of(-1.0, -1.0), Coord2d.of(1.0, -1.0), Coord2d.of(1.0, 1.0), Coord2d.of(-1.0, 1.0)};
      List<Coord2d[]> movement = Hitbox.selectMovementLayers(List.<Coord2d[]>of(neg), List.<Coord2d[]>of(obst));
      Assertions.assertEquals(1, movement.size());
      Assertions.assertSame(obst, movement.get(0));
      List<Coord2d[]> fallback = Hitbox.selectMovementLayers(List.<Coord2d[]>of(neg), Collections.emptyList());
      Assertions.assertEquals(1, fallback.size());
      Assertions.assertSame(neg, fallback.get(0));
   }

   @Test
   void packedCupboardAisleStaysFreeWhenPlacementBoxesAreNotRastered() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-22.0, -11.0);
      int w = 20;
      int h = 12;
      boolean[] blocked = new boolean[w * h];
      PrototypePathfinder.dilate(blocked, w, h, 1);
      Coord mid = PrototypePathfinder.worldCell(origin, Coord2d.of(5.5, 0.0));
      Assertions.assertTrue(mid.x >= 0 && mid.x < w && mid.y >= 0 && mid.y < h);
      Assertions.assertFalse(blocked[mid.y * w + mid.x], "packed 11-apart cupboard aisle must stay walkable");
   }

   @Test
   void furniturePrepadWouldHaveSealedPackedCupboardAisle() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-22.0, -11.0);
      int w = 20;
      int h = 12;
      Coord2d[] a = new Coord2d[]{Coord2d.of(-5.0, -5.0), Coord2d.of(5.0, -5.0), Coord2d.of(5.0, 5.0), Coord2d.of(-5.0, 5.0)};
      Coord2d[] b = new Coord2d[]{Coord2d.of(6.0, -5.0), Coord2d.of(16.0, -5.0), Coord2d.of(16.0, 5.0), Coord2d.of(6.0, 5.0)};
      Coord mid = PrototypePathfinder.worldCell(origin, Coord2d.of(5.5, 0.0));
      boolean[] prepadded = new boolean[w * h];
      double oldPrepad = 2.45;
      PrototypePathfinder.rasterPolygon(prepadded, origin, w, h, a, oldPrepad);
      PrototypePathfinder.rasterPolygon(prepadded, origin, w, h, b, oldPrepad);
      PrototypePathfinder.dilate(prepadded, w, h, 1);
      Assertions.assertTrue(prepadded[mid.y * w + mid.x], "the removed furniturePrepad is what sealed the 1-unit packed gap");
   }

   @Test
   void overlapOnlyRasterLeavesStand032TilesOutsideTenBoxFree() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-22.0, -22.0);
      int w = 24;
      int h = 24;
      boolean[] blocked = new boolean[w * h];
      Coord2d[] box = new Coord2d[]{Coord2d.of(-5.0, -5.0), Coord2d.of(5.0, -5.0), Coord2d.of(5.0, 5.0), Coord2d.of(-5.0, 5.0)};
      PrototypePathfinder.rasterPolygon(blocked, origin, w, h, box, PrototypePathfinder.OVERLAP);
      Coord stand = PrototypePathfinder.worldCell(origin, Coord2d.of(8.5, 0.0));
      Assertions.assertTrue(stand.x >= 0 && stand.x < w && stand.y >= 0 && stand.y < h);
      Assertions.assertFalse(blocked[stand.y * w + stand.x], "a point 3.5 outside a 10x10 is not occupancy-solid after overlap-only raster");
   }

   @Test
   void playerBodyLeavesAOneTileCorridorOpen() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-20.0, -20.0);
      int w = 20;
      int h = 10;
      boolean[] blocked = new boolean[w * h];
      Coord2d[] left = new Coord2d[]{Coord2d.of(0.0, -30.0), Coord2d.of(1.0, -30.0), Coord2d.of(1.0, 30.0), Coord2d.of(0.0, 30.0)};
      Coord2d[] right = new Coord2d[]{Coord2d.of(12.0, -30.0), Coord2d.of(13.0, -30.0), Coord2d.of(13.0, 30.0), Coord2d.of(12.0, 30.0)};
      PrototypePathfinder.rasterPolygon(blocked, origin, w, h, left, 4.5);
      PrototypePathfinder.rasterPolygon(blocked, origin, w, h, right, 4.5);
      Coord mid = PrototypePathfinder.worldCell(origin, Coord2d.of(6.5, 0.0));
      Assertions.assertTrue(mid.x >= 0 && mid.x < w && mid.y >= 0 && mid.y < h);
      Assertions.assertFalse(blocked[mid.y * w + mid.x], "a 1-tile corridor must fit the player body at its center");
   }

   @Test
   void playerBodyBlocksCellsCloserThanRadiusToAWall() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-20.0, -20.0);
      int w = 16;
      int h = 10;
      boolean[] blocked = new boolean[w * h];
      Coord2d[] wall = new Coord2d[]{Coord2d.of(0.0, -30.0), Coord2d.of(1.0, -30.0), Coord2d.of(1.0, 30.0), Coord2d.of(0.0, 30.0)};
      PrototypePathfinder.rasterPolygon(blocked, origin, w, h, wall, 4.5);
      Coord tooClose = PrototypePathfinder.worldCell(origin, Coord2d.of(4.0, 0.0));
      Coord far = PrototypePathfinder.worldCell(origin, Coord2d.of(8.0, 0.0));
      Assertions.assertTrue(blocked[tooClose.y * w + tooClose.x], "standing 3u from a wall puts the r=4.5 diamond through it");
      Assertions.assertFalse(blocked[far.y * w + far.x], "standing 7u from a wall clears a r=4.5 body");
   }

   @Test
   void orientedBodyIsNotAWorldLockedDiamond() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-20.0, -20.0);
      int w = 20;
      int h = 16;
      Coord2d[] wall = new Coord2d[]{Coord2d.of(-30.0, -1.0), Coord2d.of(30.0, -1.0), Coord2d.of(30.0, 0.0), Coord2d.of(-30.0, 0.0)};
      List<Coord2d[]> wide = Collections.singletonList(
         new Coord2d[]{Coord2d.of(4.0, 1.0), Coord2d.of(4.0, -1.0), Coord2d.of(-4.0, -1.0), Coord2d.of(-4.0, 1.0)}
      );
      List<Coord2d[]> tall = Collections.singletonList(
         new Coord2d[]{Coord2d.of(1.0, 4.0), Coord2d.of(1.0, -4.0), Coord2d.of(-1.0, -4.0), Coord2d.of(-1.0, 4.0)}
      );
      boolean[] eastWest = new boolean[w * h];
      boolean[] northSouth = new boolean[w * h];
      PrototypePathfinder.rasterPolygon(eastWest, origin, w, h, wall, wide);
      PrototypePathfinder.rasterPolygon(northSouth, origin, w, h, wall, tall);
      Coord justNorth = PrototypePathfinder.worldCell(origin, Coord2d.of(0.0, 2.6));
      Assertions.assertFalse(eastWest[justNorth.y * w + justNorth.x], "a body that is thin north-south still fits 2.6u off a south wall");
      Assertions.assertTrue(northSouth[justNorth.y * w + justNorth.x], "the same stand is blocked once that body is rotated to face east-west");
   }

   @Test
   void packedFurnitureIsNotInflatedByThePlayerBody() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-22.0, -22.0);
      int w = 24;
      int h = 24;
      boolean[] blocked = new boolean[w * h];
      Coord2d[] box = new Coord2d[]{Coord2d.of(-5.0, -5.0), Coord2d.of(5.0, -5.0), Coord2d.of(5.0, 5.0), Coord2d.of(-5.0, 5.0)};
      PrototypePathfinder.rasterPolygon(blocked, origin, w, h, box, PrototypePathfinder.OVERLAP);
      Coord stand = PrototypePathfinder.worldCell(origin, Coord2d.of(8.5, 0.0));
      Assertions.assertFalse(blocked[stand.y * w + stand.x]);
   }

   @Test
   void oneCellChebyshevDilateLeavesAFourCellGap() {
      int w = 12;
      int h = 5;
      boolean[] blocked = new boolean[w * h];

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < 4; x++) {
            blocked[y * w + x] = true;
         }

         for (int x = 8; x < w; x++) {
            blocked[y * w + x] = true;
         }
      }

      PrototypePathfinder.dilate(blocked, w, h, 1);
      Assertions.assertFalse(blocked[2 * w + 5], "center of a 4-cell gap stays open after 1-cell Chebyshev");
      Assertions.assertFalse(blocked[2 * w + 6]);
   }

   @Test
   void dilateBlocksNeighborsOfAnObstacle() {
      int w = 7;
      int h = 7;
      boolean[] blocked = new boolean[w * h];
      blocked[3 * w + 3] = true;
      PrototypePathfinder.dilate(blocked, w, h, 2);
      Assertions.assertTrue(blocked[3 * w + 3]);
      Assertions.assertTrue(blocked[3 * w + 5]);
      Assertions.assertTrue(blocked[1 * w + 3]);
      Assertions.assertFalse(blocked[0]);
      Assertions.assertFalse(blocked[6 * w + 6]);
   }

   @Test
   void dilateBlocksTheInsideCornerOfAnL() {
      int w = 7;
      int h = 7;
      boolean[] blocked = new boolean[w * h];

      for (int x = 3; x < w; x++) {
         blocked[3 * w + x] = true;
      }

      for (int y = 3; y < h; y++) {
         blocked[y * w + 3] = true;
      }

      PrototypePathfinder.dilate(blocked, w, h, 1);
      Assertions.assertTrue(blocked[2 * w + 2], "Chebyshev dilate must block the inner diagonal so the player diamond cannot cut the corner");
   }

   @Test
   void openFootprintLetsAgentLeaveADilatedPocket() {
      int w = 9;
      int h = 9;
      boolean[] blocked = new boolean[w * h];
      blocked[4 * w + 4] = true;
      boolean[] solid = Arrays.copyOf(blocked, blocked.length);
      PrototypePathfinder.dilate(blocked, w, h, 2);
      Assertions.assertTrue(blocked[4 * w + 2]);
      PrototypePathfinder.openFootprint(blocked, w, h, 2, 4, 2, solid);
      Assertions.assertFalse(blocked[4 * w + 2]);
      Assertions.assertFalse(blocked[4 * w + 1]);
      Assertions.assertTrue(blocked[4 * w + 4], "the obstacle itself should still be blocked outside the footprint");
   }

   @Test
   void startPocketProvidesAFirstMoveWhenConservativeSolidsOverlapPlayer() {
      int w = 9;
      int h = 9;
      boolean[] blocked = new boolean[w * h];

      for (int y = 2; y <= 6; y++) {
         for (int x = 2; x <= 6; x++) {
            blocked[y * w + x] = true;
         }
      }

      PrototypePathfinder.openStartPocket(blocked, w, h, 4, 4, 2);
      Assertions.assertFalse(blocked[4 * w + 4]);
      Assertions.assertFalse(blocked[4 * w + 5], "A* needs a connected first step, not only an open center");
      Assertions.assertTrue(blocked[2 * w + 2], "the carve remains local and must not erase the whole obstacle");
   }

   @Test
   void planningOriginUsesAStableWorldLattice() {
      Coord2d a = PrototypePathfinder.alignedOrigin(10.1, -10.1);
      Coord2d b = PrototypePathfinder.alignedOrigin(10.9, -9.0);
      Assertions.assertEquals(8.25, a.x, 1.0E-9);
      Assertions.assertEquals(-11.0, a.y, 1.0E-9);
      Assertions.assertEquals(a, b, "sub-cell shifts must keep identical world cell boundaries");
   }

   @Test
   void nearestFreeApproachesFromThePlayerSide() {
      int w = 11;
      int h = 5;
      boolean[] blocked = new boolean[w * h];
      blocked[2 * w + 5] = true;
      Coord from = Coord.of(0, 2);
      Coord goal = Coord.of(5, 2);
      Coord at = PrototypePathfinder.nearestFree(blocked, w, h, goal, from);
      Assertions.assertNotNull(at);
      Assertions.assertTrue(at.x < 5, "should stand on the player's side of the gob, not the far side");
   }

   @Test
   void nearestFreeDoesNotStandoffAcrossABlob() {
      int w = 15;
      int h = 9;
      boolean[] blocked = new boolean[w * h];

      for (int y = 2; y <= 6; y++) {
         for (int x = 6; x <= 10; x++) {
            blocked[y * w + x] = true;
         }
      }

      Coord from = Coord.of(0, 4);
      Coord goal = Coord.of(8, 4);
      Coord at = PrototypePathfinder.nearestFree(blocked, w, h, goal, from);
      Assertions.assertNotNull(at);
      Assertions.assertEquals(5, at.x, "first free cell on the near edge, not one more cell into the aisle");
      Assertions.assertEquals(4, at.y);
   }

   @Test
   void approachStopsShortOfTheGob() {
      Coord2d from = Coord2d.of(0.0, 0.0);
      Coord2d gob = Coord2d.of(22.0, 0.0);
      Coord2d at = PrototypePathfinder.approach(from, gob, 11.0);
      Assertions.assertEquals(11.0, at.x, 1.0E-9);
      Assertions.assertEquals(0.0, at.y, 1.0E-9);
      Coord2d already = PrototypePathfinder.approach(Coord2d.of(14.0, 0.0), gob, 11.0);
      Assertions.assertEquals(14.0, already.x, 1.0E-9);
   }

   @Test
   void clearanceCostPrefersAisleCenter() {
      int w = 10;
      int h = 5;
      boolean[] solid = new boolean[w * h];

      for (int y = 0; y < h; y++) {
         solid[y * w] = true;
         solid[y * w + (w - 1)] = true;
      }

      double[] cost = new double[w * h];
      PrototypePathfinder.applyClearanceCost(cost, solid, w, h);
      Assertions.assertTrue(cost[2 * w + 5] < cost[2 * w + 1], "center of the aisle should be cheaper than hugging the wall");
      Assertions.assertEquals(1.0, cost[2 * w + 5], 1.0E-9);
      Assertions.assertEquals(4.5, cost[2 * w + 1], 1.0E-9);
   }

   @Test
   void segmentHitsSquareCatchesACornerClip() {
      double lo = 5.5;
      double hi = 8.25;
      Assertions.assertTrue(
         PrototypePathfinder.segmentHitsSquare(Coord2d.of(4.0, 7.0), Coord2d.of(7.0, 4.0), lo, lo, hi, hi),
         "a line through a square's corner must count as blocked"
      );
      Assertions.assertFalse(PrototypePathfinder.segmentHitsSquare(Coord2d.of(0.0, 0.0), Coord2d.of(4.0, 0.0), lo, lo, hi, hi));
   }

   @Test
   void lineOfSightRejectsShortcutThroughBlockedCellCorner() {
      double cell = 2.75;
      int w = 5;
      int h = 5;
      boolean[] blocked = new boolean[w * h];
      blocked[2 * w + 2] = true;
      Coord2d origin = Coord2d.of(0.0, 0.0);
      double x0 = 2.0 * cell;
      double y0 = 2.0 * cell;
      Coord2d a = Coord2d.of(x0 - 1.5, y0 + 1.5);
      Coord2d b = Coord2d.of(x0 + 1.5, y0 - 1.5);
      Assertions.assertFalse(
         PrototypePathfinder.lineOfSightClear(origin, w, h, blocked, a, b), "smoothing must not collapse A* into a straight line through a house corner"
      );
      Assertions.assertTrue(PrototypePathfinder.lineOfSightClear(origin, w, h, blocked, Coord2d.of(0.5, 0.5), Coord2d.of(cell * 1.5, 0.5)));
   }

   @Test
   void solidFootprintTreatsHousesNotWallSegments() {
      Assertions.assertTrue(PrototypePathfinder.solidFootprint("gfx/terobjs/arch/timberhouse"));
      Assertions.assertTrue(PrototypePathfinder.solidFootprint("gfx/terobjs/arch/logcabin"));
      Assertions.assertTrue(PrototypePathfinder.solidFootprint("gfx/terobjs/arch/stonemansion"));
      Assertions.assertTrue(PrototypePathfinder.solidFootprint("gfx/terobjs/arch/stonemansion[0]"));
      Assertions.assertEquals("gfx/terobjs/arch/stonemansion", PrototypePathfinder.baseResid("gfx/terobjs/arch/stonemansion[0]"));
      Assertions.assertFalse(PrototypePathfinder.solidFootprint("gfx/terobjs/arch/hwall"));
      Assertions.assertFalse(PrototypePathfinder.solidFootprint("gfx/terobjs/arch/palisadeseg"));
      Assertions.assertFalse(PrototypePathfinder.solidFootprint("gfx/terobjs/arch/palisadegate"));
      Assertions.assertFalse(PrototypePathfinder.solidFootprint("gfx/terobjs/vehicle/cart"));
      Assertions.assertTrue(PrototypePathfinder.furnitureFootprint("gfx/terobjs/cupboard"));
      Assertions.assertTrue(PrototypePathfinder.furnitureFootprint("gfx/terobjs/cupboard[0]"));
      Assertions.assertTrue(PrototypePathfinder.furnitureFootprint("gfx/terobjs/studydesk-big"));
      Assertions.assertTrue(PrototypePathfinder.furnitureFootprint("gfx/terobjs/studydesk"));
      Assertions.assertTrue(PrototypePathfinder.furnitureFootprint("gfx/terobjs/chair"));
      Assertions.assertTrue(PrototypePathfinder.furnitureFootprint("gfx/terobjs/table"));
      Assertions.assertTrue(PrototypePathfinder.furnitureFootprint("gfx/terobjs/chest"));
      Assertions.assertTrue(PrototypePathfinder.furnitureFootprint("gfx/terobjs/crate"));
      Assertions.assertTrue(PrototypePathfinder.furnitureFootprint("gfx/terobjs/bed"));
      Assertions.assertTrue(PrototypePathfinder.furnitureFootprint("gfx/terobjs/barrel"));
      Assertions.assertTrue(PrototypePathfinder.skipBodyInflate("gfx/terobjs/chair"));
      Assertions.assertTrue(PrototypePathfinder.skipBodyInflate("gfx/terobjs/arch/hwall"));
      Assertions.assertFalse(PrototypePathfinder.furnitureFootprint("gfx/terobjs/arch/palisadeseg"));
      Assertions.assertFalse(PrototypePathfinder.furnitureFootprint("gfx/terobjs/vehicle/cart"));
      Assertions.assertFalse(PrototypePathfinder.furnitureFootprint("gfx/terobjs/trees/maple"));
      Assertions.assertFalse(PrototypePathfinder.furnitureFootprint("gfx/terobjs/stockpile-ore"));
      Assertions.assertFalse(PrototypePathfinder.furnitureFootprint("gfx/terobjs/ladder"));
      Assertions.assertTrue(PrototypePathfinder.vegetationResid("gfx/terobjs/bushes/thornbush"));
      Assertions.assertTrue(PrototypePathfinder.vegetationResid("gfx/terobjs/trees/maple"));
      Assertions.assertTrue(PrototypePathfinder.vegetationResid("gfx/terobjs/bumlings/stone"));
      Assertions.assertFalse(PrototypePathfinder.vegetationResid("gfx/terobjs/cupboard"));
      Assertions.assertEquals(MCache.tilesz.x, PrototypePathfinder.obstacleDisk("gfx/terobjs/bushes/thornbush"), 1.0E-9);
      Assertions.assertEquals(MCache.tilesz.x * 0.6, PrototypePathfinder.obstacleDisk("gfx/terobjs/chest"), 1.0E-9);
      Assertions.assertTrue(PrototypePathfinder.wallClearance("gfx/terobjs/arch/palisadeseg"));
      Assertions.assertTrue(PrototypePathfinder.wallClearance("gfx/terobjs/arch/palisadecp"));
      Assertions.assertTrue(PrototypePathfinder.wallClearance("gfx/terobjs/arch/palisadegate"));
      Assertions.assertTrue(PrototypePathfinder.wallClearance("gfx/terobjs/arch/brickwallseg"));
      Assertions.assertTrue(PrototypePathfinder.wallClearance("gfx/terobjs/arch/hwall"));
      Assertions.assertFalse(PrototypePathfinder.wallClearance("gfx/terobjs/vehicle/cart"));
   }

   @Test
   void skimpyBushHitboxStillOccupiesATile() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-22.0, -22.0);
      int w = 24;
      int h = 24;
      boolean[] blocked = new boolean[w * h];
      Coord2d[] tiny = new Coord2d[]{
         Coord2d.of(-1.0, -1.0), Coord2d.of(1.0, -1.0), Coord2d.of(1.0, 1.0), Coord2d.of(-1.0, 1.0)
      };
      PrototypePathfinder.rasterObstacle(
         blocked,
         origin,
         w,
         h,
         Coord2d.of(0.0, 0.0),
         false,
         "gfx/terobjs/bushes/thornbush",
         Collections.singletonList(tiny),
         PrototypePathfinder.obstacleDisk("gfx/terobjs/bushes/thornbush"),
         Collections.emptyList()
      );
      Coord rim = PrototypePathfinder.worldCell(origin, Coord2d.of(8.0, 0.0));
      Assertions.assertTrue(rim.x >= 0 && rim.x < w && rim.y >= 0 && rim.y < h);
      Assertions.assertTrue(blocked[rim.y * w + rim.x], "a 2u bush hitbox must still block a tile-radius disk");
   }

   @Test
   void sceneKeepsADistantLadderWithoutLoadedHitboxes() {
      PrototypePathfinder.GobGeom ladder = new PrototypePathfinder.GobGeom();
      ladder.resid = "gfx/terobjs/ladder";
      ladder.gobDist = 180.0;
      Assertions.assertTrue(PrototypePathfinder.includeInScene(ladder), "Walk from stand 2 must still see the recorded ladder");
      PrototypePathfinder.GobGeom chest = new PrototypePathfinder.GobGeom();
      chest.resid = "gfx/terobjs/chest";
      chest.gobDist = 180.0;
      Assertions.assertFalse(PrototypePathfinder.includeInScene(chest), "ordinary clutter stays on the 22u cutoff");
   }

   @Test
   void studyDeskKnownFootprintCoversTheTopEvenWhenObstIsTiny() {
      Coord2d rc = Coord2d.of(0.0, 0.0);
      Coord2d[] known = PrototypePathfinder.knownFurnitureFootprint("gfx/terobjs/studydesk", rc, 0.0);
      Assertions.assertNotNull(known);
      Coord2d[] tiny = new Coord2d[]{
         Coord2d.of(-1.0, -1.0), Coord2d.of(1.0, -1.0), Coord2d.of(1.0, 1.0), Coord2d.of(-1.0, 1.0)
      };
      List<Coord2d[]> polys = PrototypePathfinder.furnitureCollision(
         "gfx/terobjs/studydesk", rc, 0.0, List.<Coord2d[]>of(tiny), Collections.<Coord2d[]>emptyList()
      );
      Assertions.assertEquals(1, polys.size(), "tiny obst is ignored; known fallback is used alone");
      CollisionGeom geom = PrototypePathfinder.furnitureGeometry(
         "gfx/terobjs/studydesk", rc, 0.0, List.<Coord2d[]>of(tiny), Collections.<Coord2d[]>emptyList()
      );
      Assertions.assertEquals(CollisionGeom.FALLBACK, geom.source);
      Coord2d origin = PrototypePathfinder.alignedOrigin(-40.0, -40.0);
      int w = 32;
      int h = 32;
      boolean[] solid = new boolean[w * h];
      PrototypePathfinder.rasterObstacle(solid, origin, w, h, rc, false, "gfx/terobjs/studydesk", polys, 1.0, Collections.emptyList());
      Coord far = PrototypePathfinder.worldCell(origin, Coord2d.of(0.0, 12.0));
      Assertions.assertTrue(solid[far.y * w + far.x], "known 6x16 desk AABB must occupy the long axis, not only the tiny obst");
      List<Coord2d[]> cupboardEmpty = PrototypePathfinder.furnitureCollision(
         "gfx/terobjs/cupboard", rc, 0.0, Collections.<Coord2d[]>emptyList(), List.<Coord2d[]>of(tiny)
      );
      Assertions.assertTrue(cupboardEmpty.isEmpty(), "Neg-only cupboards still contribute no occupancy");
      Coord2d[] movement = new Coord2d[]{
         Coord2d.of(-8.0, -16.0), Coord2d.of(8.0, -16.0), Coord2d.of(8.0, 16.0), Coord2d.of(-8.0, 16.0)
      };
      List<Coord2d[]> table = PrototypePathfinder.furnitureCollision(
         "gfx/terobjs/table", rc, 0.0, List.<Coord2d[]>of(tiny), List.<Coord2d[]>of(movement)
      );
      boolean[] tableSolid = new boolean[w * h];
      PrototypePathfinder.rasterObstacle(
         tableSolid, origin, w, h, rc, false, "gfx/terobjs/table", table, 1.0, Collections.emptyList()
      );
      CollisionGeom tableGeom = PrototypePathfinder.furnitureGeometry(
         "gfx/terobjs/table", rc, 0.0, List.<Coord2d[]>of(tiny), List.<Coord2d[]>of(movement)
      );
      Assertions.assertEquals(CollisionGeom.UNAVAILABLE, tableGeom.source, "placement/movement is not a furniture solid");
      Assertions.assertTrue(table.isEmpty());
      Assertions.assertFalse(
         tableSolid[far.y * w + far.x],
         "a table with tiny obst must not occupy its placement AABB"
      );
   }

   @Test
   void barrelNegOnlyCollisionFallsBackToTheKnownFootprint() {
      Coord2d rc = Coord2d.of(0.0, 0.0);
      Coord2d[] known = PrototypePathfinder.knownFurnitureFootprint("gfx/terobjs/barrel", rc, 0.0);
      Assertions.assertNotNull(known, "barrel must have a known furniture footprint");
      Coord2d[] movement = new Coord2d[]{
         Coord2d.of(-4.0, -4.0), Coord2d.of(4.0, -4.0), Coord2d.of(4.0, 4.0), Coord2d.of(-4.0, 4.0)
      };
      List<Coord2d[]> polys = PrototypePathfinder.furnitureCollision(
         "gfx/terobjs/barrel", rc, 0.0, Collections.<Coord2d[]>emptyList(), List.<Coord2d[]>of(movement)
      );
      Assertions.assertEquals(1, polys.size(), "empty obst uses the known barrel fallback, not the neg layer");
      CollisionGeom geom = PrototypePathfinder.furnitureGeometry(
         "gfx/terobjs/barrel", rc, 0.0, Collections.<Coord2d[]>emptyList(), List.<Coord2d[]>of(movement)
      );
      Assertions.assertEquals(CollisionGeom.FALLBACK, geom.source);
      Assertions.assertFalse(geom.polygons.isEmpty());
   }

   @Test
   void knownMansionFootprintIsAFilledRectangleAroundTheOrigin() {
      Coord2d rc = Coord2d.of(100.0, 200.0);
      Coord2d[] box = PrototypePathfinder.knownBuildingFootprint("gfx/terobjs/arch/stonemansion", rc, 0.0);
      Assertions.assertNotNull(box);
      Assertions.assertEquals(4, box.length);
      double minx = box[0].x;
      double maxx = box[0].x;
      double miny = box[0].y;
      double maxy = box[0].y;

      for (Coord2d p : box) {
         minx = Math.min(minx, p.x);
         maxx = Math.max(maxx, p.x);
         miny = Math.min(miny, p.y);
         maxy = Math.max(maxy, p.y);
      }

      Assertions.assertEquals(50.0, minx, 1.0E-9);
      Assertions.assertEquals(150.0, maxx, 1.0E-9);
      Assertions.assertTrue(maxy - miny > 60.0, "mansion width should cover the interior, not just a wall");
      Assertions.assertTrue(minx < rc.x && maxx > rc.x && miny < rc.y && maxy > rc.y);
   }

   @Test
   void standCandidatesAreNorthEastSouthWest() {
      Coord2d[] c = CupboardCatalog.standCandidates(0.0, 0.0);
      Assertions.assertEquals(8, c.length);
      Assertions.assertEquals(0.0, c[0].x, 1.0E-9);
      Assertions.assertEquals(-7.5, c[0].y, 1.0E-9);
      Assertions.assertEquals(7.5, c[1].x, 1.0E-9);
      Assertions.assertEquals(0.0, c[1].y, 1.0E-9);
      Assertions.assertEquals(0.0, c[2].x, 1.0E-9);
      Assertions.assertEquals(7.5, c[2].y, 1.0E-9);
      Assertions.assertEquals(-7.5, c[3].x, 1.0E-9);
      Assertions.assertEquals(0.0, c[3].y, 1.0E-9);
   }

   @Test
   void dilatedInteractStandBecomesAnAStarGoalAfterFootprintCarve() {
      int w = 16;
      int h = 8;
      boolean[] blocked = new boolean[w * h];

      for (int y = 0; y < h; y++) {
         for (int x = 10; x <= 12; x++) {
            blocked[y * w + x] = true;
         }
      }

      boolean[] solid = Arrays.copyOf(blocked, blocked.length);
      PrototypePathfinder.dilate(blocked, w, h, 1);
      int standX = 9;
      int standY = 4;
      Assertions.assertTrue(blocked[standY * w + standX], "hug cell starts dilated");
      Assertions.assertFalse(solid[standY * w + standX], "hug cell is not furniture");
      Grid sealed = new PathfinderClearanceTest.ArrayGrid(w, h, blocked);
      Result skipped = GridAStar.find(sealed, Coord.of(2, 4), Coord.of(standX, standY), 1000);
      Assertions.assertFalse(skipped.complete, "A* must ignore a still-dilated goal");
      PrototypePathfinder.openFootprint(blocked, w, h, standX, standY, 1, solid);
      Assertions.assertFalse(blocked[standY * w + standX]);
      Assertions.assertTrue(solid[standY * w + 10], "carve must not open the furniture");
      Grid carved = new PathfinderClearanceTest.ArrayGrid(w, h, blocked);
      Result reached = GridAStar.find(carved, Coord.of(2, 4), Coord.of(standX, standY), 1000);
      Assertions.assertTrue(reached.complete);
      Assertions.assertEquals(Coord.of(standX, standY), reached.cells.get(reached.cells.size() - 1));
   }

   @Test
   void hollowRingDetectsWallPiecesAroundAnInterior() {
      Coord2d[] north = new Coord2d[]{Coord2d.of(0.0, 10.0), Coord2d.of(10.0, 10.0), Coord2d.of(10.0, 11.0), Coord2d.of(0.0, 11.0)};
      Coord2d[] south = new Coord2d[]{Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0), Coord2d.of(10.0, 1.0), Coord2d.of(0.0, 1.0)};
      Coord2d[] west = new Coord2d[]{Coord2d.of(0.0, 0.0), Coord2d.of(1.0, 0.0), Coord2d.of(1.0, 11.0), Coord2d.of(0.0, 11.0)};
      Coord2d[] east = new Coord2d[]{Coord2d.of(9.0, 0.0), Coord2d.of(10.0, 0.0), Coord2d.of(10.0, 11.0), Coord2d.of(9.0, 11.0)};
      Assertions.assertTrue(PrototypePathfinder.isHollowRing(List.of(north, south, west, east)));
      Coord2d[] box = new Coord2d[]{Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0), Coord2d.of(10.0, 10.0), Coord2d.of(0.0, 10.0)};
      Assertions.assertFalse(PrototypePathfinder.isHollowRing(List.<Coord2d[]>of(box)));
   }

   @Test
   void rotatedDeskFallbackKeepsOrientationNotWorldAabb() {
      Coord2d rc = Coord2d.of(0.0, 0.0);
      double a = Math.PI / 4.0;
      Coord2d[] tiny = new Coord2d[]{
         Coord2d.of(-1.0, -1.0), Coord2d.of(1.0, -1.0), Coord2d.of(1.0, 1.0), Coord2d.of(-1.0, 1.0)
      };
      Coord2d[] known = PrototypePathfinder.knownFurnitureFootprint("gfx/terobjs/studydesk", rc, a);
      Assertions.assertNotNull(known);
      CollisionGeom geom = PrototypePathfinder.furnitureGeometry(
         "gfx/terobjs/studydesk", rc, a, Collections.<Coord2d[]>singletonList(tiny), Collections.<Coord2d[]>emptyList()
      );
      Assertions.assertEquals(CollisionGeom.FALLBACK, geom.source);
      Assertions.assertEquals(known[0].x, geom.polygons.get(0)[0].x, 1.0E-9);
      Assertions.assertEquals(known[0].y, geom.polygons.get(0)[0].y, 1.0E-9);
      Coord2d[] aabb = LocalPlanner.aabbPolygon(geom.polygons);
      Coord2d origin = PrototypePathfinder.alignedOrigin(-40.0, -40.0);
      int w = 32;
      int h = 32;
      boolean[] exact = new boolean[w * h];
      PrototypePathfinder.rasterObstacle(
         exact, origin, w, h, rc, false, "gfx/terobjs/studydesk", geom.polygons, 1.0, Collections.emptyList()
      );
      boolean[] boxed = new boolean[w * h];
      PrototypePathfinder.rasterObstacle(
         boxed, origin, w, h, rc, false, "gfx/terobjs/studydesk", Collections.<Coord2d[]>singletonList(aabb), 1.0, Collections.emptyList()
      );
      Coord corner = PrototypePathfinder.worldCell(origin, Coord2d.of(aabb[1].x - 0.5, aabb[1].y - 0.5));
      Assertions.assertTrue(boxed[corner.y * w + corner.x], "world AABB occupies its corner");
      Assertions.assertFalse(exact[corner.y * w + corner.x], "rotated desk must not collapse to that AABB");
   }

   @Test
   void rotatedBuildingFootprintKeepsOrientationNotWorldAabb() {
      Coord2d rc = Coord2d.of(0.0, 0.0);
      double a = Math.PI / 4.0;
      Coord2d[] known = PrototypePathfinder.knownBuildingFootprint("gfx/terobjs/arch/stonemansion", rc, a);
      Assertions.assertNotNull(known);
      Coord2d[] aabb = LocalPlanner.aabbPolygon(Collections.singletonList(known));
      Coord2d origin = PrototypePathfinder.alignedOrigin(-70.0, -70.0);
      int w = 56;
      int h = 56;
      boolean[] exact = new boolean[w * h];
      PrototypePathfinder.rasterObstacle(
         exact, origin, w, h, rc, false, "gfx/terobjs/arch/stonemansion", Collections.singletonList(known), 1.0, Collections.emptyList()
      );
      boolean[] boxed = new boolean[w * h];
      PrototypePathfinder.rasterObstacle(
         boxed, origin, w, h, rc, false, "gfx/terobjs/arch/stonemansion", Collections.singletonList(aabb), 1.0, Collections.emptyList()
      );
      Coord corner = PrototypePathfinder.worldCell(origin, Coord2d.of(aabb[2].x - 0.5, aabb[2].y - 0.5));
      Assertions.assertTrue(boxed[corner.y * w + corner.x], "world AABB occupies its corner");
      Assertions.assertFalse(exact[corner.y * w + corner.x], "rotated building must not collapse to that AABB");
   }

   @Test
   void authoritativeObstIsUsedInsteadOfFallback() {
      Coord2d rc = Coord2d.of(0.0, 0.0);
      Coord2d[] obst = new Coord2d[]{
         Coord2d.of(-6.0, -6.0), Coord2d.of(6.0, -6.0), Coord2d.of(6.0, 6.0), Coord2d.of(-6.0, 6.0)
      };
      CollisionGeom geom = PrototypePathfinder.furnitureGeometry(
         "gfx/terobjs/studydesk", rc, 0.0, Collections.<Coord2d[]>singletonList(obst), Collections.<Coord2d[]>emptyList()
      );
      Assertions.assertEquals(CollisionGeom.OBST, geom.source);
      Assertions.assertSame(obst, geom.polygons.get(0));
   }

   private static final class ArrayGrid implements Grid {
      final int w;
      final int h;
      final boolean[] blocked;

      ArrayGrid(int w, int h, boolean[] blocked) {
         this.w = w;
         this.h = h;
         this.blocked = blocked;
      }

      public int width() {
         return this.w;
      }

      public int height() {
         return this.h;
      }

      public boolean blocked(int x, int y) {
         return this.blocked[y * this.w + x];
      }
   }
}
