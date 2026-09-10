package auto;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.pathfinding.CoarseTileNavigator;
import haven.pathfinding.NamedPlaceNavigator;
import haven.pathfinding.RecedingHorizonNavigator;
import haven.pathfinding.WaypointWalker;

/**
 * Real tile-aware pathfinding for MiningBot's supply-zone walks, built on
 * SolomonIbnDavid/thunder's nav-core (haven.pathfinding + HavenNavigationCore's
 * GridAStar): a proper route over the player's own explored MapFile data,
 * routed around obstacles, instead of MapHelper.walkTo's single click-and-hope
 * straight line. Fixes an observed live bug: walkTo tried to walk straight
 * through a mined tunnel's own rock wall whenever the supply zone wasn't in a
 * direct line of sight from the current tunnel segment (it needs to retreat
 * back down the tunnel's own axis first) -- real pathfinding subsumes that
 * case rather than special-casing tunnel-axis retracing by hand.
 */
public class MiningNav {
    private MiningNav() {}

    // DEFAULT is (100 legs, 5 replans, 1_000_000 expanded) -- generous enough for
    // a long trek back to the surface without hanging forever if genuinely stuck.
    private static final RecedingHorizonNavigator.Bounds BOUNDS = RecedingHorizonNavigator.Bounds.DEFAULT;
    // Per-leg walk budget handed to the live walker -- separate from BOUNDS'
    // maxLegs (how many legs the whole route may take), this is how long any
    // single leg gets before that leg is considered stuck.
    private static final long LEG_BUDGET_MS = 60000L;
    // Padding around the straight-line box between start and goal tile so the
    // coarse planner has room to route around obstacles rather than being
    // boxed in exactly along the direct line.
    private static final int PAD_TILES = 24;
    // Fallback timeout when nav-core's session/mapfile state isn't ready yet
    // (e.g. right after login, before the minimap has resolved a segment) --
    // same value MiningMaterials already uses for its own zone-walk fallback.
    private static final long FALLBACK_TIMEOUT_MS = 25000L;

    private static NamedPlaceNavigator.Location currentLocation(GameUI gui) {
        if (gui.mapfile == null || gui.mapfile.file == null || gui.mapfile.view == null) {return null;}
        return NamedPlaceNavigator.liveState(gui).current();
    }

    /**
     * Walks to a world position via real tile-based pathfinding. Falls back to
     * MapHelper.walkTo (the old straight-line behavior) only when nav-core's
     * own prerequisites (mapfile + resolved session location) aren't available --
     * a genuinely different, narrower condition than "the walk is hard."
     */
    public static boolean walkTo(GameUI gui, Bot bot, Coord2d target, double arriveRadius) {
        NamedPlaceNavigator.Location start = currentLocation(gui);
        if (start == null) {
            MiningBot.diag("[minebot-diag] MiningNav.walkTo: no session location yet, falling back to MapHelper.walkTo");
            return MapHelper.walkTo(gui, target, FALLBACK_TIMEOUT_MS, arriveRadius);
        }
        Coord goalTile = NamedPlaceNavigator.playerTile(target, gui.mapfile.view.sessloc.tc);
        Area bounds = boundsFor(start.tile, goalTile, PAD_TILES);
        CoarseTileNavigator nav = CoarseTileNavigator.live(
            gui, gui.mapfile.file, bot, BOUNDS, LEG_BUDGET_MS, WaypointWalker.Params.DEFAULT, NamedPlaceNavigator.NOOP);
        CoarseTileNavigator.Run run = nav.navigate(goalTile, bounds);
        MiningBot.diag("[minebot-diag] MiningNav.walkTo: target=%s goalTile=%s bounds=%s -> %s", target, goalTile, bounds, run);
        return run.reached();
    }

    private static Area boundsFor(Coord a, Coord b, int pad) {
        Coord ul = new Coord(Math.min(a.x, b.x) - pad, Math.min(a.y, b.y) - pad);
        Coord lr = new Coord(Math.max(a.x, b.x) + pad, Math.max(a.y, b.y) + pad);
        return Area.corn(ul, lr);
    }
}
