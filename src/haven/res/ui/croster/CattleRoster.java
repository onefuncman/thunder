/* Preprocessed source code */
package haven.res.ui.croster;

import haven.*;
import haven.render.*;
import java.util.*;
import java.util.function.*;
import java.lang.reflect.Field;
import java.awt.Font;
import haven.MenuGrid.Pagina;
import haven.rx.Reactor;
import rx.Subscription;
import java.util.concurrent.atomic.AtomicReference;

@haven.FromResource(name = "ui/croster", version = 77)
public abstract class CattleRoster <T extends Entry> extends Widget {
    public static final int WIDTH = UI.scale(900);
    public static final Comparator<Entry> namecmp = (a, b) -> a.name.compareTo(b.name);
    public static final int HEADH = UI.scale(40);
    // Larger foundry for the Select-by dropdown and selection count.
    public static final Text.Foundry SB_FND = new Text.Foundry(Text.sans.deriveFont(Font.BOLD, UI.scale(14f))).aa(true);
    private static final int COUNT_RESERVED_CHARS = 9;
    public final Map<UID, T> entries = new HashMap<>();
    public final Scrollbar sb;
    public final Widget entrycont;
    public int entryseq = 0;
    public List<T> display = Collections.emptyList();
    public boolean dirty = true;
    public Comparator<? super T> order = namecmp;
    public Column<? super T> mousecol, ordercol, ordercol2;
    public boolean revorder, revorder2;
    private Button btnRemove;
    private Button btnAll, btnNone, btnInvert, btnClear, btnRecolor;
    private Dropbox<SelAction> selDrop;
    private CountLabel countLbl;
    private int lastSel = -1, lastTotal = -1;

    public CattleRoster() {
	super(new Coord(WIDTH, UI.scale(400)));
	entrycont = add(new Widget(sz), 0, HEADH);
	sb = add(new Scrollbar(sz.y, 0, 0) {
		public void changed() {redisplay(display);}
	    }, sz.x, HEADH);
	int bw = UI.scale(80);
	btnAll = add(new Button(bw, "Select all", false).action(() -> applyMark(e -> true, true)),
		     entrycont.pos("bl").adds(0, 5));
	btnNone = add(new Button(bw, "Select none", false).action(() -> applyMark(e -> true, false)),
		      btnAll.pos("ur").adds(5, 0));
	btnInvert = add(new Button(bw, "Invert", false).action(this::invertSel),
			btnNone.pos("ur").adds(5, 0));
	btnRecolor = add(new Button(bw, "Recolor...", false).action(this::openRecolor),
			 btnInvert.pos("ur").adds(5, 0));
	btnRecolor.settip("Recolor all selected cattle (drives per-cattle color picker)");
	selDrop = add(new SelDrop(UI.scale(170)), btnRecolor.pos("ur").adds(5, 0));
	selDrop.change(SelAction.PICK);
	btnClear = add(new Button(UI.scale(100), "Refresh Names", false).action(this::clearNames),
		       selDrop.pos("ur").adds(5, 0));
	btnClear.settip("Prune names to current roster");
	btnRemove = adda(new Button(UI.scale(150), "Remove selected", false).action(() -> {
	    Collection<Object> args = new ArrayList<>();
	    for(Entry entry : this.entries.values()) {
		if(entry.mark.a)
		    args.add(entry.id);
	    }
	    wdgmsg("rm", args.toArray(new Object[0]));
	}), entrycont.pos("br").adds(0, 5), 1, 0);
	countLbl = add(new CountLabel());
	placeCountLbl();
	pack();
    }

    public void resizeRoster(Coord nsz) {
	int btnRowH = UI.scale(34);
	int innerH = Math.max(UI.scale(40), nsz.y - HEADH - btnRowH);
	this.sz = nsz;
	entrycont.resize(new Coord(nsz.x, innerH));
	sb.c = new Coord(nsz.x - sb.sz.x, HEADH);
	sb.resize(new Coord(sb.sz.x, innerH));
	Coord bl = entrycont.pos("bl").adds(0, 5);
	btnAll.c = bl;
	btnNone.c = btnAll.pos("ur").adds(5, 0);
	btnInvert.c = btnNone.pos("ur").adds(5, 0);
	btnRecolor.c = btnInvert.pos("ur").adds(5, 0);
	selDrop.c = btnRecolor.pos("ur").adds(5, 0);
	btnClear.c = selDrop.pos("ur").adds(5, 0);
	btnRemove.c = entrycont.pos("br").adds(-btnRemove.sz.x, 5);
	placeCountLbl();
	dirty = true;
    }

