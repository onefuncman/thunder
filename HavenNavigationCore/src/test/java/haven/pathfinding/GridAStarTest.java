package haven.pathfinding;

import haven.Coord;
import haven.pathfinding.GridAStar.Grid;
import haven.pathfinding.GridAStar.Result;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GridAStarTest {
   @Test
   void fillCostsMatchAStarToAGoal() {
      GridAStarTest.TestGrid grid = new GridAStarTest.TestGrid(10, 10);
      Coord start = Coord.of(1, 1);
      Coord goal = Coord.of(8, 8);
      Result path = GridAStar.find(grid, start, goal, 1000);
      GridAStar.Fill fill = GridAStar.fill(grid, start, 1000);
      Assertions.assertTrue(path.complete);
      double expected = 0.0;
      for (int i = 1; i < path.cells.size(); i++) {
         Coord a = path.cells.get(i - 1);
         Coord b = path.cells.get(i);
         boolean diag = Math.abs(a.x - b.x) == 1 && Math.abs(a.y - b.y) == 1;
         expected += diag ? Math.sqrt(2.0) : 1.0;
      }
      Assertions.assertEquals(expected, fill.distance[goal.y * 10 + goal.x], 1.0E-9);
   }

   @Test
   void findsDiagonalPathAcrossOpenGrid() {
      GridAStarTest.TestGrid grid = new GridAStarTest.TestGrid(10, 10);
      Result result = GridAStar.find(grid, Coord.of(1, 1), Coord.of(8, 8), 1000);
      Assertions.assertTrue(result.complete);
      Assertions.assertEquals(Coord.of(1, 1), result.cells.get(0));
      Assertions.assertEquals(Coord.of(8, 8), result.cells.get(result.cells.size() - 1));
      Assertions.assertEquals(8, result.cells.size());
   }

   @Test
   void routesThroughGapInWall() {
      GridAStarTest.TestGrid grid = new GridAStarTest.TestGrid(12, 12);

      for (int y = 0; y < 12; y++) {
         if (y != 7) {
            grid.block(5, y);
         }
      }

      Result result = GridAStar.find(grid, Coord.of(2, 2), Coord.of(9, 2), 1000);
      Assertions.assertTrue(result.complete);
      Assertions.assertTrue(result.cells.contains(Coord.of(5, 7)));
   }

   @Test
   void diagonalCannotCutBlockedCorner() {
      GridAStarTest.TestGrid grid = new GridAStarTest.TestGrid(3, 3);
      grid.block(1, 0);
      grid.block(0, 1);
      Result result = GridAStar.find(grid, Coord.of(0, 0), Coord.of(2, 2), 100);
      Assertions.assertFalse(result.complete);
      Assertions.assertEquals(Collections.singletonList(Coord.of(0, 0)), result.cells);
   }

   @Test
   void returnsUsefulPartialPathWhenBudgetExpires() {
      GridAStarTest.TestGrid grid = new GridAStarTest.TestGrid(100, 3);
      Result result = GridAStar.find(grid, Coord.of(0, 1), Coord.of(99, 1), 12);
      Assertions.assertFalse(result.complete);
      Assertions.assertTrue(result.cells.size() > 1);
      Assertions.assertTrue(((Coord)result.cells.get(result.cells.size() - 1)).x > 0);
   }

   @Test
   void multiGoalPicksTheNearerCell() {
      GridAStarTest.TestGrid grid = new GridAStarTest.TestGrid(12, 5);
      Result result = GridAStar.find(grid, Coord.of(1, 2), List.of(Coord.of(10, 2), Coord.of(4, 2)), 1000);
      Assertions.assertTrue(result.complete);
      Assertions.assertEquals(Coord.of(4, 2), result.cells.get(result.cells.size() - 1));
   }

   @Test
   void multiGoalIgnoresBlockedGoalAndTakesTheOther() {
      GridAStarTest.TestGrid grid = new GridAStarTest.TestGrid(10, 5);
      grid.block(3, 2);
      Result result = GridAStar.find(grid, Coord.of(1, 2), List.of(Coord.of(3, 2), Coord.of(8, 2)), 1000);
      Assertions.assertTrue(result.complete);
      Assertions.assertEquals(Coord.of(8, 2), result.cells.get(result.cells.size() - 1));
   }

   @Test
   void noDiagonalStepBesideAnObstacle() {
      GridAStarTest.TestGrid grid = new GridAStarTest.TestGrid(8, 8);

      for (int y = 3; y <= 6; y++) {
         for (int x = 4; x <= 6; x++) {
            grid.block(x, y);
         }
      }

      Result result = GridAStar.find(grid, Coord.of(2, 5), Coord.of(5, 1), 1000);
      Assertions.assertTrue(result.complete);

      for (int i = 1; i < result.cells.size(); i++) {
         Coord a = (Coord)result.cells.get(i - 1);
         Coord b = (Coord)result.cells.get(i);
         boolean diagonal = Math.abs(a.x - b.x) == 1 && Math.abs(a.y - b.y) == 1;
         if (diagonal) {
            Assertions.assertFalse(
               GridAStar.besideObstacle(grid, a.x, a.y) || GridAStar.besideObstacle(grid, b.x, b.y), "diagonal " + a + " -> " + b + " hugs furniture"
            );
         }
      }
   }

   private static final class TestGrid implements Grid {
      final int w;
      final int h;
      final boolean[] blocked;

      TestGrid(int w, int h) {
         this.w = w;
         this.h = h;
         this.blocked = new boolean[w * h];
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

      void block(int x, int y) {
         this.blocked[y * this.w + x] = true;
      }
   }
}
