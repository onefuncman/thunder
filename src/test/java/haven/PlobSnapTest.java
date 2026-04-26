package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PlobSnapTest {
    private static final double EPS = 1e-9;
    private static final double LOOSE = 1e-6;

    // --- abutAgainst (placer flush against a gob, outside it) -------------

    @Test
    void abutAgainst_nullInputs() {
	double[] ok = {0, 0, 1, 1};
	assertNull(PlobSnap.abutAgainst(null, ok, PlobSnap.Dir.LEFT));
	assertNull(PlobSnap.abutAgainst(ok, null, PlobSnap.Dir.LEFT));
	assertNull(PlobSnap.abutAgainst(new double[3], ok, PlobSnap.Dir.LEFT));
    }

    // Post-condition sanity check for each direction: after the computed delta is applied, the
    // placer's edge facing the target must coincide with the target's edge facing the placer.
    private static double[] apply(double[] aabb, double[] d) {
	return new double[] { aabb[0] + d[0], aabb[1] + d[1], aabb[2] + d[0], aabb[3] + d[1] };
    }

    @Test
    void abutAgainst_leftPutsPlacerOnTargetLeftSide() {
	// User pressed LEFT expecting placer to end up on the target's left side.
	// Invariant: new placer.right == target.left.
	double[] placer = {614, 468, 714, 538};
	double[] target = {710, 468, 834, 573};   // overlaps placer
	double[] after = apply(placer, PlobSnap.abutAgainst(placer, target, PlobSnap.Dir.LEFT));
	assertEquals(target[0], after[2], LOOSE, "placer.right must meet target.left");
	assertTrue(after[2] <= target[0] + LOOSE, "placer must end up on LEFT of target");
    }

    @Test
    void abutAgainst_rightPutsPlacerOnTargetRightSide() {
	// Invariant: new placer.left == target.right.
	double[] placer = {614, 468, 714, 538};
	double[] target = {710, 468, 834, 573};
	double[] after = apply(placer, PlobSnap.abutAgainst(placer, target, PlobSnap.Dir.RIGHT));
	assertEquals(target[2], after[0], LOOSE, "placer.left must meet target.right");
	assertTrue(after[0] >= target[2] - LOOSE, "placer must end up on RIGHT of target");
    }

    @Test
    void abutAgainst_upPutsPlacerOnTargetTopSide() {
	// Screen-Y grows downward, so "UP" means lower Y.
	// Invariant: new placer.bottom == target.top.
	double[] placer = {0, 200, 10, 220};
	double[] target = {0, 100, 10, 180};
	double[] after = apply(placer, PlobSnap.abutAgainst(placer, target, PlobSnap.Dir.UP));
	assertEquals(target[1], after[3], LOOSE, "placer.bottom must meet target.top");
	assertTrue(after[3] <= target[1] + LOOSE, "placer must end up ABOVE target");
    }

    @Test
    void abutAgainst_downPutsPlacerOnTargetBottomSide() {
	// Invariant: new placer.top == target.bottom.
	double[] placer = {0, 200, 10, 220};
	double[] target = {0, 100, 10, 180};
	double[] after = apply(placer, PlobSnap.abutAgainst(placer, target, PlobSnap.Dir.DOWN));
	assertEquals(target[3], after[1], LOOSE, "placer.top must meet target.bottom");
	assertTrue(after[1] >= target[3] - LOOSE, "placer must end up BELOW target");
    }

    @Test
    void abutAgainst_leftOnlyAffectsX() {
	double[] placer = {50, 50, 60, 60};
	double[] target = { 0, 20, 20, 40};
	double[] d = PlobSnap.abutAgainst(placer, target, PlobSnap.Dir.LEFT);
	assertEquals(0, d[1], EPS, "LEFT must not touch Y axis");
    }

    @Test
    void abutAgainst_chainLeftThenUp_composesToCorner() {
	// Placer starts inside the target. LEFT: placer.right meets target.left. X preserved after UP.
	// UP: placer.bottom meets target.top. Final: placer flush top-left corner of target.
	double[] placer = {50, 50, 70, 70};
	double[] target = {40, 40, 80, 80};

	double[] after1 = apply(placer, PlobSnap.abutAgainst(placer, target, PlobSnap.Dir.LEFT));
	assertEquals(target[0], after1[2], LOOSE); // placer.right == target.left
	assertEquals(placer[1], after1[1], LOOSE); // Y preserved

	double[] after2 = apply(after1, PlobSnap.abutAgainst(after1, target, PlobSnap.Dir.UP));
	assertEquals(target[0], after2[2], LOOSE); // X preserved after UP
	assertEquals(target[1], after2[3], LOOSE); // placer.bottom == target.top
    }

    // Regression guard matching the exact numbers from the game log where the bug surfaced.
    // placer=[614.84, 468.64 .. 714.56, 538.19]  target=[710.86, 468.64 .. 834.84, 573.04]
    // Pressing LEFT must move placer left (dSX negative), not right.
    @Test
    void abutAgainst_leftRegressionFromLiveLog() {
	double[] placer = {614.84, 468.64, 714.56, 538.19};
	double[] target = {710.86, 468.64, 834.84, 573.04};
	double[] d = PlobSnap.abutAgainst(placer, target, PlobSnap.Dir.LEFT);
	assertTrue(d[0] < 0, "LEFT on overlapping target must produce negative dSX");
	assertEquals(target[0] - placer[2], d[0], EPS);
    }

    // --- alignEdgeWith (placer flush with a region's same-side edge) ------

    @Test
    void alignEdgeWith_nullInputs() {
	double[] ok = {0, 0, 1, 1};
	assertNull(PlobSnap.alignEdgeWith(null, ok, PlobSnap.Dir.LEFT));
	assertNull(PlobSnap.alignEdgeWith(ok, null, PlobSnap.Dir.LEFT));
    }

    @Test
    void alignEdgeWith_leftAlignsPlacerLeftWithRegionLeft() {
	// Placer starts inside the region, not flush; LEFT snaps placer.left to region.left.
	double[] placer = {5, 5, 8, 8};
	double[] region = {0, 0, 10, 10};
	double[] d = PlobSnap.alignEdgeWith(placer, region, PlobSnap.Dir.LEFT);
	assertEquals(-5, d[0], EPS); // placer.left 5 -> 0
	assertEquals(0,  d[1], EPS);
    }

    @Test
    void alignEdgeWith_rightAlignsPlacerRightWithRegionRight() {
	double[] placer = {2, 2, 5, 5};
	double[] region = {0, 0, 10, 10};
	double[] d = PlobSnap.alignEdgeWith(placer, region, PlobSnap.Dir.RIGHT);
	assertEquals(5, d[0], EPS); // placer.right 5 -> 10
	assertEquals(0, d[1], EPS);
    }

    @Test
    void alignEdgeWith_upAlignsPlacerTopWithRegionTop() {
	double[] placer = {2, 5, 5, 8};
	double[] region = {0, 0, 10, 10};
	double[] d = PlobSnap.alignEdgeWith(placer, region, PlobSnap.Dir.UP);
	assertEquals(0,  d[0], EPS);
	assertEquals(-5, d[1], EPS); // placer.top 5 -> 0
    }

    @Test
    void alignEdgeWith_downAlignsPlacerBottomWithRegionBottom() {
	double[] placer = {2, 2, 5, 5};
	double[] region = {0, 0, 10, 10};
	double[] d = PlobSnap.alignEdgeWith(placer, region, PlobSnap.Dir.DOWN);
	assertEquals(0, d[0], EPS);
	assertEquals(5, d[1], EPS); // placer.bottom 5 -> 10
    }

    @Test
    void alignEdgeWith_alreadyAlignedIsNoOp() {
	double[] placer = {0, 0, 3, 3};
	double[] region = {0, 0, 10, 10};
	double[] d = PlobSnap.alignEdgeWith(placer, region, PlobSnap.Dir.LEFT);
	assertEquals(0, d[0], EPS);
	assertEquals(0, d[1], EPS);
    }

    // --- jacobianInvert ---------------------------------------------------

    @Test
    void jacobianInvert_identity() {
	Coord2d w = PlobSnap.jacobianInvert(1, 0, 0, 1, 3, -4);
	assertNotNull(w);
	assertEquals(3,  w.x, EPS);
	assertEquals(-4, w.y, EPS);
    }

    @Test
    void jacobianInvert_singularReturnsNull() {
	// Zero matrix.
	assertNull(PlobSnap.jacobianInvert(0, 0, 0, 0, 1, 1));
	// Rank-1 (both rows proportional).
	assertNull(PlobSnap.jacobianInvert(1, 2, 2, 4, 1, 1));
    }

    @Test
    void jacobianInvert_rotation90() {
	// Screen basis is world rotated 90 CCW:
	// dScreenX =  0*dwx + 1*dwy
	// dScreenY = -1*dwx + 0*dwy
	// Inverse: dwx = -dSY, dwy = dSX.
	Coord2d w = PlobSnap.jacobianInvert(0, 1, -1, 0, 3, 5);
	assertNotNull(w);
	assertEquals(-5, w.x, EPS);
	assertEquals( 3, w.y, EPS);
    }

    @Test
    void jacobianInvert_scaledAxes() {
	// Screen has 2x x-zoom, 3x y-zoom.
	Coord2d w = PlobSnap.jacobianInvert(2, 0, 0, 3, 10, 9);
	assertNotNull(w);
	assertEquals(5, w.x, EPS);
	assertEquals(3, w.y, EPS);
    }

    @Test
    void jacobianInvert_zeroDisplacementIsZero() {
	Coord2d w = PlobSnap.jacobianInvert(2, 1, -1, 2, 0, 0);
	assertNotNull(w);
	assertEquals(0, w.x, EPS);
	assertEquals(0, w.y, EPS);
    }

    // --- integration: abut + Jacobian round-trip ------------------------

    @Test
    void roundTrip_identityProjectionPlacerEndsUpOnTargetLeftSide() {
	// Identity Jacobian (pretend screen == world).
	// Placer overlaps target; pressing LEFT must put placer.right on target.left.
	double[] placer = {  0, -1, 10, 1 };
	double[] target = {  5, -1, 15, 1 };

	double[] screenDelta = PlobSnap.abutAgainst(placer, target, PlobSnap.Dir.LEFT);
	Coord2d worldDelta = PlobSnap.jacobianInvert(1, 0, 0, 1, screenDelta[0], screenDelta[1]);

	double newRight = placer[2] + worldDelta.x;
	assertEquals(target[0], newRight, LOOSE);       // placer.right = target.left
	assertTrue(worldDelta.x < 0, "LEFT must move placer left");
	assertEquals(0, worldDelta.y, EPS);
    }
}
