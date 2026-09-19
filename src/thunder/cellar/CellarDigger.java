package thunder.cellar;

import auto.Bot;
import auto.Equip;
import auto.InvHelper;
import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.Debug;
import haven.FlowerMenu;
import haven.Following;
import haven.GItem;
import haven.GameUI;
import haven.Gob;
import haven.IMeter;
import haven.Loading;
import haven.MCache;
import haven.OCache;
import haven.UI;
import haven.WItem;
import haven.Widget;
import haven.pathfinding.BotMovement;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Excavates a cellar, grounds and chips each spawned bumling, drops its output,
 * and enters the completed cellar. */
public final class CellarDigger {
    private static final String DEBUG_LOG = "logs/cellar-digger-debug.log";
    private static final long STEP_TIMEOUT = 6000L;
    private static final long CHIP_TIMEOUT = 120000L;
    private static final long DOOR_TIMEOUT = 600000L;
    private static final long POST_ACTION_SETTLE = 2000L;
    private static final long TRANSITION_TIMEOUT = 15000L;
    private static final long STAMINA_TIMEOUT = 30000L;
    private static final long BOULDER_STATE_SETTLE = 2000L;
    private static final long BOULDER_GROUND_TIMEOUT = 9000L;
    private static final double BOULDER_APPROACH_RADIUS = MCache.tilesz.x * 1.25;
    private static final double BOULDER_DROP_DISTANCE = MCache.tilesz.x * 2.5;
    private static final double BOULDER_DIRECT_CLICK_RADIUS = MCache.tilesz.x * 5.0;
    private static final double DOOR_DIRECT_CLICK_RADIUS = MCache.tilesz.x * 5.0;
    private static final int MAX_ROCK_DROPS = 600;

    private static volatile boolean running;
    private static volatile String status = "Status: idle | boulders 0 | chips 0";
    private static volatile int bouldersRemoved;
    private static volatile int chips;
    private static Bot active;
    private static GameUI activeGui;

    private CellarDigger() {}

    public static boolean isRunning() {return running;}
    public static String status() {return status;}
    public static int bouldersRemoved() {return bouldersRemoved;}
    public static int chips() {return chips;}

    public static synchronized void start(GameUI gui) {
        if(running) {
            if(gui != null) gui.error("Cellar Digger is already running.");
            return;
        }
        if(gui == null || gui.ui == null || gui.map == null || gui.menu == null ||
           gui.maininv == null || gui.equipory == null || gui.ui.sess == null) {
            if(gui != null) gui.error("Cellar Digger: the game UI is not ready.");
            return;
        }
        if(Bot.hasCurrent()) {
            gui.error("Cellar Digger: stop the currently running bot first.");
            return;
        }

        bouldersRemoved = 0;
        chips = 0;
        running = true;
        activeGui = gui;
        setStatus("starting");
        Bot bot = Bot.execute((unused, owner) -> {
            try {
                new Run(gui, owner).execute();
            } finally {
                running = false;
                active = null;
                activeGui = null;
                try {
                    if(gui.pathQueue != null) gui.pathQueue.clear();
                } catch(Exception ignored) {}
            }
        });
        active = bot;
        bot.start(gui.ui, true);
    }

    public static synchronized void stop() {
        Bot bot = active;
        GameUI gui = activeGui;
        if(bot == null && !running) return;
        setStatus("stopping");
        if(bot != null) bot.cancel("Cellar Digger stopped by user.");
        halt(gui, true);
    }

