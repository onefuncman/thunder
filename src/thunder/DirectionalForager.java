package thunder;

import auto.Actions;
import auto.Bot;
import auto.GobTarget;
import haven.*;
import haven.pathfinding.BotMovement;
import haven.pathfinding.CaveRoutePlanner;
import haven.pathfinding.MapFileCaveSource;
import haven.pathfinding.MapTileCoordinates;
import haven.pathfinding.MovementScene;
import haven.pathfinding.PathfinderLog;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;

/** Long-running, player-started compass forager. */
public final class DirectionalForager {
    private static final double FORWARD_STEP = MCache.tilesz.x * 10.0;
    private static final double LOCAL_APPROACH_RADIUS = MCache.tilesz.x * 8.0;
    private static final double FORAGEABLE_STANDOFF = 8.0;
    private static final double FORAGEABLE_INTERACTION_RANGE = 16.5;
    private static final double DANGER_RADIUS = MCache.tilesz.x * 12.0;
    private static final int MAX_TARGET_STAGE_PASSES = 8;
    private static final int CAVE_ROUTE_LEG_TILES = 8;
    private static final int CAVE_PLAN_DEPTH_TILES = 640;
    private static final int CAVE_PLAN_MAX_TILES = 50_000;
    private static final int CAVE_VISIBILITY_RADIUS_TILES = 30;
    private static final int CAVE_SEEN_EXIT_RADIUS_TILES = CAVE_VISIBILITY_RADIUS_TILES + 4;
    // Live MCache reads are only useful near the player; the saved-map
    // allowlist carries the long-range 640-tile strategic route.
    private static final int CAVE_LIVE_TERRAIN_RADIUS_TILES = 64;
    private static final int CAVE_FAILED_BARRIER_RADIUS_TILES = 1;
    private static final int MAX_CAVE_BLOCKED_REPLANS = 12;
    private static final double CAVE_ROUTE_REJOIN_TILES = 6.0;
    private static final String DEBUG_LOG = "logs/directional-forager-debug.log";
    private static final double[][] DIRECTION_ANGLE_TIERS = {
        {0},
        {Math.PI / 16, -Math.PI / 16},
        {Math.PI / 8, -Math.PI / 8},
        {Math.PI / 4, -Math.PI / 4}
    };
    private static final double[][] FORAGEABLE_APPROACH_ANGLE_TIERS = {
        {0},
        {Math.PI / 4, -Math.PI / 4},
        {Math.PI / 2, -Math.PI / 2},
        {3 * Math.PI / 4, -3 * Math.PI / 4},
        {Math.PI}
    };
    private static volatile boolean running;
    private static volatile String status = "Status: idle";
    private static volatile int collected;
    private static volatile String debugRunId = "none";
    private static Bot active;

    private DirectionalForager() {}
    public static boolean isRunning() {return running;}
    public static String status() {return status;}
    public static int collected() {return collected;}

    public static synchronized void start(GameUI gui, DirectionalForagerLogic.Direction direction, Set<String> whitelist, boolean caveMode) {
        if(running) {gui.error("Directional Forager is already running."); return;}
        if(gui == null || gui.map == null || gui.map.player() == null) return;
        if(whitelist == null || whitelist.isEmpty()) {gui.error("Directional Forager: select at least one forageable."); return;}
        if(caveMode && (gui.mapfile == null || gui.mapfile.playerLocation() == null)) {
            gui.error("Directional Forager: the saved cave map is not ready yet.");
            return;
        }
        if(Bot.hasCurrent()) {gui.error("Directional Forager: another task is already running."); return;}
        final Set<String> selected = new HashSet<>(whitelist);
        PathfinderLog.resetRun();
        String runId = Long.toString(System.currentTimeMillis(), 36) + "-"
            + UUID.randomUUID().toString().substring(0, 6);
        debugRunId = runId;
        running = true; collected = 0; status = "Status: starting";
        DirectionalForagerRouteOverlay.clear();
        Gob player = gui.map.player();
        debug("START build=%s direction=%s cave=%s player=(%.1f,%.1f) selected=%s",
            Config.version, direction, caveMode, player.rc.x, player.rc.y, new TreeSet<>(selected));
        Bot bot = Bot.execute((unused, b) -> {
            try {new Run(gui, b, direction, selected, caveMode).execute();}
            catch(RuntimeException | Error failure) {
                status = "Status: error — see directional forager log";
                debugFailure(failure);
                throw failure;
            }
            finally {
                debug("STOP status=%s collected=%d", status, collected);
                DirectionalForagerRouteOverlay.clear();
                running = false; active = null;
                try {if(gui.pathQueue != null) gui.pathQueue.clear();} catch(Exception ignored) {}
                if(status.equals("Status: stopping")) status = "Status: stopped";
            }
        });
        active = bot;
        bot.start(gui.ui, true);
    }