    private void placeCountLbl() {
	if(countLbl == null || btnRemove == null) return;
	int gap = UI.scale(10);
	int y = btnRemove.c.y + (btnRemove.sz.y - countLbl.sz.y) / 2;
	countLbl.c = new Coord(btnRemove.c.x - countLbl.sz.x - gap, y);
    }

    private void applyMark(Predicate<? super T> p, boolean val) {
	for(T e : entries.values()) {
	    if(p.test(e)) e.mark.set(val);
	}
    }

    private void invertSel() {
	for(T e : entries.values()) e.mark.set(!e.mark.a);
    }

    private boolean[] currentMarks() {
	boolean[] marks = new boolean[display.size()];
	for(int i = 0; i < marks.length; i++) marks[i] = display.get(i).mark.a;
	return(marks);
    }

    private void applyMarks(boolean[] marks) {
	for(int i = 0; i < marks.length; i++) {
	    if(marks[i] && !display.get(i).mark.a) display.get(i).mark.set(true);
	}
    }

    public void shiftClickSelect(Entry clicked) {
	int idx = display.indexOf(clicked);
	if(idx < 0) return;
	boolean[] marks = currentMarks();
	thunder.roster.RosterLogic.shiftClickRange(marks, idx);
	applyMarks(marks);
    }

    public boolean selectToTop() {
	if(display.isEmpty()) return(false);
	boolean[] marks = currentMarks();
	if(!thunder.roster.RosterLogic.selectToTop(marks)) return(false);
	applyMarks(marks);
	return(true);
    }

    public boolean selectToBottom() {
	if(display.isEmpty()) return(false);
	boolean[] marks = currentMarks();
	if(!thunder.roster.RosterLogic.selectToBottom(marks)) return(false);
	applyMarks(marks);
	return(true);
    }

    public void clearGobHighlight(UID id) {
	if(ui == null || ui.sess == null) return;
	OCache oc = ui.sess.glob.oc;
	synchronized(oc) {
	    for(Gob g : oc) {
		CattleId cid = g.getattr(CattleId.class);
		if(cid == null || !cid.id.equals(id)) continue;
		GobHighlight h = g.getattr(GobHighlight.class);
		if(h != null && h.isPersistent()) {
		    h.setPersistent(false);
		    g.delattr(GobHighlight.class);
		}
		break;
	    }
	}
    }

    public void clearAllCattleHighlights() {
	if(ui == null || ui.sess == null) return;
	OCache oc = ui.sess.glob.oc;
	synchronized(oc) {
	    for(Gob g : oc) {
		CattleId cid = g.getattr(CattleId.class);
		if(cid == null || !entries.containsKey(cid.id)) continue;
		GobHighlight h = g.getattr(GobHighlight.class);
		if(h != null && h.isPersistent()) {
		    h.setPersistent(false);
		    g.delattr(GobHighlight.class);
		}
	    }
	}
    }

    public void syncHighlights() {
	if(ui == null || ui.sess == null) return;
	Set<UID> marked = new HashSet<>();
	for(T e : entries.values()) {
	    if(e.mark.a) marked.add(e.id);
	}
	OCache oc = ui.sess.glob.oc;
	synchronized(oc) {
	    for(Gob g : oc) {
		CattleId cid = g.getattr(CattleId.class);
		if(cid == null || !entries.containsKey(cid.id)) continue;
		boolean want = marked.contains(cid.id);
		GobHighlight h = g.getattr(GobHighlight.class);
		if(want) {
		    if(h == null) {h = new GobHighlight(g); g.setattr(h);}
		    if(!h.isPersistent()) h.setPersistent(true);
		} else if(h != null && h.isPersistent()) {
		    h.setPersistent(false);
		    g.delattr(GobHighlight.class);
		}
	    }
	}
    }

    private void clearNames() {
	RosterWindow w = getparent(RosterWindow.class);
	if(w != null) w.refreshMemorized();
    }

    public static final Comparator<Entry> selcmp = (a, b) -> Boolean.compare(b.mark.a, a.mark.a);
    private final Column<Entry> selcol;
    {
	int cbw = CheckBox.sbox.sz().x + UI.scale(10);
	selcol = new Column<>("\u2713", selcmp, 1);
	selcol.w = cbw;
	selcol.x = 0;
    }

