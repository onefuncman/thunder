package thunder.mining;

import haven.Area;
import haven.Coord;
import haven.MCache;
import haven.MapView;
import haven.Material;
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
 * Also owns the visual feedback: a live-updating colored rectangle (fill +
 * border) while dragging, and per-role persistent highlights for already-
 * designated zones (shown/hidden by MiningBotSetupWnd while it's open).
 */
public class ZonePicker implements MapView.Grabber {
    private static volatile ZonePicker active;
    private static final Map<String, ZoneVisual> persistent = new HashMap<>();
    private static final Map<String, Integer[]> ROLE_RGB = new HashMap<>();
    static {
        ROLE_RGB.put(MiningZoneStore.ROLE_STOCKPILE, new Integer[]{255, 160, 0});   // orange
        ROLE_RGB.put(MiningZoneStore.ROLE_WATER,     new Integer[]{0, 160, 255});   // blue
        ROLE_RGB.put(MiningZoneStore.ROLE_FOOD,      new Integer[]{0, 220, 100});   // green
    }
    private static final Integer[] DEFAULT_RGB = {255, 255, 255};

    private static MCache.OverlayInfo colorInfo(int r, int g, int b, int a) {
        return new MCache.OverlayInfo() {
            final Material mat = new Material(new BaseColor(r, g, b, a), States.maskdepth);
            public Collection<String> tags() {return Arrays.asList("show");}
            public Material mat() {return mat;}
        };
    }

    /** Bundles a translucent fill + four opaque edge strips so a flat-fill-only overlay system still reads as a bordered box. */
    private static class ZoneVisual {
        private final MapView map;
        private final MCache.Overlay fill, top, bottom, left, right;

        ZoneVisual(MapView map, Integer[] rgb, Area a) {
            this.map = map;
            MCache.OverlayInfo fillColor = colorInfo(rgb[0], rgb[1], rgb[2], 45);
            MCache.OverlayInfo borderColor = colorInfo(rgb[0], rgb[1], rgb[2], 220);
            MCache mc = map.glob.map;
            fill = mc.new RectOverlay(fillColor, a);
            top = mc.new RectOverlay(borderColor, edge(a, 0));
            bottom = mc.new RectOverlay(borderColor, edge(a, 1));
            left = mc.new RectOverlay(borderColor, edge(a, 2));
            right = mc.new RectOverlay(borderColor, edge(a, 3));
        }

        void update(Area a) {
            fill.update(a);
            top.update(edge(a, 0));
            bottom.update(edge(a, 1));
            left.update(edge(a, 2));
            right.update(edge(a, 3));
        }

        void destroy() {
            fill.destroy();
            top.destroy();
            bottom.destroy();
            left.destroy();
            right.destroy();
        }

        private static Area edge(Area a, int side) {
            Coord ul = a.ul, br = a.br;
            switch(side) {
            case 0: return new Area(ul, Coord.of(br.x, Math.min(br.y, ul.y + 1)));         // top strip
            case 1: return new Area(Coord.of(ul.x, Math.max(ul.y, br.y - 1)), br);         // bottom strip
            case 2: return new Area(ul, Coord.of(Math.min(br.x, ul.x + 1), br.y));         // left strip
            default: return new Area(Coord.of(Math.max(ul.x, br.x - 1), ul.y), br);        // right strip
            }
        }
    }

    private final MapView map;
    private final MapView.GrabXL xl;
    private final Integer[] previewRgb;
    private final Consumer<Area> onPicked;
    private Coord downTile;
    private ZoneVisual preview;

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
        return true;
    }

    public void mmousemove(Coord mc) {
        if(downTile == null || preview == null) {return;}
        preview.update(areaFor(downTile, mc.div(MCache.tilesz2)));
    }

    public boolean mmouseup(Coord mc, int button) {
        xl.mv = false;
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

    private static Area areaFor(Coord a, Coord b) {
        Coord ul = Coord.of(Math.min(a.x, b.x), Math.min(a.y, b.y));
        Coord br = Coord.of(Math.max(a.x, b.x) + 1, Math.max(a.y, b.y) + 1);
        return new Area(ul, br);
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