    public static void stop() {
        Bot bot = active;
        if(bot == null || !running) return;
        status = "Status: stopping";
        bot.cancel("Directional Forager stopped by user.");
    }

    private static synchronized void debug(String format, Object... args) {
        try {
            File file = new File(DEBUG_LOG);
            File parent = file.getParentFile();
            if(parent != null) parent.mkdirs();
            try(PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                out.printf(Locale.ROOT, "%tFT%<tT.%<tL run=%s %s%n", new Date(), debugRunId,
                    String.format(Locale.ROOT, format, args));
            }
        } catch(Exception ignored) {}
    }

    private static synchronized void debugFailure(Throwable failure) {
        try {
            File file = new File(DEBUG_LOG);
            File parent = file.getParentFile();
            if(parent != null) parent.mkdirs();
            try(PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                out.printf(Locale.ROOT, "%tFT%<tT.%<tL run=%s ERROR%n", new Date(), debugRunId);
                failure.printStackTrace(out);
            }
        } catch(Exception ignored) {}
    }

    private static final class Run {
        final GameUI gui;
        final Bot bot;
        final DirectionalForagerLogic.Direction direction;
        final Set<String> selected;
        final Map<Long, Long> retryAfter = new HashMap<>();
        final boolean caveMode;
        long caveRouteSegment = Long.MIN_VALUE;
        CaveRoutePlanner.Plan cavePlan;
        MapFileCaveSource caveSource;
        final Set<Coord> caveSeen = new HashSet<>();
        final Set<Coord> caveBlocked = new HashSet<>();
        final Set<Coord> caveTraversed = new HashSet<>();
        int caveRouteIndex;
        int caveRouteChunk;
        int caveBlockedReplans;
        boolean caveCoverageExhausted;

        Run(GameUI gui, Bot bot, DirectionalForagerLogic.Direction direction, Set<String> selected, boolean caveMode) {
            this.gui = gui; this.bot = bot; this.direction = direction; this.selected = selected; this.caveMode = caveMode;
        }

        void execute() throws InterruptedException {
            while(true) {
                bot.checkCancelled();
                Gob player = gui.map.player();
                if(player == null || player.rc == null) stopWith("Status: player unavailable");
                observeCatalog();
                Gob target = nearest(player.rc);
                if(target != null) {
                    if(!hasCollectionSpace()) stopWith(fullMessage());
                    status = "Status: collecting " + display(target);
                    if(collect(target)) collected++;
                    else if(target.rc != null && safeFromDanger(target.rc))
                        retryAfter.put(target.id, System.currentTimeMillis() + 30000L);
                    continue;
                }
                if(caveMode) {
                    if(!walkCaveRoute()) {
                        if(!dangerCenters().isEmpty()) {
                            status = "Status: avoiding danger — waiting to continue cave route";
                            Thread.sleep(750L);
                            continue;
                        }
                        stopWith("Status: stopped — cave route is not reachable");
                    }
                } else {
                    status = "Status: walking " + direction.name().toLowerCase(Locale.ROOT);
                    if(!walkForward(player.rc)) {
                        if(!dangerCenters().isEmpty()) {
                            status = "Status: avoiding danger — finding a safe way forward";
                            Thread.sleep(750L);
                            continue;
                        }
                        stopWith("Status: stopped — no route forward");
                    }
                }
            }
        }

