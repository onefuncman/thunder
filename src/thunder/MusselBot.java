package thunder;

import auto.Actions;
import auto.Bot;
import auto.BotUtil;
import auto.GobTarget;
import haven.*;
import haven.pathfinding.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;
import org.json.JSONObject;

/** Player-started saved-map waterway navigator and River Pearl Mussel collector. */
public final class MusselBot {
    private static final String MUSSEL_KEY = "mussels";
    private static final double MUSSEL_DETECT_RADIUS = MCache.tilesz.x * 35.0;
    private static final double PICKUP_HANDOFF_RADIUS = 55.0;
    private static final double DIRECT_PICK_RADIUS = 45.0;
    private static final double LOCAL_APPROACH_RADIUS = MCache.tilesz.x * 8.0;
    // A 24-tile leg stays comfortably inside the live pathfinder's 42-tile
    // view while giving its waypoint handoff enough road to keep a boat moving.
    static final int ROUTE_LEG_TILES = 24;
    static final int MIN_ROUTE_LEG_TILES = 4;
    static final double ROUTE_REJOIN_TILES = 4.0;
    private static final int MIN_LEG_FAILURES_BEFORE_ALTERNATE = 3;
    private static final int DETOUR_MAX_TILES = 500_000;
    private static final long TARGET_RETRY_MS = 60_000L;
    private static final String DEBUG_LOG = "logs/mussel-bot-debug.log";
    private static final String MOVEMENT_DEBUG_LOG = "logs/mussel-movement-diagnostics.log";

    private static volatile boolean running;
    private static volatile String status = "Status: idle";
    private static volatile int collected;
    private static volatile String debugRunId = "none";
    private static Bot active;
    private static volatile Run activeRun;

    private MusselBot() {}
    public static boolean isRunning() {return running;}
    public static String status() {return status;}
    public static int collected() {return collected;}

