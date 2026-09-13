package me.ender.minimap;

import haven.Coord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fake cave-dust layouts for overlay / solver checks. Coordinates are
 * relative to a stamp origin (the player tile in {@code dev.ms.load}).
 */
public final class MinesweeperScenarios {
    public static final int STRIDE = 8;

    public static final class Cell {
	public final int dx, dy;
	public final byte val;

	public Cell(int dx, int dy, byte val) {
	    this.dx = dx;
	    this.dy = dy;
	    this.val = val;
	}
    }

    public static final class Scenario {
	public final String name;
	public final String blurb;
	public final Cell[] cells;

	Scenario(String name, String blurb, Cell... cells) {
	    this.name = name;
	    this.blurb = blurb;
	    this.cells = cells;
	}
    }

    private static final Map<String, Scenario> BY_NAME = new LinkedHashMap<>();

    static {
	add(new Scenario("zero",
	    "confirmed 0-dust: all 8 neighbors should turn green",
	    c(0, 0, counted(0))));
	add(new Scenario("one",
	    "1-dust with one red: the other 7 neighbors should turn green",
	    c(0, 0, counted(1)),
	    c(1, 1, Minesweeper.FLAG_DANGER)));
	add(new Scenario("two",
	    "2-dust with no marks: ambiguous, neighbors stay unknown/orange",
	    c(0, 0, counted(2))));
	add(new Scenario("three-red",
	    "3-dust with 5 greens: the remaining 3 unknown should turn red",
	    c(0, 0, counted(3)),
	    c(0, -1, Minesweeper.FLAG_SAFE),
	    c(1, -1, Minesweeper.FLAG_SAFE),
	    c(1, 0, Minesweeper.FLAG_SAFE),
	    c(1, 1, Minesweeper.FLAG_SAFE),
	    c(0, 1, Minesweeper.FLAG_SAFE)));
	add(new Scenario("unconfirmed",
	    "mined but no dust yet: neighbors must NOT go green",
	    c(0, 0, Minesweeper.FLAG_OPENED)));
	add(new Scenario("maybe",
	    "0-dust with a manual maybe: other neighbors green, maybe stays purple",
	    c(0, 0, counted(0)),
	    c(1, 0, Minesweeper.FLAG_MAYBE)));
	add(new Scenario("chain",
	    "two 0s plus a 1: zeros green their rings, the 1 should pin the leftover red",
	    c(0, 0, counted(0)),
	    c(1, 0, counted(0)),
	    c(0, 1, counted(1))));
	add(new Scenario("mixed",
	    "small puzzle: 0, 1, and 2 sharing walls",
	    c(0, 0, counted(0)),
	    c(2, 0, counted(1)),
	    c(2, 2, counted(2)),
	    c(3, 1, Minesweeper.FLAG_DANGER)));
    }

    private MinesweeperScenarios() {}

    public static byte counted(int n) {
	return (byte) (Minesweeper.FLAG_OPENED | Minesweeper.FLAG_COUNTED | (n & Minesweeper.COUNT_MASK));
    }

    public static List<Scenario> all() {
	return Collections.unmodifiableList(new ArrayList<>(BY_NAME.values()));
    }

    public static Scenario get(String name) {
	if(name == null)
	    return null;
	return BY_NAME.get(name.toLowerCase());
    }

    public static Coord offsetOf(int index) {
	return Coord.of((index % 3) * STRIDE, (index / 3) * STRIDE);
    }

    public static int paint(byte[] grid, int w, int h, int ox, int oy, Cell[] cells) {
	if((grid == null) || (cells == null) || (grid.length < (w * h)))
	    return 0;
	int n = 0;
	for(Cell c : cells) {
	    int x = ox + c.dx, y = oy + c.dy;
	    if((x < 0) || (y < 0) || (x >= w) || (y >= h))
		continue;
	    grid[x + y * w] = c.val;
	    n++;
	}
	return n;
    }

    public static String ascii(byte[] grid, int w, int h) {
	StringBuilder sb = new StringBuilder();
	for(int y = 0; y < h; y++) {
	    for(int x = 0; x < w; x++) {
		if(x > 0)
		    sb.append(' ');
		sb.append(glyph(grid[x + y * w]));
	    }
	    sb.append('\n');
	}
	return sb.toString();
    }

    public static char glyph(byte v) {
	if(MinesweeperSolver.isOpened(v) && (v & Minesweeper.FLAG_COUNTED) == 0)
	    return 'U';
	if(MinesweeperSolver.isOpened(v) || ((v & Minesweeper.FLAG_COUNTED) != 0))
	    return (char) ('0' + MinesweeperSolver.countOf(v));
	if(MinesweeperSolver.isSafe(v))
	    return 'S';
	if(MinesweeperSolver.isDanger(v))
	    return 'D';
	if(MinesweeperSolver.isMaybe(v))
	    return 'M';
	return '.';
    }

    private static Cell c(int dx, int dy, byte val) {
	return new Cell(dx, dy, val);
    }

    private static void add(Scenario s) {
	BY_NAME.put(s.name, s);
    }
}
