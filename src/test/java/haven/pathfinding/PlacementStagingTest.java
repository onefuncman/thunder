package haven.pathfinding;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PlacementStagingTest {
    @Test public void liftedObjectStagesInsideRecordedPlacementReach() {
        Coord2d target = Coord2d.of(100, 200);
        Coord2d tile = Coord2d.of(11, 11);
        Coord2d player = Coord2d.of(100, 180);

        List<Coord2d> candidates = PlacementStaging.liftedCandidates(target, tile, player);

        assertEquals(4, candidates.size());
        assertEquals(Coord2d.of(100, 191.75), candidates.get(0));
        for (Coord2d candidate : candidates)
            assertEquals(8.25, candidate.dist(target), 1e-9);
    }

    @Test public void offersOneTileOutsideEveryEdgeAndRanksNearestFirst() {
        Area area = new Area(Coord.of(10, 20), Coord.of(14, 24));
        Coord2d tile = Coord2d.of(11, 11);
        Coord2d intended = Coord2d.of(126.5, 247.5);
        Coord2d player = Coord2d.of(80, 247.5);

        List<Coord2d> candidates = PlacementStaging.candidates(area, tile, intended, player);

        assertEquals(4, candidates.size());
        assertEquals(Coord2d.of(99, 247.5), candidates.get(0));
        assertTrue(candidates.stream().anyMatch(p -> p.equals(Coord2d.of(165, 247.5))));
        assertTrue(candidates.stream().anyMatch(p -> p.equals(Coord2d.of(126.5, 209))));
        assertTrue(candidates.stream().anyMatch(p -> p.equals(Coord2d.of(126.5, 275))));
    }
}
