package thunder;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.MapView;
import haven.MiniMap;
import haven.dev.DebugDraw;
import haven.pathfinding.MapTileCoordinates;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Live, read-only visualization of River Musseler's current plans. */
public final class MusselRouteOverlay implements DebugDraw {
    private static final Color TRAVELED = new Color(145, 155, 165, 125);
    private static final Color PLANNED = new Color(55, 225, 255, 225);
    private static final Color DETOUR = new Color(255, 145, 45, 240);
    private static final Color LOCAL = new Color(235, 80, 255, 245);
    private static final Color ACTUAL = new Color(250, 250, 250, 235);
    private static final Color REQUESTED = new Color(255, 235, 70, 245);
    private static final Color EFFECTIVE = new Color(190, 105, 255, 245);
    private static final Color FAILED = new Color(255, 70, 70, 245);
    private static final Color NEXT = new Color(255, 235, 70, 245);
    private static final Color END = new Color(90, 255, 120, 245);
    private static final int WORLD_BEHIND = 24;
    private static final int WORLD_AHEAD = 110;

    static {
        DebugDraw.Registry.register(new MusselRouteOverlay());
    }

    private static volatile Snapshot current = Snapshot.empty();

    private MusselRouteOverlay() {}

    /** Forces the screen-space painter to register when the bot is first used. */
    public static void init() {}

    static synchronized void showMain(long segment, List<Coord> route, int activeIndex) {
        Snapshot old = current;
        List<Coord> main = copyTiles(route);
        current = new Snapshot(segment, main, clamp(activeIndex, main.size()),
            old.segment == segment ? old.savedDetour : Collections.emptyList(),
            old.segment == segment ? old.localRoute : Collections.emptyList(),
            old.segment == segment ? old.actualTrail : Collections.emptyList(),
            old.segment == segment ? old.requestedGoal : null,
            old.segment == segment ? old.effectiveGoal : null,
            old.segment == segment ? old.movementResult : null);
    }

    static synchronized void showProgress(int activeIndex) {
        Snapshot old = current;
        if(old.mainRoute.isEmpty()) return;
        current = new Snapshot(old.segment, old.mainRoute, clamp(activeIndex, old.mainRoute.size()),
            old.savedDetour, old.localRoute, old.actualTrail,
            old.requestedGoal, old.effectiveGoal, old.movementResult);
    }

    static synchronized void showSavedDetour(long segment, List<Coord> route) {
        Snapshot old = current;
        current = new Snapshot(segment,
            old.segment == segment ? old.mainRoute : Collections.emptyList(),
            old.segment == segment ? old.activeIndex : 0,
            copyTiles(route),
            old.segment == segment ? old.localRoute : Collections.emptyList(),
            old.segment == segment ? old.actualTrail : Collections.emptyList(),
            old.segment == segment ? old.requestedGoal : null,
            old.segment == segment ? old.effectiveGoal : null,
            old.segment == segment ? old.movementResult : null);
    }

    static synchronized void showLocalRoute(long segment, List<Coord2d> route) {
        Snapshot old = current;
        current = new Snapshot(segment,
            old.segment == segment ? old.mainRoute : Collections.emptyList(),
            old.segment == segment ? old.activeIndex : 0,
            old.segment == segment ? old.savedDetour : Collections.emptyList(),
            copyWorld(route),
            old.segment == segment ? old.actualTrail : Collections.emptyList(),
            old.segment == segment ? old.requestedGoal : null,
            old.segment == segment ? old.effectiveGoal : null,
            old.segment == segment ? old.movementResult : null);
    }

    /** Starts a visible diagnostic trace for one live movement command. */
    static synchronized void beginMovement(long segment, List<Coord2d> route,
                                           Coord2d requested, Coord2d effective,
                                           Coord2d start) {
        Snapshot old = current;
        List<Coord2d> trail = start == null
            ? Collections.emptyList() : Collections.singletonList(copyPoint(start));
        current = new Snapshot(segment,
            old.segment == segment ? old.mainRoute : Collections.emptyList(),
            old.segment == segment ? old.activeIndex : 0,
            old.segment == segment ? old.savedDetour : Collections.emptyList(),
            copyWorld(route), trail, copyPoint(requested), copyPoint(effective), null);
    }

