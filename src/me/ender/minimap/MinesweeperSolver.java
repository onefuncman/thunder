package me.ender.minimap;

/**
 * Deduce safe vs cave-in wall tiles from observed cave-dust counts.
 * Dust N on a mined tile means N of the 8 neighbors (including diagonals)
 * are collapse tiles; 0 means all 8 are safe. Classic minesweeper singles.
 */
public final class MinesweeperSolver {
    public static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    public static final int[] DY = {-1, -1, -1, 0, 0, 1, 1, 1};

    private MinesweeperSolver() {}

    public static int countOf(byte v) {
	return v & Minesweeper.COUNT_MASK;
    }

    public static byte flagsOf(byte v) {
	return (byte) (v & Minesweeper.FLAGS_MASK);
    }

    public static boolean isOpened(byte v) {
	return (v & Minesweeper.FLAG_OPENED) != 0;
    }

    public static boolean isHint(byte v) {
	if((v & Minesweeper.FLAG_COUNTED) != 0)
	    return true;
	int c = countOf(v);
	return (c >= 1) && (c <= 8);
    }

    public static boolean isDanger(byte v) {
	return flagsOf(v) == Minesweeper.FLAG_DANGER;
    }

    public static boolean isSafe(byte v) {
	return flagsOf(v) == Minesweeper.FLAG_SAFE;
    }

    public static boolean isMaybe(byte v) {
	return flagsOf(v) == Minesweeper.FLAG_MAYBE;
    }

    public static boolean isUnmarked(byte v) {
	return !isOpened(v) && !isSafe(v) && !isDanger(v) && !isMaybe(v);
    }

    /** True when an unmarked tile sits next to a mined dust count. */
    public static boolean isUnknownFrontier(byte[] values, int w, int h, int x, int y) {
	if((values == null) || (x < 0) || (y < 0) || (x >= w) || (y >= h) || (values.length < (w * h)))
	    return false;
	if(!isUnmarked(values[x + y * w]))
	    return false;
	for(int i = 0; i < 8; i++) {
	    int nx = x + DX[i], ny = y + DY[i];
	    if((nx < 0) || (ny < 0) || (nx >= w) || (ny >= h))
		continue;
	    if(isHint(values[nx + ny * w]))
		return true;
	}
	return false;
    }

    /**
     * Mutates {@code values} in place. Returns whether any flags changed.
     * Does not overwrite existing SAFE/DANGER/MAYBE marks.
     * Already-mined neighbors count against a hint's dust total, so a
     * collapse you already opened cannot force leftover walls to red.
     */
    public static boolean deduce(byte[] values, int w, int h) {
	if((values == null) || (values.length < (w * h)))
	    return false;
	boolean any = false;
	boolean changed = true;
	while(changed) {
	    changed = false;
	    for(int y = 0; y < h; y++) {
		for(int x = 0; x < w; x++) {
		    if(applyHint(values, w, h, x, y))
			changed = true;
		}
	    }
	    any |= changed;
	}
	return any;
    }

    private static boolean applyHint(byte[] values, int w, int h, int x, int y) {
	byte v = values[x + y * w];
	if(!isHint(v))
	    return false;
	int mines = countOf(v);
	int danger = 0;
	int unknown = 0;
	int openedN = 0;
	int maybeN = 0;
	int[] unk = new int[8];
	for(int i = 0; i < 8; i++) {
	    int nx = x + DX[i], ny = y + DY[i];
	    if((nx < 0) || (ny < 0) || (nx >= w) || (ny >= h))
		continue;
	    int idx = nx + ny * w;
	    byte n = values[idx];
	    if(isOpened(n) || isHint(n)) {
		/* already-mined neighbors may themselves have been collapse
		 * tiles; they still count against this hint's dust total */
		openedN++;
		continue;
	    }
	    if(isDanger(n)) {
		danger++;
	    } else if(isMaybe(n)) {
		maybeN++;
	    } else if(isSafe(n)) {
		/* known safe wall */
	    } else {
		unk[unknown++] = idx;
	    }
	}
	if(unknown == 0)
	    return false;
	byte mark;
	if(danger == mines)
	    mark = Minesweeper.FLAG_SAFE;
	else if((danger + openedN + maybeN + unknown) == mines)
	    mark = Minesweeper.FLAG_DANGER;
	else
	    return false;
	boolean changed = false;
	for(int i = 0; i < unknown; i++) {
	    int idx = unk[i];
	    byte cur = values[idx];
	    byte next = (byte) ((cur & Minesweeper.COUNT_MASK) | mark);
	    if(next != cur) {
		values[idx] = next;
		changed = true;
	    }
	}
	return changed;
    }

    /**
     * Drop auto-SAFE marks on the 8 neighbors. Used when a tile was treated as
     * 0-dust (mineout with no cavewarn yet) and a real dust count arrives after.
     */
    public static void retractSafeNeighbors(byte[] values, int w, int h, int x, int y) {
	if((values == null) || (values.length < (w * h)))
	    return;
	for(int i = 0; i < 8; i++) {
	    int nx = x + DX[i], ny = y + DY[i];
	    if((nx < 0) || (ny < 0) || (nx >= w) || (ny >= h))
		continue;
	    int idx = nx + ny * w;
	    byte n = values[idx];
	    if(isSafe(n) && !isOpened(n) && !isHint(n))
		values[idx] = (byte) (n & Minesweeper.COUNT_MASK);
	}
    }
}
