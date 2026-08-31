package me.ender.minimap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MinesweeperScenariosTest {
    private static final int W = 7, H = 7, OX = 3, OY = 3;

    private static byte[] stamp(String name) {
	MinesweeperScenarios.Scenario s = MinesweeperScenarios.get(name);
	assertNotNull(s, name);
	byte[] g = new byte[W * H];
	MinesweeperScenarios.paint(g, W, H, OX, OY, s.cells);
	MinesweeperSolver.deduce(g, W, H);
	return g;
    }

    private static int i(int dx, int dy) {
	return (OX + dx) + (OY + dy) * W;
    }

    @Test
    void listsEveryNamedScenario() {
	assertEquals(8, MinesweeperScenarios.all().size());
	assertNotNull(MinesweeperScenarios.get("zero"));
	assertNull(MinesweeperScenarios.get("nope"));
    }

    @Test
    void zeroGreensAllEightNeighbors() {
	byte[] g = stamp("zero");
	for(int dy = -1; dy <= 1; dy++) {
	    for(int dx = -1; dx <= 1; dx++) {
		if((dx == 0) && (dy == 0))
		    continue;
		assertEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[i(dx, dy)]),
		    dx + "," + dy);
	    }
	}
    }

    @Test
    void oneKeepsThePlacedDangerAndGreensTheRest() {
	byte[] g = stamp("one");
	assertEquals(Minesweeper.FLAG_DANGER, MinesweeperSolver.flagsOf(g[i(1, 1)]));
	assertEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[i(-1, -1)]));
    }

    @Test
    void twoStaysAmbiguous() {
	byte[] g = stamp("two");
	assertEquals(0, MinesweeperSolver.flagsOf(g[i(1, 0)]));
	assertEquals(0, MinesweeperSolver.flagsOf(g[i(-1, 1)]));
    }

    @Test
    void threeRedMarksTheThreeUnknownDanger() {
	byte[] g = stamp("three-red");
	assertEquals(Minesweeper.FLAG_DANGER, MinesweeperSolver.flagsOf(g[i(-1, 0)]));
	assertEquals(Minesweeper.FLAG_DANGER, MinesweeperSolver.flagsOf(g[i(-1, -1)]));
	assertEquals(Minesweeper.FLAG_DANGER, MinesweeperSolver.flagsOf(g[i(-1, 1)]));
	assertEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[i(1, 0)]));
    }

    @Test
    void unconfirmedZeroDoesNotGreenNeighbors() {
	byte[] g = stamp("unconfirmed");
	assertEquals(0, MinesweeperSolver.flagsOf(g[i(1, 0)]));
	assertTrue(MinesweeperSolver.isOpened(g[i(0, 0)]));
    }

    @Test
    void maybeIsLeftInPlace() {
	byte[] g = stamp("maybe");
	assertEquals(Minesweeper.FLAG_MAYBE, MinesweeperSolver.flagsOf(g[i(1, 0)]));
	assertEquals(Minesweeper.FLAG_SAFE, MinesweeperSolver.flagsOf(g[i(-1, 0)]));
    }

    @Test
    void asciiGlyphs() {
	assertEquals('0', MinesweeperScenarios.glyph(MinesweeperScenarios.counted(0)));
	assertEquals('U', MinesweeperScenarios.glyph(Minesweeper.FLAG_OPENED));
	assertEquals('S', MinesweeperScenarios.glyph(Minesweeper.FLAG_SAFE));
	assertEquals('D', MinesweeperScenarios.glyph(Minesweeper.FLAG_DANGER));
	assertEquals('.', MinesweeperScenarios.glyph((byte) 0));
    }

    @Test
    void offsetOfLaysOutAGrid() {
	assertEquals(0, MinesweeperScenarios.offsetOf(0).x);
	assertEquals(MinesweeperScenarios.STRIDE, MinesweeperScenarios.offsetOf(1).x);
	assertEquals(MinesweeperScenarios.STRIDE, MinesweeperScenarios.offsetOf(3).y);
    }
}
