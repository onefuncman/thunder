package haven;

import java.util.*;

/**
 * Edge snapping for the placement ghost, driven from MapView.StdPlace.
 * Ported from Vantazz/Hurricane (commit 8864192); see docs/plob-snap-design.md.
 *
 * While placing, the ghost is pulled flush against the collision boxes of
 * whatever is standing around it, one axis at a time: when it catches a wall
 * on X, Y is left following the cursor, so the ghost slides along that wall
 * until the perpendicular edge of a corner comes into range and grabs the
 * other axis as well.
 */
public class PlobSnap {
    /* All distances are world units; a tile is 11 of them. */
    public static double capture = Utils.getprefd("plobsnapdist", 3.5);
    public static double deadzone = Utils.getprefd("plobsnapdead", 6.5);
    /* How far past a neighbour we still count as standing alongside it. */
    private static final double overlap = 2.0;
    /* How far away a neighbour may sit and still be worth lining edges up with. */
    private static final double aligngap = MCache.tilesz.x;
    /* Edges lined up with a neighbour snap a little more reluctantly than faces put flush against it. */
    private static final double alignbias = 1.15;
    /* The server rejects a placement whose footprint exactly meets its
     * neighbour, so a flush snap stops just short. Sub-pixel at normal zoom.
     * Walls want considerably more than that -- they behave as though they
     * carry clearance past their declared obstacle layers. */
    public static double abutgap = Utils.getprefd("plobsnapgap", 0.1);
    public static double wallgap = Utils.getprefd("plobsnapwallgap", 1.0);

    private double heldx = Double.NaN, heldy = Double.NaN;
    /* What the last placement did, for the placesnap console command. Only
     * recorded while CFG.DEBUG_PLOB_SNAP is on -- snap() runs on every
     * mousemove during placement, and formatting a string each time is
     * pointless when nobody is going to read it. */
    public static volatile String diag = "nothing recorded";

    private static void note(String fmt, Object... args) {
	if(CFG.DEBUG_PLOB_SNAP.get())
	    diag = String.format(fmt, args);
    }

    /** One axis-aligned box, in gob-local or in world coordinates. */
    public static class Box {
	public final double lx, ly, hx, hy;
	/* How far short of this box a flush snap has to stop. */
	public final double gap;

	public Box(double lx, double ly, double hx, double hy, double gap) {
	    this.lx = lx; this.ly = ly; this.hx = hx; this.hy = hy; this.gap = gap;
	}

	public Box(double lx, double ly, double hx, double hy) {
	    this(lx, ly, hx, hy, 0);
	}

	/* The bounding box of this box turned by a around the gob origin. */
	public Box rot(double a) {
	    double nlx = Double.POSITIVE_INFINITY, nly = Double.POSITIVE_INFINITY;
	    double nhx = Double.NEGATIVE_INFINITY, nhy = Double.NEGATIVE_INFINITY;
	    for(int i = 0; i < 4; i++) {
		Coord2d c = Coord2d.of(((i & 1) == 0) ? lx : hx, ((i & 2) == 0) ? ly : hy).rot(a);
		nlx = Math.min(nlx, c.x); nly = Math.min(nly, c.y);
		nhx = Math.max(nhx, c.x); nhy = Math.max(nhy, c.y);
	    }
	    return(new Box(nlx, nly, nhx, nhy, gap));
	}

	public Box xlate(Coord2d c) {
	    return(new Box(lx + c.x, ly + c.y, hx + c.x, hy + c.y, gap));
	}

	public double ext() {
	    return(Math.max(Math.max(-lx, hx), Math.max(-ly, hy)));
	}
    }

    private static final Map<String, Box> boxcache = new HashMap<String, Box>();
    private static final Box nobox = new Box(0, 0, 0, 0);

    /* Hitboxes can live in a linked mesh resource rather than in the gob own one. */
    private static Resource hitres(Resource res) {
	for(RenderLink.Res link : res.layers(RenderLink.Res.class)) {
	    if(link.l instanceof RenderLink.MeshMat)
		return(((RenderLink.MeshMat)link.l).mesh.get());
	}
	return(res);
    }

