package haven.pathfinding;

import haven.Coord2d;
import haven.layout.LayoutFootprint;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StockpileFootprintTest {

   private static void assertFootprint(int w, int h, LayoutFootprint actual) {
      Assertions.assertNotNull(actual);
      Assertions.assertEquals(w, actual.bboxW(), "width");
      Assertions.assertEquals(h, actual.bboxH(), "height");
      Assertions.assertTrue(actual.blocksApproach(), "blocksApproach");
   }

   @Test
   void boardSoilPumpkinReserveTwoByTwo() {
      assertFootprint(2, 2, ObjectFootprints.footprintFromHalf(Coord2d.of(11, 11)));
   }

   @Test
   void metalReservesOneByTwo() {
      // 11 x 16.5 world units -> 1 x 2 tiles (ceil of 1 and 1.5).
      assertFootprint(1, 2, ObjectFootprints.footprintFromHalf(Coord2d.of(5.5, 8.25)));
   }

   @Test
   void brickReservesTwoByOne() {
      // 22 x 11 world units -> 2 x 1 tiles.
      assertFootprint(2, 1, ObjectFootprints.footprintFromHalf(Coord2d.of(11, 5.5)));
   }

   @Test
   void strawAndLeafReserveTwoByTwo() {
      // 16.5 x 16.5 -> ceil(1.5) x ceil(1.5) = 2 x 2.
      assertFootprint(2, 2, ObjectFootprints.footprintFromHalf(Coord2d.of(8.25, 8.25)));
   }

   @Test
   void tinyFootprintsClampToOneCell() {
      assertFootprint(1, 1, ObjectFootprints.footprintFromHalf(Coord2d.of(1, 1)));
   }

   @Test
   void fallbackTableMatchesKnownHitboxes() {
      Assertions.assertEquals(Coord2d.of(5.5, 8.25), StockpileOrganizer.fallbackHalfExtents("gfx/terobjs/stockpile-metal"));
      Assertions.assertEquals(Coord2d.of(11, 5.5), StockpileOrganizer.fallbackHalfExtents("gfx/terobjs/stockpile-brick"));
      Assertions.assertEquals(Coord2d.of(8.25, 8.25), StockpileOrganizer.fallbackHalfExtents("gfx/terobjs/stockpile-straw"));
      Assertions.assertEquals(Coord2d.of(8.25, 8.25), StockpileOrganizer.fallbackHalfExtents("gfx/terobjs/stockpile-leaf"));
      Assertions.assertEquals(Coord2d.of(11, 11), StockpileOrganizer.fallbackHalfExtents("gfx/terobjs/stockpile-board"));
      Assertions.assertEquals(Coord2d.of(11, 11), StockpileOrganizer.fallbackHalfExtents("gfx/terobjs/stockpile-soil"));
      Assertions.assertEquals(Coord2d.of(11, 11), StockpileOrganizer.fallbackHalfExtents("gfx/terobjs/stockpile-pumpkin"));
      Assertions.assertEquals(Coord2d.of(11, 11), StockpileOrganizer.fallbackHalfExtents(null));
   }
}
