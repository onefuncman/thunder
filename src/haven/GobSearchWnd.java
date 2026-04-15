package haven;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GobSearchWnd extends GameUI.Hidewnd {
    private static final int MIN_QUERY = 2;
    private static final int LIST_W = 240;
    private static final int LIST_H = 200;
    private static final int LIST_ITEM_H = 16;
    private final TextEntry input;
    private final Label status;
    private final ResultList results;
    private final Set<Long> highlighted = new HashSet<>();
    private List<ResidHit> hits = new ArrayList<>();
    private String lastQuery = "";

    GobSearchWnd() {
	super(new Coord(LIST_W, LIST_H + 64), "Gob Search");
	input = add(new TextEntry(LIST_W - 20, "") {
	    @Override
	    protected void changed() {
		refresh();
	    }
	});
	status = adda(new Label(""), input.pos("bl").adds(0, 4), 0, 0);
	results = add(new ResultList(LIST_W - 20, LIST_H, LIST_ITEM_H), status.pos("bl").adds(0, 4));
	pack();
	hide();
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
	if(ev.code == KeyEvent.VK_ESCAPE) {
	    if(input.text().length() > 0) {
		input.settext("");
		refresh();
		return true;
	    }
	}
	return !ignoredKey(ev.awt) && super.keydown(ev);
    }

    private static boolean ignoredKey(KeyEvent ev) {
	int code = ev.getKeyCode();
	int mods = ev.getModifiersEx();
	return (mods != 0 && mods != KeyEvent.SHIFT_DOWN_MASK)
	    || code == KeyEvent.VK_CONTROL
	    || code == KeyEvent.VK_ALT
	    || code == KeyEvent.VK_META
	    || code == KeyEvent.VK_TAB;
    }

    @Override
    public void tick(double dt) {
	super.tick(dt);
	if(visible) {
	    applyHighlights(lastQuery);
	} else if(!highlighted.isEmpty()) {
	    clearHighlights();
	}
    }

    @Override
    public void show() {
	super.show();
	setfocus(input);
	refresh();
	raise();
    }

    @Override
    public void hide() {
	super.hide();
	clearHighlights();
	hits = new ArrayList<>();
	status.settext("");
    }

    @Override
    public void dispose() {
	clearHighlights();
	super.dispose();
    }

    private void refresh() {
	lastQuery = input.text().trim().toLowerCase();
	applyHighlights(lastQuery);
    }

    private void applyHighlights(String query) {
	if(ui == null || ui.sess == null || ui.sess.glob == null) {return;}
	if(query.length() < MIN_QUERY) {
	    clearHighlights();
	    hits = new ArrayList<>();
	    status.settext("");
	    return;
	}
	OCache oc = ui.sess.glob.oc;
	Set<Long> keep = new HashSet<>();
	Map<String, Integer> counts = new HashMap<>();
	int matches = 0;
	synchronized(oc) {
	    for(Gob g : oc) {
		String rid = g.resid();
		if(rid == null) {continue;}
		if(!rid.toLowerCase().contains(query)) {continue;}
		matches++;
		keep.add(g.id);
		counts.merge(rid, 1, Integer::sum);
		GobHighlight h = g.getattr(GobHighlight.class);
		if(h == null) {
		    h = new GobHighlight(g);
		    g.setattr(h);
		    h.setPersistent(true);
		    highlighted.add(g.id);
		} else if(highlighted.contains(g.id) && !h.isPersistent()) {
		    h.setPersistent(true);
		}
	    }
	    Set<Long> toClear = new HashSet<>(highlighted);
	    toClear.removeAll(keep);
	    for(Long id : toClear) {
		Gob g = oc.getgob(id);
		if(g != null) {
		    GobHighlight h = g.getattr(GobHighlight.class);
		    if(h != null && h.isPersistent()) {
			h.setPersistent(false);
			g.delattr(GobHighlight.class);
		    }
		}
		highlighted.remove(id);
	    }
	}
	List<ResidHit> next = new ArrayList<>(counts.size());
	for(Map.Entry<String, Integer> e : counts.entrySet()) {
	    next.add(new ResidHit(e.getKey(), e.getValue()));
	}
	next.sort(Comparator.<ResidHit>comparingInt(h -> -h.count).thenComparing(h -> h.resid));
	hits = next;
	status.settext(String.format("%d match%s across %d resid%s",
	    matches, matches == 1 ? "" : "es",
	    hits.size(), hits.size() == 1 ? "" : "s"));
    }

    private void clearHighlights() {
	if(highlighted.isEmpty()) {return;}
	if(ui == null || ui.sess == null || ui.sess.glob == null) {highlighted.clear(); return;}
	OCache oc = ui.sess.glob.oc;
	synchronized(oc) {
	    for(Long id : highlighted) {
		Gob g = oc.getgob(id);
		if(g == null) {continue;}
		GobHighlight h = g.getattr(GobHighlight.class);
		if(h != null && h.isPersistent()) {
		    h.setPersistent(false);
		    g.delattr(GobHighlight.class);
		}
	    }
	}
	highlighted.clear();
    }

    private static class ResidHit {
	final String resid;
	final int count;
	ResidHit(String resid, int count) {this.resid = resid; this.count = count;}
    }

    private class ResultList extends Listbox<ResidHit> {
	private final Color rowEven = new Color(0, 0, 0, 84);
	private final Color rowOdd = new Color(0, 0, 0, 42);

	ResultList(int w, int h, int itemh) {
	    super(w, h / itemh, itemh);
	    bgcolor = new Color(0, 0, 0, 84);
	}

	@Override
	protected ResidHit listitem(int i) {return hits.get(i);}

	@Override
	protected int listitems() {return hits.size();}

	@Override
	protected void drawitem(GOut g, ResidHit item, int i) {
	    g.chcolor(((i % 2) == 0) ? rowEven : rowOdd);
	    g.frect(Coord.z, g.sz());
	    g.chcolor();
	    String label = item.count > 1
		? String.format("%s  x%d", item.resid, item.count)
		: item.resid;
	    g.atext(label, new Coord(4, itemh / 2), 0, 0.5);
	}
    }
}
