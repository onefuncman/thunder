package haven.pathfinding;

import haven.Coord;
import haven.pathfinding.CoarseRoutePlanner.Cause;
import haven.pathfinding.CoarseRoutePlanner.Route;
import haven.pathfinding.CoarseRoutePlanner.Status;
import haven.pathfinding.CoarseTileSource.Tile;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CoarseRoutePlannerTest {
   private static Route plan(CoarseRoutePlannerTest.Tiles src, Coord start, Coord goal) {
      return CoarseRoutePlanner.plan(src, start, goal, 1000000);
   }

   @Test
   void openKnownTerrainReachesGoal() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(40, 40);
      Coord start = Coord.of(2, 20);
      Coord goal = Coord.of(37, 20);
      Route route = plan(src, start, goal);
      Assertions.assertEquals(Status.REACHED, route.status);
      Assertions.assertNull(route.cause);
      Assertions.assertTrue(route.expanded > 0);
      Assertions.assertEquals(start, route.waypoints.get(0), "route starts at the start tile");
      Assertions.assertEquals(goal, route.waypoints.get(route.waypoints.size() - 1), "route ends at the goal tile");
      Assertions.assertEquals(List.of(start, goal), route.waypoints, "open ground collapses to just start and goal — coarse, not per-cell");
   }

   @Test
   void startEqualsGoalIsTrivialArrival() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(10, 10);
      Coord at = Coord.of(5, 5);
      Route route = plan(src, at, at);
      Assertions.assertEquals(Status.REACHED, route.status);
      Assertions.assertEquals(0, route.expanded);
      Assertions.assertEquals(List.of(at), route.waypoints);
   }

   @Test
   void blockedTerrainDetoursAround() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(40, 40);
      src.set(20, 6, 20, 33, Tile.BLOCKED);
      Coord start = Coord.of(2, 20);
      Coord goal = Coord.of(37, 20);
      Route route = plan(src, start, goal);
      Assertions.assertEquals(Status.REACHED, route.status);
      Assertions.assertEquals(start, route.waypoints.get(0));
      Assertions.assertEquals(goal, route.waypoints.get(route.waypoints.size() - 1));
      Assertions.assertTrue(route.waypoints.size() >= 3, "the wall forces a detour, so more than two waypoints");
      boolean turned = false;

      for (Coord w : route.waypoints) {
         Assertions.assertNotEquals(Tile.BLOCKED, src.tile(w.x, w.y), "no waypoint sits in blocked terrain");
         if (w.y < 6 || w.y > 33) {
            turned = true;
         }
      }

      Assertions.assertTrue(turned, "the coarse route must go around the wall, not through it");
   }

   @Test
   void detourRouteUsesKnownGapOnly() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(30, 30);
      src.set(15, 0, 15, 13, Tile.BLOCKED);
      src.set(15, 15, 15, 29, Tile.BLOCKED);
      Coord start = Coord.of(2, 15);
      Coord goal = Coord.of(27, 15);
      Route route = plan(src, start, goal);
      Assertions.assertEquals(Status.REACHED, route.status);
      Assertions.assertTrue(route.waypoints.size() >= 3);
      boolean throughGap = false;

      for (int i = 0; i < route.waypoints.size(); i++) {
         Coord w = (Coord)route.waypoints.get(i);
         Assertions.assertNotEquals(Tile.BLOCKED, src.tile(w.x, w.y));
         if (w.y == 14) {
            throughGap = true;
         }
      }

      Assertions.assertTrue(throughGap, "route crosses the wall only at the known gap");
   }

   @Test
   void unknownGapIsRejectedNotRoutedThrough() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(40, 40);
      src.set(20, 0, 20, 39, Tile.UNKNOWN);
      Coord start = Coord.of(2, 20);
      Coord goal = Coord.of(37, 20);
      Route route = plan(src, start, goal);
      Assertions.assertEquals(Status.NO_KNOWN_ROUTE, route.status);
      Assertions.assertEquals(Cause.UNKNOWN_GAP, route.cause, "a route may exist beyond the unexplored cells — that is not a known dead end");
      Assertions.assertTrue(route.waypoints.isEmpty(), "no route may be claimed through unknown cells");
      Assertions.assertTrue(route.expanded > 0);
   }

   @Test
   void unknownStartIsInvalid() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(10, 10);
      src.set(2, 2, 2, 2, Tile.UNKNOWN);
      Assertions.assertEquals(Status.INVALID_START, plan(src, Coord.of(2, 2), Coord.of(8, 8)).status);
   }

   @Test
   void unknownGoalIsInvalid() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(10, 10);
      src.set(8, 8, 8, 8, Tile.UNKNOWN);
      Assertions.assertEquals(Status.INVALID_GOAL, plan(src, Coord.of(2, 2), Coord.of(8, 8)).status);
   }

   @Test
   void unreachableBehindFullKnownWall() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(40, 40);
      src.set(20, 0, 20, 39, Tile.BLOCKED);
      Coord start = Coord.of(2, 20);
      Coord goal = Coord.of(37, 20);
      Route route = plan(src, start, goal);
      Assertions.assertEquals(Status.NO_KNOWN_ROUTE, route.status);
      Assertions.assertEquals(Cause.KNOWN_BLOCKED, route.cause, "known terrain seals the region — a true dead end");
      Assertions.assertTrue(route.waypoints.isEmpty());
   }

   @Test
   void segmentEdgeIsHardBoundaryNotUnknownGap() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(10, 10);
      src.set(0, 5, 9, 5, Tile.BLOCKED);
      Coord start = Coord.of(2, 2);
      Coord goal = Coord.of(2, 8);
      Route route = plan(src, start, goal);
      Assertions.assertEquals(Status.NO_KNOWN_ROUTE, route.status);
      Assertions.assertEquals(Cause.KNOWN_BLOCKED, route.cause);
   }

   @Test
   void searchBoundExhaustionIsTyped() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(40, 40);
      Coord start = Coord.of(2, 2);
      Coord goal = Coord.of(37, 37);
      Route route = CoarseRoutePlanner.plan(src, start, goal, 30);
      Assertions.assertEquals(Status.EXHAUSTED, route.status);
      Assertions.assertEquals(30, route.expanded, "the budget is consumed exactly");
      Assertions.assertTrue(route.waypoints.isEmpty(), "no route may be claimed past an exhausted search");
   }

   @Test
   void zeroBudgetIsExhaustedImmediately() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(10, 10);
      Route route = CoarseRoutePlanner.plan(src, Coord.of(0, 0), Coord.of(9, 9), 0);
      Assertions.assertEquals(Status.EXHAUSTED, route.status);
      Assertions.assertEquals(0, route.expanded);
   }

   @Test
   void fullyExploredSmallRegionIsNotExhaustion() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(10, 10);
      src.set(0, 5, 9, 5, Tile.BLOCKED);
      Route route = CoarseRoutePlanner.plan(src, Coord.of(2, 2), Coord.of(2, 8), 10000);
      Assertions.assertEquals(Status.NO_KNOWN_ROUTE, route.status);
      Assertions.assertTrue(route.expanded < 10000);
   }

   @Test
   void blockedStartIsInvalid() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(10, 10);
      src.set(0, 0, 0, 0, Tile.BLOCKED);
      Assertions.assertEquals(Status.INVALID_START, plan(src, Coord.of(0, 0), Coord.of(9, 9)).status);
   }

   @Test
   void offBoundsStartIsInvalid() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(10, 10);
      Assertions.assertEquals(Status.INVALID_START, plan(src, Coord.of(-1, 5), Coord.of(9, 9)).status);
      Assertions.assertEquals(Status.INVALID_START, plan(src, Coord.of(10, 5), Coord.of(9, 9)).status);
      Assertions.assertEquals(Status.INVALID_START, plan(src, null, Coord.of(9, 9)).status);
   }

   @Test
   void blockedGoalIsInvalid() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(10, 10);
      src.set(9, 9, 9, 9, Tile.BLOCKED);
      Assertions.assertEquals(Status.INVALID_GOAL, plan(src, Coord.of(0, 0), Coord.of(9, 9)).status);
   }

   @Test
   void offBoundsGoalIsInvalid() {
      CoarseRoutePlannerTest.Tiles src = new CoarseRoutePlannerTest.Tiles(10, 10);
      Assertions.assertEquals(Status.INVALID_GOAL, plan(src, Coord.of(0, 0), Coord.of(9, 10)).status);
      Assertions.assertEquals(Status.INVALID_GOAL, plan(src, Coord.of(0, 0), null).status);
   }

   @Test
   void nullSourceIsInvalid() {
      Assertions.assertEquals(Status.INVALID_START, CoarseRoutePlanner.plan(null, Coord.of(0, 0), Coord.of(1, 1)).status);
   }

   @Test
   void classifyUsesSharedTerrainPolicy() {
      Assertions.assertEquals(Tile.FREE, CoarseTileSource.classify("gfx/tiles/grass"));
      Assertions.assertEquals(Tile.FREE, CoarseTileSource.classify("gfx/tiles/road"));
      Assertions.assertEquals(Tile.BLOCKED, CoarseTileSource.classify("gfx/tiles/nil"));
      Assertions.assertEquals(Tile.BLOCKED, CoarseTileSource.classify("gfx/tiles/cave"));
      Assertions.assertEquals(Tile.BLOCKED, CoarseTileSource.classify("gfx/tiles/deep/water"));
      Assertions.assertEquals(Tile.BLOCKED, CoarseTileSource.classify("gfx/tiles/rocks/mountain"));
      Assertions.assertEquals(Tile.UNKNOWN, CoarseTileSource.classify("gfx/tiles/notile"));
      Assertions.assertEquals(Tile.UNKNOWN, CoarseTileSource.classify(null));
   }

   @Test
   void terrainPolicyPredicates() {
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/nil"));
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/cave"));
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/deep"));
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/rocks/boulder"));
      Assertions.assertFalse(TerrainPolicy.terrainBlocks("gfx/tiles/grass"));
      Assertions.assertFalse(TerrainPolicy.terrainBlocks(null));
      Assertions.assertTrue(TerrainPolicy.isUnknownTile("gfx/tiles/notile"));
      Assertions.assertTrue(TerrainPolicy.isUnknownTile(null));
      Assertions.assertFalse(TerrainPolicy.isUnknownTile("gfx/tiles/grass"));
   }

   @Test
   void fromNamesRoutesThroughPersistedNames() {
      int w = 7;
      int h = 5;
      String[] names = new String[w * h];
      Arrays.fill(names, "gfx/tiles/grass");
      names[2 * w + 3] = "gfx/tiles/cave";
      CoarseTileSource src = CoarseTileSource.fromNames(w, h, names);
      Route route = CoarseRoutePlanner.plan(src, Coord.of(0, 2), Coord.of(6, 2));
      Assertions.assertEquals(Status.REACHED, route.status);
      Assertions.assertEquals(Coord.of(0, 2), route.waypoints.get(0));
      Assertions.assertEquals(Coord.of(6, 2), route.waypoints.get(route.waypoints.size() - 1));
      Assertions.assertTrue(route.waypoints.size() >= 3, "the known cave forces a detour");

      for (Coord wpt : route.waypoints) {
         Assertions.assertNotEquals(Tile.BLOCKED, src.tile(wpt.x, wpt.y));
      }
   }

   @Test
   void fromNamesRejectsNotileGap() {
      int w = 7;
      int h = 5;
      String[] names = new String[w * h];
      Arrays.fill(names, "gfx/tiles/grass");

      for (int y = 0; y < h; y++) {
         names[y * w + 3] = "gfx/tiles/notile";
      }

      CoarseTileSource src = CoarseTileSource.fromNames(w, h, names);
      Route route = CoarseRoutePlanner.plan(src, Coord.of(0, 2), Coord.of(6, 2));
      Assertions.assertEquals(Status.NO_KNOWN_ROUTE, route.status);
      Assertions.assertEquals(Cause.UNKNOWN_GAP, route.cause);
   }

   private static final class Tiles implements CoarseTileSource {
      final int w;
      final int h;
      final Tile[] t;

      Tiles(int w, int h) {
         this.w = w;
         this.h = h;
         this.t = new Tile[w * h];
         Arrays.fill(this.t, Tile.FREE);
      }

      CoarseRoutePlannerTest.Tiles set(int x0, int y0, int x1, int y1, Tile v) {
         for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
               this.t[y * this.w + x] = v;
            }
         }

         return this;
      }

      public int width() {
         return this.w;
      }

      public int height() {
         return this.h;
      }

      public Tile tile(int x, int y) {
         return this.t[y * this.w + x];
      }
   }
}
