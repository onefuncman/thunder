package auto;

import haven.*;
import haven.res.gfx.fx.mscover.Global;
import haven.res.gfx.fx.mscover.Data;
import haven.rx.Reactor;
import thunder.mining.MiningZoneStore;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Automated staggered-support mining: from a support, mine forward (the
 * primary direction) for `radius` tiles, jog one tile to the right, place
 * the next support there, and continue in the original direction -- the
 * tunnel drifts sideways by one tile per support so it stays inside
 * continuous support coverage. Runs on the shared auto.Bot runner so it
 * shares cancellation with every other bot-driven feature (Esc, :macro
 * cancel, etc.), and haven.bot.MiningWatchdog aborts it the same way
 * (Bot.cancelCurrent()) when a threat is spotted.
 *
 * Action paths were confirmed empirically against a live session:
 *   Mine          -> paginae/act/mine,  action data ["mine"]
 *   Stone Column  -> paginae/bld/column, action data ["bp","column"]
 * Both are server-driven UI arms (uimsg "sel" / "place" on MapView); this
 * class drives them via MapView.hasActiveSelector/commitAreaSelection and
 * hasActivePlacement/commitPlacement rather than touching MapView's
 * internal state directly.
 *
 * Material names (MiningMaterials) and the jog-direction handedness below
 * are best-guess, flagged for the same empirical-correction pass.
 */
public class MiningBot {
    private static volatile boolean running = false;

    // Every [minebot-diag] line also goes here instead of only Debug.log (which
    // routes to the in-game console's own scrollback, with no way to page back
    // through it) -- one plain-text file per run, flushed after every write, at
    // a fixed, known path so it can be read directly rather than pasted by hand.
    private static java.io.PrintWriter diagLog;
    private static java.nio.file.Path diagLogPath;

    private MiningBot() {}

    public static boolean isRunning() {
        return running;
    }

    private static void openDiagLog(GameUI gui) {
        try {
            java.nio.file.Path dir = Debug.somedir("minebot-logs");
            dir.toFile().mkdirs();
            diagLogPath = dir.resolve("minebot-" + System.currentTimeMillis() + ".log");
            diagLog = new java.io.PrintWriter(new java.io.FileWriter(diagLogPath.toFile(), true), true);
        } catch(Exception e) {
            diagLog = null;
            diagLogPath = null;
        }
    }

    private static void closeDiagLog() {
        if(diagLog != null) {
            diagLog.close();
            diagLog = null;
        }
    }

    /* Package-private (not private) so MiningMaterials can log through the same
     * per-run file instead of duplicating the file-writer plumbing. */
    static void diag(String fmt, Object... args) {
        String line = String.format(fmt, args);
        Debug.log.println(line);
        Debug.log.flush();
        if(diagLog != null) {
            diagLog.println(line);
            diagLog.flush();
        }
    }

    /** Live stack trace of every thread named "Worker thread #N" (Defer.Worker's own
     * naming, haven/Defer.java) -- catches a genuinely hung worker in the act instead
     * of inferring one from queue/busy/pool counts. */
    private static void dumpWorkerThreadStacks() {
        for(java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            if(!t.getName().startsWith("Worker thread #")) {continue;}
            StringBuilder sb = new StringBuilder();
            sb.append("Thread \"").append(t.getName()).append("\" state=").append(t.getState()).append('\n');
            for(StackTraceElement el : e.getValue()) {
                sb.append("\tat ").append(el).append('\n');
            }
            diag("[minebot-diag] live worker thread stack:%n%s", sb.toString());
        }
    }

    /** Ends the current run early with a reason (called by MiningWatchdog or :minebot stop). */
    public static void abort(String reason) {
        if(running) {
            Bot.cancelCurrent(reason);
        }
    }

    public enum Direction {
        NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

        final int dx, dy;
        Direction(int dx, int dy) {this.dx = dx; this.dy = dy;}

        public static Direction parse(String s) {
            switch(s.toLowerCase(Locale.ROOT)) {
                case "n": case "north": return NORTH;
                case "s": case "south": return SOUTH;
                case "e": case "east":  return EAST;
                case "w": case "west":  return WEST;
                default: throw new IllegalArgumentException("Unknown direction '" + s + "' (use n/s/e/w)");
            }
        }

        Coord step() {return new Coord(dx, dy);}

        /* 90-degree rotation in this client's tile-coord convention (x east, y south):
         * (dx,dy) -> (-dy,dx). Best-effort derivation, not yet confirmed in-game. */
        Coord rightStep() {return new Coord(-dy, dx);}
    }

