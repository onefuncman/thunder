package haven.pathfinding;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StockpileOrganizerTest {

   @Test
   void plankVariantsMapToBoardPile() {
      Assertions.assertEquals("gfx/terobjs/stockpile-board",
         StockpileOrganizer.stockpileResource("gfx/invobjs/board-pine"));
      Assertions.assertEquals("gfx/terobjs/stockpile-board",
         StockpileOrganizer.stockpileResource("gfx/invobjs/board-oak"));
      Assertions.assertEquals("gfx/terobjs/stockpile-board",
         StockpileOrganizer.stockpileResource("gfx/invobjs/board"));
   }

   @Test
   void metalBarVariantsMapToMetalPile() {
      Assertions.assertEquals("gfx/terobjs/stockpile-metal",
         StockpileOrganizer.stockpileResource("gfx/invobjs/bar-bronze"));
      Assertions.assertEquals("gfx/terobjs/stockpile-metal",
         StockpileOrganizer.stockpileResource("gfx/invobjs/bar-wroughtiron"));
      Assertions.assertEquals("gfx/terobjs/stockpile-metal",
         StockpileOrganizer.stockpileResource("gfx/invobjs/bar"));
   }

   @Test
   void exactNameItemsMapToTheirPile() {
      Assertions.assertEquals("gfx/terobjs/stockpile-soil",
         StockpileOrganizer.stockpileResource("gfx/invobjs/soil"));
      Assertions.assertEquals("gfx/terobjs/stockpile-soil",
         StockpileOrganizer.stockpileResource("gfx/invobjs/worm"));
      Assertions.assertEquals("gfx/terobjs/stockpile-pumpkin",
         StockpileOrganizer.stockpileResource("gfx/invobjs/pumpkin"));
      Assertions.assertEquals("gfx/terobjs/stockpile-straw",
         StockpileOrganizer.stockpileResource("gfx/invobjs/straw"));
      Assertions.assertEquals("gfx/terobjs/stockpile-brick",
         StockpileOrganizer.stockpileResource("gfx/invobjs/brick"));
      Assertions.assertEquals("gfx/terobjs/stockpile-leaf",
         StockpileOrganizer.stockpileResource("gfx/invobjs/leaf"));
      Assertions.assertEquals("gfx/terobjs/stockpile-leaf",
         StockpileOrganizer.stockpileResource("gfx/invobjs/leaves"));
   }

   @Test
   void unrelatedNamesRejected() {
      Assertions.assertNull(StockpileOrganizer.stockpileResource("gfx/invobjs/bark"));
      Assertions.assertNull(StockpileOrganizer.stockpileResource("gfx/invobjs/bark-birch"));
      Assertions.assertNull(StockpileOrganizer.stockpileResource("gfx/invobjs/metalplate"));
      Assertions.assertNull(StockpileOrganizer.stockpileResource("gfx/invobjs/branch"));
      Assertions.assertNull(StockpileOrganizer.stockpileResource("gfx/terobjs/stockpile-board"));
      Assertions.assertNull(StockpileOrganizer.stockpileResource(null));
   }
}
