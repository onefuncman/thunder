package auto;

import haven.*;

import java.util.*;

import static haven.OCache.posres;

/** "Interact with closest ..." keybind action: right-clicks the nearest enabled
 * object - fence gates, building doorways, cellar doors, mineholes/ladders and
 * road milestones. The set of enabled kinds is configured in Options -> General. */
public class NearestInteract {
    private static final double RADIUS = 35;

    public enum Kind {
	MILESTONE("Milestones"),
	DOORWAY("Doorways"),
	CELLAR("Cellar doors"),
	MINEHOLE("Mineholes & ladders"),
	GATE_PALISADE("Palisade gates"),
	GATE_TWIG("Twig (roundpole) gates"),
	GATE_STONE("Drystone gates"),
	GATE_BRICK("Brickwall gates");

	public final String label;

	Kind(String label) {this.label = label;}
    }

    private static final Map<String, Kind> GATES = new HashMap<>();
    static {
	GATES.put("gfx/terobjs/arch/palisadegate", Kind.GATE_PALISADE);
	GATES.put("gfx/terobjs/arch/palisadebiggate", Kind.GATE_PALISADE);
	GATES.put("gfx/terobjs/arch/polegate", Kind.GATE_TWIG);
	GATES.put("gfx/terobjs/arch/polebiggate", Kind.GATE_TWIG);
	GATES.put("gfx/terobjs/arch/drystonewallgate", Kind.GATE_STONE);
	GATES.put("gfx/terobjs/arch/drystonewallbiggate", Kind.GATE_STONE);
	GATES.put("gfx/terobjs/arch/brickwallgate", Kind.GATE_BRICK);
	GATES.put("gfx/terobjs/arch/brickbiggate", Kind.GATE_BRICK);
    }

    /* Door click points on buildings: offset from the gob center (rotated by the
     * gob's angle) plus the mesh id the server expects for that door. Offsets
     * match Hurricane's InteractWithNearestObject table. */
    private static final Map<String, List<Door>> BUILDINGS = new HashMap<>();
    static {
	BUILDINGS.put("gfx/terobjs/arch/logcabin", Collections.singletonList(new Door(Coord2d.of(22, 0), 16)));
	BUILDINGS.put("gfx/terobjs/arch/timberhouse", Collections.singletonList(new Door(Coord2d.of(33, 0), 16)));
	BUILDINGS.put("gfx/terobjs/arch/stonestead", Collections.singletonList(new Door(Coord2d.of(44, 0), 16)));
	BUILDINGS.put("gfx/terobjs/arch/stonemansion", Collections.singletonList(new Door(Coord2d.of(48, 0), 16)));
	BUILDINGS.put("gfx/terobjs/arch/greathall", Arrays.asList(
	    new Door(Coord2d.of(77, -28), 18),
	    new Door(Coord2d.of(77, 0), 17),
	    new Door(Coord2d.of(77, 28), 16)));
	BUILDINGS.put("gfx/terobjs/arch/greathall-door", Arrays.asList(
	    new Door(Coord2d.of(0, -30), 18),
	    new Door(Coord2d.of(0, 0), 17),
	    new Door(Coord2d.of(0, 30), 16)));
	BUILDINGS.put("gfx/terobjs/arch/stonetower", Collections.singletonList(new Door(Coord2d.of(36, 0), 16)));
	BUILDINGS.put("gfx/terobjs/arch/windmill", Collections.singletonList(new Door(Coord2d.of(0, 28), 16)));
	BUILDINGS.put("gfx/terobjs/arch/greenhouse", Collections.singletonList(new Door(Coord2d.of(22, 0), 16)));
	BUILDINGS.put("gfx/terobjs/arch/stonehut", Collections.singletonList(new Door(Coord2d.of(20, 0), 16)));
    }