    public static void start(GameUI gui, Direction dir, int supportRadius, int stoneMin, int eatUntilPercent, int segmentCap, int barsTarget) {
        if(running) {
            gui.error("MiningBot is already running -- use ':minebot stop' first.");
            return;
        }
        if(supportRadius <= 0) {
            gui.error("Support radius must be positive.");
            return;
        }
        if(gui.menu == null || gui.map == null) {
            gui.error("Game UI isn't fully loaded (menu/map missing) -- wait for the game to finish loading, or relog if this persists, then try again.");
            return;
        }
        Gob player = gui.map.player();
        if(player == null) {
            gui.error("Player not found.");
            return;
        }
        // Anchor from the nearest REAL mine-support gob's actual position, never from
        // wherever the player happens to be standing (per TunnelerBot.java's own
        // stage==0 logic: findObjectsByNames("gfx/terobjs/column") + closestGob). The
        // support sits one tile to the "right" of the tunnel centerline (see
        // Direction.rightStep/runLoop's jog), so the tunnel anchor is the support's
        // tile minus that offset.
        Gob nearestSupport = findNearestSupport(gui, player);
        if(nearestSupport == null) {
            gui.error("No mine support found nearby -- stand within sight of an existing, already-built support before starting.");
            return;
        }
        Coord origin = nearestSupport.rc.floor(MCache.tilesz).sub(dir.rightStep());
        // Follow the chain of already-placed supports forward (each one sits exactly
        // `supportRadius` tiles further along, per this same jog geometry) to the true
        // frontier, rather than anchoring off just the nearest one -- otherwise the
        // bot doesn't notice a support a segment or two ahead and tries to mine/place
        // right next to one that's already there.
        Coord fwdStep = dir.step(), rightStep = dir.rightStep();
        while(true) {
            Coord expected = origin.add(fwdStep.mul(supportRadius)).add(rightStep);
            if(findSupportNear(gui, expected) == null) {break;}
            origin = origin.add(fwdStep.mul(supportRadius));
        }
        int cap = segmentCap <= 0 ? Integer.MAX_VALUE : segmentCap;
        int stoneNeed = Math.max(0, stoneMin);
        int eatUntil = Math.max(1, Math.min(100, eatUntilPercent));
        int barsNeed = Math.max(1, barsTarget);
        final Coord frontier = origin;

        running = true;
        openDiagLog(gui);
        // Bot's own cleanup(...) hook only fires on a normal return from the action --
        // it's skipped whenever the action exits via InterruptedException, which is
        // exactly what happens on every external cancel (Stop button, Esc,
        // MiningWatchdog's threat-flee). Wrap in our own try/finally instead so
        // `running` reliably clears no matter how the run ends.
        Bot.execute((target, bot) -> {
            try {
                prewarmSupportResource(gui, bot);
                runLoop(gui, bot, frontier, dir, supportRadius, stoneNeed, eatUntil, cap, barsNeed);
            } finally {
                running = false;
                closeDiagLog();
            }
        }).start(gui.ui, true);
    }

    public static void stop() {
        abort("Stopped by user.");
    }

    /** Nearest gob matching a known mine-support resource pattern (GobRadius.toggleFor's own substring list). */
    private static Gob findNearestSupport(GameUI gui, Gob player) {
        return gui.ui.sess.glob.oc.stream()
            .filter(MiningBot::looksLikeSupport)
            .min(java.util.Comparator.comparingDouble(g -> g.rc.dist(player.rc)))
            .orElse(null);
    }

    /** A support-type gob within about one tile of the given tile's center, or null. */
    private static Gob findSupportNear(GameUI gui, Coord tile) {
        Coord2d center = MCache.tilesz.mul(tile.x, tile.y).add(5, 5);
        return gui.ui.sess.glob.oc.stream()
            .filter(MiningBot::looksLikeSupport)
            .filter(g -> g.rc.dist(center) <= MCache.tilesz.x)
            .findFirst().orElse(null);
    }

    private static boolean looksLikeSupport(Gob g) {
        try {
            String id = g.resid();
            if(id == null) {return false;}
            return id.contains("minesupport") || id.contains("column") || id.contains("minebeam")
                || id.contains("naturalminesupport") || id.contains("towercap");
        } catch(Exception e) {
            return false;
        }
    }

