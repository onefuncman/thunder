package auto;

import haven.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static haven.Equipory.SLOTS.*;

/**
 * Finds dead animal corpses within CORPSE_RADIUS_TILES of the player, equips the
 * player's best cutting tool (from an equipped hand, belt, or main inventory),
 * walks to and fully processes each corpse in turn (skin/clean/butcher/collect
 * bones, whichever flower-menu options the corpse actually offers), then restores
 * the hand slot to exactly how it was found.
 *
 * See docs/auto-butcher.md for the full design rationale and the research behind
 * the tool list and flower-menu option names below -- none of it is guessed.
 */
public class ButcherBot {
    private static final int CORPSE_RADIUS_TILES = 10;
    // Each real action (Skin/Clean/Butcher/Collect Bones) is a single selection
    // from the flower menu; the safety net just covers the odd re-click, not
    // genuine per-stage repetition (see ACTION_PRIORITY note below).
    private static final int MAX_STEPS_PER_CORPSE = 10;
    // The very first right-click on a corpse has to cover both the native
    // walk-there-then-interact (see the note on FIRST_MENU_TIMEOUT_MS below) and
    // the menu actually opening, so it gets a much longer allowance than every
    // later re-click on the same (now-adjacent) corpse.
    private static final long FIRST_MENU_TIMEOUT_MS = 12000;
    private static final long MENU_TIMEOUT_MS = 3000;
    private static final long PROGRESS_START_TIMEOUT_MS = 3000;
    private static final long PROGRESS_FINISH_TIMEOUT_MS = 60000;
    // After one loading-bar fill clears, how long to watch for the next one
    // (same stage, next product) before concluding the whole stage is actually
    // done -- see waitActionFinished's doc comment. Sized off the ArdClient
    // fork's own Butcher.PBot script: its PBotUtils.waitForHourglass polls the
    // "still active" check every 50ms and, per direct confirmation, has never
    // once left a stage incomplete -- which only makes sense if the real
    // fill-to-fill gap is shorter than 50ms, since a slower poll would just step
    // over it and treat consecutive fills as one continuous bar. Our own poll
    // here runs every 10ms (fine enough to have actually measured 6 distinct
    // fills on one boar), so this is set just above their proven-reliable 50ms
    // rather than guessed independently. Under-guessing still degrades
    // gracefully (processCorpse just reselects the same still-offered stage,
    // one extra menu round-trip) if the true gap ever turns out longer.
    private static final long PROGRESS_QUIET_MS = 75;
    // Collect Bones has no loading bar at all -- confirmed, it collects every
    // bone at once, instantly, the moment it's chosen -- so instead of waiting on
    // a progress bar that will never appear, just poll briefly for the corpse
    // itself to be consumed.
    private static final long BONES_TIMEOUT_MS = 2000;
    // Settle gap between one confirmed-finished action and the next right-click --
    // purely cosmetic pacing, not part of completion detection, so safe to shrink.
    private static final long SETTLE_MS = 50;

    /* All 11 "Sharp Tool" resource IDs, cross-verified against this repo's own
     * i10n/Equip.java strings and two independent open-source H&H clients
     * (Moonflower, nurgling2) rather than guessed -- see docs/auto-butcher.md. */
    private static final String[] CUTTING_TOOL_TYPES = {
        "/stoneaxe", "/woodsmansaxe", "/axe-m", "/tinkersthrowingaxe",
        "/butcherscleaver", "/ceramicknife", "/flintknife", "/obsidiandagger",
        "/bronzesword", "/fyrdsword", "/hirdsword",
    };