    public static synchronized void start(GameUI gui) {
        if(running) {gui.error("Musseler is already running."); return;}
        MusselRouteOverlay.init();
        MusselRouteOverlay.clear();
        PathfinderLog.resetRun();
        Gob player = gui == null || gui.map == null ? null : gui.map.player();
        Coord2d boatPosition = BoatNavigation.position(player);
        if(player == null || boatPosition == null) {
            if(gui != null) gui.error("Musseler: player or boat position is unavailable.");
            return;
        }
        if(!drivingBoat(gui, player)) {gui.error("Musseler: take the driver seat of a boat first."); return;}
        if(gui.mapfile == null || gui.mapfile.playerLocation() == null) {
            gui.error("Musseler: the saved map is not ready yet.");
            return;
        }
        if(Bot.hasCurrent()) {gui.error("Musseler: another task is already running."); return;}
        int speed = setFastestSpeed(gui.ui == null ? gui : gui.ui.root);
        String runId = Long.toString(System.currentTimeMillis(), 36) + "-"
            + UUID.randomUUID().toString().substring(0, 6);
        debugRunId = runId;
        running = true;
        collected = 0;
        status = "Status: Planning route";
        debug("START build=%s player=(%.1f,%.1f) boat=(%.1f,%.1f) vehicle=%d resid=%s heading=%.3f half=%s speed=%d leg=%d",
            Config.version,
            player.rc.x, player.rc.y, boatPosition.x, boatPosition.y, player.vehicleId(),
            BoatNavigation.resid(player), BoatNavigation.heading(player), BoatNavigation.halfExtents(player), speed,
            ROUTE_LEG_TILES);
        Bot bot = Bot.execute((unused, b) -> {
            Run run = new Run(gui, b, runId);
            activeRun = run;
            try {run.execute();}
            catch(RuntimeException | Error failure) {
                status = "Status: Error — internal error (see mussel log)";
                debugFailure(failure);
                throw failure;
            } finally {
                debug("STOP status=%s collected=%d", status, collected);
                MusselRouteOverlay.clear();
                running = false;
                active = null;
                activeRun = null;
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
        bot.cancel("Musseler stopped by user.");
    }

    /** Writes a self-contained snapshot that can be inspected after the game
     * has moved on from the troublesome location. */
    public static void saveDebugSnapshot(GameUI gui) {
        String name = String.format(Locale.ROOT, "logs/mussel-debug-snapshot-%d.txt",
            System.currentTimeMillis());
        File file = new File(name);
        try {
            File parent = file.getParentFile();
            if(parent != null) parent.mkdirs();
            try(PrintWriter out = new PrintWriter(new FileWriter(file))) {
                out.printf(Locale.ROOT, "created=%tFT%<tT.%<tL%n", new Date());
                out.printf(Locale.ROOT, "run=%s build=%s running=%s status=%s collected=%d%n",
                    debugRunId, Config.version, running, status, collected);
                Run run = activeRun;
                out.println(run == null ? "run_state=inactive" : run.describe());
                out.println("overlay=" + MusselRouteOverlay.describe());
                out.println("pathfinder=");
                out.println(PathfinderLog.capture().toString());
                if(run != null && run.lastMovementDetails != null) {
                    out.println("last_movement=");
                    out.println(run.lastMovementDetails);
                }
            }
            debug("SNAPSHOT file=%s", file.getAbsolutePath());
            if(gui != null) gui.msg("Musseler debug snapshot saved: " + file.getName(), GameUI.MsgType.INFO);
        } catch(Exception failure) {
            debug("SNAPSHOT-FAILED error=%s", failure.toString());
            if(gui != null) gui.error("Musseler could not save the debug snapshot.");
        }
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

    private static synchronized void movementDiagnostic(String detail) {
        try {
            File file = new File(MOVEMENT_DEBUG_LOG);
            File parent = file.getParentFile();
            if(parent != null) parent.mkdirs();
            try(PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                out.printf(Locale.ROOT, "%tFT%<tT.%<tL run=%s%n", new Date(), debugRunId);
                out.println(detail);
                out.println("---");
            }
        } catch(Exception ignored) {}
    }

    private static int setFastestSpeed(Widget root) {
        if(root == null) return -1;
        if(root instanceof Speedget) {
            Speedget speed = (Speedget)root;
            int selected = Math.min(3, speed.max);
            if(selected >= 0) speed.set(selected);
            return selected;
        }
        for(Widget child = root.child; child != null; child = child.next) {
            int selected = setFastestSpeed(child);
            if(selected >= 0) return selected;
        }
        return -1;
    }

    static boolean drivingBoat(GameUI gui, Gob player) {
        if(player == null || player.vehicleId() == 0L) return false;
        Moving moving = player.getattr(Moving.class);
        if(!(moving instanceof Following)) return false;
        Gob vehicle = ((Following)moving).tgt();
        String resid = vehicle == null ? null : vehicle.resid();
        if(resid == null || !resid.contains("/vehicle/")) return false;
        if(!(resid.contains("rowboat") || resid.contains("dugout") || resid.contains("knarr")
            || resid.contains("snekkja") || resid.contains("spark") || resid.contains("coracle"))) return false;
        String seat = ((Following)moving).xfname;
        if(resid.contains("snekkja")) return "m0".equals(seat);
        if(resid.contains("knarr")) return "m9".equals(seat);
        if(resid.contains("rowboat") || resid.contains("spark")) return "d".equals(seat);
        return true;
    }

    private static final class Run {
        final GameUI gui;
        final Bot bot;
        final String runId;
        final Map<Long, Long> retryAfter = new HashMap<>();
        final Set<Coord> closedFrontiers = new HashSet<>();
        final Map<Coord, Integer> frontierAttempts = new HashMap<>();
        final Map<Coord, Boolean> waterProbeCache = new HashMap<>();
        final ArrayDeque<Coord> recentBoatTiles = new ArrayDeque<>();
        long lastMusselScanLog;
        long routeSegment = Long.MIN_VALUE;
        WaterwayRoutePlanner.Plan mainPlan;
        MapFileWaterSource planSource;
        int routeIndex;
        int noProgress;
        int cruiseLegTiles = ROUTE_LEG_TILES;
        int recoverySuccesses;
        int minLegFailures;
        int replans;
        int legs;
        int detours;
        int moveSerial;
        volatile String lastMovementSummary = "none";
        volatile String lastMovementDetails;

        Run(GameUI gui, Bot bot, String runId) {
            this.gui = gui;
            this.bot = bot;
            this.runId = runId;
        }

        String describe() {
            Context current = context();
            WaterwayRoutePlanner.Plan plan = mainPlan;
            String state = String.format(Locale.ROOT,
                "run_state=id:%s position:%s saved:%s segment:%x moving:%s route_index:%d route_size:%d cruise_leg:%d legs:%d replans:%d detours:%d closed_endpoints:%d last_movement:%s",
                runId, current == null ? "unavailable" : current.world,
                current == null ? "unavailable" : current.tile,
                current == null ? Long.MIN_VALUE : current.segment,
                BoatNavigation.moving(gui == null || gui.map == null ? null : gui.map.player()),
                routeIndex, plan == null ? 0 : plan.route.size(), cruiseLegTiles, legs, replans,
                detours, closedFrontiers.size(), lastMovementSummary);
            if(plan == null) return state + "\nroute_window=none";
            return state + String.format(Locale.ROOT,
                "\nplan=status:%s destination:%s distance:%.1f water:%d shallow:%d exposure:%d frontier:%s"
                    + "\nroute_window=%s",
                plan.status, plan.destination, plan.distanceTiles, plan.knownWater,
                plan.shallowWater, plan.shallowExposure, plan.frontier,
                routeWindow(plan.route, routeIndex, 6, 14));
        }

        String routeWindow(List<Coord> route, int cursor, int behind, int ahead) {
            if(route == null || route.isEmpty()) return "empty";
            int from = Math.max(0, cursor - behind);
            int to = Math.min(route.size(), cursor + ahead + 1);
            StringBuilder value = new StringBuilder();
            for(int i = from; i < to; i++) {
                if(value.length() > 0) value.append(' ');
                if(i == cursor) value.append('>');
                value.append(i).append(':').append(route.get(i));
            }
            return value.toString();
        }

        void execute() throws InterruptedException {
            while(true) {
                bot.checkCancelled();
                Context context = context();
                if(context == null) {finishError("player or saved map unavailable"); return;}
                Gob player = gui.map.player();
                if(!drivingBoat(gui, player)) {finishError("you are no longer driving the boat"); return;}
                if(!hasCollectionSpace()) {finish("Status: Complete — carrying space is full"); return;}
                if(routeSegment != context.segment) resetForSegment(context.segment);

                Gob mussel = nearestVisibleMussel(context.world);
                if(mussel != null) {
                    detours++;
                    try {collectMussel(mussel);}
                    finally {MusselRouteOverlay.clearDetours();}
                    syncRouteIndex(5.0, "mussel-return");
                    continue;
                }

                if(mainPlan == null && !planMain(context)) return;
                context = context();
                if(context == null) {finishError("saved map became unavailable"); return;}
                syncRouteIndex(context, ROUTE_REJOIN_TILES, "cruise-loop");

                if(mainPlan.route.size() <= 1 || routeIndex >= mainPlan.route.size()) {
                    if(mainPlan.frontier || mainPlan.status == WaterwayRoutePlanner.Status.LIMIT_REACHED) {
                        extendOrCloseFrontier(context);
                        MusselRouteOverlay.clearMain();
                        mainPlan = null;
                        continue;
                    }
                    finish(String.format(Locale.ROOT,
                        "Status: Complete — route finished (%d mussels)", collected));
                    debug("COMPLETE distance=%.1f tiles water=%d shallow=%d exposure=%d legs=%d replans=%d detours=%d collected=%d",
                        mainPlan.distanceTiles, mainPlan.knownWater, mainPlan.shallowWater,
                        mainPlan.shallowExposure, legs, replans, detours, collected);
                    return;
                }

                status = cruiseLegTiles < ROUTE_LEG_TILES
                    ? String.format(Locale.ROOT, "Status: Cruising carefully (%d-tile steps)", cruiseLegTiles)
                    : String.format(Locale.ROOT, "Status: Cruising %d/%d", routeIndex, mainPlan.route.size() - 1);
                int goalIndex = MusselNavigatorLogic.lookahead(
                    mainPlan.route, routeIndex, context.tile, cruiseLegTiles);
                Coord goalTile = mainPlan.route.get(goalIndex);
                debug("CRUISE-SELECT cursor=%d goal=%d leg=%d current=%s next=%s target=%s cursor_gap=%.1f",
                    routeIndex, goalIndex, cruiseLegTiles, context.tile, mainPlan.route.get(routeIndex), goalTile,
                    tileDistance(context.tile, mainPlan.route.get(routeIndex)));
                String cruiseLabel = String.format(Locale.ROOT,
                    "CRUISE route=%d->%d", routeIndex, goalIndex);
                Move move = moveAlongSavedRoute(context, routeIndex, goalIndex, cruiseLabel);
                if(!move.progressed && !move.arrived) {
                    Context fallback = context();
                    if(fallback == null) {finishError("boat position became unavailable"); return;}
                    debug("%s GUIDE-FALLBACK result=%s current=%s target=%s",
                        cruiseLabel, move.result, fallback.tile, goalTile);
                    move = moveToSavedTile(fallback, goalTile, false,
                        cruiseLabel + " LOCAL-FALLBACK");
                }
                legs++;
                Context after = context();
                if(after == null) {finishError("boat position became unavailable"); return;}
                remember(after.tile);
                int beforeIndex = routeIndex;
                syncRouteIndex(after, ROUTE_REJOIN_TILES, "after-move",
                    goalIndex, move.progressed || move.arrived);
                boolean advanced = move.progressed || routeIndex > beforeIndex;
                if(advanced) {
                    noProgress = 0;
                    minLegFailures = 0;
                    if(cruiseLegTiles < ROUTE_LEG_TILES && ++recoverySuccesses >= 2) {
                        int beforeLeg = cruiseLegTiles;
                        cruiseLegTiles = Math.min(ROUTE_LEG_TILES, cruiseLegTiles * 2);
                        recoverySuccesses = 0;
                        debug("RECOVERY-GROW leg=%d->%d current=%s cursor=%d",
                            beforeLeg, cruiseLegTiles, after.tile, routeIndex);
                    }
                } else {
                    recoverySuccesses = 0;
                    noProgress++;
                    boolean loop = oscillating();
                    if(loop || noProgress >= 2) {
                        int shorter = MusselNavigatorLogic.shorterLeg(
                            cruiseLegTiles, MIN_ROUTE_LEG_TILES);
                        if(shorter < cruiseLegTiles) {
                            debug("RECOVERY-SHORTEN reason=%s leg=%d->%d current=%s target=%s moved=%.1f result=%s",
                                loop ? "oscillation" : "no-progress", cruiseLegTiles, shorter,
                                after.tile, goalTile, move.moved, move.result);
                            cruiseLegTiles = shorter;
                            minLegFailures = 0;
                        } else {
                            minLegFailures++;
                            debug("RECOVERY-HOLD reason=%s leg=%d failure=%d/%d current=%s target=%s moved=%.1f result=%s",
                                loop ? "oscillation" : "no-progress", cruiseLegTiles,
                                minLegFailures, MIN_LEG_FAILURES_BEFORE_ALTERNATE,
                                after.tile, goalTile, move.moved, move.result);
                            if(minLegFailures >= MIN_LEG_FAILURES_BEFORE_ALTERNATE) {
                                Coord endpoint = mainPlan.destination;
                                if(mainPlan.frontier && endpoint != null) {
                                    closeFrontier(endpoint, 12);
                                    status = "Status: Planning alternate waterway";
                                    debug("RECOVERY-ALTERNATE abandoned=%s current=%s reason=confirmed-local-block; water-remains-connected",
                                        endpoint, after.tile);
                                    MusselRouteOverlay.clearMain();
                                    mainPlan = null;
                                    cruiseLegTiles = ROUTE_LEG_TILES;
                                    minLegFailures = 0;
                                } else {
                                    finishError("the route ahead remained impassable after short-step recovery");
                                    return;
                                }
                            } else {
                                Thread.sleep(400L);
                            }
                        }
                        noProgress = 0;
                        recentBoatTiles.clear();
                    }
                }
            }
        }

        void resetForSegment(long segment) {
            debug("SEGMENT old=%x new=%x reset route state", routeSegment, segment);
            routeSegment = segment;
            MusselRouteOverlay.clear();
            mainPlan = null;
            planSource = null;
            routeIndex = 0;
            closedFrontiers.clear();
            frontierAttempts.clear();
            recentBoatTiles.clear();
            cruiseLegTiles = ROUTE_LEG_TILES;
            recoverySuccesses = 0;
            minLegFailures = 0;
        }

        boolean planMain(Context context) throws InterruptedException {
            status = "Status: Planning route";
            long started = System.currentTimeMillis();
            WaterwayRoutePlanner.Plan plan = null;
            Context current = context;
            Context plannedFrom = null;
            for(int attempt = 0; attempt < 4; attempt++) {
                bot.checkCancelled();
                if(!settleBoatForPlanning()) {
                    finishError("the boat could not stop long enough to plan safely");
                    return false;
                }
                current = context();
                if(current == null || current.segment != context.segment) break;
                plannedFrom = current;
                boolean reusedSource = planSource != null
                    && planSource.segmentId() == current.segment;
                if(!reusedSource)
                    planSource = new MapFileWaterSource(current.file, current.segment);
                else
                    planSource.refreshMissing();
                status = reusedSource
                    ? "Status: Planning route — recalculating"
                    : "Status: Planning route — loading saved map";
                debug("PLAN-BEGIN attempt=%d source=%s cached_grids=%d missing_grids=%d start=%s closed=%d",
                    attempt + 1, reusedSource ? "reused" : "new", planSource.cachedGridCount(),
                    planSource.missingGridCount(), current.tile, closedFrontiers.size());
                plan = WaterwayRoutePlanner.plan(planSource, current.tile,
                    WaterwayRoutePlanner.DEFAULT_MAX_TILES, closedFrontiers);
                Context fresh = context();
                if(fresh == null || fresh.segment != current.segment) {
                    plan = null;
                    break;
                }
                double drift = tileDistance(current.tile, fresh.tile);
                if(plan.usable() && !MusselNavigatorLogic.stalePlanStart(current.tile, fresh.tile, 2.5)) {
                    current = fresh;
                    break;
                }
                if(plan.usable()) {
                    debug("PLAN-STALE attempt=%d planned-start=%s current=%s drift=%.1f tiles; retrying",
                        attempt + 1, current.tile, fresh.tile, drift);
                    plan = null;
                    current = fresh;
                    continue;
                }
                Thread.sleep(500L);
                current = fresh;
            }
            long elapsed = System.currentTimeMillis() - started;
            if(plan == null || !plan.usable()) {
                debug("PLAN-FAILED status=%s start=%s elapsed=%dms", plan == null ? "null" : plan.status,
                    current == null ? "unavailable" : current.tile, elapsed);
                finishError(plan != null && plan.status == WaterwayRoutePlanner.Status.INVALID_START
                    ? "the boat is not on mapped navigable water" : "no connected saved-water route found");
                return false;
            }
            mainPlan = plan;
            routeIndex = plan.route.size() > 1 ? 1 : plan.route.size();
            MusselNavigatorLogic.RejoinDecision initial = MusselNavigatorLogic.rejoinDecision(
                plan.route, current.tile, routeIndex, 0, 128, 3.0);
            routeIndex = initial.selectedIndex;
            while(routeIndex < plan.route.size()
                && tileDistance(current.tile, plan.route.get(routeIndex)) <= 1.25) routeIndex++;
            MusselRouteOverlay.showMain(current.segment, plan.route, routeIndex);
            replans++;
            debug("PLAN status=%s start=%s current=%s drift=%.1f dest=%s route=%d distance=%.1f water=%d shallow=%d exposure=%d frontier=%s frontiers=%d limited=%s elapsed=%dms",
                plan.status, plannedFrom == null ? current.tile : plannedFrom.tile, current.tile,
                plannedFrom == null ? 0.0 : tileDistance(plannedFrom.tile, current.tile),
                plan.destination, plan.route.size(), plan.distanceTiles,
                plan.knownWater, plan.shallowWater, plan.shallowExposure,
                plan.frontier, plan.frontierCount, plan.status == WaterwayRoutePlanner.Status.LIMIT_REACHED, elapsed);
            debug("CURSOR cause=plan selected=%d nearest=%d gap=%.2f accepted=%s current=%s",
                routeIndex, initial.nearestIndex, initial.nearestDistance, initial.accepted, current.tile);
            return true;
        }

        boolean settleBoatForPlanning() throws InterruptedException {
            Gob player = gui.map.player();
            Coord2d start = BoatNavigation.position(player);
            if(start == null) return false;
            try {if(gui.pathQueue != null) gui.pathQueue.clear();} catch(RuntimeException ignored) {}
            boolean initiallyMoving = BoatNavigation.moving(player);
            long started = System.currentTimeMillis();
            if(initiallyMoving)
                gui.map.wdgmsg("click", new Object[]{Coord.z, start.floor(OCache.posres), 1, 0});
            Coord2d previous = start;
            int stableSamples = 0;
            long deadline = started + 4000L;
            while(System.currentTimeMillis() < deadline) {
                bot.checkCancelled();
                Thread.sleep(75L);
                player = gui.map.player();
                Coord2d now = BoatNavigation.position(player);
                if(now == null) return false;
                boolean stable = previous.dist(now) <= 0.75 && !BoatNavigation.moving(player);
                stableSamples = stable ? stableSamples + 1 : 0;
                if(stableSamples >= 3) {
                    debug("PLAN-SETTLE initially_moving=%s moved=%.1f elapsed=%dms",
                        initiallyMoving, start.dist(now), System.currentTimeMillis() - started);
                    return true;
                }
                previous = now;
            }
            Coord2d now = BoatNavigation.position(gui.map.player());
            debug("PLAN-SETTLE failed moved=%.1f elapsed=%dms moving=%s",
                now == null ? -1.0 : start.dist(now), System.currentTimeMillis() - started,
                BoatNavigation.moving(gui.map.player()));
            return false;
        }

        void syncRouteIndex(double maxDistance, String cause) {
            Context context = context();
            if(context != null) syncRouteIndex(context, maxDistance, cause);
        }

        void syncRouteIndex(Context context, double maxDistance, String cause) {
            syncRouteIndex(context, maxDistance, cause, -1, false);
        }

        void syncRouteIndex(Context context, double maxDistance, String cause,
                            int attemptedGoalIndex, boolean trustAttemptProgress) {
            if(mainPlan == null || mainPlan.route.isEmpty()) return;
            int before = routeIndex;
            MusselNavigatorLogic.RejoinDecision decision = MusselNavigatorLogic.rejoinDecision(
                mainPlan.route, context.tile, routeIndex, 8, 128, maxDistance);
            routeIndex = decision.selectedIndex;
            boolean projected = false;
            if(!decision.accepted && trustAttemptProgress && attemptedGoalIndex >= before) {
                int progress = MusselNavigatorLogic.boundedProgressIndex(
                    mainPlan.route, context.tile, before, attemptedGoalIndex);
                if(progress > routeIndex) {
                    routeIndex = progress;
                    projected = true;
                }
            }
            while(routeIndex < mainPlan.route.size()
                && tileDistance(context.tile, mainPlan.route.get(routeIndex)) <= 1.25) routeIndex++;
            if(before != routeIndex || !decision.accepted
                || decision.nearestDistance > maxDistance * 0.75) {
                debug("CURSOR cause=%s before=%d after=%d nearest=%d gap=%.2f limit=%.2f accepted=%s projected=%s current=%s",
                    cause, before, routeIndex, decision.nearestIndex, decision.nearestDistance,
                    maxDistance, decision.accepted, projected, context.tile);
            }
            MusselRouteOverlay.showProgress(routeIndex);
        }

        Move moveToSavedTile(Context context, Coord tile, boolean approach, String label) throws InterruptedException {
            Coord2d world = MapTileCoordinates.worldPosition(tile, context.sessionTile);
            return moveToWorld(world, approach, label + " saved=" + tile);
        }

        /** Follow exactly the cyan route's chosen corners. Local movement does
         * not invent or alter the saved water-route strategy. */
        Move moveAlongSavedRoute(Context context, int fromIndex, int goalIndex,
                                 String label) throws InterruptedException {
            int movementId = ++moveSerial;
            Gob player = gui.map.player();
            Coord2d before = BoatNavigation.position(player);
            if(before == null || mainPlan == null || context == null)
                return Move.failed("NO_POSITION");
            List<Coord> corners = MusselNavigatorLogic.guideCorners(
                mainPlan.route, fromIndex, goalIndex);
            List<Coord2d> route = new ArrayList<>();
            route.add(before);
            for(Coord corner : corners) {
                Coord2d point = MapTileCoordinates.worldPosition(corner, context.sessionTile);
                if(route.get(route.size() - 1).dist(point) > 1.0) route.add(point);
            }
            Coord goalTile = mainPlan.route.get(Math.max(0,
                Math.min(goalIndex, mainPlan.route.size() - 1)));
            Coord2d goal = MapTileCoordinates.worldPosition(goalTile, context.sessionTile);
            if(route.size() < 2) return new Move("ALREADY_THERE", false, true, 0.0, before);

            Coord2d effective = route.get(route.size() - 1);
            double routeLength = MusselExplorerLogic.routeLength(route);
            Context overlayContext = context();
            if(overlayContext != null)
                MusselRouteOverlay.beginMovement(overlayContext.segment, route, goal, effective, before);
            debug("%s GUIDE-PLAN id=%d requested=%s route=%.1f points=%d corners=%d",
                label, movementId, point(goal), routeLength, route.size(), corners.size());
            BotMovement.Result result;
            try {
                result = BotMovement.followKnownRoute(
                    gui, bot, route, BotMovement.Mode.BOAT_ROUTE, 15000L);
            } catch(InterruptedException cancelled) {
                MusselRouteOverlay.finishMovement("CANCELLED");
                lastMovementSummary = movementSummary(movementId, label + " GUIDE", "CANCELLED", before, before, goal, effective);
                lastMovementDetails = lastMovementSummary;
                movementDiagnostic(lastMovementDetails);
                throw cancelled;
            }
            Coord2d after = BoatNavigation.position(gui.map.player());
            double moved = after == null ? 0.0 : before.dist(after);
            double beforeRemaining = before.dist(goal);
            double remaining = after == null ? Double.POSITIVE_INFINITY : after.dist(goal);
            MusselRouteOverlay.finishMovement(result.status.name());
            lastMovementSummary = movementSummary(movementId, label + " GUIDE", result.status.name(), before, after, goal, effective);
            lastMovementDetails = lastMovementSummary + "\ndetail=" + result.detail;
            debug("%s GUIDE-RESULT id=%d result=%s moved=%.1f requested_left=%.1f boat=%s",
                label, movementId, result.status, moved, remaining, point(after));
            debug("MOVE-TRACE %s", lastMovementSummary);
            if(result.status != BotMovement.Status.ARRIVED || remaining > MCache.tilesz.x * 1.25)
                movementDiagnostic(lastMovementDetails);
            boolean forward = MusselNavigatorLogic.madeForwardProgress(
                moved, beforeRemaining, remaining, MCache.tilesz.x);
            return new Move(result.status.name(), forward,
                result.status == BotMovement.Status.ARRIVED && remaining <= MCache.tilesz.x * 1.25,
                moved, after);
        }

        Move moveToWorld(Coord2d goal, boolean approach, String label) throws InterruptedException {
            int movementId = ++moveSerial;
            Gob player = gui.map.player();
            Coord2d before = BoatNavigation.position(player);
            if(before == null || goal == null) return Move.failed("NO_POSITION");
            JSONObject pathfinder = PathfinderLog.last();
            double direct = before.dist(goal);
            Coord2d effective = goal;
            List<Coord2d> displayRoute = Arrays.asList(before, goal);
            Context overlayContext = context();
            if(overlayContext != null)
                MusselRouteOverlay.beginMovement(overlayContext.segment, displayRoute, goal, effective, before);
            debug("%s MOVE id=%d from=(%.1f,%.1f) requested=(%.1f,%.1f) effective=%s",
                label, movementId, before.x, before.y, goal.x, goal.y, point(effective));
            BotMovement.Result result;
            try {
                result = BotMovement.moveTo(gui, bot, goal,
                    approach ? BotMovement.Mode.BOAT_APPROACH : BotMovement.Mode.BOAT_LOCAL,
                    60000L);
            } catch(InterruptedException cancelled) {
                MusselRouteOverlay.finishMovement("CANCELLED");
                lastMovementSummary = movementSummary(movementId, label, "CANCELLED", before, before, goal, effective);
                lastMovementDetails = lastMovementSummary + "\npathfinder="
                    + (pathfinder == null ? "none" : pathfinder.toString());
                movementDiagnostic(lastMovementDetails);
                throw cancelled;
            }
            player = gui.map.player();
            Coord2d after = BoatNavigation.position(player);
            double moved = after == null ? 0.0 : before.dist(after);
            double beforeRemaining = before.dist(goal);
            double remaining = after == null ? Double.POSITIVE_INFINITY : after.dist(goal);
            MusselRouteOverlay.finishMovement(result.status.name());
            lastMovementSummary = movementSummary(movementId, label, result.status.name(), before, after, goal, effective);
            lastMovementDetails = lastMovementSummary + "\ndetail=" + result.detail + "\npathfinder="
                + (pathfinder == null ? "none" : pathfinder.toString());
            debug("%s RESULT id=%d result=%s moved=%.1f requested_left=%.1f boat=%s",
                label, movementId, result.status, moved, remaining,
                after == null ? "unavailable" : String.format(Locale.ROOT, "(%.1f,%.1f)", after.x, after.y));
            debug("MOVE-TRACE %s", lastMovementSummary);
            boolean abnormal = result.status != BotMovement.Status.ARRIVED
                || remaining > MCache.tilesz.x * 1.25;
            if(abnormal) movementDiagnostic(lastMovementDetails);
            boolean forward = MusselNavigatorLogic.madeForwardProgress(
                moved, beforeRemaining, remaining, MCache.tilesz.x);
            return new Move(result.status.name(), forward,
                result.status == BotMovement.Status.ARRIVED && remaining <= MCache.tilesz.x * 1.25,
                moved, after);
        }

        void extendOrCloseFrontier(Context context) throws InterruptedException {
            status = "Status: Exploring map edge";
            Coord endpoint = mainPlan.destination == null ? context.tile : mainPlan.destination;
            int attempt = frontierAttempts.getOrDefault(endpoint, 0) + 1;
            frontierAttempts.put(new Coord(endpoint), attempt);
            double heading = routeHeading(mainPlan.route, BoatNavigation.heading(gui.map.player()));
            debug("FRONTIER endpoint=%s heading=%.3f attempt=%d limited=%s", endpoint, heading,
                attempt, mainPlan.status == WaterwayRoutePlanner.Status.LIMIT_REACHED);
            if(mainPlan.status == WaterwayRoutePlanner.Status.LIMIT_REACHED) {
                Thread.sleep(250L);
                return;
            }

            Thread.sleep(1000L);
            MapFileWaterSource fresh = new MapFileWaterSource(context.file, context.segment);
            Coord forward = endpoint.add((int)Math.round(Math.cos(heading) * 2.0),
                (int)Math.round(Math.sin(heading) * 2.0));
            WaterwayRoutePlanner.Cell oldCell = planSource == null
                ? WaterwayRoutePlanner.Cell.UNKNOWN : planSource.cell(forward);
            WaterwayRoutePlanner.Cell freshCell = fresh.cell(forward);
            if(oldCell == WaterwayRoutePlanner.Cell.UNKNOWN && freshCell.water()) {
                frontierAttempts.remove(endpoint);
                if(planSource != null) planSource.refreshTile(forward);
                debug("FRONTIER-EXTENDED saved map now contains %s", forward);
                return;
            }

            if(attempt >= 2) {
                closeFrontier(endpoint, 6);
                debug("FRONTIER-CLOSED endpoint=%s radius=6 after=%d attempts closed=%d",
                    endpoint, attempt, closedFrontiers.size());
                return;
            }

            Coord2d at = context.world;
            double[] offsets = {0.0, Math.PI / 6.0, -Math.PI / 6.0, Math.PI / 3.0, -Math.PI / 3.0};
            for(double distance : new double[]{MCache.tilesz.x * 4.0, MCache.tilesz.x * 3.0, MCache.tilesz.x * 2.0}) {
                for(double offset : offsets) {
                    double angle = heading + offset;
                    Coord2d probe = at.add(Math.cos(angle) * distance, Math.sin(angle) * distance);
                    if(!navigableWater(probe)) continue;
                    Move move = moveToWorld(probe, false, "FRONTIER-PROBE");
                    if(move.progressed || move.arrived) {
                        Thread.sleep(1200L);
                        debug("FRONTIER-PROGRESS moved=%.1f endpoint=%s", move.moved, endpoint);
                        return;
                    }
                }
            }
            closeFrontier(endpoint, 6);
            debug("FRONTIER-CLOSED endpoint=%s radius=6 closed=%d", endpoint, closedFrontiers.size());
        }

        void closeFrontier(Coord center, int radius) {
            if(center == null) return;
            for(int y = -radius; y <= radius; y++) {
                for(int x = -radius; x <= radius; x++) {
                    if(x * x + y * y <= radius * radius) closedFrontiers.add(center.add(x, y));
                }
            }
        }

        Gob nearestVisibleMussel(Coord2d at) {
            Gob nearest = null;
            double nearestDistance = Double.POSITIVE_INFINITY;
            long now = System.currentTimeMillis();
            int objects = 0, raw = 0, deferred = 0, range = 0, radar = 0;
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(gob == null || gob.rc == null || gob.disposed()) continue;
                    objects++;
                    if(!isRiverMussel(gob)) continue;
                    raw++;
                    if(retryAfter.getOrDefault(gob.id, 0L) > now) {deferred++; continue;}
                    double distance = at.dist(gob.rc);
                    if(distance > MUSSEL_DETECT_RADIUS) {range++; continue;}
                    if(!BotUtil.isOnRadar(gob)) {radar++; continue;}
                    if(distance < nearestDistance) {nearest = gob; nearestDistance = distance;}
                }
            }
            if(raw > 0 || now - lastMusselScanLog >= 5000L) {
                debug("MUSSEL-SCAN objects=%d raw=%d usable=%d deferred=%d range=%d radar=%d radius=%.1f",
                    objects, raw, nearest == null ? 0 : 1, deferred, range, radar, MUSSEL_DETECT_RADIUS);
                lastMusselScanLog = now;
            }
            return nearest;
        }

        void collectMussel(Gob target) throws InterruptedException {
            if(target == null || target.disposed()) return;
            status = "Status: Collecting mussels";
            Gob player = gui.map.player();
            Coord2d boat = BoatNavigation.position(player);
            debug("MUSSEL-DETECTED id=%d distance=%.1f at=(%.1f,%.1f)", target.id,
                boat == null ? -1.0 : boat.dist(target.rc), target.rc.x, target.rc.y);
            if(boat == null) {defer(target, "boat unavailable"); return;}

            if(MusselNavigatorLogic.needsSavedMapStage(boat.dist(target.rc), LOCAL_APPROACH_RADIUS)
                && !stageToward(target)) {
                defer(target, "no saved-water staging route");
                return;
            }
            if(target.disposed()) return;
            player = gui.map.player();
            boat = BoatNavigation.position(player);
            if(boat == null) {defer(target, "boat unavailable after staging"); return;}

            if(boat.dist(target.rc) > DIRECT_PICK_RADIUS && !positionSideOn(target)) {
                player = gui.map.player();
                boat = BoatNavigation.position(player);
                if(boat == null || boat.dist(target.rc) > PICKUP_HANDOFF_RADIUS) {
                    defer(target, "no safe close approach");
                    return;
                }
            }
            if(target.disposed()) return;
            player = gui.map.player();
            boat = BoatNavigation.position(player);
            double distance = boat == null ? Double.POSITIVE_INFINITY : boat.dist(target.rc);
            if(!MusselNavigatorLogic.readyForNativePickup(distance, PICKUP_HANDOFF_RADIUS)) {
                defer(target, "outside pickup handoff range");
                return;
            }

            debug("MUSSEL-HANDOFF id=%d distance=%.1f", target.id, distance);
            if(Actions.pickupTarget(new GobTarget(target), bot, 15000L)) {
                collected++;
                debug("MUSSEL-PICKED id=%d collected=%d", target.id, collected);
            } else {
                defer(target, "native pickup timed out");
            }
        }

        boolean stageToward(Gob target) throws InterruptedException {
            for(int pass = 0; pass < 8 && target != null && !target.disposed(); pass++) {
                Context context = context();
                if(context == null) return false;
                Coord2d boat = context.world;
                if(boat.dist(target.rc) <= LOCAL_APPROACH_RADIUS) return true;
                MapFileWaterSource source = new MapFileWaterSource(context.file, context.segment);
                Collection<Coord> goals = stagingTiles(source, context, target.rc);
                WaterwayRoutePlanner.Route route = WaterwayRoutePlanner.routeToAny(
                    source, context.tile, goals, DETOUR_MAX_TILES);
                debug("MUSSEL-STAGE-PLAN id=%d status=%s route=%d expanded=%d goal=%s",
                    target.id, route.status, route.tiles.size(), route.expanded, route.destination);
                if(!route.reached() || route.tiles.size() < 2) return false;
                MusselRouteOverlay.showSavedDetour(context.segment, route.tiles);
                int goalIndex = MusselNavigatorLogic.lookahead(
                    route.tiles, 1, context.tile, ROUTE_LEG_TILES);
                Move move = moveToSavedTile(context, route.tiles.get(goalIndex), false, "MUSSEL-STAGE");
                legs++;
                if(!move.progressed && !move.arrived) return false;
            }
            Context after = context();
            return target == null || target.disposed()
                || (after != null && after.world.dist(target.rc) <= LOCAL_APPROACH_RADIUS);
        }

        Collection<Coord> stagingTiles(MapFileWaterSource source, Context context, Coord2d target) {
            Coord targetTile = MapTileCoordinates.playerTile(target, context.sessionTile);
            List<Coord> goals = new ArrayList<>();
            for(int y = -4; y <= 4; y++) {
                for(int x = -4; x <= 4; x++) {
                    Coord tile = targetTile.add(x, y);
                    if(source.cell(tile).water()
                        && MapTileCoordinates.worldPosition(tile, context.sessionTile).dist(target) <= PICKUP_HANDOFF_RADIUS - 5.0)
                        goals.add(tile);
                }
            }
            goals.sort((a, b) -> {
                double da = tileDistance(context.tile, a), db = tileDistance(context.tile, b);
                int cmp = Double.compare(da, db);
                if(cmp != 0) return cmp;
                cmp = Integer.compare(a.x, b.x);
                return cmp != 0 ? cmp : Integer.compare(a.y, b.y);
            });
            return goals;
        }

        boolean positionSideOn(Gob target) throws InterruptedException {
            Gob player = gui.map.player();
            Coord2d from = BoatNavigation.position(player);
            if(player == null || from == null || target == null || target.rc == null) return false;
            Coord2d half = BoatNavigation.halfExtents(player);
            if(half == null) half = Coord2d.of(21.0, 8.25);
            waterProbeCache.clear();
            List<MusselExplorerLogic.Approach> approaches = new ArrayList<>(
                MusselExplorerLogic.sideApproaches(target.rc, 33.0, 26.0, 16));
            final Coord2d start = from;
            final double heading = BoatNavigation.heading(player);
            approaches.sort(Comparator.comparingDouble(a -> MusselExplorerLogic.approachScore(start, heading, a)));
            int tried = 0;
            for(MusselExplorerLogic.Approach approach : approaches) {
                bot.checkCancelled();
                if(++tried > 12) break;
                if(!poseWater(approach.stop, approach.heading, half)
                    || !poseWater(approach.staging, approach.heading, half)
                    || !approachCorridorWater(approach.staging, approach.stop, approach.heading, half)) continue;
                Coord2d current = BoatNavigation.position(gui.map.player());
                if(current == null) return false;
                if(current.dist(approach.staging) > MCache.tilesz.x) {
                    Move staged = moveToWorld(approach.staging, true, "MUSSEL-SIDE-STAGE");
                    legs++;
                    if(!staged.progressed && !staged.arrived) continue;
                }
                if(target.disposed()) return true;
                Move stopped = moveToWorld(approach.stop, true, "MUSSEL-SIDE-STOP");
                legs++;
                current = BoatNavigation.position(gui.map.player());
                if(current != null && current.dist(target.rc) <= PICKUP_HANDOFF_RADIUS) {
                    debug("MUSSEL-SIDE-READY id=%d distance=%.1f heading=%.3f", target.id,
                        current.dist(target.rc), approach.heading);
                    return true;
                }
                if(stopped.progressed && current != null && current.dist(target.rc) < DIRECT_PICK_RADIUS) return true;
            }
            return false;
        }

        boolean poseWater(Coord2d center, double boatHeading, Coord2d half) {
            for(Coord2d point : MusselExplorerLogic.footprintSamples(
                center, boatHeading, half.x + 0.75, half.y + 0.75, MCache.tilesz.x / 4.0)) {
                if(!navigableWater(point)) return false;
            }
            return true;
        }

        boolean approachCorridorWater(Coord2d from, Coord2d to, double boatHeading, Coord2d half) {
            int steps = Math.max(1, (int)Math.ceil(from.dist(to) / (MCache.tilesz.x / 3.0)));
            for(int i = 0; i <= steps; i++) {
                double f = (double)i / steps;
                Coord2d center = Coord2d.of(from.x + (to.x - from.x) * f,
                    from.y + (to.y - from.y) * f);
                if(!poseWater(center, boatHeading, half)) return false;
            }
            return true;
        }

        boolean navigableWater(Coord2d point) {
            if(point == null) return false;
            Coord tile = point.floor(MCache.tilesz);
            Boolean cached = waterProbeCache.get(tile);
            if(cached != null) return cached;
            boolean water = TerrainPolicy.isWaterTile(terrainName(point));
            waterProbeCache.put(tile, water);
            return water;
        }

        String terrainName(Coord2d point) {
            if(point == null || gui == null || gui.ui == null || gui.ui.sess == null) return "";
            try {
                Resource resource = gui.ui.sess.glob.map.tilesetr(
                    gui.ui.sess.glob.map.gettile(point.floor(MCache.tilesz)));
                return resource == null ? "" : resource.name;
            } catch(Loading loading) {
                return "loading";
            }
        }

        void defer(Gob target, String reason) {
            if(target == null) return;
            retryAfter.put(target.id, System.currentTimeMillis() + TARGET_RETRY_MS);
            debug("MUSSEL-DEFER id=%d for=%ds reason=%s", target.id, TARGET_RETRY_MS / 1000L, reason);
        }

        void remember(Coord tile) {
            if(tile == null) return;
            recentBoatTiles.addLast(new Coord(tile));
            while(recentBoatTiles.size() > 6) recentBoatTiles.removeFirst();
        }

        boolean oscillating() {
            if(recentBoatTiles.size() < 4) return false;
            return MusselNavigatorLogic.oscillating(recentBoatTiles);
        }

        Context context() {
            if(gui == null || gui.map == null || gui.mapfile == null) return null;
            MiniMap.Location location = gui.mapfile.playerLocation();
            Gob player = gui.map.player();
            Coord2d boat = BoatNavigation.position(player);
            if(location == null || boat == null) return null;
            Coord tile = MapTileCoordinates.playerTile(boat, location.tc);
            return new Context(gui.mapfile.file, location.seg.id, location.tc, tile, boat);
        }

        boolean hasCollectionSpace() {
            return gui.maininv != null && gui.maininv.free() > 0;
        }

        void finish(String message) {status = message;}

        void finishError(String detail) {
            status = "Status: Error — " + detail;
            debug("ERROR detail=%s", detail);
            try {gui.error("Musseler: " + detail + ".");} catch(RuntimeException ignored) {}
        }
    }

    private static final class Context {
        final MapFile file;
        final long segment;
        final Coord sessionTile;
        final Coord tile;
        final Coord2d world;

        Context(MapFile file, long segment, Coord sessionTile, Coord tile, Coord2d world) {
            this.file = file;
            this.segment = segment;
            this.sessionTile = new Coord(sessionTile);
            this.tile = new Coord(tile);
            this.world = world;
        }
    }

    private static final class Move {
        final String result;
        final boolean progressed;
        final boolean arrived;
        final double moved;
        final Coord2d position;

        Move(String result, boolean progressed, boolean arrived, double moved, Coord2d position) {
            this.result = result;
            this.progressed = progressed;
            this.arrived = arrived;
            this.moved = moved;
            this.position = position;
        }

        static Move failed(String result) {return new Move(result, false, false, 0.0, null);}
    }

    private static double routeHeading(List<Coord> route, double fallback) {
        if(route != null) {
            for(int i = route.size() - 1; i > 0; i--) {
                Coord a = route.get(i - 1), b = route.get(i);
                if(!a.equals(b)) return Math.atan2(b.y - a.y, b.x - a.x);
            }
        }
        return fallback;
    }

    private static double tileDistance(Coord a, Coord b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static boolean isRiverMussel(Gob gob) {
        if(gob == null) return false;
        try {
            String resid = gob.resid();
            if(resid == null) return false;
            int slash = resid.lastIndexOf('/');
            String base = slash < 0 ? resid : resid.substring(slash + 1);
            int bracket = base.indexOf('[');
            if(bracket > 0) base = base.substring(0, bracket);
            return MUSSEL_KEY.equalsIgnoreCase(base);
        } catch(Loading unavailable) {
            return false;
        }
    }

    private static String point(Coord2d value) {
        return value == null ? "unavailable"
            : String.format(Locale.ROOT, "(%.1f,%.1f)", value.x, value.y);
    }

    private static String movementSummary(int id, String label, String result,
                                          Coord2d before, Coord2d after,
                                          Coord2d requested, Coord2d effective) {
        double moved = before == null || after == null ? -1.0 : before.dist(after);
        double requestedLeft = after == null || requested == null
            ? Double.POSITIVE_INFINITY : after.dist(requested);
        double effectiveLeft = after == null || effective == null
            ? Double.POSITIVE_INFINITY : after.dist(effective);
        return String.format(Locale.ROOT,
            "id=%d label=%s result=%s moved=%.1f start=%s end=%s requested=%s effective=%s requested_left=%.1f effective_left=%.1f",
            id, label, result, moved, point(before), point(after), point(requested),
            point(effective), requestedLeft, effectiveLeft);
    }

    private static String pathfinderFacts(JSONObject value) {
        if(value == null) return "pf=none";
        return String.format(Locale.ROOT,
            "pf_reason=%s goal_blocked=%s start_blocked=%s start_solid=%s free_goal=%s dilation=%d radius=%.1f obstacles=%d waypoints=%d",
            value.optString("reason", "unknown"), value.optBoolean("goal_blocked", false),
            value.optBoolean("start_blocked", false), value.optBoolean("start_in_solid", false),
            value.opt("free_goal"), value.optInt("dilation", -1),
            value.optDouble("agent_radius", -1.0), value.optInt("obstacles", -1),
            value.optInt("waypoint_count", -1));
    }
}
