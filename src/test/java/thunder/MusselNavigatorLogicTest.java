package thunder;

import haven.Coord;
import haven.Coord2d;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MusselNavigatorLogicTest {
    @Test
    void longRouteCanBeSplitIntoSmoothTwentyFourTileLegs() {
        List<Coord> route = new ArrayList<>();
        for(int x = 0; x <= 70; x++) route.add(Coord.of(x, 0));
        assertEquals(24, MusselNavigatorLogic.lookahead(route, 1, Coord.of(0, 0), 24.0));
        assertEquals(34, MusselNavigatorLogic.lookahead(route, 11, Coord.of(10, 0), 24.0));
    }

    @Test
    void diagonalLegAlsoHonorsRealDistance() {
        List<Coord> route = new ArrayList<>();
        for(int i = 0; i <= 10; i++) route.add(Coord.of(i, i));
        int index = MusselNavigatorLogic.lookahead(route, 1, Coord.of(0, 0), 8.0);
        assertEquals(5, index);
        assertTrue(Coord.of(0, 0).dist(route.get(index)) <= 8.0);
    }

    @Test
    void cyanGuideKeepsRealBendsButDropsPerTileClicks() {
        List<Coord> route = Arrays.asList(
            Coord.of(0, 0), Coord.of(1, 0), Coord.of(2, 0), Coord.of(3, 0),
            Coord.of(3, 1), Coord.of(3, 2), Coord.of(4, 3), Coord.of(5, 4));
        assertEquals(Arrays.asList(Coord.of(3, 0), Coord.of(3, 2), Coord.of(5, 4)),
            MusselNavigatorLogic.guideCorners(route, 1, 7));
    }

    @Test
    void straightCyanGuideIsOneContinuousCommand() {
        List<Coord> route = new ArrayList<>();
        for(int x = 0; x <= 24; x++) route.add(Coord.of(x, 0));
        assertEquals(Collections.singletonList(Coord.of(24, 0)),
            MusselNavigatorLogic.guideCorners(route, 1, 24));
    }

    @Test
    void detourRejoinsTheNextNearbyFuturePoint() {
        List<Coord> route = new ArrayList<>();
        for(int x = 0; x <= 50; x++) route.add(Coord.of(x, 0));
        assertEquals(17, MusselNavigatorLogic.rejoinIndex(
            route, Coord.of(17, 2), 10, 4, 20, 5.0));
    }

    @Test
    void rejoinDiagnosticsKeepTheRejectedNearestPoint() {
        List<Coord> route = new ArrayList<>();
        for(int x = 0; x <= 30; x++) route.add(Coord.of(x, 0));
        MusselNavigatorLogic.RejoinDecision decision = MusselNavigatorLogic.rejoinDecision(
            route, Coord.of(17, 4), 10, 4, 20, 2.0);
        assertFalse(decision.accepted);
        assertEquals(10, decision.selectedIndex);
        assertEquals(17, decision.nearestIndex);
        assertEquals(4.0, decision.nearestDistance, 0.001);
    }

    @Test
    void normalBoatOffsetStillAdvancesTheRouteCursor() {
        List<Coord> route = new ArrayList<>();
        for(int x = 0; x <= 40; x++) route.add(Coord.of(x, 0));
        MusselNavigatorLogic.RejoinDecision decision = MusselNavigatorLogic.rejoinDecision(
            route, Coord.of(31, 3), 17, 8, 128, 4.0);
        assertTrue(decision.accepted);
        assertEquals(31, decision.selectedIndex);
    }

    @Test
    void failedLocalDetourCannotSendTheNextLegBackward() {
        List<Coord> route = new ArrayList<>();
        for(int x = 0; x <= 40; x++) route.add(Coord.of(x, 0));
        assertEquals(17, MusselNavigatorLogic.boundedProgressIndex(
            route, Coord.of(17, 8), 10, 24));
    }

    @Test
    void shortRecoveryLegsHaveAFloor() {
        assertEquals(12, MusselNavigatorLogic.shorterLeg(24, 4));
        assertEquals(6, MusselNavigatorLogic.shorterLeg(12, 4));
        assertEquals(4, MusselNavigatorLogic.shorterLeg(6, 4));
        assertEquals(4, MusselNavigatorLogic.shorterLeg(4, 4));
    }

    @Test
    void oneIdleFlickerDoesNotEndBoatMovement() {
        MusselNavigatorLogic.MotionDebouncer filter =
            new MusselNavigatorLogic.MotionDebouncer(300L, 0.75);
        assertFalse(filter.moving(0L, Coord2d.of(0, 0), false));
        assertTrue(filter.moving(50L, Coord2d.of(1, 0), true));
        assertTrue(filter.moving(100L, Coord2d.of(2, 0), false));
        assertTrue(filter.moving(150L, Coord2d.of(3, 0), true));
    }

    @Test
    void stableIdleEventuallyEndsBoatMovement() {
        MusselNavigatorLogic.MotionDebouncer filter =
            new MusselNavigatorLogic.MotionDebouncer(300L, 0.75);
        assertTrue(filter.moving(0L, Coord2d.of(0, 0), true));
        assertTrue(filter.moving(100L, Coord2d.of(5, 0), false));
        assertTrue(filter.moving(350L, Coord2d.of(5.2, 0), false));
        assertFalse(filter.moving(400L, Coord2d.of(5.2, 0), false));
    }

    @Test
    void nearbyLaterBendCannotSkipMostOfTheRoute() {
        List<Coord> route = Arrays.asList(Coord.of(0, 0), Coord.of(10, 0), Coord.of(20, 0),
            Coord.of(20, 10), Coord.of(10, 10), Coord.of(0, 10), Coord.of(0, 1));
        assertEquals(1, MusselNavigatorLogic.rejoinIndex(
            route, Coord.of(0, 1), 1, 0, 3, 5.0));
    }

    @Test
    void detectsABAOscillationButNotForwardTravel() {
        assertTrue(MusselNavigatorLogic.oscillating(Arrays.asList(
            Coord.of(1, 1), Coord.of(2, 1), Coord.of(1, 1), Coord.of(2, 1))));
        assertFalse(MusselNavigatorLogic.oscillating(Arrays.asList(
            Coord.of(1, 1), Coord.of(2, 1), Coord.of(3, 1), Coord.of(4, 1))));
    }

    @Test
    void farDetectionNeverMeansFarNativePickup() {
        assertTrue(MusselNavigatorLogic.needsSavedMapStage(200.0, 88.0));
        assertFalse(MusselNavigatorLogic.readyForNativePickup(200.0, 55.0));
        assertTrue(MusselNavigatorLogic.readyForNativePickup(55.0, 55.0));
    }

    @Test
    void sidewaysAndBackwardBoatMotionDoNotCountAsProgress() {
        assertTrue(MusselNavigatorLogic.madeForwardProgress(20.0, 80.0, 65.0, 11.0));
        assertFalse(MusselNavigatorLogic.madeForwardProgress(20.0, 80.0, 82.0, 11.0));
        assertFalse(MusselNavigatorLogic.madeForwardProgress(4.0, 80.0, 70.0, 11.0));
    }

    @Test
    void routePlannedFromAPositionTheBoatLeftIsDiscarded() {
        Coord planned = Coord.of(100, 100);
        assertFalse(MusselNavigatorLogic.stalePlanStart(planned, Coord.of(102, 101), 3.0));
        assertTrue(MusselNavigatorLogic.stalePlanStart(planned, Coord.of(108, 112), 3.0));
    }
}