    private static void runLoop(GameUI gui, Bot bot, Coord support, Direction dir, int radius, int stoneNeed, int eatUntil, int cap, int barsNeed) throws InterruptedException {
        Coord fwd = dir.step();
        Coord right = dir.rightStep();
        int segments = 0;

        while(segments < cap) {
            // SAFETY: mine exactly `radius` tiles, no more -- a simple, predictable fixed
            // count from a correctly-anchored starting point (the real nearest support's
            // position, established in start()/at the end of the previous segment).
            // haven.res.gfx.fx.mscover's live coverage data (isSupported, still below)
            // proved unreliable as a bound (it once allowed mining well past the
            // configured radius) and is not used to gate this loop; a wrong/stale
            // coverage read must never be able to justify mining further than radius.
            for(int step = 1; step <= radius; step++) {
                bot.checkCancelled();
                ensureSupplies(gui, bot, stoneNeed, barsNeed, eatUntil);

                Coord targetTile = support.add(fwd.mul(step));
                // Skip the whole arm+select+wait-for-progress sequence on a tile that's
                // already open floor -- mineTile's own wait (waitProgress's first loop)
                // has nothing to wait FOR there (no swing ever starts), so it just burns
                // its full 2s startTimeout every time before giving up. Re-running a
                // previously-mined tunnel (or resuming after a restart) shouldn't pay
                // that cost tile by tile.
                boolean mined;
                if(MapHelper.isMinedFloorTile(gui, targetTile)) {
                    mined = false;
                } else {
                    mined = mineTile(gui, bot, targetTile); // false may just mean "already open floor here"
                }
                if(!walkToTile(gui, targetTile)) {
                    bot.cancel("Blocked " + step + " tile(s) past the last support -- " + blockedReason(mined) + ".");
                    return;
                }
            }

            // preJogTile is where the character is already standing (the last tile of
            // the straight run, exactly `radius` tiles from the segment's anchor) -- the
            // jog tile is mined and the support is placed from there without walking onto
            // it; placement's own auto-walk (pathQueue.start) handles getting close
            // enough, and we explicitly return here afterward.
            Coord preJogTile = support.add(fwd.mul(radius));
            bot.checkCancelled();
            Coord jogTile = preJogTile.add(right);
            if(!MapHelper.isMinedFloorTile(gui, jogTile)) {
                mineTile(gui, bot, jogTile); // false may just mean "already open floor here"; ignored, matches forward-mining tiles
            }

            bot.checkCancelled();
            // Defensive re-check right before placement -- the per-tile ensureSupplies
            // calls above are best-effort (never cancel the run), so this is the one
            // place stone/bars become a hard requirement: if we're still short after a
            // full resupply attempt, there's genuinely nowhere left to get more.
            ensureSupplies(gui, bot, stoneNeed, barsNeed, eatUntil);
            if(MiningMaterials.stoneCount(gui) < stoneNeed || !MiningMaterials.hasBar(gui)) {
                bot.cancel("Out of materials with no floor stone/stockpile zone (or empty) to resupply from.");
                return;
            }
            // Wait for the UI command queue to actually drain before arming placement.
            // uimsg/wdgmsg processing shares one thread pool with resource loading
            // (UI.CommandQueue.execute -> loader.defer), and commands targeting the
            // same widget (MapView) are strictly ordered -- back-to-back mine actions
            // with none of a human's natural pacing can leave a real backlog of
            // MapView-targeted server responses for the "place" arm command to queue
            // behind. A fixed pause (the previous approach here) doesn't check whether
            // that backlog has actually cleared; this does.
            waitForCommandQueueIdle(gui, bot, 15000);
            // Every logged field of the actual "place" commit (rc, angle, playerTile,
            // distance) came back byte-identical to a real, successful manual placement
            // at this exact tile -- confirmed via direct comparison -- yet the bot's
            // commit still gets silently rejected. That rules out the commit's own
            // content; the remaining difference is server-side state at the moment of
            // commit. walkToTile's "arrived" check is a local, client-side position
            // check -- if the server's own authoritative movement state (Moving
            // GAttrib, server-driven, same idiom thunder.MilkingAssist already uses to
            // check this) hasn't cleared yet, the server could still see the character
            // as not-quite-settled even though the client already looks arrived. A
            // manual placement never hits this because a real player pauses before
            // clicking. Waiting on this specific, verifiable signal (not a blind pause)
            // tests that directly.
            waitForMovementSettled(gui, bot, 3000);
            if(!placeSupport(gui, bot, tileCenter(jogTile))) {
                bot.cancel("Could not place support for segment " + (segments + 1) + " -- placement or construction failed.");
                return;
            }

            bot.checkCancelled();
            if(!walkToTile(gui, preJogTile)) {
                bot.cancel("Could not return to " + preJogTile + " after placing support for segment " + (segments + 1) + ".");
                return;
            }

            // The main tunnel stays a straight line -- the next segment continues from
            // preJogTile (on that line), not from jogTile (the support's one-tile offset).
            // Using jogTile here was the bug: it walked the whole line sideways by one
            // tile at every support instead of just poking the support out to the side.
            support = preJogTile;
            segments++;
        }
        gui.msg("MiningBot finished: " + segments + " support segment(s) mined.", GameUI.MsgType.GOOD);
    }

    private static Coord2d tileCenter(Coord tile) {
        return MCache.tilesz.mul(tile.x, tile.y).add(5, 5);
    }

    private static boolean walkToTile(GameUI gui, Coord tile) {
        return MapHelper.walkTo(gui, tileCenter(tile), 4000);
    }

    private static String blockedReason(boolean mined) {
        return mined
            ? "mined it but could not walk in afterward (movement blocked?)"
            : "could not mine it (out of range/stamina, or nothing there) and could not walk into it either (solid wall?)";
    }

