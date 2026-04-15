/* Preprocessed source code */
package haven.res.ui.croster;

import haven.*;
import haven.render.*;
import java.util.*;
import java.util.function.*;
import haven.MenuGrid.Pagina;
import java.awt.Color;
import java.awt.image.BufferedImage;

@haven.FromResource(name = "ui/croster", version = 77)
public class RosterWindow extends Window {
    public static final Map<Glob, RosterWindow> rosters = new HashMap<>();
    public static int rmseq = 0;
    public int btny = 0;
    public List<TypeButton> buttons = new ArrayList<>();
    public final Set<UID> memorized = new HashSet<>();
    public final Map<UID, Boolean> castrated = Collections.synchronizedMap(new HashMap<>());
    private boolean collapsed = false;
    private Coord uncollapsedSz;
    private Coord requestedSz;
    private long lastCapClick = 0;
    private CattleRoster lastShown = null;
    private boolean packing = false;
    private static final String PREF_HIGHLIGHT = "croster/highlight";
    private static final String PREF_HIDE_CLOSED = "croster/hide-when-closed";
    private static final String BASE_TITLE = "Cattle Roster";
    private static final String CAP_TIP = "Double-click title to collapse/expand";
    private static final Resource handcurs = Resource.local().loadwait("gfx/hud/curs/hand");
    public boolean highlighting;
    public boolean hideWhenClosed;
    private CheckBox cbHighlight, cbHideClosed;

    RosterWindow() {
	super(Coord.z, BASE_TITLE, true);
	highlighting = Utils.getprefb(PREF_HIGHLIGHT, false);
	hideWhenClosed = Utils.getprefb(PREF_HIDE_CLOSED, false);
	cbHighlight = add(new CheckBox("Highlight") {
		{a = highlighting;}
		public void set(boolean v) {
		    this.a = v;
		    highlighting = v;
		    Utils.setprefb(PREF_HIGHLIGHT, v);
		    if(!v) clearAllHighlights();
		}
	    });
	cbHighlight.settip("Highlight selected cattle/animals with a glow");
	cbHideClosed = add(new CheckBox("Hide when closed") {
		{a = hideWhenClosed;}
		public void set(boolean v) {
		    this.a = v;
		    hideWhenClosed = v;
		    Utils.setprefb(PREF_HIDE_CLOSED, v);
		    if(v && !visible) clearAllHighlights();
		}
	    });
	cbHideClosed.settip("Hide names, selections, and highlights while the roster window is closed");
    }

    public void clearAllHighlights() {
	for(CattleRoster ch : children(CattleRoster.class))
	    ch.clearAllCattleHighlights();
    }

    public void syncAllHighlights() {
	for(CattleRoster ch : children(CattleRoster.class))
	    ch.syncHighlights();
    }

    private boolean visualsActive() {
	return(visible || !hideWhenClosed);
    }

    public void tick(double dt) {
	if(highlighting && visualsActive()) syncAllHighlights();
	super.tick(dt);
    }

    private Tex chevTex() {
	String s = collapsed ? "\u25B2" : "\u25BC";
	return(Window.DefaultDeco.cf.render(s).tex());
    }

    @Override
    protected Deco makedeco() {
	return(new ChevronDeco().dragsize(true));
    }

    private class ChevronDeco extends DefaultDeco {
	ChevronDeco() { super(true); }
	@Override
	protected void drawframe(GOut g) {
	    super.drawframe(g);
	    Tex chev = chevTex();
	    int x = sz.x - cbtn.sz.x - chev.sz().x - UI.scale(6);
	    int y = (cbtn.sz.y - chev.sz().y) / 2;
	    g.image(chev, new Coord(x, y));
	}
    }

    public void memorize(UID id) {
	synchronized(memorized) {
	    memorized.add(id);
	}
	rmseq++;
    }

    public boolean isMemorized(UID id) {
	synchronized(memorized) {
	    return(memorized.contains(id));
	}
    }

    public void clearMemorized() {
	synchronized(memorized) {
	    memorized.clear();
	}
	rmseq++;
    }

    public void refreshMemorized() {
	synchronized(memorized) {
	    memorized.clear();
	    for(CattleRoster<?> ch : children(CattleRoster.class)) {
		for(UID id : ch.entries.keySet())
		    memorized.add(id);
	    }
	}
	rmseq++;
    }

    @Override
    public boolean show(boolean show) {
	boolean wasVisible = visible;
	boolean ret = super.show(show);
	if(show && !wasVisible) refreshMemorized();
	if(!show && wasVisible && hideWhenClosed) clearAllHighlights();
	return(ret);
    }

    public void show(CattleRoster rost) {
	for(CattleRoster ch : children(CattleRoster.class))
	    ch.show(ch == rost);
	lastShown = rost;
    }

