package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BotMovementTest {
   @Test
   void exposesOnlyTheFourMovementModes() {
      Assertions.assertArrayEquals(new BotMovement.Mode[]{
         BotMovement.Mode.LAND,
         BotMovement.Mode.BOAT_ROUTE,
         BotMovement.Mode.BOAT_LOCAL,
         BotMovement.Mode.BOAT_APPROACH
      }, BotMovement.Mode.values());
      Assertions.assertEquals(2, BotMovement.MAX_REPLANS);
   }

   @Test
   void movingThreatsAreReadAgainForEveryPlan() {
      AtomicInteger reads = new AtomicInteger();
      BotMovement.Avoidance avoidance = BotMovement.Avoidance.dynamic(
         () -> Collections.singletonList(Coord2d.of(reads.incrementAndGet(), 0.0)), 20.0
      );

      Assertions.assertEquals(1.0, avoidance.currentCenters().get(0).x, 0.0);
      Assertions.assertEquals(2.0, avoidance.currentCenters().get(0).x, 0.0);
      Assertions.assertEquals(2, reads.get());
   }

   @Test
   void dangerRadiusIsAppliedWithoutMutatingTheSceneSnapshot() {
      byte[] cells = new byte[25];
      OccupancyGrid original = new OccupancyGrid(
         Coord2d.of(0.0, 0.0), 5, 5, 10.0, cells,
         Coord.of(0, 0), Coord.of(4, 4), Coord.of(4, 4), Collections.<Coord>emptyList()
      );

      OccupancyGrid safe = BotMovement.withAvoidance(
         original, List.of(Coord2d.of(25.0, 25.0)), 11.0
      );

      Assertions.assertEquals(OccupancyGrid.FREE, original.at(2, 2));
      Assertions.assertEquals(OccupancyGrid.SOLID, safe.at(2, 2));
      Assertions.assertEquals(OccupancyGrid.SOLID, safe.at(1, 2));
      Assertions.assertEquals(OccupancyGrid.FREE, safe.at(0, 0));
   }
}
