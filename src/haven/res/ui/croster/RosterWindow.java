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
    private boolean collapsed = false;
    private Coord uncollapsedSz;
    private long lastCapClick = 0;
    private CattleRoster lastShown = null;
    private boolean packing = false;

    RosterWindow() {
	super(Coord.z, "Cattle Roster", true);
    }

    @Override
    protected Deco makedeco() {
	return(new DefaultDeco(true).dragsize(true));
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
    }

    @Override
    public void resize(Coord sz) {
	int minW = UI.scale(300), minH = UI.scale(40);
	if(!collapsed) minH = UI.scale(140);
	if(sz.x < minW) sz = new Coord(minW, sz.y);
	if(sz.y < minH) sz = new Coord(sz.x, minH);
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
    }

    public void toggleCollapsed() {
	collapsed = !collapsed;
	if(collapsed) {
	    uncollapsedSz = new Coord(sz.x, sz.y);
	    lastShown = null;
	    for(CattleRoster ch : children(CattleRoster.class)) {
		if(ch.visible) lastShown = ch;
		ch.hide();
	    }
	    for(TypeButton b : buttons) b.hide();
	    pack();
	} else {
	    for(TypeButton b : buttons) b.show();
	    if(lastShown != null) show(lastShown);
	    else if(!buttons.isEmpty()) buttons.get(0).click();
	    if(uncollapsedSz != null) resize(uncollapsedSz);
	    else pack();
	}
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
	return(c.y >= 0 && c.y < UI.scale(30));
    }

    public void wdgmsg(Widget sender, String msg, Object... args) {
	if((sender == this) && msg.equals("close")) {
	    this.hide();
	    return;
	}
	super.wdgmsg(sender, msg, args);
    }
}

/* >pagina: RosterButton$Fac */
