package haven.pathfinding;

import haven.Coord2d;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class OrganizerAreaSelectorTest {

   @Test
   void firstClickRecordsAVertexAndStaysInProgress() {
      OrganizerAreaSelector selector = new OrganizerAreaSelector();
      Assertions.assertEquals(OrganizerAreaSelector.State.EMPTY, selector.state());
      Assertions.assertNull(selector.click(Coord2d.of(5.0, 5.0)));
      Assertions.assertEquals(OrganizerAreaSelector.State.IN_PROGRESS, selector.state());
      Assertions.assertEquals(1, selector.vertexCount());
      Assertions.assertNull(selector.selection());
   }

   @Test
   void fourClicksCompletePolygonAndExposeBoundingBox() {
      OrganizerAreaSelector selector = new OrganizerAreaSelector();
      selector.click(Coord2d.of(5.0, 5.0));        // tile (0,0)
      selector.click(Coord2d.of(20.0, 5.0));       // tile (1,0)
      selector.click(Coord2d.of(20.0, 20.0));      // tile (1,1)
      OrganizerAreaSelector.Selection sel = selector.click(Coord2d.of(5.0, 20.0)); // tile (0,1)
      Assertions.assertNotNull(sel);
      Assertions.assertEquals(OrganizerAreaSelector.State.COMPLETE, selector.state());
      Assertions.assertEquals(4, sel.vertexCount());
      // bounding box of vertices (0,0),(1,0),(1,1),(0,1) is tiles 0..1
      Assertions.assertEquals(2, sel.widthTiles());
      Assertions.assertEquals(2, sel.heightTiles());
      Assertions.assertEquals(4, sel.tileCount());
      Assertions.assertEquals(Coord2d.of(0.0, 0.0), sel.min);
      Assertions.assertEquals(Coord2d.of(22.0, 22.0), sel.max);
      // unresolved (no live map) => no grid vertices
      Assertions.assertTrue(sel.gridVertices.isEmpty());
   }

   @Test
   void singleTileSelectionHasUnitBounds() {
      OrganizerAreaSelector selector = new OrganizerAreaSelector();
      selector.click(Coord2d.of(1.0, 2.0));
      selector.click(Coord2d.of(3.0, 4.0));
      selector.click(Coord2d.of(5.0, 6.0));
      OrganizerAreaSelector.Selection sel = selector.click(Coord2d.of(7.0, 8.0)); // all tile (0,0)
      Assertions.assertEquals(1, sel.widthTiles());
      Assertions.assertEquals(1, sel.heightTiles());
      Assertions.assertEquals(1, sel.tileCount());
      Assertions.assertEquals(Coord2d.of(0.0, 0.0), sel.min);
      Assertions.assertEquals(Coord2d.of(11.0, 11.0), sel.max);
   }

   @Test
   void cancelResetsEverything() {
      OrganizerAreaSelector selector = new OrganizerAreaSelector();
      selector.click(Coord2d.of(0.0, 0.0));
      selector.click(Coord2d.of(20.0, 0.0));
      Assertions.assertEquals(2, selector.vertexCount());
      selector.cancel();
      Assertions.assertEquals(OrganizerAreaSelector.State.EMPTY, selector.state());
      Assertions.assertNull(selector.selection());
      Assertions.assertEquals(0, selector.vertexCount());
      Assertions.assertNull(selector.firstTile());
   }
}
