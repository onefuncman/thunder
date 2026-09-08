package thunder.fish;

import auto.Bot;
import haven.*;
import haven.pathfinding.ApproachOnly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fish Spit-Roast Bot: retrieve whole raw fish from a player-designated input
 * area, roast them one at a time on a fireplace's roasting-spit overlay, and
 * deposit the resulting spit-roasted fish into a player-designated output area.
 *
 * Runs on the shared auto.Bot system (one run at a time, cancellable via Stop /
 * Esc), and uses the merged Nav Core (ApproachOnly.approach) for every walk so
 * the target gob's center is never a walking destination.
 */
public class FishSpitRoastBot {
    private static volatile boolean running = false;
    private static Bot active;  // the one running fish bot, for precise Stop semantics
    private static java.io.PrintWriter runLog;
    private static final long CURSOR_TIMEOUT = 5000;
    private static final long LOAD_TIMEOUT = 8000;
    private static final long MENU_TIMEOUT = 4000;
    private static final long ROAST_TIMEOUT = 60000;
    private static final long CARVE_TIMEOUT = 30000;
    private static final long NO_PROGRESS_GRACE = 8000;
    private static final int LOW_INVENTORY_FREE = 4;

    private FishSpitRoastBot() {}

    public static boolean isRunning() {
        return running;
    }

    public static void start(GameUI gui, Area input, Area fire, Area output, int batch) {
        if (running) {
            gui.error("Fish Spit-Roast Bot is already running -- stop it first.");
            return;
        }
        if (input == null || fire == null || output == null) {
            gui.error("Fish Spit-Roast: all three areas must be set before starting.");
            return;
        }
        if (batch <= 0) {
            gui.error("Fish Spit-Roast: batch size must be positive.");
            return;
        }
        if (gui.menu == null || gui.map == null) {
            gui.error("Fish Spit-Roast: game UI isn't fully loaded yet.");
            return;
        }
        running = true;
        Bot bot = Bot.execute((target, b) -> {
            try {
                openRunLog();
                log("start batch=%d input=%s fire=%s output=%s", batch, areaStr(input), areaStr(fire), areaStr(output));
                new Run(gui, b, input, fire, output, batch).execute();
            } finally {
                running = false;
                clearPaths(gui);
                returnHeldToInventory(gui);
                closeRunLog();
                active = null;
            }
        });
        active = bot;
        bot.start(gui.ui, true);
    }

    public static void stop() {
        Bot b = active;
        if (running && b != null) {
            b.cancel("Stopped by user.");
        }
    }

    private static String areaStr(Area a) {
        return a == null ? "null" : (a.ul + "-" + a.br);
    }

    private static void openRunLog() {
        try {
            java.nio.file.Path dir = Debug.somedir("fishbot-logs");
            dir.toFile().mkdirs();
            // Single fixed file, truncated on each run so logs never accumulate.
            runLog = new java.io.PrintWriter(new java.io.FileWriter(dir.resolve("fishbot.log").toFile(), false), true);
        } catch (Exception e) {
            runLog = null;
        }
    }

    private static void closeRunLog() {
        if (runLog != null) {
            runLog.close();
            runLog = null;
        }
    }

    private static void log(String fmt, Object... args) {
        if (runLog != null) {
            runLog.println(String.format(fmt, args));
            runLog.flush();
        }
    }

    private static void clearPaths(GameUI gui) {
        try {
            if (gui != null && gui.pathQueue != null) {
                gui.pathQueue.clear();
            }
        } catch (Exception ignored) {}
    }

    /** Best-effort return of any cursor item to main inventory; never drops on the ground. */
    private static void returnHeldToInventory(GameUI gui) {
        try {
            GameUI.DraggedItem drag = gui.hand();
            if (drag == null || drag.item == null) {return;}
            Coord slot = gui.maininv != null ? gui.maininv.findPlaceFor(new Coord(1, 1)) : null;
            if (slot == null) {slot = Coord.z;}
            gui.maininv.wdgmsg("drop", slot);
        } catch (Exception ignored) {}
    }

    /** Internal success/failure signal so the run stops with one clear message. */
    private static final class Abort extends Exception {
        final boolean success;
        Abort(String message, boolean success) {
            super(message);
            this.success = success;
        }
    }

