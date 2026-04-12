package haven.proto;

import haven.*;

import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Dumps the current in-game UI state relevant to inventory-driven
 * automation scripts (cheese-tray fill, salt-food, refill-drinks, etc.):
 *
 * <ul>
 *   <li>Cursor + hand state</li>
 *   <li>Every open Window (via gui.lchild traversal)</li>
 *   <li>Every Inventory inside those windows (incl. ExtInventory-wrapped)</li>
 *   <li>Every WItem: resname, item widget id, grid coord, tooltip text</li>
 * </ul>
 *
 * Output is deterministic plaintext so unit tests can load it as a
 * fixture — the point is to capture real observed state (e.g. the
 * {@code gfx/invobjs/small/cheesetray} resname that hand-written test
 * cases would never have guessed) and replay it in a FakeEnv.
 */
public class SceneSnapshotter {
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");

    public static Path snapshot(GameUI gui) throws IOException {
	Path dir = Paths.get("snapshots");
	Files.createDirectories(dir);
	Path out = dir.resolve("scene-" + STAMP.format(new Date()) + ".txt");
	String text = render(gui);
	Files.write(out, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	return out;
    }

    public static String render(GameUI gui) {
	StringBuilder sb = new StringBuilder();
	sb.append("# Scene snapshot ").append(new Date()).append('\n');

	sb.append("mouse: ").append(gui.ui.mc == null ? "null" : (gui.ui.mc.x + "," + gui.ui.mc.y)).append('\n');
	sb.append("cursor: ").append(gui.cursor == null ? "null" : gui.cursor.toString()).append('\n');

	GameUI.DraggedItem held = gui.hand();
	if(held == null || held.item == null) {
	    sb.append("hand: null\n");
	} else {
	    GItem g = held.item;
	    sb.append("hand: id=").append(gui.ui.widgetid(g));
	    appendItemAttrs(sb, g);
	    sb.append('\n');
	}

	FlowerMenu fm = findFlowerMenu(gui);
	if(fm != null) {
	    sb.append("flowermenu: options=[");
	    for (int i = 0; i < fm.options.length; i++) {
		if(i > 0) { sb.append(", "); }
		sb.append("\"").append(safe(fm.options[i])).append("\"");
	    }
	    sb.append("]\n");
	}

	sb.append('\n');

	int windowIndex = 0;
	for (Widget w = gui.lchild; w != null; w = w.prev) {
	    if(!(w instanceof Window)) { continue; }
	    Window wnd = (Window) w;
	    String cap = wnd.cap == null ? "<no-cap>" : wnd.cap;
	    sb.append("window[").append(windowIndex++).append("] \"").append(cap).append("\"")
		.append(" id=").append(widgetId(gui, wnd))
		.append(" pos=(").append(wnd.c.x).append(",").append(wnd.c.y).append(")")
		.append(" size=").append(wnd.sz.x).append("x").append(wnd.sz.y)
		.append('\n');

	    int invIndex = 0;
	    for (Widget ch = wnd.lchild; ch != null; ch = ch.prev) {
		Inventory inv = ExtInventory.inventory(ch);
		if(inv == null) { continue; }
		sb.append("  inv[").append(invIndex++).append("] ")
		    .append(inv.isz == null ? "?x?" : inv.isz.x + "x" + inv.isz.y).append('\n');

		List<WItem> items = new ArrayList<>();
		for (WItem it : inv.children(WItem.class)) { items.add(it); }
		items.sort(Comparator
		    .comparingInt((WItem it) -> it.c == null ? 0 : it.c.y)
		    .thenComparingInt(it -> it.c == null ? 0 : it.c.x));
		for (WItem it : items) {
		    sb.append("    ").append(itemLine(it)).append('\n');
		}
	    }
	}
	return sb.toString();
    }

    private static FlowerMenu findFlowerMenu(GameUI gui) {
	for (Widget w = gui.ui.root.lchild; w != null; w = w.prev) {
	    if(w instanceof FlowerMenu) { return (FlowerMenu) w; }
	}
	return null;
    }

    private static String itemLine(WItem it) {
	StringBuilder sb = new StringBuilder();
	Coord gc = it.c == null ? null : it.c.div(Inventory.invsq.sz());
	sb.append("[");
	if(gc != null) { sb.append(gc.x).append(",").append(gc.y); }
	sb.append("] ");
	GItem g = it.item;
	sb.append("id=").append(g == null ? "?" : String.valueOf(it.ui.widgetid(g)));
	if(it.c != null) { sb.append(" px=(").append(it.c.x).append(",").append(it.c.y).append(")"); }
	appendItemAttrs(sb, g);
	try {
	    String sort = it.sortName();
	    if(sort != null && !sort.isEmpty()) { sb.append(" sort=\"").append(safe(sort)).append("\""); }
	} catch (Exception ignore) {}
	return sb.toString();
    }

    /** Shared attribute rendering for an item — used for both inventory items and hand. */
    private static void appendItemAttrs(StringBuilder sb, GItem g) {
	if(g == null) { return; }
	if(g.num >= 0) { sb.append(" num=").append(g.num); }
	if(g.meter > 0) { sb.append(" meter=").append(g.meter); }
	if(g.infoseq != 0) { sb.append(" infoseq=").append(g.infoseq); }
	int sdtLen = sdtLength(g);
	if(g.sdt != null) { sb.append(" sdt_count=").append(decSdt(g)).append(" sdt_len=").append(sdtLen); }
	String sdtHex = sdtHex(g);
	if(sdtHex != null) { sb.append(" sdt_hex=").append(sdtHex); }
	double q = 0;
	try { q = g.quality(); } catch (Exception ignore) {}
	if(q > 0) { sb.append(" q=").append(String.format("%.1f", q)); }
	if(g.contentsnm != null) { sb.append(" contentsnm=\"").append(safe(g.contentsnm)).append("\""); }
	sb.append(" res=\"").append(safe(g.resname())).append("\"");
	sb.append(" info=\"").append(safe(infoText(g))).append("\"");
    }

    private static int decSdt(GItem g) {
	try {
	    if(g.sdt == null) { return 0; }
	    return Sprite.decnum(g.sdt.clone());
	} catch (Exception e) { return 0; }
    }

    private static int sdtLength(GItem g) {
	try {
	    if(g.sdt == null) { return 0; }
	    return g.sdt.bytes().length;
	} catch (Exception e) { return 0; }
    }

    private static String sdtHex(GItem g) {
	try {
	    if(g.sdt == null) { return null; }
	    byte[] b = g.sdt.bytes();
	    if(b.length == 0) { return "[]"; }
	    StringBuilder s = new StringBuilder(b.length * 2);
	    for (byte by : b) { s.append(String.format("%02x", by & 0xff)); }
	    return s.toString();
	} catch (Exception e) { return null; }
    }

    private static String infoText(GItem g) {
	if(g == null) { return ""; }
	try {
	    List<ItemInfo> info = g.info();
	    if(info == null || info.isEmpty()) { return ""; }
	    StringBuilder s = new StringBuilder();
	    dumpInfoTree(info, s);
	    return s.toString();
	} catch (Loading l) {
	    return "<loading>";
	} catch (Exception e) {
	    return "<err:" + e.getClass().getSimpleName() + ">";
	}
    }

    /** Recursively render every ItemInfo subclass + its interesting field (Name.original, AdHoc.str, Contents.sub). */
    private static void dumpInfoTree(List<ItemInfo> info, StringBuilder s) {
	boolean first = true;
	for (ItemInfo ii : info) {
	    if(!first) { s.append(" | "); }
	    first = false;
	    String cls = ii.getClass().getSimpleName();
	    s.append(cls);
	    if(ii instanceof ItemInfo.Name) {
		s.append(":\"").append(((ItemInfo.Name) ii).original).append("\"");
	    } else if(ii instanceof ItemInfo.AdHoc) {
		s.append(":\"").append(((ItemInfo.AdHoc) ii).str.text).append("\"");
	    } else if(ii instanceof ItemInfo.Contents) {
		s.append("{");
		dumpInfoTree(((ItemInfo.Contents) ii).sub, s);
		s.append("}");
	    }
	}
    }

    private static String widgetId(GameUI gui, Widget w) {
	try { return String.valueOf(gui.ui.widgetid(w)); } catch (Exception e) { return "?"; }
    }

    private static String safe(String s) {
	if(s == null) { return ""; }
	return s.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
