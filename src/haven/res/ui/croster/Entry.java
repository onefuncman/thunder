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
public class Entry extends Widget {
    public static final int WIDTH = CattleRoster.WIDTH;
    public static final int HEIGHT = UI.scale(20);
    public static final Coord SIZE = new Coord(WIDTH, HEIGHT);
    public static final Color every = new Color(255, 255, 255, 16), other = new Color(255, 255, 255, 32);
    public static final Function<Integer, String> percent = v -> String.format("%d%%", v);
    public static final Function<Number, String> quality = v -> Long.toString(Math.round(v.doubleValue()));
    public static final Function<Entry, Tex> namerend = e -> {
	return(CharWnd.attrf.render(e.name, BuddyWnd.gc[e.grp]).tex());
    };
    public static final Tex male   = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/male", 2)::get).layer(Resource.imgc).tex();
    public static final Tex female = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/female", 2)::get).layer(Resource.imgc).tex();
    public static final Function<Boolean, Tex> sex = v -> (v ? male : female);
    public static final Tex adult = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/adult", 2)::get).layer(Resource.imgc).tex();
    public static final Tex child = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/child", 2)::get).layer(Resource.imgc).tex();
    public static final Function<Boolean, Tex> growth = v -> (v ? child : adult);
    public static final Tex dead  = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/dead", 2)::get).layer(Resource.imgc).tex();
    public static final Tex alive = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/alive", 2)::get).layer(Resource.imgc).tex();
    public static final Function<Boolean, Tex> deadrend = v -> (v ? dead : alive);
    public static final Tex pregy = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/pregnant-y", 2)::get).layer(Resource.imgc).tex();
    public static final Tex pregn = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/pregnant-n", 2)::get).layer(Resource.imgc).tex();
    public static final Function<Boolean, Tex> pregrend = v -> (v ? pregy : pregn);
    public static final Tex lacty = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/lactate-y", 1)::get).layer(Resource.imgc).tex();
    public static final Tex lactn = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/lactate-n", 1)::get).layer(Resource.imgc).tex();
    public static final Function<Boolean, Tex> lactrend = v -> (v ? lacty : lactn);
    public static final Tex ownedn = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/owned-n", 1)::get).layer(Resource.imgc).tex();
    public static final Tex ownedo = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/owned-o", 1)::get).layer(Resource.imgc).tex();
    public static final Tex ownedm = Loading.waitfor(Resource.classres(Entry.class).pool.load("gfx/hud/rosters/owned-m", 1)::get).layer(Resource.imgc).tex();
    public static final Function<Integer, Tex> ownrend = v -> ((v == 3) ? ownedm : ((v == 1) ? ownedo : ownedn));
    public final UID id;
    public String name;
    public int grp;
    public double q;
    public int idx;
    public CheckBox mark;

    public Entry(Coord sz, UID id, String name) {
	super(sz);
	this.id = id;
	this.name = name;
	this.mark = adda(new CheckBox(""), UI.scale(5), sz.y / 2, 0, 0.5);
    }

    protected void drawbg(GOut g) {
	g.chcolor(((idx & 1) == 0) ? every : other);
	g.frect(Coord.z, sz);
	g.chcolor();
    }

    private Tex[] rend = {};
    private Object[] rendv = {};
    public <V> void drawcol(GOut g, Column<?> col, double a, V val, Function<? super V, ?> fmt, int idx) {
	if(fmt == null) fmt = Function.identity();
	if(rend.length <= idx) {
	    rend = Arrays.copyOf(rend, idx + 1);
	    rendv = Arrays.copyOf(rendv, idx + 1);
	}
	if(!Utils.eq(rendv[idx], val)) {
	    if(rend[idx] != null)
		rend[idx].dispose();
	    Object rval = fmt.apply(val);
	    if(rval instanceof Tex)
		rend[idx] = (Tex)rval;
	    else
		rend[idx] = CharWnd.attrf.render(String.valueOf(rval)).tex();
	    rendv[idx] = val;
	}
	Coord sz = rend[idx].sz();
	g.image(rend[idx], new Coord(col.x + (int)Math.round((col.w - sz.x) * a), (this.sz.y - sz.y) / 2));
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(haven.CFG.CROSTER_DEBUG.get() && ev.b == 3 && ui.modctrl) {
	    logDebug();
	    return(true);
	}
	if(ev.propagate(this) || super.mousedown(ev))
	    return(true);
	CattleRoster<?> rost = getparent(CattleRoster.class);
	if(rost == null) return(false);
	rost.wdgmsg("click", id, ev.b, ui.modflags(), ui.mc);
	return(true);
    }