    /** Identity of a roasting spit: the fireplace gob id only. The gob and its
     *  roastspit overlay are re-resolved fresh every access, because the server
     *  can replace the overlay object in-place when content loads. */
    private static final class Spit {
        final long fireId;
        Spit(long fireId) {
            this.fireId = fireId;
        }
    }

    private static final class Run {
        private final GameUI gui;
        private final Bot bot;
        private final Area input;
        private final Area fire;
        private final Area output;
        private final int batch;

        Run(GameUI gui, Bot bot, Area input, Area fire, Area output, int batch) {
            this.gui = gui;
            this.bot = bot;
            this.input = input;
            this.fire = fire;
            this.output = output;
            this.batch = batch;
        }

        void execute() {
            try {
                runLoop();
            } catch (InterruptedException e) {
                log("cancelled");
            } catch (Abort a) {
                log("%s: %s", a.success ? "done" : "fail", a.getMessage());
                if (a.success) {
                    gui.msg("Fish Spit-Roast: " + a.getMessage(), GameUI.MsgType.GOOD);
                } else {
                    gui.error("Fish Spit-Roast: " + a.getMessage());
                }
            }
        }

        private void done(String message) throws Abort {
            throw new Abort(message, true);
        }

        private void fail(String message) throws Abort {
            throw new Abort(message, false);
        }

        private void runLoop() throws InterruptedException, Abort {
            if (input == null || fire == null || output == null) {fail("one or more areas not selected.");}

            int have = countRawFish();
            if (have < batch) {
                have = retrieveFromInput();
            }
            if (have == 0) {fail("no raw fish available.");}

            Spit spit = findSpit();
            if (spit == null) {fail("no roasting spit found in fire area.");}

            // Approach once up front so a spit that already holds a fish can be
            // Turned/Carved before the first loadFish ever runs.
            Gob spitGob = fireGob(spit);
            if (spitGob == null || spitOverlay(spit) == null) {fail("no roasting spit found in fire area.");}
            if (!approachAndSettle(spitGob)) {fail("could not reach fire target.");}

            int completed = 0;
            while (completed < batch) {
                bot.checkCancelled();
                FishRecognition.SpitState state = spitState(spit);
                switch (state) {
                    case UNKNOWN:
                        fail("spit occupied by an unknown item.");
                    case COOKED:
                        carve(spit);
                        completed++;
                        maybeDepositEarly();
                        break;
                    case RAW:
                        turn(spit);
                        break;
                    case EMPTY:
                    default:
                        if (countRawFish() == 0) {
                            depositCooked();
                            done("finished, roasted " + completed + " fish.");
                        }
                        loadFish(spit);
                        break;
                }
            }
            depositCooked();
            done("finished, roasted " + completed + " fish.");
        }

        // ---- Inventory helpers -------------------------------------------------

        private List<WItem> unstack(WItem w) {
            if (w == null) {return Collections.emptyList();}
            Widget contents = w.item.contents;
            if (contents != null) {
                return new ArrayList<>(contents.children(WItem.class));
            }
            return Collections.singletonList(w);
        }

        private List<WItem> personalUnstacked() {
            List<WItem> raw = new ArrayList<>();
            if (gui.maininv != null) {
                for (WItem w : gui.maininv.children(WItem.class)) {raw.add(w);}
            }
            Equipory e = gui.equipory;
            if (e != null) {
                addEquipSlot(e, Equipory.SLOTS.BELT, raw);
                addEquipSlot(e, Equipory.SLOTS.POUCH_LEFT, raw);
                addEquipSlot(e, Equipory.SLOTS.POUCH_RIGHT, raw);
                addEquipSlot(e, Equipory.SLOTS.HAND_LEFT, raw);
                addEquipSlot(e, Equipory.SLOTS.HAND_RIGHT, raw);
            }
            List<WItem> out = new ArrayList<>();
            for (WItem w : raw) {out.addAll(unstack(w));}
            return out;
        }

        private void addEquipSlot(Equipory e, Equipory.SLOTS slot, List<WItem> out) {
            WItem w = e.slots[slot.idx];
            if (w != null && !w.disposed()) {out.add(w);}
        }

