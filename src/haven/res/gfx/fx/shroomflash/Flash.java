/* Preprocessed source code */
package haven.res.gfx.fx.shroomflash;

import haven.*;
import haven.render.*;
import java.util.*;

/* >spr: Flash */
@haven.FromResource(name = "gfx/fx/shroomflash", version = 2)
public class Flash extends Sprite implements PView.Render2D {
    public static final Resource.Image[] imgs = Resource.classres(Flash.class).layers(Resource.imgc).toArray(new Resource.Image[0]);
    public static final Pipe.Op blend = FragColor.blend(new BlendMode(BlendMode.Function.ADD, BlendMode.Factor.SRC_ALPHA, BlendMode.Factor.ONE,
								      BlendMode.Function.ADD, BlendMode.Factor.ZERO, BlendMode.Factor.ONE));
    public static final Random rnd = new Random();
    public static final Indir<Resource> sfxu = Resource.classres(Flash.class).pool.load("sfx/fx/flash", 1);
    public static int lastidx = -1;
    public final boolean fsflash = !Utils.getprefb("anti-flash", false);
    public final Resource.Image img;
    public Coord2d ul, sz;
    public double t, pt, lt, intn;
    private final Resource sfx;

    public Flash(Owner owner, Resource res) {
	super(owner, res);
	sfx = sfxu.get();
	int idx;
	if(lastidx < 0) {
	    idx = rnd.nextInt(imgs.length);
	} else {
	    idx = rnd.nextInt(imgs.length - 1);
	    if(idx >= lastidx)
		idx++;
	}
	img = imgs[lastidx = idx];
	lt = 2.0 + (rnd.nextDouble() * 5.0);
	intn = 0.25 + (rnd.nextDouble() * 0.25);
    }

    public static Flash mksprite(Owner owner, Resource res, Message sdt) {
	return(new Flash(owner, res));
    }

    public void draw(GOut g, Pipe state) {
	if(CFG.FX_DISABLE_SHROOMED.get()) return;
	if(ul == null) {
	    Coord2d csz = new Coord2d(g.sz());
	    csz = csz.mul(1000.0 / Math.max(csz.x, csz.y));
	    sz = new Coord2d(img.rawtex().sz()).div(csz).mul(0.75 + (rnd.nextDouble() * 0.5));
	    double ar = 0.9 + (rnd.nextDouble() * 0.2);
	    sz = sz.mul(ar, 1 / ar);
	    ul = sz.mul(-0.25).add(rnd.nextDouble() * (1 - (sz.x * 0.5)), rnd.nextDouble() * (1 - (sz.y * 0.5)));
	}
	g.state().prep(blend);
	if((pt < 0.2) && fsflash) {
	    g.chcolor(255, 255, 255, (int)Math.round(128 * (1 - (pt / 0.2))));
	    g.frect(Coord.z, g.sz());
	}
	g.chcolor(255, 255, 255, (int)Math.round(255 * intn * Math.min(2 * (1 - (t / lt)), 1)));
	g.image(img.rawtex(), g.sz().mul(ul).round(), g.sz().mul(sz).round());
	g.defstate();
    }

    public boolean tick(double dt) {
	pt = t;
	t += dt;
	return(t >= lt);
    }

    @Override public void added(RenderTree.Slot slot) {
	if(CFG.FX_DISABLE_SHROOMED.get()) return;
	slot.add(Sprite.create(owner, sfx, Message.nil));
    }
}