    /**
     * A resource footprint in map coordinates, as the server sees it.
     *
     * The "build" obstacle is included even though the collision-box overlay
     * leaves it out: it is the shape placement is validated against, and
     * snapping to the drawn box instead produces placements that look flush
     * and get refused.
     */
    private static Box resbox(Resource res) {
	synchronized(boxcache) {
	    Box hit = boxcache.get(res.name);
	    if(hit != null)
		return((hit == nobox) ? null : hit);
	}
	double lx = Double.POSITIVE_INFINITY, ly = Double.POSITIVE_INFINITY;
	double hx = Double.NEGATIVE_INFINITY, hy = Double.NEGATIVE_INFINITY;
	Resource hr = hitres(res);
	for(Resource.Neg neg : hr.layers(Resource.Neg.class)) {
	    lx = Math.min(lx, Math.min(neg.ac.x, neg.bc.x)); ly = Math.min(ly, Math.min(neg.ac.y, neg.bc.y));
	    hx = Math.max(hx, Math.max(neg.ac.x, neg.bc.x)); hy = Math.max(hy, Math.max(neg.ac.y, neg.bc.y));
	}
	for(Resource.Obstacle obst : hr.layers(Resource.Obstacle.class)) {
	    if("ext".equals(obst.id))
		continue;
	    for(Coord2d[] poly : obst.p) {
		for(Coord2d p : poly) {
		    lx = Math.min(lx, p.x); ly = Math.min(ly, p.y);
		    hx = Math.max(hx, p.x); hy = Math.max(hy, p.y);
		}
	    }
	}
	Box ret = (lx > hx) ? null : new Box(lx, ly, hx, hy, gapfor(res));
	synchronized(boxcache) {
	    boxcache.put(res.name, (ret == null) ? nobox : ret);
	}
	return(ret);
    }

    private static double gapfor(Resource res) {
	return(res.name.startsWith("gfx/terobjs/arch/") ? wallgap : abutgap);
    }

    /** The ghost footprint, plus the gob it is mirroring and must not snap to. */
    private static class Self {
	final Box box;
	final long srcid;
	final String how;

	Self(Box box, long srcid, String how) {
	    this.box = box; this.srcid = srcid; this.how = how;
	}
    }

    /**
     * Resolving what the ghost actually is.
     *
     * A placement ghost is normally not the object being placed. It is
     * ui/gobcp, whose Gobcopy sprite merely mirrors a hidden source gob, and
     * which carries no obstacle layers of its own -- so reading the footprint
     * off the ghost's own resource finds nothing and no snapping can happen.
     * Gobcopy takes that source gob's id from the first word of its sdt, so
     * the footprint is one oc lookup away. Some ghosts instead carry their
     * shape on an overlay, and construction sites carry it in the sdt.
     */
    private static Self plobself(Gob plob) {
	Resource res = plob.getres();
	Box ret = (res == null) ? null : resbox(res);
	if(ret != null)
	    return(new Self(ret, -1, "own resource " + res.name));

	long srcid = -1;
	if((res != null) && res.name.equals("ui/gobcp")) {
	    ResDrawable rd = plob.getattr(ResDrawable.class);
	    if((rd != null) && (rd.sdt != null) && (rd.sdt.rbuf.length >= 4)) {
		srcid = rd.sdt.clone().uint32();
		Gob src = plob.glob.oc.getgob(srcid);
		Resource sres = (src == null) ? null : src.getres();
		if(sres != null) {
		    ret = resbox(sres);
		    if(ret != null)
			return(new Self(ret, srcid, "mirrored gob " + sres.name));
		}
	    }
	}

	List<Gob.Overlay> ols;
	synchronized(plob.ols) {
	    ols = new ArrayList<Gob.Overlay>(plob.ols);
	}
	for(Gob.Overlay ol : ols) {
	    Indir<Resource> ind = Overlays.res(ol);
	    if(ind == null)
		continue;
	    Resource ores = ind.get();
	    if(ores == null)
		continue;
	    Box b = resbox(ores);
	    if(b != null)
		return(new Self(b, srcid, "overlay " + ores.name));
	}

	if((res != null) && res.name.endsWith("/consobj")) {
	    ResDrawable rd = plob.getattr(ResDrawable.class);
	    if((rd != null) && (rd.sdt != null) && (rd.sdt.rbuf.length >= 4)) {
		byte[] b = rd.sdt.rbuf;
		return(new Self(new Box(Math.min(b[0], b[2]), Math.min(b[1], b[3]),
					Math.max(b[0], b[2]), Math.max(b[1], b[3]), abutgap),
				srcid, "construction plot"));
	    }
	}
	return(new Self(null, srcid, (res == null) ? "no resource yet" : ("no footprint on " + res.name)));
    }

