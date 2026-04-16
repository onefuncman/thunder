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

    // Populated by DisplayMarker once a widget context is available so
    // parameterized icons (loftar's MapFile v3 byte[] data) can be built
    // via GobIcon.getfac(res).create(...). Null falls back to static image.
    public transient OwnerContext owner;
    private transient GobIcon.Icon iconCache;
    private transient byte[] iconCacheData;

    public SMarker(long seg, Coord tc, String nm, UID oid, Resource.Saved res, byte[] data) {
	super(seg, tc, nm);
	this.oid = oid;
	this.res = res;
	this.data = data;
	questIterator = Utils.circularIterator(questConditions);
    }

    private GobIcon.Icon icon() {
	if(owner == null) {return null;}
	if(iconCache == null || iconCacheData != data) {
	    Resource r = this.res.loadsaved();
	    iconCache = GobIcon.getfac(r).create(owner, r, new MessageBuf(data == null ? new byte[0] : data));
	    iconCacheData = data;
	}
	return iconCache;
    }

    @Override
    public boolean equals(Object o) {
	if(this == o) return true;
	if(o == null || getClass() != o.getClass()) return false;
	if(!super.equals(o)) return false;
	SMarker sMarker = (SMarker) o;
	return Objects.equals(oid, sMarker.oid) && res.equals(sMarker.res);
    }

    @Override
    public void draw(GOut g, Coord c, Text tip, final float scale, final MapFile file) {
	try {
	    final Resource res = this.res.loadsaved();
	    if(CFG.QUESTHELPER_HIGHLIGHT_QUESTGIVERS.get() && !questConditions.isEmpty()) {
		Resource.Image qimg = res.layer(Resource.imgc);
		if(qimg != null) {
		    for(QuestCondition item : new ArrayList<>(questConditions)) {
			g.chcolor(item.questGiverMarkerColor());
			g.fellipse(c, qimg.ssz.div(2).sub(1, 1));
		    }
		    g.chcolor();
		}
	    }
	    GobIcon.Icon ic = icon();
	    if(ic != null) {
		ic.draw(g, c);
	    } else {
		Resource.Image img = res.layer(Resource.imgc);
		if(img != null) {
		    Resource.Neg neg = res.layer(Resource.negc);
		    Coord cc = neg != null ? neg.cc : img.ssz.div(2);
		    g.image(img, c.sub(cc));
		}
	    }
	    if(tip != null && CFG.MMAP_SHOW_MARKER_NAMES.get()) {
		g.aimage(tip.tex(), c.addy(UI.scale(3)), 0.5, 0);
	    }
	} catch (Loading ignored) {}
    }

    @Override
    public Area area() {
	try {
	    GobIcon.Icon ic = icon();
	    if(ic != null) {
		final Resource r = this.res.loadsaved();
		final Resource.Neg neg = r.layer(Resource.negc);
		final Resource.Image img = r.layer(Resource.imgc);
		if(img != null) {
		    final Coord cc = neg != null ? neg.cc : img.ssz.div(2);
		    return Area.sized(cc.inv(), img.ssz);
		}
	    }
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