    /* Real flower-menu option names for corpse processing, confirmed via
     * Moonflower's SharpToolAutoManager.isProcessingOption (an independently
     * built, working implementation of this exact mechanic) -- not guessed.
     * Order is priority: skin before butcher or the hide is ruined (per Ring of
     * Brodgar wiki), clean/gut/pluck/scale before the final butcher step, bones
     * collected last.
     *
     * Each stage is selected from the flower menu EXACTLY ONCE per animal --
     * confirmed directly. What happens after that single selection differs by
     * stage: Skin/Clean/Butcher each fill their loading bar/hourglass
     * automatically, over and over, once per individual product the animal
     * yields (a hide, then another hide; one batch of intestines, then another;
     * one cut of meat, then the next), entirely without further input, until
     * every unit of that stage's product has been collected -- so the NUMBER of
     * fills varies by animal (how much it yields), but the number of flower-menu
     * SELECTIONS never does. Collect Bones has no loading bar at all: choosing it
     * collects every bone at once, instantly. `waitActionFinished` (Skin/Clean/
     * Butcher) and the bones-specific disposal poll in `processCorpse` exist
     * specifically to wait out however many fills a given animal needs before
     * ever going back to the flower menu, rather than re-selecting the same
     * stage repeatedly. */
    private static final String[] ACTION_PRIORITY = {
        "Skin", "Flay", "Clean", "Gut", "Pluck", "Scale", "Butcher",
        "Collect Bones", "Gather Bones", "Take Bones",
    };

    private static final String[] BONES_NAMES = {"Collect Bones", "Gather Bones", "Take Bones"};

    /* Items that can't be unequipped -- mirrors Equip.FORBIDDEN (private there). */
    private static final String[] FORBIDDEN = {
        "/bucket", "/pickingbasket", "/splint",
    };

    private ButcherBot() {}

    public static void run(GameUI gui) {
        gui.msg("Auto-Butcher: initiating.", GameUI.MsgType.INFO);
        Bot.execute((t, bot) -> {
            List<Gob> corpses = findCorpses(gui);
            if(corpses.isEmpty()) {
                bot.cancel("Auto-Butcher: no processable corpses detected within range.");
                return;
            }

            ToolSwap swap = equipBestTool(gui, bot);
            if(swap == null) {return;} // equipBestTool already cancelled with a reason

            int processed = 0;
            try {
                for(Gob corpse : corpses) {
                    bot.checkCancelled();
                    if(corpse.disposed()) {continue;}
                    processCorpse(gui, bot, corpse);
                    processed++;
                }
            } catch(InterruptedException e) {
                bot.cancel("Auto-Butcher: operation interrupted before completion; hand contents restored.");
                throw e;
            } finally {
                // Guaranteed to run even on cancellation or an exception -- Bot's own
                // cleanup(...) list does NOT run in either case (see Bot.call()), so
                // restoration must live in a real try/finally, not Bot.cleanup().
                swap.restore(gui);
            }
            gui.msg("Auto-Butcher: operation complete -- " + processed + " corpse(s) processed.", GameUI.MsgType.INFO);
        }).start(gui.ui);
    }

    private static List<Gob> findCorpses(GameUI gui) {
        double radius = CORPSE_RADIUS_TILES * MCache.tilesz.x;
        // Wild animals reduced to 0 HP go into the "/knock" pose (GobTag.KO)
        // permanently -- there's no separate "/dead" pose for them, confirmed live
        // (a knocked bear's flower menu already offers Skin/Clean/Butcher). GobTag.DEAD
        // ("/dead"/"/waterdead") is kept too since it's the correct pose for other
        // cases (e.g. GobTag.java's own AGGRO_TARGET check already treats KO and DEAD
        // as equivalent "not a valid living target" states).
        return gui.ui.sess.glob.oc.stream()
            .filter(g -> PositionHelper.distanceToPlayer(g) <= radius)
            .filter(g -> g.is(GobTag.ANIMAL) && (g.is(GobTag.DEAD) || g.is(GobTag.KO)))
            .sorted(PositionHelper.byDistanceToPlayer)
            .collect(Collectors.toList());
    }

    //////////////////////////////////////////////////////////////////////
    // Tool swap + restore
    //////////////////////////////////////////////////////////////////////

    private static final Predicate<WItem> IS_CUTTING_TOOL = InvHelper.ofType(CUTTING_TOOL_TYPES);

    private static List<InvHelper.ContainedItem> allTools(GameUI gui) {
        return Stream.of(InvHelper.INVENTORY_CONTAINED(gui), InvHelper.BELT_CONTAINED(gui), InvHelper.HANDS_CONTAINED(gui))
            .flatMap(supplier -> supplier.get().stream())
            .filter(c -> IS_CUTTING_TOOL.test(c.item))
            .collect(Collectors.toList());
    }