    /**
     * Checked every mine-tile step and again (defensively) right before placement.
     * If ANY of water/food/stone/bars is running low, tops up ALL FOUR in the same
     * trip, not just whichever one triggered it -- a resupply trip's real cost is
     * the walk there and back, not the number of things picked up while there, so
     * running out of water on segment 4 and going back for bars on segment 7 (two
     * separate trips to the same or nearby zones) is pure waste. Every underlying
     * call (refillWaterFromZone/eatFromZone/fetchFromZone) already checks its own
     * target before acting and returns fast if already satisfied, so calling all
     * four unconditionally whenever ANY one is low is safe and cheap, not wasteful.
     * Never cancels the run itself -- see runLoop's own hard stone/bar check right
     * before each placement for where that safety guarantee still lives.
     */
    // IMeter's own fraction is 0.0-1.0 (confirmed against IMeter.checkStarvation's
    // already-trusted 0.20/0.25 starvation thresholds) -- not the same number as the
    // inflated tooltip percentage (e.g. "9056%"), which is that same fraction times
    // another 100 (a server-side display quirk, not a different stat: 20% <-> "2000%"
    // is the same moment, just two display scales). User wants the stop-and-eat
    // trigger at "2600%" on that tooltip scale, i.e. 0.26 here.
    private static final double LOW_ENERGY_THRESHOLD = 0.26;

    private static void ensureSupplies(GameUI gui, Bot bot, int stoneNeed, int barsNeed, int eatUntil) throws InterruptedException {
        IMeter stam = gui.getIMeter("stam");
        boolean lowWater = stam != null && stam.meter(0) >= 0 && stam.meter(0) < 0.15 && !hasAnyDrink(gui);
        IMeter nrj = gui.getIMeter("nrj");
        boolean lowFood = nrj != null && nrj.meter(0) >= 0 && nrj.meter(0) < LOW_ENERGY_THRESHOLD;
        boolean lowStone = MiningMaterials.stoneCount(gui) < stoneNeed;
        boolean lowBars = !MiningMaterials.hasBar(gui);
        if(!lowWater && !lowFood && !lowStone && !lowBars) {return;}
        diag("[minebot-diag] ensureSupplies: resupply triggered (water=%b food=%b stone=%b bars=%b) -- topping up everything",
            lowWater, lowFood, lowStone, lowBars);

        Area water = MiningZoneStore.get().get(MiningZoneStore.ROLE_WATER);
        if(water != null) {
            boolean ok = MiningMaterials.refillWaterFromZone(gui, bot, water);
            diag("[minebot-diag] ensureSupplies: refillWaterFromZone -> %b", ok);
        } else if(lowWater) {
            diag("[minebot-diag] ensureSupplies: out of water and no water zone designated");
        }

        Area food = MiningZoneStore.get().get(MiningZoneStore.ROLE_FOOD);
        if(food != null) {
            boolean ok = MiningMaterials.eatFromZone(gui, bot, food, eatUntil);
            diag("[minebot-diag] ensureSupplies: eatFromZone -> %b", ok);
        } else if(lowFood) {
            diag("[minebot-diag] ensureSupplies: low energy and no food zone designated");
        }

        if(MiningMaterials.stoneCount(gui) < stoneNeed) {
            if(!MiningMaterials.pickUpLooseStone(gui, bot, stoneNeed)) {
                Area stockpile = MiningZoneStore.get().get(MiningZoneStore.ROLE_STOCKPILE);
                if(stockpile != null) {
                    boolean ok = MiningMaterials.fetchFromZone(gui, bot, stockpile, MiningMaterials::isBuildingStone, stoneNeed);
                    diag("[minebot-diag] ensureSupplies: fetch stone from stockpile -> %b", ok);
                } else {
                    diag("[minebot-diag] ensureSupplies: out of stone, no floor stone nearby, no stockpile zone designated");
                }
            }
        }

        Area stockpile = MiningZoneStore.get().get(MiningZoneStore.ROLE_STOCKPILE);
        if(stockpile != null) {
            boolean ok = MiningMaterials.fetchFromZone(gui, bot, stockpile, MiningMaterials::isHardBar, barsNeed);
            diag("[minebot-diag] ensureSupplies: fetch bars from stockpile (target %d) -> %b", barsNeed, ok);
        } else if(lowBars) {
            diag("[minebot-diag] ensureSupplies: out of bars and no stockpile zone designated");
        }
    }

    /**
     * Drops whatever's held on the cursor onto the ground at the player's feet --
     * not back into inventory (MapView.drop's real ground-drop wdgmsg shape,
     * "drop" on the map widget with a world coord, not gui.maininv's "drop" which
     * just returns it to an inventory slot and does nothing if inventory is full,
     * the exact case this exists to handle). CFG.ITEM_DROP_PROTECTION silently
     * downgrades an unprotected drop to a plain click unless Ctrl is held, hence
     * MOD_CTRL here.
     */
    private static void dropCursorItem(GameUI gui) {
        if(gui.hand() == null) {return;}
        Gob player = gui.map.player();
        if(player == null) {return;}
        gui.map.wdgmsg("drop", Coord.z, player.rc.floor(OCache.posres), UI.MOD_CTRL);
        BotUtil.waitHeldChanged(gui);
    }

