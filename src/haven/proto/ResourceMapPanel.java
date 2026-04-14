package haven.proto;

import haven.*;
import java.awt.Color;
import java.util.*;

public class ResourceMapPanel extends Widget {
    private static final Text.Foundry fnd = new Text.Foundry(Text.mono, 10);
    private static final int LINEH = fnd.height() + UI.scale(2);
    private static final int HEADER = UI.scale(25);
    private final List<ResEntry> entries = new ArrayList<>();
    private final Scrollbar sb;
    private final TextEntry filterEntry;
    private int selected = -1;
    private long lastClickTime = 0;
    private int lastClickIdx = -1;
    private long flashUntil = 0;

    private static class ResEntry {
	final int id;
	final String name;
	final int version;

	ResEntry(int id, String name, int version) {
	    this.id = id;
	    this.name = name;
	    this.version = version;
	}
    }

    public ResourceMapPanel(Coord sz) {
	super(sz);
	int x = 0;
	add(new Button(UI.scale(70), "Refresh") {
	    public void click() { refresh(); }
	}, x, 0);
	x += UI.scale(78);
	add(new Label("Filter:"), x, UI.scale(2));
	x += UI.scale(40);
	filterEntry = add(new TextEntry(UI.scale(150), "") {
	    @Override
	    public void changed(ReadLine buf) {
		super.changed(buf);
		applyFilter();
	    }
	}, x, 0);
	x += UI.scale(158);
	add(new Button(UI.scale(70), "Copy All") { public void click() { copyAll(); } }, x, 0);
	x += UI.scale(74);
	add(new Button(UI.scale(90), "Copy Selected") { public void click() { copySelected(); } }, x, 0);
	sb = adda(new Scrollbar(sz.y - HEADER, 0, 0), sz.x - UI.scale(1), HEADER, 1, 0);
    }

    private static String formatEntry(ResEntry e) {
	return e.id + "\t" + e.name + (e.version >= 0 ? "\tv" + e.version : "");
    }

    private void copyAll() {
	StringBuilder sb2 = new StringBuilder();
	for(ResEntry e : filtered) sb2.append(formatEntry(e)).append('\n');
	ClipboardUtil.copy(sb2.toString());
	flashUntil = System.currentTimeMillis() + 400;
	selected = -2;
    }

    private void copySelected() {
	if(selected >= 0 && selected < filtered.size()) {
	    ClipboardUtil.copy(formatEntry(filtered.get(selected)));
	    flashUntil = System.currentTimeMillis() + 400;
	}
    }

    public void refresh() {
	entries.clear();
	if(ui != null && ui.sess != null) {
	    Map<Integer, String> snapshot = ui.sess.getResCacheSnapshot();
	    for(Map.Entry<Integer, String> e : snapshot.entrySet()) {
		String val = e.getValue();
		int ver = -1;
		String name = val;
		int colon = val.lastIndexOf(':');
		if(colon > 0 && !val.equals("(unresolved)")) {
		    name = val.substring(0, colon);
		    try { ver = Integer.parseInt(val.substring(colon + 1)); } catch(NumberFormatException ex) {}
		}
		entries.add(new ResEntry(e.getKey(), name, ver));
	    }
	}
	entries.sort(Comparator.comparingInt(a -> a.id));
	applyFilter();
    }

    private List<ResEntry> filtered = new ArrayList<>();

    private void applyFilter() {
	String filter = filterEntry.buf.line().toLowerCase().trim();
	filtered.clear();
	for(ResEntry e : entries) {
	    if(filter.isEmpty() || e.name.toLowerCase().contains(filter))
		filtered.add(e);
	}
	sb.max(Math.max(0, filtered.size() - visibleLines()));
    }

    private int visibleLines() {
	return (sz.y - HEADER) / LINEH;
    }

    @Override
    public void draw(GOut g) {
	g.chcolor(new Color(15, 15, 15, 220));
	g.frect(new Coord(0, HEADER), new Coord(sz.x, sz.y - HEADER));
	g.chcolor();

	boolean flashing = System.currentTimeMillis() < flashUntil;
	int y = HEADER + UI.scale(2);
	int vis = visibleLines();
	for(int i = 0; i < vis && (i + sb.val) < filtered.size(); i++) {
	    int idx = i + sb.val;
	    ResEntry e = filtered.get(idx);
	    if(flashing && selected == -2) {
		g.chcolor(new Color(80, 120, 80, 120));
		g.frect(new Coord(0, y), new Coord(sz.x, LINEH));
	    } else if(idx == selected) {
		g.chcolor(flashing ? new Color(80, 120, 80, 160) : new Color(60, 80, 110, 160));
		g.frect(new Coord(0, y), new Coord(sz.x, LINEH));
	    }
	    g.chcolor(new Color(140, 140, 140));
	    g.text(String.format("%5d", e.id), new Coord(UI.scale(4), y));
	    g.chcolor(Color.WHITE);
	    g.text(e.name, new Coord(UI.scale(50), y));
	    if(e.version >= 0) {
		g.chcolor(new Color(100, 100, 100));
		g.text("v" + e.version, new Coord(sz.x - UI.scale(60), y));
	    }
	    g.chcolor();
	    y += LINEH;
	}
	super.draw(g);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
	if(ev.propagate(this)) return true;
	if(ev.c.y >= HEADER && ev.b == 1) {
	    int idx = (ev.c.y - HEADER - UI.scale(2)) / LINEH + sb.val;
	    if(idx >= 0 && idx < filtered.size()) {
		long now = System.currentTimeMillis();
		if(idx == lastClickIdx && (now - lastClickTime) < 400) {
		    selected = idx;
		    copySelected();
		    lastClickTime = 0;
		    lastClickIdx = -1;
		} else {
		    selected = idx;
		    lastClickTime = now;
		    lastClickIdx = idx;
		}
		return true;
	    }
	}
	return false;
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
	sb.ch(ev.a);
	return true;
    }

    @Override
    public void resize(Coord sz) {
	super.resize(sz);
	sb.resize(sz.y - HEADER);
	sb.c = new Coord(sz.x - sb.sz.x, HEADER);
    }
}
