package me.ender.minimap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MinesweeperSolverEdgeTest {
    @Test
    void openedCollapseDoesNotForceTheLastUnknownDanger() {
	/* 2x2: mine only at SE. NW is a 1, SE is mined (count 0), NE flagged
	 * safe. Old rule treated SE as gone and painted SW red. */
	MinesweeperAscii.Grid got = MinesweeperAscii.deduce(MinesweeperAscii.parse(
	    "1 S\n. 0\n"));
	assertEquals("1 S\nS 0\n", got.format());
	assertFalse(MinesweeperSolver.isDanger(got.at(0, 1)));
    }

    @Test
    void cornerZeroGreensOnlyThreeNeighbors() {
	MinesweeperAscii.Grid got = MinesweeperAscii.deduce(MinesweeperAscii.parse(
	    "0 . .\n. . .\n. . .\n"));
	assertEquals("0 S .\nS S .\n. . .\n", got.format());
    }

    @Test
    void cornerThreeUnknownsAreAllDanger() {
	MinesweeperAscii.Grid got = MinesweeperAscii.deduce(MinesweeperAscii.parse(
	    "3 . .\n. . .\n. . .\n"));
	assertEquals("3 D .\nD D .\n. . .\n", got.format());
    }

    @Test
    void eightDustPaintsEveryNeighborDanger() {
	MinesweeperAscii.Grid got = MinesweeperAscii.deduce(MinesweeperAscii.parse(
	    ". . .\n. 8 .\n. . .\n"));
	assertEquals("D D D\nD 8 D\nD D D\n", got.format());
    }

    @Test
    void countBiggerThanNeighborsIsANoOp() {
	MinesweeperAscii.Grid got = MinesweeperAscii.deduce(MinesweeperAscii.parse(
	    "8 . .\n. . .\n. . .\n"));
	assertEquals("8 . .\n. . .\n. . .\n", got.format());
    }

    @Test
    void oneByOneZeroIsANoOp() {
	MinesweeperAscii.Grid got = MinesweeperAscii.deduce(MinesweeperAscii.parse("0"));
	assertEquals("0\n", got.format());
    }

    @Test
    void emptyAndNullDeduceAreFalse() {
	assertFalse(MinesweeperSolver.deduce(null, 3, 3));
	assertFalse(MinesweeperSolver.deduce(new byte[3], 3, 3));
	assertFalse(MinesweeperSolver.deduce(new byte[9], 3, 3));
    }

    @Test
    void maybeBlocksAllUnknownDanger() {
	/* 1-dust, one maybe, one unknown: maybe might be the mine. */
	MinesweeperAscii.Grid got = MinesweeperAscii.deduce(MinesweeperAscii.parse(
	    "1 M\n. .\n"));
	assertFalse(MinesweeperSolver.isDanger(got.at(0, 1)));
	assertFalse(MinesweeperSolver.isSafe(got.at(0, 1)));
    }

    @Test
    void frontierIgnoresOutOfBounds() {
	byte[] g = MinesweeperAscii.parse("0 .").values;
	assertTrue(MinesweeperSolver.isUnknownFrontier(g, 2, 1, 1, 0));
	assertFalse(MinesweeperSolver.isUnknownFrontier(g, 2, 1, -1, 0));
	assertFalse(MinesweeperSolver.isUnknownFrontier(g, 2, 1, 0, 0));
    }

    @Test
    void exhaustive3x3NeverLies() {
	int w = 3, h = 3, n = w * h;
	int lied = 0;
	String first = null;
	for(int mineMask = 0; mineMask < (1 << n); mineMask++) {
	    boolean[] mine = new boolean[n];
	    for(int i = 0; i < n; i++)
		mine[i] = ((mineMask >> i) & 1) != 0;
	    for(int openMask = 0; openMask < (1 << n); openMask++) {
		byte[] obs = new byte[n];
		for(int i = 0; i < n; i++) {
		    if(((openMask >> i) & 1) == 0)
			continue;
		    int x = i % w, y = i / w;
		    obs[i] = MinesweeperScenarios.counted(
			MinesweeperPuzzleGen.neighborMines(mine, w, h, x, y));
		}
		byte[] got = obs.clone();
		MinesweeperSolver.deduce(got, w, h);
		MinesweeperPuzzleGen.Puzzle p = new MinesweeperPuzzleGen.Puzzle(w, h, mine, obs);
		try {
		    MinesweeperPuzzleGen.assertSound(p, got);
		} catch (AssertionError e) {
		    lied++;
		    if(first == null)
			first = "mines=" + Integer.toBinaryString(mineMask)
			    + " open=" + Integer.toBinaryString(openMask)
			    + "\n" + e.getMessage();
		}
	    }
	}
	assertEquals(0, lied, first);
    }

    @Test
    void randomPuzzlesWithOpenedCollapsesStaySound() {
	int lied = 0;
	String first = null;
	for(long seed = 0; seed < 500; seed++) {
	    MinesweeperPuzzleGen.Puzzle p = MinesweeperPuzzleGen.generate(seed, 8, 8, 12, 14, 4);
	    byte[] got = p.observed.clone();
	    MinesweeperSolver.deduce(got, p.w, p.h);
	    try {
		MinesweeperPuzzleGen.assertSound(p, got);
	    } catch (AssertionError e) {
		lied++;
		if(first == null)
		    first = "seed " + seed + "\n" + e.getMessage();
	    }
	}
	assertEquals(0, lied, first);
    }

    @Test
    void randomTinyBoardsStaySound() {
	int lied = 0;
	String first = null;
	for(long seed = 0; seed < 200; seed++) {
	    int w = 2 + (int) (seed % 3);
	    int h = 2 + (int) ((seed / 3) % 3);
	    int cells = w * h;
	    int mines = 1 + (int) (seed % Math.max(1, cells / 2));
	    if(mines >= cells)
		mines = cells - 1;
	    int opens = 1 + (int) ((seed / 5) % Math.max(1, cells - mines));
	    int openMines = (int) ((seed / 7) % (mines + 1));
	    MinesweeperPuzzleGen.Puzzle p = MinesweeperPuzzleGen.generate(seed, w, h, mines, opens, openMines);
	    byte[] got = p.observed.clone();
	    MinesweeperSolver.deduce(got, p.w, p.h);
	    try {
		MinesweeperPuzzleGen.assertSound(p, got);
	    } catch (AssertionError e) {
		lied++;
		if(first == null)
		    first = "seed " + seed + " " + w + "x" + h + "\n" + e.getMessage();
	    }
	}
	assertEquals(0, lied, first);
    }
}
