package haven.proto;

import haven.*;
import java.awt.Color;
import java.util.*;

public class WidgetTreePanel extends Widget {
    private static final Text.Foundry fnd = new Text.Foundry(Text.mono, 10);
    private static final int LINEH = fnd.height() + UI.scale(2);
    private static final int TOPBAR = UI.scale(25);
    private static final int FILTERBAR = UI.scale(22);
    private static final int HEADER = TOPBAR + FILTERBAR;

    private final List<TreeEntry> allEntries = new ArrayList<>();
    private final List<TreeEntry> entries = new ArrayList<>();
    private final Scrollbar sb;
    private final TextEntry filterEntry;
    private final Button pickBtn;
    private final Button overlayBtn;
    private int selected = -1;
    private String filter = "";
    private UI.Grab pickGrab = null;
    private boolean picking = false;
    private long lastClickTime = 0;
    private int lastClickIdx = -1;
    private ClickAreaOverlay overlay = null;

    private static class TreeEntry {
	final int depth;
	final int widgetId;
	final String className;
	final Coord pos;
	final Coord size;
	final boolean visible;
	final Widget widget;

	TreeEntry(int depth, int widgetId, String className, Coord pos, Coord size, boolean visible, Widget widget) {
	    this.depth = depth;
	    this.widgetId = widgetId;
	    this.className = className;
	    this.pos = pos;
	    this.size = size;
	    this.visible = visible;
	    this.widget = widget;
	}
    }

    public WidgetTreePanel(Coord sz) {
	super(sz);
	int x = 0;
	add(new Button(UI.scale(80), "Refresh") { public void click() { refresh(); } }, x, 0);
	x += UI.scale(84);
	pickBtn = add(new Button(UI.scale(60), "Pick") { public void click() { togglePick(); } }, x, 0);
	x += UI.scale(64);
	overlayBtn = add(new Button(UI.scale(90), "Click Areas") { public void click() { toggleOverlay(); } }, x, 0);

	filterEntry = add(new TextEntry(sz.x - UI.scale(60), "") {
	    @Override
	    protected void changed() {
		super.changed();
		filter = text();
		applyFilter();
	    }
	}, UI.scale(56), TOPBAR);
	add(new Label("Filter:", fnd), UI.scale(4), TOPBAR + UI.scale(4));

	sb = adda(new Scrollbar(sz.y - HEADER, 0, 0), sz.x - UI.scale(1), HEADER, 1, 0);
    }

    public void refresh() {
	allEntries.clear();
	if(ui != null && ui.root != null)
	    walk(ui.root, 0);
	applyFilter();
    }

    private void walk(Widget w, int depth) {
	int wid = (ui != null) ? ui.widgetid(w) : -1;
	allEntries.add(new TreeEntry(depth, wid, w.getClass().getSimpleName(), w.c, w.sz, w.visible, w));
	for(Widget ch = w.child; ch != null; ch = ch.next)
	    walk(ch, depth + 1);
    }

    private void applyFilter() {
	entries.clear();
	String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
	if(f.isEmpty()) {
	    entries.addAll(allEntries);
	} else {
	    for(TreeEntry e : allEntries) {
		if(e.className.toLowerCase(Locale.ROOT).contains(f) || ("#" + e.widgetId).contains(f))
		    entries.add(e);
	    }
	}
	sb.max(Math.max(0, entries.size() - visibleLines()));
	if(selected >= entries.size()) selected = -1;
    }

    private int visibleLines() {
	return (sz.y - HEADER) / LINEH;
    }

    private void togglePick() {
	if(picking) {
	    endPick();
	} else {
	    picking = true;
	    pickBtn.change("Picking...");
	    pickGrab = ui.grabmouse(this);
	}
    }

    private void endPick() {
	picking = false;
	pickBtn.change("Pick");
	if(pickGrab != null) { pickGrab.remove(); pickGrab = null; }
    }