    /**
     * isBucket/isDrinkContainer only check item TYPE (does the resource name end in
     * "/waterskin" etc) -- an empty waterskin still carried on the belt satisfies that
     * check forever, so the old version of this method never noticed the character had
     * run dry and never triggered a zone refill. InvHelper.HAS_WATER checks the item's
     * actual contents (w.contains.get().is("Water")), which is what "do I still have a
     * drink" actually needs to mean.
     */
    private static boolean hasAnyDrink(GameUI gui) {
        return InvHelper.HANDS(gui).get().stream().anyMatch(w -> InvHelper.isBucket(w) && InvHelper.HAS_WATER.test(w))
            || InvHelper.POUCHES(gui).get().stream().anyMatch(w -> InvHelper.isDrinkContainer(w) && InvHelper.HAS_WATER.test(w))
            || InvHelper.INVENTORY(gui).get().stream().anyMatch(w -> InvHelper.isDrinkContainer(w) && InvHelper.HAS_WATER.test(w))
            || InvHelper.BELT(gui).get().stream().anyMatch(w -> InvHelper.isDrinkContainer(w) && InvHelper.HAS_WATER.test(w));
    }

    /** Arms Mine mode, selects exactly the one target tile, and waits for the swing to land. */
    private static boolean mineTile(GameUI gui, Bot bot, Coord tile) throws InterruptedException {
        dropCursorItem(gui); // same precondition as placeSupport -- a stuck cursor item can block arming too
        // Matches the real UI click's wire shape exactly (captured via discovery logging):
        // MenuGrid.PagButton.use(Interaction) sends act(actionData..., modflags) -- the no-arg
        // .use() shortcut (as used by Actions.aggro for "atk") omits modflags, which "mine"
        // apparently needs to actually arm the selector.
        gui.menu.wdgmsg("act", "mine", gui.ui.modflags());
        boolean armed = waitFor(bot, 2000, gui.map::hasActiveSelector);
        diag("[minebot-diag] mineTile tile=%s armed=%b", tile, armed);
        if(!armed) {
            return false; // no wall in mining range, or the server refused to arm the selector
        }
        gui.map.commitAreaSelection(tile, tile, gui.ui.modflags());
        boolean progressed = BotUtil.waitProgress(bot, 2000, 15000);
        diag("[minebot-diag] mineTile tile=%s progressed=%b", tile, progressed);
        // Right-click on self cancels the active cursor/selection mode (same idiom
        // Actions.aggro uses in its cleanup) -- without this, a lingering Mine-mode
        // selection may block ordinary movement clicks afterward.
        BotUtil.rclick(gui);
        return progressed;
    }

    /**
     * Arms the Stone Column build action, commits it at the given world position
     * (the character walks there on its own via the placement's pathQueue, same
     * as a real click), then works the resulting "Stone Column" requirements
     * window: waits for it, clicks Build, and confirms it closes (construction
     * actually completed) rather than assuming success.
     */
    private static boolean placeSupport(GameUI gui, Bot bot, Coord2d at) throws InterruptedException {
        dropCursorItem(gui); // a full-inventory pickup can leave an item stuck on the cursor, which blocks placement (same precondition Actions.refillDrinks checks for)

        // Auto-capture the raw wire traffic for this placement attempt (same recorder
        // the :proto record console command drives) so a failure can be read back
        // directly instead of guessed at from a boolean state machine.
        java.nio.file.Path captureFile = startProtoCapture(gui);
        try {
            return placeSupportInner(gui, bot, at);
        } finally {
            stopProtoCapture(gui);
            if(captureFile != null) {
                gui.msg("MiningBot: placement wire capture saved to " + captureFile, GameUI.MsgType.INFO);
            }
        }
    }

    private static java.nio.file.Path startProtoCapture(GameUI gui) {
        try {
            if(gui.ui.sess == null || gui.ui.sess.protoBus == null) {return null;}
            haven.proto.EnhancedRecorder rec = gui.ui.sess.protoBus.recorder;
            java.nio.file.Path dir = Debug.somedir("proto-recordings");
            dir.toFile().mkdirs();
            java.nio.file.Path file = dir.resolve("minebot-place-" + System.currentTimeMillis() + ".rec");
            rec.start(file);
            return rec.getFilePath();
        } catch(Exception e) {
            return null;
        }
    }

    private static void stopProtoCapture(GameUI gui) {
        try {
            if(gui.ui.sess != null && gui.ui.sess.protoBus != null) {
                gui.ui.sess.protoBus.recorder.stop();
            }
        } catch(Exception ignored) {}
    }

