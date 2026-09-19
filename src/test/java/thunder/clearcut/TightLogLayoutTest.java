package thunder.clearcut;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.OCache;
import haven.pathfinding.ExactPlacementPlanner;
import haven.pathfinding.ObjectSpatialProfiles;
import haven.pathfinding.PlacementGeometry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TightLogLayoutTest {
    private static final Coord2d TILE = Coord2d.of(11, 11);

    @Test public void placesFromFrontEdgeAndKeepsFullFootprintInside() {
        Area area = new Area(Coord.z, Coord.of(2, 2));
        ExactPlacementPlanner.Rect footprint = new ExactPlacementPlanner.Rect(-5, -2, 5, 2);
        Coord2d at = ExactPlacementPlanner.next(area, TILE, footprint, new ArrayList<>(), Coord2d.z, 0.1);
        assertNotNull(at);
        ExactPlacementPlanner.Rect placed = footprint.move(at.x, at.y);
        assertTrue(placed.inside(new ExactPlacementPlanner.Rect(0, 0, 22, 22)));
        assertEquals(5.0, at.x, 0.0001);
        assertEquals(2.0, at.y, 0.0001);
    }

    @Test public void abutsExistingLogWithRequestedGap() {
        Area area = new Area(Coord.z, Coord.of(4, 3));
        ExactPlacementPlanner.Rect footprint = new ExactPlacementPlanner.Rect(-5, -2, 5, 2);
        List<ExactPlacementPlanner.Rect> obstacles = new ArrayList<>();
        obstacles.add(new ExactPlacementPlanner.Rect(34, 27, 44, 31));
        Coord2d at = ExactPlacementPlanner.next(area, TILE, footprint, obstacles, Coord2d.z, 0.1);
        assertNotNull(at);
        ExactPlacementPlanner.Rect placed = footprint.move(at.x, at.y);
        assertFalse(placed.conflicts(obstacles.get(0), 0.1));
    }

    @Test public void fillsTheSameFrontEdgeInsteadOfJumpingToTheOppositeCorner() {
        Area area = new Area(Coord.z, Coord.of(4, 3));
        ExactPlacementPlanner.Rect footprint = new ExactPlacementPlanner.Rect(-5, -2, 5, 2);
        Coord2d source = Coord2d.of(-100, 16.5);
        List<ExactPlacementPlanner.Rect> obstacles = new ArrayList<>();

        Coord2d first = ExactPlacementPlanner.next(area, TILE, footprint, obstacles, source, 0.1);
        assertNotNull(first);
        obstacles.add(footprint.move(first.x, first.y));
        Coord2d second = ExactPlacementPlanner.next(area, TILE, footprint, obstacles, source, 0.1);

        assertNotNull(second);
        assertEquals(first.x, second.x, 0.0001, "both logs should stay on the selected front edge");
        assertTrue(first.dist(second) < (area.br.y - area.ul.y) * TILE.y,
            "the next slot should continue along the edge, not cross the whole area");
    }

    @Test public void returnsNullWhenFootprintCannotFit() {
        Area area = new Area(Coord.z, Coord.of(1, 1));
        ExactPlacementPlanner.Rect huge = new ExactPlacementPlanner.Rect(-10, -10, 10, 10);
        assertNull(ExactPlacementPlanner.next(area, TILE, huge, new ArrayList<>(), Coord2d.z, 0.1));
    }

    @Test public void mixedSizesReplanAroundServerAdjustedRotation() {
        Area area = new Area(Coord.z, Coord.of(5, 4));
        List<ExactPlacementPlanner.Rect> obstacles = new ArrayList<>();
        // The first log was planned horizontally but observed vertically after drop.
        obstacles.add(new ExactPlacementPlanner.Rect(47, 25, 51, 39));
        ExactPlacementPlanner.Rect medium = new ExactPlacementPlanner.Rect(-5, -2.5, 5, 2.5);
        Coord2d mediumAt = ExactPlacementPlanner.next(area, TILE, medium, obstacles, Coord2d.z, 0.1);
        assertNotNull(mediumAt);
        ExactPlacementPlanner.Rect mediumPlaced = medium.move(mediumAt.x, mediumAt.y);
        assertFalse(mediumPlaced.conflicts(obstacles.get(0), 0.1));
        obstacles.add(mediumPlaced);

        ExactPlacementPlanner.Rect shortLog = new ExactPlacementPlanner.Rect(-3.25, -1.75, 3.25, 1.75);
        Coord2d shortAt = ExactPlacementPlanner.next(area, TILE, shortLog, obstacles, Coord2d.z, 0.1);
        assertNotNull(shortAt);
        ExactPlacementPlanner.Rect shortPlaced = shortLog.move(shortAt.x, shortAt.y);
        for(ExactPlacementPlanner.Rect obstacle : obstacles)
            assertFalse(shortPlaced.conflicts(obstacle, 0.1));
    }

    @Test public void exactPlannerPacksDiagonalLogsThatOverlappingAabbsWouldReject() {
        Area area = new Area(Coord.z, Coord.of(6, 6));
        ExactPlacementPlanner.Shape diagonal = new ExactPlacementPlanner.Shape(Collections.singletonList(
            rotatedRectangle(11.0, 2.75, Math.PI / 4.0)));
        List<ExactPlacementPlanner.Shape> obstacles = new ArrayList<>();

        Coord2d firstAt = ExactPlacementPlanner.nextExact(area, TILE, diagonal, obstacles,
            Coord2d.of(-100, 33), 0.1);
        assertNotNull(firstAt);
        ExactPlacementPlanner.Shape first = diagonal.move(firstAt);
        obstacles.add(first);

        Coord2d secondAt = ExactPlacementPlanner.nextExact(area, TILE, diagonal, obstacles,
            Coord2d.of(-100, 33), 0.1);
        assertNotNull(secondAt);
        ExactPlacementPlanner.Shape second = diagonal.move(secondAt);
        assertFalse(second.conflicts(first, 0.1), "actual collision polygons must retain the safe gap");
        assertTrue(second.bounds.conflicts(first.bounds, 0.1),
            "the case must prove polygon packing succeeds where AABB-only packing fails");
        assertTrue(first.inside(area, TILE, 0.0));
        assertTrue(second.inside(area, TILE, 0.0));
    }

    @Test public void exactTwoTileWideAreaAcceptsAFullLengthHorizontalLog() {
        Area area = new Area(Coord.z, Coord.of(2, 1));
        ExactPlacementPlanner.Shape log = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(-11.0, -2.75, 11.0, 2.75));

        Coord2d at = ExactPlacementPlanner.nextExact(area, TILE, log, new ArrayList<>(),
            Coord2d.of(-100, 5.5), 0.1);

        assertNotNull(at, "the object gap must not be applied to the imaginary selection border");
        assertEquals(11.0, at.x, 1e-9);
        assertTrue(log.move(at).inside(area, TILE, 0.0));
    }

    @Test public void observedPlacementAllowsServerCoordinateRoundTripAtTheBoundary() {
        Area area = new Area(Coord.z, Coord.of(3, 3));
        ExactPlacementPlanner.Shape log = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(-2.0, -10.0, 2.0, 10.0));
        ExactPlacementPlanner.Shape observed = log.move(Coord2d.of(
            2.0 - OCache.posres.x * 0.5, 10.0 - OCache.posres.y * 0.25));

        assertFalse(observed.inside(area, TILE, 0.0),
            "zero-slack geometry should expose the protocol rounding error");
        assertTrue(observed.inside(area, TILE, 0.0,
            PlacementGeometry.SERVER_POSITION_TOLERANCE));
        assertTrue(log.move(Coord2d.of(2.0 - OCache.posres.x * 2.0, 10.0))
            .inside(area, TILE, 0.0, PlacementGeometry.SERVER_POSITION_TOLERANCE),
            "the recorded clear-cut drift is exactly two protocol quanta");
        assertFalse(log.move(Coord2d.of(2.0 - OCache.posres.x * 3.0, 10.0))
            .inside(area, TILE, 0.0, PlacementGeometry.SERVER_POSITION_TOLERANCE),
            "real out-of-area placement must still be rejected");
    }

    @Test public void observedPackingIgnoresOnlySubQuantumContactError() {
        double tolerance = PlacementGeometry.SERVER_POSITION_TOLERANCE;
        ExactPlacementPlanner.Shape first = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(0.0, 0.0, 4.0, 20.0));
        ExactPlacementPlanner.Shape roundingContact = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(4.0 - tolerance * 0.5, 0.0, 8.0, 20.0));
        ExactPlacementPlanner.Shape realOverlap = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(4.0 - tolerance * 2.0, 0.0, 8.0, 20.0));

        assertFalse(first.conflicts(roundingContact, -tolerance));
        assertTrue(first.conflicts(realOverlap, -tolerance));
    }

    @Test public void recordedManualLogSpacingMatchesPhysicalPlacementFootprint() {
        assertTrue(ObjectSpatialProfiles.ordinaryTreeLog("gfx/terobjs/trees/pinelog"));
        assertTrue(ObjectSpatialProfiles.ordinaryTreeLog("gfx/terobjs/trees/oaklog[4]"));
        assertFalse(ObjectSpatialProfiles.ordinaryTreeLog("gfx/terobjs/trees/oldtrunk"));

        ExactPlacementPlanner.Shape first = PlacementGeometry.rectangle(
            Coord2d.of(-10164.0, -9948.125), Coord2d.of(10.0, 2.0), Math.PI);
        ExactPlacementPlanner.Shape second = PlacementGeometry.rectangle(
            Coord2d.of(-10164.0, -9952.25), Coord2d.of(10.0, 2.0), Math.PI);

        assertEquals(20.0, first.bounds.width(), 1e-9);
        assertEquals(4.0, first.bounds.height(), 1e-9);
        assertFalse(first.conflicts(second, 0.1),
            "the server accepted these recorded centers with a 0.125-unit physical gap");
        assertTrue(first.conflicts(second, 0.126),
            "the fixture must preserve the measured 0.125-unit gap");
    }

    @Test public void multiPlanFillsPlayerFacingRowBeforeMovingDeeper() {
        Area area = new Area(Coord.z, Coord.of(4, 3));
        ExactPlacementPlanner.Shape box = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(-5, -2, 5, 2));
        List<Coord2d> anchors = ExactPlacementPlanner.planExact(
            area, TILE, box, new ArrayList<>(), Coord2d.of(-100, 16.5), 0.125, 3);

        assertEquals(3, anchors.size());
        assertEquals(anchors.get(0).x, anchors.get(1).x, 1e-9);
        assertEquals(anchors.get(1).x, anchors.get(2).x, 1e-9);
        assertTrue(anchors.get(0).x < 11.0, "the first row must be on the player-facing edge");
    }

    @Test public void verticalLogsTightlyFillSideBySideBeforeMovingDeeper() {
        Area area = new Area(Coord.z, Coord.of(3, 5));
        ExactPlacementPlanner.Shape log = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(-2, -10, 2, 10));
        List<Coord2d> anchors = ExactPlacementPlanner.planExact(
            area, TILE, log, new ArrayList<>(), Coord2d.of(16.5, -100), 0.125, 10,
            ExactPlacementPlanner.FillOrder.SIDE_BY_SIDE);

        assertEquals(10, anchors.size());
        assertEquals(anchors.get(0).y, anchors.get(1).y, 1e-9,
            "vertical logs must share a front-to-back row");
        assertEquals(4.125, Math.abs(anchors.get(1).x - anchors.get(0).x), 1e-9,
            "the centers must differ by the four-unit log width plus the recorded gap");
        int nextRow = -1;
        for(int i = 1; i < anchors.size(); i++) {
            if(Math.abs(anchors.get(i).y - anchors.get(0).y) > 1e-9) {
                nextRow = i;
                break;
            }
        }
        assertEquals(8, nextRow, "the narrow-axis row must fill before advancing behind it");
        assertEquals(anchors.get(0).x, anchors.get(nextRow).x, 1e-9,
            "the next row must restart at the same side after routing around the full row");
    }

    @Test public void horizontalLogsAlsoAdvanceAlongTheirNarrowAxisFirst() {
        Area area = new Area(Coord.z, Coord.of(5, 3));
        ExactPlacementPlanner.Shape log = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(-10, -2, 10, 2));
        List<Coord2d> anchors = ExactPlacementPlanner.planExact(
            area, TILE, log, new ArrayList<>(), Coord2d.of(-100, 16.5), 0.125, 2,
            ExactPlacementPlanner.FillOrder.SIDE_BY_SIDE);

        assertEquals(2, anchors.size());
        assertEquals(anchors.get(0).x, anchors.get(1).x, 1e-9);
        assertEquals(4.125, Math.abs(anchors.get(1).y - anchors.get(0).y), 1e-9);
    }

    @Test public void backToFrontLogRowsKeepTheApproachSideOpen() {
        Area area = new Area(Coord.z, Coord.of(5, 4));
        ExactPlacementPlanner.Shape log = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(-10, -2, 10, 2));
        Coord2d source = Coord2d.of(-100, 22);
        List<Coord2d> anchors = ExactPlacementPlanner.planExact(
            area, TILE, log, new ArrayList<>(), source, 0.125, 12,
            ExactPlacementPlanner.FillOrder.SIDE_BY_SIDE_BACK_TO_FRONT);

        assertEquals(12, anchors.size());
        double farRowX = anchors.get(0).x;
        assertTrue(farRowX > area.br.x * TILE.x * 0.5,
            "the first row must be on the edge farthest from the clear-cut source");
        int nextRow = -1;
        for(int i = 1; i < anchors.size(); i++) {
            if(Math.abs(anchors.get(i).x - farRowX) > 1e-9) {
                nextRow = i;
                break;
            }
        }
        assertTrue(nextRow > 0, "the fixture must fill more than one row");
        assertTrue(anchors.get(nextRow).x < farRowX,
            "later rows must advance toward the source instead of walling them off");
    }

    @Test public void verticalDropAreaFillsLeftToRightThenMovesTowardClearCut() {
        Area area = new Area(Coord.z, Coord.of(6, 5));
        ExactPlacementPlanner.Shape log = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(-10, -2, 10, 2));
        Coord2d clearCutAbove = Coord2d.of(33, -100);
        double farRowY = 53.0;
        double rightSlotX = 50.25;
        List<ExactPlacementPlanner.Shape> existingRightColumn = new ArrayList<>();
        existingRightColumn.add(log.move(Coord2d.of(rightSlotX, farRowY)));
        existingRightColumn.add(log.move(Coord2d.of(rightSlotX, farRowY - 4.125)));

        List<Coord2d> anchors = ExactPlacementPlanner.planExact(
            area, TILE, log, existingRightColumn, clearCutAbove, 0.125, 3,
            ExactPlacementPlanner.FillOrder.SIDE_BY_SIDE_BACK_TO_FRONT);

        assertEquals(3, anchors.size());
        assertEquals(farRowY, anchors.get(0).y, 1e-9);
        assertEquals(farRowY, anchors.get(1).y, 1e-9,
            "the far row must be filled before moving toward the clear-cut area");
        assertTrue(anchors.get(0).x < anchors.get(1).x,
            "open slots in a row must be filled from left to right");
        assertEquals(anchors.get(0).x, anchors.get(2).x, 1e-9,
            "the next row must restart at the left edge");
        assertTrue(anchors.get(2).y < farRowY,
            "after a full row, the next row must move toward the clear-cut area");
    }

    @Test public void serverRoundedLogContinuesAtRecordedTightGap() {
        Area area = new Area(Coord.z, Coord.of(5, 5));
        ExactPlacementPlanner.Shape log = ExactPlacementPlanner.Shape.rectangle(
            new ExactPlacementPlanner.Rect(-10, -2, 10, 2));
        Coord2d observedAnchor = Coord2d.of(10, 2 - OCache.posres.y);
        List<ExactPlacementPlanner.Shape> obstacles = Collections.singletonList(
            log.move(observedAnchor));

        Coord2d next = ExactPlacementPlanner.nextExact(
            area, TILE, log, obstacles, Coord2d.of(-100, 27.5), 0.125,
            ExactPlacementPlanner.FillOrder.SIDE_BY_SIDE);

        assertNotNull(next);
        assertEquals(observedAnchor.x, next.x, 1e-9);
        assertEquals(4.125, next.y - observedAnchor.y, 1e-9,
            "wire-coordinate drift must not make the planner skip a tight slot");
    }

    private static Coord2d[] rotatedRectangle(double halfLength, double halfWidth, double angle) {
        Coord2d[] local = {
            Coord2d.of(-halfLength, -halfWidth), Coord2d.of(halfLength, -halfWidth),
            Coord2d.of(halfLength, halfWidth), Coord2d.of(-halfLength, halfWidth)
        };
        Coord2d[] out = new Coord2d[local.length];
        double cs = Math.cos(angle), sn = Math.sin(angle);
        for(int i = 0; i < local.length; i++) out[i] = Coord2d.of(
            local[i].x * cs - local[i].y * sn,
            local[i].x * sn + local[i].y * cs);
        return out;
    }
}
