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
	int zoff = Math.max(0, haven.CFG.CROSTER_NAME_Z.get());
	Coord sc = Homo3D.obj2view(new Coord3f(0, 0, zoff), state, Area.sized(g.sz())).round2();
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
	    if((rnm != null) && memorized) {
		Coord nmc = sc.sub(rnm.sz().x / 2, -rnm.sz().y);
		g.image(rnm, nmc);
		if((entry != null) && entry.mark.a)
		    g.image(CheckBox.smark, nmc.sub(CheckBox.smark.sz().x, 0));
	    }
	    Entry e = entry;
	    if((e != null) && e.mark.a) {
		Coord fc = Homo3D.obj2view(new Coord3f(0, 0, 0), state, Area.sized(g.sz())).round2();
		int r = UI.scale(18);
		g.chcolor(80, 255, 80, 140);
		g.frect2(fc.sub(r, r / 3), fc.add(r, r / 3));
		g.chcolor();
	    }
	}
    }
}