    public void addroster(CattleRoster rost) {
	if(btny == 0)
	    btny = rost.sz.y + UI.scale(10);
	add(rost, Coord.z);
	TypeButton btn = this.add(rost.button());
	btn.action(() -> show(rost));
	buttons.add(btn);
	buttons.sort((a, b) -> (a.order - b.order));
	relayoutButtons();
	buttons.get(0).click();
	for(Object oe : rost.entries.values()) {
	    Entry e = (Entry) oe;
	    memorize(e.id);
	}
	pack();
	rmseq++;
    }

    private void relayoutButtons() {
	int x = 0;
	for(Widget wdg : buttons) {
	    wdg.move(new Coord(x, btny));
	    x += wdg.sz.x + UI.scale(10);
	}
    }

    @Override
    public void pack() {
	packing = true;
	try { super.pack(); } finally { packing = false; }
	relayoutCheckboxes();
    }

    private void relayoutCheckboxes() {
	if(cbHighlight == null || cbHideClosed == null || collapsed) return;
	Area ca = (deco != null) ? deco.contarea() : null;
	int rightEdge = (ca != null) ? ca.sz().x : sz.x;
	int cbY = btny + UI.scale(6);
	cbHighlight.move(new Coord(rightEdge - cbHighlight.sz.x, cbY));
	cbHideClosed.move(new Coord(cbHighlight.c.x - cbHideClosed.sz.x - UI.scale(12), cbY));
    }

    @Override
    public void resize(Coord sz) {
	int minW = UI.scale(collapsed ? 80 : 180), minH = UI.scale(16);
	if(!collapsed) minH = UI.scale(140);
	if(sz.x < minW) sz = new Coord(minW, sz.y);
	if(sz.y < minH) sz = new Coord(sz.x, minH);
	requestedSz = sz;
	super.resize(sz);
	if(packing || collapsed) return;
	Area ca = (deco != null) ? deco.contarea() : null;
	if(ca == null) return;
	int btnH = UI.scale(28);
	int availY = ca.sz().y - btnH - UI.scale(12);
	if(availY < UI.scale(100)) availY = UI.scale(100);
	int availX = ca.sz().x;
	for(CattleRoster ch : children(CattleRoster.class)) {
	    Coord nsz = new Coord(availX, availY);
	    if(!ch.sz.equals(nsz)) ch.resizeRoster(nsz);
	}
	btny = availY + UI.scale(4);
	relayoutButtons();
	relayoutCheckboxes();
    }

    public void toggleCollapsed() {
	if(!collapsed) {
	    if(requestedSz != null)
		uncollapsedSz = new Coord(requestedSz.x, requestedSz.y);
	    lastShown = null;
	    for(CattleRoster ch : children(CattleRoster.class)) {
		if(ch.visible) lastShown = ch;
		ch.hide();
	    }
	    for(TypeButton b : buttons) b.hide();
	    cbHighlight.hide();
	    cbHideClosed.hide();
	    collapsed = true;
	    int capW = Window.cf.render(BASE_TITLE).sz().x;
	    int chevW = chevTex().sz().x;
	    int w = capW + chevW + Window.cbtni[0].getWidth() + UI.scale(28);
	    resize(new Coord(w, UI.scale(16)));
	} else {
	    collapsed = false;
	    for(TypeButton b : buttons) b.show();
	    cbHighlight.show();
	    cbHideClosed.show();
	    if(lastShown != null) show(lastShown);
	    else if(!buttons.isEmpty()) buttons.get(0).click();
	    Coord target = (uncollapsedSz != null) ? uncollapsedSz : new Coord(sz.x, UI.scale(300));
	    resize(target);
	}
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
	if(onCaptionBar(c)) return(CAP_TIP);
	return(super.tooltip(c, prev));
    }

    @Override
    public Resource getcurss(Coord c) {
	if(onCaptionBar(c)) return(handcurs);
	return(super.getcurss(c));
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
	if(super.keydown(ev)) return(true);
	if(ev.code == ev.awt.VK_SPACE) {
	    toggleCollapsed();
	    return(true);
	}
	return(false);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
	if((ev.b == 1) && onCaptionBar(ev.c)) {
	    long now = System.currentTimeMillis();
	    if(now - lastCapClick < 400) {
		toggleCollapsed();
		lastCapClick = 0;
		return(true);
	    }
	    lastCapClick = now;
	}
	return(super.mousedown(ev));
    }

    private boolean onCaptionBar(Coord c) {
	if(c.x < 0 || c.x >= sz.x || c.y < 0) return(false);
	int h = UI.scale(30);
	if(deco instanceof DefaultDeco) {
	    DefaultDeco dd = (DefaultDeco) deco;
	    if(dd.cpsz.y > 0) h = dd.cpsz.y;
	}
	return(c.y < h);
    }

    public void wdgmsg(Widget sender, String msg, Object... args) {
	if((sender == this) && msg.equals("close")) {
	    show(false);
	    return;
	}
	super.wdgmsg(sender, msg, args);
    }

    @Override
    public void hide() {
	boolean wasVisible = visible;
	super.hide();
	if(wasVisible && hideWhenClosed) clearAllHighlights();
    }
}

/* >pagina: RosterButton$Fac */
