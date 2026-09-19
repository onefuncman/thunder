package thunder.clearcut;

import haven.Coord2d;
import haven.pathfinding.ExactPlacementPlanner;
import haven.pathfinding.PlacementEgress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DropEgressTest {
    @Test public void leavesHorizontalLogAcrossItsNearestNarrowSide() {
        ExactPlacementPlanner.Shape log = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(-11.0, -2.75, 11.0, 2.75));
        Coord2d player = Coord2d.z;

        List<Coord2d> choices = PlacementEgress.candidates(log, player, Coord2d.of(0, -100), 5.0);

        assertFalse(choices.isEmpty());
        assertEquals(0.0, choices.get(0).x, 1e-7);
        assertEquals(-7.75, choices.get(0).y, 1e-7);
        assertFalse(PlacementEgress.clearOf(log, player, 5.0));
        assertTrue(PlacementEgress.clearOf(log, choices.get(0), 5.0));
    }

    @Test public void producesClearCandidatesForRotatedLog() {
        double angle = Math.PI / 3.0, cs = Math.cos(angle), sn = Math.sin(angle);
        Coord2d[] polygon = new Coord2d[4];
        Coord2d[] local = {Coord2d.of(-11, -2.75), Coord2d.of(11, -2.75),
            Coord2d.of(11, 2.75), Coord2d.of(-11, 2.75)};
        for(int i = 0; i < local.length; i++) polygon[i] = Coord2d.of(
            local[i].x * cs - local[i].y * sn,
            local[i].x * sn + local[i].y * cs);
        ExactPlacementPlanner.Shape log = new ExactPlacementPlanner.Shape(java.util.Collections.singletonList(polygon));

        List<Coord2d> choices = PlacementEgress.candidates(log, Coord2d.z, Coord2d.of(100, 0), 5.0);

        assertFalse(choices.isEmpty());
        assertTrue(choices.stream().anyMatch(point -> PlacementEgress.clearOf(log, point, 5.0)));
    }
}
