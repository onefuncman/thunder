package haven.pathfinding;

import auto.Bot;
import haven.Area;
import haven.Coord2d;
import haven.GameUI;
import haven.MCache;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Candidate stand points used before exact object and stockpile placement. */
public final class PlacementStaging {
    private static final double LIFTED_STAGE_TILE_FRACTION = 0.75;

    private PlacementStaging() {}

    /**
     * Reachable stand points for an ordinary lifted-object placement. The
     * recorded tight-log placements finish from about twelve world units away;
     * three quarters of a tile leaves room for BotMovement's arrival tolerance
     * while keeping the player inside that confirmed placement reach.
     */
    public static List<Coord2d> liftedCandidates(Coord2d intended, Coord2d tileSize,
                                                  Coord2d player) {
        List<Coord2d> out = new ArrayList<Coord2d>();
        if (intended == null || tileSize == null) return out;
        double dx = tileSize.x * LIFTED_STAGE_TILE_FRACTION;
        double dy = tileSize.y * LIFTED_STAGE_TILE_FRACTION;
        out.add(intended.add(-dx, 0.0));
        out.add(intended.add(dx, 0.0));
        out.add(intended.add(0.0, -dy));
        out.add(intended.add(0.0, dy));
        if (player != null) out.sort(Comparator.comparingDouble(player::dist));
        return out;
    }

    public static List<Coord2d> candidates(Area area, Coord2d tileSize,
                                            Coord2d intended, Coord2d player) {
        List<Coord2d> out = new ArrayList<Coord2d>();
        if (area == null || !area.positive() || tileSize == null) return out;
        double x0 = area.ul.x * tileSize.x, y0 = area.ul.y * tileSize.y;
        double x1 = area.br.x * tileSize.x, y1 = area.br.y * tileSize.y;
        Coord2d target = intended == null ? Coord2d.of((x0 + x1) * 0.5, (y0 + y1) * 0.5) : intended;
        out.add(Coord2d.of(x0 - tileSize.x, clamp(target.y, y0, y1)));
        out.add(Coord2d.of(x1 + tileSize.x, clamp(target.y, y0, y1)));
        out.add(Coord2d.of(clamp(target.x, x0, x1), y0 - tileSize.y));
        out.add(Coord2d.of(clamp(target.x, x0, x1), y1 + tileSize.y));
        if (player != null) out.sort(Comparator.comparingDouble(player::dist));
        return out;
    }

    public static boolean stage(GameUI gui, Bot bot, Area area, Coord2d intended,
                                long timeoutMs, MovementListener listener)
        throws InterruptedException {
        if (gui == null || gui.map == null || bot == null || gui.map.player() == null ||
            gui.map.player().rc == null) return false;
        Coord2d player = gui.map.player().rc;
        List<Coord2d> candidates = candidates(area, MCache.tilesz, intended, player);
        if (candidates.isEmpty()) return false;
        for (Coord2d candidate : candidates) {
            if (player.dist(candidate) <= 4.0) return true;
        }
        MovementListener out = listener == null ? MovementListeners.NOOP : listener;
        out.beginWait("placement staging", timeoutMs, "move to any outside-area stand");
        BotMovement.Result result = BotMovement.moveToAny(
            gui, bot, candidates, BotMovement.Avoidance.NONE, BotMovement.Mode.LAND, timeoutMs);
        if (result.status == BotMovement.Status.ARRIVED) {
            out.event("placement staging arrived");
            return true;
        }
        out.fail("placement staging " + result.status);
        return false;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
