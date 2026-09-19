package haven;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldFootprintTest {
    @Test
    void abutmentMatchesManualPlacementOnEveryAxis() {
        WorldFootprint.Bounds placer = new WorldFootprint.Bounds(10, 20, 14, 26);
        WorldFootprint.Bounds target = new WorldFootprint.Bounds(30, 40, 38, 48);

        assertEquals(-WorldFootprint.DEFAULT_ABUT_GAP,
            placer.maxX + WorldFootprint.abutTranslation(placer, target, -1, 0,
                WorldFootprint.DEFAULT_ABUT_GAP, false, false).x - target.minX, 1e-9);
        assertEquals(WorldFootprint.DEFAULT_ABUT_GAP,
            placer.minX + WorldFootprint.abutTranslation(placer, target, 1, 0,
                WorldFootprint.DEFAULT_ABUT_GAP, false, false).x - target.maxX, 1e-9);
        assertEquals(-WorldFootprint.DEFAULT_ABUT_GAP,
            placer.maxY + WorldFootprint.abutTranslation(placer, target, 0, -1,
                WorldFootprint.DEFAULT_ABUT_GAP, false, false).y - target.minY, 1e-9);
        assertEquals(WorldFootprint.DEFAULT_ABUT_GAP,
            placer.minY + WorldFootprint.abutTranslation(placer, target, 0, 1,
                WorldFootprint.DEFAULT_ABUT_GAP, false, false).y - target.maxY, 1e-9);
    }

    @Test
    void legalDropCoordinatesAreContinuousContainedAndObstacleFree() {
        Area area = Area.sized(Coord.z, Coord.of(3, 2));
        WorldFootprint.Bounds relative = new WorldFootprint.Bounds(-2.25, -1.5, 2.25, 1.5);
        List<WorldFootprint.Bounds> obstacles = Collections.singletonList(
            new WorldFootprint.Bounds(0, 0, 5, 5));

        List<Coord2d> legal = WorldFootprint.legalSubTileDrops(
            area, Coord2d.of(11, 11), relative, obstacles, Coord2d.of(0, 0), 0.1);

        assertFalse(legal.isEmpty());
        for(Coord2d anchor : legal) {
            assertTrue(anchor.x - 2.25 >= 0 && anchor.y - 1.5 >= 0);
            assertTrue(anchor.x + 2.25 <= 33 && anchor.y + 1.5 <= 22);
            boolean separated = anchor.x + 2.25 + 0.1 <= 0 || 5 + 0.1 <= anchor.x - 2.25 ||
                anchor.y + 1.5 + 0.1 <= 0 || 5 + 0.1 <= anchor.y - 1.5;
            assertTrue(separated);
        }
        assertNotEquals(Math.rint(legal.get(0).x), legal.get(0).x, 1e-9,
            "the placement API must preserve legal sub-tile coordinates");
    }

    @Test
    void boundsRetainsAllTransformedPolygonExtents() {
        List<Coord2d[]> polygons = Arrays.asList(
            new Coord2d[]{Coord2d.of(-4, 2), Coord2d.of(3, 7)},
            new Coord2d[]{Coord2d.of(8, -5), Coord2d.of(2, 1)});
        WorldFootprint.Bounds bounds = WorldFootprint.bounds(polygons);
        assertNotNull(bounds);
        assertArrayEquals(new double[]{-4, -5, 8, 7}, bounds.array(), 1e-9);
    }
}
