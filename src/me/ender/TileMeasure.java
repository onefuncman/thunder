package me.ender;

import haven.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static haven.MCache.tilesz;

/**
 * Click-to-mark tile measuring stick. Marks stay drawn until cleared.
 */
public class TileMeasure {
    private static Text.Foundry labelFoundry;
    private static final Color HUD = new Color(220, 235, 255);
    private static final String HUD_HINT = "Measure: click tiles  ·  Alt new  ·  Ctrl undo  ·  Shift clear  ·  right-click done";

    private final List<List<Coord>> measurements = new ArrayList<>();
    private final Map<String, Tex> labels = new HashMap<>();
    private Coord hover;

    public static boolean paginaAction(OwnerContext ctx, MenuGrid.Interaction iact) {
	UI ui = ctx.context(UI.class);
	if(ui == null || ui.gui == null || ui.gui.map == null)
	    return false;
	GameUI gui = ui.gui;
	if(iact != null && iact.modflags == UI.MOD_SHIFT) {
	    gui.tileMeasure.clear();
	    gui.msg("Measurements cleared", GameUI.MsgType.INFO);
	    return true;
	}
	if(iact != null && iact.modflags == UI.MOD_CTRL) {
	    gui.tileMeasure.undo();
	    gui.msg("Last measurement mark undone", GameUI.MsgType.INFO);
	    return true;
	}
	CustomCursors.toggleMeasureMode(gui.map);
	return true;
    }

    public static boolean isActive() {
	return CustomCursors.isMeasuring();
    }

    public static void paint(GOut g, MapView mv) {
	if(mv == null || mv.ui == null || mv.ui.gui == null)
	    return;
	mv.ui.gui.tileMeasure.paintOn(g, mv);
    }

    public void mark(Coord tc) {
	mark(tc, false);
    }

    public void mark(Coord tc, boolean detached) {
	if(tc == null)
	    return;
	Coord copy = Coord.of(tc);
	synchronized(this) {
	    List<Coord> active;
	    if(detached || measurements.isEmpty()) {
		active = new ArrayList<>();
		measurements.add(active);
	    } else {
		active = measurements.get(measurements.size() - 1);
	    }
	    if(!active.isEmpty() && active.get(active.size() - 1).equals(copy))
		return;
	    active.add(copy);
	}
    }

    public void undo() {
	synchronized(this) {
	    if(measurements.isEmpty())
		return;
	    List<Coord> active = measurements.get(measurements.size() - 1);
	    if(!active.isEmpty())
		active.remove(active.size() - 1);
	    if(active.isEmpty())
		measurements.remove(measurements.size() - 1);
	}
    }

    public void clear() {
	synchronized(this) {
	    measurements.clear();
	    hover = null;
	}
    }

    public void setHover(Coord tc) {
	synchronized(this) {
	    hover = tc == null ? null : Coord.of(tc);
	}
    }

    public String hoverTip() {
	return hoverTip(false);
    }

    public String hoverTip(boolean detached) {
	Coord last, h;
	synchronized(this) {
	    last = lastMark();
	    h = hover;
	}
	if(h == null)
	    return last == null ? "Click a tile to start measuring" : (detached ? "Alt-click to start a new measurement" : "Click another tile");
	if(last == null || detached)
	    return "Start: " + h.x + ", " + h.y;
	return formatSegment(last, h);
    }

    private Coord lastMark() {
	if(measurements.isEmpty())
	    return null;
	List<Coord> active = measurements.get(measurements.size() - 1);
	return active.isEmpty() ? null : active.get(active.size() - 1);
    }

    List<List<Coord>> measurementSnapshot() {
	synchronized(this) {
	    List<List<Coord>> copy = new ArrayList<>(measurements.size());
	    for(List<Coord> measurement : measurements)
		copy.add(new ArrayList<>(measurement));
	    return copy;
	}
    }

    public static int chebyshev(Coord a, Coord b) {
	return Math.max(Math.abs(b.x - a.x), Math.abs(b.y - a.y));
    }

    public static int manhattan(Coord a, Coord b) {
	return Math.abs(b.x - a.x) + Math.abs(b.y - a.y);
    }

    public static double euclidean(Coord a, Coord b) {
	return Math.hypot(b.x - a.x, b.y - a.y);
    }

    public static int chebyshevPath(List<Coord> tiles) {
	if(tiles == null || tiles.size() < 2)
	    return 0;
	int sum = 0;
	for(int i = 1; i < tiles.size(); i++)
	    sum += chebyshev(tiles.get(i - 1), tiles.get(i));
	return sum;
    }

    public static String formatSegment(Coord a, Coord b) {
	int dx = b.x - a.x;
	int dy = b.y - a.y;
	int cheb = Math.max(Math.abs(dx), Math.abs(dy));
	if(dx == 0 && dy == 0)
	    return "0 tiles";
	double euc = Math.hypot(dx, dy);
	String delta = String.format("Δ%+d,%+d", dx, dy);
	if(euc == cheb)
	    return String.format(Locale.US, "%d tiles  %s", cheb, delta);
	return String.format(Locale.US, "%d tiles  %s  (%.1f)", cheb, delta, euc);
    }

    public static String formatTotal(List<Coord> tiles) {
	int n = tiles == null ? 0 : tiles.size();
	if(n < 2)
	    return null;
	int steps = chebyshevPath(tiles);
	if(n == 2)
	    return formatSegment(tiles.get(0), tiles.get(1));
	return String.format(Locale.US, "total %d tiles  (%d marks)", steps, n);
    }

