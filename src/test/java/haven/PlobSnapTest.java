package haven;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// Ported from Vantazz/Hurricane's PlobSnapCheck. All distances are world units
// and a tile is 11 of them, so the numbers below are in thirds-of-a-tile
// rather than anything round.
public class PlobSnapTest {
    private static final double EPS = 1e-6;

    // The tunables are mutable statics initialized from user prefs; pin them so
    // a locally-tuned client doesn't change what the tests assert.
    @BeforeAll
    static void pinTunables() {
	PlobSnap.capture = 3.5;
	PlobSnap.deadzone = 6.5;
	PlobSnap.abutgap = 0.1;
	PlobSnap.wallgap = 1.0;
    }

    /** The 6x6 ghost used throughout. */
    private static final PlobSnap.Box SELF = new PlobSnap.Box(-3, -3, 3, 3);

    private static double ax(boolean xaxis, List<PlobSnap.Box> near, double cx, double cy) {
	return PlobSnap.axis(xaxis, near, SELF, Coord2d.of(cx, cy), PlobSnap.deadzone);
    }

    /** The two-pass resolution snap() does, minus the hysteresis. */
    private static Coord2d settle(List<PlobSnap.Box> near, Coord2d mc) {
	Coord2d c = mc;
	double rx = Double.NaN, ry = Double.NaN;
	for(int p = 0; p < 2; p++) {
	    rx = ax(true, near, c.x, c.y);
	    ry = ax(false, near, c.x, c.y);
	    c = Coord2d.of(Double.isNaN(rx) ? mc.x : rx, Double.isNaN(ry) ? mc.y : ry);
	}
	double sx = (!Double.isNaN(rx) && Math.abs(rx - mc.x) <= PlobSnap.capture) ? rx : Double.NaN;
	double sy = (!Double.isNaN(ry) && Math.abs(ry - mc.y) <= PlobSnap.capture) ? ry : Double.NaN;
	return Coord2d.of(Double.isNaN(sx) ? mc.x : sx, Double.isNaN(sy) ? mc.y : sy);
    }

    private static final PlobSnap.Box HWALL = new PlobSnap.Box(-50, -2, 50, 2);      // runs east-west
    private static final PlobSnap.Box VWALL = new PlobSnap.Box(-52, -50, -48, 50);   // runs north-south

    // --- against a single wall --------------------------------------------

    @Test
    void yGoesFlushUnderAWall() {
	assertEquals(5.0, ax(false, Arrays.asList(HWALL), 0, 8), EPS);
    }

    @Test
    void xStaysFreeSoTheGhostSlidesAlong() {
	assertTrue(Double.isNaN(ax(true, Arrays.asList(HWALL), 0, 8)));
    }

    @Test
    void letsGoOnceDraggedPastTheDeadZone() {
	assertTrue(Double.isNaN(ax(false, Arrays.asList(HWALL), 0, 20)));
    }

    // --- into a corner ----------------------------------------------------

    @Test
    void cornerCatchesBothAxes() {
	List<PlobSnap.Box> two = Arrays.asList(HWALL, VWALL);
	Coord2d corner = settle(two, Coord2d.of(-42, 8));
	assertEquals(-45.0, corner.x, EPS);
	assertEquals(5.0, corner.y, EPS);
    }

    @Test
    void outInTheOpenOnlyTheWallAxisHolds() {
	List<PlobSnap.Box> two = Arrays.asList(HWALL, VWALL);
	Coord2d midway = settle(two, Coord2d.of(-20, 8));
	assertEquals(-20.0, midway.x, EPS, "still free on x out in the open");
	assertEquals(5.0, midway.y, EPS, "still held against the wall on y");
    }

    // --- a small object leaning on a big one ------------------------------

    // The bug this guards: aligning edges with a wall you are touching buries
    // the object in it. Alignment needs separation on the other axis.
    @Test
    void goesFlushOnTheFaceNotEdgeAligned() {
	List<PlobSnap.Box> house = Arrays.asList(new PlobSnap.Box(0, -30, 40, 30));
	assertEquals(-3.0, ax(true, house, -2, 0), EPS, "goes flush on the face");
	assertEquals(-3.0, settle(house, Coord2d.of(-2, 0)).x, EPS, "and does not line up its edges instead");
    }

    // --- a second object beside the first, both on the wall ---------------

    @Test
    void flushAgainstNeighbourAndEdgeAlignedUnderTheWall() {
	PlobSnap.Box chest = new PlobSnap.Box(-8, -1, -2, 5);
	Coord2d beside = settle(Arrays.asList(HWALL, chest), Coord2d.of(2, 6));
	assertEquals(1.0, beside.x, EPS, "flush against the first");
	assertEquals(5.0, beside.y, EPS, "edges lined up under the wall");
    }

    // --- clearance the server insists on ----------------------------------

