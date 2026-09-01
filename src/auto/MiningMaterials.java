package auto;

import haven.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static auto.GobHelper.gobIs;

/**
 * Material classification + on-the-fly logistics for MiningBot: building
 * stone vs ore, hard metal bars (Bronze / Wrought Iron -- explicitly NOT
 * plain Iron), and picking up loose stone dropped on the ground.
 */
public class MiningMaterials {
    private MiningMaterials() {}

    // User-confirmed, exhaustive list: every rock-type item name H&H produces from
    // mining, minus the 18 ore types below. Any of these count as "stone" for
    // support-building purposes.
    private static final Set<String> STONE_NAMES = new HashSet<>(Arrays.asList(
        "Alabaster", "Apatite", "Arkose", "Basalt", "Bat Rock", "Black Coal", "Breccia",
        "Cat Gold", "Chert", "Diabase", "Diorite", "Dolomite", "Dross", "Eclogite",
        "Feldspar", "Flint", "Fluorospar", "Gabbro", "Gneiss", "Granite", "Graywacke",
        "Greenschist", "Hornblende", "Jasper", "Korund", "Kyanite", "Lava Rock",
        "Limestone", "Marble", "Mica", "Microlite", "Obsidian", "Olivine", "Orthoclase",
        "Pegmatite", "Porphyry", "Pumice", "Quarryartz", "Quartz", "Rhyolite",
        "Rock Crystal", "Rock Salt", "Sandstone", "Schist", "Serpentine",
        "Shard of Conch", "Slag", "Slate", "Soapstone", "Sodalite", "Sunstone", "Zincspar"
    ));

    // Same rock-type family, but these are ore -- must NOT be used for supports, and
    // should be dropped/discarded rather than counted toward the stone minimum.
    private static final Set<String> ORE_NAMES = new HashSet<>(Arrays.asList(
        "Black Ore", "Bloodstone", "Cassiterite", "Chalcopyrite", "Cinnabar", "Direvein",
        "Galena", "Heavy Earth", "Horn Silver", "Iron Ochre", "Lead Glance", "Leaf Ore",
        "Malachite", "Meteorite", "Peacock Ore", "Schrifterz", "Silvershine", "Wine Glance"
    ));

    // Confirmed via live inventory tooltip: H&H uses "Bar of <Material>", not "<Material> Bar".
    private static final String[] HARD_BAR_NAMES = {"Bar of Bronze", "Bar of Wrought Iron"};

    public static boolean isBuildingStone(WItem w) {
        return STONE_NAMES.contains(itemName(w.item));
    }

    public static boolean isOre(WItem w) {
        return ORE_NAMES.contains(itemName(w.item));
    }

    public static boolean isHardBar(WItem w) {
        String name = itemName(w.item);
        if(name == null) {return false;}
        for(String n : HARD_BAR_NAMES) {
            if(n.equals(name)) {return true;}
        }
        return false;
    }

    private static final String STACK_SUFFIX = ", stack of";

    /* Matches ItemAutoDrop.name()'s normalization: a stacked item's display name is
     * "<Name>, stack of", not "<Name>" -- strip that suffix so classification and
     * exact-name matching work the same whether or not the item happens to be stacked. */
    private static String itemName(GItem item) {
        try {
            List<ItemInfo> info = item.info();
            if(info == null) {return null;}
            ItemInfo.Name name = ItemInfo.find(ItemInfo.Name.class, info);
            if(name == null || name.original == null) {return null;}
            String n = name.original;
            return n.endsWith(STACK_SUFFIX) ? n.substring(0, n.length() - STACK_SUFFIX.length()) : n;
        } catch (Loading l) {
            return null;
        }
    }

