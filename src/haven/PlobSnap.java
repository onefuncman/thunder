package haven;

// Pure math for placement-mode snap. Kept free of MapView/Resource/Gob so it can
// be unit-tested without a running client or GL context.
public final class PlobSnap {
    public enum Dir { LEFT, RIGHT, UP, DOWN }

    private PlobSnap() {}

    // Placer goes on the `dir` side of the target: placer's far edge (facing the target)
    // meets the target's near edge (facing the placer), so the placer ends up flush against
    // the target on target's `dir` side.
    // Examples:
    //   LEFT  -> placer is on the target's LEFT side; placer.right meets target.left.
    //   RIGHT -> placer is on the target's RIGHT side; placer.left  meets target.right.
    // Returns {dSX, dSY} in the same coord system as the input AABBs.
    public static double[] abutAgainst(double[] placer, double[] target, Dir dir) {
	if(placer == null || target == null) return null;
	if(placer.length != 4 || target.length != 4) return null;
	switch(dir) {
	case LEFT:  return new double[] { target[0] - placer[2], 0 }; // placer.right  = target.left
	case RIGHT: return new double[] { target[2] - placer[0], 0 }; // placer.left   = target.right
	case UP:    return new double[] { 0, target[1] - placer[3] }; // placer.bottom = target.top
	case DOWN:  return new double[] { 0, target[3] - placer[1] }; // placer.top    = target.bottom
	}
	return null;
    }

    // Placer aligns its same-side edge with the region's same-side edge (e.g. "snap to the
    // left edge of the hovered tile" -> placer.left = tile.left). Placer stays inside the
    // region on that axis, flush with the `dir` edge.
    public static double[] alignEdgeWith(double[] placer, double[] region, Dir dir) {
	if(placer == null || region == null) return null;
	if(placer.length != 4 || region.length != 4) return null;
	switch(dir) {
	case LEFT:  return new double[] { region[0] - placer[0], 0 };
	case RIGHT: return new double[] { region[2] - placer[2], 0 };
	case UP:    return new double[] { 0, region[1] - placer[1] };
	case DOWN:  return new double[] { 0, region[3] - placer[3] };
	}
	return null;
    }

    // Invert a 2x2 screen<-world Jacobian to convert a screen displacement into the
    // world displacement that would produce it near the sample point. J layout:
    //   dScreenX = jxx * dWorldX + jxy * dWorldY
    //   dScreenY = jyx * dWorldX + jyy * dWorldY
    // Returns null when the Jacobian is singular (|det| < 1e-9).
    public static Coord2d jacobianInvert(double jxx, double jxy, double jyx, double jyy,
					 double dSX, double dSY) {
	double det = jxx * jyy - jxy * jyx;
	if(Math.abs(det) < 1e-9) return null;
	double dwx = ( jyy * dSX - jxy * dSY) / det;
	double dwy = (-jyx * dSX + jxx * dSY) / det;
	return Coord2d.of(dwx, dwy);
    }

}
