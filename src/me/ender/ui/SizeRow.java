package me.ender.ui;

import haven.CFG;
import haven.Coord;
import haven.GOut;
import haven.Text;
import haven.UI;
import haven.Widget;

import java.awt.Color;

/* Compact, wrapped grid of fixed text-size choices for the transient notice
 * text (e.g. "Too hard to mine"). */
public class SizeRow extends Widget {
    public static final int[] SIZES = {10, 12, 14, 16, 18, 20, 24, 28, 32, 36, 40, 48, 60, 72, 96};
    private static final int PER_ROW = 6;
    private static final Color BG = new Color(255, 255, 255, 50);
    private static final Color SEL = new Color(255, 180, 0, 230);
    private static final Text.Foundry lblfnt = new Text.Foundry(Text.sans, UI.scale(11));

    private final CFG<Integer> cfg;
    private final Text[] labels = new Text[SIZES.length];
    private final int btnw, btnh, gap;
    private int sel;

    public SizeRow(CFG<Integer> cfg) {
	this.cfg = cfg;
	this.sel = clip(cfg.get());
	int h = 0;
	for(int i = 0; i < SIZES.length; i++) {
	    labels[i] = lblfnt.render(Integer.toString(SIZES[i]));
	    h = Math.max(h, labels[i].sz().y);
	}
	gap = UI.scale(2);
	btnw = UI.scale(26);
	btnh = h + UI.scale(6);
	int cols = Math.min(PER_ROW, SIZES.length);
	int rows = (SIZES.length + PER_ROW - 1) / PER_ROW;
	resize(cols * (btnw + gap) - gap, rows * (btnh + gap) - gap);
	settip("Text size for action-failure messages (e.g. \"Too hard to mine\")", true);
    }

    private static int clip(int idx) {
	return (idx < 0 || idx >= SIZES.length) ? 2 : idx;
    }

    private Coord btnpos(int i) {
	return new Coord((i % PER_ROW) * (btnw + gap), (i / PER_ROW) * (btnh + gap));
    }

    public void draw(GOut g) {
	for(int i = 0; i < SIZES.length; i++) {
	    Coord c = btnpos(i);
	    g.chcolor(i == sel ? SEL : BG);
	    g.frect(c, new Coord(btnw, btnh));
	    g.chcolor();
	    Coord tsz = labels[i].sz();
	    g.image(labels[i].tex(), c.add((btnw - tsz.x) / 2, (btnh - tsz.y) / 2));
	}
    }

    public boolean mousedown(Widget.MouseDownEvent ev) {
	if(ev.b != 1)
	    return(false);
	int col = ev.c.x / (btnw + gap);
	int row = ev.c.y / (btnh + gap);
	if((col < 0) || (col >= PER_ROW) || (row < 0))
	    return(false);
	int i = row * PER_ROW + col;
	if((i < 0) || (i >= SIZES.length))
	    return(false);
	if(i != sel) {
	    sel = i;
	    cfg.set(i);
	}
	return(true);
    }
}
