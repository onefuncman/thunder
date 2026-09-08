package haven.pathfinding;

import haven.Coord2d;
import haven.layout.LayoutFootprint;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ObjectFootprintsTest {

   @Test
   void nullResnameUsesGenericOneTileDefault() {
      LayoutFootprint fp = ObjectFootprints.footprintFor(null);
      Assertions.assertNotNull(fp);
      Assertions.assertEquals(1, fp.bboxW());
      Assertions.assertEquals(1, fp.bboxH());
      Assertions.assertTrue(fp.blocksApproach());
   }

   @Test
   void nullResnameHonorsExplicitFallback() {
      LayoutFootprint fp = ObjectFootprints.footprintFor(null, Coord2d.of(11, 11));
      Assertions.assertEquals(2, fp.bboxW());
      Assertions.assertEquals(2, fp.bboxH());
   }

   @Test
   void invalidFallbackFallsThroughToDefault() {
      LayoutFootprint fp = ObjectFootprints.footprintFor(null, Coord2d.of(0, 0));
      Assertions.assertEquals(1, fp.bboxW());
      Assertions.assertEquals(1, fp.bboxH());
   }

   @Test
   void halfExtentsClampToMinimumOneCell() {
      LayoutFootprint fp = ObjectFootprints.footprintFromHalf(Coord2d.of(0.1, 0.1));
      Assertions.assertEquals(1, fp.bboxW());
      Assertions.assertEquals(1, fp.bboxH());
   }

   @Test
   void halfExtentsRoundUpToWholeTiles() {
      // 16.5 x 16.5 world units -> ceil(1.5) x ceil(1.5) = 2 x 2 cells.
      LayoutFootprint fp = ObjectFootprints.footprintFromHalf(Coord2d.of(8.25, 8.25));
      Assertions.assertEquals(2, fp.bboxW());
      Assertions.assertEquals(2, fp.bboxH());
   }
}
