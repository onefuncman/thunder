package me.ender.minimap;

import haven.*;
import me.ender.QuestCondition;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SMarker extends Marker {
    public final UID oid;
    public final Resource.Saved res;
    public byte[] data;

    public List<QuestCondition> questConditions = new ArrayList<>();
    public Iterator<QuestCondition> questIterator;

    public SMarker(long seg, Coord tc, String nm, UID oid, Resource.Saved res, byte[] data) {
	super(seg, tc, nm);
	this.oid = oid;
	this.res = res;
	this.data = data;
	questIterator = Utils.circularIterator(questConditions);
    }

    @Override
    public boolean equals(Object o) {
	if(this == o) return true;
	if(o == null || getClass() != o.getClass()) return false;
	if(!super.equals(o)) return false;
	SMarker sMarker = (SMarker) o;
	return Objects.equals(oid, sMarker.oid) && res.equals(sMarker.res);
    }

    // Fallback draw path used when no parameterized icon is available (e.g. no widget context).
    // DisplayMarker now owns the normal rendering via GobIcon.getfac(res).create(owner, res, data).
    @Override
    public void draw(GOut g, Coord c, Text tip, final float scale, final MapFile file) {
	try {
	    final Resource res = this.res.loadsaved();
	    final Resource.Image img = res.layer(Resource.imgc);
	    if(img == null) {return;}
	    final Resource.Neg neg = res.layer(Resource.negc);
	    final Coord cc = neg != null ? neg.cc : img.ssz.div(2);
	    final Coord ul = c.sub(cc);
	    if(CFG.QUESTHELPER_HIGHLIGHT_QUESTGIVERS.get() && !questConditions.isEmpty()) {
		for(QuestCondition item : new ArrayList<>(questConditions)) {
		    g.chcolor(item.questGiverMarkerColor());
		    g.fellipse(c, img.ssz.div(2).sub(1, 1));
		}
		g.chcolor();
	    }
	    g.image(img, ul);
	    if(tip != null && CFG.MMAP_SHOW_MARKER_NAMES.get()) {
		g.aimage(tip.tex(), c.addy(UI.scale(3)), 0.5, 0);
	    }
	} catch (Loading ignored) {}
    }

    @Override
    public Area area() {
	try {
	    final Resource res = this.res.loadsaved();
	    final Resource.Image img = res.layer(Resource.imgc);
	    if(img == null) {return null;}
	    final Resource.Neg neg = res.layer(Resource.negc);
	    final Coord cc = neg != null ? neg.cc : img.ssz.div(2);
	    return Area.sized(cc.inv(), img.ssz);
	} catch (Loading ignored) {
	    return null;
	}
    }

    @Override
    public int hashCode() {
	return Objects.hash(super.hashCode(), oid, res);
    }
}
