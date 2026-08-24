package me.ender.gob;

import haven.*;
import haven.render.Location;
import haven.render.Pipe;
import haven.render.RenderTree;
import haven.render.Transform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class GobEffects {
    private static final Resource tgtfx = Resource.local().loadwait("gfx/hud/combat/trgtarw");
    private final Collection<Effect> curfx = new ArrayList<>();
    private final Map<Long, Effect> sticky = new HashMap<>();
    private final UI ui;

    public GobEffects(UI ui) {this.ui = ui;}

    public void markGob(Gob gob) {
	fxon(gob, tgtfx, 7);
    }

    public void stickGob(Gob gob) {
	if(sticky.containsKey(gob.id)) {return;}
	Effect fx = fxon(gob, tgtfx, Double.POSITIVE_INFINITY);
	if(fx != null) {sticky.put(gob.id, fx);}
    }

    public void unstickGob(long gobId) {
	Effect fx = sticky.remove(gobId);
	if(fx != null) {fx.duration = -1;}
    }
    
    public void markPoint(MCache.Grid grid, Coord off) {
	Coord rc = grid.gc.mul(MCache.tilesz2).mul(MCache.cmaps).add(off);
	float z = (CFG.FLAT_TERRAIN.get() ? 0 : (float) grid.getz(off.div(MCache.tilesz))) - 16f;
	
	fxat(new Location(Transform.makexlate(new Matrix4f(), new Coord3f(rc.x, -rc.y, z)), "gobx"), tgtfx, 7);
    }
    
    private Effect fxon(Gob gob, Resource fx, double duration) {
	MapView map = ui.gui.map;
	
	if(map == null) {return (null);}
	
	Effect cur = new GobEffect(gob, map.basic, Sprite.create(null, fx, Message.nil));
	cur.duration = duration;
	curfx.add(cur);
	
	return (cur);
    }
    
    
    private Effect fxat(Pipe.Op place, Resource fx, long duration) {
	MapView map = ui.gui.map;
	
	if(map == null) {return (null);}
	
	Effect cur = new Effect(map.basic, Sprite.create(null, fx, Message.nil), place);
	cur.duration = duration;
	curfx.add(cur);
	
	return (cur);
    }
    
    
    public void tick(double dt) {
	for (Iterator<Effect> i = curfx.iterator(); i.hasNext(); ) {
	    Effect fx = i.next();
	    if(!fx.tick(dt)) {
		if(fx.slot != null) {
		    fx.slot.remove();
		    fx.slot = null;
		}
		i.remove();
		sticky.values().remove(fx);
	    }
	}
    }

    /* The returned world-space scale s renders on screen at apparent size
     * s/zoom, where zoom is the camera's zoom factor (1 = default view)
     * and apparent 1 = the arrow's vanilla size at default zoom. Zoomed
     * out, track the zoom 1:1 (constant, readable apparent size), capped
     * at 8x world. Zoomed in, shrink the apparent size linearly with the
     * zoom so the marker visibly settles down onto the object, flooring
     * at 40% apparent so it always stays findable. */
    private float zoomScale() {
	MapView map = (ui.gui != null) ? ui.gui.map : null;
	if(map == null || map.camera == null) {return 1f;}
	float zf = map.camera.zoomfac();
	if(zf >= 1f) {return Math.min(zf, 8f);}
	return zf * Math.max(0.4f, zf);
    }

    private class Effect implements RenderTree.Node {
	private final Sprite spr;
	private final RenderTree.Slot where;
	protected Pipe.Op place;
	protected RenderTree.Slot slot;
	double duration = 0;

	Effect(RenderTree.Slot where, Sprite spr, Pipe.Op place) {
	    this.where = where;
	    this.place = place;
	    this.spr = spr;
	}

	public void added(RenderTree.Slot slot) {
	    slot.add(spr);
	}

	/* The trgtarw mesh spans z 21..28.3 in gob space: the tip already
	 * hovers 21 units over the ground. Anchor the zoom scale at the tip
	 * so the arrow grows upward from its natural hover height, instead
	 * of the scale multiplying that offset and lifting it into the sky. */
	private static final float TIP_Z = 21f;

	protected Pipe.Op state() {
	    float s = zoomScale();
	    if(s == 1f) {return place;}
	    return Pipe.Op.compose(place,
		Location.xlate(new Coord3f(0, 0, TIP_Z * (1 - s))),
		Location.scale(s));
	}

	public boolean tick(double dt) {
	    if(place != null) {
		if(slot == null) {
		    try {
			slot = where.add(spr, state());
		    } catch (Loading ignored) {}
		} else {
		    slot.cstate(state());
		}
	    }

	    duration -= dt;
	    spr.tick(dt);

	    return duration >= 0;
	}
    }


    private class GobEffect extends Effect {
	private final Gob gob;

	GobEffect(Gob gob, RenderTree.Slot where, Sprite spr) {
	    /* Placement resolves lazily in tick: computing it here can throw
	     * Loading while the map grid under the gob is still being fetched
	     * (e.g. right after zoning via a ladder). */
	    super(where, spr, null);
	    this.gob = gob;
	}

	@Override
	public boolean tick(double dt) {
	    if(gob.disposed()) {
		return false;
	    }
	    try {
		place = gob.placed.curplace();
	    } catch (Loading ignored) {}
	    return super.tick(dt);
	}
    }
}