    private static double safeQuality(WItem item) {
        try {
            return item.quality();
        } catch(Loading l) {
            return 0;
        }
    }

    private static InvHelper.ContainedItem pickBest(List<InvHelper.ContainedItem> tools) {
        InvHelper.ContainedItem best = null;
        double bestQuality = -1;
        for(InvHelper.ContainedItem c : tools) {
            double q = safeQuality(c.item);
            if(best == null || q > bestQuality) {
                best = c;
                bestQuality = q;
            }
        }
        return best;
    }

    /**
     * Equips the single best cutting tool into a hand. Also clears any OTHER
     * cutting-tool-capable item out of both hands entirely first -- the game has
     * no logic to prefer one hand's tool over the other's when both qualify, so
     * a worse tool left in the off hand could silently get used instead of the
     * one just picked (e.g. a quality-10 sword left equipped in the right hand
     * while a quality-15 axe goes into the left). Non-cutting items (a shield,
     * etc.) are left alone unless they're physically occupying the hand the
     * tool itself needs to go into. Displaced items always prefer the belt over
     * main inventory when parked (findStashTarget), matching where a player
     * would expect their gear to land.
     */
    private static ToolSwap equipBestTool(GameUI gui, Bot bot) throws InterruptedException {
        InvHelper.ContainedItem best = pickBest(allTools(gui));
        if(best == null) {
            bot.cancel("Auto-Butcher: no suitable cutting tool located.");
            return null;
        }

        Equipory equipory = gui.equipory;
        WItem leftHand = equipory.slot(HAND_LEFT);
        WItem rightHand = equipory.slot(HAND_RIGHT);

        Equipory.SLOTS bestHand = null;
        if(leftHand != null && leftHand.item == best.item.item) {bestHand = HAND_LEFT;}
        else if(rightHand != null && rightHand.item == best.item.item) {bestHand = HAND_RIGHT;}

        // Any hand other than the one already holding `best` gets cleared if it
        // holds a *different* cutting tool -- it can't be used for anything useful
        // there and risks the server using it instead of the one we're choosing.
        List<Equipory.SLOTS> toClear = new ArrayList<>();
        if(bestHand != HAND_LEFT && leftHand != null && IS_CUTTING_TOOL.test(leftHand)) {toClear.add(HAND_LEFT);}
        if(bestHand != HAND_RIGHT && rightHand != null && IS_CUTTING_TOOL.test(rightHand)) {toClear.add(HAND_RIGHT);}

        boolean needEquip = bestHand == null;
        Equipory.SLOTS targetHand = bestHand;
        if(needEquip) {
            boolean leftFree = leftHand == null || toClear.contains(HAND_LEFT);
            boolean rightFree = rightHand == null || toClear.contains(HAND_RIGHT);
            if(leftFree) {
                targetHand = HAND_LEFT;
            } else if(rightFree) {
                targetHand = HAND_RIGHT;
            } else {
                // Both hands hold something and neither is a cutting tool (e.g. two
                // shields) -- have to displace one to make room. HAND_LEFT, matching
                // the convention the rest of the codebase's Equip.* helpers use.
                targetHand = HAND_LEFT;
                toClear.add(HAND_LEFT);
            }
        }

        for(Equipory.SLOTS hand : toClear) {
            WItem item = equipory.slot(hand);
            if(item != null && GobTag.ofType(item.item.resname(), FORBIDDEN)) {
                bot.cancel("Auto-Butcher: item in " + (hand == HAND_LEFT ? "left" : "right") + " hand cannot be unequipped.");
                return null;
            }
        }

        // Check total room *before* touching anything, so a "no room" failure never
        // leaves an item stuck on the cursor partway through clearing hands. Sized
        // by each item's real grid footprint (WItem.lsz), not a flat 1 square per
        // item -- a two-square item (e.g. a sword) needs two free squares, and
        // undercounting could pick a target coordinate that doesn't actually fit it.
        int neededSquares = 0;
        for(Equipory.SLOTS hand : toClear) {
            WItem item = equipory.slot(hand);
            if(item != null) {neededSquares += item.lsz.x * item.lsz.y;}
        }
        int belt = beltFreeSlots(gui);
        int inv = gui.maininv != null ? gui.maininv.free() : 0;
        if(neededSquares > belt + inv) {
            bot.cancel("Auto-Butcher: insufficient storage space to proceed.");
            return null;
        }

        List<HandStash> displaced = new ArrayList<>();
        for(Equipory.SLOTS hand : toClear) {
            WItem item = equipory.slot(hand);
            if(item == null) {continue;}
            Coord size = item.lsz; // captured before take() -- the WItem is disposed once picked up
            item.take();
            BotUtil.waitHeldChanged(gui);
            Stash stash = findStashTarget(gui, size);
            if(stash == null) {
                // Shouldn't happen given the free-slot count check above, but never
                // leave an item stuck on the cursor if it somehow does.
                equipory.sendDrop(hand);
                BotUtil.waitHeldChanged(gui);
                bot.cancel("Auto-Butcher: storage allocation error.");
                return null;
            }
            stash.inv.wdgmsg("drop", stash.c);
            BotUtil.waitHeldChanged(gui);
            displaced.add(new HandStash(hand, stash));
        }

        if(needEquip) {
            best.take();
            BotUtil.waitHeldChanged(gui);
            equipory.sendDrop(targetHand);
            BotUtil.waitHeldChanged(gui);
        }

        if(!needEquip && displaced.isEmpty()) {
            return ToolSwap.noop();
        }
        return new ToolSwap(needEquip ? best : null, targetHand, displaced);
    }

