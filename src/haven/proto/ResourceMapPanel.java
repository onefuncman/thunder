package haven.proto;

import haven.*;
import java.awt.Color;
import java.util.*;

public class ResourceMapPanel extends Widget {
    private static final Text.Foundry fnd = new Text.Foundry(Text.mono, 10);
    private static final int LINEH = fnd.height() + UI.scale(2);
    private final List<ResEntry> entries = new ArrayList<>();
    private final Scrollbar sb;
    private final TextEntry filterEntry;

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
		applyFilter();
	    }
	}, x, 0);
	sb = adda(new Scrollbar(sz.y - UI.scale(25), 0, 0), sz.x - UI.scale(1), UI.scale(25), 1, 0);
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
	return (sz.y - UI.scale(25)) / LINEH;
    }

    @Override
    public void draw(GOut g) {
	g.chcolor(new Color(15, 15, 15, 220));
	g.frect(new Coord(0, UI.scale(25)), new Coord(sz.x, sz.y - UI.scale(25)));
	g.chcolor();

	int y = UI.scale(27);
	int vis = visibleLines();
	for(int i = 0; i < vis && (i + sb.val) < filtered.size(); i++) {
	    ResEntry e = filtered.get(i + sb.val);
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