    public static void run(GameUI gui) {
	OCache oc = gui.ui.sess.glob.oc;
	Gob player = oc.getgob(gui.plid);
	if(player == null) {return;}
	Coord2d plc = player.rc;
	Set<Kind> enabled = CFG.INTERACT_NEAREST_FOR.get();

	ITarget best = null;
	double bestDist = RADIUS;
	synchronized (oc) {
	    for (Gob gob : oc) {
		if(gob.id == player.id) {continue;}
		String res = gob.resid();
		if(res == null) {continue;}

		List<Door> doors = BUILDINGS.get(res);
		if(doors != null) {
		    if(!enabled.contains(Kind.DOORWAY)) {continue;}
		    for (Door door : doors) {
			Coord2d c = gob.rc.add(rotate(door.off, gob.a));
			double d = c.dist(plc);
			if(d <= bestDist) {
			    best = new DoorTarget(gob, c, door.id);
			    bestDist = d;
			}
		    }
		    continue;
		}

		Kind kind = classify(gob, res);
		if(kind == null || !enabled.contains(kind)) {continue;}
		double d = gob.rc.dist(plc);
		if(d <= bestDist) {
		    best = new GobTarget(gob);
		    bestDist = d;
		}
	    }
	}
	if(best != null) {
	    Bot.process(Collections.singletonList(best)).actions(ITarget::rclick).start(gui.ui, true);
	}
    }

    private static Kind classify(Gob gob, String res) {
	Kind gate = GATES.get(res);
	if(gate != null) {return gob.isVisitorGate() ? null : gate;}
	if(res.startsWith("gfx/terobjs/road/milestone-")) {return Kind.MILESTONE;}
	if(res.endsWith("-door")) {return Kind.DOORWAY;}
	if("gfx/terobjs/arch/cellardoor".equals(res)) {return Kind.CELLAR;}
	if("gfx/terobjs/minehole".equals(res) || "gfx/terobjs/ladder".equals(res)) {return Kind.MINEHOLE;}
	return null;
    }

    private static Coord2d rotate(Coord2d c, double a) {
	return Coord2d.of(c.x * Math.cos(a) - c.y * Math.sin(a), c.x * Math.sin(a) + c.y * Math.cos(a));
    }

    private static class Door {
	final Coord2d off;
	final int id;

	Door(Coord2d off, int id) {
	    this.off = off;
	    this.id = id;
	}
    }

    /** Clicks a specific door of a building gob: the click lands on the door's
     * world position with the door's mesh id, like a manual click on the door. */
    private static class DoorTarget implements ITarget {
	private final Gob gob;
	private final Coord2d pos;
	private final int door;

	DoorTarget(Gob gob, Coord2d pos, int door) {
	    this.gob = gob;
	    this.pos = pos;
	    this.door = door;
	}

	@Override
	public void rclick(int modflags) {
	    click(3, modflags);
	}

	@Override
	public void click(int button, int modflags) {
	    if(disposed()) {return;}
	    try {
		MapView map = gob.glob.sess.ui.gui.map;
		Coord mc = pos.floor(posres);
		map.click(pos, button, Coord.z, mc, button, modflags, 0, (int) gob.id, mc, 0, door);
	    } catch (Exception ignored) {}
	}

	@Override
	public void interact() {}

	@Override
	public void highlight() {
	    if(!disposed()) {gob.highlight();}
	}

	@Override
	public void take() {}

	@Override
	public void putBack() {}

	@Override
	public boolean hasMenu() {return false;}

	@Override
	public boolean disposed() {return gob == null || gob.disposed();}
    }

    public static class Opts extends WindowX {
	private static Opts instance;

	public static void toggle(Widget parent) {
	    if(instance == null) {
		instance = parent.add(new Opts());
	    } else {
		doClose();
	    }
	}

	private static void doClose() {
	    if(instance != null) {
		instance.reqdestroy();
		instance = null;
	    }
	}

	@Override
	public void destroy() {
	    super.destroy();
	    instance = null;
	}

	public Opts() {
	    super(Coord.z, "Interact with closest");
	    justclose = true;
	    int y = 0;
	    Set<Kind> selected = CFG.INTERACT_NEAREST_FOR.get();
	    for (Kind kind : Kind.values()) {
		CheckBox box = add(new CheckBox(kind.label, false), 0, y);
		box.a = selected.contains(kind);
		box.changed(val -> {
		    Set<Kind> kinds = CFG.INTERACT_NEAREST_FOR.get();
		    boolean changed = val ? kinds.add(kind) : kinds.remove(kind);
		    if(changed) {CFG.INTERACT_NEAREST_FOR.set(kinds);}
		});
		y += 25;
	    }

	    pack();
	    Coord asz = ca().sz();
	    if(asz.x < 200) {
		resize(new Coord(200, asz.y));
	    }
	}
    }
}