    private static boolean placeSupportInner(GameUI gui, Bot bot, Coord2d at) throws InterruptedException {
        java.util.Set<Widget> before = new java.util.HashSet<>();
        for(Widget w = gui.lchild; w != null; w = w.prev) {before.add(w);}

        gui.menu.wdgmsg("act", "bp", "column", gui.ui.modflags());
        // A manual hotkey press arms this basically instantly -- a long timeout here
        // was papering over a real difference between that and this bot's identical
        // wdgmsg, not fixing it. Kept short so a genuine problem fails fast instead of
        // stalling the run; see the pause just before this call for the current
        // (unconfirmed) mitigation attempt, and the wire-capture comparison in
        // progress for the actual root cause.
        boolean armed = waitForPlacementArmed(gui, bot, at, 10000);
        if(!armed) {
            diag("[minebot-diag] placeSupportInner: never armed within 10s");
            gui.msg("MiningBot: Stone Column placement never armed within 10s.", GameUI.MsgType.BAD);
            // Tear down the stale future instead of just dropping the reference -- a
            // left-running one would otherwise still be sitting around (and getting
            // torn down and replaced by MapView's own uimsg("place",...) handler,
            // which does the same cancel() call) the moment the *next* attempt arms.
            gui.map.cancelPlacement();
            return false;
        }
        diag("[minebot-diag] placeSupportInner: armed, committing at=%s", at);
        // warpAndCommitPlacement drives the ghost's own StdPlace.adjust to move it to
        // `at` (computing its own real angle the same way a real mousemove would),
        // then commits using the ghost's own resulting rc/a. Sending an externally
        // computed coordinate instead (the previous approach) got a *silent*
        // rejection -- a captured wire trace showed the server responding to our
        // "place" with an immediate "unplace" and no error toast, because our sent
        // coordinate didn't match the ghost's own tracked position.
        // button=1 (left click), confirmed via a direct side-by-side comparison against
        // a real manual placement's logged commit at this exact tile -- every other
        // field (rc, angle, playerTile, distance) matched exactly; button=3 (an earlier,
        // apparently mistaken assumption from way back in this investigation) was the
        // one and only difference, and the reason every commit was getting silently
        // rejected even once the loading-stall and angle bugs were fixed.
        if(!gui.map.warpAndCommitPlacement(at, 1, gui.ui.modflags())) {
            diag("[minebot-diag] placeSupportInner: ghost disappeared before commit");
            gui.msg("MiningBot: placement ghost disappeared before it could be committed.", GameUI.MsgType.BAD);
            return false;
        }
        diag("[minebot-diag] placeSupportInner: commit sent, capturing rejection window: %s", gui.map.lastWarpCommitDebug());
        String rejection = captureServerMessage(1500);
        if(rejection != null) {
            diag("[minebot-diag] placeSupportInner: rejected with message: %s", rejection);
            gui.msg("MiningBot: support placement rejected: " + rejection, GameUI.MsgType.BAD);
            return false;
        }
        // hasActivePlacement() going false here is NOT a reliable rejection signal --
        // confirmed live: a placement that actually succeeded (ghost placed, Stone
        // Column window appeared, exactly as expected) ALSO cleared the ghost by this
        // point, apparently as normal success cleanup once the server hands off to the
        // real build window, not just on rejection. Treating that as failure here was
        // a false positive that gave up right before the window would have been found
        // waiting. The window's actual appearance (checked next) is the real signal.
        diag("[minebot-diag] placeSupportInner: no text rejection, waiting for Stone Column window (ghost state no longer checked -- see comment)");

        Window wnd = waitForNamedWindow(gui, bot, "Stone Column", before, 6000);
        if(wnd == null) {
            diag("[minebot-diag] placeSupportInner: Stone Column window never appeared");
            gui.msg("MiningBot: Stone Column window never appeared after placement.", GameUI.MsgType.BAD);
            return false;
        }
        diag("[minebot-diag] placeSupportInner: window appeared, looking for Build button");
        Button build = findButton(wnd, "Build");
        if(build == null) {
            diag("[minebot-diag] placeSupportInner: no Build button found");
            gui.msg("MiningBot: no Build button found in the Stone Column window.", GameUI.MsgType.BAD);
            return false;
        }
        diag("[minebot-diag] placeSupportInner: clicking Build");
        build.click();

        if(!waitFor(bot, 8000, () -> wnd.disposed())) {
            diag("[minebot-diag] placeSupportInner: window stayed open after Build click (out of materials?)");
            gui.msg("MiningBot: Stone Column construction didn't complete (window stayed open -- out of materials?).", GameUI.MsgType.BAD);
            return false;
        }
        diag("[minebot-diag] placeSupportInner: window closed, build succeeded");
        return true;
    }

