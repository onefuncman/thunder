/* Preprocessed source code */
package haven.res.ui.croster;

import haven.*;
import haven.render.*;
import java.util.*;
import java.util.function.*;
import java.lang.reflect.Field;
import haven.MenuGrid.Pagina;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;

@haven.FromResource(name = "ui/croster", version = 77)
public abstract class CattleRoster <T extends Entry> extends Widget {
    public static final int WIDTH = UI.scale(900);
    public static final Comparator<Entry> namecmp = (a, b) -> a.name.compareTo(b.name);
    public static final int HEADH = UI.scale(40);
    public final Map<UID, T> entries = new HashMap<>();
    public final Scrollbar sb;
    public final Widget entrycont;
    public int entryseq = 0;
    public List<T> display = Collections.emptyList();
    public boolean dirty = true;
    public Comparator<? super T> order = namecmp;
    public Column<? super T> mousecol, ordercol;
    public boolean revorder;
    private Button btnRemove;
    private Button btnAll, btnNone, btnInvert, btnClear;
    private ToggleBtn btnHighlight;
    private Dropbox<SelAction> selDrop;
    private Label countLbl;
    private int lastSel = -1, lastTotal = -1;
    private boolean highlighting = false;

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
	btnHighlight = add(new ToggleBtn(bw, "Highlight").action(this::highlightSel),
			   btnInvert.pos("ur").adds(5, 0));
	btnHighlight.settip("Toggle persistent highlight for selected cattle");
	selDrop = add(new SelDrop(UI.scale(140)), btnHighlight.pos("ur").adds(5, 0));
	selDrop.change(SelAction.PICK);
	countLbl = add(new Label("0/0"), selDrop.pos("ur").adds(8, 4));
	btnClear = add(new Button(UI.scale(100), "Refresh Names", false).action(this::clearNames),
		       countLbl.pos("ur").adds(10, -4));
	btnClear.settip("Prune names to current roster");
	btnRemove = adda(new Button(UI.scale(150), "Remove selected", false).action(() -> {
	    Collection<Object> args = new ArrayList<>();
	    for(Entry entry : this.entries.values()) {
		if(entry.mark.a)
		    args.add(entry.id);
	    }
	    wdgmsg("rm", args.toArray(new Object[0]));
	}), entrycont.pos("br").adds(0, 5), 1, 0);
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
	btnHighlight.c = btnInvert.pos("ur").adds(5, 0);
	selDrop.c = btnHighlight.pos("ur").adds(5, 0);
	countLbl.c = selDrop.pos("ur").adds(8, 4);
	btnClear.c = countLbl.pos("ur").adds(10, -4);
	btnRemove.c = entrycont.pos("br").adds(-btnRemove.sz.x, 5);
	dirty = true;
    }

    private void applyMark(Predicate<? super T> p, boolean val) {
	for(T e : entries.values()) {
	    if(p.test(e)) e.mark.set(val);
	}
    }

    private void invertSel() {
	for(T e : entries.values()) e.mark.set(!e.mark.a);
    }

    private void highlightSel() {
	highlighting = !highlighting;
	btnHighlight.setOn(highlighting);
	if(!highlighting) clearAllCattleHighlights();
    }

    private void clearAllCattleHighlights() {
	if(ui == null || ui.sess == null) return;
	for(Gob g : ui.sess.glob.oc) {
	    if(g.getattr(CattleId.class) == null) continue;
	    GobHighlight h = g.getattr(GobHighlight.class);
	    if(h != null && h.isPersistent()) {
		h.setPersistent(false);
		g.delattr(GobHighlight.class);
	    }
	}
    }

    private void syncHighlights() {
	if(ui == null || ui.sess == null) return;
	Set<UID> marked = new HashSet<>();
	for(T e : entries.values()) {
	    if(e.mark.a) marked.add(e.id);
	}
	for(Gob g : ui.sess.glob.oc) {
	    CattleId cid = g.getattr(CattleId.class);
	    if(cid == null) continue;
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

    private void clearNames() {
	RosterWindow w = getparent(RosterWindow.class);
	if(w != null) w.refreshMemorized();
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

    public void tick(double dt) {
	if(dirty) {
	    List<T> ndisp = new ArrayList<>(entries.values());
	    ndisp.sort(order);
	    redisplay(ndisp);
	    dirty = false;
	}
	int sel = 0, tot = entries.size();
	for(T e : entries.values()) if(e.mark.a) sel++;
	if(sel != lastSel || tot != lastTotal) {
	    lastSel = sel; lastTotal = tot;
	    countLbl.settext(sel + "/" + tot);
	}
	if(highlighting) syncHighlights();
	super.tick(dt);
    }

    protected abstract List<Column<? super T>> cols();

    public void drawcols(GOut g) {
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
	for(Column<? super T> col : cols()) {
	    if((c.x >= col.x) && (c.x < col.x + col.w))
		return(col);
	}
	return(null);
    }

    public void mousemove(MouseMoveEvent ev) {
	super.mousemove(ev);
	mousecol = onhead(ev.c);
    }

    public boolean mousedown(MouseDownEvent ev) {
	Column<? super T> col = onhead(ev.c);
	if(ev.b == 1) {
	    if((col != null) && (col.order != null)) {
		revorder = (col == ordercol) ? !revorder : false;
		this.order = col.order;
		if(revorder)
		    this.order = this.order.reversed();
		ordercol = col;
		dirty = true;
		return(true);
	    }
	}
	return(super.mousedown(ev));
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
	    delentry(entry.id);
	    addentry(entry);
	} else if(msg == "rm") {
	    delentry((UID)args[0]);
	} else if(msg == "addto") {
	    GameUI gui = (GameUI)ui.getwidget(Utils.iv(args[0]));
	    Pagina pag = gui.menu.paginafor(ui.sess.getresv(args[1]));
	    RosterButton btn = (RosterButton)Loading.waitfor(pag::button);
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

    private class SelDrop extends Dropbox<SelAction> {
	private boolean suppress = false;
	SelDrop(int w) { super(w, 12, UI.scale(16)); }
	private List<SelAction> available() {
	    return(Arrays.asList(SelAction.values()));
	}
	protected SelAction listitem(int i) { return(available().get(i)); }
	protected int listitems() { return(available().size()); }
	protected void drawitem(GOut g, SelAction it, int i) { g.atext(it.label, UI.scale(3, 8), 0, 0.5); }
	public void change(SelAction it) {
	    super.change(it);
	    if(suppress) return;
	    if(it == null || it == SelAction.PICK) return;
	    if(it == SelAction.BY_COLOR) {
		List<Integer> grps = distinctGrps();
		if(!grps.isEmpty()) ui.root.add(new ColorPickWnd(grps), ui.mc);
	    } else if(it.pred != null) {
		applyMark(e -> true, false);
		applyMark(it.pred, true);
	    }
	    suppress = true;
	    change(SelAction.PICK);
	    suppress = false;
	}
    }

    private static class ToggleBtn extends Button {
	private boolean on = false;
	ToggleBtn(int w, String text) { super(w, text, false); }
	public ToggleBtn action(Runnable action) { super.action(action); return(this); }
	public void setOn(boolean v) {
	    if(on != v) { on = v; redraw(); }
	}
	public void draw(BufferedImage img) {
	    super.draw(img);
	    if(on) {
		Graphics g = img.getGraphics();
		g.setColor(new Color(80, 220, 80, 90));
		g.fillRect(UI.scale(4), UI.scale(4), sz.x - UI.scale(8), sz.y - UI.scale(8));
		g.setColor(new Color(140, 255, 140, 200));
		g.drawRect(UI.scale(4), UI.scale(4), sz.x - UI.scale(9), sz.y - UI.scale(9));
		g.dispose();
	    }
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
