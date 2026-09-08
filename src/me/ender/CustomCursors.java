package me.ender;

import haven.*;
import me.ender.minimap.Minesweeper;

import java.util.function.Consumer;

import static haven.MCache.*;

public class CustomCursors {
    public static final Resource.Named INSPECT = Resource.local().loadwait("gfx/hud/curs/studyx").indir();
    public static final Resource.Named TRACK = Resource.local().loadwait("gfx/hud/curs/track").indir();
    public static final Resource.Named SWEEPER = Resource.local().loadwait("gfx/hud/curs/minesweep").indir();
    // Distinct Named so PICK != INSPECT by reference, even though they share a cursor image.
    public static final Resource.Named PICK = new Resource.Named(INSPECT.name, INSPECT.ver) {
	public Resource get() {return INSPECT.get();}
    };
    private static Consumer<Gob> pickCallback;
    private static Consumer<Coord2d> markGroundCallback;
    private static Consumer<Coord2d> markAreaCallback;
    private static Runnable markAreaCancel;
    private static Coord2d markAreaStart;
    private static int markAreaClicks = 0;
    private static int markAreaMaxClicks = 2;
    private static MCache.RectOverlay markAreaOverlay;
    private static MapView markAreaMap;
    private static boolean pickConsumeEmpty;
    private static boolean pickShowTooltip;


    public static boolean processHit(MapView map, Coord2d mc, ClickData inf) {
	UI ui = map.ui;

	if(isPicking(map)) {
	    if(markAreaCallback != null) {
		if(markingAreaCtrlPass(map)) {
		    return false; // Ctrl = pass through to normal click-to-move
		}
		boolean first = markAreaStart == null;
		if(first) markAreaStart = mc;
		updateAreaOverlay(map, mc);
		markAreaCallback.accept(mc);
		markAreaClicks++;
		if(markAreaClicks >= markAreaMaxClicks) stopPicking(map, false, true);
		return true;
	    }
	    if(markGroundCallback != null) {
		markGroundCallback.accept(mc);
		stopPicking(map, true, false);
		return true;
	    }
	    if(inf == null) {
		if(pickConsumeEmpty) { stopPicking(map, true, false); return true; }
		return false;
	    }
	    Gob gob = Gob.from(inf.ci);
	    if(gob == null) {
		if(pickConsumeEmpty) { stopPicking(map, true, false); return true; }
		return false;
	    }
	    if(pickCallback != null) pickCallback.accept(gob);
	    stopPicking(map, true, false);
	    return true;
	} else if(isTracking(map)) {
	    if(inf == null) {return false;}
	    Gob gob = Gob.from(inf.ci);
	    if(gob == null) {return false;}
	    
	    ui.gui.mapfile.track(gob);
	    stopTracking(map);
	    return true;
	} else if(isSweeping(map)) {
	    int modflags = ui.modflags();
	    byte value;
	    
	    if(modflags == 0) {
		value = Minesweeper.FLAG_DANGER;
	    } else if(modflags == UI.MOD_CTRL) {
		value = Minesweeper.CLEAR_FLAGS;
	    } else if(modflags == UI.MOD_META) {
		value = Minesweeper.FLAG_SAFE;
	    } else if(modflags == UI.MOD_SHIFT) {
		value = Minesweeper.FLAG_MAYBE;
	    } else {
		return true;
	    }
	    
	    Minesweeper.markFlagAtPoint(mc, value, ui.gui);
	    return true;
	}
	
	return false;
    }
    
    public static boolean processDown(MapView map, Widget.MouseDownEvent ev) {
	if(ev.b == 3) {
	    if(isInspecting(map)) {
		stopInspecting(map);
		return true;
	    } else if(isTracking(map)) {
		stopTracking(map);
		return true;
	    } else if(isSweeping(map)) {
		stopSweeping(map);
		return true;
	    } else if(isPicking(map)) {
		stopPicking(map, true, false);
		return true;
	    }

	}
	return false;
    }
    
