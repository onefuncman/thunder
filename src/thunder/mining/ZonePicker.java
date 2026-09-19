package thunder.mining;

import haven.Area;
import haven.Coord;
import haven.MCache;
import haven.MapView;
import haven.Material;
import haven.UI;
import haven.render.BaseColor;
import haven.render.States;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Click-and-drag rectangle picker on the main map view: mirrors
 * thunder.macro.MacroPicker's static-armed-slot lifecycle (start/active/cancel),
 * but captures a full mousedown->mouseup drag via MapView.Grabber/GrabXL
 * instead of a single click. Purely local -- nothing is sent to the server,
 * unlike the mine-tile Selector this is modeled on.
 *
 * Also owns the visual feedback: a live-updating tinted rectangle while
 * dragging, and per-role persistent highlights for already-
 * designated zones (shown or hidden by the owning setup window).
 */
public class ZonePicker implements MapView.Grabber {
    private static volatile ZonePicker active;
    private static final Map<String, ZoneVisual> persistent = new HashMap<>();
    private static final Map<String, Integer[]> ROLE_RGB = new HashMap<>();

    /** Registers a per-role highlight color without coupling this picker to the
     * feature that owns the zone. */
    public static synchronized void registerRoleColor(String role, Integer[] rgb) {
        ROLE_RGB.put(role, rgb);
    }
    private static final Integer[] DEFAULT_RGB = {255, 255, 255};

    private static MCache.OverlayInfo colorInfo(int r, int g, int b, int a) {
        return new MCache.OverlayInfo() {
            final Material mat = new Material(new BaseColor(r, g, b, a), States.maskdepth);
            public Collection<String> tags() {return Arrays.asList("show");}
            public Material mat() {return mat;}
        };
    }

    /** A translucent area highlight without a separate border. */
    private static class ZoneVisual {
        private final MCache.Overlay fill;

        ZoneVisual(MapView map, Integer[] rgb, Area a) {
            MCache.OverlayInfo fillColor = colorInfo(rgb[0], rgb[1], rgb[2], 45);
            MCache mc = map.glob.map;
            fill = mc.new RectOverlay(fillColor, a);
        }

        void update(Area a) {
            fill.update(a);
        }

        void destroy() {
            fill.destroy();
        }
    }

    private final MapView map;
    private final MapView.GrabXL xl;
    private final Integer[] previewRgb;
    private final Consumer<Area> onPicked;
    private Coord downTile;
    private ZoneVisual preview;
    private UI.Grab mouseGrab;

    private ZonePicker(MapView map, Integer[] previewRgb, Consumer<Area> onPicked) {
        this.map = map;
        this.previewRgb = previewRgb;
        this.onPicked = onPicked;
        this.xl = map.new GrabXL(this);
    }

    public static void start(MapView map, String role, Consumer<Area> onPicked) {
        start(map, ROLE_RGB.getOrDefault(role, DEFAULT_RGB), onPicked);
    }

    public static synchronized void start(MapView map, Integer[] previewRgb, Consumer<Area> onPicked) {
        cancel();
        ZonePicker picker = new ZonePicker(map, previewRgb, onPicked);
        active = picker;
        map.grab(picker.xl);
    }

    public static synchronized boolean active() {
        return active != null;
    }

    /** Releases the grab and drops the drag preview without firing a callback. Safe to call when nothing is armed. */
    public static synchronized void cancel() {
        if(active != null) {
            active.xl.mv = false;
            active.releaseMouseGrab();
            active.map.release(active.xl);
            if(active.preview != null) {active.preview.destroy();}
            active = null;
        }
    }

    public boolean mmousedown(Coord mc, int button) {
        if(button != 1) {return false;}
        downTile = mc.div(MCache.tilesz2);
        preview = new ZoneVisual(map, previewRgb, areaFor(downTile, downTile));
        xl.mv = true; // GrabXL only forwards mmousemove while this is set (see Selector)
        // Keep receiving the release even when a large drag ends over the setup
        // window or another widget. Without this, the old saved zone remains in
        // place and can look like a newly selected 1x1 area at runtime.
        releaseMouseGrab();
        mouseGrab = map.ui.grabmouse(map);
        return true;
    }

    public void mmousemove(Coord mc) {
        if(downTile == null || preview == null) {return;}
        preview.update(areaFor(downTile, mc.div(MCache.tilesz2)));
    }

    public boolean mmouseup(Coord mc, int button) {
        xl.mv = false;
        releaseMouseGrab();
        if(downTile == null) {return true;}
        Area area = areaFor(downTile, mc.div(MCache.tilesz2));

        Consumer<Area> cb = onPicked;
        synchronized(ZonePicker.class) {
            if(active == this) {
                map.release(xl);
                active = null;
            }
        }
        if(preview != null) {preview.destroy(); preview = null;}
        cb.accept(area);
        return true;
    }

    public boolean mmousewheel(Coord mc, int amount) {return false;}

    static Area areaFor(Coord a, Coord b) {
        Coord ul = Coord.of(Math.min(a.x, b.x), Math.min(a.y, b.y));
        Coord br = Coord.of(Math.max(a.x, b.x) + 1, Math.max(a.y, b.y) + 1);
        return new Area(ul, br);
    }

    private void releaseMouseGrab() {
        if(mouseGrab != null) {
            mouseGrab.remove();
            mouseGrab = null;
        }
    }

    /** Shows (or repositions) a persistent colored highlight for a designated zone. */
    public static synchronized void showZone(MapView map, String role, Area area) {
        ZoneVisual v = persistent.get(role);
        if(v != null) {
            v.update(area);
        } else {
            persistent.put(role, new ZoneVisual(map, ROLE_RGB.getOrDefault(role, DEFAULT_RGB), area));
        }
    }

    public static synchronized void hideZone(String role) {
        ZoneVisual v = persistent.remove(role);
        if(v != null) {v.destroy();}
    }

    public static synchronized void hideAllZones() {
        for(ZoneVisual v : persistent.values()) {v.destroy();}
        persistent.clear();
    }
}