        private int countRawFish() {
            int n = 0;
            for (WItem w : personalUnstacked()) {
                if (FishRecognition.isRawFishResname(w.item.resname())) {n++;}
            }
            return n;
        }

        private WItem firstRawFish() {
            for (WItem w : personalUnstacked()) {
                if (FishRecognition.isRawFishResname(w.item.resname())) {return w;}
            }
            return null;
        }

        private int countCookedItems() {
            return collectCookedItems().size();
        }

        private List<WItem> collectCookedItems() {
            List<WItem> out = new ArrayList<>();
            for (WItem w : personalUnstacked()) {
                if (FishRecognition.isCookedDisplayName(w.item.name.get(""))) {out.add(w);}
            }
            return out;
        }

        // ---- Retrieval from input area ----------------------------------------

        private List<Gob> gobsIn(Area area) {
            return gui.ui.sess.glob.oc.stream()
                .filter(g -> g != null && !g.disposed() && g.rc != null)
                .filter(g -> area.contains(g.rc.floor(MCache.tilesz)))
                .collect(Collectors.toList());
        }

        private String residOf(Gob g) {
            try {
                return g.resid();
            } catch (Exception e) {
                return null;
            }
        }

        private boolean isStockpile(String resid) {
            return resid != null && resid.toLowerCase(Locale.ROOT).contains("stockpile");
        }

        private boolean isInputContainer(Gob gob, String resid) {
            if (resid == null) {return false;}
            int p = FishRecognition.outputPriority(resid);
            if (p == FishRecognition.PRIORITY_LARGE_CHEST || p == FishRecognition.PRIORITY_CONTAINER) {return true;}
            return gob.is(GobTag.CONTAINER);
        }

        private int retrieveFromInput() throws InterruptedException, Abort {
            List<Gob> gobs = gobsIn(input);
            if (gobs.isEmpty()) {fail("no recognized fish source in input area.");}
            boolean reached = false;
            for (Gob gob : gobs) {
                bot.checkCancelled();
                if (countRawFish() >= batch) {break;}
                String resid = residOf(gob);
                if (isStockpile(resid)) {
                    if (!approachAndSettle(gob)) {continue;}
                    reached = true;
                    retrieveFromStockpile(gob);
                } else if (isInputContainer(gob, resid)) {
                    if (!approachAndSettle(gob)) {continue;}
                    reached = true;
                    retrieveFromContainer(gob);
                }
            }
            if (!reached) {fail("could not reach input target.");}
            return countRawFish();
        }

        private void retrieveFromStockpile(Gob gob) throws InterruptedException {
            Window win = openContainerWindow(gob, CURSOR_TIMEOUT);
            if (win == null) {return;}
            try {
                for (ISBox box : collectWidgets(win, ISBox.class)) {
                    bot.checkCancelled();
                    if (win.disposed()) {break;}
                    if (countRawFish() >= batch) {break;}
                    if (box.disposed() || !isRawFishBox(box)) {continue;}
                    int need = batch - countRawFish();
                    int rem = remOf(box);
                    int take = rem > 0 ? Math.min(need, rem) : need;
                    if (take <= 0) {continue;}
                    int before = countRawFish();
                    box.transfer(-1, take);
                    waitFor(CURSOR_TIMEOUT, () -> countRawFish() > before || box.disposed() || win.disposed());
                }
            } finally {
                win.reqdestroy();
            }
        }

        private void retrieveFromContainer(Gob gob) throws InterruptedException {
            Window win = openContainerWindow(gob, CURSOR_TIMEOUT);
            if (win == null) {return;}
            try {
                Inventory inv = largestInventory(win);
                if (inv == null) {return;}
                while (countRawFish() < batch) {
                    bot.checkCancelled();
                    WItem fish = firstRawFishIn(inv);
                    if (fish == null) {break;}
                    if (win.disposed()) {break;}
                    int before = countRawFish();
                    final WItem f = fish;
                    f.item.wdgmsg("transfer", Coord.z);
                    waitFor(CURSOR_TIMEOUT, () -> countRawFish() > before || f.disposed());
                    if (countRawFish() <= before && !f.disposed()) {break;}
                }
            } finally {
                win.reqdestroy();
            }
        }

