package haven.proto;

import haven.*;
import java.awt.Color;
import java.util.*;

public class WidgetTreePanel extends Widget {
    private static final Text.Foundry fnd = new Text.Foundry(Text.mono, 10);
    private static final int LINEH = fnd.height() + UI.scale(2);
    private final List<TreeEntry> entries = new ArrayList<>();
    private final Scrollbar sb;
    private int selected = -1;

    private static class TreeEntry {
	final int depth;
	final int widgetId;
	final String className;
	final Coord pos;
	final Coord size;
	final boolean visible;

	TreeEntry(int depth, int widgetId, String className, Coord pos, Coord size, boolean visible) {
	    this.depth = depth;
	    this.widgetId = widgetId;
	    this.className = className;
	    this.pos = pos;
	    this.size = size;
	    this.visible = visible;
	}
    }

    public WidgetTreePanel(Coord sz) {
	super(sz);
	add(new Button(UI.scale(80), "Refresh") {
	    public void click() { refresh(); }
	}, 0, 0);
	sb = adda(new Scrollbar(sz.y - UI.scale(25), 0, 0), sz.x - UI.scale(1), UI.scale(25), 1, 0);
    }

    public void refresh() {
	entries.clear();
	if(ui != null && ui.root != null)
	    walk(ui.root, 0);
	sb.max(Math.max(0, entries.size() - visibleLines()));
    }

    private void walk(Widget w, int depth) {
	int wid = ui.widgetid(w);
	entries.add(new TreeEntry(depth, wid, w.getClass().getSimpleName(), w.c, w.sz, w.visible));
	for(Widget ch = w.child; ch != null; ch = ch.next) {
	    walk(ch, depth + 1);
	}
    }

    private int visibleLines() {
	return (sz.y - UI.scale(25)) / LINEH;
    }

    @Override
    public void draw(GOut g) {
	g.chcolor(new Color(15, 15, 15, 220));
	g.frect(new Coord(0, UI.scale(25)), new Coord(sz.x, sz.y - UI.scale(25)));
	g.chcolor();

	int y = UI.scale(27);
	int vis = visibleLines();
	for(int i = 0; i < vis && (i + sb.val) < entries.size(); i++) {
	    int idx = i + sb.val;
	    TreeEntry e = entries.get(idx);
	    if(idx == selected) {
		g.chcolor(new Color(60, 60, 100, 150));
		g.frect(new Coord(0, y), new Coord(sz.x, LINEH));
	    }
	    int indent = e.depth * UI.scale(12);
	    String idStr = e.widgetId >= 0 ? "#" + e.widgetId : "  ";
	    g.chcolor(new Color(140, 140, 140));
	    g.text(String.format("%5s", idStr), new Coord(UI.scale(4) + indent, y));
	    g.chcolor(e.visible ? Color.WHITE : new Color(100, 100, 100));
	    String info = String.format(" %s (%d,%d) %dx%d", e.className, e.pos.x, e.pos.y, e.size.x, e.size.y);
	    g.text(info, new Coord(UI.scale(40) + indent, y));
	    g.chcolor();
	    y += LINEH;
	}
	super.draw(g);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
	if(ev.propagate(this))
	    return true;
	int idx = (ev.c.y - UI.scale(25)) / LINEH + sb.val;
	if(idx >= 0 && idx < entries.size())
	    selected = idx;
	return true;
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
	sb.ch(ev.a);
	return true;
    }

    @Override
    public void resize(Coord sz) {
	super.resize(sz);
	sb.resize(sz.y - UI.scale(25));
	sb.c = new Coord(sz.x - sb.sz.x, UI.scale(25));
    }
}