    @Test
    void stopsShortOfTheNeighbourByItsGap() {
	assertEquals(6.0, ax(false, Arrays.asList(new PlobSnap.Box(-50, -2, 50, 2, 1.0)), 0, 8), EPS,
		     "stops 1.0 short of a wall face");
	assertEquals(5.1, ax(false, Arrays.asList(new PlobSnap.Box(-50, -2, 50, 2, 0.1)), 0, 8), EPS,
		     "stops 0.1 short of an ordinary face");
	for(double g : new double[]{0.0, 0.1, 1.0}) {
	    double c = ax(false, Arrays.asList(new PlobSnap.Box(-50, -2, 50, 2, g)), 0, 8);
	    assertEquals(g, (c - 3.0) - 2.0, EPS, "the gap never eats into the neighbour (gap " + g + ")");
	}
    }

    // --- the dead zone holds and then lets go ------------------------------

    // Grabs inside `capture`, holds out to `deadzone`, releases past it.
    @Test
    void hysteresisGrabsHoldsAndReleases() {
	List<PlobSnap.Box> one = Arrays.asList(HWALL);
	double held = Double.NaN;
	boolean grabbed = false, heldOn = false, released = false;
	for(double y : new double[]{12, 9, 8, 10, 11.5, 12, 20}) {
	    double cand = ax(false, one, 0, y);
	    held = PlobSnap.resolve(cand, y, held);
	    boolean on = !Double.isNaN(held);
	    if(y == 8) grabbed = on;
	    if(y == 11.5) heldOn = on;
	    if(y == 20) released = !on;
	    if(on)
		assertEquals(5.0, held, EPS, "held the flush edge at y=" + y);
	}
	assertTrue(grabbed, "grabs when the cursor comes inside the capture radius");
	assertTrue(heldOn, "still holding at the edge of the dead zone");
	assertTrue(released, "free again well past it");
    }

    // --- terrain walls, which carry no gob at all --------------------------

    @Test
    void backsUpAgainstAnEastWestCaveWall() {
	double ts = MCache.tilesz.x;
	double gap = PlobSnap.abutgap, reach = PlobSnap.deadzone;
	// Rock everywhere at tile y <= 0; open floor below it.
	PlobSnap.Rock roof = (tx, ty) -> ty <= 0;
	assertEquals(ts + 3 + gap,
		     PlobSnap.faceaxis((along, across) -> roof.at(across, along), -3, 3, 25 - 3, 25 + 3, 16, reach, gap),
		     EPS);
    }

    // The regression that killed the box-per-tile approach: a wall is a run of
    // tiles, and each tile's side faces would chop the slide into steps.
    @Test
    void noFaceAnywhereAlongTheRunSoTheSlideStaysSmooth() {
	PlobSnap.Rock roof = (tx, ty) -> ty <= 0;
	for(double cx = 14; cx <= 36; cx += 2.0) {
	    assertTrue(Double.isNaN(PlobSnap.faceaxis(roof, -3, 3, 16 - 3, 16 + 3, cx, PlobSnap.deadzone, PlobSnap.abutgap)),
		       "no face at cx=" + cx);
	}
    }

    @Test
    void caveCornerCatchesBothAxes() {
	double ts = MCache.tilesz.x;
	double gap = PlobSnap.abutgap, reach = PlobSnap.deadzone;
	PlobSnap.Rock corner = (tx, ty) -> (ty <= 0) || (tx <= 0);
	assertEquals(ts + 3 + gap, PlobSnap.faceaxis(corner, -3, 3, 16 - 3, 16 + 3, 16, reach, gap), EPS, "cave corner, x");
	assertEquals(ts + 3 + gap,
		     PlobSnap.faceaxis((along, across) -> corner.at(across, along), -3, 3, 16 - 3, 16 + 3, 16, reach, gap),
		     EPS, "cave corner, y");
    }

    @Test
    void solidRockAndOpenFloorOfferNoFace() {
	double gap = PlobSnap.abutgap, reach = PlobSnap.deadzone;
	assertTrue(Double.isNaN(PlobSnap.faceaxis((tx, ty) -> true, -3, 3, 13, 19, 25, reach, gap)), "solid rock");
	assertTrue(Double.isNaN(PlobSnap.faceaxis((tx, ty) -> false, -3, 3, 13, 19, 25, reach, gap)), "open floor");
    }

    // --- the rotated-box helper -------------------------------------------

    @Test
    void rotSwapsExtentsAtNinetyDegrees() {
	PlobSnap.Box b = new PlobSnap.Box(-2, -5, 2, 5, 0.1).rot(Math.PI / 2);
	assertEquals(-5, b.lx, EPS);
	assertEquals(-2, b.ly, EPS);
	assertEquals(5, b.hx, EPS);
	assertEquals(2, b.hy, EPS);
	assertEquals(0.1, b.gap, EPS, "gap survives rotation");
    }
}