    /** Records the path the boat actually traveled, not merely the requested path. */
    static synchronized void recordMovement(long segment, Coord2d position) {
        Snapshot old = current;
        if(position == null || old.segment != segment) return;
        if(!old.actualTrail.isEmpty()
            && old.actualTrail.get(old.actualTrail.size() - 1).dist(position) < 2.0) return;
        List<Coord2d> trail = new ArrayList<>(old.actualTrail);
        trail.add(copyPoint(position));
        if(trail.size() > 180) trail = new ArrayList<>(trail.subList(trail.size() - 180, trail.size()));
        current = new Snapshot(old.segment, old.mainRoute, old.activeIndex,
            old.savedDetour, old.localRoute, Collections.unmodifiableList(trail),
            old.requestedGoal, old.effectiveGoal, old.movementResult);
    }

    static synchronized void finishMovement(String result) {
        Snapshot old = current;
        current = new Snapshot(old.segment, old.mainRoute, old.activeIndex,
            old.savedDetour, old.localRoute, old.actualTrail,
            old.requestedGoal, old.effectiveGoal, result);
    }

    static synchronized void clearLocalRoute() {
        Snapshot old = current;
        if(old.localRoute.isEmpty()) return;
        current = new Snapshot(old.segment, old.mainRoute, old.activeIndex,
            old.savedDetour, Collections.emptyList(), old.actualTrail,
            old.requestedGoal, old.effectiveGoal, old.movementResult);
    }

    static synchronized void clearDetours() {
        Snapshot old = current;
        if(old.savedDetour.isEmpty() && old.localRoute.isEmpty()) return;
        current = new Snapshot(old.segment, old.mainRoute, old.activeIndex,
            Collections.emptyList(), Collections.emptyList(), old.actualTrail,
            old.requestedGoal, old.effectiveGoal, old.movementResult);
    }

    static synchronized void clearMain() {
        Snapshot old = current;
        Snapshot next = new Snapshot(old.segment, Collections.emptyList(), 0,
            old.savedDetour, old.localRoute, old.actualTrail,
            old.requestedGoal, old.effectiveGoal, old.movementResult);
        current = next.isEmpty() ? Snapshot.empty() : next;
    }

    public static synchronized void clear() {
        current = Snapshot.empty();
    }

    static Snapshot snapshot() {return current;}

    static String describe() {
        Snapshot snap = current;
        return String.format("segment=%x main=%d active=%d detour=%d local=%d trail=%d requested=%s effective=%s result=%s",
            snap.segment, snap.mainRoute.size(), snap.activeIndex, snap.savedDetour.size(),
            snap.localRoute.size(), snap.actualTrail.size(), snap.requestedGoal,
            snap.effectiveGoal, snap.movementResult);
    }

    @Override
    public CFG<Boolean> toggle() {return CFG.MUSSEL_ROUTE_OVERLAY;}

    @Override
    public void paint(GOut g, MapView mv) {
        Snapshot snap = current;
        if(snap.isEmpty() || mv == null || mv.ui == null || mv.ui.gui == null
            || mv.ui.gui.mapfile == null || mv.ui.gui.mapfile.view == null) return;
        MiniMap.Location session = mv.ui.gui.mapfile.view.sessloc;
        if(session == null || session.seg.id != snap.segment) return;

        int active = clamp(snap.activeIndex, snap.mainRoute.size());
        int from = Math.max(0, active - WORLD_BEHIND);
        int to = Math.min(snap.mainRoute.size(), Math.max(active + WORLD_AHEAD, from + 2));
        paintSavedWorld(g, mv, snap.mainRoute, session.tc, from, Math.min(active + 1, to), TRAVELED, 2.0);
        paintSavedWorld(g, mv, snap.mainRoute, session.tc, Math.max(from, active - 1), to, PLANNED, 3.0);
        paintSavedWorld(g, mv, snap.savedDetour, session.tc, 0, snap.savedDetour.size(), DETOUR, 3.0);
        paintWorld(g, mv, snap.localRoute, LOCAL, 3.0);
        paintWorld(g, mv, snap.actualTrail, ACTUAL, 2.0);

        if(snap.requestedGoal != null)
            markerWorld(g, mv, snap.requestedGoal, REQUESTED, 6, "REQ");
        if(snap.effectiveGoal != null && (snap.requestedGoal == null
            || snap.requestedGoal.dist(snap.effectiveGoal) > 1.0))
            markerWorld(g, mv, snap.effectiveGoal, EFFECTIVE, 6, "LIVE");
        if(snap.movementResult != null && !"ARRIVED".equals(snap.movementResult)
            && !snap.actualTrail.isEmpty())
            markerWorld(g, mv, snap.actualTrail.get(snap.actualTrail.size() - 1),
                FAILED, 7, snap.movementResult);

        if(active < snap.mainRoute.size())
            markerWorld(g, mv, MapTileCoordinates.worldPosition(snap.mainRoute.get(active), session.tc), NEXT, 6, "NEXT");
        if(!snap.mainRoute.isEmpty())
            markerWorld(g, mv, MapTileCoordinates.worldPosition(snap.mainRoute.get(snap.mainRoute.size() - 1), session.tc), END, 7, "END");
        g.chcolor();
    }