    /**
     * Waits for the player gob's Moving GAttrib to clear -- server-driven state (set/
     * cleared by the server's own movement messages, per LinMove), not a purely local
     * prediction, so this is a genuine signal that the server itself considers the
     * character settled, not just that the client's own arrival check passed. Same
     * idiom thunder.MilkingAssist already uses (player.getattr(Moving.class) != null).
     */
    /* Package-private (not private) so MiningMaterials can reuse the same check
     * before container/gob interactions, not just before placement commits. */
    static void waitForMovementSettled(GameUI gui, Bot bot, long timeoutMs) throws InterruptedException {
        Gob player = gui.map.player();
        if(player == null) {return;}
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean wasMoving = player.getattr(Moving.class) != null;
        if(wasMoving) {
            diag("[minebot-diag] waitForMovementSettled: still moving, waiting");
        }
        while(player.getattr(Moving.class) != null) {
            bot.checkCancelled();
            if(System.currentTimeMillis() >= deadline) {
                diag("[minebot-diag] waitForMovementSettled: still moving after %dms, giving up", timeoutMs);
                return;
            }
            BotUtil.pause(50);
        }
        if(wasMoving) {
            diag("[minebot-diag] waitForMovementSettled: settled");
        }
    }

    /** Waits for a Window with the given caption to appear among gui's children that wasn't already there in `before`. */
    private static Window waitForNamedWindow(GameUI gui, Bot bot, String caption, java.util.Set<Widget> before, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while(System.currentTimeMillis() < deadline) {
            bot.checkCancelled();
            for(Widget w = gui.lchild; w != null; w = w.prev) {
                if((w instanceof Window) && !before.contains(w) && caption.equals(((Window) w).caption())) {
                    return (Window) w;
                }
            }
            BotUtil.pause(100);
        }
        return null;
    }

    /* Package-private (not private) so MiningMaterials can reuse it for the
     * stockpile "Take" button, not just the Stone Column "Build" button. Recursive --
     * the Stone Column window happens to have Build as a direct child, but a
     * Stockpile's "Take" button is nested one level inside an ISBox (confirmed live
     * via a full widget-tree dump), so a direct-children-only search silently missed
     * it every time despite the window opening correctly. */
    static Button findButton(Widget parent, String label) {
        for(Widget w = parent.lchild; w != null; w = w.prev) {
            if((w instanceof Button) && (((Button) w).text != null) && label.equals(((Button) w).text.text)) {
                return (Button) w;
            }
            Button found = findButton(w, label);
            if(found != null) {return found;}
        }
        return null;
    }

    /**
     * Polls UI.queue.inflight(), logging its depth whenever it changes, until it
     * reads 0 or the timeout elapses. Unlike UI.queue.drain() (which blocks
     * uninterruptibly and has no timeout -- not safe to call from a cancellable
     * bot thread), this is a normal cancellable poll loop matching
     * waitForPlacementArmed's own diagnostic discipline: if the queue is
     * genuinely never idle, this gives up and logs that fact instead of hanging
     * forever.
     */
    private static boolean waitForCommandQueueIdle(GameUI gui, Bot bot, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int lastDepth = -1;
        while(true) {
            bot.checkCancelled();
            int depth = gui.ui.queue.inflight();
            if(depth != lastDepth) {
                diag("[minebot-diag] command queue inflight=%d", depth);
                lastDepth = depth;
            }
            if(depth == 0) {return true;}
            if(System.currentTimeMillis() >= deadline) {return false;}
            BotUtil.pause(100);
        }
    }

    /**
     * Arms a Stone Column placement once, up front, before any mining happens,
     * waits for it with a generous (60s) cap since nothing is time-critical yet,
     * then tears it down without committing anything. Every stuck-placement
     * capture so far has shown the exact same wire request the hotkey sends
     * (ruling out a protocol/coordinate bug) resolving inconsistently -- from
     * instant to 40s+ -- which matches an ordinary first-use-in-session resource
     * load/cache-miss for the Stone Column model more than any bug in this
     * class's own logic (a manual hotkey press earlier in the session pays that
     * cost once and every press after is instant; a bot's first placement
     * attempt hasn't paid it yet). This can't make that load itself faster, but
     * it moves *when* the wait happens from a moment where the character is
     * standing in a freshly-mined, unsupported tile to one before mining has
     * even started.
     */
    private static void prewarmSupportResource(GameUI gui, Bot bot) throws InterruptedException {
        gui.msg("MiningBot: pre-loading the Stone Column resource before starting...", GameUI.MsgType.INFO);
        long started = System.currentTimeMillis();
        gui.menu.wdgmsg("act", "bp", "column", gui.ui.modflags());
        boolean armed = waitForPlacementArmed(gui, bot, null, 60000);
        gui.map.cancelPlacement();
        long tookMs = System.currentTimeMillis() - started;
        if(armed) {
            gui.msg("MiningBot: Stone Column resource ready (" + tookMs + "ms) -- starting.", GameUI.MsgType.INFO);
        } else {
            gui.msg("MiningBot: Stone Column resource still hadn't finished loading after 60s -- starting anyway, but expect the first placement to be slow too.", GameUI.MsgType.BAD);
        }
    }