    private static void setStatus(String phase) {
        status = "Status: " + phase + " | boulders " + bouldersRemoved + " | chips " + chips;
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

    private static void halt(GameUI gui, boolean closeMenus) {
        if(gui == null) return;
        try {
            if(gui.pathQueue != null) gui.pathQueue.clear();
        } catch(Exception ignored) {}
        try {
            if(gui.map != null) gui.map.cancelPlacement();
        } catch(Exception ignored) {}
        if(closeMenus) {
            try {
                for(FlowerMenu menu : widgetsOfType(gui.ui.root, FlowerMenu.class)) menu.choose(null);
            } catch(Exception ignored) {}
        }
        try {
            Gob player = gui.map.player();
            if(player != null && player.rc != null)
                gui.map.wdgmsg("click", Coord.z, player.rc.floor(OCache.posres), 3, 0);
        } catch(Exception ignored) {}
    }

    private static <T extends Widget> List<T> widgetsOfType(Widget root, Class<T> type) {
        List<T> out = new ArrayList<>();
        if(root == null) return out;
        for(Widget child = root.lchild; child != null; child = child.prev) {
            if(type.isInstance(child)) out.add(type.cast(child));
            out.addAll(widgetsOfType(child, type));
        }
        return out;
    }

    private static final class Abort extends Exception {
        final boolean success;
        Abort(String message, boolean success) {
            super(message);
            this.success = success;
        }
    }

    private enum ScanState {AVAILABLE, EMPTY, LOADING}

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

    private static final class Run {
        private final GameUI gui;
        private final Bot bot;
        private String phase = "startup";
        private int doorCycles;
        private Coord2d pendingDoorPosition;
        private Coord2d pendingDoorApproach;
        private double pendingDoorFacing;

        Run(GameUI gui, Bot bot) {
            this.gui = gui;
            this.bot = bot;
        }

        void execute() {
            diag("START autoDrink=%s threshold=%s", CFG.AUTO_DRINK_ENABLED.get(),
                CFG.AUTO_DRINK_THRESHOLD.get());
            try {
                runLoop();
            } catch(InterruptedException e) {
                setPhase("stopped by user");
                diag("STOP reason=user");
                halt(gui, false);
            } catch(Abort abort) {
                setPhase(abort.getMessage());
                diag("STOP success=%s reason=%s", abort.success, abort.getMessage());
                if(abort.success) {
                    gui.msg("Cellar Digger: " + abort.getMessage() + ".", GameUI.MsgType.GOOD);
                } else {
                    halt(gui, false);
                    gui.error("Cellar Digger: " + abort.getMessage() + ".");
                }
            } catch(Throwable t) {
                setPhase("error: " + t.getClass().getSimpleName());
                diag("STOP success=false error=%s message=%s", t.getClass().getName(), t.getMessage());
                halt(gui, false);
                gui.error("Cellar Digger failed: " + t.getClass().getSimpleName() +
                    (t.getMessage() == null ? "" : " - " + t.getMessage()));
                Debug.log.println("[cellar-digger] " + t);
                t.printStackTrace(Debug.log);
            }
        }

        private void runLoop() throws InterruptedException, Abort {
            Gob door = preflight();
            while(true) {
                checkSafety();

                Gob bumling = nearestBumling();
                if(bumling != null) {
                    handleBumling(bumling);
                    continue;
                }

                setPhase("confirming excavation area is clear");
                Thread.sleep(250L);
                bot.checkCancelled();
                bumling = nearestBumling();
                if(bumling != null) {
                    handleBumling(bumling);
                    continue;
                }

                door = nearestCellarDoor();
                if(door == null) {
                    if(cellarTransitionVisible()) done("complete - entered cellar");
                    fail("cellar door disappeared without a visible cellar transition");
                }
                excavateOrEnter(door);
            }
        }

        private Gob preflight() throws InterruptedException, Abort {
            setPhase("preflight");
            Gob player = gui.map.player();
            if(!validGob(player)) fail("player position is unavailable");
            if(gui.getIMeter("nrj") == null) fail("energy meter is unavailable");
            if(gui.getIMeter("stam") == null) fail("stamina meter is unavailable");
            if(inCombat()) fail("cannot start while in combat");
            if(gui.hand() != null) fail("clear the cursor before starting");
            if(gui.map.isPlacing()) fail("cancel the active placement cursor before starting");
            if(!CFG.AUTO_DRINK_ENABLED.get()) fail("global auto-drink must be enabled");
            if(!CellarDiggerRules.autoDrinkThresholdSafe(CFG.AUTO_DRINK_THRESHOLD.get()))
                fail("global auto-drink threshold must be at least 40%");

            ScanState water = settledWaterState();
            if(water == ScanState.LOADING) fail("carried water could not be inspected");
            if(water != ScanState.AVAILABLE)
                fail("carry a waterskin, flask, jug, kuksa, or held bucket containing water");

            ScanState rocks = settledRockState();
            if(rocks == ScanState.LOADING) fail("main-inventory contents could not be inspected");
            if(rocks == ScanState.AVAILABLE)
                fail("remove all stone and ore from the main inventory before starting");

            if(!Equip.hasInHandsOrBelt(gui, Equip.PICKAXE))
                fail("no pickaxe found in hands or belt");
            if(!Equip.canEnsureTwoHanded(gui, Equip.PICKAXE))
                fail("the pickaxe cannot be equipped two-handed; clear a hand or belt slot");

            checkSafety();
            Gob door = nearestCellarDoor();
            if(door == null) fail("no cellar door is visible inside the building");
            diag("PREFLIGHT door=%d resid=%s pos=%s energy=%.4f stamina=%.4f", door.id,
                resid(door), door.rc, meter("nrj"), meter("stam"));
            return door;
        }

        private ScanState settledWaterState() throws InterruptedException {
            final ScanState[] state = {waterState()};
            if(state[0] != ScanState.LOADING) return state[0];
            waitFor(3000L, () -> {
                state[0] = waterState();
                return state[0] != ScanState.LOADING;
            });
            return state[0];
        }

        /** A cellar excavation first creates a bumling attached to the player.
         * The resource can enter the object cache slightly before its Following
         * attribute, so do not mistake that short interval for a grounded rock. */
        private void handleBumling(Gob bumling) throws InterruptedException, Abort {
            bumling = settledBumling(bumling.id);
            if(bumling == null) return;
            if(isCarried(bumling)) {
                placeCarriedBoulder(bumling);
                bumling = currentBumling(bumling.id);
                if(bumling != null) chipBoulder(bumling);
                return;
            }
            chipBoulder(bumling);
        }

        private Gob settledBumling(long id) throws InterruptedException, Abort {
            long end = System.currentTimeMillis() + BOULDER_STATE_SETTLE;
            while(System.currentTimeMillis() < end) {
                checkSafety();
                Gob current = currentBumling(id);
                if(current == null || isCarried(current)) return current;
                Thread.sleep(75L);
            }
            return currentBumling(id);
        }

        /** Release the overhead bumling with the exact four-argument ground
         * right-click seen in the successful manual protocol recording. The
         * server performs its own short carrying walk and grounds the object;
         * cellar bumlings do not use the placement-ghost/place-message flow. */
        private void placeCarriedBoulder(Gob bumling) throws InterruptedException, Abort {
            long id = bumling.id;
            ensureBoulderDropPlan();
            for(int attempt = 1; attempt <= CellarDiggerRules.MAX_ATTEMPTS; attempt++) {
                checkSafety();
                Gob current = currentBumling(id);
                if(current == null) fail("carried boulder " + id + " disappeared before placement");
                if(!isCarried(current)) {
                    Gob grounded = waitForGroundedBumling(id, 750L);
                    if(grounded != null) return;
                    fail("boulder " + id + " was released without a stable ground position");
                }

                Gob player = gui.map.player();
                if(player == null || player.rc == null)
                    fail("player position is unavailable while placing boulder " + id);
                Coord2d target = CellarDiggerRules.boulderDropTarget(
                    pendingDoorPosition, pendingDoorApproach, pendingDoorFacing,
                    BOULDER_DROP_DISTANCE, attempt);
                if(target == null) fail("could not choose a safe drop point for boulder " + id);
                setPhase("placing boulder " + (bouldersRemoved + 1));
                diag("BOULDER-PLACE begin id=%d resid=%s attempt=%d carried-pos=%s target=%s player=%s",
                    id, resid(current), attempt, current.rc, target, player.rc);

                Coord mc = target.floor(OCache.posres);
                gui.map.wdgmsg("click", Coord.z, mc, 3, 0);
                diag("BOULDER-PLACE ground-click id=%d attempt=%d mc=%s args=4 target=%s",
                    id, attempt, mc, target);

                Gob grounded = waitForGroundedBumling(id, BOULDER_GROUND_TIMEOUT);
                if(grounded != null) {
                    diag("BOULDER-PLACE grounded id=%d resid=%s attempt=%d pos=%s angle=%.6f",
                        id, resid(grounded), attempt, grounded.rc, grounded.a);
                    setPhase("placed boulder " + (bouldersRemoved + 1));
                    return;
                }

                current = currentBumling(id);
                diag("BOULDER-PLACE retry id=%d attempt=%d exists=%s carried=%s placing=%s",
                    id, attempt, current != null, isCarried(current), gui.map.isPlacing());
                if(current == null) fail("boulder " + id + " disappeared during placement");
                if(!isCarried(current))
                    fail("boulder " + id + " was released without a stable ground position");
            }
            fail("could not place carried boulder " + id + " after three attempts");
        }

        private void ensureBoulderDropPlan() throws Abort {
            if(pendingDoorPosition != null) return;
            Gob door = nearestCellarDoor();
            Gob player = gui.map.player();
            if(door == null || player == null || player.rc == null)
                fail("cellar door or player position is unavailable while planning boulder placement");
            rememberBoulderDropPlan(door, player);
        }

        private void rememberBoulderDropPlan(Gob door, Gob player) {
            pendingDoorPosition = Coord2d.of(door.rc.x, door.rc.y);
            pendingDoorApproach = Coord2d.of(player.rc.x, player.rc.y);
            pendingDoorFacing = player.a;
            Coord2d target = CellarDiggerRules.boulderDropTarget(
                pendingDoorPosition, pendingDoorApproach, pendingDoorFacing,
                BOULDER_DROP_DISTANCE, 1);
            diag("BOULDER-PLACE plan door=%d door-pos=%s approach=%s facing=%.6f target=%s distance=%.3f",
                door.id, pendingDoorPosition, pendingDoorApproach, pendingDoorFacing,
                target, BOULDER_DROP_DISTANCE);
        }

        private Gob waitForGroundedBumling(long id, long timeout)
                throws InterruptedException, Abort {
            long end = System.currentTimeMillis() + timeout;
            Coord2d last = null;
            int stable = 0;
            while(System.currentTimeMillis() < end) {
                checkSafety();
                Gob current = currentBumling(id);
                if(current != null && !isCarried(current) && current.rc != null) {
                    boolean same = last != null && current.rc.dist(last) <= OCache.posres.x * 1.5;
                    stable = same ? stable + 1 : 1;
                    last = Coord2d.of(current.rc.x, current.rc.y);
                    if(CellarDiggerRules.groundedBoulderStable(stable)) return current;
                } else {
                    stable = 0;
                    last = null;
                }
                Thread.sleep(75L);
            }
            return null;
        }

        private ScanState waterState() {
            try {
                List<WItem> vessels = new ArrayList<>();
                for(WItem item : InvHelper.HANDS(gui).get())
                    if(InvHelper.isDrinkContainer(item) || InvHelper.isBucket(item)) vessels.add(item);
                for(WItem item : InvHelper.POUCHES(gui).get())
                    if(InvHelper.isDrinkContainer(item)) vessels.add(item);
                for(WItem item : InvHelper.INVENTORY(gui).get())
                    if(InvHelper.isDrinkContainer(item)) vessels.add(item);
                for(WItem item : InvHelper.BELT(gui).get())
                    if(InvHelper.isDrinkContainer(item)) vessels.add(item);
                for(WItem item : vessels) if(InvHelper.HAS_WATER.test(item)) return ScanState.AVAILABLE;
                return ScanState.EMPTY;
            } catch(Loading loading) {
                return ScanState.LOADING;
            }
        }

        private ScanState settledRockState() throws InterruptedException {
            final ScanState[] state = {rockState()};
            if(state[0] != ScanState.LOADING) return state[0];
            waitFor(3000L, () -> {
                state[0] = rockState();
                return state[0] != ScanState.LOADING;
            });
            return state[0];
        }

        private ScanState rockState() {
            if(gui.maininv == null) return ScanState.LOADING;
            for(WItem item : gui.maininv.children(WItem.class)) {
                try {
                    if(item == null || item.item == null) continue;
                    item.item.info();
                    if(CellarDiggerMaterials.isRockMaterial(item)) return ScanState.AVAILABLE;
                } catch(Loading loading) {
                    return ScanState.LOADING;
                }
            }
            return ScanState.EMPTY;
        }

        private void checkSafety() throws InterruptedException, Abort {
            bot.checkCancelled();
            if(inCombat()) fail("combat started");

            double energy = meter("nrj");
            if(energy < 0.0) fail("energy meter became unavailable");
            if(CellarDiggerRules.energyTooLow(energy)) fail("energy fell below 25%");

            ScanState water = waterState();
            if(water == ScanState.EMPTY) fail("carried water ran out");
            if(water == ScanState.LOADING) {
                water = settledWaterState();
                if(water != ScanState.AVAILABLE) fail("carried water became unavailable");
            }

            double stamina = meter("stam");
            if(stamina < 0.0) fail("stamina meter became unavailable");
            if(CellarDiggerRules.staminaNeedsRecovery(stamina)) waitForStaminaRecovery();
        }

        private void waitForStaminaRecovery() throws InterruptedException, Abort {
            long deadline = System.currentTimeMillis() + STAMINA_TIMEOUT;
            long shownPercent = Long.MIN_VALUE;
            diag("DRINK wait start stamina=%.4f", meter("stam"));
            while(System.currentTimeMillis() < deadline) {
                bot.checkCancelled();
                if(inCombat()) fail("combat started while waiting for auto-drink");
                double energy = meter("nrj");
                if(energy < 0.0) fail("energy meter became unavailable while waiting for auto-drink");
                if(CellarDiggerRules.energyTooLow(energy)) fail("energy fell below 25%");
                ScanState water = waterState();
                if(water == ScanState.EMPTY) fail("carried water ran out while waiting for auto-drink");
                double stamina = meter("stam");
                if(stamina < 0.0) fail("stamina meter became unavailable while waiting for auto-drink");
                long percent = Math.round(stamina * 100.0);
                if(percent != shownPercent) {
                    shownPercent = percent;
                    setPhase("waiting for auto-drink (" + percent + "%)");
                }
                if(CellarDiggerRules.staminaRecovered(stamina)) {
                    diag("DRINK recovered stamina=%.4f", stamina);
                    return;
                }
                Thread.sleep(100L);
            }
            fail("stamina did not recover to 80% within 30 seconds");
        }

        private void chipBoulder(Gob bumling) throws InterruptedException, Abort {
            long id = bumling.id;
            int completedChips = 0;
            int failedActions = 0;
            diag("BOULDER begin id=%d resid=%s pos=%s", id, resid(bumling), bumling.rc);

            while(completedChips < CellarDiggerRules.MAX_CHIPS_PER_BOULDER) {
                checkSafety();
                bumling = currentBumling(id);
                if(bumling == null) {
                    bouldersRemoved++;
                    setPhase("removed boulder " + bouldersRemoved);
                    diag("BOULDER complete id=%d chips=%d", id, completedChips);
                    return;
                }
                if(isCarried(bumling)) {
                    diag("BOULDER chip deferred id=%d reason=carried", id);
                    placeCarriedBoulder(bumling);
                    return;
                }
                if(!Equip.ensureTwoHanded(gui, bot, Equip.PICKAXE))
                    fail("could not equip the pickaxe for boulder " + id);
                bumling = currentBumling(id);
                if(bumling == null) continue;
                if(!approachBumling(bumling)) {
                    if(currentBumling(id) == null) continue;
                    fail("could not reach boulder " + id);
                }

                setPhase("chipping boulder " + (bouldersRemoved + 1));
                ctrlDropRockStacks();
                bumling = currentBumling(id);
                if(bumling == null) continue;
                FlowerMenu menu = openMenu(bumling);
                if(menu == null) {
                    if(currentBumling(id) == null) continue;
                    fail("Chip stone menu did not appear for boulder " + id);
                }
                int chip = optionIndex(menu, "Chip stone");
                if(chip < 0) {
                    diag("CHIP id=%d options=%s missing=Chip stone", id, menuOptions(menu));
                    menu.wdgmsg("cl", -1, 0);
                    if(currentBumling(id) == null) continue;
                    fail("Chip stone action is unavailable for boulder " + id);
                }
                diag("CHIP protocol id=%d widget-option=%d option=Chip stone mods=0 action=%d",
                    id, chip, completedChips + 1);
                menu.wdgmsg("cl", chip, 0);
                ChipDropResult result = waitForChipAndDropRocks(id);
                diag("CHIP result id=%d started=%s produced=%s gone=%s", id,
                    result.started, result.produced, result.gone);
                if(!result.produced && !result.gone) {
                    failedActions++;
                    if(failedActions >= CellarDiggerRules.MAX_ATTEMPTS)
                        fail("boulder " + id + " did not produce a rock after three attempts");
                    continue;
                }
                failedActions = 0;
                completedChips++;
                chips++;
                setPhase("chipped " + chips + " stone" + (chips == 1 ? "" : "s"));
            }
            if(currentBumling(id) == null) {
                bouldersRemoved++;
                setPhase("removed boulder " + bouldersRemoved);
                diag("BOULDER complete id=%d chips=%d at-limit=true", id, completedChips);
                return;
            }
            fail("boulder " + id + " exceeded the 500-chip safety limit");
        }

        private void excavateOrEnter(Gob door) throws InterruptedException, Abort {
            for(int attempt = 1; attempt <= CellarDiggerRules.MAX_ATTEMPTS; attempt++) {
                checkSafety();
                door = currentCellarDoor(door.id);
                if(door == null) door = nearestCellarDoor();
                if(door == null) {
                    if(cellarTransitionVisible()) done("complete - entered cellar");
                    fail("cellar door disappeared before it could be used");
                }
                if(!Equip.ensureTwoHanded(gui, bot, Equip.PICKAXE))
                    fail("could not equip the pickaxe for cellar excavation");
                if(!approachDoor(door)) {
                    if(currentCellarDoor(door.id) == null && cellarTransitionVisible())
                        done("complete - entered cellar");
                    fail("could not reach cellar door " + door.id);
                }
                if(doorCycles >= CellarDiggerRules.MAX_DOOR_CYCLES)
                    fail("cellar excavation exceeded the 128-cycle safety limit");

                doorCycles++;
                Gob player = gui.map.player();
                if(player == null || player.rc == null)
                    fail("player position is unavailable before cellar excavation");
                rememberBoulderDropPlan(door, player);
                setPhase("excavating cellar cycle " + doorCycles);
                diag("DOOR click id=%d resid=%s pos=%s cycle=%d attempt=%d", door.id,
                    resid(door), door.rc, doorCycles, attempt);
                clickGob(door, 3);
                CellarDiggerRules.DoorOutcome outcome = waitForDoorOutcome(door.id);
                diag("DOOR result id=%d cycle=%d attempt=%d outcome=%s", door.id,
                    doorCycles, attempt, outcome);
                if(outcome == CellarDiggerRules.DoorOutcome.COMPLETE)
                    done("complete - entered cellar");
                if(outcome == CellarDiggerRules.DoorOutcome.CONTINUE) return;
            }
            fail("cellar door produced no boulder or transition after three attempts");
        }

        private CellarDiggerRules.DoorOutcome waitForDoorOutcome(long doorId)
                throws InterruptedException, Abort {
            long now = System.currentTimeMillis();
            long startDeadline = now + 3000L;
            long deadline = now + DOOR_TIMEOUT;
            long stoppedAt = -1L;
            long doorGoneAt = -1L;
            boolean started = false;

            while(System.currentTimeMillis() < deadline) {
                checkSafety();
                boolean bumling = nearestBumling() != null;
                boolean door = currentCellarDoor(doorId) != null;
                boolean transition = cellarTransitionVisible();
                CellarDiggerRules.DoorOutcome outcome =
                    CellarDiggerRules.doorOutcome(bumling, door, transition);
                if(outcome != CellarDiggerRules.DoorOutcome.RETRY) return outcome;

                boolean activeProgress = gui.prog != null;
                now = System.currentTimeMillis();
                if(!door) {
                    if(doorGoneAt < 0L) doorGoneAt = now;
                    if(now - doorGoneAt >= TRANSITION_TIMEOUT)
                        return CellarDiggerRules.DoorOutcome.RETRY;
                    Thread.sleep(100L);
                    continue;
                }
                doorGoneAt = -1L;
                if(activeProgress) {
                    started = true;
                    stoppedAt = -1L;
                } else if(!started) {
                    if(now >= startDeadline) return CellarDiggerRules.DoorOutcome.RETRY;
                } else {
                    if(stoppedAt < 0L) stoppedAt = now;
                    if(now - stoppedAt >= POST_ACTION_SETTLE)
                        return CellarDiggerRules.DoorOutcome.RETRY;
                }
                Thread.sleep(100L);
            }
            return CellarDiggerRules.DoorOutcome.RETRY;
        }

        private ChipDropResult waitForChipAndDropRocks(long boulderId)
                throws InterruptedException, Abort {
            long now = System.currentTimeMillis();
            long startDeadline = now + 3000L;
            long actionDeadline = now + CHIP_TIMEOUT;
            long stoppedAt = -1L;
            boolean started = false;
            boolean produced = false;
            boolean gone = false;

            while(System.currentTimeMillis() < actionDeadline) {
                checkSafety();
                boolean activeProgress = gui.prog != null;
                if(activeProgress) started = true;

                int dropped = ctrlDropRockStacks();
                if(dropped > 0) {
                    produced = true;
                    started = true;
                }

                GameUI.DraggedItem held = gui.hand();
                if(held != null) {
                    try {
                        held.item.info();
                    } catch(Loading loading) {
                        Thread.sleep(25L);
                        continue;
                    }
                    if(!CellarDiggerMaterials.isRockMaterial(held.item))
                        fail("an unexpected non-rock item appeared on the cursor");
                    Gob player = gui.map.player();
                    if(player == null || player.rc == null)
                        fail("player position is unavailable while dropping a chipped rock");
                    diag("ROCK-DROP cursor item=%s", safeItemResid(held.item));
                    gui.map.wdgmsg("drop", Coord.z, player.rc.floor(OCache.posres), UI.MOD_CTRL);
                    if(!waitFor(STEP_TIMEOUT, () -> gui.hand() == null))
                        fail("could not drop a chipped rock from the cursor");
                    produced = true;
                    started = true;
                }

                gone = currentBumling(boulderId) == null;
                if(gone) started = true;
                now = System.currentTimeMillis();
                if(!started) {
                    if(now >= startDeadline) break;
                } else if(activeProgress) {
                    stoppedAt = -1L;
                } else {
                    if(stoppedAt < 0L) stoppedAt = now;
                    long settle = produced ? 150L : (gone ? 750L : 2000L);
                    if(now - stoppedAt >= settle) break;
                }
                Thread.sleep(25L);
            }
            return new ChipDropResult(started, produced, gone);
        }

        private List<WItem> rockStacksInMainInventory() {
            List<WItem> out = new ArrayList<>();
            if(gui.maininv != null) {
                for(WItem item : gui.maininv.children(WItem.class))
                    if(CellarDiggerMaterials.isRockMaterial(item)) out.add(item);
            }
            return out;
        }

        private int ctrlDropRockStacks() throws InterruptedException, Abort {
            int dropped = 0;
            while(dropped < MAX_ROCK_DROPS) {
                bot.checkCancelled();
                List<WItem> stacks = rockStacksInMainInventory();
                if(stacks.isEmpty()) return dropped;
                WItem rock = stacks.get(0);
                GItem item = rock.item;
                diag("ROCK-DROP ctrl-click item=%s", safeItemResid(item));
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

        private boolean approachDoor(Gob door) throws InterruptedException {
            for(int attempt = 1; attempt <= CellarDiggerRules.MAX_ATTEMPTS; attempt++) {
                door = currentCellarDoor(door.id);
                if(door == null) return false;
                Gob player = gui.map.player();
                double distance = player == null || player.rc == null ? Double.POSITIVE_INFINITY :
                    player.rc.dist(door.rc);
                if(distance <= DOOR_DIRECT_CLICK_RADIUS) {
                    diag("DOOR-APPROACH id=%d resid=%s attempt=%d status=DIRECT_PROTOCOL distance=%.3f",
                        door.id, resid(door), attempt, distance);
                    return true;
                }
                BotMovement.Result result = BotMovement.approach(gui, bot, door, BotMovement.Mode.LAND);
                diag("DOOR-APPROACH id=%d resid=%s attempt=%d status=%s detail=%s", door.id,
                    resid(door), attempt, result == null ? null : result.status,
                    result == null ? "null result" : result.detail);
                if(result != null && result.readyToInteract()) return true;
                if(result != null && result.status == BotMovement.Status.TARGET_GONE) return false;
            }
            return false;
        }

        private boolean approachBumling(Gob bumling) throws InterruptedException {
            long id = bumling.id;
            for(int attempt = 1; attempt <= CellarDiggerRules.MAX_ATTEMPTS; attempt++) {
                Gob target = currentBumling(id);
                if(target == null) return false;
                Gob player = gui.map.player();
                double distance = player == null || player.rc == null ? Double.POSITIVE_INFINITY :
                    player.rc.dist(target.rc);
                if(CellarDiggerRules.withinDirectInteractionRange(
                    distance, BOULDER_DIRECT_CLICK_RADIUS)) {
                    diag("BOULDER-APPROACH id=%d resid=%s attempt=%d status=DIRECT_PROTOCOL distance=%.3f",
                        id, resid(target), attempt, distance);
                    return true;
                }
                BotMovement.Result normal = BotMovement.approach(gui, bot, target, BotMovement.Mode.LAND);
                diag("BOULDER-APPROACH id=%d resid=%s attempt=%d status=%s detail=%s", id,
                    resid(target), attempt, normal == null ? null : normal.status,
                    normal == null ? "null result" : normal.detail);
                if(normal != null && normal.readyToInteract()) return true;
                if(normal != null && normal.status == BotMovement.Status.TARGET_GONE) return false;

                List<Coord2d> candidates = new ArrayList<>();
                for(int i = 0; i < 24; i++) {
                    double angle = Math.PI * 2.0 * i / 24.0;
                    candidates.add(target.rc.add(Math.cos(angle) * BOULDER_APPROACH_RADIUS,
                        Math.sin(angle) * BOULDER_APPROACH_RADIUS));
                }
                player = gui.map.player();
                if(player != null && player.rc != null)
                    candidates.sort(Comparator.comparingDouble(player.rc::dist));
                BotMovement.Result ring = BotMovement.moveToAny(gui, bot, candidates,
                    BotMovement.Avoidance.NONE, BotMovement.Mode.LAND);
                diag("BOULDER-RING id=%d attempt=%d status=%s detail=%s goal=%s", id, attempt,
                    ring == null ? null : ring.status,
                    ring == null ? "null result" : ring.detail,
                    ring == null ? null : ring.selectedGoal);
                player = gui.map.player();
                target = currentBumling(id);
                if(ring != null && ring.arrived() && player != null && player.rc != null &&
                   target != null && player.rc.dist(target.rc) <= MCache.tilesz.x * 1.75)
                    return true;
            }
            return false;
        }

        private FlowerMenu openMenu(Gob gob) throws InterruptedException {
            for(int attempt = 1; attempt <= CellarDiggerRules.MAX_ATTEMPTS; attempt++) {
                bot.checkCancelled();
                Set<Widget> before = widgets(gui.ui.root);
                FlowerMenu.lastGob(gob);
                clickGob(gob, 3);
                final FlowerMenu[] found = new FlowerMenu[1];
                if(waitFor(STEP_TIMEOUT, () -> {
                    found[0] = newWidget(gui.ui.root, FlowerMenu.class, before);
                    return found[0] != null;
                })) {
                    diag("MENU id=%d attempt=%d options=%s", gob.id, attempt, menuOptions(found[0]));
                    return found[0];
                }
                diag("MENU id=%d attempt=%d result=timeout", gob.id, attempt);
                if(currentBumling(gob.id) == null) return null;
            }
            return null;
        }

        private int optionIndex(FlowerMenu menu, String name) {
            return CellarDiggerRules.exactMenuOption(menu == null ? null : menu.options, name);
        }

        private String menuOptions(FlowerMenu menu) {
            if(menu == null || menu.options == null) return "[]";
            List<String> names = new ArrayList<>();
            for(String option : menu.options) names.add(option);
            return names.toString();
        }

        private void clickGob(Gob gob, int button) {
            Coord mc = gob.rc.floor(OCache.posres);
            gui.map.wdgmsg("click", Coord.z, mc, button, 0, 0, (int)gob.id, mc, 0, -1);
        }

        private Gob nearestCellarDoor() {
            return nearestGob(true);
        }

        private Gob nearestBumling() {
            return nearestGob(false);
        }

        private Gob nearestGob(boolean door) {
            Gob player = gui.map.player();
            Coord2d origin = player == null ? null : player.rc;
            if(origin == null) return null;
            Gob nearest = null;
            double best = Double.POSITIVE_INFINITY;
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc) {
                    if(!validGob(gob)) continue;
                    String resource = resid(gob);
                    if(door ? !CellarDiggerRules.isCellarDoor(resource) :
                              !CellarDiggerRules.isBumling(resource)) continue;
                    double distance = origin.dist(gob.rc);
                    if(distance < best) {
                        nearest = gob;
                        best = distance;
                    }
                }
            }
            return nearest;
        }

        private Gob currentCellarDoor(long id) {
            Gob gob = gui.ui.sess.glob.oc.getgob(id);
            return validGob(gob) && CellarDiggerRules.isCellarDoor(resid(gob)) ? gob : null;
        }

        private Gob currentBumling(long id) {
            Gob gob = gui.ui.sess.glob.oc.getgob(id);
            return validGob(gob) && CellarDiggerRules.isBumling(resid(gob)) ? gob : null;
        }

        private boolean isCarried(Gob gob) {
            if(gob == null || gob.disposed()) return false;
            Gob player = gui.map.player();
            Following following = gob.getattr(Following.class);
            return player != null && following != null && following.tgt == player.id;
        }

        private boolean cellarTransitionVisible() {
            synchronized(gui.ui.sess.glob.oc) {
                for(Gob gob : gui.ui.sess.glob.oc)
                    if(validGob(gob) && CellarDiggerRules.isCellarTransition(resid(gob))) return true;
            }
            return false;
        }

        private boolean inCombat() {
            try {
                return gui.fv != null && !gui.fv.lsrel.isEmpty();
            } catch(RuntimeException ignored) {
                return true;
            }
        }

        private double meter(String name) {
            try {
                IMeter meter = gui.getIMeter(name);
                return meter == null ? -1.0 : meter.meter(0);
            } catch(RuntimeException ignored) {
                return -1.0;
            }
        }

        private Set<Widget> widgets(Widget root) {
            Set<Widget> out = new HashSet<>();
            if(root == null) return out;
            for(Widget child = root.lchild; child != null; child = child.prev) {
                out.add(child);
                out.addAll(widgets(child));
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

        private String safeItemResid(GItem item) {
            try {
                return item == null ? null : item.resname();
            } catch(RuntimeException ignored) {
                return "<loading>";
            }
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

        private void setPhase(String next) {
            phase = next;
            setStatus(next);
            diag("PHASE %s", next);
        }

        private void done(String message) throws Abort {
            throw new Abort(message, true);
        }

        private void fail(String message) throws Abort {
            throw new Abort("failed during " + phase + ": " + message, false);
        }
    }

    private static boolean validGob(Gob gob) {
        return gob != null && gob.rc != null && !gob.disposed() && !gob.virtual && gob.id >= 0;
    }

    private static String resid(Gob gob) {
        try {
            return gob == null ? null : gob.resid();
        } catch(RuntimeException ignored) {
            return null;
        }
    }
}
