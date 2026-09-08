package haven;

import auto.ITarget;
import auto.Targets;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static haven.MCache.*;
import static haven.OCache.*;

public class PathQueue {
    private static final boolean DBG = false;
    private static final Coord2d gsz = tilesz.mul(cmaps.x, cmaps.y);
    private final List<Coord2d> queue = new LinkedList<>();
    private final MapView map;
    private Moving moving;
    private boolean clicked = false;
    private Coord2d clickPos = null;
    private boolean passenger = false;
    private int ignoreStops = 0;
    
    public PathQueue(MapView map) {
	this.map = map;
	CFG.QUEUE_PATHS.observe(cfg -> {
	    if(!cfg.get()) {clear();}
	});
    }
    
    public boolean add(Coord2d p) {
	if(!map.ui.isDefaultCursor()) {return true;}
	boolean start = false;
	synchronized (queue) {
	    if(passenger) {return false;}
	    if(queue.isEmpty()) { start = true; }
	    queue.add(p);
	    unclick();
	}
	
	return start;
    }
    
    public void start(Coord2d p) {
	if(!map.ui.isDefaultCursor()) {return;}
	synchronized (queue) {
	    if(passenger) {return;}
	    queue.clear();
	    queue.add(p);
	    unclick();
	}
    }
    
    public void click(Coord2d mc, ClickData inf) {
	if(inf != null) {
	    click(Gob.from(inf.ci));
	} else {
	    click(mc);
	}
    }
    
    public void click(ITarget target) {
	click(Targets.gob(target));
    }
    
    public void click(Gob gob) {
	click(gob != null ? gob.rc : null);
    }
    
    public void click(Coord2d pos) {
	clicked = true;
	this.clickPos = pos;
    }
    
    public void click() {
	clicked = true;
	clickPos = null;
    }
    
    public void unclick() {
	clicked = false;
	clickPos = null;
    }
    
    public List<Pair<Coord3f, Coord3f>> lines() {
	LinkedList<Coord2d> tmp;
	synchronized (queue) {
	    tmp = new LinkedList<>(queue);
	}
	
	List<Pair<Coord3f, Coord3f>> lines = new LinkedList<>();
	if(!tmp.isEmpty()) {
	    try {
		Gob player = map.player();
		if(player != null) {
		    Coord2d pc = player.rc;
		    Coord pgrid = pc.floor(gsz);
		    float z = 0;
		    Coord3f current = new Coord3f((float) pc.x, (float) pc.y, 0);
		    try {
			current = moving == null ? player.getrc() : moving.gett();
			z = current.z;
		    } catch (Loading ignored) {}
		    for (Coord2d p : tmp) {
			Coord3f next = new Coord3f((float) p.x, (float) p.y, z);
			if(pgrid.manhattan2(p.floor(gsz)) <= 1) {
			    try {
				next = map.glob.map.getzp(p);
			    } catch (Loading ignored) {}
			}
			lines.add(new Pair<>(current, next));
			current = next;
		    }
		}
	    } catch (Loading ignored) {}
	}
	return lines;
    }
    
    public List<Pair<Coord2d, Coord2d>> minimapLines() {
	LinkedList<Coord2d> tmp;
	synchronized (queue) {
	    tmp = new LinkedList<>(queue);
	}
	
	List<Pair<Coord2d, Coord2d>> lines = new LinkedList<>();
	if(!tmp.isEmpty()) {
	    Gob player = map.player();
	    if(player != null) {
		Coord2d current = player.rc;
		for (Coord2d p : tmp) {
		    lines.add(new Pair<>(current, p));
		    current = p;
		}
	    }
	}
	return lines;
    }
    
    private Coord2d pop() {
	synchronized (queue) {
	    if(queue.isEmpty()) { return null; }
	    queue.remove(0);
	    return queue.isEmpty() ? null : queue.get(0);
	}
    }
    
    public void movementChange(Gob gob, GAttrib from, GAttrib to) {
	if(gob.is(GobTag.ME)) {
	    synchronized (queue) {checkPassenger((Moving) to);}
	}
	if(skip(gob)) {return;}
	if(DBG) {log(gob, from, to);}
	moving = (Moving) to;
	synchronized (queue) {
	    if(to == null) {
		if(ignoreStops > 0) {
		    ignoreStops--;
		    return;
		}
		Coord2d next = pop();
		if(next != null) {
		    unclick();
		    map.wdgmsg("click", Coord.z, next.floor(posres), 1, 0);
		}
	    } else if(to instanceof Homing || to instanceof Following) {
		clear();
	    } else {
		if(to instanceof LinMove)
		    ignoreStops = 0;
		if(clicked) {
		    if(this.clickPos != null) {
			start(this.clickPos);
		    } else {
			clear();
		    }
		    unclick();
		}
	    }
	}
    }
    
    private void checkPassenger(Moving moving) {
	boolean passenger = false;
	if(moving instanceof Following) {
	    Following follow = (Following) moving;
	    Optional<String> vehicle = Optional.ofNullable(follow.tgt()).map(Gob::resid);
	    if(vehicle.isPresent()) {
		String id = vehicle.get();
		String pos = follow.xfname;
		if(id.contains("/vehicle/snekkja")) {
		    passenger = !pos.equals("m0");
		} else if(id.contains("/vehicle/knarr")) {
		    passenger = !pos.equals("m9");
		} else if(id.contains("/vehicle/rowboat")) {
		    passenger = !pos.equals("d");
		} else if(id.contains("/vehicle/spark")) {
		    passenger = !pos.equals("d");
		} else if(id.contains("/vehicle/wagon")) {
		    passenger = !pos.equals("d0");
		}
		if(DBG) Debug.log.printf("vehicle: '%s', position: '%s', passenger: %s%n", id, pos, passenger);
	    }
	}
	this.passenger = passenger;
	if(passenger) {
	    clear();
	}
    }
    
    private boolean skip(Gob gob) {
	Gob me = map.player();
	if(me == null) {
	    boolean skip = map.plgob == -1 || map.plgob != gob.id;
	    if(DBG) Debug.log.printf("skip (%d) '%d' is null, %b%n", gob.id, map.plgob, skip);
	    return skip;
	}
	long vehicleId = me.vehicleId();
	if(vehicleId == 0) {
	    boolean skip = me.id != gob.id;
	    if(DBG) Debug.log.printf("skip (%d) '%d'<%d> not drives, %b%n", gob.id, map.plgob, vehicleId, skip);
	    return skip;
	} else {
	    boolean skip = gob.id != vehicleId;
	    if(DBG) Debug.log.printf("skip (%d) '%d'<%d> drives, %b%n", gob.id, map.plgob, vehicleId, skip);
	    return skip;
	}
    }
    
    public void repath() {
	synchronized (queue) {
	    ignoreStops = moving != null ? 1 : 0;
	    queue.clear();
	    unclick();
	}
    }

    public void clear() {
	synchronized (queue) {
	    queue.clear();
	    ignoreStops = 0;
	}
    }
    
    private void log(Gob gob, GAttrib from, GAttrib to) {
	String type = "unknown";
	String action = "unknown";
	if(to == null) {
	    if(from != null) {
		action = "removed";
		type = from.getClass().getName();
	    } else {
		action = "removed";
		type = "both empty???";
	    }
	} else {
	    if(from != null) {
		action = "switched";
		type = from.getClass().getName() + " to " + to.getClass().getName();
	    } else {
		action = "added";
		type = to.getClass().getName();
	    }
	}
	Debug.log.printf("id:'%d' %s - %s%n", gob.id, action, type);
    }
}