        private WItem firstRawFishIn(Inventory inv) {
            for (WItem w : inv.children(WItem.class)) {
                for (WItem child : unstack(w)) {
                    if (FishRecognition.isRawFishResname(child.item.resname())) {return child;}
                }
            }
            return null;
        }

        private boolean isRawFishBox(ISBox box) {
            try {
                java.lang.reflect.Field f = ISBox.class.getDeclaredField("res");
                f.setAccessible(true);
                Object res = f.get(box);
                if (!(res instanceof Indir)) {return false;}
                Object r = ((Indir<?>) res).get();
                return (r instanceof Resource) && FishRecognition.isRawFishResname(((Resource) r).name);
            } catch (Exception e) {
                return false;
            }
        }

        private int remOf(ISBox box) {
            try {
                java.lang.reflect.Field f = ISBox.class.getDeclaredField("rem");
                f.setAccessible(true);
                return (Integer) f.get(box);
            } catch (Exception e) {
                return -1;
            }
        }

        // ---- Spit detection ----------------------------------------------------

        private Spit findSpit() {
            for (Gob g : gobsIn(fire)) {
                for (Gob.Overlay ol : new ArrayList<>(g.ols)) {
                    if (isRoastspitOverlay(ol)) {
                        return new Spit(g.id);
                    }
                }
            }
            return null;
        }

        private Gob fireGob(Spit spit) {
            return gui.ui.sess.glob.oc.getgob(spit.fireId);
        }

        private Gob.Overlay spitOverlay(Spit spit) {
            Gob g = fireGob(spit);
            if (g == null) {return null;}
            for (Gob.Overlay ol : new ArrayList<>(g.ols)) {
                if (isRoastspitOverlay(ol)) {return ol;}
            }
            return null;
        }

        private boolean isRoastspitOverlay(Gob.Overlay ol) {
            try {
                Sprite spr = ol.spr;
                if (spr == null) {return false;}
                String cn = spr.getClass().getName();
                if (cn.toLowerCase(Locale.ROOT).contains("roastspit")) {return true;}
                return spr.res != null && "gfx/terobjs/roastspit".equals(spr.res.name);
            } catch (Exception e) {
                return false;
            }
        }

        private static final String CONTENT_UNREADABLE = "\u0000unreadable";

        /**
         * Returns the current spit content resource name, or null when the spit is
         * empty. The live server's Roastspit sprite has no {@code getContent()}
         * method (only the Nurgling fork added one); it stores the loaded fish in a
         * private {@code equed} field (a {@code Roastspit$Equed} wrapping the content
         * resource). We try {@code getContent()} first for forward compatibility,
         * then fall back to reading {@code equed}.
         */
        private String spitContentName(Spit spit) {
            Gob.Overlay ol = spitOverlay(spit);
            if (ol == null || ol.spr == null) {return null;}
            Sprite spr = ol.spr;

            try {
                Object c = spr.getClass().getMethod("getContent").invoke(spr);
                if (c != null) {
                    String n = contentName(c);
                    return n != null ? n : CONTENT_UNREADABLE;
                }
                return null;
            } catch (NoSuchMethodException ignored) {
                // fall through to the equed field below
            } catch (Exception ignored) {
            }

            try {
                java.lang.reflect.Field f = spr.getClass().getDeclaredField("equed");
                f.setAccessible(true);
                Object equed = f.get(spr);
                if (equed == null) {return null;}
                String n = contentName(equed);
                if (n != null) {return n;}
                return CONTENT_UNREADABLE;
            } catch (NoSuchFieldException e) {
                return null;
            } catch (Exception ignored) {
                return null;
            }
        }

        /** Recursively unwrap an object (Indir / StateNode / Sprite / Resource / String, then a
         *  generic scan of an unknown object's non-primitive fields) into a resource-name string. */
        private String contentName(Object content) {
            return contentName0(content, 0);
        }