    /** A belt pouch's contents widget is a plain `Inventory` for an ordinary
     * pouch, but a fancier `ExtInventory` wrapper for some special/premium belts
     * (confirmed live: an "Exquisite Belt" reports `contents=ExtInventory`) --
     * `ExtInventory.inventory(Widget)` (already used the same way in
     * `MiningMaterials.java`) unwraps either case to the real `Inventory` grid. */
    private static Inventory beltInventory(GameUI gui) {
        WItem beltItem = gui.equipory.slot(BELT);
        return beltItem == null ? null : ExtInventory.inventory(beltItem.item.contents);
    }

    private static int beltFreeSlots(GameUI gui) {
        Inventory belt = beltInventory(gui);
        return belt != null ? belt.free() : 0;
    }

    /** Belt first, then main inventory -- matches where a player would expect a
     * displaced item to land, and is checked fresh against live state every call
     * so back-to-back stashes never collide on the same grid square. `size` is the
     * item's real grid footprint (WItem.lsz) -- searching with a hardcoded 1x1
     * could return a coordinate with room for only part of a bigger item (e.g. a
     * two-square sword), which the server would then have to silently reject or
     * relocate, undermining the whole point of tracking where it landed. */
    private static Stash findStashTarget(GameUI gui, Coord size) {
        Inventory belt = beltInventory(gui);
        if(belt != null) {
            Coord c = belt.findPlaceFor(size);
            if(c != null) {return new Stash(belt, c);}
        }
        if(gui.maininv != null) {
            Coord c = gui.maininv.findPlaceFor(size);
            if(c != null) {return new Stash(gui.maininv, c);}
        }
        return null;
    }

    private static WItem findAt(Inventory inv, Coord c) {
        for(WItem w : inv.children(WItem.class)) {
            if(w.c.sub(1, 1).div(Inventory.sqsz).equals(c)) {return w;}
        }
        return null;
    }

    private static final class Stash {
        final Inventory inv;
        final Coord c;

        Stash(Inventory inv, Coord c) {
            this.inv = inv;
            this.c = c;
        }
    }

    private static final class HandStash {
        final Equipory.SLOTS hand;
        final Stash stash;

        HandStash(Equipory.SLOTS hand, Stash stash) {
            this.hand = hand;
            this.stash = stash;
        }
    }

    private static final class ToolSwap {
        private final InvHelper.ContainedItem tool; // null if the tool was never moved (already correctly equipped)
        private final Equipory.SLOTS toolHand; // hand the tool was placed in; only meaningful if tool != null
        private final List<HandStash> displaced; // items pulled from hands, to restore