    public static void inspect(MapView map, Coord c) {
	if(map.cursor == PICK && !pickShowTooltip) return;
	boolean isMining = map.cursor == null && isMining(map.ui);
	if(map.cursor == INSPECT || map.cursor == TRACK || (map.cursor == PICK && pickShowTooltip) || isMining) {
	    map.new Hittest(c) {
		@Override
		protected void hit(Coord pc, Coord2d mc, ClickData inf) {
		    String tip = null;
		    if(inf != null && !isMining) {
			Gob gob = Gob.from(inf.ci);
			if(gob != null) {
			    tip = (map.cursor == INSPECT || map.cursor == PICK) ? gob.inspect(map.fullTip) : gob.tooltip();
			}
		    } else if(map.cursor == INSPECT || isMining) {
			MCache mCache = map.glob.map;
			int tile = mCache.gettile(mc.div(tilesz).floor());
			Resource res = mCache.tilesetr(tile);
			if(res != null) {
			    if(isMining) {
				Resource.Tooltip tooltip = res.layer(Resource.tooltip);
				if(tooltip != null) {
				    tip = tooltip.t;
				}
			    } else {
				tip = res.name;
			    }
			}
		    }
		    map.ttip(tip);
		}
		
		@Override
		protected void nohit(Coord pc) {
		    map.ttip(null);
		}
	    }.run();
	} else {
	    map.ttip(null);
	}
    }
    
    public static boolean isMining(UI ui) {
	Indir<Resource> cursor = ui.root.cursor;
	if(cursor == null) {return false;}
	try {
	    return "gfx/hud/curs/mine".equals(cursor.get().name);
	} catch (Loading ignore) {}
	return false;
    }
    
    private static void stopCustomModes(MapView map) {
	stopInspecting(map);
	stopTracking(map);
	stopSweeping(map);
	stopPicking(map, true, false);
    }
    
    //INSPECTING
    
    public static boolean isInspecting(MapView map) {
	return map.cursor == INSPECT;
    }
    
    public static void toggleInspectMode(MapView map) {
	if(isInspecting(map)) {
	    stopInspecting(map);
	} else {
	    startInspecting(map);
	}
    }
    
    private static void startInspecting(MapView map) {
	stopCustomModes(map);
	if(map.cursor == null) {
	    map.cursor = INSPECT;
	    inspect(map, map.rootxlate(map.ui.mc));
	}
    }
    
    private static void stopInspecting(MapView map) {
	if(map.cursor == INSPECT) {
	    map.cursor = null;
	    map.ttip(null);
	}
    }
    
    //TRACKING
    
    public static boolean isTracking(MapView map) {
	return map.cursor == TRACK;
    }
    
    public static void toggleTrackingMode(MapView map) {
	if(isTracking(map)) {
	    stopTracking(map);
	} else {
	    startTracking(map);
	}
    }
    
    private static void startTracking(MapView map) {
	stopCustomModes(map);
	if(map.cursor == null) {
	    map.cursor = TRACK;
	    inspect(map, map.rootxlate(map.ui.mc));
	}
    }
    
    private static void stopTracking(MapView map) {
	if(map.cursor == TRACK) {
	    map.cursor = null;
	    map.ttip(null);
	}
    }
    
    //Mine SWEEPER
    public static boolean isSweeping(MapView map) {
	return map.cursor == SWEEPER;
    }
    
    public static void toggleSweeperMode(MapView map) {
	if(isSweeping(map)) {
	    stopSweeping(map);
	} else {
	    startSweeping(map);
	}
    }
    
    private static void startSweeping(MapView map) {
	stopCustomModes(map);
	if(map.cursor == null) {
	    map.cursor = SWEEPER;
	    map.ttip(null);
	}
    }
    
    private static void stopSweeping(MapView map) {
	if(map.cursor == SWEEPER) {
	    map.cursor = null;
	    map.ttip(null);
	}
    }

