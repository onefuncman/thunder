package haven.pathfinding;

import haven.Coord;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class WaterwayRoutePlannerTest {
    @Test
    void startsHereAndChoosesTheFartherEnd() {
        Tiles map = new Tiles();
        for(int x = 0; x <= 20; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.DEEP);
        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(3, 0));
        assertTrue(plan.usable());
        assertEquals(Coord.of(20, 0), plan.destination);
        assertEquals(Coord.of(3, 0), plan.route.get(0));
    }

    @Test
    void neverLeavesTheStartingBodyOfWater() {
        Tiles map = new Tiles();
        for(int x = 0; x <= 5; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.DEEP);
        for(int x = 20; x <= 40; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.SHALLOW);
        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(0, 0));
        assertEquals(Coord.of(5, 0), plan.destination);
        assertTrue(plan.route.stream().allMatch(c -> c.x <= 5));
        assertEquals(6, plan.knownWater);
    }

    @Test
    void shallowRichRouteWinsWithinTenPercentOfLongest() {
        Tiles map = new Tiles();
        map.put(0, 0, WaterwayRoutePlanner.Cell.DEEP);
        for(int x = 1; x <= 100; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.DEEP);
        for(int y = 1; y <= 90; y++) map.put(0, y, WaterwayRoutePlanner.Cell.SHALLOW);
        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(0, 0));
        assertEquals(Coord.of(0, 90), plan.destination);
        assertTrue(plan.shallowExposure > 1);
    }

    @Test
    void openMapEdgeBecomesTheDestination() {
        Tiles map = new Tiles();
        for(int x = 0; x <= 6; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.DEEP);
        map.put(7, 0, WaterwayRoutePlanner.Cell.UNKNOWN);
        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(0, 0));
        assertTrue(plan.frontier);
        assertEquals(Coord.of(6, 0), plan.destination);
        assertEquals(1, plan.frontierCount);
    }

    @Test
    void closedFrontierIsNotChosenAgain() {
        Tiles map = new Tiles();
        for(int x = -5; x <= 5; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.DEEP);
        map.put(-6, 0, WaterwayRoutePlanner.Cell.UNKNOWN);
        map.put(6, 0, WaterwayRoutePlanner.Cell.UNKNOWN);
        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(0, 0), 100,
            Collections.singleton(Coord.of(-5, 0)));
        assertEquals(Coord.of(5, 0), plan.destination);
        assertTrue(plan.frontier);
    }

    @Test
    void closedKnownEndpointRemainsWaterButIsNotChosenAgain() {
        Tiles map = new Tiles();
        for(int x = -8; x <= 8; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.DEEP);
        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(0, 0), 100,
            Arrays.asList(Coord.of(-8, 0), Coord.of(-7, 0), Coord.of(-6, 0)));
        assertEquals(Coord.of(8, 0), plan.destination);
        assertEquals(17, plan.knownWater, "closing a destination must not split the waterway");
    }

    @Test
    void landSplitsTheConnectedWater() {
        Tiles map = new Tiles();
        for(int x = 0; x <= 10; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.DEEP);
        map.put(5, 0, WaterwayRoutePlanner.Cell.LAND);
        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(0, 0));
        assertEquals(Coord.of(4, 0), plan.destination);
        assertEquals(5, plan.knownWater);
    }

    @Test
    void diagonalCannotSqueezeBetweenTwoBanks() {
        Tiles map = new Tiles();
        map.put(0, 0, WaterwayRoutePlanner.Cell.DEEP);
        map.put(1, 1, WaterwayRoutePlanner.Cell.DEEP);
        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(0, 0));
        assertEquals(1, plan.knownWater);
        assertEquals(Collections.singletonList(Coord.of(0, 0)), plan.route);
    }

    @Test
    void planningLimitReturnsTheBestSafePartialRoute() {
        Tiles map = new Tiles();
        for(int x = 0; x < 100; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.DEEP);
        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(0, 0), 10,
            Collections.emptySet());
        assertEquals(WaterwayRoutePlanner.Status.LIMIT_REACHED, plan.status);
        assertTrue(plan.usable());
        assertEquals(10, plan.knownWater);
    }

    @Test
    void stagingRouteUsesOnlyConnectedKnownWater() {
        Tiles map = new Tiles();
        for(int x = 0; x <= 12; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.DEEP);
        WaterwayRoutePlanner.Route route = WaterwayRoutePlanner.routeToAny(map, Coord.of(0, 0),
            Arrays.asList(Coord.of(12, 0), Coord.of(20, 0)), 100);
        assertTrue(route.reached());
        assertEquals(Coord.of(12, 0), route.destination);
        assertEquals(13, route.tiles.size());
    }

    @Test
    void equalChoicesAreDeterministic() {
        Tiles map = new Tiles();
        for(int x = -4; x <= 4; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.DEEP);
        Coord first = WaterwayRoutePlanner.plan(map, Coord.of(0, 0)).destination;
        for(int i = 0; i < 5; i++)
            assertEquals(first, WaterwayRoutePlanner.plan(map, Coord.of(0, 0)).destination);
    }

    @Test
    void broadWaterRouteUsesTheMiddleInsteadOfHuggingABank() {
        Tiles map = new Tiles();
        for(int y = 0; y <= 8; y++)
            for(int x = 0; x <= 30; x++) map.put(x, y, WaterwayRoutePlanner.Cell.DEEP);
        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(0, 4));
        assertTrue(plan.usable());
        assertTrue(plan.route.stream().filter(c -> c.x >= 8 && c.x <= 22)
            .allMatch(c -> c.y >= 3 && c.y <= 5),
            "the whole long middle section should keep useful bank clearance");
    }

    @Test
    void shallowWaterIsForVisibilityNotTheCruiseLane() {
        Tiles map = new Tiles();
        for(int y = 0; y <= 8; y++)
            for(int x = 0; x < 50; x++) map.put(x, y, WaterwayRoutePlanner.Cell.DEEP);
        for(int x = 1; x < 49; x++) map.put(x, 4, WaterwayRoutePlanner.Cell.SHALLOW);
        map.put(50, 4, WaterwayRoutePlanner.Cell.DEEP);

        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(0, 4));
        assertEquals(Coord.of(50, 4), plan.destination);
        assertTrue(plan.route.stream().filter(c -> c.x >= 5 && c.x <= 45)
            .allMatch(c -> map.cell(c) == WaterwayRoutePlanner.Cell.DEEP),
            "a deep center lane should beat a shallow shortcut");
    }

    @Test
    void narrowRiverRemainsUsableDespiteCenterPreference() {
        Tiles map = new Tiles();
        for(int x = 0; x <= 30; x++) map.put(x, 0, WaterwayRoutePlanner.Cell.DEEP);
        WaterwayRoutePlanner.Plan plan = WaterwayRoutePlanner.plan(map, Coord.of(0, 0));
        assertEquals(Coord.of(30, 0), plan.destination);
        assertEquals(31, plan.route.size());
    }

    private static final class Tiles implements WaterwayRoutePlanner.Source {
        private final Map<Coord, WaterwayRoutePlanner.Cell> cells = new HashMap<>();

        void put(int x, int y, WaterwayRoutePlanner.Cell cell) {
            cells.put(Coord.of(x, y), cell);
        }

        @Override
        public WaterwayRoutePlanner.Cell cell(Coord tile) {
            return cells.getOrDefault(tile, WaterwayRoutePlanner.Cell.LAND);
        }
    }
}