    private static final String[] passable = {
	"gfx/terobjs/herbs", "gfx/terobjs/items", "gfx/terobjs/plants",
	"gfx/terobjs/clue", "gfx/terobjs/boostspeed",
	"gfx/borka/body", "gfx/kritter/",
    };

    private static boolean snappable(Resource res) {
	if(res.name.contains("trellis"))
	    return(true);
	for(String p : passable) {
	    if(res.name.startsWith(p))
		return(false);
	}
	return(true);
    }

    private static List<Box> neighbours(MapView mv, Coord2d c, double range, long srcid) {
	/* Pick the candidates out under the lock, but resolve their resources
	 * outside it, since that can go off loading things. */
	List<Gob> cand = new ArrayList<Gob>();
	long plid = mv.plgob;
	synchronized(mv.glob.oc) {
	    for(Gob g : mv.glob.oc) {
		/* Never snap to yourself, to the gob the ghost is mirroring, or to a phantom. */
		if((g.id == plid) || (g.id == srcid) || (g.id < 0))
		    continue;
		Coord2d rc = g.rc;
		if((rc == null) || (Math.abs(rc.x - c.x) > range) || (Math.abs(rc.y - c.y) > range))
		    continue;
		cand.add(g);
	    }
	}
	List<Box> ret = new ArrayList<Box>(cand.size());
	for(Gob g : cand) {
	    Coord2d rc = g.rc;
	    if(rc == null)
		continue;
	    try {
		Resource res = g.getres();
		if((res == null) || !snappable(res))
		    continue;
		Box b = resbox(res);
		if(b != null)
		    ret.add(b.rot(g.a).xlate(rc));
	    } catch(Loading l) {
	    }
	}
	return(ret);
    }

    /* Terrain that placement treats as a wall: solid rock and the void. */
    private static boolean blockedTile(String name) {
	if(name == null)
	    return(true);
	return(name.equals("gfx/tiles/deep")
	       || name.equals("gfx/tiles/cave")
	       || name.equals("gfx/tiles/nil")
	       || name.startsWith("gfx/tiles/rocks/"));
    }

    private static boolean blocked(MCache mc, Coord tc) {
	try {
	    Resource res = mc.tilesetr(mc.gettile(tc));
	    return((res != null) && blockedTile(res.name));
	} catch(Loading l) {
	    return(false);
	} catch(RuntimeException e) {
	    /* Off the loaded map. */
	    return(false);
	}
    }

    private static Coord tile(boolean xaxis, int along, int across) {
	return(xaxis ? new Coord(along, across) : new Coord(across, along));
    }

    /**
     * Walls that are terrain rather than objects -- cave walls above all, which
     * carry no gob and are therefore invisible to the neighbour scan.
     *
     * Terrain cannot go through the box machinery. A wall is a run of separate
     * tiles, and every one of them would offer up its two side faces as snap
     * targets, chopping the slide along the wall into tile-sized steps. What
     * actually snaps is a wall *face*: a tile boundary with rock on one side
     * and open floor on the other. So only the rows (or columns) the ghost
     * itself covers are searched, which is what makes a wall running east-west
     * offer nothing at all to the X axis and leaves the slide along it smooth.
     */
    private static double terrainaxis(MCache mcache, boolean xaxis, Box self, Coord2d c, double reach) {
	double lo, hi, olo, ohi, free;
	if(xaxis) {
	    lo = self.lx; hi = self.hx; olo = self.ly + c.y; ohi = self.hy + c.y; free = c.x;
	} else {
	    lo = self.ly; hi = self.hy; olo = self.lx + c.x; ohi = self.hx + c.x; free = c.y;
	}
	final boolean xa = xaxis;
	return(faceaxis((along, across) -> blocked(mcache, tile(xa, along, across)),
			lo, hi, olo, ohi, free, reach, abutgap));
    }

