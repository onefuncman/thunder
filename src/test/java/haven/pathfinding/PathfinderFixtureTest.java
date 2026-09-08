package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.Hitbox;
import haven.pathfinding.GridAStar.Grid;
import haven.pathfinding.GridAStar.Result;
import haven.pathfinding.PathfinderLog.Occupancy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PathfinderFixtureTest {
   private static final double CELL = 2.75;
   private static final String CUPBOARD = "gfx/terobjs/cupboard";
   private static final String PALISADE = "gfx/terobjs/arch/palisadeseg";
   private static final String CHEST = "gfx/terobjs/chest";

   private static Coord2d[] square(double half, double cx, double cy) {
      return new Coord2d[]{
         Coord2d.of(cx - half, cy - half), Coord2d.of(cx + half, cy - half), Coord2d.of(cx + half, cy + half), Coord2d.of(cx - half, cy + half)
      };
   }

   private static Coord2d[] box10(double cx, double cy) {
      return square(5.0, cx, cy);
   }

   private static List<Coord2d[]> diamondBody() {
      return Collections.singletonList(new Coord2d[]{Coord2d.of(4.0, 1.0), Coord2d.of(4.0, -1.0), Coord2d.of(-4.0, -1.0), Coord2d.of(-4.0, 1.0)});
   }

   private static boolean rasterFurniture(boolean[] blocked, Coord2d origin, int w, int h, Coord2d center) {
      return PrototypePathfinder.rasterObstacle(
         blocked, origin, w, h, center, false, "gfx/terobjs/cupboard", List.<Coord2d[]>of(box10(center.x, center.y)), 1.0, Collections.emptyList()
      );
   }

   private static Coord2d[] wall(double x0, double x1) {
      return new Coord2d[]{Coord2d.of(x0, -30.0), Coord2d.of(x1, -30.0), Coord2d.of(x1, 30.0), Coord2d.of(x0, 30.0)};
   }

   private static void rasterWall(boolean[] blocked, Coord2d origin, int w, int h, boolean inflate, Coord2d[] wallPoly) {
      PrototypePathfinder.rasterObstacle(
         blocked,
         origin,
         w,
         h,
         Coord2d.of(wallPoly[0].x + 0.5, 0.0),
         inflate,
         "gfx/terobjs/arch/palisadeseg",
         List.<Coord2d[]>of(wallPoly),
         1.0,
         inflate ? diamondBody() : Collections.emptyList()
      );
   }

   private static Coord cellAt(Coord2d origin, Coord2d p) {
      return PrototypePathfinder.worldCell(origin, p);
   }

   @Test
   void packedCupboardsUseObstacleOnlyAndAreNeverInflated() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-22.0, -11.0);
      int w = 20;
      int h = 12;
      boolean[] solid = new boolean[w * h];
      rasterFurniture(solid, origin, w, h, Coord2d.of(0.0, 0.0));
      rasterFurniture(solid, origin, w, h, Coord2d.of(11.0, 0.0));
      Coord inside = cellAt(origin, Coord2d.of(0.0, 0.0));
      Assertions.assertTrue(solid[inside.y * w + inside.x], "the cupboard body itself is solid");
      Coord aisle = cellAt(origin, Coord2d.of(5.5, 9.0));
      Assertions.assertTrue(aisle.x >= 0 && aisle.x < w && aisle.y >= 0 && aisle.y < h);
      Assertions.assertFalse(solid[aisle.y * w + aisle.x], "the packed aisle stays walkable");
      boolean[] prepadded = new boolean[w * h];
      double oldPrepad = 2.45;

      for (Coord2d at : new Coord2d[]{Coord2d.of(0.0, 0.0), Coord2d.of(11.0, 0.0)}) {
         PrototypePathfinder.rasterPolygon(prepadded, origin, w, h, box10(at.x, at.y), oldPrepad);
      }

      PrototypePathfinder.dilate(prepadded, w, h, 1);
      Assertions.assertTrue(prepadded[aisle.y * w + aisle.x], "the removed furniturePrepad sealed this aisle row");
      boolean[] dilated = Arrays.copyOf(solid, solid.length);

      for (Coord2d at : new Coord2d[]{Coord2d.of(0.0, 0.0), Coord2d.of(11.0, 0.0)}) {
         PrototypePathfinder.rasterObstacle(dilated, origin, w, h, at, true, "gfx/terobjs/cupboard", List.<Coord2d[]>of(box10(at.x, at.y)), 1.0, diamondBody());
      }

      Assertions.assertArrayEquals(solid, dilated, "packed furniture is never Minkowski-inflated");
      Coord stand = cellAt(origin, Coord2d.of(11.0, 8.5));
      Assertions.assertFalse(dilated[stand.y * w + stand.x], "0.32-tile stand stays legal after the body pass");
   }

   @Test
   void chairsAndChestsAreNeverBodyInflated() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-22.0, -11.0);
      int w = 20;
      int h = 12;
      Coord2d at = Coord2d.of(0.0, 0.0);
      boolean[] solid = new boolean[w * h];
      PrototypePathfinder.rasterObstacle(
         solid, origin, w, h, at, false, "gfx/terobjs/chair", List.<Coord2d[]>of(box10(at.x, at.y)), 1.0, Collections.emptyList()
      );
      boolean[] dilated = Arrays.copyOf(solid, solid.length);
      PrototypePathfinder.rasterObstacle(
         dilated, origin, w, h, at, true, "gfx/terobjs/chest", List.<Coord2d[]>of(box10(at.x, at.y)), 1.0, diamondBody()
      );
      Assertions.assertArrayEquals(solid, dilated, "household furniture is never Minkowski-inflated");
   }

   @Test
   void idlePlayerStanding032TilesFromACupboardIsNotSolid() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-22.0, -22.0);
      int w = 24;
      int h = 24;
      boolean[] solid = new boolean[w * h];
      rasterFurniture(solid, origin, w, h, Coord2d.of(0.0, 0.0));
      Coord idle = cellAt(origin, Coord2d.of(8.5, 0.0));
      Assertions.assertFalse(solid[idle.y * w + idle.x], "idle player 0.32 tiles off the face is not solid");
      Occupancy occ = Occupancy.capture(origin, w, h, 2.75, solid, solid, solid, idle, null, null, Collections.emptyList());
      Assertions.assertEquals((byte)0, occ.at(idle.x, idle.y));
   }

   @Test
   void oneTileCorridorFitsTheBodyAtItsCenterButNotAtTheHug() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-20.0, -20.0);
      int w = 20;
      int h = 10;
      Coord2d[] left = wall(0.0, 1.0);
      Coord2d[] right = wall(12.0, 13.0);
      boolean[] solid = new boolean[w * h];
      rasterWall(solid, origin, w, h, false, left);
      rasterWall(solid, origin, w, h, false, right);
      boolean[] dilated = Arrays.copyOf(solid, solid.length);
      rasterWall(dilated, origin, w, h, true, left);
      rasterWall(dilated, origin, w, h, true, right);
      Coord mid = cellAt(origin, Coord2d.of(6.5, 0.0));
      Assertions.assertFalse(solid[mid.y * w + mid.x]);
      Assertions.assertFalse(dilated[mid.y * w + mid.x], "1-tile corridor fits the body at the center");
      Coord hug = cellAt(origin, Coord2d.of(3.5, 0.0));
      Assertions.assertFalse(solid[hug.y * w + hug.x], "the hug cell is not furniture-solid");
      Assertions.assertTrue(dilated[hug.y * w + hug.x], "standing 3.5u from a wall overlaps the ~4.2 body");
   }

   @Test
   void snapDoesNotJumpAcrossAWall() {
      int w = 14;
      int h = 9;
      boolean[] blocked = new boolean[w * h];

      for (int y = 2; y <= 6; y++) {
         blocked[y * w + 5] = true;
         blocked[y * w + 6] = true;
      }

      Coord from = Coord.of(0, 4);
      Coord snapped = PrototypePathfinder.nearestFree(blocked, w, h, Coord.of(5, 4), from);
      Assertions.assertNotNull(snapped);
      Assertions.assertTrue(snapped.x < 5, "snap must land on the player's side of the wall, not through it");
      Assertions.assertFalse(blocked[snapped.y * w + snapped.x]);
      Result result = GridAStar.find(new PathfinderFixtureTest.ArrayGrid(w, h, blocked), from, Coord.of(9, 4), 10000);
      Assertions.assertTrue(result.complete, "a wall 5 cells tall leaves a route around");
      Assertions.assertTrue(result.cells.size() > 10, "route must bend around the wall, not cut through it");

      for (Coord c : result.cells) {
         Assertions.assertFalse(blocked[c.y * w + c.x], "path never crosses a blocked wall cell");
      }
   }

   @Test
   void unknownLoadingGeometryBlocksInsteadOfBeingTraversable() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-11.0, -11.0);
      int w = 12;
      int h = 12;
      boolean[] solid = new boolean[w * h];
      PrototypePathfinder.rasterObstacle(solid, origin, w, h, Coord2d.of(0.0, 0.0), false, "gfx/terobjs/cupboard", null, 11.0, Collections.emptyList());
      Coord at = cellAt(origin, Coord2d.of(0.0, 0.0));
      Assertions.assertTrue(solid[at.y * w + at.x], "unknown geometry must be blocked, not traversable");
      boolean[] dilated = Arrays.copyOf(solid, solid.length);
      PrototypePathfinder.rasterObstacle(dilated, origin, w, h, Coord2d.of(0.0, 0.0), true, "gfx/terobjs/cupboard", null, 11.0, diamondBody());
      Assertions.assertArrayEquals(solid, dilated, "unknown furniture is never inflated");
      boolean[] empty = new boolean[w * h];
      PrototypePathfinder.rasterObstacle(
         empty, origin, w, h, Coord2d.of(5.5, 5.5), false, "gfx/terobjs/chest", Collections.emptyList(), 6.6, Collections.emptyList()
      );
      Coord e = cellAt(origin, Coord2d.of(5.5, 5.5));
      Assertions.assertFalse(empty[e.y * w + e.x], "empty furniture layers are unavailable, not a silent disk");
   }

   @Test
   void negOnlyFurnitureIsPlacementNotMovement() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-11.0, -11.0);
      int w = 12;
      int h = 12;
      boolean[] blocked = new boolean[w * h];
      boolean rastered = PrototypePathfinder.rasterObstacle(
         blocked, origin, w, h, Coord2d.of(0.0, 0.0), false, "gfx/terobjs/cupboard", Collections.emptyList(), 1.0, Collections.emptyList()
      );
      Assertions.assertFalse(rastered, "Neg-only furniture contributes no occupancy");
      Coord inside = cellAt(origin, Coord2d.of(0.0, 0.0));
      Assertions.assertFalse(blocked[inside.y * w + inside.x], "a Neg-only 10×10 is not movement-solid");
   }

   @Test
   void obstacleLayerIsMovementNegIsPlacement() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-11.0, -11.0);
      int w = 12;
      int h = 12;
      List<Coord2d[]> neg = List.<Coord2d[]>of(box10(0.0, 0.0));
      List<Coord2d[]> obst = List.<Coord2d[]>of(square(1.0, 0.0, 0.0));
      List<Coord2d[]> movement = Hitbox.selectMovementLayers(neg, obst);
      Assertions.assertEquals(1, movement.size());
      Assertions.assertSame(obst.get(0), movement.get(0));
      boolean[] blocked = new boolean[w * h];
      PrototypePathfinder.rasterObstacle(blocked, origin, w, h, Coord2d.of(0.0, 0.0), false, "gfx/terobjs/chest", movement, 6.6, Collections.emptyList());
      Coord core = cellAt(origin, Coord2d.of(0.0, 0.0));
      Assertions.assertTrue(blocked[core.y * w + core.x], "the Obstacle box is movement-solid");
      Coord insideNeg = cellAt(origin, Coord2d.of(4.0, 0.0));
      Assertions.assertFalse(blocked[insideNeg.y * w + insideNeg.x], "the Neg box is placement only and must not block walking");
      boolean[] negOnly = new boolean[w * h];
      PrototypePathfinder.rasterObstacle(
         negOnly,
         origin,
         w,
         h,
         Coord2d.of(0.0, 0.0),
         false,
         "gfx/terobjs/chest",
         Hitbox.selectMovementLayers(neg, Collections.emptyList()),
         6.6,
         Collections.emptyList()
      );
      Coord inside2 = cellAt(origin, Coord2d.of(4.0, 0.0));
      Assertions.assertTrue(negOnly[inside2.y * w + inside2.x], "without an Obstacle layer the Neg box is the movement solid");
   }

   @Test
   void narrowValidAisleStaysOpenUnlessClearanceIsAppliedTwice() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-22.0, -11.0);
      int w = 24;
      int h = 12;
      boolean[] solid = new boolean[w * h];
      rasterFurniture(solid, origin, w, h, Coord2d.of(0.0, 0.0));
      rasterFurniture(solid, origin, w, h, Coord2d.of(11.0, 0.0));
      Coord aisle = cellAt(origin, Coord2d.of(5.5, 9.0));
      Assertions.assertFalse(solid[aisle.y * w + aisle.x], "narrow valid cupboard aisle stays walkable");
      boolean[] dilated = Arrays.copyOf(solid, solid.length);
      PrototypePathfinder.rasterObstacle(
         dilated, origin, w, h, Coord2d.of(0.0, 0.0), true, CUPBOARD, List.<Coord2d[]>of(box10(0.0, 0.0)), 1.0, diamondBody()
      );
      PrototypePathfinder.rasterObstacle(
         dilated, origin, w, h, Coord2d.of(11.0, 0.0), true, CUPBOARD, List.<Coord2d[]>of(box10(11.0, 0.0)), 1.0, diamondBody()
      );
      Assertions.assertArrayEquals(solid, dilated, "player Minkowski must not run again on furniture");
      boolean[] twice = Arrays.copyOf(solid, solid.length);
      PrototypePathfinder.dilate(twice, w, h, 1);
      Assertions.assertTrue(twice[aisle.y * w + aisle.x], "applying occupancy dilation on top of exact furniture seals the aisle");
   }

   @Test
   void genuinelyTooNarrowAisleIsBlocked() {
      Coord2d origin = PrototypePathfinder.alignedOrigin(-22.0, -11.0);
      int w = 20;
      int h = 12;
      boolean[] solid = new boolean[w * h];
      rasterFurniture(solid, origin, w, h, Coord2d.of(0.0, 0.0));
      rasterFurniture(solid, origin, w, h, Coord2d.of(10.5, 0.0));
      Coord pinch = cellAt(origin, Coord2d.of(5.25, 0.0));
      Assertions.assertTrue(solid[pinch.y * w + pinch.x], "a 0.5u gap is not a legal passage");
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