        /** Keep the preferred heading, but make nearby forward endpoints valid goals too. */
        boolean walkForward(Coord2d from) throws InterruptedException {
            Coord2d origin = from;
            double[] distances = {FORWARD_STEP, MCache.tilesz.x * 8.0, MCache.tilesz.x * 6.0};
            for(int tier = 0; tier < DIRECTION_ANGLE_TIERS.length; tier++) {
                for(double distance : distances) {
                    bot.checkCancelled();
                    Gob player = gui.map.player();
                    if(player != null && player.rc != null) origin = player.rc;
                    List<Coord2d> requested = DirectionalForagerLogic.forwardProbes(
                        origin, direction, distance, DIRECTION_ANGLE_TIERS[tier]);
                    BotMovement.Result result = moveAny(requested);
                    debugMovement("FORWARD-" + tier, result, requested.size(), distance, -1L);
                    if(arrived(result)) return true;
                }
            }
            return false;
        }

        /** Follow a monotonic route planned over the current saved-map cave segment. */
        boolean walkCaveRoute() throws InterruptedException {
            CaveContext context = caveContext();
            if(context == null) {
                stopWith("Status: stopped — saved cave map unavailable");
                return false;
            }
            if(caveRouteSegment != context.segment) resetCaveRoute(context.segment);
            markCaveCoverage(context.tile);
            markCaveTraversed(context.tile);
            if(cavePlan == null && !planCaveRoute(context)) return false;

            context = caveContext();
            if(context == null || context.segment != caveRouteSegment) return false;
            syncCaveRoute(context, "before-leg");
            if(caveRouteIndex >= cavePlan.route.size()) {
                debug("CAVE-CHUNK-COMPLETE chunk=%d route=%d seen=%d at=%s",
                    caveRouteChunk, cavePlan.route.size(), caveSeen.size(), context.tile);
                cavePlan = null;
                caveRouteIndex = 0;
                DirectionalForagerRouteOverlay.clear();
                caveBlockedReplans = 0;
                if(!planCaveRoute(context)) {
                    if(caveCoverageExhausted)
                        stopWith(String.format(Locale.ROOT,
                            "Status: stopped — cave coverage complete (%d collected)", collected));
                    return false;
                }
            }

            int goalIndex = DirectionalForagerLogic.lookahead(
                cavePlan.route, caveRouteIndex, context.tile, CAVE_ROUTE_LEG_TILES);
            int attemptIndex = goalIndex;
            int coverageStart = Math.max(0, caveRouteIndex - 1);
            while(attemptIndex >= caveRouteIndex) {
                bot.checkCancelled();
                Coord goalTile = cavePlan.route.get(attemptIndex);
                Coord2d goal = MapTileCoordinates.worldPosition(goalTile, context.sessionTile);
                status = String.format(Locale.ROOT, "Status: following cave route chunk %d (%d/%d)",
                    caveRouteChunk, caveRouteIndex, cavePlan.route.size() - 1);
                BotMovement.Result result = moveAny(Collections.singletonList(goal));
                debugMovement("CAVE-ROUTE-" + caveRouteIndex + "-" + attemptIndex,
                    result, 1, context.world.dist(goal), -1L);
                if(arrived(result)) {
                    markCaveCoverage(cavePlan.route, coverageStart, attemptIndex);
                    markCaveTraversed(cavePlan.route, coverageStart, attemptIndex);
                    caveRouteIndex = Math.max(caveRouteIndex, attemptIndex);
                    CaveContext after = caveContext();
                    if(after != null && after.segment == caveRouteSegment) {
                        markCaveCoverage(after.tile);
                        markCaveTraversed(after.tile);
                        syncCaveRoute(after, "after-leg");
                    }
                    return true;
                }
                if(attemptIndex == caveRouteIndex) break;
                attemptIndex = Math.max(caveRouteIndex,
                    caveRouteIndex + (attemptIndex - caveRouteIndex) / 2);
            }
            if(!dangerCenters().isEmpty()) return false;
            CaveContext blockedAt = caveContext();
            if(blockedAt != null && blockedAt.segment == caveRouteSegment
                && caveBlockedReplans < MAX_CAVE_BLOCKED_REPLANS) {
                markCaveCoverage(blockedAt.tile);
                int liveBlocks = observeLiveCaveTerrain(blockedAt);
                Coord barrier = DirectionalForagerLogic.firstBlockedRouteTile(
                    cavePlan.route, blockedAt.tile, caveRouteIndex, goalIndex);
                int learned = markFailedCaveBarrier(barrier, blockedAt.tile);
                caveBlockedReplans++;
                debug("CAVE-BLOCKED-REPLAN retry=%d/%d stopped=%s barrier=%s live_new=%d learned=%d blocked=%d",
                    caveBlockedReplans, MAX_CAVE_BLOCKED_REPLANS, blockedAt.tile,
                    barrier, liveBlocks, learned, caveBlocked.size());
                status = "Status: cave wall found — planning around it";
                cavePlan = null;
                caveRouteIndex = 0;
                DirectionalForagerRouteOverlay.clear();
                PathfinderLog.invalidateOccupancy();
                return true;
            }
            PathfinderLog.dumpFailure("directional forager cave route blocked");
            return false;
        }

