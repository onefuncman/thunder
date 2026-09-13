package me.ender.minimap;

import java.util.Random;

/**
 * Builds fake cave-dust observations from a hidden mine map: open some
 * safe cells, write their neighbor-mine counts, leave the rest unmarked.
 */
public final class MinesweeperPuzzleGen {
    public static final class Puzzle {
	public final int w, h;
	public final boolean[] mine;
	public final byte[] observed;

	public Puzzle(int w, int h, boolean[] mine, byte[] observed) {
	    this.w = w;
	    this.h = h;
	    this.mine = mine;
	    this.observed = observed;
	}
    }

    private MinesweeperPuzzleGen() {}

    public static Puzzle generate(long seed, int w, int h, int mines, int opens) {
	return generate(seed, w, h, mines, opens, 0);
    }

    /**
     * @param openMines how many collapse tiles are already mined (opened with
     *                  their neighbor dust count). These are the cells that
     *                  used to make the DANGER rule over-count.
     */
    public static Puzzle generate(long seed, int w, int h, int mines, int opens, int openMines) {
	if((mines < 0) || (opens < 0) || (openMines < 0) || (mines + opens > w * h) || (openMines > mines))
	    throw new IllegalArgumentException("bad generate sizes");
	Random rng = new Random(seed);
	boolean[] mine = new boolean[w * h];
	int placed = 0;
	while(placed < mines) {
	    int i = rng.nextInt(w * h);
	    if(mine[i])
		continue;
	    mine[i] = true;
	    placed++;
	}
	boolean[] open = new boolean[w * h];
	int nopen = 0;
	int guard = 0;
	while((nopen < opens) && (guard++ < w * h * 40)) {
	    int i = rng.nextInt(w * h);
	    if(mine[i] || open[i])
		continue;
	    open[i] = true;
	    nopen++;
	}
	int nMineOpen = 0;
	guard = 0;
	while((nMineOpen < openMines) && (guard++ < w * h * 40)) {
	    int i = rng.nextInt(w * h);
	    if(!mine[i] || open[i])
		continue;
	    open[i] = true;
	    nMineOpen++;
	}
	byte[] obs = new byte[w * h];
	for(int i = 0; i < w * h; i++) {
	    if(!open[i])
		continue;
	    int x = i % w, y = i / w;
	    obs[i] = MinesweeperScenarios.counted(neighborMines(mine, w, h, x, y));
	}
	return new Puzzle(w, h, mine, obs);
    }

    public static int neighborMines(boolean[] mine, int w, int h, int x, int y) {
	int n = 0;
	for(int k = 0; k < 8; k++) {
	    int nx = x + MinesweeperSolver.DX[k], ny = y + MinesweeperSolver.DY[k];
	    if((nx < 0) || (ny < 0) || (nx >= w) || (ny >= h))
		continue;
	    if(mine[nx + ny * w])
		n++;
	}
	return n;
    }

    /**
     * Solver may leave cells unknown, but must never mark a mine safe or a
     * safe wall danger.
     */
    public static void assertSound(Puzzle p, byte[] deduced) {
	for(int i = 0; i < p.mine.length; i++) {
	    byte v = deduced[i];
	    if(MinesweeperSolver.isSafe(v) && p.mine[i])
		throw new AssertionError("SAFE on a mine at " + (i % p.w) + "," + (i / p.w)
		    + "\n" + MinesweeperScenarios.ascii(deduced, p.w, p.h));
	    if(MinesweeperSolver.isDanger(v) && !p.mine[i])
		throw new AssertionError("DANGER on a safe wall at " + (i % p.w) + "," + (i / p.w)
		    + "\n" + MinesweeperScenarios.ascii(deduced, p.w, p.h));
	}
    }
}
