package thunder.clearcut;

import auto.Bot;
import auto.Equip;
import auto.Actions;
import auto.GobTarget;
import auto.InvHelper;
import auto.ClearCutSupplies;
import haven.*;
import haven.pathfinding.BotMovement;
import haven.pathfinding.ExactPlacementPlanner;
import haven.pathfinding.ObjectSpatialProfiles;
import haven.pathfinding.PlacementGeometry;
import haven.pathfinding.MovementListeners;
import haven.pathfinding.PlacementEgress;
import haven.pathfinding.PlacementExecutor;
import haven.pathfinding.MovementProfile;
import haven.res.ui.tt.level.Level;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Full-area vegetation/boulder removal, product collection, resupply, and cart-first log cleanup. */
public final class ClearCutBot {
    private static final String DEBUG_LOG = "logs/clearcut-debug.log";
    private static final long STEP_TIMEOUT = 6000L;
    private static final long ACTION_TIMEOUT = 120000L;
    private static final double LOG_PLACEMENT_ANGLE = 0.0;
    private static final double BUSH_APPROACH_RADIUS = MCache.tilesz.x * 1.60;
    private static final double BOULDER_APPROACH_RADIUS = MCache.tilesz.x * 1.25;
    private static volatile boolean running;
    private static volatile String status = "Status: idle";
    private static volatile int treesDone;
    private static volatile int bushesDone;
    private static volatile int bouldersDone;
    private static volatile int stumpsDone;
    private static volatile int logsDone;
    private static volatile int productsDone;
    private static Bot active;

    private ClearCutBot() {}

    public static boolean isRunning() {return running;}
    public static String status() {return status;}

    public static synchronized void start(GameUI gui, ClearCutConfig config) {
        if(running) {gui.error("Clear-Cut is already running."); return;}
        if(gui == null || gui.map == null || gui.menu == null || gui.ui == null) {
            if(gui != null) gui.error("Clear-Cut: the game UI is not ready.");
            return;
        }
        String error = config == null ? "missing setup" : config.validationError();
        if(error != null) {gui.error("Clear-Cut: " + error + "."); return;}
        treesDone = bushesDone = bouldersDone = stumpsDone = logsDone = productsDone = 0;
        running = true;
        setStatus("starting");
        Bot bot = Bot.execute((unused, b) -> {
            try {new Run(gui, b, config).execute();}
            finally {
                running = false;
                active = null;
                try {if(gui.pathQueue != null) gui.pathQueue.clear();} catch(Exception ignored) {}
                returnHeldItem(gui);
            }
        });
        active = bot;
        bot.start(gui.ui, true);
    }

    public static void stop() {
        Bot bot = active;
        if(bot != null) bot.cancel("Clear-Cut stopped by user.");
        setStatus("stopping");
    }

    private static void setStatus(String phase) {
        status = "Status: " + phase + " | trees " + treesDone + " | bushes " + bushesDone +
            " | boulders " + bouldersDone + " | stumps " + stumpsDone +
            " | logs " + logsDone + " | products " + productsDone;
    }

    private static synchronized void diag(String format, Object... args) {
        try {
            File file = new File(DEBUG_LOG);
            File parent = file.getParentFile();
            if(parent != null) parent.mkdirs();
            try(PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                out.printf("%d ", System.currentTimeMillis());
                out.printf(format, args);
                out.println();
            }
        } catch(Exception ignored) {}
    }

    private static void returnHeldItem(GameUI gui) {
        try {
            if(gui == null || gui.hand() == null || gui.maininv == null) return;
            Coord slot = gui.maininv.findPlaceFor(Coord.of(2, 1));
            if(slot == null) slot = gui.maininv.findPlaceFor(Coord.of(1, 1));
            if(slot != null) gui.maininv.wdgmsg("drop", slot);
        } catch(Exception ignored) {}
    }

    private static final class Abort extends Exception {
        final boolean success;
        Abort(String message, boolean success) {super(message); this.success = success;}
    }

    private enum Kind {TREE, BUSH, BOULDER, STUMP, LOG}

    private static final class TargetRef {
        final Kind kind;
        final long id;
        final Coord2d position;
        final String resource;
        TargetRef(Kind kind, Gob gob, String resource) {
            this.kind = kind; this.id = gob.id; this.position = gob.rc; this.resource = resource;
        }
        String key() {return kind.name() + ":" + id;}
    }

    private static final class GobRef {
        final long id;
        final Coord2d position;
        GobRef(Gob gob) {this.id = gob.id; this.position = gob.rc;}
    }

    private static final class Survey {
        final Map<String, TargetRef> targets = new LinkedHashMap<>();
        List<TargetRef> of(Kind kind) {
            List<TargetRef> out = new ArrayList<>();
            for(TargetRef ref : targets.values()) if(ref.kind == kind) out.add(ref);
            return out;
        }
        boolean empty() {
            return ClearCutRules.completionSatisfied(of(Kind.TREE).size(), of(Kind.BUSH).size(),
                of(Kind.BOULDER).size(), of(Kind.STUMP).size(), of(Kind.LOG).size());
        }
    }

    private static final class Run {
        private final GameUI gui;
        private final Bot bot;
        private final ClearCutConfig config;
        private final Map<String, TargetRef> pendingLogs = new LinkedHashMap<>();
        private final Map<Long, GobRef> discoveredCarts = new LinkedHashMap<>();
        private final Map<Long, ExactPlacementPlanner.Shape> observedDropObstacles = new LinkedHashMap<>();
        private final Set<GItem> collectedProducts = Collections.newSetFromMap(new IdentityHashMap<GItem, Boolean>());
        private boolean productCollectionEnabled;
        private int treesInBatch;
        private String phase = "startup";
        private Coord2d cartOrderOrigin;
        private final Coord2d dropLayoutOrigin;

        Run(GameUI gui, Bot bot, ClearCutConfig config) {
            this.gui = gui; this.bot = bot; this.config = config;
            this.productCollectionEnabled = config.collectTreeProducts;
            this.dropLayoutOrigin = areaCenter(config.clearCut);
        }

        void execute() {
            try {
                runLoop();
            } catch(InterruptedException e) {
                setStatus("stopped by user");
            } catch(Abort abort) {
                setStatus(abort.getMessage());
                if(abort.success) gui.msg("Clear-Cut: " + abort.getMessage(), GameUI.MsgType.GOOD);
                else gui.error("Clear-Cut: " + abort.getMessage());
            } catch(Throwable t) {
                setStatus("error: " + t.getClass().getSimpleName());
                gui.error("Clear-Cut failed: " + t.getClass().getSimpleName() +
                    (t.getMessage() == null ? "" : " — " + t.getMessage()));
                Debug.log.println("[clearcut] " + t);
                t.printStackTrace(Debug.log);
            }
        }

        private void runLoop() throws InterruptedException, Abort {
            preflight();
            ensureSupplies();
            indexLoadedLogDropOff();

            // Start useful work immediately from the objects the client already has
            // loaded. A full source-area survey here made every start (and restart)
            // walk the clear-cut boundary before lifting even an obvious nearby log.
            // The exhaustive sweep still runs below, after visible work is exhausted,
            // so initially unloaded targets cannot be missed at completion.
            Survey initial = loadedWorkArea();
            // Clear boulders before loose logs. A boulder can overlap a log's
            // interaction side and make the game's carry click walk into it.
            chipRefs(initial.of(Kind.BOULDER));
            queueLogs(initial.of(Kind.LOG));
            haulPendingLogs();
            digRefs(initial.of(Kind.STUMP));
            processBushes(initial.of(Kind.BUSH));
            processTrees(initial.of(Kind.TREE));
            flushBatch();

            for(int pass = 1; pass <= ClearCutConfig.MAX_ATTEMPTS; pass++) {
                Survey finalSurvey = surveyWorkArea("final verification " + pass);
                if(finalSurvey.empty() && confirmAreaEmpty(finalSurvey)) {
                    depositProducts();
                    done("complete");
                }
                chipRefs(finalSurvey.of(Kind.BOULDER));
                queueLogs(finalSurvey.of(Kind.LOG));
                haulPendingLogs();
                digRefs(finalSurvey.of(Kind.STUMP));
                processBushes(finalSurvey.of(Kind.BUSH));
                processTrees(finalSurvey.of(Kind.TREE));
                flushBatch();
            }
            Survey remaining = surveyWorkArea("completion check");
            if(!remaining.empty() || !confirmAreaEmpty(remaining))
                fail("targets remain or the clear-cut area could not be confirmed empty after three verification passes");
            depositProducts();
            done("complete");
        }

        private Survey loadedWorkArea() {
            Survey survey = new Survey();
            captureTargets(survey);
            return survey;
        }

        private void preflight() throws InterruptedException, Abort {
            setPhase("preflight");
            String error = config.validationError();
            if(error != null) fail(error);
            for(String warning : config.overlapWarnings())
                gui.msg("Clear-Cut warning: " + warning + ".", GameUI.MsgType.INFO);
            IMeter energy = gui.getIMeter("nrj");
            if(energy == null) fail("energy meter is unavailable");
            if(!config.hasFoodArea() && ClearCutRules.needsEnergy(energy.meter(0)))
                fail("energy is below 4,000% and no energy-food area was selected");
            if(!CFG.AUTO_DRINK_ENABLED.get() || CFG.AUTO_DRINK_THRESHOLD.get() <= 0)
                fail("global auto-drink must be enabled with a non-zero threshold");
            if(!hasDrinkVessel()) fail("carry a waterskin, flask, jug, kuksa, or bucket");
            if(!Equip.hasInHandsOrBelt(gui, Equip.WOODCUT_AXE)) fail("no compatible woodcutting axe found in hands or belt");
            if(!Equip.hasInHandsOrBelt(gui, Equip.SHOVEL)) fail("no compatible shovel found in hands or belt");
            if(!Equip.canEnsureTwoHanded(gui, Equip.WOODCUT_AXE) || !Equip.canEnsureTwoHanded(gui, Equip.SHOVEL))
                fail("the belt needs an empty slot to swap the selected axe and shovel");
            if(gui.hand() != null) fail("clear the cursor before starting");

            // Do not travel to inspect optional supply areas at startup. Runtime
            // resupply validates them only when carried water is actually empty
            // or energy has actually fallen below the eating threshold.
            if(!config.hasWaterArea() && !hasWater()) {
                fail("no water remains in carried drink containers and no water area was selected");
            }
        }

