package haven.proto;

import haven.*;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public class WidgetDetailsWindow extends Window {
    private static final Text.Foundry fnd = new Text.Foundry(Text.mono, 10);
    private static final int LINEH = fnd.height() + UI.scale(2);
    private static final Coord DEFSZ = UI.scale(500, 400);

    private final List<String> lines = new ArrayList<>();
    private int scroll = 0;

    public WidgetDetailsWindow(Widget target) {
	super(DEFSZ, "Widget: " + target.getClass().getSimpleName(), true);
	collect(target);
    }

    @Override
    protected Deco makedeco() {
	return new DefaultDeco(true).dragsize(true);
    }

    private int visibleLines() { return Math.max(1, sz.y / LINEH); }

    private int maxScroll() { return Math.max(0, lines.size() - visibleLines()); }

    private void collect(Widget w) {
	lines.clear();
	lines.add("== " + w.getClass().getName() + " ==");
	lines.add("pos: (" + w.c.x + ", " + w.c.y + ")");
	lines.add("size: " + w.sz.x + " x " + w.sz.y);
	lines.add("visible: " + w.visible);
	lines.add("hasfocus: " + w.hasfocus);
	lines.add("canfocus: " + w.canfocus);
	try {
	    Coord rp = w.rootpos();
	    lines.add("rootpos: (" + rp.x + ", " + rp.y + ")");
	} catch (Exception e) {
	    lines.add("rootpos: <unavailable>");
	}
	lines.add("parent: " + (w.parent == null ? "<root>" : w.parent.getClass().getSimpleName()));
	int nch = 0;
	for(Widget ch = w.child; ch != null; ch = ch.next) nch++;
	lines.add("children: " + nch);
	lines.add("");
	lines.add("-- declared fields --");
	collectFields(w);
    }

    private void collectFields(Widget w) {
	Class<?> c = w.getClass();
	while(c != null && c != Object.class) {
	    Field[] fs = c.getDeclaredFields();
	    Arrays.sort(fs, Comparator.comparing(Field::getName));
	    if(fs.length > 0)
		lines.add("  [" + c.getSimpleName() + "]");
	    for(Field f : fs) {
		if(Modifier.isStatic(f.getModifiers())) continue;
		try {
		    f.setAccessible(true);
		    Object v = f.get(w);
		    lines.add("    " + f.getName() + " = " + formatValue(v));
		} catch(Throwable t) {
		    lines.add("    " + f.getName() + " = <" + t.getClass().getSimpleName() + ">");
		}
	    }
	    c = c.getSuperclass();
	}
    }

    private String formatValue(Object v) {
	if(v == null) return "null";
	if(v instanceof String) return "\"" + v + "\"";
	if(v instanceof Widget) return v.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(v));
	if(v.getClass().isArray()) return v.getClass().getSimpleName();
	String s;
	try { s = String.valueOf(v); } catch(Throwable t) { s = "<" + t.getClass().getSimpleName() + ">"; }
	if(s.length() > 200) s = s.substring(0, 200) + "...";
	return s;
    }

    @Override
    public void cdraw(GOut g) {
	super.cdraw(g);
	int y = UI.scale(4);
	int vis = visibleLines();
	for(int i = 0; i < vis && (i + scroll) < lines.size(); i++) {
	    String line = lines.get(i + scroll);
	    Color col = line.startsWith("==") ? Color.YELLOW
		: line.startsWith("--") ? new Color(180, 220, 255)
		: line.startsWith("  [") ? new Color(160, 200, 160)
		: Color.WHITE;
	    g.chcolor(col);
	    g.text(line, new Coord(UI.scale(4), y));
	    y += LINEH;
	}
	g.chcolor();
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
	scroll = Utils.clip(scroll + ev.a * 3, 0, maxScroll());
	return true;
    }
}
