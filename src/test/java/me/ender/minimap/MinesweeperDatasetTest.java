package me.ender.minimap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless cave-dust datasets: named ASCII boards plus generated puzzles.
 * Glyphs: {@code 0-8} opened count, {@code S} green, {@code D} red,
 * {@code M} maybe, {@code U} unconfirmed open, {@code .} unknown.
 */
public class MinesweeperDatasetTest {

    private static void assertBoard(String name, String before, String after) {
	MinesweeperAscii.Grid in = MinesweeperAscii.parse(before);
	MinesweeperAscii.Grid got = MinesweeperAscii.deduce(in);
	MinesweeperAscii.Grid want = MinesweeperAscii.parse(after);
	assertEquals(want.w, got.w, name + " width");
	assertEquals(want.h, got.h, name + " height");
	assertEquals(want.format(), got.format(), name);
    }

    @Test
    void zeroDustGreensEveryNeighbor() {
	assertBoard("zero",
	    ". . .\n. 0 .\n. . .\n",
	    "S S S\nS 0 S\nS S S\n");
    }

    @Test
    void oneDustWithKnownDangerGreensTheRest() {
	assertBoard("one",
	    ". . .\n. 1 .\n. . D\n",
	    "S S S\nS 1 S\nS S D\n");
    }

    @Test
    void twoDustStaysAmbiguous() {
	assertBoard("two",
	    ". . .\n. 2 .\n. . .\n",
	    ". . .\n. 2 .\n. . .\n");
    }

    @Test
    void threeDustWithFiveSafeMarksTheRestDanger() {
	assertBoard("three-red",
	    ". S S\n. 3 S\n. S S\n",
	    "D S S\nD 3 S\nD S S\n");
    }

    @Test
    void unconfirmedOpenDoesNotGreenNeighbors() {
	assertBoard("unconfirmed",
	    ". . .\n. U .\n. . .\n",
	    ". . .\n. U .\n. . .\n");
    }

    @Test
    void maybeIsPreservedOnAZero() {
	assertBoard("maybe",
	    ". . .\n. 0 M\n. . .\n",
	    "S S S\nS 0 M\nS S S\n");
    }

    @Test
    void chainZeroThenOnePinsLeftoverDanger() {
	assertBoard("chain",
	    "0 . D\n. 1 .\n. . 0\n",
	    "0 S D\nS 1 S\nS S 0\n");
    }

    @Test
    void mixedPuzzleKeepsPlacedDanger() {
	assertBoard("mixed",
	    "0 . 1 .\n. . . D\n. . 2 .\n",
	    "0 S 1 S\nS S S D\n. . 2 .\n");
    }

    @ParameterizedTest(name = "generated seed={0}")
    @CsvSource({
	"1, 9, 9, 10, 12",
	"2, 9, 9, 10, 12",
	"3, 7, 7, 8, 10",
	"4, 11, 11, 14, 16",
	"5, 5, 5, 4, 6"
    })
    void generatedPuzzlesNeverLie(long seed, int w, int h, int mines, int opens) {
	MinesweeperPuzzleGen.Puzzle p = MinesweeperPuzzleGen.generate(seed, w, h, mines, opens);
	byte[] got = p.observed.clone();
	MinesweeperSolver.deduce(got, p.w, p.h);
	MinesweeperPuzzleGen.assertSound(p, got);
    }

    @Test
    void manyRandomPuzzlesStaySound() {
	int lied = 0;
	String first = null;
	for(long seed = 100; seed < 400; seed++) {
	    MinesweeperPuzzleGen.Puzzle p = MinesweeperPuzzleGen.generate(seed, 9, 9, 12, 15);
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
    void parseRoundTrip() {
	String src = "S 0 D\nM U 2\n";
	assertEquals("S 0 D\nM U 2\n", MinesweeperAscii.parse(src).format());
    }
}
