package haven.pathfinding;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WaterClearanceProtectionTest {
    @Test
    void waterModesDoNotFreezeTheHullAtItsCurrentHeading() {
        assertFalse(MovementScene.inflateTerrainWithBody(
            MovementScene.TerrainMode.WATER_ONLY));
        assertFalse(MovementScene.inflateTerrainWithBody(
            MovementScene.TerrainMode.WATER_APPROACH));
        assertTrue(MovementScene.inflateTerrainWithBody(
            MovementScene.TerrainMode.NORMAL));
        assertTrue(MovementScene.inflateTerrainWithBody(
            MovementScene.TerrainMode.CAVE));
    }
}