        boolean planCaveRoute(CaveContext initial) throws InterruptedException {
            status = "Status: planning cave route";
            caveCoverageExhausted = false;
            long started = System.currentTimeMillis();
            CaveRoutePlanner.Plan plan = null;
            CaveContext context = initial;
            for(int attempt = 0; attempt < 4; attempt++) {
                bot.checkCancelled();
                if(caveSource == null || caveSource.segmentId() != context.segment)
                    caveSource = new MapFileCaveSource(context.file, context.segment);
                else
                    caveSource.refreshMissing();
                caveBlocked.remove(context.tile);
                markCaveTraversed(context.tile);
                int liveBlocks = observeLiveCaveTerrain(context);
                final MapFileCaveSource savedSource = caveSource;
                final Coord routeStart = new Coord(context.tile);
                CaveRoutePlanner.Source routeSource = tile -> DirectionalForagerLogic.caveRouteBlocked(
                    tile, routeStart, caveBlocked, caveTraversed,
                    caveSeen, CAVE_SEEN_EXIT_RADIUS_TILES)
                    ? CaveRoutePlanner.Cell.BLOCKED : savedSource.cell(tile);
                debug("CAVE-PLAN-BEGIN attempt=%d segment=%x start=%s cached_grids=%d missing_grids=%d live_new=%d blocked=%d traversed=%d",
                    attempt + 1, context.segment, context.tile,
                    caveSource.cachedGridCount(), caveSource.missingGridCount(),
                    liveBlocks, caveBlocked.size(), caveTraversed.size());
                plan = CaveRoutePlanner.plan(routeSource, context.tile,
                    CAVE_PLAN_MAX_TILES, CAVE_PLAN_DEPTH_TILES, caveSeen);
                CaveContext fresh = caveContext();
                if(fresh == null || fresh.segment != context.segment) return false;
                double drift = tileDistance(context.tile, fresh.tile);
                if(plan.usable() && drift <= 2.5) {context = fresh; break;}
                debug("CAVE-PLAN-RETRY attempt=%d status=%s drift=%.1f route=%d",
                    attempt + 1, plan.status, drift, plan.route.size());
                plan = null;
                context = fresh;
                Thread.sleep(500L);
            }
            if(plan == null || !plan.usable()) {
                debug("CAVE-PLAN-FAILED start=%s elapsed=%dms",
                    context == null ? "unavailable" : context.tile,
                    System.currentTimeMillis() - started);
                return false;
            }
            if(plan.route.size() <= 1 || plan.unseenVisibilityScore <= 0) {
                caveCoverageExhausted = true;
                debug("CAVE-PLAN-EXHAUSTED chunk=%d start=%s route=%d unseen=%d seen=%d elapsed=%dms",
                    caveRouteChunk + 1, context.tile, plan.route.size(),
                    plan.unseenVisibilityScore, caveSeen.size(),
                    System.currentTimeMillis() - started);
                return false;
            }
            cavePlan = plan;
            caveRouteChunk++;
            caveRouteIndex = plan.route.size() > 1 ? 1 : plan.route.size();
            syncCaveRoute(context, "planned");
            DirectionalForagerRouteOverlay.show(
                context.segment, plan.route, caveRouteIndex);
            debug("CAVE-PLAN status=%s chunk=%d segment=%x start=%s dest=%s route=%d distance=%.1f floor=%d visibility=%d unseen=%d seen=%d depth=%d frontier=%s frontiers=%d elapsed=%dms",
                plan.status, caveRouteChunk, context.segment, context.tile, plan.destination,
                plan.route.size(), plan.distanceTiles, plan.knownFloor,
                plan.visibilityScore, plan.unseenVisibilityScore, caveSeen.size(),
                CAVE_PLAN_DEPTH_TILES, plan.frontier, plan.frontierCount,
                System.currentTimeMillis() - started);
            return true;
        }

