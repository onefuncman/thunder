package haven.pathfinding;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CurrentVehicleExclusionTest {
    @Test
    void currentBoatIsNotItsOwnObstacle() {
        assertTrue(MovementScene.currentVehicle(42L, 42L));
        assertFalse(MovementScene.currentVehicle(42L, 43L));
        assertFalse(MovementScene.currentVehicle(0L, 0L));
        assertTrue(MovementScene.currentVehicle(42L, 43L, true),
            "the independently maintained local-driver flag excludes the hull while ids update");
        assertFalse(MovementScene.currentVehicle(42L, 43L, false));
    }
}