    /**
     * Polls hasActivePlacement, logging MapView's raw placing state every time it
     * changes (not just a single before/after boolean) and nudging the player
     * every 5s so a long wait is visibly "still working," not indistinguishable
     * from frozen. place() (which attaches the ghost to the render tree, making it
     * visible) runs synchronously as part of resolving the same Loader.Future that
     * hasActivePlacement checks -- on paper visible-ghost and done() should be the
     * same instant, but that wasn't observed live, so this errs long (45s) and
     * transparent rather than guessing another fixed timeout.
     */
    private static boolean waitForPlacementArmed(GameUI gui, Bot bot, Coord2d at, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastNudge = System.currentTimeMillis();
        String lastState = null;
        while(true) {
            bot.checkCancelled();
            // Root-caused live via a captured stack trace of the actual stuck worker
            // thread: auto.Bot runs this ENTIRE bot run as one Defer.Future (Bot.java's
            // task = Defer.later(this)), which permanently occupies its Defer instance's
            // one worker thread for the run's whole lifetime -- and TexL's texture
            // finalization (what a stuck placement is really waiting on, e.g. "Finalizing
            // texture in gfx/terobjs/bumlings/porphyry...") shares that exact same
            // instance. The bot thread is the only thing that could free that worker,
            // and it can't, because it's the one blocked waiting. ensureExtraWorker
            // forces a second worker into that specific instance so the stuck texture
            // task has somewhere to run at all; boostPlacementPriority (kept, harmless)
            // only matters once a free worker actually exists to hand it to.
            gui.map.ensurePlacementBlockerWorker();
            gui.map.boostPlacementPriority(10);
            String state = gui.map.placingDebugState();
            if(!state.equals(lastState)) {
                // placingDebugState only tells us *that* the Plob is still loading, not
                // *why* -- it's a Loader.Future like any other resource, competing for
                // the same shared thread pool (maxthreads=4) as every uimsg/wdgmsg
                // Command AND every other resource load a rapid-fire mining burst can
                // trigger (particles, decals, tooltips). queue.inflight() only shows
                // pending Commands, not this second, independent bottleneck -- log both
                // so a stall's actual cause shows up in the numbers instead of a guess.
                // A third, separate pool: Resource.Pool's own content-fetch loaders
                // (nloaders=2, smaller than Glob.loader's 4) -- qdepth() is a pre-existing
                // accessor, not something added for this. If a Stone Column resource
                // fetch is stuck behind other resource loads (mining throws off a lot of
                // decal/particle/tile fetches), this is where that backlog would show up,
                // invisible to the other two numbers.
                diag("[minebot-diag] placeSupport at=%s placingState=%s queueInflight=%d loaderStats=%s resourceQdepth=%d",
                    at, state, gui.ui.queue.inflight(), gui.ui.sess.glob.loader.stats(), Resource.remote().qdepth());
                String blockerStats = gui.map.placingBlockerPoolStats();
                if(blockerStats != null) {
                    diag("[minebot-diag] placeSupport blocker's own Defer pool: %s", blockerStats);
                }
                String trace = gui.map.placingLoadTrace();
                if(trace != null) {
                    diag("[minebot-diag] placeSupport stall stack trace:%n%s", trace);
                }
                if(blockerStats != null) {
                    // The pool-stats line above showed pool.size()=1 with that one
                    // worker busy and never returning -- a live stack trace of every
                    // "Worker thread #N" (Defer.Worker's own naming) shows what it's
                    // actually stuck doing right now, instead of inferring a hang from
                    // counts alone.
                    dumpWorkerThreadStacks();
                }
                lastState = state;
            }
            if(gui.map.hasActivePlacement()) {return true;}
            long now = System.currentTimeMillis();
            if(now >= deadline) {return false;}
            if(now - lastNudge >= 15000) {
                gui.msg("MiningBot: still waiting for Stone Column placement to finish loading (" + state + ")...", GameUI.MsgType.INFO);
                lastNudge = now;
            }
            BotUtil.pause(500);
        }
    }

    /** Captures the first server info/error toast (if any) within the given window -- a successful, unremarkable action produces none. */
    private static String captureServerMessage(long windowMs) {
        AtomicReference<String> captured = new AtomicReference<>();
        rx.Subscription s1 = Reactor.EMSG.subscribe(captured::set);
        rx.Subscription s2 = Reactor.IMSG.subscribe(captured::set);
        try {
            BotUtil.pause(windowMs);
        } finally {
            s1.unsubscribe();
            s2.unsubscribe();
        }
        return captured.get();
    }

    /** True if `tile` is inside at least one currently-tracked mine-support's coverage (the same live data "Display radius" visualizes). */
    private static boolean isSupported(GameUI gui, Coord tile) {
        Global cov = Global.get(gui.ui.sess.glob);
        Data dat = cov.dat;
        if(dat == null || !dat.area.contains(tile)) {return false;}
        return dat.cc[dat.area.ridx(tile)] > 0;
    }

    private interface Poll {boolean test();}

    private static boolean waitFor(Bot bot, long timeoutMs, Poll condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while(!condition.test()) {
            if(System.currentTimeMillis() >= deadline) {return false;}
            bot.checkCancelled();
            BotUtil.pause(100);
        }
        return true;
    }
}