        private boolean hasDrinkVessel() {
            for(WItem item : InvHelper.HANDS(gui).get()) if(InvHelper.isDrinkContainer(item) || InvHelper.isBucket(item)) return true;
            for(WItem item : InvHelper.POUCHES(gui).get()) if(InvHelper.isDrinkContainer(item)) return true;
            for(WItem item : InvHelper.INVENTORY(gui).get()) if(InvHelper.isDrinkContainer(item)) return true;
            for(WItem item : InvHelper.BELT(gui).get()) if(InvHelper.isDrinkContainer(item)) return true;
            return false;
        }

        private List<WItem> drinkVessels() {
            List<WItem> out = new ArrayList<>();
            Set<GItem> seen = Collections.newSetFromMap(new IdentityHashMap<GItem, Boolean>());
            for(WItem item : InvHelper.HANDS(gui).get())
                if((InvHelper.isDrinkContainer(item) || InvHelper.isBucket(item)) && seen.add(item.item)) out.add(item);
            for(WItem item : InvHelper.POUCHES(gui).get())
                if(InvHelper.isDrinkContainer(item) && seen.add(item.item)) out.add(item);
            for(WItem item : InvHelper.INVENTORY(gui).get())
                if(InvHelper.isDrinkContainer(item) && seen.add(item.item)) out.add(item);
            for(WItem item : InvHelper.BELT(gui).get())
                if(InvHelper.isDrinkContainer(item) && seen.add(item.item)) out.add(item);
            return out;
        }

        private boolean hasWater() {
            for(WItem item : InvHelper.HANDS(gui).get()) if(itemHasWater(item)) return true;
            for(WItem item : InvHelper.POUCHES(gui).get()) if(itemHasWater(item)) return true;
            for(WItem item : InvHelper.INVENTORY(gui).get()) if(itemHasWater(item)) return true;
            for(WItem item : InvHelper.BELT(gui).get()) if(itemHasWater(item)) return true;
            return false;
        }

        private boolean itemHasWater(WItem item) {
            try {
                return InvHelper.HAS_WATER.test(item);
            } catch(Loading ignored) {
                return false;
            }
        }

        private boolean allDrinkVesselsFull() {
            List<WItem> vessels = drinkVessels();
            if(vessels.isEmpty()) return false;
            for(WItem item : vessels) {
                try {
                    Level fullness = item.fullness.get();
                    if(fullness == null || fullness.cur != fullness.max || !InvHelper.HAS_WATER.test(item)) return false;
                } catch(Loading ignored) {
                    return false;
                }
            }
            return true;
        }