    @SafeVarargs
    public static <E extends Entry>  List<Column<? super E>> initcols(Column<? super E>... attrs) {
	for(int i = 0, x = CheckBox.sbox.sz().x + UI.scale(10); i < attrs.length; i++) {
	    Column<? super E> attr = attrs[i];
	    attr.x = x;
	    x += attr.w;
	    x += UI.scale(attr.r ? 5 : 1);
	}
	return(Arrays.asList(attrs));
    }

    public void redisplay(List<T> display) {
	Set<T> hide = new HashSet<>(entries.values());
	int h = 0, th = entrycont.sz.y;
	for(T entry : display)
	    h += entry.sz.y;
	sb.max = h - th;
	int y = -sb.val, idx = 0;
	for(T entry : display) {
	    entry.idx = idx++;
	    if((y + entry.sz.y > 0) && (y < th)) {
		entry.move(new Coord(0, y));
		entry.show();
	    } else {
		entry.hide();
	    }
	    hide.remove(entry);
	    y += entry.sz.y;
	}
	for(T entry : hide)
	    entry.hide();
	this.display = display;
    }

    private Comparator<? super T> effectiveOrder() {
	Comparator<? super T> primary = (ordercol != null) ? ordercol.order : null;
	Comparator<? super T> secondary = (ordercol2 != null && ordercol2 != ordercol) ? ordercol2.order : null;
	return(thunder.roster.RosterLogic.combineOrder(primary, revorder, secondary, revorder2, namecmp));
    }

    public void tick(double dt) {
	if(dirty) {
	    List<T> ndisp = new ArrayList<>(entries.values());
	    ndisp.sort(effectiveOrder());
	    redisplay(ndisp);
	    dirty = false;
	}
	int sel = 0, tot = entries.size();
	for(T e : entries.values()) if(e.mark.a) sel++;
	if(sel != lastSel || tot != lastTotal) {
	    lastSel = sel; lastTotal = tot;
	    Coord prev = countLbl.sz;
	    countLbl.settext(sel + "/" + tot);
	    if(!countLbl.sz.equals(prev)) placeCountLbl();
	}
	super.tick(dt);
    }

    protected abstract List<Column<? super T>> cols();

    public void drawcols(GOut g) {
	if((selcol == mousecol) && (selcol.order != null)) {
	    g.chcolor(255, 255, 0, 16);
	    g.frect2(new Coord(selcol.x, 0), new Coord(selcol.x + selcol.w, sz.y));
	    g.chcolor();
	}
	if(selcol == ordercol) {
	    g.chcolor(255, 255, 0, 16);
	    g.frect2(new Coord(selcol.x, 0), new Coord(selcol.x + selcol.w, sz.y));
	    g.chcolor();
	}
	g.aimage(selcol.head(), new Coord(selcol.x + (selcol.w / 2), HEADH / 2), 0.5, 0.5);
	Column prev = null;
	for(Column col : cols()) {
	    if((prev != null) && !prev.r) {
		g.chcolor(255, 255, 0, 64);
		int x = (prev.x + prev.w + col.x) / 2;
		g.line(new Coord(x, 0), new Coord(x, sz.y), 1);
		g.chcolor();
	    }
	    if((col == mousecol) && (col.order != null)) {
		g.chcolor(255, 255, 0, 16);
		g.frect2(new Coord(col.x, 0), new Coord(col.x + col.w, sz.y));
		g.chcolor();
	    }
	    if(col == ordercol) {
		g.chcolor(255, 255, 0, 16);
		g.frect2(new Coord(col.x, 0), new Coord(col.x + col.w, sz.y));
		g.chcolor();
	    } else if(col == ordercol2) {
		g.chcolor(255, 255, 0, 8);
		g.frect2(new Coord(col.x, 0), new Coord(col.x + col.w, sz.y));
		g.chcolor();
	    }
	    Tex head = col.head();
	    g.aimage(head, new Coord(col.x + (col.w / 2), HEADH / 2), 0.5, 0.5);
	    prev = col;
	}
    }

    public void draw(GOut g) {
	drawcols(g);
	super.draw(g);
    }

    public Column<? super T> onhead(Coord c) {
	if((c.y < 0) || (c.y >= HEADH))
	    return(null);
	if((c.x >= selcol.x) && (c.x < selcol.x + selcol.w))
	    return(selcol);
	for(Column<? super T> col : cols()) {
	    if((c.x >= col.x) && (c.x < col.x + col.w))
		return(col);
	}
	return(null);
    }