    private void toggleOverlay() {
	if(overlay != null && overlay.parent != null) {
	    overlay.destroy();
	    overlay = null;
	    overlayBtn.change("Click Areas");
	} else if(ui != null && ui.root != null) {
	    overlay = new ClickAreaOverlay(ui.root.sz, this::selectedWidget);
	    ui.root.add(overlay, Coord.z);
	    overlay.z(Integer.MAX_VALUE);
	    overlayBtn.change("Hide Areas");
	}
    }

    public Widget selectedWidget() {
	if(selected < 0 || selected >= entries.size()) return null;
	return entries.get(selected).widget;
    }

    private void handlePick(Coord rootC) {
	endPick();
	Widget picked = deepestAt(ui.root, rootC, topLevelAncestor());
	if(picked == null) return;
	refresh();
	for(int i = 0; i < entries.size(); i++) {
	    if(entries.get(i).widget == picked) {
		selected = i;
		int vis = visibleLines();
		if(i < sb.val || i >= sb.val + vis)
		    sb.val = Math.max(0, Math.min(i - vis / 2, sb.max));
		break;
	    }
	}
    }

    private Widget topLevelAncestor() {
	Widget w = this;
	while(w.parent != null && w.parent != ui.root) w = w.parent;
	return w;
    }

    private static boolean isPassthrough(Widget w) {
	Class<?> c = w.getClass();
	Package pkg = c.getPackage();
	if(pkg != null && "haven.proto".equals(pkg.getName())) return true;
	return "haven.res.ui.locptr.Pointer".equals(c.getName());
    }

    private Widget deepestAt(Widget w, Coord rootC, Widget exclude) {
	if(w == null || w == exclude || !w.visible) return null;
	Coord wp = w.rootpos();
	if(!rootC.isect(wp, w.sz)) return null;
	for(Widget ch = w.lchild; ch != null; ch = ch.prev) {
	    Widget r = deepestAt(ch, rootC, exclude);
	    if(r != null) return r;
	}
	if(isPassthrough(w)) return null;
	if(w.parent == null) return null;
	return w;
    }

    private void openDetails(TreeEntry e) {
	if(ui == null || ui.root == null || e.widget == null) return;
	WidgetDetailsWindow w = new WidgetDetailsWindow(e.widget);
	ui.root.add(w, me.ender.ClientUtils.getScreenCenter(ui));
    }

    @Override
    public void draw(GOut g) {
	g.chcolor(new Color(15, 15, 15, 220));
	g.frect(new Coord(0, HEADER), new Coord(sz.x, sz.y - HEADER));
	g.chcolor();

	int y = HEADER + UI.scale(2);
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
	if(picking) {
	    if(ev.b == 1) {
		Coord rootC = rootpos().add(ev.c);
		handlePick(rootC);
		return true;
	    } else {
		endPick();
		return true;
	    }
	}
	if(ev.propagate(this))
	    return true;
	if(ev.c.y < HEADER) return true;
	int idx = (ev.c.y - HEADER - UI.scale(2)) / LINEH + sb.val;
	if(idx >= 0 && idx < entries.size()) {
	    long now = System.currentTimeMillis();
	    if(idx == lastClickIdx && (now - lastClickTime) < 400) {
		openDetails(entries.get(idx));
		lastClickTime = 0;
		lastClickIdx = -1;
	    } else {
		lastClickTime = now;
		lastClickIdx = idx;
	    }
	    selected = idx;
	}
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
	sb.resize(sz.y - HEADER);
	sb.c = new Coord(sz.x - sb.sz.x, HEADER);
	if(filterEntry != null) filterEntry.resize(sz.x - UI.scale(60));
    }

    @Override
    public void destroy() {
	if(overlay != null) { overlay.destroy(); overlay = null; }
	if(pickGrab != null) { pickGrab.remove(); pickGrab = null; }
	super.destroy();
    }
}