        private void ensureSupplies() throws InterruptedException, Abort {
            bot.checkCancelled();
            if(!hasWater()) {
                if(!config.hasWaterArea())
                    fail("no water remains in carried drink containers and no water area was selected");
                setPhase("refilling water");
                observeArea(config.water, "water area");
                boolean filled = false;
                for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS && !filled; attempt++) {
                    boolean ok = ClearCutSupplies.refillWaterFromZone(gui, bot, config.water);
                    filled = ok && waitFor(STEP_TIMEOUT, this::allDrinkVesselsFull);
                }
                if(!filled) fail("could not refill every carried drink vessel from the selected area");
            }
            IMeter energy = gui.getIMeter("nrj");
            double current = energy == null ? -1 : energy.meter(0);
            if(ClearCutRules.needsEnergy(current)) {
                if(!config.hasFoodArea())
                    fail("energy fell below 4,000% and no energy-food area was selected");
                setPhase("eating to 8,000%");
                observeArea(config.food, "food area");
                boolean restored = false;
                for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS && !restored; attempt++)
                    restored = ClearCutSupplies.eatFromZone(gui, bot, config.food, ClearCutConfig.EAT_UNTIL_PERCENT);
                energy = gui.getIMeter("nrj");
                current = energy == null ? -1 : energy.meter(0);
                if(!restored || !ClearCutRules.energyTargetReached(current))
                    fail("food containers or tables could not restore energy to 8,000%");
            }
        }

        private Survey surveyWorkArea(String label) throws InterruptedException, Abort {
            List<Coord> waypoints = ClearCutRules.surveyTiles(config.clearCut, ClearCutConfig.SURVEY_LANE_TILES);
            Survey survey = new Survey();
            for(int i = 0; i < waypoints.size(); i++) {
                bot.checkCancelled();
                ensureSupplies();
                setPhase(label + " " + (i + 1) + "/" + waypoints.size());
                List<Coord2d> targets = surveyObservationPoints(waypoints.get(i), config.clearCut);
                if(targets.isEmpty() || !plannedWalkToAny(targets, 20000L, MCache.tilesz.x * 1.25))
                    fail("could not cover survey tile " + waypoints.get(i));
                waitMovement();
                // Object and tag updates can trail movement slightly. Sample the
                // settled scene more than once so one loading frame cannot prove
                // that the area is empty.
                Thread.sleep(250L);
                captureTargets(survey);
                Thread.sleep(250L);
                captureTargets(survey);
            }
            captureTargets(survey);
            return survey;
        }

        /** Completion is fail-closed: require three consecutive settled scans.
         * Any supported work target seen by any scan cancels the empty confirmation. */
        private boolean confirmAreaEmpty(Survey survey) throws InterruptedException {
            for(int scan = 1; scan <= 3; scan++) {
                bot.checkCancelled();
                captureTargets(survey);
                if(!survey.empty()) return false;
                if(scan < 3) Thread.sleep(300L);
            }
            return true;
        }

        /** Remember carts/obstacles already loaded by the client without walking
         * the drop-off boundary. A later haul travels directly to a known cart,
         * or makes one observation trip when the area was not loaded at start. */
        private void indexLoadedLogDropOff() {
            Gob player = gui.map.player();
            cartOrderOrigin = player == null ? null : player.rc;
            captureDropOffObjects();
        }

        private void captureDropOffObjects() {
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(!validGob(gob) || gob == gui.map.player() || gob.getattr(Following.class) != null ||
                       !config.logDropOff.contains(gob.rc.floor(MCache.tilesz))) continue;
                    ExactPlacementPlanner.Shape shape = worldShape(gob);
                    if(shape != null) observedDropObstacles.put(gob.id, shape);
                    if(ClearCutRules.isCart(resid(gob))) discoveredCarts.putIfAbsent(gob.id, new GobRef(gob));
                }
            }
        }

        private void captureTargets(Survey survey) {
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(!validGob(gob) || !config.clearCut.contains(gob.rc.floor(MCache.tilesz))) continue;
                    String resource = resid(gob);
                    Kind kind = kindOf(gob);
                    if(kind != null && gob.getattr(Following.class) == null) {
                        TargetRef ref = new TargetRef(kind, gob, resource);
                        survey.targets.put(ref.key(), ref);
                    }
                }
            }
        }

        private void processTrees(List<TargetRef> refs) throws InterruptedException, Abort {
            List<TargetRef> remaining = new ArrayList<>(refs);
            while(!remaining.isEmpty()) {
                sortByPlayer(remaining);
                TargetRef ref = remaining.remove(0);
                Gob tree = resolve(ref);
                if(tree == null || kindOf(tree) != Kind.TREE) continue;
                if(!processTree(tree)) continue;
                treesDone++;
                treesInBatch++;
                setPhase("processed tree " + treesDone);
                if(ClearCutRules.shouldHaul(treesInBatch)) flushBatch();
            }
        }

        private boolean processTree(Gob tree) throws InterruptedException, Abort {
            ensureSupplies();
            Coord2d origin = tree.rc;
            if(productCollectionEnabled) collectProducts(tree, Kind.TREE, "tree");
            if(!Equip.ensureTwoHanded(gui, bot, Equip.WOODCUT_AXE)) fail("could not equip a woodcutting axe");
            tree = currentGob(tree.id, Kind.TREE);
            if(tree == null) return false;
            if(!approachWithRetries(tree)) fail("could not reach tree " + tree.id);
            setPhase("chopping tree " + (treesDone + 1));
            if(!chooseAndVerify(tree, "Chop", Kind.TREE)) fail("tree " + tree.id + " did not finish chopping");
            capturePendingLogs(origin);
            final Gob[] stumpResult = new Gob[1];
            waitFor(3000L, () -> {
                stumpResult[0] = findNear(Kind.STUMP, origin, MCache.tilesz.x * 1.75);
                return stumpResult[0] != null;
            });
            Gob stump = stumpResult[0];
            if(stump != null) digStump(stump);
            return true;
        }

        private void processBushes(List<TargetRef> refs) throws InterruptedException, Abort {
            List<TargetRef> remaining = new ArrayList<>(refs);
            while(!remaining.isEmpty()) {
                sortByPlayer(remaining);
                TargetRef ref = remaining.remove(0);
                Gob bush = resolve(ref);
                if(bush == null || kindOf(bush) != Kind.BUSH) continue;
                ensureSupplies();
                if(productCollectionEnabled) collectProducts(bush, Kind.BUSH, "bush");
                if(!Equip.ensureTwoHanded(gui, bot, Equip.WOODCUT_AXE))
                    fail("could not equip a woodcutting axe");
                bush = currentGob(bush.id, Kind.BUSH);
                if(bush == null) continue;
                if(!approachBushWithRetries(bush)) fail("could not reach bush " + bush.id);
                setPhase("chopping bush " + (bushesDone + 1));
                if(!chooseAndVerify(bush, "Chop", Kind.BUSH))
                    fail("bush " + bush.id + " did not finish chopping");
                bushesDone++;
                setPhase("chopped bush " + bushesDone);
            }
        }

        private void chipRefs(List<TargetRef> refs) throws InterruptedException, Abort {
            List<TargetRef> remaining = new ArrayList<>(refs);
            while(!remaining.isEmpty()) {
                sortByPlayer(remaining);
                TargetRef ref = remaining.remove(0);
                Gob boulder = resolve(ref);
                if(boulder != null && kindOf(boulder) == Kind.BOULDER) chipBoulder(boulder);
            }
        }

        /** Chip one boulder to exhaustion and ground-drop every newly produced
         * stone/ore item so a full inventory cannot stop the work. */
        private void chipBoulder(Gob boulder) throws InterruptedException, Abort {
            final long boulderId = boulder.id;
            int chips = 0;
            int failedActions = 0;
            while(chips < 500) {
                bot.checkCancelled();
                ensureSupplies();
                boulder = currentGob(boulderId, Kind.BOULDER);
                if(boulder == null) {
                    bouldersDone++;
                    setPhase("chipped boulder " + bouldersDone);
                    return;
                }
                if(!ensureBoulderTool())
                    fail("could not equip a pickaxe or woodcutting axe for boulder " + boulderId);
                if(!approachBoulderWithRetries(boulder)) fail("could not reach boulder " + boulderId);
                setPhase("chipping boulder " + (bouldersDone + 1));

                // Start each chip with a clean inventory. More importantly, the
                // wait below keeps watching while the action runs and Ctrl-drops
                // the top-level rock stack as soon as the server adds it.
                ctrlDropRockStacks();
                FlowerMenu menu = openMenu(boulder);
                if(menu == null) {
                    if(currentGob(boulderId, Kind.BOULDER) == null) continue;
                    if(++failedActions >= ClearCutConfig.MAX_ATTEMPTS)
                        fail("Chip stone menu did not appear for boulder " + boulderId);
                    continue;
                }
                FlowerMenu.Petal chip = petal(menu, "Chip stone");
                if(chip == null) {
                    menu.choose(null);
                    if(currentGob(boulderId, Kind.BOULDER) == null) continue;
                    fail("Chip stone action is unavailable for boulder " + boulderId);
                }
                menu.choose(chip);
                ChipDropResult result = waitForChipAndDropRocks(boulderId);
                if((!result.started || !result.produced) && !result.gone) {
                    if(++failedActions >= ClearCutConfig.MAX_ATTEMPTS)
                        fail("boulder " + boulderId + " did not produce a rock after three attempts");
                } else {
                    failedActions = 0;
                    chips++;
                }
            }
            fail("boulder " + boulderId + " exceeded the 500-chip safety limit");
        }

        private boolean ensureBoulderTool() throws InterruptedException {
            if(Equip.hasInHandsOrBelt(gui, Equip.PICKAXE))
                return Equip.ensureTwoHanded(gui, bot, Equip.PICKAXE);
            return Equip.ensureTwoHanded(gui, bot, Equip.WOODCUT_AXE);
        }

        private void collectProducts(Gob source, Kind sourceKind, String sourceName)
                throws InterruptedException {
            if(!productCollectionEnabled || source == null) return;
            TargetRef sourceRef = new TargetRef(sourceKind, source, resid(source));
            if(!ensureProductSpace()) return;
            source = resolve(sourceRef);
            if(source == null || !approachProductSource(source, sourceKind)) {
                diag("PRODUCT skip source=%s id=%d reason=approach-failed", sourceName, sourceRef.id);
                return;
            }

            int actions = 0;
            while(productCollectionEnabled && actions < ClearCutConfig.MAX_PRODUCT_ACTIONS_PER_SOURCE) {
                bot.checkCancelled();
                if(!hasProductSpace()) {
                    if(!ensureProductSpace()) return;
                    source = resolve(sourceRef);
                    if(source == null || !approachProductSource(source, sourceKind)) {
                        diag("PRODUCT skip source=%s id=%d reason=return-approach-failed", sourceName, sourceRef.id);
                        return;
                    }
                } else {
                    source = currentGob(sourceRef.id, sourceKind);
                    if(source == null) return;
                }

                FlowerMenu menu = openProductMenu(source);
                if(menu == null) {
                    diag("PRODUCT skip source=%s id=%d reason=menu-unavailable", sourceName, sourceRef.id);
                    return;
                }
                List<FlowerMenu.Petal> products = productPetals(menu);
                if(products.isEmpty()) {
                    menu.choose(null);
                    return;
                }

                FlowerMenu.Petal product = products.get(0);
                Set<GItem> before = inventoryItemIdentities();
                setPhase("collecting " + product.name);
                menu.choose(product);
                boolean completed = waitForProductProgressToFinish();
                Thread.sleep(250L);
                registerNewProducts(before);
                if(gui.hand() != null && !returnHeldProduct()) {
                    disableProductCollection("a collected product could not be returned to inventory");
                    if(!dropHeldProduct()) waitForProductCursorClear();
                    return;
                }
                if(!completed) {
                    diag("PRODUCT skip action=%s source=%s id=%d reason=action-not-completed",
                        product.name, sourceName, sourceRef.id);
                    return;
                }
                actions++;
                productsDone++;
                setPhase("finished " + product.name);
            }
            if(actions >= ClearCutConfig.MAX_PRODUCT_ACTIONS_PER_SOURCE) {
                diag("PRODUCT stop source=%s id=%d reason=action-limit", sourceName, sourceRef.id);
                gui.msg("Clear-Cut warning: product action limit reached for one " + sourceName +
                    "; continuing with clearing.", GameUI.MsgType.INFO);
            }
        }

        private boolean approachProductSource(Gob source, Kind sourceKind) throws InterruptedException {
            return sourceKind == Kind.BUSH
                ? approachBushWithRetries(source)
                : approachWithRetries(source);
        }

        private List<FlowerMenu.Petal> productPetals(FlowerMenu menu) {
            List<FlowerMenu.Petal> out = new ArrayList<>();
            if(menu.opts != null) for(FlowerMenu.Petal petal : menu.opts)
                if(ClearCutRules.isTreeProductAction(petal.name)) out.add(petal);
            out.sort(Comparator.comparingInt(p -> ClearCutRules.productPriority(p.name)));
            return out;
        }

        private boolean hasProductSpace() {
            return gui.maininv != null && gui.maininv.findPlaceFor(Coord.of(2, 1)) != null;
        }

        private boolean ensureProductSpace() throws InterruptedException {
            if(hasProductSpace()) return true;
            depositProducts();
            if(hasProductSpace()) return true;
            disableProductCollection("main inventory has no 2x1 space for another tree product");
            return false;
        }

        private void digRefs(List<TargetRef> refs) throws InterruptedException, Abort {
            for(TargetRef ref : refs) {
                Gob stump = resolve(ref);
                if(stump != null && kindOf(stump) == Kind.STUMP) digStump(stump);
            }
        }

        private void digStump(Gob stump) throws InterruptedException, Abort {
            TargetRef ref = new TargetRef(Kind.STUMP, stump, resid(stump));
            ensureSupplies();
            setPhase("approaching stump " + ref.id);
            // A food/water trip can unload the original Gob instance. Resolve it
            // again before asking the pathfinder for its current geometry.
            stump = resolve(ref);
            if(stump == null || kindOf(stump) != Kind.STUMP) return;
            final long stumpId = stump.id;
            Coord2d stumpPosition = stump.rc;
            if(!Equip.ensureTwoHanded(gui, bot, Equip.SHOVEL)) fail("could not equip a shovel");
            if(!approachWithRetries(stump)) fail("could not reach stump " + stumpId);
            setPhase("digging stump " + stumpId);
            for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS; attempt++) {
                bot.checkCancelled();
                MenuGrid.Pagina destroy = destroyAction();
                if(destroy == null) fail("Destroy action is unavailable");
                destroy.button().use(new MenuGrid.Interaction(1, 0));
                Thread.sleep(150L);
                clickGob(stump, 1);
                boolean removed = waitFor(ACTION_TIMEOUT, () -> currentGob(stumpId, Kind.STUMP) == null);
                cancelMapAction();
                if(removed) {
                    stumpsDone++;
                    collectToughRoots(stumpPosition);
                    waitForRootInventorySpace();
                    setPhase("dug stump " + stumpsDone);
                    return;
                }
            }
            fail("stump " + stumpId + " remained after three attempts");
        }

        /** Tough Root is a short-lived ground drop; Shift+right-click collects its local group. */
        private void collectToughRoots(Coord2d stumpPosition) throws InterruptedException, Abort {
            final Gob[] spawned = new Gob[1];
            waitFor(1500L, () -> {
                spawned[0] = nearestToughRoot(stumpPosition);
                return spawned[0] != null;
            });
            int groups = 0;
            while(spawned[0] != null || (spawned[0] = nearestToughRoot(stumpPosition)) != null) {
                if(++groups > 20) fail("tough-root pickup exceeded its safety limit near " + stumpPosition);
                waitForRootInventorySpace();
                Gob root = spawned[0];
                spawned[0] = null;
                boolean removed = false;
                for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS && !removed; attempt++) {
                    bot.checkCancelled();
                    setPhase("collecting Tough Root " + root.id);
                    removed = Actions.pickupTarget(new GobTarget(root), bot, 15000L);
                    if(!removed && rootInventoryFull()) waitForRootInventorySpace();
                }
                if(!removed) fail("Tough Root " + root.id + " was not picked up after three attempts");
                // Let the server's Shift-pick queue consume nearby roots before deciding
                // whether another group command is necessary.
                waitFor(1500L, () -> nearestToughRoot(stumpPosition) == null || rootInventoryFull());
                waitForRootInventorySpace();
            }
        }

        private Gob nearestToughRoot(Coord2d center) {
            Gob nearest = null;
            double nearestDistance = Double.POSITIVE_INFINITY;
            double radius = MCache.tilesz.x * 3.0;
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(!validGob(gob) || !ClearCutRules.isToughRoot(resid(gob))) continue;
                    double distance = gob.rc.dist(center);
                    if(distance <= radius && distance < nearestDistance) {
                        nearest = gob;
                        nearestDistance = distance;
                    }
                }
            }
            return nearest;
        }

        private boolean rootInventoryFull() {
            int freeSlots = gui.maininv != null && gui.maininv.findPlaceFor(Coord.of(1, 1)) != null ? 1 : 0;
            return ClearCutRules.rootInventoryNeedsAttention(freeSlots);
        }

        /** Pauses this bot thread until the user frees a main-inventory square. */
        private void waitForRootInventorySpace() throws InterruptedException, Abort {
            if(!rootInventoryFull()) return;
            setPhase("PAUSED — inventory full (Tough/Strange Root)");
            gui.error("Clear-Cut paused: inventory full. Empty at least one main-inventory slot for Tough/Strange Roots.");
            while(rootInventoryFull()) {
                bot.checkCancelled();
                Thread.sleep(500L);
            }
            gui.msg("Clear-Cut: inventory space detected; resuming.", GameUI.MsgType.GOOD);
        }

        private boolean chooseAndVerify(Gob gob, String action, Kind beforeKind) throws InterruptedException {
            for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS; attempt++) {
                FlowerMenu menu = openMenu(gob);
                if(menu == null) continue;
                FlowerMenu.Petal petal = petal(menu, action);
                if(petal == null) {menu.choose(null); return false;}
                menu.choose(petal);
                if(waitFor(ACTION_TIMEOUT, () -> currentGob(gob.id, beforeKind) == null)) return true;
            }
            return false;
        }

        private void flushBatch() throws InterruptedException, Abort {
            if(productCollectionEnabled) depositProducts();
            capturePendingLogs(null);
            haulPendingLogs();
            treesInBatch = 0;
        }

        private void queueLogs(List<TargetRef> refs) {
            for(TargetRef ref : refs) pendingLogs.put(ref.key(), ref);
        }

        private void capturePendingLogs(Coord2d origin) {
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(!validGob(gob) || gob.getattr(Following.class) != null) continue;
                    String resource = resid(gob);
                    if(kindOf(gob) != Kind.LOG) continue;
                    boolean source = config.clearCut.contains(gob.rc.floor(MCache.tilesz));
                    boolean spawnedNear = origin != null && gob.rc.dist(origin) <= MCache.tilesz.x * 3.0;
                    if(source || spawnedNear) {
                        TargetRef ref = new TargetRef(Kind.LOG, gob, resource);
                        pendingLogs.put(ref.key(), ref);
                    }
                }
            }
        }

        private void haulPendingLogs() throws InterruptedException, Abort {
            while(!pendingLogs.isEmpty()) {
                List<TargetRef> refs = new ArrayList<>(pendingLogs.values());
                sortByPlayer(refs);
                TargetRef ref = refs.get(0);
                pendingLogs.remove(ref.key());
                Gob log = resolve(ref);
                if(log == null || kindOf(log) != Kind.LOG) continue;
                ensureSupplies();
                setPhase("hauling log " + (logsDone + 1));
                Gob carried = liftLog(log);
                if(carried == null) fail("could not lift log " + log.id);
                if(!loadIntoCart(carried)) dropCarriedLog(carried);
                logsDone++;
            }
        }

        private Gob liftLog(Gob log) throws InterruptedException {
            for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS; attempt++) {
                Gob player = gui.map.player();
                diag("LIFT attempt=%d log=%d log-pos=%s player=%s phase=approach",
                    attempt, log.id, log.rc, player == null ? null : player.rc);
                if(!approachWithRetries(log)) {
                    diag("LIFT attempt=%d log=%d result=approach-failed", attempt, log.id);
                    continue;
                }
                gui.wdgmsg("act", "carry");
                Thread.sleep(100L);
                clickGob(log, 1);
                Gob carried = waitCarriedLog(log.id, STEP_TIMEOUT);
                if(carried != null) {
                    diag("LIFT attempt=%d log=%d result=carried carried-id=%d", attempt, log.id, carried.id);
                    return carried;
                }
                player = gui.map.player();
                diag("LIFT attempt=%d log=%d result=carry-not-observed log-pos=%s player=%s",
                    attempt, log.id, log.rc, player == null ? null : player.rc);
            }
            return null;
        }

        private boolean loadIntoCart(Gob carried) throws InterruptedException, Abort {
            // Prefer carts that were visible at setup. If the area was outside
            // object-loading range, make one direct observation trip now rather
            // than sweeping its perimeter at startup.
            captureDropOffObjects();
            if(discoveredCarts.isEmpty()) {
                observeArea(config.logDropOff, "log drop-off");
                captureDropOffObjects();
            }
            List<GobRef> carts = new ArrayList<>(discoveredCarts.values());
            carts.sort(Comparator.<GobRef>comparingDouble(ref -> cartOrderOrigin == null ? 0 : cartOrderOrigin.dist(ref.position))
                .thenComparingLong(ref -> ref.id));
            for(GobRef cartRef : carts) {
                Gob cart = resolveGob(cartRef);
                if(cart == null) fail("cart " + cartRef.id + " could not be reloaded at its observed position");
                int state = stableCartState(cart);
                if(state < 0) fail("cart " + cart.id + " cargo state is unreadable");
                if(ClearCutRules.cartFull(state)) continue;
                if(!approachCartWithRetries(cart))
                    fail("could not reach an interaction position beside cart " + cart.id + " while carrying log " + carried.id);
                for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS; attempt++) {
                    int before = ClearCutRules.occupiedCartCount(cartModelState(cart));
                    clickGob(cart, 3);
                    boolean loaded = waitFor(STEP_TIMEOUT, () -> {
                        int now = cartModelState(cart);
                        return !isCarried(carried) && now >= 0 && ClearCutRules.occupiedCartCount(now) > before;
                    });
                    int after = stableCartState(cart);
                    if(loaded && after >= 0 && ClearCutRules.occupiedCartCount(after) > before && !isCarried(carried)) {
                        return true;
                    }
                    if(!isCarried(carried)) fail("cart interaction released log without confirming a cargo slot");
                }
                fail("cart " + cart.id + " rejected a log while it still had an open slot");
            }
            return false;
        }

        private void dropCarriedLog(Gob carried) throws InterruptedException, Abort {
            observeArea(config.logDropOff, "log drop-off");
            double[] gaps = {ObjectSpatialProfiles.resolve(resid(carried)).placementGap, 0.25, 0.5};
            boolean foundGeometricSlot = false;
            double lastWidth = 0.0, lastHeight = 0.0;
            for(double gap : gaps) {
                // The server accepts the exact anchor while the player stands nearby.
                // PlacementExecutor first reaches an open point within placement
                // range so the final server step cannot cut through prior logs.
                double plannedAngle = LOG_PLACEMENT_ANGLE;
                ExactPlacementPlanner.Shape footprint = relativeShape(carried, plannedAngle);
                if(footprint == null) fail("could not read carried log footprint");
                lastWidth = footprint.bounds.width();
                lastHeight = footprint.bounds.height();
                List<ExactPlacementPlanner.Shape> obstacles = dropObstacles(carried);
                List<ExactPlacementPlanner.Shape> planningObstacles = new ArrayList<>(obstacles);
                for(int anchorAttempt = 1;
                    anchorAttempt <= ClearCutConfig.MAX_LOG_PLACEMENT_ANCHORS;
                    anchorAttempt++) {
                    List<Coord2d> anchors = ExactPlacementPlanner.planExact(
                        config.logDropOff, MCache.tilesz, footprint, planningObstacles,
                        dropLayoutOrigin, gap, 1,
                        ExactPlacementPlanner.FillOrder.SIDE_BY_SIDE_BACK_TO_FRONT);
                    Coord2d point = anchors.isEmpty() ? null : anchors.get(0);
                    if(point == null) {
                        diag("DROP no-slot log=%d carried-angle=%.6f planned-angle=%.6f footprint=%.3fx%.3f area=%dx%d-tiles gap=%.3f obstacles=%d reason=%s",
                            carried.id, carried.a, plannedAngle, lastWidth, lastHeight,
                            config.logDropOff.sz().x, config.logDropOff.sz().y, gap,
                            obstacles.size(), "no exact placement fits inside the selected area");
                        if(anchorAttempt > 1) {
                            pauseForLogDropOffHelp(carried, true);
                            return;
                        }
                        break;
                    }
                    foundGeometricSlot = true;
                    Coord2d before = carried.rc == null ? null : Coord2d.of(carried.rc.x, carried.rc.y);
                    PlacementExecutor.Result commit = PlacementExecutor.commitLifted(
                        gui, bot, carried, config.logDropOff, point, plannedAngle,
                        20000L, STEP_TIMEOUT, MovementListeners.NOOP);
                    if(!commit.sent()) {
                        diag("DROP commit-failed log=%d anchor=%s gap=%.3f candidate=%d/%d outcome=%s detail=%s",
                            carried.id, point, gap, anchorAttempt,
                            ClearCutConfig.MAX_LOG_PLACEMENT_ANCHORS, commit.outcome, commit.detail);
                        if(commit.outcome == PlacementExecutor.Outcome.STAGING_FAILED) {
                            planningObstacles.add(footprint.move(point));
                            if(anchorAttempt == ClearCutConfig.MAX_LOG_PLACEMENT_ANCHORS) {
                                pauseForLogDropOffHelp(carried, true);
                                return;
                            }
                            continue;
                        }
                        break;
                    }
                    diag("DROP command log=%d from=%s anchor=%s carried-angle=%.6f planned-angle=%.6f gap=%.3f obstacles=%d",
                        carried.id, before, point, carried.a, plannedAngle, gap, obstacles.size());
                    Gob grounded = waitForGroundedPose(carried, before, STEP_TIMEOUT + 3000L);
                    if(grounded != null) {
                        String problem = groundDropProblem(grounded);
                        diag("DROP observed log=%d actual=%s angle=%.6f intended=%s result=%s",
                            grounded.id, grounded.rc, grounded.a, point,
                            problem == null ? "accepted" : problem);
                        if(problem == null) {
                            ObjectSpatialProfiles.confirmPlacementGap(resid(grounded), gap);
                            ExactPlacementPlanner.Shape placed = worldShape(grounded);
                            if(placed != null) observedDropObstacles.put(grounded.id, placed);
                            if(!stepClearOfDroppedLog(grounded, placed))
                                fail("log was placed, but the character could not step clear of its collision area");
                            return;
                        }
                        Gob lifted = liftDroppedAgain(grounded);
                        if(lifted == null)
                            fail("server placed log illegally (" + problem + ") and it could not be lifted again");
                        carried = lifted;
                    } else if(!isCarried(carried)) {
                        fail("log " + carried.id + " was released but its authoritative ground position never arrived");
                    } else {
                        diag("DROP rejected log=%d anchor=%s gap=%.3f", carried.id, point, gap);
                    }
                    break;
                }
            }
            if(!foundGeometricSlot)
                diag("DROP full log=%d footprint=%.3fx%.3f area=%dx%d-tiles",
                    carried.id, lastWidth, lastHeight, config.logDropOff.sz().x,
                    config.logDropOff.sz().y);
            pauseForLogDropOffHelp(carried, foundGeometricSlot);
        }

        private void pauseForLogDropOffHelp(Gob carried, boolean blocked)
                throws InterruptedException {
            String reason = blocked ? "blocked" : "full";
            diag("DROP paused log=%d reason=%s", carried.id, reason);
            setPhase("PAUSED — log drop-off " + reason);
            gui.error("Clear-Cut paused: the log drop-off is " + reason +
                ". Move existing logs or manually place the carried log; Clear-Cut will continue when it is released.");
            while(isCarried(carried)) {
                bot.checkCancelled();
                Thread.sleep(500L);
            }
            Gob grounded = gui.ui.sess.glob.oc.getgob(carried.id);
            ExactPlacementPlanner.Shape placed = worldShape(grounded);
            if(placed != null && boundsTouchArea(placed.bounds, config.logDropOff))
                observedDropObstacles.put(grounded.id, placed);
            setPhase("hauling log " + (logsDone + 1));
            gui.msg("Clear-Cut: carried log released; resuming.", GameUI.MsgType.GOOD);
        }

        private List<ExactPlacementPlanner.Shape> dropObstacles(Gob carried) {
            List<ExactPlacementPlanner.Shape> out = new ArrayList<>();
            Set<Long> live = new HashSet<>();
            Gob player = gui.map.player();
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(!validGob(gob) || (carried != null && gob.id == carried.id) || gob == player ||
                       gob.getattr(Following.class) != null) continue;
                    ExactPlacementPlanner.Shape shape = worldShape(gob);
                    if(shape == null || shape.bounds == null) continue;
                    if(boundsTouchArea(shape.bounds, config.logDropOff)) {
                        live.add(gob.id);
                        observedDropObstacles.put(gob.id, shape);
                        out.add(shape);
                    }
                }
            }
            for(Map.Entry<Long, ExactPlacementPlanner.Shape> entry : observedDropObstacles.entrySet()) {
                if(live.contains(entry.getKey()) || (carried != null && entry.getKey() == carried.id)) continue;
                out.add(entry.getValue());
            }
            return out;
        }

        private String groundDropProblem(Gob log) {
            if(log == null || log.disposed() || log.rc == null) return "missing grounded object";
            ExactPlacementPlanner.Shape placed = worldShape(log);
            if(placed == null) return "unreadable grounded footprint";
            double tolerance = PlacementGeometry.SERVER_POSITION_TOLERANCE;
            if(!placed.inside(config.logDropOff, MCache.tilesz, 0.0, tolerance))
                return "outside selected area";
            for(ExactPlacementPlanner.Shape obstacle : dropObstacles(log))
                if(placed.conflicts(obstacle, -tolerance)) return "overlaps an existing object";
            return null;
        }

        private ExactPlacementPlanner.Shape relativeShape(Gob gob, double angle) {
            return PlacementGeometry.relative(gob, angle);
        }

        private ExactPlacementPlanner.Shape worldShape(Gob gob) {
            return PlacementGeometry.world(gob);
        }

        /** The server grounds a carried log at the player's coordinate. Until
         * the player steps out, normal pathfinding sees its start inside the new
         * obstacle and cannot begin the next tree/stump route. */
        private boolean stepClearOfDroppedLog(Gob log, ExactPlacementPlanner.Shape placed)
                throws InterruptedException {
            Gob player = gui.map.player();
            if(player == null || player.rc == null || placed == null) return false;
            double clearance = MovementProfile.agentRadius(player) + 0.75;
            if(PlacementEgress.clearOf(placed, player.rc, clearance)) return true;
            List<Coord2d> candidates = PlacementEgress.candidates(
                placed, player.rc, dropLayoutOrigin, clearance);
            for(Coord2d candidate : candidates) {
                bot.checkCancelled();
                if(egressPointBlocked(candidate, clearance, log.id)) continue;
                Coord2d from = Coord2d.of(player.rc.x, player.rc.y);
                diag("DROP egress log=%d from=%s target=%s clearance=%.3f",
                    log.id, from, candidate, clearance);
                gui.map.wdgmsg("click", Coord.z, candidate.div(OCache.posres).round(), 1, 0);
                long end = System.currentTimeMillis() + STEP_TIMEOUT;
                boolean moved = false;
                while(System.currentTimeMillis() < end) {
                    bot.checkCancelled();
                    player = gui.map.player();
                    if(player == null || player.rc == null) break;
                    moved |= player.rc.dist(from) > OCache.posres.x * 2.0;
                    if(moved && player.getattr(Moving.class) == null) break;
                    Thread.sleep(50L);
                }
                player = gui.map.player();
                boolean clear = player != null && player.rc != null &&
                    PlacementEgress.clearOf(placed, player.rc, clearance);
                diag("DROP egress-result log=%d player=%s moved=%s clear=%s",
                    log.id, player == null ? null : player.rc, moved, clear);
                if(clear) return true;
            }
            return false;
        }

        private boolean egressPointBlocked(Coord2d point, double clearance, long droppedLogId) {
            Gob player = gui.map.player();
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(!validGob(gob) || gob == player || gob.id == droppedLogId ||
                       gob.getattr(Following.class) != null) continue;
                    ExactPlacementPlanner.Shape obstacle = worldShape(gob);
                    if(obstacle != null && !PlacementEgress.clearOf(obstacle, point, clearance)) return true;
                }
            }
            return false;
        }

        /** Following removal and OD_MOVE are separate server deltas. Do not
         * validate or re-click the log until its new ground pose has actually
         * arrived and remained stable for several samples. */
        private Gob waitForGroundedPose(Gob log, Coord2d carriedFrom, long timeout)
                throws InterruptedException {
            if(log == null) return null;
            long id = log.id;
            long end = System.currentTimeMillis() + timeout;
            Coord2d last = null;
            double lastAngle = 0.0;
            int stable = 0;
            double movedThreshold = Math.max(0.05, Math.max(OCache.posres.x, OCache.posres.y) * 2.0);
            while(System.currentTimeMillis() < end) {
                bot.checkCancelled();
                Gob current = gui.ui.sess.glob.oc.getgob(id);
                if(current == null) current = log;
                if(current != null && !current.disposed() && !isCarried(current) && current.rc != null) {
                    boolean moved = carriedFrom == null || current.rc.dist(carriedFrom) > movedThreshold;
                    if(moved) {
                        boolean same = last != null && current.rc.dist(last) <= OCache.posres.x * 1.5 &&
                            angleDistance(current.a, lastAngle) <= 1e-5;
                        stable = same ? stable + 1 : 1;
                        last = Coord2d.of(current.rc.x, current.rc.y);
                        lastAngle = current.a;
                        if(stable >= 3) return current;
                    } else {
                        stable = 0;
                        last = null;
                    }
                } else {
                    stable = 0;
                    last = null;
                }
                Thread.sleep(75L);
            }
            return null;
        }

        private static double angleDistance(double a, double b) {
            double delta = Math.abs(a - b) % (Math.PI * 2.0);
            return Math.min(delta, Math.PI * 2.0 - delta);
        }

        private static Coord2d areaCenter(Area area) {
            return Coord2d.of((area.ul.x + area.br.x) * MCache.tilesz.x * 0.5,
                (area.ul.y + area.br.y) * MCache.tilesz.y * 0.5);
        }

        private Gob liftDroppedAgain(Gob log) throws InterruptedException {
            if(log == null || log.disposed()) return null;
            for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS; attempt++) {
                Gob current = gui.ui.sess.glob.oc.getgob(log.id);
                if(!validGob(current)) current = log;
                if(!validGob(current)) return null;
                Gob player = gui.map.player();
                boolean touching = player != null && player.rc != null &&
                    player.rc.dist(current.rc) <= MCache.tilesz.x * 1.5;
                if(!touching) {
                    BotMovement.Result result = BotMovement.approach(gui, bot, current, BotMovement.Mode.LAND);
                    if(result == null || !result.readyToInteract()) continue;
                    waitMovement();
                } else {
                    // A rejected drop leaves player and log at the same anchor.
                    // Asking for an approach pose in that state is impossible;
                    // the object is already directly interactable.
                    diag("DROP relift-direct log=%d player=%s log-pos=%s", current.id,
                        player == null ? null : player.rc, current.rc);
                }
                gui.wdgmsg("act", "carry");
                Thread.sleep(100L);
                clickGob(current, 1);
                Gob carried = waitCarriedLog(current.id, STEP_TIMEOUT);
                if(carried != null) {
                    return carried;
                }
            }
            return null;
        }

        private void depositProducts() throws InterruptedException {
            if(!productCollectionEnabled || collectedProducts.isEmpty()) return;
            try {
                observeArea(config.productDropOff, "tree-product drop-off");
            } catch(Abort abort) {
                disableProductCollection("tree-product storage could not be reached");
                return;
            }
            List<Gob> containers = containerGobs(config.productDropOff);
            if(containers.isEmpty()) {
                disableProductCollection("no tree-product container is available");
                return;
            }
            boolean opened = false;
            for(Gob container : containers) {
                if(collectedProducts.isEmpty()) break;
                if(!approachWithRetries(container)) continue;
                Window window = openContainerWindow(container);
                if(window == null) continue;
                opened = true;
                try {
                    Inventory inventory = largestInventory(window);
                    if(inventory == null) continue;
                    while(true) {
                        WItem item = firstTrackedProduct();
                        if(item == null) break;
                        if(!depositOne(item, inventory)) {
                            if(gui.hand() != null && !returnHeldProduct() && !dropHeldProduct())
                                waitForProductCursorClear();
                            break;
                        }
                        collectedProducts.remove(item.item);
                    }
                } finally {window.reqdestroy();}
            }
            purgeDisposedProducts();
            if(!collectedProducts.isEmpty())
                disableProductCollection(opened ? "all tree-product containers are full" :
                    "tree-product containers could not be opened");
        }

        private void disableProductCollection(String reason) {
            if(!productCollectionEnabled) return;
            productCollectionEnabled = false;
            diag("PRODUCT disabled reason=%s", reason);
            gui.msg("Clear-Cut warning: " + reason + "; continuing without tree products.",
                GameUI.MsgType.INFO);
        }

        private boolean depositOne(WItem item, Inventory inventory) throws InterruptedException {
            Coord size = item.lsz == null ? Coord.of(1, 1) : item.lsz;
            Coord slot = inventory.findPlaceFor(size);
            if(slot == null) return false;
            item.item.wdgmsg("take", item.sz.div(2));
            if(!waitFor(STEP_TIMEOUT, () -> gui.hand() != null)) return false;
            inventory.wdgmsg("drop", slot);
            return waitFor(STEP_TIMEOUT, () -> gui.hand() == null);
        }

        private WItem firstTrackedProduct() {
            if(gui.maininv == null) return null;
            for(WItem item : gui.maininv.children(WItem.class)) if(collectedProducts.contains(item.item)) return item;
            return null;
        }

        private void purgeDisposedProducts() {
            Set<GItem> present = inventoryItemIdentities();
            collectedProducts.removeIf(item -> item == null || item.disposed() || !present.contains(item));
        }

        private Set<GItem> inventoryItemIdentities() {
            Set<GItem> out = Collections.newSetFromMap(new IdentityHashMap<GItem, Boolean>());
            if(gui.maininv != null) for(WItem item : gui.maininv.children(WItem.class)) out.add(item.item);
            return out;
        }

        private void registerNewProducts(Set<GItem> before) {
            if(gui.maininv != null) for(WItem item : gui.maininv.children(WItem.class))
                if(!before.contains(item.item)) collectedProducts.add(item.item);
            if(gui.hand() != null && gui.hand().item != null) collectedProducts.add(gui.hand().item);
        }

        /** Top-level WItems are what a player Ctrl-clicks. A stack's children are
         * deliberately not returned: dropping a child removes one rock, while
         * dropping its wrapper removes the entire visible stack. */
        private List<WItem> rockStacksInMainInventory() {
            List<WItem> out = new ArrayList<>();
            if(gui.maininv != null) for(WItem item : gui.maininv.children(WItem.class))
                if(ClearCutSupplies.isRockMaterial(item)) out.add(item);
            return out;
        }

        /** Send the exact GItem "drop" message produced by Ctrl-left-clicking
         * each visible rock item. No trailing wait is performed when no rock is
         * present; the chipping watcher calls this repeatedly every 25 ms. */
        private int ctrlDropRockStacks() throws InterruptedException, Abort {
            int dropped = 0;
            while(dropped < 600) {
                bot.checkCancelled();
                List<WItem> stacks = rockStacksInMainInventory();
                if(stacks.isEmpty()) return dropped;
                WItem rock = stacks.get(0);
                GItem item = rock.item;
                diag("ROCK-DROP ctrl-click item=%s", item.resname());
                item.wdgmsg("drop", rock.sz.div(2));
                if(!waitFor(STEP_TIMEOUT, () -> !mainInventoryContains(item)))
                    fail("could not Ctrl-drop a chipped-rock stack from main inventory");
                dropped++;
            }
            fail("chipped-rock dropping exceeded its safety limit");
            return dropped;
        }

        private boolean mainInventoryContains(GItem wanted) {
            if(gui.maininv == null || wanted == null) return false;
            for(WItem item : gui.maininv.children(WItem.class)) if(item.item == wanted) return true;
            return false;
        }

        private static final class ChipDropResult {
            final boolean started;
            final boolean produced;
            final boolean gone;
            ChipDropResult(boolean started, boolean produced, boolean gone) {
                this.started = started;
                this.produced = produced;
                this.gone = gone;
            }
        }

        /** Watch action progress and inventory together. The old sequence waited
         * for progress to end, then waited for an item, then spent another 1.5 s
         * draining stack children. This loop sees the new top-level stack during
         * the action and Ctrl-drops it immediately. */
        private ChipDropResult waitForChipAndDropRocks(long boulderId)
                throws InterruptedException, Abort {
            long now = System.currentTimeMillis();
            long startDeadline = now + 3000L;
            long actionDeadline = now + ACTION_TIMEOUT;
            long stoppedAt = -1L;
            boolean started = false;
            boolean produced = false;
            boolean gone = false;

            while(System.currentTimeMillis() < actionDeadline) {
                bot.checkCancelled();
                boolean active = gui.prog != null;
                if(active) started = true;

                int dropped = ctrlDropRockStacks();
                if(dropped > 0) {
                    produced = true;
                    started = true;
                }

                GameUI.DraggedItem held = gui.hand();
                if(held != null && ClearCutSupplies.isRockMaterial(held.item)) {
                    Gob player = gui.map.player();
                    if(player == null || player.rc == null)
                        fail("player position is unavailable while dropping a chipped rock");
                    gui.map.wdgmsg("drop", Coord.z, player.rc.floor(OCache.posres), UI.MOD_CTRL);
                    if(!waitFor(STEP_TIMEOUT, () -> gui.hand() == null))
                        fail("could not drop a chipped rock from the cursor");
                    produced = true;
                    started = true;
                }

                gone = currentGob(boulderId, Kind.BOULDER) == null;
                if(gone) started = true;
                now = System.currentTimeMillis();
                if(!started) {
                    if(now >= startDeadline) break;
                } else if(active) {
                    stoppedAt = -1L;
                } else {
                    if(stoppedAt < 0L) stoppedAt = now;
                    // Once the stack has appeared, allow one short pass for any
                    // same-tick inventory update. If it has not appeared yet,
                    // retain a small late-update window instead of the old 3.5 s.
                    long settle = produced ? 150L : (gone ? 750L : 2000L);
                    if(now - stoppedAt >= settle) break;
                }
                Thread.sleep(25L);
            }
            return new ChipDropResult(started, produced, gone);
        }

        private boolean returnHeldProduct() throws InterruptedException {
            GameUI.DraggedItem held = gui.hand();
            if(held == null) return true;
            if(!collectedProducts.contains(held.item)) return false;
            Coord slot = gui.maininv == null ? null : gui.maininv.findPlaceFor(Coord.of(2, 1));
            if(slot == null && gui.maininv != null) slot = gui.maininv.findPlaceFor(Coord.of(1, 1));
            if(slot == null) return false;
            gui.maininv.wdgmsg("drop", slot);
            return waitFor(STEP_TIMEOUT, () -> gui.hand() == null);
        }

        private boolean dropHeldProduct() throws InterruptedException {
            GameUI.DraggedItem held = gui.hand();
            Gob player = gui.map.player();
            if(held == null) return true;
            if(!collectedProducts.contains(held.item) || player == null || player.rc == null) return false;
            GItem item = held.item;
            gui.map.wdgmsg("drop", Coord.z, player.rc.floor(OCache.posres), UI.MOD_CTRL);
            boolean dropped = waitFor(STEP_TIMEOUT, () -> gui.hand() == null);
            if(dropped) collectedProducts.remove(item);
            return dropped;
        }

        private void waitForProductCursorClear() throws InterruptedException {
            if(gui.hand() == null) return;
            disableProductCollection("a collected product is still on the cursor");
            setPhase("PAUSED — clear held tree product");
            gui.error("Clear-Cut paused: clear the held tree product to continue.");
            while(gui.hand() != null) {
                bot.checkCancelled();
                Thread.sleep(500L);
            }
            gui.msg("Clear-Cut: cursor cleared; resuming without tree products.", GameUI.MsgType.GOOD);
        }

        private Gob resolve(TargetRef ref) throws InterruptedException {
            Gob direct = currentGob(ref.id, ref.kind);
            if(direct != null) return direct;
            if(!walkNear(ref.position)) return null;
            direct = currentGob(ref.id, ref.kind);
            return direct != null ? direct : findNear(ref.kind, ref.position, MCache.tilesz.x * 1.75);
        }

        private Gob currentGob(long id, Kind kind) {
            Gob gob = gui.ui.sess.glob.oc.getgob(id);
            if(!validGob(gob)) return null;
            return kind == kindOf(gob) ? gob : null;
        }

        private Gob findNear(Kind kind, Coord2d point, double radius) {
            Gob best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(!validGob(gob) || kind != kindOf(gob)) continue;
                    double distance = gob.rc.dist(point);
                    if(distance <= radius && distance < bestDistance) {best = gob; bestDistance = distance;}
                }
            }
            return best;
        }

        private boolean walkNear(Coord2d point) throws InterruptedException {
            Gob player = gui.map.player();
            if(player != null && player.rc != null && player.rc.dist(point) <= MCache.tilesz.x * 8) return true;
            double radius = MCache.tilesz.x * 2.5;
            List<Coord2d> options = new ArrayList<>();
            for(int i = 0; i < 16; i++) {
                double angle = Math.PI * 2 * i / 16.0;
                options.add(point.add(Math.cos(angle) * radius, Math.sin(angle) * radius));
            }
            if(player != null && player.rc != null) options.sort(Comparator.comparingDouble(player.rc::dist));
            for(Coord2d option : options) if(walkWithRetries(option, MCache.tilesz.x)) return true;
            return false;
        }

        private void observeArea(Area area, String name) throws InterruptedException, Abort {
            Gob player = gui.map.player();
            if(player == null || player.rc == null) fail("player position unavailable while observing " + name);
            if(distanceToArea(player.rc, area) <= MCache.tilesz.x * 3.0) return;
            List<Coord2d> points = areaObservationPoints(area);
            points.sort(Comparator.comparingDouble(player.rc::dist));
            for(Coord2d point : points) {
                if(walkWithRetries(point, MCache.tilesz.x * 1.25)) {waitMovement(); Thread.sleep(250L); return;}
            }
            fail("could not reach " + name);
        }

        private List<Coord2d> areaObservationPoints(Area area) {
            List<Coord2d> out = new ArrayList<>();
            int cx = (area.ul.x + area.br.x - 1) / 2;
            int cy = (area.ul.y + area.br.y - 1) / 2;
            out.add(tileCenter(Coord.of(cx, cy)));
            out.add(tileCenter(area.ul));
            out.add(tileCenter(Coord.of(area.br.x - 1, area.ul.y)));
            out.add(tileCenter(Coord.of(area.ul.x, area.br.y - 1)));
            out.add(tileCenter(area.br.sub(1, 1)));
            return out;
        }

        private List<Coord2d> surveyObservationPoints(Coord tile, Area area) {
            List<Coord2d> out = new ArrayList<>();
            for(Coord candidate : ClearCutRules.surveyCandidateTiles(tile, area.margin(2), 2))
                out.add(tileCenter(candidate));
            return out;
        }

        private boolean walkWithRetries(Coord2d point, double tolerance) throws InterruptedException {
            for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS; attempt++)
                if(plannedWalkTo(point, 20000L, tolerance)) return true;
            return false;
        }

        private boolean plannedWalkTo(Coord2d point, long timeout, double tolerance) throws InterruptedException {
            Gob player = gui.map.player();
            if(player == null || player.rc == null || point == null) return false;
            if(player.rc.dist(point) <= tolerance) return true;
            BotMovement.Result result = BotMovement.moveTo(gui, bot, point, BotMovement.Mode.LAND, timeout);
            player = gui.map.player();
            return result.arrived() && player != null && player.rc != null && player.rc.dist(point) <= tolerance;
        }

        private boolean plannedWalkToAny(List<Coord2d> points, long timeout, double tolerance)
                throws InterruptedException {
            Gob player = gui.map.player();
            if(player == null || player.rc == null || points == null || points.isEmpty()) return false;
            for(Coord2d point : points) if(player.rc.dist(point) <= tolerance) return true;
            BotMovement.Result result = BotMovement.moveToAny(gui, bot, points,
                BotMovement.Avoidance.NONE, BotMovement.Mode.LAND, timeout);
            player = gui.map.player();
            return result.arrived() && result.selectedGoal != null && player != null && player.rc != null &&
                player.rc.dist(result.selectedGoal) <= tolerance;
        }

        private boolean approachWithRetries(Gob gob) throws InterruptedException {
            for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS; attempt++) {
                if(!validGob(gob)) return false;
                Gob player = gui.map.player();
                if(player != null && player.rc != null && player.rc.dist(gob.rc) <= MCache.tilesz.x * 1.5) {
                    waitMovement(); return true;
                }
                BotMovement.Result result = BotMovement.approach(gui, bot, gob, BotMovement.Mode.LAND);
                diag("APPROACH target=%d resid=%s attempt=%d status=%s detail=%s", gob.id,
                    resid(gob), attempt, result == null ? null : result.status,
                    result == null ? "null result" : result.detail);
                if(result != null && result.readyToInteract()) {waitMovement(); return true;}
            }
            return false;
        }

        /** Some bushes expose no useful movement hitbox, so use the shared
         * geometry-free object fallback when normal approach cannot plan. */
        private boolean approachBushWithRetries(Gob bush) throws InterruptedException {
            return approachWithRingFallback(bush, Kind.BUSH, BUSH_APPROACH_RADIUS, "BUSH");
        }

        /** Every boulder/bumling uses the same rule, regardless of material or
         * stage number. Some small stages have no usable movement geometry. */
        private boolean approachBoulderWithRetries(Gob boulder) throws InterruptedException {
            return approachWithRingFallback(boulder, Kind.BOULDER, BOULDER_APPROACH_RADIUS, "BOULDER");
        }

        /** Try exact object approach first, then route to the nearest open
         * point on a conservative ring when the target has no usable geometry. */
        private boolean approachWithRingFallback(Gob target, Kind kind, double radius, String label)
                throws InterruptedException {
            for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS; attempt++) {
                if(!validGob(target)) return false;
                Gob player = gui.map.player();
                if(player != null && player.rc != null && player.rc.dist(target.rc) <= MCache.tilesz.x * 1.5) {
                    waitMovement();
                    return true;
                }

                BotMovement.Result normal = BotMovement.approach(gui, bot, target, BotMovement.Mode.LAND);
                diag("%s approach target=%d resid=%s attempt=%d normal=%s detail=%s", label, target.id,
                    resid(target), attempt, normal == null ? null : normal.status,
                    normal == null ? "null result" : normal.detail);
                if(normal != null && normal.readyToInteract()) {
                    waitMovement();
                    return true;
                }
                if(normal != null && normal.status == BotMovement.Status.TARGET_GONE) return false;

                List<Coord2d> candidates = new ArrayList<>();
                for(int i = 0; i < 24; i++) {
                    double angle = Math.PI * 2.0 * i / 24.0;
                    candidates.add(target.rc.add(Math.cos(angle) * radius,
                        Math.sin(angle) * radius));
                }
                if(player != null && player.rc != null)
                    candidates.sort(Comparator.comparingDouble(player.rc::dist));
                BotMovement.Result ring = BotMovement.moveToAny(gui, bot, candidates,
                    BotMovement.Avoidance.NONE, BotMovement.Mode.LAND);
                diag("%s approach target=%d attempt=%d ring=%s detail=%s goal=%s", label, target.id,
                    attempt, ring == null ? null : ring.status,
                    ring == null ? "null result" : ring.detail,
                    ring == null ? null : ring.selectedGoal);
                waitMovement();
                player = gui.map.player();
                Gob current = currentGob(target.id, kind);
                if(ring != null && ring.arrived() && player != null && player.rc != null &&
                   current != null && player.rc.dist(current.rc) <= MCache.tilesz.x * 1.75)
                    return true;
                target = current;
            }
            return false;
        }

        /** Cart loading needs an actually validated interaction pose. The
         * generic close-distance shortcut is unsafe around packed storage: the
         * player can be nearby but separated from the cart by another object. */
        private boolean approachCartWithRetries(Gob cart) throws InterruptedException {
            for(int attempt = 1; attempt <= ClearCutConfig.MAX_ATTEMPTS; attempt++) {
                if(!validGob(cart)) return false;
                BotMovement.Result result = BotMovement.approach(gui, bot, cart, BotMovement.Mode.LAND);
                if(result != null && result.readyToInteract()) {
                    waitMovement();
                    return true;
                }
            }
            return false;
        }

        private void waitMovement() throws InterruptedException {
            Gob player = gui.map.player();
            long end = System.currentTimeMillis() + 3000L;
            while(player != null && player.getattr(Moving.class) != null && System.currentTimeMillis() < end) {
                bot.checkCancelled(); Thread.sleep(50L);
            }
        }

        private FlowerMenu openMenu(Gob gob) throws InterruptedException {
            return openMenu(gob, ClearCutConfig.MAX_ATTEMPTS, STEP_TIMEOUT);
        }

        private FlowerMenu openProductMenu(Gob gob) throws InterruptedException {
            return openMenu(gob, ClearCutConfig.PRODUCT_MENU_ATTEMPTS,
                ClearCutConfig.PRODUCT_MENU_TIMEOUT_MS);
        }

        private FlowerMenu openMenu(Gob gob, int attempts, long timeout) throws InterruptedException {
            for(int attempt = 1; attempt <= attempts; attempt++) {
                Set<Widget> before = widgets(gui.ui.root);
                FlowerMenu.lastGob(gob);
                clickGob(gob, 3);
                final FlowerMenu[] found = new FlowerMenu[1];
                if(waitFor(timeout, () -> {
                    found[0] = newWidget(gui.ui.root, FlowerMenu.class, before);
                    return found[0] != null;
                })) return found[0];
            }
            return null;
        }

        private FlowerMenu.Petal petal(FlowerMenu menu, String name) {
            if(menu.opts != null) for(FlowerMenu.Petal petal : menu.opts) if(name.equals(petal.name)) return petal;
            return null;
        }

        private void clickGob(Gob gob, int button) {
            Coord mc = gob.rc.floor(OCache.posres);
            gui.map.wdgmsg("click", Coord.z, mc, button, 0, 0, (int)gob.id, mc, 0, -1);
        }

        private void cancelMapAction() {
            try {
                Gob player = gui.map.player();
                if(player != null) gui.map.wdgmsg("click", Coord.z, player.rc.floor(OCache.posres), 3, 0);
            } catch(Exception ignored) {}
        }

        private MenuGrid.Pagina destroyAction() {
            synchronized(gui.menu.paginae) {
                for(MenuGrid.Pagina pagina : gui.menu.paginae) {
                    try {if("Destroy".equals(pagina.button().name())) return pagina;} catch(Loading ignored) {}
                }
            }
            return null;
        }

        private Window openContainerWindow(Gob gob) throws InterruptedException {
            Set<Widget> before = widgets(gui);
            clickGob(gob, 3);
            final Window[] found = new Window[1];
            waitFor(STEP_TIMEOUT, () -> {
                found[0] = newWidget(gui, Window.class, before);
                return found[0] != null;
            });
            return found[0];
        }

        private Inventory largestInventory(Widget root) {
            Inventory best = null;
            for(Widget child = root.lchild; child != null; child = child.prev) {
                Inventory inv = ExtInventory.inventory(child);
                if(inv != null && (best == null || inv.size() > best.size())) best = inv;
                Inventory nested = largestInventory(child);
                if(nested != null && (best == null || nested.size() > best.size())) best = nested;
            }
            return best;
        }

        private List<Gob> containerGobs(Area area) {
            List<Gob> out = gobsIn(area, this::isContainer);
            sortGobs(out);
            return out;
        }

        private boolean isContainer(Gob gob) {
            try {
                return gob.is(GobTag.CONTAINER) || ClearCutSupplies.isInventoryBasketResid(gob.resid());
            } catch(Exception e) {
                return false;
            }
        }

        private interface GobFilter {boolean accept(Gob gob);}
        private List<Gob> gobsIn(Area area, GobFilter filter) {
            List<Gob> out = new ArrayList<>();
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) if(validGob(gob) && area.contains(gob.rc.floor(MCache.tilesz)) && filter.accept(gob)) out.add(gob);
            }
            return out;
        }

        private void sortGobs(List<Gob> gobs) {
            Gob player = gui.map.player();
            gobs.sort(Comparator.<Gob>comparingDouble(g -> player == null ? 0 : player.rc.dist(g.rc)).thenComparingLong(g -> g.id));
        }

        private void sortByPlayer(List<TargetRef> refs) {
            Gob player = gui.map.player();
            refs.sort(Comparator.<TargetRef>comparingDouble(r -> player == null ? 0 : player.rc.dist(r.position)).thenComparingLong(r -> r.id));
        }

        private int stableCartState(Gob cart) throws InterruptedException {
            int state = cartModelState(cart);
            if(state >= 0) return state;
            waitFor(STEP_TIMEOUT, () -> cartModelState(cart) >= 0);
            return cartModelState(cart);
        }

        private Gob resolveGob(GobRef ref) throws InterruptedException {
            Gob gob = gui.ui.sess.glob.oc.getgob(ref.id);
            if(validGob(gob)) return gob;
            if(!walkNear(ref.position)) return null;
            gob = gui.ui.sess.glob.oc.getgob(ref.id);
            return validGob(gob) ? gob : null;
        }

        private int cartModelState(Gob cart) {
            try {
                Drawable drawable = cart.getattr(Drawable.class);
                return drawable instanceof ResDrawable ? ((ResDrawable)drawable).sdtnum() : -1;
            } catch(Exception e) {return -1;}
        }

        private Gob waitCarriedLog(long preferredId, long timeout) throws InterruptedException {
            final Gob[] found = new Gob[1];
            waitFor(timeout, () -> {
                Gob preferred = gui.ui.sess.glob.oc.getgob(preferredId);
                if(preferred != null && kindOf(preferred) == Kind.LOG && isCarried(preferred)) {found[0] = preferred; return true;}
                Gob player = gui.map.player();
                if(player == null) return false;
                synchronized(gui.ui.sess.glob.oc) {
                    for(Gob gob : gui.ui.sess.glob.oc) if(validGob(gob) && kindOf(gob) == Kind.LOG && isCarried(gob)) {
                        found[0] = gob; return true;
                    }
                }
                return false;
            });
            return found[0];
        }

        private boolean isCarried(Gob gob) {
            if(gob == null || gob.disposed()) return false;
            Gob player = gui.map.player();
            Following following = gob.getattr(Following.class);
            return player != null && following != null && following.tgt == player.id;
        }

        private boolean waitForProductProgressToFinish() throws InterruptedException {
            if(!waitFor(1200L, () -> gui.prog != null)) return false;
            boolean completed = waitFor(ClearCutConfig.PRODUCT_ACTION_TIMEOUT_MS,
                () -> gui.prog == null);
            if(!completed) {
                cancelMapAction();
                waitFor(3000L, () -> gui.prog == null);
            }
            return completed;
        }

        private boolean boundsTouchArea(WorldFootprint.Bounds b, Area area) {
            double minX = area.ul.x * MCache.tilesz.x, minY = area.ul.y * MCache.tilesz.y;
            double maxX = area.br.x * MCache.tilesz.x, maxY = area.br.y * MCache.tilesz.y;
            return b.maxX > minX && b.maxY > minY && b.minX < maxX && b.minY < maxY;
        }

        private boolean boundsTouchArea(ExactPlacementPlanner.Rect b, Area area) {
            double minX = area.ul.x * MCache.tilesz.x, minY = area.ul.y * MCache.tilesz.y;
            double maxX = area.br.x * MCache.tilesz.x, maxY = area.br.y * MCache.tilesz.y;
            return b.maxX > minX && b.maxY > minY && b.minX < maxX && b.minY < maxY;
        }

        private Coord2d tileCenter(Coord tile) {
            return Coord2d.of(tile).mul(MCache.tilesz).add(MCache.tilesz.x * 0.5, MCache.tilesz.y * 0.5);
        }

        private double distanceToArea(Coord2d point, Area area) {
            double minX = area.ul.x * MCache.tilesz.x, minY = area.ul.y * MCache.tilesz.y;
            double maxX = area.br.x * MCache.tilesz.x, maxY = area.br.y * MCache.tilesz.y;
            double dx = Math.max(minX - point.x, Math.max(0, point.x - maxX));
            double dy = Math.max(minY - point.y, Math.max(0, point.y - maxY));
            return Math.hypot(dx, dy);
        }

        private static Kind kindOf(Gob gob) {
            if(gob == null) return null;
            try {
                if(gob.is(GobTag.LOG)) return Kind.LOG;
                if(gob.is(GobTag.STUMP)) return Kind.STUMP;
                if(gob.is(GobTag.BUSH)) return Kind.BUSH;
                if(gob.is(GobTag.TREE)) return Kind.TREE;
            } catch(RuntimeException ignored) {}
            return kindOf(resid(gob));
        }

        private static Kind kindOf(String resource) {
            if(ClearCutRules.isLog(resource)) return Kind.LOG;
            if(ClearCutRules.isStump(resource)) return Kind.STUMP;
            if(ClearCutRules.isBush(resource)) return Kind.BUSH;
            if(ClearCutRules.isBoulder(resource)) return Kind.BOULDER;
            if(ClearCutRules.isTree(resource)) return Kind.TREE;
            return null;
        }

        private static boolean validGob(Gob gob) {
            return gob != null && gob.rc != null && !gob.disposed() && !gob.virtual && gob.id >= 0;
        }

        private static String resid(Gob gob) {
            try {return gob == null ? null : gob.resid();} catch(Exception e) {return null;}
        }

        private Set<Widget> widgets(Widget root) {
            Set<Widget> out = new HashSet<>();
            if(root == null) return out;
            for(Widget child = root.lchild; child != null; child = child.prev) {
                out.add(child); out.addAll(widgets(child));
            }
            return out;
        }

        private <T extends Widget> T newWidget(Widget root, Class<T> type, Set<Widget> before) {
            if(root == null) return null;
            for(Widget child = root.lchild; child != null; child = child.prev) {
                if(type.isInstance(child) && !before.contains(child)) return type.cast(child);
                T nested = newWidget(child, type, before);
                if(nested != null) return nested;
            }
            return null;
        }

        private interface Check {boolean get();}
        private boolean waitFor(long timeout, Check check) throws InterruptedException {
            long end = System.currentTimeMillis() + timeout;
            while(System.currentTimeMillis() < end) {
                bot.checkCancelled();
                if(check.get()) return true;
                Thread.sleep(75L);
            }
            return check.get();
        }

        private void setPhase(String phase) {
            this.phase = phase;
            setStatus(phase);
        }

        private void done(String message) throws Abort {throw new Abort(message, true);}
        private void fail(String message) throws Abort {
            throw new Abort("failed during " + phase + ": " + message, false);
        }
    }
}