    // --- Column resize via header edge drag ---
    private static final int RESIZE_GRAB = UI.scale(4);
    private static final Resource HRES_CURSOR = Resource.local().loadwait("gfx/hud/curs/hand");
    private Column<? super T> resizingCol;
    private int grabStartX, grabStartW;
    private UI.Grab resizeGrab;

    private Column<? super T> columnAtEdge(Coord c) {
	if((c.y < 0) || (c.y >= HEADH)) return(null);
	for(Column<? super T> col : cols()) {
	    int edge = col.x + col.w;
	    if((c.x >= edge - RESIZE_GRAB) && (c.x <= edge + RESIZE_GRAB)) return(col);
	}
	return(null);
    }

    private void packColumns() {
	int x = CheckBox.sbox.sz().x + UI.scale(10);
	for(Column<? super T> col : cols()) {
	    col.x = x;
	    x += col.w;
	    x += UI.scale(col.r ? 5 : 1);
	}
    }

    @Override
    public Resource getcurss(Coord c) {
	if((resizingCol != null) || (columnAtEdge(c) != null))
	    return(HRES_CURSOR);
	return(super.getcurss(c));
    }

    public void mousemove(MouseMoveEvent ev) {
	super.mousemove(ev);
	if(resizingCol != null) {
	    int nw = Math.max(resizingCol.minw, grabStartW + (ev.c.x - grabStartX));
	    if(nw != resizingCol.w) {
		resizingCol.w = nw;
		packColumns();
		dirty = true;
	    }
	    return;
	}
	mousecol = onhead(ev.c);
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(ev.b == 1) {
	    Column<? super T> edge = columnAtEdge(ev.c);
	    if(edge != null) {
		resizingCol = edge;
		grabStartX = ev.c.x;
		grabStartW = edge.w;
		resizeGrab = ui.grabmouse(this);
		return(true);
	    }
	}
	Column<? super T> col = onhead(ev.c);
	if((col != null) && (col.order != null)) {
	    if(ev.b == 1) {
		revorder = (col == ordercol) ? !revorder : false;
		ordercol = col;
		ordercol2 = null;
		revorder2 = false;
		dirty = true;
		return(true);
	    } else if(ev.b == 3) {
		if(col == ordercol) return(true); // primary; ignore right-click on it
		revorder2 = (col == ordercol2) ? !revorder2 : false;
		ordercol2 = col;
		dirty = true;
		return(true);
	    }
	}
	return(super.mousedown(ev));
    }

    public boolean mouseup(MouseUpEvent ev) {
	if(resizingCol != null && ev.b == 1) {
	    resizingCol = null;
	    if(resizeGrab != null) {resizeGrab.remove(); resizeGrab = null;}
	    dirty = true;
	    return(true);
	}
	return(super.mouseup(ev));
    }

    public boolean mousewheel(MouseWheelEvent ev) {
	sb.ch(ev.a * UI.scale(15));
	return(true);
    }

    public Object tooltip(Coord c, Widget prev) {
	if(mousecol != null)
	    return(mousecol.tip);
	return(super.tooltip(c, prev));
    }

    public void addentry(T entry) {
	entries.put(entry.id, entry);
	entrycont.add(entry, Coord.z);
	dirty = true;
	entryseq++;
	RosterWindow w = getparent(RosterWindow.class);
	if(w != null) w.memorize(entry.id);
    }

    public void delentry(UID id) {
	T entry = entries.remove(id);
	if(entry == null) return;
	clearGobHighlight(id);
	RosterWindow w = getparent(RosterWindow.class);
	if(w != null) w.unmemorize(id);
	entry.destroy();
	dirty = true;
	entryseq++;
    }

    public void delentry(T entry) {
	delentry(entry.id);
    }

    public abstract T parse(Object... args);

    public void uimsg(String msg, Object... args) {
	if(msg == "add") {
	    addentry(parse(args));
	} else if(msg == "upd") {
	    T entry = parse(args);
	    T old = entries.get(entry.id);
	    boolean wasMarked = (old != null) && old.mark.a;
	    delentry(entry.id);
	    addentry(entry);
	    if(wasMarked)
		entry.mark.set(true);
	} else if(msg == "rm") {
	    delentry((UID)args[0]);
	} else if(msg == "addto") {
	    GameUI gui = (GameUI)ui.getwidget(Utils.iv(args[0]));
	    if(gui == null || gui.menu == null) return;
	    Pagina pag = gui.menu.paginafor(ui.sess.getresv(args[1]));
	    if(pag == null) return;
	    RosterButton btn = (RosterButton)Loading.waitfor(pag::button);
	    if(btn == null) return;
	    btn.add(this);
	} else {
	    super.uimsg(msg, args);
	}
    }