        private String contentName0(Object content, int depth) {
            if (content == null || depth > 6) {return null;}
            try {
                if (content instanceof Indir) {
                    return contentName0(((Indir<?>) content).get(), depth + 1);
                }
                if (content instanceof RUtils.StateNode) {
                    return contentName0(((RUtils.StateNode<?>) content).r, depth + 1);
                }
                if (content instanceof Sprite) {
                    Resource res = ((Sprite) content).res;
                    if (res != null && res.name != null) {return res.name;}
                    return contentName0(res, depth + 1);
                }
                if (content instanceof Resource) {
                    return ((Resource) content).name;
                }
                if (content instanceof String) {
                    return (String) content;
                }
                // Unknown wrapper object: scan non-static, non-synthetic, non-primitive,
                // non-String fields for the first one that yields a clean name.
                for (java.lang.reflect.Field f : content.getClass().getDeclaredFields()) {
                    int mod = f.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(mod) || f.isSynthetic()) {continue;}
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft == String.class) {continue;}
                    try {
                        f.setAccessible(true);
                        Object v = f.get(content);
                        if (v == null || v == content) {continue;}
                        String n = contentName0(v, depth + 1);
                        if (n != null) {return n;}
                    } catch (Exception ignored) {}
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        private FishRecognition.SpitState spitState(Spit spit) {
            String name = spitContentName(spit);
            if (name == null) {return FishRecognition.SpitState.EMPTY;}
            if (CONTENT_UNREADABLE.equals(name)) {return FishRecognition.SpitState.UNKNOWN;}
            return FishRecognition.spitState(name);
        }

        // ---- Spit interactions -------------------------------------------------

        private void loadFish(Spit spit) throws InterruptedException, Abort {
            Gob fire = fireGob(spit);
            Gob.Overlay ol = spitOverlay(spit);
            if (fire == null || ol == null) {fail("no roasting spit found in fire area.");}
            if (!approachAndSettle(fire)) {fail("could not reach fire target.");}
            WItem fish = firstRawFish();
            if (fish == null) {fail("no raw fish available.");}
            if (gui.hand() != null) {returnHeldToInventory(gui);}
            fish.take();
            if (!waitForHand(CURSOR_TIMEOUT)) {fail("could not pick up a raw fish.");}
            Coord mc = fire.rc.floor(OCache.posres);
            gui.map.wdgmsg("itemact", Coord.z, mc, 0, 1, (int) fire.id, mc, ol.id, -1);
            boolean loaded = waitFor(LOAD_TIMEOUT, () -> gui.hand() == null && spitState(spit) == FishRecognition.SpitState.RAW);
            if (!loaded) {
                returnHeldToInventory(gui);
                fail("spit rejected the fish.");
            }
        }

        private void turn(Spit spit) throws InterruptedException, Abort {
            if (fireGob(spit) == null || spitOverlay(spit) == null) {fail("no roasting spit found in fire area.");}
            FlowerMenu menu = rightClickSpitForMenu(spit);
            if (menu == null) {fail("turn menu did not appear.");}
            FlowerMenu.Petal turn = findPetal(menu, "Turn");
            if (turn == null) {
                menu.choose(null);
                fail("turn menu did not appear.");
            }
            menu.choose(turn);

            long start = System.currentTimeMillis();
            boolean sawProgress = false;
            long deadline = System.currentTimeMillis() + ROAST_TIMEOUT;
            while (System.currentTimeMillis() < deadline) {
                bot.checkCancelled();
                FishRecognition.SpitState s = spitState(spit);
                if (s == FishRecognition.SpitState.COOKED) {return;}
                if (s == FishRecognition.SpitState.UNKNOWN) {fail("spit occupied by an unknown item.");}
                if (gui.prog != null) {sawProgress = true;}
                if (!sawProgress && (System.currentTimeMillis() - start) > NO_PROGRESS_GRACE) {
                    fail("fire may be unlit or unfueled.");
                }
                sleep(200);
            }
            fail("cooking timed out.");
        }

        private void carve(Spit spit) throws InterruptedException, Abort {
            if (fireGob(spit) == null || spitOverlay(spit) == null) {fail("no roasting spit found in fire area.");}
            FlowerMenu menu = rightClickSpitForMenu(spit);
            if (menu == null) {fail("carve menu did not appear.");}
            FlowerMenu.Petal carve = findPetal(menu, "Carve");
            if (carve == null) {
                menu.choose(null);
                fail("carve menu did not appear.");
            }
            int before = countCookedItems();
            menu.choose(carve);
            boolean empty = waitFor(CARVE_TIMEOUT, () -> spitState(spit) == FishRecognition.SpitState.EMPTY);
            if (!empty) {fail("carving timed out.");}
            boolean appeared = waitFor(CURSOR_TIMEOUT, () -> countCookedItems() > before);
            if (!appeared) {fail("cooked output could not be identified.");}
        }

        private FlowerMenu rightClickSpitForMenu(Spit spit) throws InterruptedException {
            Gob fire = fireGob(spit);
            Gob.Overlay ol = spitOverlay(spit);
            if (fire == null || ol == null) {return null;}
            Set<Widget> before = collectWidgets(gui.ui.root);
            Coord mc = fire.rc.floor(OCache.posres);
            gui.map.wdgmsg("click", Coord.z, mc, 3, 0, 1, (int) fire.id, mc, ol.id, -1);
            long deadline = System.currentTimeMillis() + MENU_TIMEOUT;
            while (System.currentTimeMillis() < deadline) {
                bot.checkCancelled();
                FlowerMenu menu = findNewWidget(gui.ui.root, FlowerMenu.class, before);
                if (menu != null) {return menu;}
                sleep(100);
            }
            return null;
        }

        private FlowerMenu.Petal findPetal(FlowerMenu menu, String name) {
            if (menu.opts == null) {return null;}
            for (FlowerMenu.Petal p : menu.opts) {
                if (name.equals(p.name)) {return p;}
            }
            return null;
        }

        // ---- Deposit to output -------------------------------------------------

        private void maybeDepositEarly() throws InterruptedException, Abort {
            if (gui.maininv != null && gui.maininv.free() <= LOW_INVENTORY_FREE) {
                depositCooked();
            }
        }

        private void depositCooked() throws InterruptedException, Abort {
            if (countCookedItems() == 0) {return;}
            List<Gob> outputs = findOutputCandidates();
            if (outputs.isEmpty()) {fail("no output storage found in output area.");}
            for (Gob out : outputs) {
                bot.checkCancelled();
                if (countCookedItems() == 0) {break;}
                if (!approachAndSettle(out)) {continue;}
                Window win = openContainerWindow(out, CURSOR_TIMEOUT);
                if (win == null) {continue;}
                try {
                    Inventory inv = largestInventory(win);
                    if (inv == null) {continue;}
                    while (countCookedItems() > 0) {
                        bot.checkCancelled();
                        WItem fish = collectCookedItems().get(0);
                        if (!depositOne(fish, inv)) {break;}
                    }
                } finally {
                    win.reqdestroy();
                }
            }
            if (countCookedItems() > 0) {fail("all output storage is full.");}
        }

        private boolean depositOne(WItem fish, Inventory inv) throws InterruptedException {
            if (fish.disposed()) {return false;}
            returnHeldToInventory(gui);
            if (gui.hand() != null) {return false;}
            Coord size = fish.lsz != null ? fish.lsz : new Coord(1, 1);
            Coord slot = inv.findPlaceFor(size);
            if (slot == null) {return false;}
            // 1-arg take: matches a real left-click, unlike WItem.take()'s extra trailing 0.
            fish.item.wdgmsg("take", fish.sz.div(2));
            if (!waitForHand(CURSOR_TIMEOUT)) {return false;}
            inv.wdgmsg("drop", slot);
            if (!waitForHandEmpty(CURSOR_TIMEOUT)) {
                // Fall back to dropping at the origin slot before giving up.
                inv.wdgmsg("drop", Coord.z);
                if (!waitForHandEmpty(2000)) {
                    returnHeldToInventory(gui);
                    return false;
                }
            }
            return true;
        }

        private List<Gob> findOutputCandidates() {
            List<Gob> out = new ArrayList<>();
            for (Gob g : gobsIn(output)) {
                int p = outputPriorityOf(g);
                if (p != FishRecognition.NOT_OUTPUT) {out.add(g);}
            }
            out.sort((a, b) -> {
                int pa = outputPriorityOf(a);
                int pb = outputPriorityOf(b);
                if (pa != pb) {return Integer.compare(pa, pb);}
                Gob player = gui.map.player();
                double da = player != null && a.rc != null ? player.rc.dist(a.rc) : Double.MAX_VALUE;
                double db = player != null && b.rc != null ? player.rc.dist(b.rc) : Double.MAX_VALUE;
                return Double.compare(da, db);
            });
            return out;
        }

        private int outputPriorityOf(Gob g) {
            String resid = residOf(g);
            int p = FishRecognition.outputPriority(resid);
            if (p != FishRecognition.NOT_OUTPUT) {return p;}
            return g.is(GobTag.CONTAINER) ? FishRecognition.PRIORITY_CONTAINER : FishRecognition.NOT_OUTPUT;
        }

        // ---- Navigation / windows ---------------------------------------------

        private boolean approachGob(Gob gob) throws InterruptedException {
            ApproachOnly.Result r = ApproachOnly.approach(gui, gob, bot);
            return r != null && r.outcome == ApproachOnly.Outcome.APPROACH_READY;
        }

        /** Approach then wait for the server-driven Moving attribute to clear so the
         *  follow-up click/itemact isn't rejected while still mid-stride. */
        private boolean approachAndSettle(Gob gob) throws InterruptedException {
            if (!approachGob(gob)) {return false;}
            Gob player = gui.map.player();
            if (player == null) {return true;}
            long deadline = System.currentTimeMillis() + 3000;
            while (player.getattr(Moving.class) != null) {
                bot.checkCancelled();
                if (System.currentTimeMillis() >= deadline) {break;}
                sleep(50);
            }
            return true;
        }

        private Window openContainerWindow(Gob gob, long timeoutMs) throws InterruptedException {
            Set<Widget> before = new HashSet<>();
            for (Widget w = gui.lchild; w != null; w = w.prev) {before.add(w);}
            FlowerMenu.lastGob(gob);
            Coord mc = gob.rc.floor(OCache.posres);
            gui.map.wdgmsg("click", Coord.z, mc, 3, gui.ui.modflags(), 0, (int) gob.id, mc, 0, -1);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                bot.checkCancelled();
                for (Widget w = gui.lchild; w != null; w = w.prev) {
                    if ((w instanceof Window) && !before.contains(w)) {return (Window) w;}
                }
                sleep(100);
            }
            return null;
        }

