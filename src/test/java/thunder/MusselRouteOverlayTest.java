package thunder;

import haven.Coord;
import haven.Coord2d;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MusselRouteOverlayTest {
    @AfterEach
    void clearOverlay() {
        MusselRouteOverlay.clear();
    }

    @Test
    void mainRouteUsesAnIndependentSnapshotAndClampsProgress() {
        List<Coord> source = new ArrayList<>(Arrays.asList(
            Coord.of(1, 2), Coord.of(2, 2), Coord.of(3, 2)));
        MusselRouteOverlay.showMain(77L, source, 1);
        source.clear();

        MusselRouteOverlay.Snapshot first = MusselRouteOverlay.snapshot();
        assertEquals(77L, first.segment);
        assertEquals(3, first.mainRoute.size());
        assertEquals(1, first.activeIndex);

        MusselRouteOverlay.showProgress(99);
        MusselRouteOverlay.Snapshot progressed = MusselRouteOverlay.snapshot();
        assertSame(first.mainRoute, progressed.mainRoute);
        assertEquals(3, progressed.activeIndex);
    }

    @Test
    void detoursCanBeClearedWithoutLosingTheLongRoute() {
        MusselRouteOverlay.showMain(12L, Arrays.asList(Coord.of(0, 0), Coord.of(8, 0)), 1);
        MusselRouteOverlay.showSavedDetour(12L, Arrays.asList(Coord.of(2, 0), Coord.of(2, 4)));
        MusselRouteOverlay.showLocalRoute(12L, Arrays.asList(Coord2d.of(1, 1), Coord2d.of(2, 2)));

        MusselRouteOverlay.clearDetours();
        MusselRouteOverlay.Snapshot snap = MusselRouteOverlay.snapshot();
        assertEquals(2, snap.mainRoute.size());
        assertTrue(snap.savedDetour.isEmpty());
        assertTrue(snap.localRoute.isEmpty());
        assertFalse(snap.isEmpty());
    }

    @Test
    void changingSavedMapSegmentsDropsStaleRoutes() {
        MusselRouteOverlay.showMain(3L, Arrays.asList(Coord.of(0, 0), Coord.of(1, 0)), 1);
        MusselRouteOverlay.showSavedDetour(4L, Arrays.asList(Coord.of(9, 9), Coord.of(10, 9)));

        MusselRouteOverlay.Snapshot snap = MusselRouteOverlay.snapshot();
        assertEquals(4L, snap.segment);
        assertTrue(snap.mainRoute.isEmpty());
        assertEquals(2, snap.savedDetour.size());
    }

    @Test
    void movementDebugKeepsRequestedEffectiveAndActualPathsSeparate() {
        List<Coord2d> local = Arrays.asList(Coord2d.of(0, 0), Coord2d.of(20, 0));
        MusselRouteOverlay.beginMovement(9L, local, Coord2d.of(20, 0),
            Coord2d.of(17, 2), Coord2d.of(0, 0));
        MusselRouteOverlay.recordMovement(9L, Coord2d.of(5, 1));
        MusselRouteOverlay.finishMovement("SHORT_STOP");

        MusselRouteOverlay.Snapshot snap = MusselRouteOverlay.snapshot();
        assertEquals(Coord2d.of(20, 0), snap.requestedGoal);
        assertEquals(Coord2d.of(17, 2), snap.effectiveGoal);
        assertEquals(2, snap.actualTrail.size());
        assertEquals("SHORT_STOP", snap.movementResult);
    }
}
