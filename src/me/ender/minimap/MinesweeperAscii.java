package me.ender.minimap;

import java.util.ArrayList;
import java.util.List;

/** Parse / format the glyph grids used as fake cave-dust datasets. */
public final class MinesweeperAscii {
    private MinesweeperAscii() {}

    public static final class Grid {
	public final int w, h;
	public final byte[] values;

	public Grid(int w, int h, byte[] values) {
	    this.w = w;
	    this.h = h;
	    this.values = values;
	}

	public String format() {
	    return MinesweeperScenarios.ascii(values, w, h);
	}

	public byte at(int x, int y) {
	    return values[x + y * w];
	}
    }

    public static Grid parse(String ascii) {
	if(ascii == null)
	    throw new IllegalArgumentException("ascii");
	List<String[]> rows = new ArrayList<>();
	for(String line : ascii.split("\n")) {
	    String t = line.trim();
	    if(t.isEmpty() || t.startsWith("#"))
		continue;
	    String[] tok = t.split("\\s+");
	    if(rows.size() > 0 && tok.length != rows.get(0).length)
		throw new IllegalArgumentException("ragged row: " + t);
	    rows.add(tok);
	}
	if(rows.isEmpty())
	    throw new IllegalArgumentException("empty grid");
	int h = rows.size();
	int w = rows.get(0).length;
	byte[] v = new byte[w * h];
	for(int y = 0; y < h; y++) {
	    for(int x = 0; x < w; x++)
		v[x + y * w] = parseGlyph(rows.get(y)[x]);
	}
	return new Grid(w, h, v);
    }

    public static byte parseGlyph(String tok) {
	if(tok == null || tok.isEmpty())
	    throw new IllegalArgumentException("empty glyph");
	if(tok.length() == 1)
	    return parseGlyph(tok.charAt(0));
	throw new IllegalArgumentException("bad glyph: " + tok);
    }

    public static byte parseGlyph(char ch) {
	if(ch == '.')
	    return 0;
	if(ch == 'S')
	    return Minesweeper.FLAG_SAFE;
	if(ch == 'D')
	    return Minesweeper.FLAG_DANGER;
	if(ch == 'M')
	    return Minesweeper.FLAG_MAYBE;
	if(ch == 'U')
	    return Minesweeper.FLAG_OPENED;
	if(ch >= '0' && ch <= '8')
	    return MinesweeperScenarios.counted(ch - '0');
	throw new IllegalArgumentException("bad glyph: " + ch);
    }

    public static Grid deduce(Grid g) {
	byte[] copy = g.values.clone();
	MinesweeperSolver.deduce(copy, g.w, g.h);
	return new Grid(g.w, g.h, copy);
    }
}
