package haven.pathfinding;

import haven.Coord2d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ObjectSpatialProfilesTest {
    @AfterEach public void clearLearned() {
        ObjectSpatialProfiles.clearObservedGapsForTests();
    }

    @Test public void logKeepsNavigationAndPlacementGeometrySeparate() {
        ObjectSpatialProfile log = ObjectSpatialProfiles.resolve("gfx/terobjs/trees/pinelog");
        assertEquals(Coord2d.of(11.0, 2.75), log.navigationHalf);
        assertEquals(Coord2d.of(10.0, 2.0), log.placementHalf);
        assertEquals(0.125, log.placementGap, 1e-9);
        assertTrue(log.placementGapConfirmed);
        assertTrue(log.playerMayOverlapPlacement);
    }

    @Test public void stockpileDisallowsPlayerOverlapAndMarksSizeProvisional() {
        ObjectSpatialProfile pile = ObjectSpatialProfiles.resolve("gfx/terobjs/stockpile-board");
        assertFalse(pile.playerMayOverlapPlacement);
        assertNotNull(pile.placementHalf);
        assertEquals("provisional-stockpile-catalog", pile.placementSource);
        assertFalse(pile.placementGapConfirmed);
    }

    @Test public void serverObservedGapOverridesTheProvisionalDefault() {
        ObjectSpatialProfiles.confirmPlacementGap("gfx/terobjs/barrel", 0.25);
        ObjectSpatialProfile barrel = ObjectSpatialProfiles.resolve("gfx/terobjs/barrel");
        assertEquals(0.25, barrel.placementGap, 1e-9);
        assertTrue(barrel.placementGapConfirmed);
    }
}
