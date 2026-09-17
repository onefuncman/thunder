package me.ender.ui;

import haven.CFG;
import haven.Coord;
import haven.GOut;
import haven.UI;
import haven.Widget;

import java.awt.Color;

/* Compact 5x5 grid of clickable cells for picking one of 25 screen-anchor
 * positions. Index = row * COLS + col, row/col 0..COLS-1/0..ROWS-1 running
 * top/left to bottom/right. */
public class ScreenPosGrid extends Widget {
    public static final int COLS = 5, ROWS = 5;
    private static final Color BORDER = new Color(255, 255, 255, 80);
    private static final Color CELL = new Color(255, 255, 255, 50);
    private static final Color SEL = new Color(255, 180, 0, 230);

    private final int cell, gap;
    private final CFG<Integer> cfg;
    private int sel;

    public ScreenPosGrid(int cell, int gap, CFG<Integer> cfg) {
	this.cell = cell;
	this.gap = gap;
	this.cfg = cfg;
	this.sel = clip(cfg.get());
	resize(COLS * cell + (COLS - 1) * gap, ROWS * cell + (ROWS - 1) * gap);
	settip("Where on screen action-failure messages (e.g. \"Too hard to mine\") appear", true);
    }

    private static int clip(int idx) {
	return (idx < 0 || idx >= COLS * ROWS) ? (COLS * (ROWS - 1)) : idx;
    }

    private Coord cellpos(int col, int row) {
	return new Coord(col * (cell + gap), row * (cell + gap));
    }

    public void draw(GOut g) {
	g.chcolor(BORDER);
	g.rect(Coord.z, sz);
	g.chcolor();
	for(int row = 0; row < ROWS; row++) {
	    for(int col = 0; col < COLS; col++) {
		int idx = row * COLS + col;
		g.chcolor(idx == sel ? SEL : CELL);
		g.frect(cellpos(col, row), new Coord(cell, cell));
	    }
	}
	g.chcolor();
    }

    public boolean mousedown(Widget.MouseDownEvent ev) {
	if(ev.b != 1)
	    return(false);
	int col = ev.c.x / (cell + gap);
	int row = ev.c.y / (cell + gap);
	if((col < 0) || (col >= COLS) || (row < 0) || (row >= ROWS))
	    return(false);
	int idx = row * COLS + col;
	if(idx != sel) {
	    sel = idx;
	    cfg.set(idx);
	}
	return(true);
    }
}
