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
    
    private static class Effect implements RenderTree.Node {
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
	
	public boolean tick(double dt) {
	    if(slot == null) {
		try {
		    slot = where.add(spr, place);
		} catch (Loading ignored) {}
	    }
	    
	    duration -= dt;
	    spr.tick(dt);
	    
	    return duration >= 0;
	}
    }
    
    
    private static class GobEffect extends Effect {
	private final Gob gob;
	
	GobEffect(Gob gob, RenderTree.Slot where, Sprite spr) {
	    super(where, spr, gob.placed.curplace());
	    this.gob = gob;
	}
	
	@Override
	public boolean tick(double dt) {
	    if(gob.disposed()) {
		return false;
	    }
	    boolean active = super.tick(dt);
	    if(!active) {return false;}
	    place = gob.placed.curplace();
	    if(slot != null) {slot.cstate(place);}
	    return true;
	}
    }
}