    /** Is this tile solid rock? Taken as an argument so the face search can be tested off a synthetic map. */
    public interface Rock {
	boolean at(int along, int across);
    }

    /* The face search itself, in axis-local terms: `along` runs down the axis
     * being snapped, `across` down the other one. */
    public static double faceaxis(Rock rock, double lo, double hi, double olo, double ohi,
			   double free, double reach, double gap) {
	double ts = MCache.tilesz.x;
	int oa = (int)Math.floor(olo / ts), ob = (int)Math.floor(ohi / ts);
	int sa = (int)Math.floor((free + lo - reach) / ts), sb = (int)Math.floor((free + hi + reach) / ts);
	double best = Double.NaN, bd = Double.POSITIVE_INFINITY;
	for(int o = oa; o <= ob; o++) {
	    for(int s = sa; s <= sb; s++) {
		if(!rock.at(s, o))
		    continue;
		/* Rock here, open floor on the near side: a face to back up against. */
		if(!rock.at(s - 1, o)) {
		    double cand = (s * ts) - hi - gap;
		    double d = Math.abs(cand - free);
		    if((d <= reach) && (d < bd)) {bd = d; best = cand;}
		}
		if(!rock.at(s + 1, o)) {
		    double cand = ((s + 1) * ts) - lo + gap;
		    double d = Math.abs(cand - free);
		    if((d <= reach) && (d < bd)) {bd = d; best = cand;}
		}
	    }
	}
	return(best);
    }

    /* Whichever of the two candidates sits closer to where the cursor actually is. */
    private static double pick(double a, double b, double free) {
	if(Double.isNaN(a))
	    return(b);
	if(Double.isNaN(b))
	    return(a);
	return((Math.abs(b - free) < Math.abs(a - free)) ? b : a);
    }

    /**
     * The best snapped centre coordinate on one axis, or NaN when no edge is
     * within reach. Only neighbours the ghost currently stands alongside on
     * the other axis are considered, so a wall two rooms over cannot grab it.
     * Package-private so the unit tests can drive it on synthetic boxes.
     */
    static double axis(boolean xaxis, List<Box> near, Box self, Coord2d c, double reach) {
	double lo, hi, olo, ohi, free;
	if(xaxis) {
	    lo = self.lx; hi = self.hx; olo = self.ly + c.y; ohi = self.hy + c.y; free = c.x;
	} else {
	    lo = self.ly; hi = self.hy; olo = self.lx + c.x; ohi = self.hx + c.x; free = c.y;
	}
	double best = Double.NaN, bscore = Double.POSITIVE_INFINITY;
	for(Box n : near) {
	    double nlo, nhi, nolo, nohi;
	    if(xaxis) {
		nlo = n.lx; nhi = n.hx; nolo = n.ly; nohi = n.hy;
	    } else {
		nlo = n.ly; nhi = n.hy; nolo = n.lx; nohi = n.hx;
	    }
	    /* The separation along the other axis, negative where the two boxes
	     * span the same ground. Standing alongside a box is what lets a face
	     * go flush against it; standing clear of it, but still close by, is
	     * what lets the two line their edges up instead. Keeping those apart
	     * is what stops a small object from lining itself up with a wall it
	     * is leaning on, which would bury it in the wall. */
	    double sep = Math.max(nolo - ohi, olo - nohi);
	    boolean beside = (sep <= overlap);
	    boolean apart = (sep >= -0.5) && (sep <= aligngap);
	    for(int i = 0; i < 4; i++) {
		double cand;
		switch(i) {
		case 0:  cand = nlo - hi - n.gap; break; /* our far face flush on their near face */
		case 1:  cand = nhi - lo + n.gap; break; /* our near face flush on their far face */
		case 2:  cand = nlo - lo; break;         /* near edges lined up */
		default: cand = nhi - hi; break;         /* far edges lined up */
		}
		if((i < 2) ? !beside : !apart)
		    continue;
		double d = Math.abs(cand - free);
		if(d > reach)
		    continue;
		double score = d * ((i < 2) ? 1.0 : alignbias);
		if(score < bscore) {
		    bscore = score;
		    best = cand;
		}
	    }
	}
	return(best);
    }