    public abstract TypeButton button();

    public static TypeButton typebtn(Indir<Resource> up, Indir<Resource> dn) {
	Resource ur = Loading.waitfor(() -> up.get());
	Resource.Image ui = ur.layer(Resource.imgc);
	Resource.Image di = Loading.waitfor(() -> dn.get()).layer(Resource.imgc);
	TypeButton ret = new TypeButton(ui.scaled(), di.scaled(), ui.z);
	Resource.Tooltip tip = ur.layer(Resource.tooltip);
	if(tip != null)
	    ret.settip(tip.t);
	return(ret);
    }

    // --- Selection actions (reflection-driven) ---
    static final String[] MALE_FIELDS  = {"ram","bull","billy","hog","stallion","buck"};
    static final String[] CHILD_FIELDS = {"lamb","calf","kid","piglet","foal","fawn"};
    static final String[] PREG_FIELDS  = {"pregnant"};
    static final String[] LACT_FIELDS  = {"lactate"};
    static final String[] GRP_NAMES = {"White","Green","Red","Blue","Teal","Yellow","Purple","Orange"};

    static enum SelAction {
	PICK("Select by...", null),
	MALES("Males", fieldMatch(MALE_FIELDS, true)),
	FEMALES("Females", fieldMatch(MALE_FIELDS, false)),
	ADULTS("Adults", fieldMatch(CHILD_FIELDS, false)),
	CHILDREN("Children", fieldMatch(CHILD_FIELDS, true)),
	ALIVE("Alive", fieldMatch(new String[]{"dead"}, false)),
	DEAD("Dead", fieldMatch(new String[]{"dead"}, true)),
	PREGNANT("Pregnant", fieldMatch(PREG_FIELDS, true)),
	NOT_PREGNANT("Not Pregnant", fieldMatch(PREG_FIELDS, false)),
	LACTATING("Lactating", fieldMatch(LACT_FIELDS, true)),
	NOT_LACTATING("Not Lactating", fieldMatch(LACT_FIELDS, false)),
	BRANDED("Branded", ownedMatch(true)),
	NOT_BRANDED("Not Branded", ownedMatch(false)),
	CASTRATED("Castrated", null),
	NONCASTRATED("Noncastrated", null),
	BY_COLOR("By color...", null);
	public final String label;
	public final Predicate<Entry> pred;
	SelAction(String label, Predicate<Entry> pred) {this.label = label; this.pred = pred;}
    }

    private static Predicate<Entry> fieldMatch(String[] names, boolean val) {
	return e -> {
	    for(String n : names) {
		Field f = findField(e.getClass(), n);
		if(f == null) continue;
		try {
		    Object v = f.get(e);
		    if(v instanceof Boolean) return(((Boolean)v).booleanValue() == val);
		} catch(IllegalAccessException ignored) {}
	    }
	    return(false);
	};
    }

    private static Predicate<Entry> ownedMatch(boolean branded) {
	return e -> {
	    Field f = findField(e.getClass(), "owned");
	    if(f == null) return(false);
	    try {
		Object v = f.get(e);
		boolean owned;
		if(v instanceof Boolean) owned = (Boolean)v;
		else if(v instanceof Number) owned = ((Number)v).intValue() > 0;
		else return(false);
		return(branded == owned);
	    } catch(IllegalAccessException ignored) {}
	    return(false);
	};
    }

    private static Field findField(Class<?> cls, String name) {
	while(cls != null) {
	    try {
		Field f = cls.getDeclaredField(name);
		f.setAccessible(true);
		return(f);
	    } catch(NoSuchFieldException e) {
		cls = cls.getSuperclass();
	    }
	}
	return(null);
    }

    private boolean hasFieldAny(String[] names) {
	if(entries.isEmpty()) return(false);
	Entry probe = entries.values().iterator().next();
	for(String n : names) {
	    if(findField(probe.getClass(), n) != null) return(true);
	}
	return(false);
    }

    private static String grpName(int grp) {
	return((grp >= 0 && grp < GRP_NAMES.length) ? GRP_NAMES[grp] : ("Group " + grp));
    }

    private List<Integer> distinctGrps() {
	Set<Integer> seen = new TreeSet<>();
	for(Entry e : entries.values()) seen.add(e.grp);
	return(new ArrayList<>(seen));
    }

    private void applyGrp(int grp) {
	for(T e : entries.values()) {
	    if(e.grp == grp) e.mark.set(true);
	}
    }

