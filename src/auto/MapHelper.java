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
}