    /* Grab inside the capture radius, then hang on until the cursor has dragged
     * the whole dead zone away from the edge that was caught. */
    static double resolve(double cand, double free, double held) {
	if(Double.isNaN(cand))
	    return(Double.NaN);
	if(Math.abs(cand - free) <= capture)
	    return(cand);
	if(!Double.isNaN(held) && (Math.abs(cand - held) < 0.01))
	    return(cand);
	return(Double.NaN);
    }

    public void reset() {
	heldx = heldy = Double.NaN;
    }

    /**
     * The snapped position for a ghost of angle a whose cursor sits at mc, or
     * null when nothing nearby caught it and the caller should fall back to
     * its normal placement grid.
     */
    public Coord2d snap(MapView.Plob plob, Coord2d mc, double a) {
	/* An off-axis ghost has no meaningful axis-aligned footprint to snap with. */
	if(Math.abs(Math.IEEEremainder(a, Math.PI / 2)) > 1e-4) {
	    note("ghost is turned off-axis (%.1f deg), not snapping", Math.toDegrees(a));
	    reset();
	    return(null);
	}
	Box self;
	List<Box> near;
	String how;
	try {
	    Self s = plobself(plob);
	    how = s.how;
	    if(s.box == null) {
		note("no footprint: %s", how);
		reset();
		return(null);
	    }
	    self = s.box.rot(a);
	    near = neighbours(plob.mv(), mc, self.ext() + deadzone + (6 * MCache.tilesz.x), s.srcid);
	} catch(Loading l) {
	    return(null);
	}

	/* Two passes, so that catching one axis lets the other reconsider from
	 * where the ghost actually ended up -- that is what closes a corner. */
	MCache mcache = plob.mv().glob.map;
	Coord2d c = mc;
	double rx = Double.NaN, ry = Double.NaN;
	int nwall = 0;
	for(int pass = 0; pass < 2; pass++) {
	    double tx = terrainaxis(mcache, true, self, c, deadzone);
	    double ty = terrainaxis(mcache, false, self, c, deadzone);
	    nwall = (Double.isNaN(tx) ? 0 : 1) + (Double.isNaN(ty) ? 0 : 1);
	    rx = pick(axis(true, near, self, c, deadzone), tx, c.x);
	    ry = pick(axis(false, near, self, c, deadzone), ty, c.y);
	    c = Coord2d.of(Double.isNaN(rx) ? mc.x : rx, Double.isNaN(ry) ? mc.y : ry);
	}
	double sx = heldx = resolve(rx, mc.x, heldx);
	double sy = heldy = resolve(ry, mc.y, heldy);
	if(Double.isNaN(sx) && Double.isNaN(sy)) {
	    note("footprint %.1fx%.1f from %s, %d hitboxes + %d wall faces in reach, none close enough to grab",
		 self.hx - self.lx, self.hy - self.ly, how, near.size(), nwall);
	    return(null);
	}
	note("snapped %s -- footprint %.1fx%.1f from %s, %d hitboxes + %d wall faces in reach",
	     Double.isNaN(sx) ? "on y, sliding on x" : (Double.isNaN(sy) ? "on x, sliding on y" : "on both, in a corner"),
	     self.hx - self.lx, self.hy - self.ly, how, near.size(), nwall);
	return(Coord2d.of(Double.isNaN(sx) ? mc.x : sx, Double.isNaN(sy) ? mc.y : sy));
    }
}