    private volatile boolean scanning = false;
    private static final String CASTRATE_OPT = "Castrate";

    private boolean isMale(T e) {
	for(String n : MALE_FIELDS) {
	    Field f = findField(e.getClass(), n);
	    if(f == null) continue;
	    try {
		Object v = f.get(e);
		if((v instanceof Boolean) && ((Boolean)v).booleanValue()) return(true);
	    } catch(IllegalAccessException ignored) {}
	}
	return(false);
    }

    private void openRecolor() {
	if(ui == null || entries.isEmpty()) return;
	boolean anySelected = false;
	for(T e : entries.values()) { if(e.mark.a) {anySelected = true; break;} }
	if(!anySelected) {
	    if(ui.gui != null) ui.gui.error("Recolor: no cattle selected.");
	    return;
	}
	ui.root.add(new SetColorWnd(), ui.mc);
    }

    // Resource name identifying the cattle info window opened on right-click.
    // The window widget's Avaview child has avagob matching the cattle gob.
    private Widget findCattleInfoWindow(long gobid) {
	if(ui == null || ui.gui == null) return(null);
	for(Widget c = ui.gui.child; c != null; c = c.next) {
	    if(!(c instanceof Window)) continue;
	    if(hasMatchingAvaview(c, gobid)) return(c);
	}
	return(null);
    }

    private static boolean hasMatchingAvaview(Widget parent, long gobid) {
	for(Widget c = parent.child; c != null; c = c.next) {
	    if(c instanceof ProxyFrame) {
		Object inner = ((ProxyFrame<?>)c).ch;
		if(inner instanceof Avaview && ((Avaview)inner).avagob == gobid) return(true);
	    }
	    if(c instanceof Avaview && ((Avaview)c).avagob == gobid) return(true);
	    if(hasMatchingAvaview(c, gobid)) return(true);
	}
	return(false);
    }

    private static BuddyWnd.GroupSelector findGroupSelector(Widget root) {
	for(Widget c = root.child; c != null; c = c.next) {
	    if(c instanceof BuddyWnd.GroupSelector) return((BuddyWnd.GroupSelector)c);
	    BuddyWnd.GroupSelector r = findGroupSelector(c);
	    if(r != null) return(r);
	}
	return(null);
    }

    // Pre-flight + run accounting for a recolor batch. Pure data class so
    // unit tests can drive the workflow without wiring a UI.
    public static class RecolorReport {
	public final int selected;     // entries the user had marked
	public final int alreadyColor; // already at target color; skipped
	public final int offscreen;    // no CattleId gob in OCache; can't recolor
	public final int attempted;    // sent to the worker
	public int recolored;          // worker succeeded
	public int windowMissed;       // right-click sent, no info window opened
	public int grpMissed;          // window opened, no GroupSelector found
	RecolorReport(int selected, int alreadyColor, int offscreen, int attempted) {
	    this.selected = selected; this.alreadyColor = alreadyColor;
	    this.offscreen = offscreen; this.attempted = attempted;
	}
	public String summary() {
	    StringBuilder sb = new StringBuilder();
	    sb.append("Recolor: ").append(recolored).append("/").append(attempted).append(" done");
	    if(alreadyColor > 0) sb.append(", ").append(alreadyColor).append(" already that color");
	    if(offscreen > 0) sb.append(", ").append(offscreen).append(" not in render");
	    if(windowMissed > 0) sb.append(", ").append(windowMissed).append(" window timeout");
	    if(grpMissed > 0) sb.append(", ").append(grpMissed).append(" no color picker");
	    return(sb.append(".").toString());
	}
    }