        void markCaveCoverage(Coord tile) {
            if(tile == null) return;
            int radius = CAVE_VISIBILITY_RADIUS_TILES;
            int radiusSquared = radius * radius;
            for(int y = -radius; y <= radius; y++)
                for(int x = -radius; x <= radius; x++)
                    if(x * x + y * y <= radiusSquared)
                        caveSeen.add(tile.add(x, y));
        }

        void markCaveCoverage(List<Coord> route, int from, int through) {
            if(route == null || route.isEmpty()) return;
            int first = Math.max(0, from);
            int last = Math.min(route.size() - 1, through);
            for(int i = first; i <= last; i++) markCaveCoverage(route.get(i));
        }

        void markCaveTraversed(Coord tile) {
            if(tile != null) caveTraversed.add(new Coord(tile));
        }

        void markCaveTraversed(List<Coord> route, int from, int through) {
            if(route == null || route.isEmpty()) return;
            int first = Math.max(0, from);
            int last = Math.min(route.size() - 1, through);
            for(int i = first; i <= last; i++) markCaveTraversed(route.get(i));
        }

        int observeLiveCaveTerrain(CaveContext context) {
            if(context == null || context.world == null) return 0;
            Coord liveCenter = context.world.floor(MCache.tilesz);
            int radius = CAVE_LIVE_TERRAIN_RADIUS_TILES;
            int radiusSquared = radius * radius;
            int added = 0;
            for(int y = -radius; y <= radius; y++) {
                for(int x = -radius; x <= radius; x++) {
                    if(x * x + y * y > radiusSquared) continue;
                    Coord savedTile = liveCenter.add(x, y).add(context.sessionTile);
                    if(savedTile.equals(context.tile)) continue;
                    try {
                        if(MovementScene.liveCaveTerrainBlocks(gui, liveCenter.add(x, y))
                            && caveBlocked.add(savedTile)) added++;
                    } catch(Loading ignored) {
                    } catch(RuntimeException ignored) {
                    }
                }
            }
            return added;
        }

        int markFailedCaveBarrier(Coord barrier, Coord current) {
            if(barrier == null) return 0;
            int before = caveBlocked.size();
            int radius = CAVE_FAILED_BARRIER_RADIUS_TILES;
            for(int y = -radius; y <= radius; y++)
                for(int x = -radius; x <= radius; x++)
                    if(x * x + y * y <= radius * radius)
                        caveBlocked.add(barrier.add(x, y));
            if(current != null) caveBlocked.remove(current);
            return caveBlocked.size() - before;
        }

        void syncCaveRoute(CaveContext context, String cause) {
            if(cavePlan == null || cavePlan.route.isEmpty() || context == null) return;
            int before = caveRouteIndex;
            DirectionalForagerLogic.RejoinDecision decision = DirectionalForagerLogic.rejoinDecision(
                cavePlan.route, context.tile, caveRouteIndex, 8, 128, CAVE_ROUTE_REJOIN_TILES);
            caveRouteIndex = Math.max(caveRouteIndex, decision.selectedIndex);
            while(caveRouteIndex < cavePlan.route.size()
                && tileDistance(context.tile, cavePlan.route.get(caveRouteIndex)) <= 1.25)
                caveRouteIndex++;
            if(before != caveRouteIndex || !decision.accepted)
                debug("CAVE-CURSOR cause=%s before=%d after=%d nearest=%d gap=%.2f accepted=%s current=%s",
                    cause, before, caveRouteIndex, decision.nearestIndex,
                    decision.nearestDistance, decision.accepted, context.tile);
            DirectionalForagerRouteOverlay.showProgress(caveRouteIndex);
        }

        void resetCaveRoute(long segment) {
            debug("CAVE-SEGMENT old=%x new=%x reset route", caveRouteSegment, segment);
            caveRouteSegment = segment;
            cavePlan = null;
            caveSource = null;
            caveSeen.clear();
            caveBlocked.clear();
            caveTraversed.clear();
            caveRouteIndex = 0;
            caveRouteChunk = 0;
            caveBlockedReplans = 0;
            caveCoverageExhausted = false;
            DirectionalForagerRouteOverlay.clear();
        }

