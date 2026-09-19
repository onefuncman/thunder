package haven.pathfinding;

import haven.Coord2d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BoatNavigationTest {
    @Test
    void drivenVehicleMovementOverridesPermanentFollowingAttribute() {
        assertFalse(BoatNavigation.selectMoving(true, true, false));
        assertTrue(BoatNavigation.selectMoving(true, true, true));
        assertTrue(BoatNavigation.selectMoving(false, true, false));
    }

    @Test
    void turnRadiusCoversWholeRowboatRatherThanOnlyItsWidth() {
        double radius = BoatNavigation.safeRadius(Coord2d.of(21.0, 8.25), 11.0);
        assertEquals(Math.hypot(21.0, 8.25) + 1.0, radius, 0.0001);
        assertTrue(radius > 22.0);
    }
}
