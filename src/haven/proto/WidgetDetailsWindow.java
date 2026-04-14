package haven.proto;

import haven.*;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public class WidgetDetailsWindow extends Window {
    private static final Text.Foundry fnd = new Text.Foundry(Text.mono, 10);
    private static final int LINEH = fnd.height() + UI.scale(2);
    private static final int HEADER = UI.scale(24);
    private static final Coord DEFSZ = UI.scale(500, 400);

    private final List<String> lines = new ArrayList<>();
    private int scroll = 0;
    private int selected = -1;
    private long flashUntil = 0;
    private long lastClickTime = 0;

    public WidgetDetailsWindow(Widget target) {
	super(DEFSZ, "Widget: " + target.getClass().getSimpleName(), true);
	collect(target);
	add(new Button(UI.scale(70), "Copy All") { public void click() { copyAll(); } }, UI.scale(4), UI.scale(2));
	add(new Button(UI.scale(90), "Copy Selected") { public void click() { copySelected(); } }, UI.scale(78), UI.scale(2));
	add(new Label("click to select, double-click to copy", fnd), UI.scale(174), UI.scale(6));
    }

    @Override
    protected Deco makedeco() {
	return new DefaultDeco(true).dragsize(true);
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
	if(sender == this && "close".equals(msg)) {
	    reqdestroy();
	    return;
	}
	super.wdgmsg(sender, msg, args);
    }

    private int visibleLines() { return Math.max(1, (sz.y - HEADER) / LINEH); }

    private int maxScroll() { return Math.max(0, lines.size() - visibleLines()); }

    private void copyAll() {
	StringBuilder sb = new StringBuilder();
	for(String line : lines) sb.append(line).append('\n');
	ClipboardUtil.copy(sb.toString());
	flashUntil = System.currentTimeMillis() + 400;
	selected = -2;
    }

    private void copySelected() {
	if(selected >= 0 && selected < lines.size()) {
	    ClipboardUtil.copy(lines.get(selected));
	    flashUntil = System.currentTimeMillis() + 400;
	}
    }

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
	int y = HEADER;
	int vis = visibleLines();
	boolean flashing = System.currentTimeMillis() < flashUntil;
	for(int i = 0; i < vis && (i + scroll) < lines.size(); i++) {
	    int idx = i + scroll;
	    String line = lines.get(idx);
	    if(idx == selected || (flashing && selected == -2)) {
		g.chcolor(new Color(80, 120, 80, 160));
		g.frect(new Coord(0, y), new Coord(sz.x, LINEH));
	    } else if(idx == selected) {
		g.chcolor(new Color(60, 80, 110, 160));
		g.frect(new Coord(0, y), new Coord(sz.x, LINEH));
	    }
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
    public boolean mousedown(MouseDownEvent ev) {
	if(super.mousedown(ev)) return true;
	if(ev.c.y >= HEADER && ev.b == 1) {
	    int idx = (ev.c.y - HEADER) / LINEH + scroll;
	    if(idx >= 0 && idx < lines.size()) {
		long now = System.currentTimeMillis();
		if(idx == selected && (now - lastClickTime) < 400) {
		    copySelected();
		    lastClickTime = 0;
		} else {
		    lastClickTime = now;
		}
		selected = idx;
		return true;
	    }
	}
	return false;
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
	scroll = Utils.clip(scroll + ev.a * 3, 0, maxScroll());
	return true;
    }
}