    private static Text.Foundry fnd() {
	if(labelFoundry == null)
	    labelFoundry = new Text.Foundry(Text.sansbold, 12);
	return labelFoundry;
    }

    private void paintOn(GOut g, MapView mv) {
	List<List<Coord>> paths;
	Coord h;
	synchronized(this) {
	    paths = measurementSnapshot();
	    h = hover;
	}
	if(paths.isEmpty() && h == null && !CustomCursors.isMeasuring())
	    return;
	Color line = CFG.COLOR_MEASURE_LINE.get();
	Color preview = CFG.COLOR_MEASURE_PREVIEW.get();
	double width = Math.max(1, Math.min(8, CFG.MEASURE_LINE_WIDTH.get()));

	if(CustomCursors.isMeasuring()) {
	    g.chcolor(HUD);
	    g.atext(HUD_HINT, new Coord(12, 12), 0, 0);
	    g.chcolor();
	}

	for(List<Coord> pts : paths) {
	    for(int i = 0; i < pts.size(); i++) {
		Coord tc = pts.get(i);
		outlineTile(g, mv, tc, line, fillColor(line), true, width);
		if(i > 0) {
		    Coord prev = pts.get(i - 1);
		    drawWorldLine(g, mv, tileCenter(prev), tileCenter(tc), line, width);
		    drawLabel(g, mv, mid(prev, tc), formatSegment(prev, tc));
		}
	    }
	    if(pts.size() >= 3) {
		String total = formatTotal(pts);
		if(total != null)
		    drawLabel(g, mv, tileCenter(pts.get(pts.size() - 1)).add(0, tilesz.y), total);
	    }
	}

	if(CustomCursors.isMeasuring() && h != null) {
	    boolean detached = mv.ui.modflags() == UI.MOD_META;
	    outlineTile(g, mv, h, preview, null, false, width);
	    if(!detached && !paths.isEmpty()) {
		List<Coord> active = paths.get(paths.size() - 1);
		Coord last = active.isEmpty() ? null : active.get(active.size() - 1);
		if(last != null && !last.equals(h)) {
		    drawWorldLine(g, mv, tileCenter(last), tileCenter(h), preview, width);
		    drawLabel(g, mv, mid(last, h), formatSegment(last, h));
		}
	    }
	}
    }

    private static Color fillColor(Color line) {
	return new Color(line.getRed(), line.getGreen(), line.getBlue(), Math.min(64, line.getAlpha() / 4));
    }

    private void outlineTile(GOut g, MapView mv, Coord tc, Color line, Color fill, boolean cross, double width) {
	Coord2d ul = tc.mul(tilesz);
	Coord a = screen(mv, ul);
	Coord b = screen(mv, ul.add(tilesz.x, 0));
	Coord c = screen(mv, ul.add(tilesz.x, tilesz.y));
	Coord d = screen(mv, ul.add(0, tilesz.y));
	if(a == null && b == null && c == null && d == null)
	    return;
	if(fill != null) {
	    Coord sc = screen(mv, tileCenter(tc));
	    if(sc != null) {
		g.chcolor(fill);
		int r = UI.scale(8);
		g.fellipse(sc, Coord.of(r, r));
		g.chcolor();
	    }
	}
	g.chcolor(line);
	if(a != null && b != null) g.line(a, b, width);
	if(b != null && c != null) g.line(b, c, width);
	if(c != null && d != null) g.line(c, d, width);
	if(d != null && a != null) g.line(d, a, width);
	if(cross) {
	    Coord sc = screen(mv, tileCenter(tc));
	    if(sc != null) {
		int r = UI.scale(5);
		g.line(sc.add(-r, 0), sc.add(r, 0), width);
		g.line(sc.add(0, -r), sc.add(0, r), width);
	    }
	}
	g.chcolor();
    }

    private void drawWorldLine(GOut g, MapView mv, Coord2d a, Coord2d b, Color color, double width) {
	Coord sa = screen(mv, a);
	Coord sb = screen(mv, b);
	if(sa == null || sb == null)
	    return;
	g.chcolor(color);
	g.line(sa, sb, width);
	g.chcolor();
    }

    private void drawLabel(GOut g, MapView mv, Coord2d world, String text) {
	Coord sc = screen(mv, world);
	if(sc == null || text == null)
	    return;
	g.aimage(labelTex(text), sc, 0.5, 1.1);
    }

    private Tex labelTex(String text) {
	Color color = CFG.COLOR_MEASURE_LABEL.get();
	String key = color.getRGB() + ":" + text;
	Tex tex = labels.get(key);
	if(tex == null) {
	    tex = Text.renderstroked(text, color, Color.BLACK, fnd()).tex();
	    labels.put(key, tex);
	}
	return tex;
    }

    private static Coord2d tileCenter(Coord tc) {
	return tc.mul(tilesz).add(tilesz.div(2));
    }

    private static Coord2d mid(Coord a, Coord b) {
	return tileCenter(a).add(tileCenter(b)).div(2);
    }

    private static Coord screen(MapView mv, Coord2d world) {
	if(mv == null || world == null)
	    return null;
	Coord3f s;
	try {
	    s = mv.screenxf(mv.glob.map.getzp(world));
	} catch(RuntimeException e) {
	    s = mv.screenxf(world);
	}
	if(s == null)
	    return null;
	Coord c = Coord.of(Math.round(s.x), Math.round(s.y));
	if(c.x < -40 || c.y < -40 || c.x > mv.sz.x + 40 || c.y > mv.sz.y + 40)
	    return null;
	return c;
    }
}