    /** Called by every MiniMap, including the large map window. */
    public static void paintMiniMap(GOut g, MiniMap map) {
        Snapshot snap = current;
        if(snap.isEmpty() || map == null || !Boolean.TRUE.equals(CFG.MUSSEL_ROUTE_OVERLAY.get())) return;
        int active = clamp(snap.activeIndex, snap.mainRoute.size());
        paintSavedMap(g, map, snap.segment, snap.mainRoute, 0, Math.min(active + 1, snap.mainRoute.size()), TRAVELED, 1.5);
        paintSavedMap(g, map, snap.segment, snap.mainRoute, Math.max(0, active - 1), snap.mainRoute.size(), PLANNED, 2.5);
        paintSavedMap(g, map, snap.segment, snap.savedDetour, 0, snap.savedDetour.size(), DETOUR, 2.5);
        paintWorldMap(g, map, snap.segment, snap.actualTrail, ACTUAL, 1.5);

        if(map.sessloc != null && map.sessloc.seg.id == snap.segment) {
            if(snap.requestedGoal != null)
                markerMap(g, savedWorldPoint(map, snap.segment, snap.requestedGoal), REQUESTED, 4);
            if(snap.effectiveGoal != null && (snap.requestedGoal == null
                || snap.requestedGoal.dist(snap.effectiveGoal) > 1.0))
                markerMap(g, savedWorldPoint(map, snap.segment, snap.effectiveGoal), EFFECTIVE, 4);
            if(snap.movementResult != null && !"ARRIVED".equals(snap.movementResult)
                && !snap.actualTrail.isEmpty())
                markerMap(g, savedWorldPoint(map, snap.segment,
                    snap.actualTrail.get(snap.actualTrail.size() - 1)), FAILED, 5);
        }

        if(active < snap.mainRoute.size())
            markerMap(g, map.savedTileToScreen(snap.segment, snap.mainRoute.get(active)), NEXT, 4);
        if(!snap.mainRoute.isEmpty())
            markerMap(g, map.savedTileToScreen(snap.segment, snap.mainRoute.get(snap.mainRoute.size() - 1)), END, 5);
        g.chcolor();
    }

    private static void paintSavedWorld(GOut g, MapView mv, List<Coord> route, Coord sessionTile,
                                        int from, int to, Color color, double width) {
        if(route == null || route.size() < 2 || from >= to) return;
        g.chcolor(color);
        Coord previous = null;
        for(int i = Math.max(0, from); i < Math.min(route.size(), to); i++) {
            Coord point = screen(mv, MapTileCoordinates.worldPosition(route.get(i), sessionTile));
            if(point == null) {
                previous = null;
            } else {
                if(previous != null) g.line(previous, point, width);
                previous = point;
            }
        }
    }

    private static void paintWorld(GOut g, MapView mv, List<Coord2d> route, Color color, double width) {
        if(route == null || route.size() < 2) return;
        g.chcolor(color);
        Coord previous = null;
        for(Coord2d world : route) {
            Coord point = screen(mv, world);
            if(point == null) {
                previous = null;
            } else {
                if(previous != null) g.line(previous, point, width);
                previous = point;
            }
        }
    }

    private static void paintSavedMap(GOut g, MiniMap map, long segment, List<Coord> route,
                                      int from, int to, Color color, double width) {
        if(route == null || route.size() < 2 || from >= to) return;
        g.chcolor(color);
        Coord previous = null;
        for(int i = Math.max(0, from); i < Math.min(route.size(), to); i++) {
            Coord point = map.savedTileToScreen(segment, route.get(i));
            if(point == null) {
                previous = null;
                continue;
            }
            boolean last = i + 1 >= Math.min(route.size(), to);
            if(previous != null && (last || pixelDistance(previous, point) >= 1.5)) {
                g.line(previous, point, width);
                previous = point;
            } else if(previous == null) {
                previous = point;
            }
        }
    }