        private ToolSwap(InvHelper.ContainedItem tool, Equipory.SLOTS toolHand, List<HandStash> displaced) {
            this.tool = tool;
            this.toolHand = toolHand;
            this.displaced = displaced;
        }

        static ToolSwap noop() {
            return new ToolSwap(null, null, Collections.emptyList());
        }

        /** Best-effort restore -- reports a message rather than throwing, since this
         * always runs from a finally block where the run is already ending. */
        void restore(GameUI gui) {
            if(tool == null && displaced.isEmpty()) {return;}
            try {
                Equipory equipory = gui.equipory;
                if(tool != null) {
                    WItem equipped = equipory.slot(toolHand);
                    if(equipped == null) {
                        gui.msg("Auto-Butcher: cutting tool was no longer in hand when restoring -- check your belt/inventory.", GameUI.MsgType.BAD);
                    } else {
                        equipped.take();
                        BotUtil.waitHeldChanged(gui);
                        tool.putBack();
                        BotUtil.waitHeldChanged(gui);
                    }
                }
                for(HandStash hs : displaced) {
                    WItem parked = findAt(hs.stash.inv, hs.stash.c);
                    if(parked == null) {
                        gui.msg("Auto-Butcher: could not find an original hand item to restore it -- check your belt/inventory.", GameUI.MsgType.BAD);
                        continue;
                    }
                    parked.take();
                    BotUtil.waitHeldChanged(gui);
                    equipory.sendDrop(hs.hand);
                    BotUtil.waitHeldChanged(gui);
                }
            } catch(Exception e) {
                gui.msg("Auto-Butcher: failed to fully restore your hands: " + e.getMessage(), GameUI.MsgType.BAD);
            }
        }
    }

    //////////////////////////////////////////////////////////////////////
    // Per-corpse flower-menu processing -- rudimentary pathfinding, deliberately:
    // see docs/auto-butcher.md
    //////////////////////////////////////////////////////////////////////

