package thunder;

import haven.Coord2d;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MusselExplorerLogicTest {
    @Test
    void compassHeadingsAndProbesMatchWorldAxes() {
        Coord2d p = Coord2d.of(100, 100);
        assertPoint(100, 90, MusselExplorerLogic.probe(p,
            MusselExplorerLogic.heading(MusselExplorerLogic.Direction.NORTH), 10, 0));
        assertPoint(110, 100, MusselExplorerLogic.probe(p,
            MusselExplorerLogic.heading(MusselExplorerLogic.Direction.EAST), 10, 0));
    }

    @Test
    void unvisitedWaterWinsBeforeAlreadySearchedWater() {
        MusselExplorerLogic.Candidate fresh = candidate(1, false, false, 0);
        MusselExplorerLogic.Candidate visited = candidate(0, true, true, 1);
        assertSame(fresh, MusselExplorerLogic.ranked(Arrays.asList(visited, fresh)).get(0));
    }

    @Test
    void shallowAndFrontierWaterArePreferredAtEqualVisits() {
        MusselExplorerLogic.Candidate plain = candidate(0, false, false, 0);
        MusselExplorerLogic.Candidate shallow = candidate(Math.PI, true, false, 0);
        MusselExplorerLogic.Candidate frontier = candidate(Math.PI, true, true, 0);
        List<MusselExplorerLogic.Candidate> ranked = MusselExplorerLogic.ranked(Arrays.asList(plain, shallow, frontier));
        assertSame(frontier, ranked.get(0));
        assertSame(shallow, ranked.get(1));
    }

    @Test
    void straightTravelBreaksOtherwiseEqualTie() {
        MusselExplorerLogic.Candidate straight = candidate(0, false, false, 0);
        MusselExplorerLogic.Candidate turn = candidate(Math.PI / 2, false, false, 0);
        assertTrue(MusselExplorerLogic.score(straight) > MusselExplorerLogic.score(turn));
    }

    @Test
    void longForwardTravelBeatsAShortNearbyStep() {
        MusselExplorerLogic.Candidate shortStep = new MusselExplorerLogic.Candidate(
            Coord2d.of(10, 0), 0, 0, true, false, 10);
        MusselExplorerLogic.Candidate longStep = new MusselExplorerLogic.Candidate(
            Coord2d.of(100, 0), Math.PI / 8, 0, true, false, 100);
        assertSame(longStep, MusselExplorerLogic.ranked(Arrays.asList(shortStep, longStep)).get(0));
    }

    @Test
    void originalDirectionAllowsSmallDetourButRejectsLargeReturn() {
        double north = MusselExplorerLogic.heading(MusselExplorerLogic.Direction.NORTH);
        Coord2d origin = Coord2d.of(100, 100);
        assertEquals(80.0, MusselExplorerLogic.directionalProgress(
            origin, Coord2d.of(130, 20), north), 0.0001);
        assertTrue(MusselExplorerLogic.withinBacktrack(60, 80, 44));
        assertFalse(MusselExplorerLogic.withinBacktrack(20, 80, 44));
    }

    @Test
    void boatPathDropsTinyCorrectionPoints() {
        List<Coord2d> path = Arrays.asList(
            Coord2d.of(0, 0), Coord2d.of(20, 0), Coord2d.of(40, 20),
            Coord2d.of(42.75, 20), Coord2d.of(42.75, 22.75));
        List<Coord2d> smooth = MusselExplorerLogic.smoothBoatPath(path, 6.0);
        assertEquals(Arrays.asList(Coord2d.of(0, 0), Coord2d.of(20, 0),
            Coord2d.of(40, 20), Coord2d.of(42.75, 22.75)), smooth);
    }

    @Test
    void boatPathDropsTinyCorrectionsAtBothEnds() {
        List<Coord2d> path = Arrays.asList(
            Coord2d.of(0, 0), Coord2d.of(2.75, 0), Coord2d.of(50, 45), Coord2d.of(50, 50));
        assertEquals(Arrays.asList(Coord2d.of(0, 0), Coord2d.of(50, 50)),
            MusselExplorerLogic.smoothBoatPath(path, 6.0));
    }

    @Test
    void collectionRouteCanBeRetracedFromCurrentPosition() {
        List<Coord2d> route = Arrays.asList(Coord2d.of(0, 0), Coord2d.of(20, 5), Coord2d.of(40, 10));
        List<Coord2d> reversed = MusselExplorerLogic.reversedRoute(route, Coord2d.of(41, 10));
        assertEquals(Arrays.asList(Coord2d.of(41, 10), Coord2d.of(20, 5), Coord2d.of(0, 0)), reversed);
    }

    @Test
    void musselApproachesPutTargetBesideBoat() {
        Coord2d target = Coord2d.of(0, 0);
        List<MusselExplorerLogic.Approach> approaches =
            MusselExplorerLogic.sideApproaches(target, 10.5, 33.0, 16);
        assertEquals(32, approaches.size());
        for(MusselExplorerLogic.Approach approach : approaches) {
            double travel = Math.atan2(approach.stop.y - approach.staging.y,
                approach.stop.x - approach.staging.x);
            double departure = Math.atan2(approach.departure.y - approach.stop.y,
                approach.departure.x - approach.stop.x);
            assertEquals(0.0, Math.abs(MusselExplorerLogic.normalizeAngle(travel - approach.heading)), 0.0001);
            assertEquals(0.0, Math.abs(MusselExplorerLogic.normalizeAngle(departure - approach.heading)), 0.0001);
            double tx = target.x - approach.stop.x, ty = target.y - approach.stop.y;
            assertEquals(0.0, tx * Math.cos(approach.heading) + ty * Math.sin(approach.heading), 0.0001);
            assertEquals(10.5, approach.stop.dist(target), 0.0001);
        }
    }

    @Test
    void footprintSamplesCoverRotatedHullAndRouteLengthIsMeasured() {
        List<Coord2d> samples = MusselExplorerLogic.footprintSamples(
            Coord2d.of(100, 100), Math.PI / 2.0, 21.0, 8.25, 2.75);
        assertTrue(samples.stream().anyMatch(p -> Math.abs(p.x - 91.75) < 0.0001 && Math.abs(p.y - 79.0) < 0.0001));
        assertTrue(samples.stream().anyMatch(p -> Math.abs(p.x - 108.25) < 0.0001 && Math.abs(p.y - 121.0) < 0.0001));
        assertEquals(10.0, MusselExplorerLogic.routeLength(Arrays.asList(
            Coord2d.of(0, 0), Coord2d.of(3, 4), Coord2d.of(6, 8))), 0.0001);
    }

    @Test
    void cruiseRouteRejectsLargeShorelineLoop() {
        Coord2d from = Coord2d.of(0, 0), to = Coord2d.of(100, 0);
        assertTrue(MusselExplorerLogic.reasonableCruiseRoute(from, to,
            Arrays.asList(from, Coord2d.of(50, 15), to)));
        assertFalse(MusselExplorerLogic.reasonableCruiseRoute(from, to,
            Arrays.asList(from, Coord2d.of(0, 120), Coord2d.of(100, 120), to)));
    }

    @Test
    void incompleteCruiseRouteMustActuallyAdvanceTowardTheGoal() {
        Coord2d from = Coord2d.of(0, 0), to = Coord2d.of(200, 0);
        assertFalse(MusselExplorerLogic.usefulPartialCruiseRoute(from, to,
            Arrays.asList(from, Coord2d.of(0, 11)), 11.0));
        assertTrue(MusselExplorerLogic.usefulPartialCruiseRoute(from, to,
            Arrays.asList(from, Coord2d.of(30, 4)), 11.0));
    }

    @Test
    void oneTilePartialStepBesideABankIsUseful() {
        Coord2d from = Coord2d.of(-11130.6, -9090.1);
        Coord2d goal = Coord2d.of(-11313.5, -8937.5);
        Coord2d safeStep = Coord2d.of(-11141.6, -9090.1);
        assertTrue(MusselExplorerLogic.usefulPartialCruiseRoute(
            from, goal, Arrays.asList(from, safeStep), 11.0));
    }

    @Test
    void inefficientPartialShorelineLoopIsStillRejected() {
        Coord2d from = Coord2d.of(0, 0), goal = Coord2d.of(235, 0);
        assertFalse(MusselExplorerLogic.usefulPartialCruiseRoute(from, goal,
            Arrays.asList(from, Coord2d.of(0, 40), Coord2d.of(13, 0)), 11.0));
    }

    private static MusselExplorerLogic.Candidate candidate(double angle, boolean shallow, boolean frontier, int visits) {
        return new MusselExplorerLogic.Candidate(Coord2d.of(10 + angle, 20), angle, visits, shallow, frontier);
    }

    private static void assertPoint(double x, double y, Coord2d actual) {
        assertEquals(x, actual.x, 0.0001);
        assertEquals(y, actual.y, 0.0001);
    }
}