        CaveContext caveContext() {
            if(gui == null || gui.map == null || gui.mapfile == null) return null;
            MiniMap.Location location = gui.mapfile.playerLocation();
            Gob player = gui.map.player();
            if(location == null || player == null || player.rc == null) return null;
            Coord tile = MapTileCoordinates.playerTile(player.rc, location.tc);
            return new CaveContext(gui.mapfile.file, location.seg.id, location.tc, tile, player.rc);
        }

        double tileDistance(Coord a, Coord b) {
            return Math.hypot(a.x - b.x, a.y - b.y);
        }

        void observeCatalog() {
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) ForageCatalog.observe(gob);
            }
        }

        Gob nearest(Coord2d at) {
            List<Gob> gobs = new ArrayList<>();
            List<Coord2d> dangers = dangerCenters();
            long now = System.currentTimeMillis();
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(gob != null && gob.rc != null && !gob.disposed() && retryAfter.getOrDefault(gob.id, 0L) <= now
                        && ForageCatalog.isSelected(gob, selected) && safeFromDanger(gob.rc, dangers)) gobs.add(gob);
                }
            }
            gobs.sort(Comparator.comparingDouble(g -> at.dist(g.rc)));
            return gobs.isEmpty() ? null : gobs.get(0);
        }

        boolean collect(Gob gob) throws InterruptedException {
            if(gob == null || gob.disposed() || gob.rc == null || !safeFromDanger(gob.rc)) return false;
            Gob player = gui.map.player();
            debug("TARGET id=%d resid=%s distance=%.1f at=(%.1f,%.1f)", gob.id, resource(gob),
                player == null || player.rc == null ? -1.0 : player.rc.dist(gob.rc), gob.rc.x, gob.rc.y);
            if(!makeMainRoom()) return false;
            player = gui.map.player();
            if(player == null || player.rc == null) return false;
            if(player.rc.dist(gob.rc) > LOCAL_APPROACH_RADIUS && !stageToward(gob)) {
                debug("TARGET-STAGE-FAILED id=%d", gob.id);
                return false;
            }
            if(gob.disposed() || gob.rc == null || !safeFromDanger(gob.rc)) return false;
            if(!approachForageable(gob)) return false;
            if(gob.disposed() || gob.rc == null || !safeFromDanger(gob.rc)) return false;
            long before = storageSignature();
            boolean removed = Actions.pickupTarget(new GobTarget(gob), bot, 15000L);
            boolean changed = removed && storageSignature() != before;
            debug("PICKUP id=%d removed=%s storageChanged=%s", gob.id, removed, changed);
            return DirectionalForagerLogic.pickupConfirmed(removed, changed);
        }

        /** Move through bounded local legs until the target is in local approach range. */
        boolean stageToward(Gob target) throws InterruptedException {
            for(int pass = 0; pass < MAX_TARGET_STAGE_PASSES; pass++) {
                bot.checkCancelled();
                if(target == null || target.disposed() || target.rc == null) return false;
                Gob player = gui.map.player();
                if(player == null || player.rc == null) return false;
                double before = player.rc.dist(target.rc);
                if(before <= LOCAL_APPROACH_RADIUS) return true;
                boolean moved = false;
                for(int tier = 0; tier < DIRECTION_ANGLE_TIERS.length; tier++) {
                    player = gui.map.player();
                    if(player == null || player.rc == null || target.disposed() || target.rc == null) return false;
                    double remaining = player.rc.dist(target.rc);
                    if(remaining <= LOCAL_APPROACH_RADIUS) return true;
                    double step = Math.min(FORWARD_STEP,
                        Math.max(MCache.tilesz.x * 4.0, remaining - LOCAL_APPROACH_RADIUS));
                    List<Coord2d> requested = DirectionalForagerLogic.towardProbes(
                        player.rc, target.rc, step, DIRECTION_ANGLE_TIERS[tier]);
                    requested.removeIf(candidate -> !safeFromDanger(candidate));
                    if(requested.isEmpty()) continue;
                    BotMovement.Result result = moveAny(requested);
                    debugMovement("TARGET-STAGE-" + pass + "-" + tier,
                        result, requested.size(), step, target.id);
                    if(arrived(result)) {moved = true; break;}
                }
                if(!moved) return false;
                player = gui.map.player();
                if(player == null || player.rc == null || target.disposed() || target.rc == null) return false;
                double after = player.rc.dist(target.rc);
                debug("TARGET-STAGE-PROGRESS id=%d pass=%d before=%.1f after=%.1f", target.id, pass, before, after);
                if(after >= before - MCache.tilesz.x) return false;
            }
            Gob player = gui.map.player();
            return target != null && !target.disposed() && target.rc != null
                && player != null && player.rc != null
                && player.rc.dist(target.rc) <= LOCAL_APPROACH_RADIUS;
        }

        /** Forageables are collision-free point targets, so use a small stand
         * ring instead of requiring solid-object footprint geometry. */
        boolean approachForageable(Gob target) throws InterruptedException {
            for(int tier = 0; tier < FORAGEABLE_APPROACH_ANGLE_TIERS.length; tier++) {
                bot.checkCancelled();
                if(target == null || target.disposed() || target.rc == null) return false;
                Gob player = gui.map.player();
                if(player == null || player.rc == null) return false;
                double distance = player.rc.dist(target.rc);
                if(distance <= FORAGEABLE_INTERACTION_RANGE) {
                    debug("FORAGE-READY id=%d distance=%.1f", target.id, distance);
                    return true;
                }
                List<Coord2d> requested = DirectionalForagerLogic.approachProbes(
                    player.rc, target.rc, FORAGEABLE_STANDOFF,
                    FORAGEABLE_APPROACH_ANGLE_TIERS[tier]);
                requested.removeIf(candidate -> !safeFromDanger(candidate));
                if(requested.isEmpty()) continue;
                BotMovement.Result result = moveAny(requested);
                debugMovement("FORAGE-APPROACH-" + tier, result,
                    requested.size(), FORAGEABLE_STANDOFF, target.id);
                if(!arrived(result)) continue;
                player = gui.map.player();
                if(player == null || player.rc == null || target.disposed() || target.rc == null) return false;
                distance = player.rc.dist(target.rc);
                debug("FORAGE-APPROACH-ARRIVED id=%d distance=%.1f", target.id, distance);
                if(distance <= FORAGEABLE_INTERACTION_RANGE) return true;
            }
            return false;
        }

        BotMovement.Result moveAny(List<Coord2d> destinations) throws InterruptedException {
            BotMovement.Avoidance avoidance = BotMovement.Avoidance.dynamic(this::dangerCenters, DANGER_RADIUS);
            return BotMovement.moveToAny(
                gui, bot, destinations, avoidance,
                caveMode ? BotMovement.Mode.CAVE : BotMovement.Mode.LAND, 45000L);
        }

        boolean arrived(BotMovement.Result result) {
            return result != null && result.status == BotMovement.Status.ARRIVED;
        }

        void debugMovement(String phase, BotMovement.Result result, int goals, double distance, long targetId) {
            if(result == null) {
                debug("MOVE phase=%s target=%d goals=%d distance=%.1f result=null", phase, targetId, goals, distance);
                return;
            }
            debug("MOVE phase=%s target=%d goals=%d distance=%.1f status=%s selected=%s end=%s replans=%d detail=%s",
                phase, targetId, goals, distance, result.status, result.selectedGoal, result.end,
                result.replans, result.detail);
        }

        List<Coord2d> dangerCenters() {
            List<Coord2d> dangers = new ArrayList<>();
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(gob != null && gob.rc != null && !gob.disposed()
                        && gob.is(GobTag.AGGRESSIVE) && !gob.anyOf(GobTag.DEAD, GobTag.KO)) dangers.add(gob.rc);
                }
            }
            return dangers;
        }

        boolean safeFromDanger(Coord2d point) {
            return safeFromDanger(point, dangerCenters());
        }

        boolean safeFromDanger(Coord2d point, Collection<Coord2d> dangers) {
            return DirectionalForagerLogic.safeFrom(point, dangers, DANGER_RADIUS);
        }

        boolean hasCollectionSpace() {
            if(gui.maininv != null && gui.maininv.free() > 0) return true;
            Inventory basket = pickingBasket();
            return basket != null && basket.free() > 0;
        }

        boolean makeMainRoom() throws InterruptedException {
            if(gui.maininv != null && gui.maininv.free() > 0) return true;
            Inventory basket = pickingBasket();
            if(basket == null || basket.free() <= 0 || gui.maininv == null) return false;
            for(WItem item : new ArrayList<>(gui.maininv.children(WItem.class))) {
                String resid;
                try {resid = item.item.resname();} catch(Loading e) {continue;}
                if(!ForageCatalog.isSelectedInventoryResource(resid, selected)) continue;
                Coord place = basket.findPlaceFor(item.lsz);
                if(place == null) continue;
                item.take();
                if(!waitForHand(true, 2500L)) return false;
                basket.wdgmsg("drop", place);
                if(!waitForHand(false, 2500L)) return false;
                return gui.maininv.free() > 0;
            }
            return false;
        }

        boolean waitForHand(boolean occupied, long timeout) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeout;
            while(System.currentTimeMillis() < deadline) {
                bot.checkCancelled();
                if((gui.hand() != null) == occupied) return true;
                Thread.sleep(50L);
            }
            return (gui.hand() != null) == occupied;
        }

        Inventory pickingBasket() {
            for(GItem.ContentsWindow wnd : gui.children(GItem.ContentsWindow.class)) {
                String resid;
                try {resid = wnd.cont.resname();} catch(Loading e) {continue;}
                if(resid == null || !resid.endsWith("/pickingbasket")) continue;
                if(wnd.inv instanceof Inventory) return (Inventory)wnd.inv;
                for(Inventory inv : wnd.inv.children(Inventory.class)) return inv;
            }
            return null;
        }

        long storageSignature() {
            long sig = inventorySignature(gui.maininv);
            Inventory basket = pickingBasket();
            return sig * 1000003L + inventorySignature(basket);
        }

        long inventorySignature(Inventory inv) {
            if(inv == null) return 0;
            long sig = inv.free();
            for(WItem item : inv.children(WItem.class)) {
                try {
                    sig = sig * 31L + item.item.resname().hashCode();
                    Float quantity = item.quantity.get();
                    sig = sig * 31L + (quantity == null ? 0 : Float.floatToIntBits(quantity));
                }
                catch(Loading e) {sig = sig * 31L + 1;}
            }
            return sig;
        }

        String fullMessage() {
            return equippedBasketClosed() ? "Status: stopped — open the equipped Picking Basket" : "Status: stopped — carrying space is full";
        }

        boolean equippedBasketClosed() {
            if(gui.equipory == null || pickingBasket() != null) return false;
            for(Equipory.SLOTS slot : new Equipory.SLOTS[]{Equipory.SLOTS.HAND_LEFT, Equipory.SLOTS.HAND_RIGHT}) {
                WItem item = gui.equipory.slot(slot);
                try {if(item != null && item.item.resname().endsWith("/pickingbasket")) return true;} catch(Loading ignored) {}
            }
            return false;
        }

        String display(Gob gob) {
            try {
                String key = ForageCatalog.key(gob.resid());
                for(ForageCatalog.Entry entry : ForageCatalog.entries())
                    if(Objects.equals(entry.key, key)) return entry.name;
                return key == null ? "forageable" : key;
            } catch(Exception e) {return "forageable";}
        }

        String resource(Gob gob) {
            try {return gob == null ? "null" : String.valueOf(gob.resid());}
            catch(Exception e) {return "unavailable";}
        }

        void stopWith(String message) throws InterruptedException {
            status = message;
            debug("STOP-REQUEST message=%s", message);
            bot.cancel(message.replace("Status: ", "Directional Forager: "));
            bot.checkCancelled();
        }
    }

    private static final class CaveContext {
        final MapFile file;
        final long segment;
        final Coord sessionTile;
        final Coord tile;
        final Coord2d world;

        CaveContext(MapFile file, long segment, Coord sessionTile, Coord tile, Coord2d world) {
            this.file = file;
            this.segment = segment;
            this.sessionTile = new Coord(sessionTile);
            this.tile = new Coord(tile);
            this.world = world;
        }
    }
}