    /** Case/hyphen/whitespace-insensitive comparison for flower-menu option names --
     * mirrors the normalization Moonflower's own SharpToolAutoManager.isProcessingOption
     * already does, since the exact display casing isn't guaranteed and an exact-case
     * `.equals()` would silently skip a real option (e.g. "Collect bones" vs "Collect Bones"). */
    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('-', ' ').trim();
    }

    /**
     * Waits out a full Skin/Clean/Butcher stage: one flower-menu selection, then
     * however many automatic loading-bar fills the animal actually needs for
     * that stage (one hide, then another; one batch of intestines, then
     * another; one cut of meat, then the next -- confirmed directly, this is
     * the real mechanic, not a guess). After each fill clears, this watches for
     * PROGRESS_QUIET_MS with no new fill starting before concluding the stage
     * is genuinely done; if another fill starts within that window it waits
     * that one out too and checks again. Only pays the quiet-window cost once,
     * at the very end -- it breaks out immediately the instant a real next
     * fill does start, so an animal needing many fills doesn't pay it many
     * times, only whatever the true gaps between fills happen to be.
     */
    private static boolean waitActionFinished(GameUI gui, Bot bot) throws InterruptedException {
        long startDeadline = System.currentTimeMillis() + PROGRESS_START_TIMEOUT_MS;
        while(gui.prog == null) {
            if(System.currentTimeMillis() > startDeadline) {return false;}
            bot.checkCancelled();
            BotUtil.pause(10);
        }
        long overallDeadline = System.currentTimeMillis() + PROGRESS_FINISH_TIMEOUT_MS;
        while(true) {
            while(gui.prog != null) {
                if(System.currentTimeMillis() > overallDeadline) {return false;}
                bot.checkCancelled();
                BotUtil.pause(10);
            }
            long quietDeadline = System.currentTimeMillis() + PROGRESS_QUIET_MS;
            boolean nextFillStarted = false;
            while(System.currentTimeMillis() < quietDeadline) {
                if(gui.prog != null) {nextFillStarted = true; break;}
                bot.checkCancelled();
                BotUtil.pause(10);
            }
            if(!nextFillStarted) {return true;}
            if(System.currentTimeMillis() > overallDeadline) {return false;}
            // another fill started automatically -- loop back and wait it out too
        }
    }

    /** Collect Bones has no loading bar -- confirmed, it collects every bone at
     * once, instantly, the moment it's chosen -- so there's nothing to wait on;
     * just give the corpse's disposal message a short moment to arrive rather
     * than waiting on a progress bar that will never appear. */
    private static void waitBonesFinished(Bot bot, Gob corpse) throws InterruptedException {
        long deadline = System.currentTimeMillis() + BONES_TIMEOUT_MS;
        while(!corpse.disposed() && System.currentTimeMillis() < deadline) {
            bot.checkCancelled();
            BotUtil.pause(10);
        }
    }

    private static boolean isBonesOption(String name) {
        for(String bones : BONES_NAMES) {
            if(normalize(bones).equals(normalize(name))) {return true;}
        }
        return false;
    }

    private static void processCorpse(GameUI gui, Bot bot, Gob corpse) throws InterruptedException {
        for(int i = 0; i < MAX_STEPS_PER_CORPSE; i++) {
            bot.checkCancelled();
            if(corpse.disposed()) {return;}

            Set<Widget> before = new HashSet<>();
            collectWidgets(gui.ui.root, before);
            // No explicit pre-walk: right-clicking a gob triggers the same native
            // walk-there-then-interact the game already does for a manual right-click
            // (Gob.rclick -> MapView.click(Gob,...)), so it correctly accounts for
            // real interaction range regardless of mount/hitbox size -- a fixed
            // GOB_ARRIVE_RADIUS pre-walk check doesn't (confirmed live: it wrongly
            // timed out while mounted, since a horse's larger collision keeps the
            // rider further from the corpse's center than that fixed radius allowed,
            // even though the corpse was fully interactable). The first click per
            // corpse gets a much longer timeout to cover that implicit walk.
            long timeout = (i == 0) ? FIRST_MENU_TIMEOUT_MS : MENU_TIMEOUT_MS;
            corpse.rclick(0);

            FlowerMenu menu = null;
            long deadline = System.currentTimeMillis() + timeout;
            while(System.currentTimeMillis() < deadline) {
                bot.checkCancelled();
                menu = findNewWidget(gui.ui.root, FlowerMenu.class, before);
                if(menu != null) {break;}
                BotUtil.pause(100);
            }
            if(menu == null) {return;} // corpse fully processed, or unreachable -- move on

            FlowerMenu.Petal chosen = null;
            for(String name : ACTION_PRIORITY) {
                for(FlowerMenu.Petal p : menu.opts) {
                    if(normalize(name).equals(normalize(p.name))) {chosen = p; break;}
                }
                if(chosen != null) {break;}
            }
            if(chosen == null) {
                menu.choose(null);
                return;
            }

            menu.choose(chosen);
            if(isBonesOption(chosen.name)) {
                waitBonesFinished(bot, corpse);
            } else {
                waitActionFinished(gui, bot);
            }
            BotUtil.pause(SETTLE_MS);
        }
        gui.msg("Auto-Butcher: a corpse could not be fully processed and was skipped.", GameUI.MsgType.BAD);
    }

    /** Recursively collects every widget in a subtree (not just direct children).
     * Same pattern as MiningMaterials.collectWidgets, duplicated locally rather
     * than shared -- both copies are ~10 lines and MiningMaterials is live
     * production bot code not worth touching for this. */
    private static void collectWidgets(Widget parent, Set<Widget> out) {
        for(Widget w = parent.lchild; w != null; w = w.prev) {
            out.add(w);
            collectWidgets(w, out);
        }
    }

    /** Recursively finds the first widget of type `type` under `root` not already in `before`. */
    private static <T extends Widget> T findNewWidget(Widget root, Class<T> type, Set<Widget> before) {
        for(Widget w = root.lchild; w != null; w = w.prev) {
            if(type.isInstance(w) && !before.contains(w)) {return type.cast(w);}
            T found = findNewWidget(w, type, before);
            if(found != null) {return found;}
        }
        return null;
    }
}