    private void logDebug() {
	StringBuilder buf = new StringBuilder("[CattleRoster debug]\n");
	buf.append(String.format("  class=%s%n", getClass().getName()));
	buf.append(String.format("  id=%s name=%s grp=%d q=%.2f mark=%s%n", id, name, grp, q, mark.a));
	Class<?> cls = getClass();
	while(cls != null && cls != Widget.class) {
	    for(java.lang.reflect.Field f : cls.getDeclaredFields()) {
		if(java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
		try {
		    f.setAccessible(true);
		    Object v = f.get(this);
		    buf.append(String.format("  %s.%s = %s%n", cls.getSimpleName(), f.getName(), v));
		} catch(Exception ignored) {}
	    }
	    cls = cls.getSuperclass();
	}
	appendGobInfo(buf);
	System.err.println(buf.toString());
    }

    private void appendGobInfo(StringBuilder buf) {
	Gob gob = null;
	try {
	    OCache oc = ui.sess.glob.oc;
	    synchronized(oc) {
		for(Gob g : oc) {
		    CattleId cid = g.getattr(CattleId.class);
		    if(cid != null && cid.id.equals(id)) {gob = g; break;}
		}
	    }
	} catch(Exception ignored) {}
	if(gob == null) {buf.append("  [gob] not found in oc\n"); return;}
	buf.append(String.format("  [gob] id=%d rc=%s%n", gob.id, gob.rc));
	Drawable d = gob.drawable;
	Resource res = (d != null) ? d.getres() : null;
	buf.append(String.format("  [gob] drawable=%s res=%s%n",
	    (d == null) ? "null" : d.getClass().getSimpleName(),
	    (res == null) ? "null" : res.name));
	Skeleton skel = null;
	if(d instanceof Composite) {
	    Composite cd = (Composite)d;
	    skel = cd.comp.skel;
	    try {
		for(Composited.MD md : cd.comp.cmod) {
		    try {buf.append(String.format("  [gob] model=%s%n", md.mod.get().name));}
		    catch(Loading l) {buf.append("  [gob] model=(loading)\n");}
		    for(ResData tex : md.tex) {
			try {buf.append(String.format("    tex=%s%n", tex.res.get().name));}
			catch(Loading l) {buf.append("    tex=(loading)\n");}
		    }
		}
		for(Composited.ED eq : cd.comp.cequ) {
		    try {buf.append(String.format("  [gob] equ %s=%s%n", eq.at, eq.res.res.get().name));}
		    catch(Loading l) {buf.append(String.format("  [gob] equ %s=(loading)%n", eq.at));}
		}
	    } catch(Exception e) {buf.append("  [gob] composite error: ").append(e.getMessage()).append('\n');}
	} else if(res != null) {
	    Skeleton.Res sr = res.layer(Skeleton.Res.class);
	    if(sr != null) skel = sr.s;
	}
	synchronized(gob.ols) {
	    for(Gob.Overlay ol : gob.ols) {
		String olRes = "?";
		try {
		    if(ol.spr != null && ol.spr.res != null) olRes = ol.spr.res.name;
		} catch(Exception ignored) {}
		buf.append(String.format("  [gob] ol id=%d res=%s spr=%s%n", ol.id, olRes,
		    (ol.spr == null) ? "null" : ol.spr.getClass().getSimpleName()));
	    }
	}
	if(skel == null) {buf.append("  [gob] no skeleton (use empirical observation for height)\n"); return;}
	Skeleton.Pose bind = skel.bindpose;
	float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
	float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
	String topX = null, topY = null, topZ = null;
	for(int i = 0; i < skel.blist.length; i++) {
	    float x = bind.gpos[i][0], y = bind.gpos[i][1], z = bind.gpos[i][2];
	    if(x > maxX) {maxX = x; topX = skel.blist[i].name;}
	    if(y > maxY) {maxY = y; topY = skel.blist[i].name;}
	    if(z > maxZ) {maxZ = z; topZ = skel.blist[i].name;}
	    if(x < minX) minX = x;
	    if(y < minY) minY = y;
	    if(z < minZ) minZ = z;
	}
	buf.append(String.format("  [gob] bindpose extents: x=[%.2f..%.2f] y=[%.2f..%.2f] z=[%.2f..%.2f] (world +Z=up)%n",
	    minX, maxX, minY, maxY, minZ, maxZ));
	buf.append(String.format("  [gob] topmost bones: maxX=%s maxY=%s maxZ=%s%n", topX, topY, topZ));
	for(int i = 0; i < skel.blist.length; i++) {
	    buf.append(String.format("    bone %-20s gpos=(%.2f, %.2f, %.2f)%n",
		skel.blist[i].name, bind.gpos[i][0], bind.gpos[i][1], bind.gpos[i][2]));
	}
    }

    public <T extends Entry> void markall(Class<T> type, Predicate<? super T> p) {
	CattleRoster<?> rost = getparent(CattleRoster.class);
	if(rost == null) return;
	boolean val = !this.mark.a;
	for(T ent : rost.children(type)) {
	    if(p.test(ent))
		ent.mark.set(val);
	}
    }
}
