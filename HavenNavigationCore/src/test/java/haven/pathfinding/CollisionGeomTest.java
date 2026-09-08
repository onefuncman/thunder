package haven.pathfinding;

import haven.Coord2d;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CollisionGeomTest {
   private static Coord2d[] box(double hx, double hy) {
      return new Coord2d[]{
         Coord2d.of(-hx, -hy), Coord2d.of(hx, -hy), Coord2d.of(hx, hy), Coord2d.of(-hx, hy)
      };
   }

   @Test
   void furnitureChoiceReasonDistinguishesEmptyFromStub() {
      Coord2d[] known = box(6.0, 16.0);
      Assertions.assertEquals("obst", CollisionGeom.furnitureChoiceReason(Collections.singletonList(box(6.0, 16.0)), known));
      Assertions.assertEquals("stub_obst_fallback", CollisionGeom.furnitureChoiceReason(Collections.singletonList(box(1.0, 1.0)), known));
      Assertions.assertEquals("empty_obst_fallback", CollisionGeom.furnitureChoiceReason(Collections.<Coord2d[]>emptyList(), known));
      Assertions.assertEquals(CollisionGeom.UNAVAILABLE, CollisionGeom.furnitureChoiceReason(Collections.<Coord2d[]>emptyList(), null));
      double[] sz = CollisionGeom.size(known);
      Assertions.assertEquals(12.0, sz[0], 1.0E-9);
      Assertions.assertEquals(32.0, sz[1], 1.0E-9);
   }

   @Test
   void tinyObstIsNotMeaningful() {
      List<Coord2d[]> tiny = new ArrayList<Coord2d[]>();
      tiny.add(box(1.0, 1.0));
      Assertions.assertFalse(CollisionGeom.meaningful(tiny));
      Assertions.assertTrue(CollisionGeom.meaningful(Collections.singletonList(box(6.0, 16.0))));
   }

   @Test
   void furnitureUsesObstOrFallbackNeverUnion() {
      Coord2d[] known = box(6.0, 16.0);
      CollisionGeom obst = CollisionGeom.furniture(Collections.singletonList(box(6.0, 16.0)), known);
      Assertions.assertEquals(CollisionGeom.OBST, obst.source);
      Assertions.assertEquals(1, obst.polygons.size());
      CollisionGeom fallback = CollisionGeom.furniture(Collections.singletonList(box(1.0, 1.0)), known);
      Assertions.assertEquals(CollisionGeom.FALLBACK, fallback.source);
      Assertions.assertEquals(1, fallback.polygons.size());
      Assertions.assertSame(known, fallback.polygons.get(0));
      CollisionGeom missing = CollisionGeom.furniture(Collections.singletonList(box(1.0, 1.0)), null);
      Assertions.assertTrue(missing.unavailable());
      Assertions.assertTrue(missing.polygons.isEmpty());
   }

   @Test
   void targetPrefersObstThenMovementNeverEmptyStub() {
      List<Coord2d[]> obst = Collections.singletonList(box(8.0, 3.0));
      List<Coord2d[]> placement = Collections.singletonList(box(20.0, 20.0));
      CollisionGeom g = CollisionGeom.target(obst, placement);
      Assertions.assertEquals(CollisionGeom.OBST, g.source);
      Assertions.assertEquals(1, g.polygons.size());
      CollisionGeom fromMove = CollisionGeom.target(Collections.singletonList(box(1.0, 1.0)), placement);
      Assertions.assertEquals(CollisionGeom.MOVEMENT, fromMove.source);
      CollisionGeom none = CollisionGeom.target(Collections.<Coord2d[]>emptyList(), Collections.<Coord2d[]>emptyList());
      Assertions.assertTrue(none.unavailable());
   }
}
