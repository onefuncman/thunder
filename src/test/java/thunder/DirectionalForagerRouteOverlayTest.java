package thunder;

import haven.Coord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DirectionalForagerRouteOverlayTest {
    @AfterEach
    void clearOverlay() {
        DirectionalForagerRouteOverlay.clear();
    }

    @Test
    void routeSnapshotIsIndependentAndProgressIsClamped() {
        List<Coord> source = new ArrayList<>(Arrays.asList(
            Coord.of(4, 7), Coord.of(5, 7), Coord.of(6, 7)));
        DirectionalForagerRouteOverlay.show(81L, source, 1);
        source.clear();

        DirectionalForagerRouteOverlay.Snapshot first =
            DirectionalForagerRouteOverlay.snapshot();
        assertEquals(81L, first.segment);
        assertEquals(3, first.route.size());
        assertEquals(1, first.activeIndex);

        DirectionalForagerRouteOverlay.showProgress(99);
        DirectionalForagerRouteOverlay.Snapshot progressed =
            DirectionalForagerRouteOverlay.snapshot();
        assertSame(first.route, progressed.route);
        assertEquals(3, progressed.activeIndex);
    }

    @Test
    void newPlanReplacesThePreviousSegmentAndClearRemovesIt() {
        DirectionalForagerRouteOverlay.show(4L,
            Arrays.asList(Coord.of(1, 1), Coord.of(2, 1)), 1);
        DirectionalForagerRouteOverlay.show(9L,
            Arrays.asList(Coord.of(8, 8), Coord.of(9, 8)), 0);

        DirectionalForagerRouteOverlay.Snapshot replacement =
            DirectionalForagerRouteOverlay.snapshot();
        assertEquals(9L, replacement.segment);
        assertEquals(Coord.of(8, 8), replacement.route.get(0));

        DirectionalForagerRouteOverlay.clear();
        assertTrue(DirectionalForagerRouteOverlay.snapshot().route.isEmpty());
    }
}