    //GOB PICKING
    public static boolean isPicking(MapView map) {
	return map.cursor == PICK;
    }

    public static void startPicking(MapView map, Consumer<Gob> callback) {
	startPicking(map, callback, false, false);
    }

    public static void startPicking(MapView map, Consumer<Gob> callback, boolean consumeEmpty, boolean showTooltip) {
	stopCustomModes(map);
	if(map.cursor == null) {
	    pickCallback = callback;
	    pickConsumeEmpty = consumeEmpty;
	    pickShowTooltip = showTooltip;
	    map.cursor = PICK;
	    if(showTooltip)
		inspect(map, map.rootxlate(map.ui.mc));
	}
    }

    public static void startMarkingGround(MapView map, Consumer<Coord2d> callback) {
	stopCustomModes(map);
	if(map.cursor == null) {
	    markGroundCallback = callback;
	    pickCallback = null;
	    pickConsumeEmpty = false;
	    pickShowTooltip = false;
	    map.cursor = PICK;
	}
    }

    /** Starts a two-click, tile-aligned area picker. The callback receives each click. */
    public static void startMarkingArea(MapView map, Consumer<Coord2d> callback, Runnable cancelled) {
	startMarkingArea(map, callback, cancelled, 2);
    }

    public static void startMarkingArea(MapView map, Consumer<Coord2d> callback, Runnable cancelled, int maxClicks) {
	stopCustomModes(map);
	clearAreaOverlay();
	if(map.cursor == null) {
	    markAreaCallback = callback;
	    markAreaCancel = cancelled;
	    markAreaStart = null;
	    markAreaClicks = 0;
	    markAreaMaxClicks = Math.max(2, maxClicks);
	    markAreaMap = map;
	    pickCallback = null;
	    markGroundCallback = null;
	    pickConsumeEmpty = false;
	    pickShowTooltip = false;
	    map.cursor = PICK;
	}
    }

    public static boolean isMarkingArea(MapView map) {
	return map != null && map.cursor == PICK && markAreaCallback != null;
    }

    /** True when area-marking is active and Ctrl is held — the click should pass through as a normal move. */
    public static boolean markingAreaCtrlPass(MapView map) {
	return isMarkingArea(map) && (map.ui.modflags() & UI.MOD_CTRL) != 0;
    }

    private static void updateAreaOverlay(MapView map, Coord2d end) {
	if(markAreaStart == null || map == null) return;
	Coord a = markAreaStart.floor(MCache.tilesz);
	Coord b = end.floor(MCache.tilesz);
	Coord ul = new Coord(Math.min(a.x, b.x), Math.min(a.y, b.y));
	Coord br = new Coord(Math.max(a.x, b.x), Math.max(a.y, b.y));
	if(markAreaOverlay != null) map.glob.map.remove(markAreaOverlay);
	markAreaOverlay = map.glob.map.new RectOverlay(MapView.selol, new Area(ul, br.add(1, 1)));
	map.glob.map.add(markAreaOverlay);
    }

    private static void clearAreaOverlay() {
	if(markAreaOverlay != null && markAreaMap != null && markAreaMap.glob != null)
	    markAreaMap.glob.map.remove(markAreaOverlay);
	markAreaOverlay = null;
	markAreaMap = null;
    }

    private static void stopPicking(MapView map, boolean notifyCancel, boolean preserveArea) {
	if(map.cursor == PICK) {
	    map.cursor = null;
	    map.ttip(null);
	    pickCallback = null;
	    markGroundCallback = null;
	    if(markAreaCallback != null && notifyCancel && markAreaCancel != null) markAreaCancel.run();
	    markAreaCallback = null;
	    markAreaCancel = null;
	    markAreaStart = null;
	    markAreaClicks = 0;
	    markAreaMaxClicks = 2;
	    if(!preserveArea) clearAreaOverlay();
	}
    }

}
