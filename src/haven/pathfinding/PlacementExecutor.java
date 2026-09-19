package haven.pathfinding;

import auto.Bot;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.OCache;
import java.util.List;

/** Shared live-ghost protocol for exact object and stockpile placement. */
public final class PlacementExecutor {
    private PlacementExecutor() {}

    public enum Outcome {
        COMMIT_SENT,
        STAGING_FAILED,
        PLAYER_OVERLAPS_STOCKPILE,
        PLACER_NOT_ARMED,
        COMMIT_FAILED
    }

    public static final class Result {
        public final Outcome outcome;
        public final Coord2d target;
        public final String detail;

        private Result(Outcome outcome, Coord2d target, String detail) {
            this.outcome = outcome;
            this.target = target;
            this.detail = detail == null ? "" : detail;
        }

        public boolean sent() { return outcome == Outcome.COMMIT_SENT; }
    }

    /**
     * Stages using player-only navigation, arms the lifted-object placer with a
     * ground right-click, and commits through the live ghost in fine mode.
     * Success verification remains with the caller because ordinary objects may
     * fail silently and have object-specific authoritative signals.
     */
    public static Result commitLifted(GameUI gui, Bot bot, Gob carried, Area area,
                                      Coord2d target, double angle,
                                      long walkTimeoutMs, long armTimeoutMs,
                                      MovementListener listener)
        throws InterruptedException {
        Result staged = stageLifted(gui, bot, target, walkTimeoutMs, listener);
        if (staged != null) return staged;
        if (!gui.map.isPlacing()) {
            Object[] click = (carried == null) ? null : liftedObjectClickArgs(carried.id, carried.rc);
            if (click == null) {
                return new Result(Outcome.PLACER_NOT_ARMED, target,
                    "carried object is unavailable for the placement right-click");
            }
            gui.map.wdgmsg("click", click);
            if (!waitFor(bot, armTimeoutMs, gui.map::isPlacing)) {
                return new Result(Outcome.PLACER_NOT_ARMED, target, "lifted-object placement ghost did not appear");
            }
        }
        if (!gui.map.warpAndCommitPlacementExact(target, angle, 1, 0)) {
            return new Result(Outcome.COMMIT_FAILED, target, "live placement ghost could not be committed");
        }
        return new Result(Outcome.COMMIT_SENT, target, "");
    }

    /**
     * Builds the same gob-targeted right-click that a player sends by clicking
     * the object carried overhead. A ground-only four-argument click does not
     * identify the carried gob, so the server never opens its placement ghost.
     */
    static Object[] liftedObjectClickArgs(long carriedId, Coord2d carriedPosition) {
        if (carriedPosition == null) return null;
        Coord mc = carriedPosition.floor(OCache.posres);
        return new Object[] {Coord.z, mc, 3, 0, 0, (int)carriedId, mc, 0, -1};
    }

    /** Same protocol for stockpile creation, with the confirmed player-overlap restriction. */
    public static Result commitStockpile(GameUI gui, Bot bot, Area area, Coord2d target,
                                         double angle, ExactPlacementPlanner.Shape placedShape,
                                         long walkTimeoutMs, long armTimeoutMs,
                                         MovementListener listener)
        throws InterruptedException {
        Result staged = stage(gui, bot, area, target, walkTimeoutMs, listener);
        if (staged != null) return staged;
        Gob player = gui.map.player();
        double clearance = player == null ? 6.25 : MovementScene.agentRadius(player) + 0.75;
        if (player == null || player.rc == null || placedShape == null ||
            !PlacementEgress.clearOf(placedShape, player.rc, clearance)) {
            return new Result(Outcome.PLAYER_OVERLAPS_STOCKPILE, target,
                "player overlaps the future stockpile hitbox");
        }
        if (!gui.map.isPlacing()) {
            if (!gui.map.itemactAt(target, 0)) {
                return new Result(Outcome.PLACER_NOT_ARMED, target, "stockpile itemact could not be sent");
            }
            if (!waitFor(bot, armTimeoutMs, gui.map::isPlacing)) {
                return new Result(Outcome.PLACER_NOT_ARMED, target, "stockpile placement ghost did not appear");
            }
        }
        if (!gui.map.warpAndCommitPlacementExact(target, angle, 1, 0)) {
            return new Result(Outcome.COMMIT_FAILED, target, "live placement ghost could not be committed");
        }
        return new Result(Outcome.COMMIT_SENT, target, "");
    }

    private static Result stage(GameUI gui, Bot bot, Area area, Coord2d target,
                                long walkTimeoutMs, MovementListener listener)
        throws InterruptedException {
        if (gui == null || gui.map == null || bot == null || area == null || target == null) {
            return new Result(Outcome.STAGING_FAILED, target, "invalid placement request");
        }
        if (!PlacementStaging.stage(gui, bot, area, target, walkTimeoutMs, listener)) {
            return new Result(Outcome.STAGING_FAILED, target,
                "NavCore could not reach a point one tile outside the selected area");
        }
        return null;
    }

    /** Reach a collision-free point inside the recorded placement range before
     * asking the server to perform its short final placement step. */
    private static Result stageLifted(GameUI gui, Bot bot, Coord2d target,
                                      long walkTimeoutMs, MovementListener listener)
        throws InterruptedException {
        if (gui == null || gui.map == null || bot == null || target == null) {
            return new Result(Outcome.STAGING_FAILED, target, "invalid lifted placement request");
        }
        MovementListener out = listener == null ? MovementListeners.NOOP : listener;
        Gob player = gui.map.player();
        Coord2d at = player == null ? null : player.rc;
        List<Coord2d> candidates = PlacementStaging.liftedCandidates(target, MCache.tilesz, at);
        if (candidates.isEmpty()) {
            return new Result(Outcome.STAGING_FAILED, target,
                "lifted placement staging candidates unavailable");
        }
        out.beginWait("lifted placement staging", walkTimeoutMs,
            "move collision-aware within placement reach of the chosen anchor");
        BotMovement.Result movement = BotMovement.moveToAny(
            gui, bot, candidates, BotMovement.Avoidance.NONE,
            BotMovement.Mode.LAND, walkTimeoutMs);
        if (movement.status != BotMovement.Status.ARRIVED) {
            String detail = movement.status + (movement.detail.isEmpty() ? "" : ": " + movement.detail);
            out.fail("lifted placement staging " + detail);
            return new Result(Outcome.STAGING_FAILED, target, detail);
        }
        out.event("lifted placement staging arrived");
        return null;
    }

    private interface Check { boolean get(); }

    private static boolean waitFor(Bot bot, long timeoutMs, Check check) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            bot.checkCancelled();
            if (check.get()) return true;
            Thread.sleep(50L);
        }
        return check.get();
    }
}
