package haven.pathfinding;

import haven.Coord;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CaveRoutePlannerTest {
    @Test
    void startsAtThePlayerAndChoosesTheFartherEnd() {
        Tiles map = new Tiles();
        for(int x = -4; x <= 20; x++) map.open(x, 0);
        CaveRoutePlanner.Plan plan = CaveRoutePlanner.plan(map, Coord.of(0, 0));

        assertTrue(plan.usable());
        assertEquals(Coord.of(20, 0), plan.destination);
        assertEquals(Coord.of(0, 0), plan.route.get(0));
        assertEquals(Coord.of(20, 0), plan.route.get(plan.route.size() - 1));
    }

    @Test
    void neverLeavesThePlayersConnectedCaveFloor() {
        Tiles map = new Tiles();
        for(int x = 0; x <= 8; x++) map.open(x, 0);
        for(int x = 20; x <= 40; x++) map.open(x, 0);

        CaveRoutePlanner.Plan plan = CaveRoutePlanner.plan(map, Coord.of(0, 0));

        assertEquals(Coord.of(8, 0), plan.destination);
        assertEquals(9, plan.knownFloor);
        assertTrue(plan.route.stream().allMatch(tile -> tile.x <= 8));
    }

    @Test
    void broadVisiblePassageWinsInsideTheNearLongestBand() {
        Tiles map = new Tiles();
        for(int x = 0; x <= 100; x++) map.open(x, 0);
        for(int y = 0; y <= 90; y++)
            for(int x = -4; x <= 4; x++) map.open(x, y);

        CaveRoutePlanner.Plan plan = CaveRoutePlanner.plan(map, Coord.of(0, 0));

        assertTrue(plan.destination.y >= 86,
            "the slightly shorter wide cave should beat the narrow 100-tile tunnel");
        assertTrue(plan.visibilityScore > plan.route.size());
    }

    @Test
    void broadHallRouteUsesTheMiddleInsteadOfHuggingAWall() {
        Tiles map = new Tiles();
        for(int y = 0; y <= 8; y++)
            for(int x = 0; x <= 30; x++) map.open(x, y);

        CaveRoutePlanner.Plan plan = CaveRoutePlanner.plan(map, Coord.of(0, 4));

        assertTrue(plan.usable());
        assertTrue(plan.route.stream().filter(tile -> tile.x >= 8 && tile.x <= 22)
            .allMatch(tile -> tile.y >= 3 && tile.y <= 5));
    }

    @Test
    void diagonalFloorCannotSqueezeThroughRockCorners() {
        Tiles map = new Tiles();
        map.open(0, 0);
        map.open(1, 1);

        CaveRoutePlanner.Plan plan = CaveRoutePlanner.plan(map, Coord.of(0, 0));

        assertEquals(1, plan.knownFloor);
        assertEquals(Collections.singletonList(Coord.of(0, 0)), plan.route);
    }

    @Test
    void savedMapTerrainClassificationUsesTheStrictCaveFloorAllowlist() {
        assertEquals(CaveRoutePlanner.Cell.UNKNOWN, MapFileCaveSource.classify(null));
        assertEquals(CaveRoutePlanner.Cell.UNKNOWN, MapFileCaveSource.classify("gfx/tiles/notile"));

        for(String floor : Arrays.asList(
            "mine", "deepcave", "gleamgrotto", "lushcave",
            "shadehollow", "warmdepth", "wildcavern", "gloomdark")) {
            assertEquals(CaveRoutePlanner.Cell.OPEN,
                MapFileCaveSource.classify("gfx/tiles/" + floor), floor);
        }
        assertEquals(CaveRoutePlanner.Cell.OPEN,
            MapFileCaveSource.classify("GFX/TILES/WILDCAVERN"));

        assertEquals(CaveRoutePlanner.Cell.BLOCKED, MapFileCaveSource.classify("gfx/tiles/cave"));
        assertEquals(CaveRoutePlanner.Cell.BLOCKED, MapFileCaveSource.classify("gfx/tiles/rough"));
        assertEquals(CaveRoutePlanner.Cell.BLOCKED, MapFileCaveSource.classify("gfx/tiles/cavefloor"));
        assertEquals(CaveRoutePlanner.Cell.BLOCKED, MapFileCaveSource.classify("gfx/tiles/grass"));
        assertEquals(CaveRoutePlanner.Cell.BLOCKED, MapFileCaveSource.classify("gfx/tiles/paving/brick"));
        assertEquals(CaveRoutePlanner.Cell.BLOCKED, MapFileCaveSource.classify("gfx/tiles/rocks/granite"));
        assertEquals(CaveRoutePlanner.Cell.BLOCKED, MapFileCaveSource.classify("gfx/tiles/cavewall"));
        assertEquals(CaveRoutePlanner.Cell.BLOCKED, MapFileCaveSource.classify("gfx/tiles/water"));
        assertEquals(CaveRoutePlanner.Cell.BLOCKED, MapFileCaveSource.classify("gfx/tiles/minewall"));
    }

    @Test
    void planningLimitReturnsAUsablePartialRoute() {
        Tiles map = new Tiles();
        for(int x = 0; x < 100; x++) map.open(x, 0);

        CaveRoutePlanner.Plan plan = CaveRoutePlanner.plan(map, Coord.of(0, 0), 10);

        assertEquals(CaveRoutePlanner.Status.LIMIT_REACHED, plan.status);
        assertTrue(plan.usable());
        assertEquals(10, plan.knownFloor);
    }

    @Test
    void boundedPlanStopsAtRequestedDepth() {
        Tiles map = new Tiles();
        for(int x = 0; x <= 200; x++) map.open(x, 0);

        CaveRoutePlanner.Plan plan = CaveRoutePlanner.plan(map, Coord.of(0, 0),
            50_000, 24, Collections.emptySet());

        assertTrue(plan.usable());
        assertEquals(Coord.of(24, 0), plan.destination);
        assertEquals(25, plan.route.size());
        assertTrue(plan.knownFloor < 200);
    }

    @Test
    void nextChunkPrefersFloorThatHasNotAlreadyBeenSeen() {
        Tiles map = new Tiles();
        Set<Coord> seen = new HashSet<>();
        for(int x = -40; x <= 40; x++) {
            map.open(x, 0);
            if(x >= 0) seen.add(Coord.of(x, 0));
        }

        CaveRoutePlanner.Plan plan = CaveRoutePlanner.plan(map, Coord.of(0, 0),
            50_000, 32, seen);

        assertTrue(plan.usable());
        assertTrue(plan.destination.x < 0,
            "the chained plan should continue into unseen cave instead of turning back");
        assertTrue(plan.unseenVisibilityScore > 0);
    }

    @Test
    void fullySeenChunkReportsNoNewVisibility() {
        Tiles map = new Tiles();
        Set<Coord> seen = new HashSet<>();
        for(int x = -10; x <= 10; x++) {
            Coord tile = Coord.of(x, 0);
            map.open(x, 0);
            seen.add(tile);
        }

        CaveRoutePlanner.Plan plan = CaveRoutePlanner.plan(map, Coord.of(0, 0),
            50_000, 10, seen);

        assertTrue(plan.usable());
        assertEquals(0, plan.unseenVisibilityScore);
    }

    private static final class Tiles implements CaveRoutePlanner.Source {
        private final Set<Coord> open = new HashSet<>();

        void open(int x, int y) {open.add(Coord.of(x, y));}

        @Override
        public CaveRoutePlanner.Cell cell(Coord tile) {
            return open.contains(tile) ? CaveRoutePlanner.Cell.OPEN : CaveRoutePlanner.Cell.BLOCKED;
        }
    }
}