    private void recolorSelected(int color) {
	if(scanning) return;
	if(ui == null || ui.sess == null || ui.gui == null || ui.gui.map == null) return;

	int selCount = 0, alreadyColor = 0;
	Set<UID> selectedOnscreen = new HashSet<>();
	List<Long> targets = new ArrayList<>();
	OCache oc = ui.sess.glob.oc;
	synchronized(oc) {
	    for(T e : entries.values()) {
		if(!e.mark.a) continue;
		selCount++;
		if(e.grp == color) { alreadyColor++; continue; }
	    }
	    for(Gob g : oc) {
		CattleId cid = g.getattr(CattleId.class);
		if(cid == null) continue;
		T e = entries.get(cid.id);
		if(e == null || !e.mark.a) continue;
		if(e.grp == color) continue;
		selectedOnscreen.add(cid.id);
		targets.add(g.id);
	    }
	}
	int needRecolor = selCount - alreadyColor;
	int offscreen = needRecolor - selectedOnscreen.size();
	RecolorReport report = new RecolorReport(selCount, alreadyColor, offscreen, targets.size());
	if(selCount == 0) { ui.gui.error("Recolor: no cattle selected."); return; }
	if(targets.isEmpty()) { ui.gui.error(report.summary()); return; }
	if(offscreen > 0) ui.gui.error("Recolor: " + offscreen + " selected cattle not in render; proceeding with " + targets.size() + ".");

	scanning = true;
	Thread t = new Thread(() -> {
	    try {
		for(long gobid : targets) {
		    if(ui == null) break;
		    recolorOne(gobid, color, report);
		}
		if(ui != null && ui.gui != null)
		    ui.gui.error(report.summary());
	    } finally {
		scanning = false;
	    }
	}, "CattleRecolor");
	t.setDaemon(true);
	t.start();
    }

    private void recolorOne(long gobid, int color, RecolorReport report) {
	Gob gob;
	OCache oc = ui.sess.glob.oc;
	synchronized(oc) {
	    gob = oc.getgob(gobid);
	}
	if(gob == null) { report.windowMissed++; return; }
	ui.gui.map.click(gob, 3);
	Widget cattleWnd = null;
	long deadline = System.currentTimeMillis() + 2000;
	while(System.currentTimeMillis() < deadline) {
	    cattleWnd = findCattleInfoWindow(gobid);
	    if(cattleWnd != null) break;
	    try { Thread.sleep(40); } catch(InterruptedException e) { report.windowMissed++; return; }
	}
	if(cattleWnd == null) { report.windowMissed++; return; }
	BuddyWnd.GroupSelector grp = findGroupSelector(cattleWnd);
	if(grp == null) { report.grpMissed++; ((Window)cattleWnd).reqclose(); return; }
	grp.select(color);
	((Window)cattleWnd).reqclose();
	report.recolored++;
	try { Thread.sleep(100); } catch(InterruptedException e) { /* done */ }
    }

    private class SetColorWnd extends Window {
	SetColorWnd() {
	    super(Coord.z, "Recolor selected");
	    justclose = true;
	    add(new Label("Pick a color:"), UI.scale(5), UI.scale(5));
	    final SetColorWnd self = this;
	    BuddyWnd.GroupSelector sel = new BuddyWnd.GroupSelector(-1) {
		    public void changed(int group) {
			recolorSelected(group);
			self.reqdestroy();
		    }
		};
	    add(sel, UI.scale(5), UI.scale(25));
	    pack();
	}
    }

    private void scanCastration(boolean selectCastrated) {
	if(scanning) return;
	RosterWindow wnd = getparent(RosterWindow.class);
	if(wnd == null || ui == null || ui.sess == null || ui.gui == null || ui.gui.map == null) return;
	List<Gob> targets = new ArrayList<>();
	OCache oc = ui.sess.glob.oc;
	synchronized(oc) {
	    for(Gob g : oc) {
		CattleId cid = g.getattr(CattleId.class);
		if(cid == null) continue;
		T e = entries.get(cid.id);
		if(e == null || !isMale(e)) continue;
		if(wnd.castrated.get(cid.id) == Boolean.TRUE) continue;
		targets.add(g);
	    }
	}
	if(targets.isEmpty()) {
	    applyCastratedSelection(wnd, selectCastrated);
	    return;
	}
	scanning = true;
	Thread t = new Thread(() -> {
	    try {
		for(Gob g : targets) {
		    if(ui == null) break;
		    scanOne(g, wnd);
		}
	    } finally {
		scanning = false;
		applyCastratedSelection(wnd, selectCastrated);
	    }
	}, "CastrationScan");
	t.setDaemon(true);
	t.start();
    }