    /* Scans every personal storage location (matches AutoDrink.countDrinks'
     * HANDS/POUCHES/INVENTORY/BELT sweep), and unstacked -- a pile of same-type
     * items groups into a single "stack" WItem wrapping the real items as
     * children (stack.item.contents), and without unstacking first, every check
     * here would only ever see the wrapper, never the actual rock-type items
     * inside it. Actions.fuelWith already establishes this exact pattern
     * (unstacked(INVENTORY(gui))) -- this was the real bug, not storage location. */
    private static Stream<WItem> personalItems(GameUI gui) {
        return Stream.of(
            InvHelper.unstacked(InvHelper.HANDS(gui)).get().stream(),
            InvHelper.unstacked(InvHelper.POUCHES(gui)).get().stream(),
            InvHelper.unstacked(InvHelper.INVENTORY(gui)).get().stream(),
            InvHelper.unstacked(InvHelper.BELT(gui)).get().stream()
        ).flatMap(x -> x);
    }

    /* Sums stack quantity, not item-widget count -- a single stacked WItem can
     * represent many units of stone, and .count() alone would undercount it. */
    public static int stoneCount(GameUI gui) {
        return (int) personalItems(gui)
            .filter(MiningMaterials::isBuildingStone)
            .map(w -> w.quantity.get())
            .reduce(0f, Float::sum)
            .floatValue();
    }

    public static boolean hasBar(GameUI gui) {
        return personalItems(gui).anyMatch(MiningMaterials::isHardBar);
    }

    /** Picks up loose stone (any STONE_NAMES rock type) dropped nearby until `need` is reached or none remain in range. */
    public static boolean pickUpLooseStone(GameUI gui, Bot bot, int need) throws InterruptedException {
        while(stoneCount(gui) < need) {
            bot.checkCancelled();
            Gob nearest = gui.ui.sess.glob.oc.stream()
                .filter(gobIs(GobTag.PICKUP))
                .filter(MiningMaterials::looksLikeStone)
                .filter(g -> PositionHelper.distanceToPlayer(g) <= CFG.AUTO_PICK_RADIUS.get())
                .sorted(PositionHelper.byDistanceToPlayer)
                .findFirst().orElse(null);
            if(nearest == null) {return false;}
            new GobTarget(nearest).rclick_shift();
            try {
                nearest.waitRemoval();
            } catch (InterruptedException e) {
                return false; // pickup stalled (out of reach, blocked, etc.) -- bail rather than loop forever
            }
        }
        return true;
    }

