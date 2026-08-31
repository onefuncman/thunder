package me.ender.minimap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MinesweeperSolverTest {
    private static final int W = 5, H = 5;

    private static int i(int x, int y) {return x + y * W;}

    private static byte[] grid() {return new byte[W * H];}

    private static byte opened(int count) {
	return (byte) (Minesweeper.FLAG_OPENED | Minesweeper.FLAG_COUNTED | (count & Minesweeper.COUNT_MASK));
    }

    @Test
    void zeroDustMarksAllEightNeighborsSafe() {
	byte[] g = grid();
	g[i(2, 2)] = opened(0);
	assertTrue(MinesweeperSolver.deduce(g, W, H));
	for(int dy = -1; dy <= 1; dy++) {
	    for(int dx = -1; dx <= 1; dx++) {
		if((dx == 0) && (dy == 0))
		    continue;
		assertEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[i(2 + dx, 2 + dy)]));
	    }
	}
    }

    @Test
    void oneDustWithSevenSafeMarksTheLastDanger() {
	byte[] g = grid();
	g[i(2, 2)] = opened(1);
	int danger = i(3, 3);
	for(int dy = -1; dy <= 1; dy++) {
	    for(int dx = -1; dx <= 1; dx++) {
		if((dx == 0) && (dy == 0))
		    continue;
		int idx = i(2 + dx, 2 + dy);
		if(idx != danger)
		    g[idx] = Minesweeper.FLAG_SAFE;
	    }
	}
	assertTrue(MinesweeperSolver.deduce(g, W, H));
	assertEquals(Minesweeper.FLAG_DANGER, MinesweeperSolver.flagsOf(g[danger]));
    }

    @Test
    void oneDustWithOneDangerMarksTheRestSafe() {
	byte[] g = grid();
	g[i(2, 2)] = opened(1);
	g[i(3, 3)] = Minesweeper.FLAG_DANGER;
	assertTrue(MinesweeperSolver.deduce(g, W, H));
	for(int dy = -1; dy <= 1; dy++) {
	    for(int dx = -1; dx <= 1; dx++) {
		if((dx == 0) && (dy == 0))
		    continue;
		int idx = i(2 + dx, 2 + dy);
		if(idx == i(3, 3))
		    continue;
		assertEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[idx]),
		    "neighbor " + (2 + dx) + "," + (2 + dy));
	    }
	}
    }

    @Test
    void doesNotOverwriteMaybe() {
	byte[] g = grid();
	g[i(2, 2)] = opened(0);
	g[i(3, 2)] = Minesweeper.FLAG_MAYBE;
	assertTrue(MinesweeperSolver.deduce(g, W, H));
	assertEquals(Minesweeper.FLAG_MAYBE, MinesweeperSolver.flagsOf(g[i(3, 2)]));
    }

    @Test
    void openedNeighborsAreNotCountedAsMines() {
	byte[] g = grid();
	g[i(2, 2)] = opened(0);
	g[i(3, 2)] = opened(1);
	assertTrue(MinesweeperSolver.deduce(g, W, H));
	assertTrue(MinesweeperSolver.isOpened(g[i(3, 2)]));
	assertNotEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[i(3, 2)]));
    }

    @Test
    void existingDustCountWithoutOpenedFlagStillHints() {
	byte[] g = grid();
	g[i(2, 2)] = 1;
	g[i(3, 3)] = Minesweeper.FLAG_DANGER;
	assertTrue(MinesweeperSolver.deduce(g, W, H));
	assertEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[i(1, 1)]));
    }

    @Test
    void noChangeWhenAmbiguous() {
	byte[] g = grid();
	g[i(2, 2)] = opened(2);
	assertFalse(MinesweeperSolver.deduce(g, W, H));
    }

    @Test
    void edgeTileZeroDustOnlyMarksInBoundsNeighbors() {
	byte[] g = grid();
	g[i(0, 0)] = opened(0);
	assertTrue(MinesweeperSolver.deduce(g, W, H));
	assertEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[i(1, 0)]));
	assertEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[i(0, 1)]));
	assertEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[i(1, 1)]));
	assertEquals(0, g[i(2, 2)]);
    }

    @Test
    void dustAfterFalseZeroRetractsSafeNeighbors() {
	byte[] g = grid();
	g[i(2, 2)] = opened(0);
	assertTrue(MinesweeperSolver.deduce(g, W, H));
	assertEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[i(3, 3)]));
	g[i(2, 2)] = opened(3);
	MinesweeperSolver.retractSafeNeighbors(g, W, H, 2, 2);
	assertEquals(0, MinesweeperSolver.flagsOf(g[i(3, 3)]));
	assertFalse(MinesweeperSolver.deduce(g, W, H));
    }

    @Test
    void unconfirmedZeroDoesNotMarkNeighbors() {
	byte[] g = grid();
	g[i(2, 2)] = Minesweeper.FLAG_OPENED;
	assertFalse(MinesweeperSolver.deduce(g, W, H));
	assertEquals(0, MinesweeperSolver.flagsOf(g[i(3, 3)]));
    }

    @Test
    void unmarkedNeighborOfHintIsUnknownFrontier() {
	byte[] g = grid();
	g[i(2, 2)] = opened(2);
	assertTrue(MinesweeperSolver.isUnknownFrontier(g, W, H, 3, 3));
	assertFalse(MinesweeperSolver.isUnknownFrontier(g, W, H, 0, 0));
	g[i(3, 3)] = Minesweeper.FLAG_SAFE;
	assertFalse(MinesweeperSolver.isUnknownFrontier(g, W, H, 3, 3));
    }
}
