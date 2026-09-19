package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.OCache;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PlacementExecutorTest {
    @Test public void liftedPlacementStartsWithGobTargetedRightClick() {
        long logId = 1401006367L;
        Coord2d logPosition = Coord2d.of(-10433.5, -11128.7021484375);

        Object[] args = PlacementExecutor.liftedObjectClickArgs(logId, logPosition);
        Coord mc = logPosition.floor(OCache.posres);

        assertNotNull(args);
        assertArrayEquals(new Object[] {
            Coord.z, mc, 3, 0, 0, (int)logId, mc, 0, -1
        }, args);
    }

    @Test public void missingCarriedObjectCannotProduceAPlacementClick() {
        assertNull(PlacementExecutor.liftedObjectClickArgs(17L, null));
    }
}