        private Inventory largestInventory(Widget root) {
            Inventory best = null;
            for (Widget w = root.lchild; w != null; w = w.prev) {
                Inventory inv = ExtInventory.inventory(w);
                if (inv != null && (best == null || inv.size() > best.size())) {best = inv;}
                Inventory sub = largestInventory(w);
                if (sub != null && (best == null || sub.size() > best.size())) {best = sub;}
            }
            return best;
        }

        // ---- Widget-tree / waiting helpers -------------------------------------

        private Set<Widget> collectWidgets(Widget root) {
            Set<Widget> out = new HashSet<>();
            for (Widget w = root.lchild; w != null; w = w.prev) {
                out.add(w);
                out.addAll(collectWidgets(w));
            }
            return out;
        }

        private <T extends Widget> List<T> collectWidgets(Widget root, Class<T> type) {
            List<T> out = new ArrayList<>();
            for (Widget w = root.lchild; w != null; w = w.prev) {
                if (type.isInstance(w)) {out.add(type.cast(w));}
                out.addAll(collectWidgets(w, type));
            }
            return out;
        }

        private <T extends Widget> T findNewWidget(Widget root, Class<T> type, Set<Widget> before) {
            for (Widget w = root.lchild; w != null; w = w.prev) {
                if (type.isInstance(w) && !before.contains(w)) {return type.cast(w);}
                T found = findNewWidget(w, type, before);
                if (found != null) {return found;}
            }
            return null;
        }

        private interface Poll {boolean test();}

        private boolean waitFor(long timeoutMs, Poll poll) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                bot.checkCancelled();
                if (poll.test()) {return true;}
                sleep(100);
            }
            return poll.test();
        }

        private boolean waitForHand(long timeoutMs) throws InterruptedException {
            return waitFor(timeoutMs, () -> gui.hand() != null);
        }

        private boolean waitForHandEmpty(long timeoutMs) throws InterruptedException {
            return waitFor(timeoutMs, () -> gui.hand() == null);
        }

        private void sleep(long ms) throws InterruptedException {
            Thread.sleep(ms);
        }
    }
}
