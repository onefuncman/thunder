package auto;

import haven.*;
import haven.resutil.WaterTile;

public class MapHelper {
    
    public static boolean isPlayerOnFreshWaterTile(GameUI gui) {
	return isFreshWaterTile(gui, gui.map.player().rc.floor(MCache.tilesz));
    }
    
    private static final Coord[] NEIGHBORS = {
	Coord.of(0, 1),
	Coord.of(1, 0),
	Coord.of(0, -1),
	Coord.of(-1, 0),
	//diagonals
	Coord.of(1, 1),
	Coord.of(-1, 1),
	Coord.of(1, -1),
	Coord.of(-1, -1),
    };
    
    public static Coord2d nearbyWaterTile(GameUI gui) {
	Gob player = gui.map.player();
	Coord tc = player.rc.floor(MCache.tilesz);
	
	Coord2d result = null;
	double closest = Double.MAX_VALUE;
	
	for (Coord d : NEIGHBORS) {
	    Coord n = tc.add(d);
	    if(!isFreshWaterTile(gui, n)) {continue;}
	    //TODO: instead of tile center get closest to player spot in this tile
	    Coord2d c = tileCenter(n);
	    double dist = c.dist(player.rc);
	    if(dist < closest) {
		closest = dist;
		result = c;
	    }
	}
	
	return result;
    }
    
    private static Coord2d tileCenter(Coord tc) {
	return MCache.tilesz.mul(tc.x, tc.y).add(5, 5);
    }
    
    public static boolean isFreshWaterTile(GameUI gui, Coord tc) {
	MCache mcache = gui.ui.sess.glob.map;
	int t = mcache.gettile(tc);
	Tiler tl = mcache.tiler(t);
	if(!(tl instanceof WaterTile)) {
	    return false;
	}

	Resource res = mcache.tilesetr(t);
	if(res == null) {
	    return false;
	}

	return res.name.equals("gfx/tiles/water") || res.name.equals("gfx/tiles/deep");
    }

    public static boolean isSaltWaterTile(GameUI gui, Coord tc) {
	MCache mcache = gui.ui.sess.glob.map;
	int t = mcache.gettile(tc);
	Resource res = mcache.tilesetr(t);
	if(res == null) {
	    return false;
	}
	String n = res.name;
	return n.equals("gfx/tiles/owater") || n.equals("gfx/tiles/odeep") || n.equals("gfx/tiles/odeeper");
    }

    /* Known already-dug cave/mine floor tile names (walkable, nothing left to mine).
     * Deliberately an allow-list, not a wall blacklist -- missing a floor variant here
     * just means MiningBot conservatively still attempts to mine it (matches existing
     * behavior, a no-op mine swing that's slow but not wrong); missing a wall variant
     * from a blacklist would wrongly skip mining a tile that still needs it, which is
     * the actually unsafe direction to be wrong in. "gfx/tiles/caveobsidian" is left
     * out for the same reason -- unconfirmed whether it's a floor or wall variant. */
    private static final String[] MINED_FLOOR_TILES = {
	"gfx/tiles/cave", "gfx/tiles/cavefloor", "gfx/tiles/mine", "gfx/tiles/rough",
    };

    /** True if tc is already open, walkable cave/mine floor -- nothing left to mine there. */
    public static boolean isMinedFloorTile(GameUI gui, Coord tc) {
	MCache mcache = gui.ui.sess.glob.map;
	int t = mcache.gettile(tc);
	Resource res = mcache.tilesetr(t);
	if(res == null) {
	    return false;
	}
	for(String name : MINED_FLOOR_TILES) {
	    if(res.name.equals(name)) {return true;}
	}
	return false;
    }

    /**
     * Walk to a world coordinate and confirm arrival by position, with its own
     * timeout. The only existing walk-and-wait precedent (Actions.refillDrinks)
     * waits for the /walking or /running pose to start then stop, which times
     * out silently if the click resolves without ever posing (e.g. already
     * adjacent) and never actually checks the player reached the target.
     */
    public static boolean walkTo(GameUI gui, Coord2d target, long timeoutMs) {
	return walkTo(gui, target, timeoutMs, MCache.tilesz.x * 0.6);
    }

    /**
     * Walk to a world coordinate and confirm arrival by position, with its own
     * timeout and an explicit arrival radius. The default 0.6-tile radius (see the
     * 3-arg overload) is right for walking to an empty tile, but wrong for walking
     * to a solid gob (a container, a barrel): collision stops the player short of
     * the gob's own center point, so the player visibly arrives right next to it
     * while still failing that tight a distance check forever -- confirmed live,
     * MiningMaterials' container/barrel walks kept reporting arrived=false despite
     * the character visibly walking there and stopping right beside it.
     */
    public static boolean walkTo(GameUI gui, Coord2d target, long timeoutMs, double arriveRadius) {
	Gob player = gui.map.player();
	if(player == null) {return false;}
	Coord2d startPos = player.rc;
	gui.map.click(target, 1);

	long deadline = System.currentTimeMillis() + timeoutMs;
	boolean arrived = false;
	while(System.currentTimeMillis() < deadline) {
	    if(player.disposed()) {break;}
	    if(player.rc.dist(target) <= arriveRadius) {arrived = true; break;}
	    BotUtil.pause(100);
	}
	if(!arrived && !player.disposed()) {arrived = player.rc.dist(target) <= arriveRadius;}
	Debug.log.printf("[minebot-diag] walkTo target=%s from=%s to=%s arriveRadius=%.1f arrived=%b%n",
	    target, startPos, player.disposed() ? "disposed" : player.rc, arriveRadius, arrived);
	Debug.log.flush();
	return arrived;
    }

    /** Arrival radius for walking to a solid gob (container, barrel) -- collision keeps the
     * player from ever reaching the gob's own center, unlike an empty tile target. */
    public static final double GOB_ARRIVE_RADIUS = MCache.tilesz.x * 1.5;
}