    private static void paintWorldMap(GOut g, MiniMap map, long segment,
                                      List<Coord2d> route, Color color, double width) {
        if(route == null || route.size() < 2 || map.sessloc == null
            || map.sessloc.seg.id != segment) return;
        g.chcolor(color);
        Coord previous = null;
        for(Coord2d world : route) {
            Coord point = savedWorldPoint(map, segment, world);
            if(point == null) {
                previous = null;
            } else {
                if(previous != null && !previous.equals(point)) g.line(previous, point, width);
                previous = point;
            }
        }
    }

    private static Coord savedWorldPoint(MiniMap map, long segment, Coord2d world) {
        if(map == null || map.sessloc == null || world == null || map.sessloc.seg.id != segment)
            return null;
        Coord tile = MapTileCoordinates.playerTile(world, map.sessloc.tc);
        return map.savedTileToScreen(segment, tile);
    }

    private static void markerWorld(GOut g, MapView mv, Coord2d world, Color color, int radius, String label) {
        Coord point = screen(mv, world);
        if(point == null) return;
        g.chcolor(color);
        g.line(point.add(-radius, -radius), point.add(radius, radius), 2.0);
        g.line(point.add(-radius, radius), point.add(radius, -radius), 2.0);
        if(label != null) g.atext(label, point.add(radius + 3, -radius), 0, 0);
    }

    private static void markerMap(GOut g, Coord point, Color color, int radius) {
        if(point == null) return;
        g.chcolor(color);
        g.fellipse(point, Coord.of(radius, radius));
    }

    private static Coord screen(MapView mv, Coord2d world) {
        if(world == null) return null;
        Coord3f projected;
        try {projected = mv.screenxf(mv.glob.map.getzp(world));}
        catch(RuntimeException failure) {projected = mv.screenxf(world);}
        if(projected == null) return null;
        Coord point = Coord.of(Math.round(projected.x), Math.round(projected.y));
        int margin = 80;
        return point.x >= -margin && point.y >= -margin
            && point.x <= mv.sz.x + margin && point.y <= mv.sz.y + margin ? point : null;
    }

    private static double pixelDistance(Coord a, Coord b) {
        long dx = (long)a.x - b.x, dy = (long)a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static int clamp(int index, int size) {return Math.max(0, Math.min(index, size));}

    private static List<Coord> copyTiles(List<Coord> route) {
        if(route == null || route.isEmpty()) return Collections.emptyList();
        List<Coord> copy = new ArrayList<>(route.size());
        for(Coord tile : route) if(tile != null) copy.add(new Coord(tile));
        return Collections.unmodifiableList(copy);
    }

    private static List<Coord2d> copyWorld(List<Coord2d> route) {
        if(route == null || route.isEmpty()) return Collections.emptyList();
        List<Coord2d> copy = new ArrayList<>(route.size());
        for(Coord2d point : route) if(point != null) copy.add(Coord2d.of(point.x, point.y));
        return Collections.unmodifiableList(copy);
    }

    private static Coord2d copyPoint(Coord2d point) {
        return point == null ? null : Coord2d.of(point.x, point.y);
    }

    static final class Snapshot {
        final long segment;
        final List<Coord> mainRoute;
        final int activeIndex;
        final List<Coord> savedDetour;
        final List<Coord2d> localRoute;
        final List<Coord2d> actualTrail;
        final Coord2d requestedGoal;
        final Coord2d effectiveGoal;
        final String movementResult;

        Snapshot(long segment, List<Coord> mainRoute, int activeIndex,
                 List<Coord> savedDetour, List<Coord2d> localRoute,
                 List<Coord2d> actualTrail, Coord2d requestedGoal,
                 Coord2d effectiveGoal, String movementResult) {
            this.segment = segment;
            this.mainRoute = mainRoute;
            this.activeIndex = activeIndex;
            this.savedDetour = savedDetour;
            this.localRoute = localRoute;
            this.actualTrail = actualTrail;
            this.requestedGoal = requestedGoal;
            this.effectiveGoal = effectiveGoal;
            this.movementResult = movementResult;
        }

        boolean isEmpty() {
            return mainRoute.isEmpty() && savedDetour.isEmpty() && localRoute.isEmpty()
                && actualTrail.isEmpty() && requestedGoal == null && effectiveGoal == null;
        }

        static Snapshot empty() {
            return new Snapshot(Long.MIN_VALUE, Collections.emptyList(), 0,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, null, null);
        }
    }
}
