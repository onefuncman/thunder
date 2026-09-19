package thunder;

import haven.Coord;
import haven.GOut;
import haven.MiniMap;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Saved-map visualization of Directional Forager's active cave route. */
public final class DirectionalForagerRouteOverlay {
    private static final Color TRAVELED = new Color(145, 155, 165, 125);
    private static final Color PLANNED = new Color(255, 135, 55, 235);
    private static final Color NEXT = new Color(255, 235, 70, 245);
    private static final Color END = new Color(90, 255, 120, 245);

    private static volatile Snapshot current = Snapshot.empty();

    private DirectionalForagerRouteOverlay() {}

    static synchronized void show(long segment, List<Coord> route, int activeIndex) {
        List<Coord> copy = copyTiles(route);
        current = new Snapshot(segment, copy, clamp(activeIndex, copy.size()));
    }

    static synchronized void showProgress(int activeIndex) {
        Snapshot old = current;
        if(old.route.isEmpty()) return;
        current = new Snapshot(old.segment, old.route, clamp(activeIndex, old.route.size()));
    }

    public static synchronized void clear() {
        current = Snapshot.empty();
    }

    static Snapshot snapshot() {return current;}

    /** Called by every MiniMap, including the large map window. */
    public static void paintMiniMap(GOut g, MiniMap map) {
        Snapshot snap = current;
        if(snap.route.isEmpty() || map == null) return;
        int active = clamp(snap.activeIndex, snap.route.size());
        paintRoute(g, map, snap.segment, snap.route, 0,
            Math.min(active + 1, snap.route.size()), TRAVELED, 1.5);
        paintRoute(g, map, snap.segment, snap.route, Math.max(0, active - 1),
            snap.route.size(), PLANNED, 2.5);
        if(active < snap.route.size())
            marker(g, map.savedTileToScreen(snap.segment, snap.route.get(active)), NEXT, 4);
        marker(g, map.savedTileToScreen(snap.segment,
            snap.route.get(snap.route.size() - 1)), END, 5);
        g.chcolor();
    }

    private static void paintRoute(GOut g, MiniMap map, long segment, List<Coord> route,
                                   int from, int to, Color color, double width) {
        if(route.size() < 2 || from >= to) return;
        g.chcolor(color);
        Coord previous = null;
        int end = Math.min(route.size(), to);
        for(int i = Math.max(0, from); i < end; i++) {
            Coord point = map.savedTileToScreen(segment, route.get(i));
            if(point == null) {
                previous = null;
                continue;
            }
            boolean last = i + 1 >= end;
            if(previous == null) {
                previous = point;
            } else if(last || pixelDistance(previous, point) >= 1.5) {
                g.line(previous, point, width);
                previous = point;
            }
        }
    }

    private static void marker(GOut g, Coord point, Color color, int radius) {
        if(point == null) return;
        g.chcolor(color);
        g.fellipse(point, Coord.of(radius, radius));
    }

    private static double pixelDistance(Coord a, Coord b) {
        long dx = (long)a.x - b.x, dy = (long)a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static int clamp(int index, int size) {
        return Math.max(0, Math.min(index, size));
    }

    private static List<Coord> copyTiles(List<Coord> route) {
        if(route == null || route.isEmpty()) return Collections.emptyList();
        List<Coord> copy = new ArrayList<>(route.size());
        for(Coord tile : route) if(tile != null) copy.add(new Coord(tile));
        return Collections.unmodifiableList(copy);
    }

    static final class Snapshot {
        final long segment;
        final List<Coord> route;
        final int activeIndex;

        Snapshot(long segment, List<Coord> route, int activeIndex) {
            this.segment = segment;
            this.route = route;
            this.activeIndex = activeIndex;
        }

        static Snapshot empty() {
            return new Snapshot(Long.MIN_VALUE, Collections.emptyList(), 0);
        }
    }
}
