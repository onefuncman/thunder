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
public class CattleId extends GAttrib implements RenderTree.Node, PView.Render2D {
    public final UID id;

    /* Top-of-model Z per animal resource, in world units. Captured from
     * each mesh's bind-pose vertex positions via State Inspector →
     * Capture Heights (the meshMaxZ column). This base is projected to
     * screen; the config slider then lifts the name by a fixed pixel
     * amount so the gap stays stable regardless of camera zoom.
     *
     * Two key shapes:
     *   "gfx/kritter/horse/mare"           - bare resname (no variants)
     *   "gfx/kritter/sheep/sheep[ram]"     - variant, selected when the
     *                                        composite resId contains "/ram"
     * modelTopZ() tries variant-aware keys first, then bare resname. */
    private static final Map<String, Float> MODEL_TOP_Z = new HashMap<>();
    /* Order matters: checked in sequence, first substring match in the
     * composite resId wins. */
    private static final String[] VARIANT_MARKERS = {"/ram", "/ewe", "/bull", "/cow"};
    static {
	MODEL_TOP_Z.put("gfx/kritter/cattle/calf",               8.29f);
	MODEL_TOP_Z.put("gfx/kritter/cattle/cattle[bull]",      15.64f);
	MODEL_TOP_Z.put("gfx/kritter/cattle/cattle[cow]",       13.48f);
	MODEL_TOP_Z.put("gfx/kritter/cattle/cattle",            13.48f);
	MODEL_TOP_Z.put("gfx/kritter/goat/billy",               11.27f);
	MODEL_TOP_Z.put("gfx/kritter/goat/kid",                  7.17f);
	MODEL_TOP_Z.put("gfx/kritter/goat/nanny",                9.91f);
	MODEL_TOP_Z.put("gfx/kritter/horse/foal",               12.23f);
	MODEL_TOP_Z.put("gfx/kritter/horse/mare",               18.28f);
	MODEL_TOP_Z.put("gfx/kritter/horse/stallion",           19.74f);
	MODEL_TOP_Z.put("gfx/kritter/pig/hog",                   8.42f);
	MODEL_TOP_Z.put("gfx/kritter/pig/piglet",                4.53f);
	MODEL_TOP_Z.put("gfx/kritter/pig/sow",                   7.36f);
	MODEL_TOP_Z.put("gfx/kritter/reindeer/teimdeerbull",    19.76f);
	MODEL_TOP_Z.put("gfx/kritter/reindeer/teimdeercow",     13.04f);
	MODEL_TOP_Z.put("gfx/kritter/reindeer/teimdeerkid",      8.54f);
	MODEL_TOP_Z.put("gfx/kritter/sheep/lamb",                6.94f);
	MODEL_TOP_Z.put("gfx/kritter/sheep/sheep[ram]",         11.07f);
	MODEL_TOP_Z.put("gfx/kritter/sheep/sheep[ewe]",         10.52f);
	MODEL_TOP_Z.put("gfx/kritter/sheep/sheep",              10.52f);
    }

    private float modelTopZ() {
	try {
	    Resource res = gob.getres();
	    if(res == null) return(0f);
	    String name = res.name;
	    String resId = (gob.drawable instanceof Composite) ? ((Composite)gob.drawable).resId() : null;
	    if(resId != null) {
		for(String marker : VARIANT_MARKERS) {
		    if(resId.contains(marker)) {
			Float v = MODEL_TOP_Z.get(name + "[" + marker.substring(1) + "]");
			if(v != null) return(v);
		    }
		}
	    }
	    Float v = MODEL_TOP_Z.get(name);
	    if(v != null) return(v);
	} catch(Loading l) {}
	return(0f);
    }

    public CattleId(Gob gob, UID id) {
	super(gob);
	this.id = id;
    }

    public static void parse(Gob gob, Message dat) {
	UID id = UID.of(dat.int64());
	gob.setattr(new CattleId(gob, id));
    }

    private int rmseq = 0, entryseq = 0;
    private RosterWindow wnd = null;
    private CattleRoster<?> roster = null;
    private Entry entry = null;
    public Entry entry() {
	if((entry == null) || ((roster != null) && (roster.entryseq != entryseq))) {
	    if(rmseq != RosterWindow.rmseq) {
		synchronized(RosterWindow.rosters) {
		    RosterWindow wnd = RosterWindow.rosters.get(gob.glob);
		    if(wnd != null) {
			for(CattleRoster<?> ch : wnd.children(CattleRoster.class)) {
			    if(ch.entries.get(this.id) != null) {
				this.wnd = wnd;
				this.roster = ch;
				this.rmseq = RosterWindow.rmseq;
				break;
			    }
			}
		    }
		}
	    }
	    if(roster != null)
		this.entry = roster.entries.get(this.id);
	}
	return(entry);
    }

    private String lnm;
    private int lgrp;
    private Tex rnm;
    public void draw(GOut g, Pipe state) {
	Coord sc = Homo3D.obj2view(new Coord3f(0, 0, modelTopZ()), state, Area.sized(g.sz())).round2();
	sc = sc.sub(0, UI.scale(Math.max(0, haven.CFG.CROSTER_NAME_Z.get())));
	if(sc.isect(Coord.z, g.sz())) {
	    Entry entry = entry();
	    int grp = (entry != null) ? entry.grp : 0;
	    String name = (entry != null) ? entry.name : null;
	    if((name != null) && ((rnm == null) || !name.equals(lnm) || (grp != lgrp))) {
		Color col = BuddyWnd.gc[grp];
		rnm = new TexI(Utils.outline2(Text.render(name, col).img, Utils.contrast(col)));
		lnm = name;
		lgrp = grp;
	    }
	    boolean memorized = (wnd != null) && wnd.isMemorized(id);
	    boolean showVisuals = (wnd != null) && (wnd.visible || !wnd.hideWhenClosed);
	    if((rnm != null) && memorized && showVisuals) {
		Coord nmc = sc.sub(rnm.sz().x / 2, -rnm.sz().y);
		g.image(rnm, nmc);
		if((entry != null) && entry.mark.a)
		    g.image(CheckBox.smark, nmc.sub(CheckBox.smark.sz().x, 0));
	    }
	}
    }
}