    /* Ground-dropped rock/ore item-gobs use resid gfx/terobjs/items/<rockname> in this
     * client family; matching by resid substring against the same confirmed name list
     * (lowercased, spaces stripped) avoids a second, separate name guess. */
    private static boolean looksLikeStone(Gob g) {
        try {
            String id = g.resid();
            if(id == null) {return false;}
            String idLower = id.toLowerCase(java.util.Locale.ROOT);
            for(String name : STONE_NAMES) {
                if(idLower.contains(name.toLowerCase(java.util.Locale.ROOT).replace(" ", ""))) {return true;}
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /* Scans all personal storage (see personalItems) and sums stack quantity,
     * not item-widget count -- see stoneCount's note above. */
    private static int countMatching(GameUI gui, Predicate<WItem> want) {
        return (int) personalItems(gui)
            .filter(want)
            .map(w -> w.quantity.get())
            .reduce(0f, Float::sum)
            .floatValue();
    }

    /**
     * Logs every gob whose tile falls inside `zone` (resid + tag set), unconditionally,
     * every call -- so what a zone actually contains is on record, not guessed at.
     * Directly answered a real question live: a real game "Stockpile" structure isn't
     * in etc/containers.json5, so it never gets tagged GobTag.CONTAINER at all, and
     * fetchFromZone's container search silently skips it. This names the actual
     * resource so containers.json5 can be corrected instead of guessed at a second time.
     */
    private static void logZoneContents(GameUI gui, Area zone) {
        gui.ui.sess.glob.oc.stream()
            .filter(g -> zone.contains(g.rc.floor(MCache.tilesz)))
            .forEach(g -> MiningBot.diag("[minebot-diag] zone content: resid=%s tags=%s", g.resid(), GobTag.tags(g)));
    }

    /**
     * Walks to each GobTag.CONTAINER gob inside `zone` in turn, opens it, and
     * transfers every item matching `want` into main inventory; then, if still
     * short, walks to and picks up every loose GobTag.PICKUP item-gob in the
     * zone in turn (discovered live: a "stockpile zone" is just as likely to be
     * a pile dropped on the ground as a chest, and the container-only search
     * silently found nothing and never even walked). Stops once `need` matching
     * items are on hand or every container and loose item in the zone has been
     * tried. Everything here is called directly (never via
     * Bot.process(...).start(...)) since this runs from inside an
     * already-running MiningBot task -- starting a second Bot would cancel it.
     */
    /** Recursively logs a widget subtree's class names (and Button labels), so an
     * unexpected UI shape (nested panel, nonstandard label, etc.) is on record
     * instead of guessed at from a single failed findButton call. */
    private static void dumpWidgetTree(Widget parent, int depth) {
        StringBuilder indentBuf = new StringBuilder();
        for(int i = 0; i < depth; i++) {indentBuf.append("  ");}
        String indent = indentBuf.toString();
        for(Widget w = parent.lchild; w != null; w = w.prev) {
            String extra = "";
            if(w instanceof Button) {
                Button b = (Button) w;
                extra = " text=\"" + (b.text != null ? b.text.text : null) + "\"";
            }
            MiningBot.diag("[minebot-diag] widget tree: %s%s%s", indent, w.getClass().getSimpleName(), extra);
            dumpWidgetTree(w, depth + 1);
        }
    }

    public static boolean fetchFromZone(GameUI gui, Bot bot, Area zone, Predicate<WItem> want, int need) throws InterruptedException {
        // Logged unconditionally, before the early-return below -- already having
        // enough on hand (e.g. from an earlier test) short-circuited this whole
        // function before the diagnostic ever ran, live, producing an empty log with
        // no clue why. This must never be skippable by already having enough.
        logZoneContents(gui, zone);
        if(countMatching(gui, want) >= need) {return true;}

        List<Gob> containers = gui.ui.sess.glob.oc.stream()
            .filter(gobIs(GobTag.CONTAINER))
            .filter(g -> zone.contains(g.rc.floor(MCache.tilesz)))
            .sorted(PositionHelper.byDistanceToPlayer)
            .collect(Collectors.toList());
        MiningBot.diag("[minebot-diag] fetchFromZone: %d container(s) in zone", containers.size());

        for(Gob container : containers) {
            bot.checkCancelled();
            if(countMatching(gui, want) >= need) {return true;}
            if(container.disposed()) {
                MiningBot.diag("[minebot-diag] fetchFromZone: container %s disposed, skipping", container.resid());
                continue;
            }
            boolean walked = MapHelper.walkTo(gui, container.rc, 6000, MapHelper.GOB_ARRIVE_RADIUS);
            MiningBot.diag("[minebot-diag] fetchFromZone: walkTo container %s -> %b", container.resid(), walked);
            if(!walked) {continue;}
            // walkTo's distance-based "arrived" can fire while the character is still
            // mid-stride (not yet stopped server-side) -- itemact() fired immediately
            // after, with no other action in between to naturally absorb that gap
            // (unlike refillWaterFromZone's take()+waitHeldChanged before its own
            // itemact()), may be rejected/ignored by the server while still moving.
            MiningBot.waitForMovementSettled(gui, bot, 3000);

            Window win = openContainerWindow(gui, bot, container, 3000);
            MiningBot.diag("[minebot-diag] fetchFromZone: openContainerWindow -> %s", win != null ? win.caption() : "null");
            if(win == null) {continue;}

            // Two structurally different UIs share the CONTAINER tag, confirmed live
            // (user description): a crate opens an Inventory grid of real items to
            // shift-click transfer; a Stockpile opens a build-window-style menu with
            // a "Take" button that must be clicked once per unit wanted -- there's no
            // item grid to search at all. Handle whichever one actually appeared.
            Button take = MiningBot.findButton(win, "Take");
            if(take == null) {
                // findButton only checks direct children -- if the real button is
                // nested (a sub-panel, a per-item-type row, etc.) this dump shows the
                // actual tree instead of guessing at another label/depth blind.
                dumpWidgetTree(win, 0);
            }
            if(take != null) {
                int clicked = 0;
                // eatFromZone passes need=Integer.MAX_VALUE ("take everything available") --
                // plain int math here (need - count + 5) overflows to negative in that case,
                // which makes the loop below never run at all (clicked < cap is false from the
                // start) and silently take zero food from a Stockpile-shaped food zone. Long
                // arithmetic + a sane clamp avoids the overflow without capping normal (small
                // need) cases below their real requirement.
                int cap = (int) Math.min(1000, Math.max(0, (long) need - countMatching(gui, want) + 5));
                while(countMatching(gui, want) < need && clicked < cap) {
                    bot.checkCancelled();
                    take.click();
                    clicked++;
                    BotUtil.pause(300);
                }
                MiningBot.diag("[minebot-diag] fetchFromZone: Stockpile-style, clicked Take %d time(s), now %d/%d", clicked, (int) countMatching(gui, want), need);
            } else {
                Inventory inv = null;
                for(Widget wdg = win.lchild; wdg != null; wdg = wdg.prev) {
                    Inventory found = ExtInventory.inventory(wdg);
                    if(found != null) {inv = found; break;}
                }
                MiningBot.diag("[minebot-diag] fetchFromZone: crate-style, inventory widget found=%b", inv != null);
                if(inv != null) {
                    int matched = 0;
                    for(WItem w : inv.children(WItem.class)) {
                        if(want.test(w)) {
                            matched++;
                            w.item.wdgmsg("transfer", Coord.z);
                            BotUtil.pause(300);
                        }
                    }
                    MiningBot.diag("[minebot-diag] fetchFromZone: %d matching item(s) transferred", matched);
                }
            }
            win.reqdestroy();
            BotUtil.pause(200);
        }
        if(countMatching(gui, want) >= need) {return true;}
        MiningBot.diag("[minebot-diag] fetchFromZone: still short after containers (%d/%d), trying loose pickup", (int) countMatching(gui, want), need);
        return pickUpLooseFromZone(gui, bot, zone, want, need);
    }

    /*
     * No reliable ground-gob resid pattern exists for "bar of bronze/wrought iron"
     * (guessing item names from resids has already produced two confirmed-wrong
     * assumptions in this project -- see HARD_BAR_NAMES's own history). Rather than
     * add a third guess, pick up every unclaimed GobTag.PICKUP gob in the zone and
     * classify it AFTER pickup with the same tooltip-name-based `want` predicate the
     * rest of the bot already trusts (isBuildingStone/isHardBar). Safe because the
     * zone is user-designated specifically as a stockpile -- there's no expectation
     * of unrelated loose items sitting in it to accidentally hoard.
     */
    private static boolean pickUpLooseFromZone(GameUI gui, Bot bot, Area zone, Predicate<WItem> want, int need) throws InterruptedException {
        Set<Long> tried = new HashSet<>();
        while(countMatching(gui, want) < need) {
            bot.checkCancelled();
            Gob nearest = gui.ui.sess.glob.oc.stream()
                .filter(gobIs(GobTag.PICKUP))
                .filter(g -> zone.contains(g.rc.floor(MCache.tilesz)))
                .filter(g -> !tried.contains(g.id))
                .sorted(PositionHelper.byDistanceToPlayer)
                .findFirst().orElse(null);
            if(nearest == null) {
                MiningBot.diag("[minebot-diag] pickUpLooseFromZone: no more PICKUP-tagged gobs in zone");
                return countMatching(gui, want) >= need;
            }
            String resid = nearest.resid(); // captured before pickup -- resid() on an already-removed gob is unsafe
            MiningBot.diag("[minebot-diag] pickUpLooseFromZone: trying %s", resid);
            tried.add(nearest.id);
            if(nearest.disposed() || !MapHelper.walkTo(gui, nearest.rc, 6000)) {
                MiningBot.diag("[minebot-diag] pickUpLooseFromZone: couldn't walk to %s, skipping", resid);
                continue;
            }
            new GobTarget(nearest).rclick_shift();
            try {
                nearest.waitRemoval();
                MiningBot.diag("[minebot-diag] pickUpLooseFromZone: picked up %s (matching count now %d)", resid, (int) countMatching(gui, want));
            } catch (InterruptedException e) {
                MiningBot.diag("[minebot-diag] pickUpLooseFromZone: pickup stalled on %s", resid);
                return countMatching(gui, want) >= need; // pickup stalled -- report what we actually got, not a hard failure
            }
        }
        return true;
    }

    /**
     * Detects the container window that just opened by diffing GameUI's children
     * before/after a real left-click on the gob. Traced live: a real click on a gob
     * (Gob.click -> MapView.click) sends wdgmsg("click", ...) -- itemact() sends a
     * DIFFERENT message, wdgmsg("itemact", ...), which is "use the item in hand on
     * this gob," not "open this." That mismatch (an unverified assumption from
     * early planning, never checked against what a real click actually sends) is
     * why nothing ever opened for either the stockpile or a completely standard
     * crate.
     *
     * Sends the wire message directly rather than through Gob.click/MapView.click:
     * that path, for a plain left click with CFG.QUEUE_PATHS on (the default),
     * ALSO calls ui.gui.pathQueue.start(mc) as a side effect -- restarting movement
     * toward the container's own (collision-blocked, unreachable) center the
     * instant the click fires, undoing waitForMovementSettled's work immediately
     * before the server sees the interact request. This sends the identical
     * wdgmsg("click", ...) content MapView.click ultimately sends, minus that
     * side effect.
     */
    private static Window openContainerWindow(GameUI gui, Bot bot, Gob container, long timeoutMs) throws InterruptedException {
        java.util.Set<Widget> before = new java.util.HashSet<>();
        for(Widget w = gui.lchild; w != null; w = w.prev) {before.add(w);}
        // User-confirmed: both a crate and a stockpile are opened with a RIGHT click
        // (button 3), not left -- left click was never going to open either one,
        // independent of every other fix so far. FlowerMenu.lastGob mirrors the one
        // other side effect MapView.click(Gob,...) has for button==3 (bookkeeping
        // for a possible radial-menu response); harmless if unneeded here.
        FlowerMenu.lastGob(container);
        Coord mc = container.rc.floor(OCache.posres);
        gui.map.wdgmsg("click", Coord.z, mc, 3, gui.ui.modflags(), 0, (int) container.id, mc, 0, -1);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while(System.currentTimeMillis() < deadline) {
            bot.checkCancelled();
            for(Widget w = gui.lchild; w != null; w = w.prev) {
                if((w instanceof Window) && !before.contains(w)) {return (Window) w;}
            }
            BotUtil.pause(100);
        }
        MiningBot.diag("[minebot-diag] openContainerWindow: no new window appeared within %dms after click on %s", timeoutMs, container.resid());
        return null;
    }

    /** Refills every water-holding container the player carries from a barrel found inside `zone`. */
    public static boolean refillWaterFromZone(GameUI gui, Bot bot, Area zone) throws InterruptedException {
        logZoneContents(gui, zone);
        Gob barrel = gui.ui.sess.glob.oc.stream()
            .filter(gobIs(GobTag.HAS_WATER))
            .filter(g -> zone.contains(g.rc.floor(MCache.tilesz)))
            .sorted(PositionHelper.byDistanceToPlayer)
            .findFirst().orElse(null);
        if(barrel == null) {
            MiningBot.diag("[minebot-diag] refillWaterFromZone: no GobTag.HAS_WATER gob in zone");
            return false;
        }
        boolean walked = MapHelper.walkTo(gui, barrel.rc, 6000, MapHelper.GOB_ARRIVE_RADIUS);
        MiningBot.diag("[minebot-diag] refillWaterFromZone: found barrel %s, walkTo -> %b", barrel.resid(), walked);
        if(!walked) {return false;}

        List<InvHelper.ContainedItem> targets = Stream.of(
                InvHelper.POUCHES_CONTAINED(gui).get().stream().filter(InvHelper::isDrinkContainer),
                InvHelper.INVENTORY_CONTAINED(gui).get().stream().filter(InvHelper::isDrinkContainer),
                InvHelper.BELT_CONTAINED(gui).get().stream().filter(InvHelper::isDrinkContainer),
                InvHelper.HANDS_CONTAINED(gui).get().stream().filter(InvHelper::isBucket)
            ).flatMap(x -> x)
            // canBeFilledWith requires the container to ALREADY contain water
            // (item.contains.get().is("Water")) to count as fillable -- a fully empty
            // container's contents are ItemData.Content.EMPTY (name == null), so
            // .is(...) is always false there, meaning an empty waterskin (the exact
            // case this exists to handle) never qualified. isNotFull only checks for
            // remaining capacity, which is the actual condition that matters here.
            .filter(InvHelper::isNotFull)
            .collect(Collectors.toList());
        MiningBot.diag("[minebot-diag] refillWaterFromZone: %d not-full drink container(s) found on person", targets.size());

        for(InvHelper.ContainedItem ci : targets) {
            bot.checkCancelled();
            ContainedTarget target = new ContainedTarget(ci);
            target.take();
            boolean tookOk = BotUtil.waitHeldChanged(gui, 2000);
            barrel.itemact(UI.MOD_META);
            BotUtil.pause(200);
            target.putBack();
            boolean putBackOk = BotUtil.waitHeldChanged(gui, 2000);
            MiningBot.diag("[minebot-diag] refillWaterFromZone: fill attempt -- take=%b putBack=%b", tookOk, putBackOk);
        }
        return true;
    }

    /**
     * Walks to each GobTag.CONTAINER gob in the food zone and eats directly from
     * its own inventory -- food is never transferred to personal inventory first
     * (user-confirmed: unnecessary, just right-click the item inside the container
     * and choose "Eat" from its flower menu). Stops once the "nrj" meter reaches
     * `untilPercent` or food runs out across every container in the zone.
     *
     * Eating itself goes through a real right-click + flower-menu "Eat" selection
     * (eatViaFlowerMenu), not WItem.itemact(0) -- confirmed live via energy-level
     * logging that itemact(0) (the same call the existing AutoEat feature uses)
     * silently does nothing here: energy sat at the exact same value across 15+
     * consecutive "eat" calls.
     */
    public static boolean eatFromZone(GameUI gui, Bot bot, Area zone, int untilPercent) throws InterruptedException {
        logZoneContents(gui, zone);
        double target = Math.max(0, Math.min(100, untilPercent)) / 100.0;
        MiningBot.diag("[minebot-diag] eatFromZone: target=%.2f", target);

        List<Gob> containers = gui.ui.sess.glob.oc.stream()
            .filter(gobIs(GobTag.CONTAINER))
            .filter(g -> zone.contains(g.rc.floor(MCache.tilesz)))
            .sorted(PositionHelper.byDistanceToPlayer)
            .collect(Collectors.toList());
        MiningBot.diag("[minebot-diag] eatFromZone: %d container(s) in zone", containers.size());

        int bites = 0;
        for(Gob container : containers) {
            IMeter meter = gui.getIMeter("nrj");
            if(meter != null && meter.meter(0) >= 0 && meter.meter(0) >= target) {break;}
            bot.checkCancelled();
            if(container.disposed()) {continue;}

            boolean walked = MapHelper.walkTo(gui, container.rc, 6000, MapHelper.GOB_ARRIVE_RADIUS);
            MiningBot.diag("[minebot-diag] eatFromZone: walkTo container %s -> %b", container.resid(), walked);
            if(!walked) {continue;}
            MiningBot.waitForMovementSettled(gui, bot, 3000);

            Window win = openContainerWindow(gui, bot, container, 3000);
            MiningBot.diag("[minebot-diag] eatFromZone: openContainerWindow -> %s", win != null ? win.caption() : "null");
            if(win == null) {continue;}

            Inventory inv = null;
            for(Widget wdg = win.lchild; wdg != null; wdg = wdg.prev) {
                Inventory found = ExtInventory.inventory(wdg);
                if(found != null) {inv = found; break;}
            }
            if(inv != null) {
                while(true) {
                    bot.checkCancelled();
                    meter = gui.getIMeter("nrj");
                    if(meter == null) {
                        MiningBot.diag("[minebot-diag] eatFromZone: no nrj meter found");
                        break;
                    }
                    double energy = meter.meter(0);
                    if(energy < 0 || energy >= target) {
                        MiningBot.diag("[minebot-diag] eatFromZone: target reached, energy=%.4f target=%.2f bites=%d", energy, target, bites);
                        break;
                    }
                    Inventory invF = inv;
                    WItem food = invF.children(WItem.class).stream()
                        .filter(w -> ItemData.hasFoodInfo(w.item)).findFirst().orElse(null);
                    if(food == null) {
                        MiningBot.diag("[minebot-diag] eatFromZone: no food left in this container, energy=%.4f bites=%d", energy, bites);
                        break;
                    }
                    MiningBot.diag("[minebot-diag] eatFromZone: energy=%.4f < target=%.2f, eating %s (in container)", energy, target, food.item.resname());
                    boolean chose = eatViaFlowerMenu(gui, bot, food);
                    if(!chose) {
                        MiningBot.diag("[minebot-diag] eatFromZone: eatViaFlowerMenu failed on %s, giving up", food.item.resname());
                        break;
                    }
                    bites++;
                    BotUtil.pause(1200); // eating takes a moment -- avoid re-triggering mid-chew
                }
            }
            win.reqdestroy();
            BotUtil.pause(200);
        }

        IMeter finalMeter = gui.getIMeter("nrj");
        double finalEnergy = finalMeter != null ? finalMeter.meter(0) : -1;
        MiningBot.diag("[minebot-diag] eatFromZone: done, energy=%.4f target=%.2f bites=%d", finalEnergy, target, bites);
        return finalEnergy >= 0 && finalEnergy >= target;
    }

    /** Recursively collects every widget in a subtree (not just direct children). */
    private static void collectWidgets(Widget parent, java.util.Set<Widget> out) {
        for(Widget w = parent.lchild; w != null; w = w.prev) {
            out.add(w);
            collectWidgets(w, out);
        }
    }

    /** Recursively finds the first widget of type `type` under `root` not already in `before`. */
    private static <T extends Widget> T findNewWidget(Widget root, Class<T> type, java.util.Set<Widget> before) {
        for(Widget w = root.lchild; w != null; w = w.prev) {
            if(type.isInstance(w) && !before.contains(w)) {return type.cast(w);}
            T found = findNewWidget(w, type, before);
            if(found != null) {return found;}
        }
        return null;
    }

    /**
     * Right-click a food item (opens a real flower menu, same as a manual player
     * right-click -- confirmed via WItem.mousedown: real right-click sends
     * wdgmsg("iact", ...), which is what WItem.rclick() also sends), then choose
     * the "Eat" option directly via FlowerMenu.choose(Petal) once the menu widget
     * appears. Searched from ui.root, not gui -- FlowerMenu grabs the mouse/
     * keyboard globally (ui.grabmouse/ui.grabkeys in its own added()), which meant
     * it wasn't a child of GameUI at all; searching only gui.lchild found nothing
     * even though the menu was genuinely on screen (user-confirmed live). Returns
     * false (and leaves nothing armed, via choose(null)) if no menu appeared or it
     * had no "Eat" option -- a genuinely different food item or game state, not
     * just a slow response, is more likely at that point than something to retry.
     */
    private static boolean eatViaFlowerMenu(GameUI gui, Bot bot, WItem food) throws InterruptedException {
        Widget root = gui.ui.root;
        java.util.Set<Widget> before = new java.util.HashSet<>();
        collectWidgets(root, before);
        food.rclick();
        long deadline = System.currentTimeMillis() + 3000;
        FlowerMenu menu = null;
        while(System.currentTimeMillis() < deadline) {
            bot.checkCancelled();
            menu = findNewWidget(root, FlowerMenu.class, before);
            if(menu != null) {break;}
            BotUtil.pause(100);
        }
        if(menu == null) {
            MiningBot.diag("[minebot-diag] eatViaFlowerMenu: no flower menu appeared after right-click on %s", food.item.resname());
            return false;
        }
        FlowerMenu.Petal eat = null;
        for(FlowerMenu.Petal p : menu.opts) {
            if("Eat".equals(p.name)) {eat = p; break;}
        }
        if(eat == null) {
            MiningBot.diag("[minebot-diag] eatViaFlowerMenu: no 'Eat' option (options=%s)", java.util.Arrays.toString(menu.options));
            menu.choose(null);
            return false;
        }
        menu.choose(eat);
        return true;
    }
}