    private void scanOne(Gob gob, RosterWindow wnd) {
	CattleId cid = gob.getattr(CattleId.class);
	if(cid == null) return;
	AtomicReference<FlowerMenu> captured = new AtomicReference<>();
	Object lock = new Object();
	Subscription sub = Reactor.FLOWER.subscribe(fm -> {
	    if(captured.compareAndSet(null, fm)) {
		synchronized(lock) { lock.notifyAll(); }
	    }
	});
	try {
	    FlowerMenu.lastGob(gob);
	    ui.gui.map.click(gob, 3);
	    long deadline = System.currentTimeMillis() + 2000;
	    synchronized(lock) {
		while(captured.get() == null) {
		    long rem = deadline - System.currentTimeMillis();
		    if(rem <= 0) break;
		    try { lock.wait(rem); } catch(InterruptedException e) { return; }
		}
	    }
	    FlowerMenu fm = captured.get();
	    if(fm == null) return;
	    boolean hasCastrate = false;
	    for(String opt : fm.options) {
		if(CASTRATE_OPT.equals(opt)) { hasCastrate = true; break; }
	    }
	    wnd.castrated.put(cid.id, !hasCastrate);
	    long attachDeadline = System.currentTimeMillis() + 500;
	    while(fm.ui == null && System.currentTimeMillis() < attachDeadline) {
		Thread.sleep(10);
	    }
	    if(fm.ui != null) fm.choose(-1);
	    Thread.sleep(80);
	} catch(InterruptedException ignored) {
	} finally {
	    sub.unsubscribe();
	}
    }

    private void applyCastratedSelection(RosterWindow wnd, boolean selectCastrated) {
	applyMark(e -> true, false);
	int hits = 0;
	for(T e : entries.values()) {
	    Boolean v = wnd.castrated.get(e.id);
	    if(v == null) continue;
	    if(selectCastrated == v.booleanValue()) { e.mark.set(true); hits++; }
	}
	if(hits == 0 && ui != null && ui.gui != null) {
	    String what = selectCastrated ? "castrated" : "noncastrated";
	    ui.gui.error("No " + what + " animals found within render distance.");
	}
    }

    private static final Map<SelAction, Tex> selDropLabelTex = new EnumMap<>(SelAction.class);
    private static Tex selDropLabel(SelAction it) {
	Tex t = selDropLabelTex.get(it);
	if(t == null) selDropLabelTex.put(it, t = SB_FND.render(it.label).tex());
	return(t);
    }

    private class SelDrop extends Dropbox<SelAction> {
	private boolean suppress = false;
	SelDrop(int w) { super(w, 12, Math.max(UI.scale(20), SB_FND.height() + UI.scale(4))); }
	private List<SelAction> available() {
	    return(Arrays.asList(SelAction.values()));
	}
	protected SelAction listitem(int i) { return(available().get(i)); }
	protected int listitems() { return(available().size()); }
	protected void drawitem(GOut g, SelAction it, int i) {
	    Tex t = selDropLabel(it);
	    g.image(t, new Coord(UI.scale(4), (itemh - t.sz().y) / 2));
	}
	public void change(SelAction it) {
	    super.change(it);
	    if(suppress) return;
	    if(it == null || it == SelAction.PICK) return;
	    if(it == SelAction.BY_COLOR) {
		List<Integer> grps = distinctGrps();
		if(!grps.isEmpty()) ui.root.add(new ColorPickWnd(grps), ui.mc);
	    } else if(it == SelAction.CASTRATED) {
		scanCastration(true);
	    } else if(it == SelAction.NONCASTRATED) {
		scanCastration(false);
	    } else if(it.pred != null) {
		applyMark(e -> true, false);
		applyMark(it.pred, true);
	    }
	    suppress = true;
	    change(SelAction.PICK);
	    suppress = false;
	}
    }

    // Right-aligned count label with reserved width for COUNT_RESERVED_CHARS characters.
    private static class CountLabel extends Widget {
	private Tex tex;
	private final int reservedW;

	CountLabel() {
	    super(Coord.z);
	    StringBuilder wide = new StringBuilder();
	    for(int i = 0; i < COUNT_RESERVED_CHARS; i++) wide.append('8');
	    reservedW = SB_FND.strsize(wide.toString()).x;
	    settext("0/0");
	}

	void settext(String s) {
	    if(tex != null) tex.dispose();
	    tex = SB_FND.render(s).tex();
	    resize(new Coord(Math.max(reservedW, tex.sz().x), tex.sz().y));
	}

	public void draw(GOut g) {
	    if(tex == null) return;
	    g.image(tex, new Coord(sz.x - tex.sz().x, (sz.y - tex.sz().y) / 2));
	}
    }

    private class ColorPickWnd extends Window {
	ColorPickWnd(List<Integer> grps) {
	    super(Coord.z, "Select by color");
	    justclose = true;
	    int y = 0;
	    for(int g : grps) {
		final int grp = g;
		add(new Button(UI.scale(180), grpName(grp), false).action(() -> {
		    applyMark(e -> true, false);
		    applyGrp(grp);
		    reqdestroy();
		}), 0, y);
		y += UI.scale(24);
	    }
	    pack();
	}
    }
}
