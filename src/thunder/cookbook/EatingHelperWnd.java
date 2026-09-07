package thunder.cookbook;

import haven.*;
import haven.resutil.FoodInfo;
import haven.rx.Reactor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * "What should I eat next, and in what order, to grow the stat I want?"
 * Reuses the game's own live FEP formula (haven.resutil.FoodInfo.breakdown(),
 * added alongside this window) rather than reimplementing the math -- that
 * formula already accounts for current hunger modifier, per-category
 * satiation, subscription/verified bonus, and (if actively feasting at a
 * specific table) that table's Food Event Bonus.
 *
 * Runs a genuine multi-step plan (simulatePlan()), not just a snapshot sort --
 * an earlier version just ranked every item by its CURRENT-state FEP and
 * called that the order to eat in, which put every instance of the same dish
 * back-to-back (e.g. six "Egg & Bacon" in a row): technically the highest
 * values at that instant, but eating six in a row is exactly what tanks that
 * category's satiation, so it was quietly wrong advice. Reported directly by
 * the user with a screenshot. The simulation greedily picks, at each step,
 * whichever remaining item gives the most useful target progress per hunger under
 * the CURRENTLY-SIMULATED satiation state (not the live one), "eats" it, and
 * updates the simulated satiation for its categories using the best-fit
 * formula from two real eating sessions (see satiationDelta()) before moving
 * to the next step -- so it naturally spreads across categories instead of
 * exhausting one.
 *
 * Honest limits on the simulation (all confirmed directly with the user
 * rather than assumed):
 *  - satiationDelta() is an empirical fit from two eating sessions, not a
 *    dev-confirmed constant -- expect drift, especially since we found it
 *    also depends on current hunger modifier (gmod) in a way not fully
 *    isolated yet. Held constant at gmod's live value for the whole plan.
 *  - the FEP-bar cap's "variety bonus" reduction IS re-simulated: each newly
 *    -eaten distinct resource during the plan lowers simCap the same way
 *    fepnum() does, continuing from the reduction count already baked into
 *    the live cap (derived by replaying fepnum()'s own search). An earlier
 *    version held the cap fixed, which overestimated how much total FEP was
 *    needed and recommended one extra item past what was actually
 *    necessary -- reported directly by the user (plan said 2 items, 1 was
 *    enough).
 *  - stops once the simulated bar would cross the (now-shrinking) cap (one
 *    attribute point) or after MAX_SIM_STEPS, whichever comes first.
 *  - candidate scoring credits target FEP plus a new food's variety-cap
 *    reduction, divides that useful progress by hunger cost, and caps credit
 *    at the remaining gap so overfill is not rewarded.
 *
 * Variety bonus is accounted for (see the cap-reduction note above) and its
 * formula is independently confirmed against
 * https://ringofbrodgar.com/wiki/Variety_Bonus (found by the user): their
 * `0.894 * sqrt(maxstat)` at 200% food efficiency matches
 * `sqrt(maxattr*2*gmod/5)` at gmod=2.0 exactly.
 *
 * Table bonus: only applied when Window.lastFeastTable is set (the specific
 * table whose "Feast!" button was actually clicked -- see Window.java's
 * CheckForDinnerTable hook), not just because some table window happens to
 * be open. Confirmed directly with the user: multiple table inventories can
 * be open at once, only the one actually being fed from matters.
 *
 * See docs/eating-helper.md.
 */
public class EatingHelperWnd extends WindowX {
    private static final Coord ICON_SZ = UI.scale(24, 24);
    private static final int ROWH = UI.scale(28);
    private static final Coord LIST_SZ = new Coord(UI.scale(420), UI.scale(320));
    private static final int MAX_SIM_STEPS = 60;

    private static final long DEFAULT_MIN_GAP_MS = 50; // floor between bites -- never send faster than this even if confirmed instantly
    private static final long BITE_TIMEOUT_MS = 2000; // fallback if a single bite never gets a confirmed update
    private static final double SETTLE_TIMEOUT = 5.0; // seconds -- fallback cap on waiting for a confirmed server update
    private static final int DEFAULT_LIMIT = 10;

    private final DropboxOfStrings statSel;
    private final TextEntry limitField;
    private final TextEntry paceField;
    private final Coord autoBtnPos;
    private Button autoBtn;
    private final WrappedLabel status;
    private final WrappedLabel liveStats;
    private final GroupedList results;
    private final List<Widget> badges = new ArrayList<>();

    private String selectedStat = null;
    private List<PlanStep> lastPlan = Collections.emptyList();
    private boolean lastPlanFeasting = false;

    // Auto-eat state. Runs off tick(), not a blocking loop. Ordinary eating goes
    // through Reactor.FLOWER's fire-and-forget subscribe/forceChoose pattern; Feast
    // eating uses the table item's direct left-click path.
    //
    // Both the per-bite pacing AND the settle phase (after the last bite in a round,
    // before comparing attributes to check for a level-up) are event-driven, not fixed
    // timers: each waits for SatiationCapture.lastUpdate() to actually advance past the
    // moment of the relevant bite, confirming the server really processed it, instead of
    // guessing a delay. Raised directly by the user wanting to go as fast as the
    // connection allows (300 items in seconds, not minutes) without outrunning the
    // server -- a fixed "safe" delay is either too slow for a good connection or too
    // fast for a bad one; confirming a real update sidesteps the guess. Per-bite pacing
    // additionally enforces a DEFAULT_MIN_GAP_MS floor (default 50ms, user-adjustable)
    // so it never sends faster than that even when confirmation arrives instantly.
    // BITE_TIMEOUT_MS/SETTLE_TIMEOUT are just fallbacks in case a specific update never
    // arrives for some other reason, so auto-eat can't hang forever on one item.
    private boolean autoRunning = false;
    private boolean autoSettling = false;
    private boolean autoWaitingBite = false;
    private long nextAllowedActionTime = 0;
    private long biteDeadline = 0;
    private long lastEatActionTime = 0;
    private long settleDeadline = 0;
    private List<Entry> autoQueue = Collections.emptyList();
    private int autoQueueIdx = 0;
    private int autoItemsThisLevel = 0;
    private Map<String, Integer> autoAttrsSnapshot = null;
    private String autoCurrentBiteName = null; // for BITE-CONFIRMED/BITE-TIMEOUT log lines only
    private boolean autoRoundFeasting = false;
    private final Set<String> autoFoodsThisLevel = new HashSet<>();
    // Locked in once when auto-eat starts -- selectedStat itself gets silently reassigned by
    // runQuery()'s own dropdown-preservation fallback (falls back to "whatever's first in the
    // list" once the previously-selected stat runs out of food), which would otherwise let
    // auto-eat quietly keep grinding a DIFFERENT stat than the one actually picked. Reported
    // directly by the user after exactly that happened (target silently switched from
    // Constitution to Perception mid-run) -- startAutoRound() compares against this and stops
    // cleanly instead of drifting.
    private String autoTargetStat = null;

    /**
     * Plain Label only wraps in its width-taking constructor -- settext() (which is how
     * status/liveStats get updated every query) always falls back to Label's own
     * unwrapped f.render(...), so a long satiations list or status line ran clean off the
     * screen, past where the game window can even be resized to. Overriding settext() to
     * keep using f.renderwrap(...) (same call the width-constructor makes once) fixes that
     * for every update, not just the first. Reported directly by the user with a
     * screenshot of the line running off both the window and the desktop.
     */
    private static class WrappedLabel extends Label {
        private final int w;

        WrappedLabel(String text, int w) {
            super(text, w);
            this.w = w;
        }

        @Override
        public void settext(String text) {
            // Label's private i10n(String) translation helper isn't reachable from here (different
            // package); harmless to skip -- it's a no-op passthrough unless i10n() is overridden true,
            // which plain Label never does (defaults to false), so regular Label.settext skips it too.
            if(text.equals(this.texts)) {return;}
            this.text.dispose();
            this.text = f.renderwrap(texts = text, col, w);
            resize(this.text.sz());
        }
    }

    public EatingHelperWnd() {
        super(Coord.z, "Eating Helper");
        justclose = true;

        Label lbl = add(new Label("Target stat:"), 0, UI.scale(4));
        statSel = add(new DropboxOfStrings(UI.scale(140), 12, UI.scale(20)), lbl.pos("ur").adds(6, -4));
        statSel.setChangedCallback((idx, item) -> {selectedStat = item; runQuery();});
        add(new Button(UI.scale(80), "Run Query", this::runQuery), statSel.pos("ur").adds(10, 0));

        Label limitLbl = add(new Label("Auto-eat limit (items/level):"), lbl.pos("bl").adds(0, 8));
        limitField = add(new TextEntry(UI.scale(40), String.valueOf(DEFAULT_LIMIT)), limitLbl.pos("ur").adds(6, -4));
        autoBtnPos = limitField.pos("ur").adds(10, 0);
        setAutoBtn("Auto-Eat");

        Label paceLbl = add(new Label("Min. gap between bites (ms):"), limitLbl.pos("bl").adds(0, 8));
        paceField = add(new TextEntry(UI.scale(40), String.valueOf(DEFAULT_MIN_GAP_MS)), paceLbl.pos("ur").adds(6, -4));

        status = add(new WrappedLabel("Click Run Query to scan your inventory.", LIST_SZ.x), paceLbl.pos("bl").adds(0, 6));
        // Shows exactly what live state the calculator is using -- hunger modifier, current
        // FEP bar, table bonus, per-category satiations -- so it's checkable against the
        // Character Sheet instead of a black box. Requested directly by the user.
        liveStats = add(new WrappedLabel("", LIST_SZ.x), status.pos("bl").adds(0, 4));
        results = add(new GroupedList(LIST_SZ.x, LIST_SZ.y / ROWH), liveStats.pos("bl").adds(0, 6));

        pack();
    }

    private void setAutoBtn(String label) {
        if(autoBtn != null) {autoBtn.destroy();}
        autoBtn = add(new Button(UI.scale(90), label, this::onAutoBtnClick), autoBtnPos);
    }

    public static void toggle(UI ui) {
        if((ui == null) || (ui.gui == null)) {return;}
        if(ui.gui.eatingHelperWnd == null) {
            ui.gui.eatingHelperWnd = ui.gui.add(new EatingHelperWnd(), UI.scale(new Coord(260, 200)));
        } else {
            ui.gui.eatingHelperWnd.destroy();
        }
    }

    @Override
    public void destroy() {
        autoRunning = false;
        clearBadges();
        if((ui != null) && (ui.gui != null) && (ui.gui.eatingHelperWnd == this)) {ui.gui.eatingHelperWnd = null;}
        super.destroy();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(!autoRunning) {return;}
        if(autoSettling) {
            // Event-driven, not a fixed timer: wait for a real server message confirming
            // state actually changed since the last bite, so a slow connection can't get
            // checked against stale attributes (and a fast one doesn't wait needlessly).
            // SETTLE_TIMEOUT is only a fallback in case no update ever arrives at all.
            if((SatiationCapture.lastUpdate() >= lastEatActionTime) || (System.currentTimeMillis() > settleDeadline)) {
                autoSettling = false;
                checkLevelAndContinue();
            }
            return;
        }
        if(autoWaitingBite) {
            long now = System.currentTimeMillis();
            boolean confirmed = SatiationCapture.lastUpdate() >= lastEatActionTime;
            boolean gapPassed = now >= nextAllowedActionTime;
            if(confirmed && gapPassed) {
                SatiationCapture.log(String.format("BITE-CONFIRMED %s rtt=%dms", autoCurrentBiteName, now - lastEatActionTime));
                autoWaitingBite = false;
            } else if(now > biteDeadline) {
                // No confirming update ever arrived for this bite within BITE_TIMEOUT_MS -- possible
                // dropped/rejected action, or just an unusually slow response. Logged as a distinct
                // event (not a BITE-CONFIRMED) specifically so this is greppable/countable afterward --
                // added directly at the user's request, to check for exactly this kind of silent
                // failure before trusting the tool on a large batch.
                SatiationCapture.log(String.format("BITE-TIMEOUT %s no confirmation within %dms", autoCurrentBiteName, BITE_TIMEOUT_MS));
                autoWaitingBite = false;
            } else {
                return;
            }
        }
        if(autoQueueIdx >= autoQueue.size()) {
            autoSettling = true;
            settleDeadline = System.currentTimeMillis() + (long) (SETTLE_TIMEOUT * 1000);
            return;
        }
        Entry e = autoQueue.get(autoQueueIdx++);
        if(!e.witem.disposed()) {
            if(!eatOne(e)) {return;}
            long gapMs = parseMinGapMs();
            nextAllowedActionTime = System.currentTimeMillis() + gapMs;
            biteDeadline = System.currentTimeMillis() + BITE_TIMEOUT_MS;
            autoWaitingBite = true;
        } // else: already gone (eaten some other way, or the window it lived in closed) -- skip it immediately, no wait
    }

    /**
     * Feast food must be consumed with the table's normal left-click path; using the
     * inventory flower-menu "Eat" action bypasses the table and loses its FEP bonus.
     * Outside Feast mode, use right-click + force-choose "Eat" -- same
     * pattern as haven.bot.AutoEat, which never reads FlowerMenu.opts itself. An earlier
     * version of this method DID read m.opts here, inside the Reactor.FLOWER subscriber,
     * to log whether "Eat" was actually a valid option before choosing it -- crashed the
     * whole client instantly (real stack trace from the user, IntelliJ console): opts is
     * still null at that point because Reactor.FLOWER.onNext(this) fires from INSIDE
     * FlowerMenu's own constructor, before opts gets assigned. forceChoose() itself is
     * safe (it only actually reads/matches options later, once the menu is fully built --
     * see FlowerMenu's forceChosen = forceChoose() call elsewhere in its lifecycle), so the
     * fix is to never touch opts this early ourselves. The same "was Eat actually chosen"
     * check is done safely below via Reactor.FLOWER_CHOICE instead, which only fires once
     * a choice is actually made (menu fully constructed by then).
     */
    private boolean eatOne(Entry e) {
        String name = itemDisplayName(e);
        autoCurrentBiteName = name;
        long sentAt = System.currentTimeMillis();
        try {
            SatiationCapture.log("BITE-SENT " + name + " res=" + e.witem.item.resname());
            Window feastTable = activeFeastTable();
            if(autoRoundFeasting) {
                if(feastTable == null) {
                    stopAutoEat("Feast mode is no longer active -- stopping before eating without the table bonus.");
                    return false;
                }
                e.witem.take();
                rememberAutoFood(e);
                SatiationCapture.log("BITE-FEAST-CLICK " + name);
                lastEatActionTime = sentAt;
                return true;
            }
            Reactor.FLOWER.first().subscribe(m -> m.forceChoose("Eat"));
            Reactor.FLOWER_CHOICE.first().subscribe(c -> {
                long menuAt = System.currentTimeMillis();
                if("Eat".equals(c.opt)) {
                    SatiationCapture.log(String.format("BITE-MENU-OK %s rtt=%dms", name, menuAt - sentAt));
                } else {
                    // Either no "Eat" option existed (forceChoose had nothing to match, so nothing
                    // got eaten) or something else entirely was chosen -- either way, worth flagging.
                    SatiationCapture.log(String.format("BITE-MENU-NO-EAT-OPTION %s rtt=%dms chosen=%s", name, menuAt - sentAt, c.opt));
                }
            });
            e.witem.rclick();
            rememberAutoFood(e);
            lastEatActionTime = sentAt;
            return true;
        } catch(Exception ex) {
            SatiationCapture.log("BITE-ERROR " + name + " " + ex);
            stopAutoEat("Could not eat " + name + " -- stopping auto-eat.");
            return false;
        }
    }

    private void rememberAutoFood(Entry e) {
        autoFoodsThisLevel.add(e.varietyKey);
    }

    private static Window activeFeastTable() {
        Window table = Window.lastFeastTable;
        return ((table != null) && (table.parent != null)) ? table : null;
    }

    private static String itemDisplayName(Entry e) {
        try {
            Resource.Tooltip tt = e.witem.item.getres().layer(Resource.tooltip);
            return (tt != null) ? tt.t : e.witem.item.resname();
        } catch(Exception ex) {
            return "?";
        }
    }

    private int parseLimit() {
        try {
            int v = Integer.parseInt(limitField.text().trim());
            return Math.max(1, v);
        } catch(Exception e) {
            return DEFAULT_LIMIT;
        }
    }

    private long parseMinGapMs() {
        try {
            long v = Long.parseLong(paceField.text().trim());
            return Math.max(0, v);
        } catch(Exception e) {
            return DEFAULT_MIN_GAP_MS;
        }
    }

    private void snapshotAttrs(BAttrWnd battr) {
        Map<String, Integer> m = new HashMap<>();
        for(BAttrWnd.Attr a : battr.attrs) {m.put(a.nm, a.attr.base);}
        autoAttrsSnapshot = m;
    }

    private void onAutoBtnClick() {
        if(autoRunning) {
            stopAutoEat("Stopped.");
            return;
        }
        if(selectedStat == null) {
            status.settext("Pick a target stat (run a query first if the dropdown is empty) before auto-eating.");
            relayout();
            return;
        }
        CharWnd cw = ((ui != null) && (ui.gui != null)) ? ui.gui.chrwdg : null;
        if((cw == null) || (cw.battr == null)) {
            status.settext("Open your Character Sheet's Base Attributes (Food) tab first, then try again.");
            relayout();
            return;
        }
        autoRunning = true;
        autoSettling = false;
        autoItemsThisLevel = 0;
        autoFoodsThisLevel.clear();
        autoTargetStat = selectedStat; // locked for the whole run -- see field doc
        snapshotAttrs(cw.battr);
        setAutoBtn("Stop Auto-Eat");
        SatiationCapture.log(String.format("AUTOEAT-START target=%s limit=%d minGapMs=%d", selectedStat, parseLimit(), parseMinGapMs()));
        startAutoRound();
    }

    private void stopAutoEat(String reason) {
        autoRunning = false;
        autoSettling = false;
        autoRoundFeasting = false;
        autoQueue = Collections.emptyList();
        setAutoBtn("Auto-Eat");
        SatiationCapture.log("AUTOEAT-STOP " + (reason != null ? reason : "(user)"));
        if(reason != null) {
            status.settext(reason);
            relayout();
        }
    }

    /**
     * Re-runs the query fresh (same as clicking "Run Query" -- always re-checks live hunger/
     * satiation/feasting state rather than assuming anything about the previous round) and,
     * if under the user's per-level limit, queues up the resulting plan to eat. Called both
     * to start a fresh level and to get a top-off plan when a round finishes short (the "mild
     * .. sliver off" case the user described -- confirmed infrequent but real, so auto-eat has
     * to handle it instead of assuming one round always finishes the level).
     */
    private void startAutoRound() {
        runQuery();
        if(!autoRunning) {return;} // runQuery() itself doesn't stop auto-eat, but be defensive
        if(!java.util.Objects.equals(selectedStat, autoTargetStat)) {
            // runQuery()'s dropdown-preservation fallback picked a DIFFERENT stat because no
            // remaining food gives autoTargetStat anymore -- stop cleanly rather than silently
            // grinding a stat the user never chose.
            stopAutoEat(String.format("No food left giving \"%s\" FEP (closest available would be \"%s\") -- stopping auto-eat rather than switching stats on you.",
                autoTargetStat, (selectedStat != null) ? selectedStat : "none"));
            return;
        }
        if(lastPlan.isEmpty()) {
            stopAutoEat("No plan available for " + selectedStat + " -- stopping auto-eat.");
            return;
        }
        autoRoundFeasting = lastPlanFeasting;
        int limit = parseLimit();
        int projected = autoItemsThisLevel + lastPlan.size();
        // Strict, applies to EVERY round the same way -- a fresh level start or a top-off,
        // no exception either way. A top-off is expected to happen at most once per level
        // (confirmed directly by the user: "it shouldn't need to top off more than once"),
        // so there's no real scenario where letting a round exceed the limit is actually
        // needed -- the limit is a hard ceiling on the whole level's total, full stop.
        if(projected > limit) {
            stopAutoEat(String.format("This level would take %d+ food items total (limit is %d) -- stopping before eating any more of it.",
                projected, limit));
            return;
        }
        List<Entry> queue = new ArrayList<>();
        for(PlanStep s : lastPlan) {queue.add(s.entry);}
        autoQueue = queue;
        autoQueueIdx = 0;
        autoItemsThisLevel = projected;
        autoWaitingBite = false; // eat the first item on the very next tick, no wait
        SatiationCapture.log(String.format("ROUND-START target=%s queued=%d itemsThisLevelSoFar=%d limit=%d", selectedStat, queue.size(), projected, limit));
    }

    /** After a round finishes and settles, check whether any attribute actually went up. */
    private void checkLevelAndContinue() {
        if(!autoRunning) {return;}
        CharWnd cw = ((ui != null) && (ui.gui != null)) ? ui.gui.chrwdg : null;
        if((cw == null) || (cw.battr == null)) {
            stopAutoEat("Character Sheet closed -- stopping auto-eat.");
            return;
        }
        boolean leveled = false;
        String leveledAttr = null;
        if(autoAttrsSnapshot != null) {
            for(BAttrWnd.Attr a : cw.battr.attrs) {
                Integer before = autoAttrsSnapshot.get(a.nm);
                if((before != null) && (a.attr.base > before)) {leveled = true; leveledAttr = a.nm; break;}
            }
        }
        SatiationCapture.log("ROUND-RESULT leveled=" + leveled + (leveled ? (" attr=" + leveledAttr) : " (top-off needed)"));
        if(leveled) {
            autoItemsThisLevel = 0;
            autoFoodsThisLevel.clear();
            snapshotAttrs(cw.battr);
        }
        // Either way, get a fresh plan: leveled -> plan for the NEXT point; didn't level ->
        // this was a short round, and the fresh query will recommend whatever small top-off
        // is still needed (re-checked against the SAME per-level limit, cumulative across
        // both rounds, not reset).
        startAutoRound();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if((sender == this) && msg.equals("close")) {
            destroy();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    private void clearBadges() {
        for(Widget w : badges) {w.destroy();}
        badges.clear();
    }

    private static class Entry {
        final WItem witem;
        final FoodInfo finf;
        final FoodInfo.Breakdown bd; // live-state breakdown, used for the initial stat list + "no food found" check
        final String varietyKey;
        Entry(WItem witem, FoodInfo finf, FoodInfo.Breakdown bd) {
            this.witem = witem;
            this.finf = finf;
            this.bd = bd;
            this.varietyKey = foodVarietyKey(witem, finf);
        }
    }

    /**
     * A resource alone is not a food identity: bear, fox, and other meats can all be
     * gfx/invobjs/meat. The server's satiation-category identities distinguish species,
     * while the resource distinguishes preparations such as roast and spitroast.
     */
    private static String foodVarietyKey(WItem witem, FoodInfo finf) {
        String resource;
        try {resource = witem.item.resname();} catch(Exception ex) {resource = "?";}
        int[] types = Arrays.copyOf(finf.types, finf.types.length);
        Arrays.sort(types);
        return resource + "|" + Arrays.toString(types);
    }

    /** One step of the simulated plan: a single item "eaten" at that point in the sequence. */
    private static class PlanStep {
        final Entry entry;
        final double targetGain;
        final double totalGain;
        PlanStep(Entry entry, double targetGain, double totalGain) {this.entry = entry; this.targetGain = targetGain; this.totalGain = totalGain;}
    }

    /** Consecutive identical-resource PlanSteps collapsed into one display row with a count. */
    private static class PlanGroup {
        final Entry sample;
        final int count;
        final double targetGain;
        final double cumTargetAfter;
        PlanGroup(Entry sample, int count, double targetGain, double cumTargetAfter) {
            this.sample = sample;
            this.count = count;
            this.targetGain = targetGain;
            this.cumTargetAfter = cumTargetAfter;
        }
    }

    /**
     * Empirical fit from two real eating sessions (see docs/eating-helper.md): the satiation
     * value's "odds" (1-a)/a increases by a roughly item-proportional amount per bite, cleanest
     * where hunger modifier was near-constant across the sample (~0.008 per unit of the item's
     * total raw FEP). Explicitly an approximation -- flagged to the user, not presented as exact.
     */
    private static double satiationDelta(double itemFepSum) {
        return 0.008 * itemFepSum;
    }

    private static double applySatiation(double oldA, double itemFepSum) {
        double old = Math.max(oldA, 1e-6);
        double x = (1.0 - old) / old;
        double xNew = Math.max(x + satiationDelta(itemFepSum), 0);
        return 1.0 / (1.0 + xNew);
    }

    /**
     * Greedily builds an eating order: at each step, evaluate every remaining item under the
     * CURRENTLY-SIMULATED satiation/effmod state (not the live one), pick whichever gives the
     * most useful target progress per unit of hunger, "eat" it (advance the simulated FEP bar
     * and satiation for its categories), repeat. Variety cap reduction counts as progress and
     * progress beyond the remaining gap is discarded so overfill receives no credit.
     */
    private List<PlanStep> simulatePlan(List<Entry> pool, String target, BAttrWnd battr, boolean feasting, int tableBonus) {
        List<PlanStep> plan = new ArrayList<>();
        if((target == null) || pool.isEmpty()) {return plan;}

        Map<Integer, Double> simSat = new HashMap<>();
        List<BAttrWnd.Constipations.El> liveEls = battr.cons.els;
        for(int i = 0; i < liveEls.size(); i++) {simSat.put(i, liveEls.get(i).a);}

        double bonusmul = 1;
        if(GameUI.subscribedAccount) {bonusmul += 0.3;}
        if(GameUI.verifiedAccount) {bonusmul += 0.2;}
        double gmod = battr.glut.gmod;
        double tableMod = feasting ? (1.0 + ((double) tableBonus / 100.0)) : 1.0;

        double simCurFep = 0;
        for(BAttrWnd.FoodMeter.El el : battr.feps.els) {simCurFep += el.a;}
        double simCap = battr.feps.cap;

        // Variety bonus: eating a NEW distinct food type lowers the cap (see FoodInfo.fepnum()'s
        // own cap-reduction loop, which this replicates). Holding simCap fixed made the sim think
        // more total FEP was needed than the real game does, recommending one extra item past what
        // was actually necessary -- reported directly by the user after the plan needed 2 items but
        // 1 turned out to be enough. maxattr/n here are derived by reversing fepnum()'s own search
        // to find how many reductions are already baked into the CURRENT live cap, so newly-eaten
        // uniques during the simulation continue that same count instead of restarting it.
        int maxattr = 1;
        for(BAttrWnd.Attr a : battr.attrs) {if(a.attr.base > maxattr) {maxattr = a.attr.base;}}
        int n = 0;
        {
            double fep = maxattr, fepnext = maxattr;
            while(fepnext > simCap) {
                fep = fepnext;
                fepnext = fep - Math.sqrt((double) maxattr * 2 * gmod / 5 / (double) ++n);
            }
        }
        // Preserve resources already eaten by Auto-Eat across top-off replans for this level;
        // otherwise the same food would falsely receive another variety bonus on every query.
        java.util.Set<String> uniqueEaten = autoRunning
            ? new java.util.HashSet<>(autoFoodsThisLevel)
            : new java.util.HashSet<>();

        List<Entry> remaining = new ArrayList<>(pool);
        for(int step = 0; (step < MAX_SIM_STEPS) && !remaining.isEmpty() && (simCurFep < simCap); step++) {
            double gapLeft = simCap - simCurFep;

            // Score useful target progress per hunger. A new resource gets credit for its
            // variety cap reduction; repeated copies do not. Foods with no target FEP are
            // deliberately ineligible even if they are novel.
            Entry best = null;
            double bestTarget = -1, bestTotal = 0, bestScore = -1;
            for(Entry e : remaining) {
                double effective = 1;
                for(int t : e.finf.types) {
                    Double s = simSat.get(t);
                    if(s != null) {effective = Math.min(effective, s);}
                }
                double effmod = gmod * effective * bonusmul;
                double targetGain = 0, totalGain = 0;
                for(FoodInfo.Event ev : e.finf.evs) {
                    double fep = ev.a * tableMod * effmod;
                    totalGain += fep;
                    if(ev.ev.nm.equals(target)) {targetGain += fep;}
                }
                boolean newFood = !uniqueEaten.contains(e.varietyKey);
                double varietyReduction = newFood
                    ? Math.sqrt((double) maxattr * 2 * gmod / 5 / (double) (n + 1))
                    : 0;
                double usefulTarget = Math.min(targetGain, Math.max(0, gapLeft - varietyReduction));
                double usefulProgress = Math.min(gapLeft, varietyReduction + usefulTarget);
                double score = usefulProgress / Math.max(e.finf.glut, 1e-9);
                if((targetGain > 0) && ((score > bestScore) ||
                    ((score == bestScore) && (targetGain > bestTarget)))) {
                    bestScore = score;
                    bestTarget = targetGain;
                    bestTotal = totalGain;
                    best = e;
                }
            }
            if((best == null) || (bestTarget <= 0)) {break;}

            remaining.remove(best);
            simCurFep += bestTotal;

            try {
                if(uniqueEaten.add(best.varietyKey)) {
                    simCap -= Math.sqrt((double) maxattr * 2 * gmod / 5 / (double) ++n);
                }
            } catch(Exception ignored) {}

            double itemFepSum = 0;
            for(FoodInfo.Event ev : best.finf.evs) {itemFepSum += ev.a;}
            for(int t : best.finf.types) {
                double old = simSat.getOrDefault(t, 1.0);
                simSat.put(t, applySatiation(old, itemFepSum));
            }

            plan.add(new PlanStep(best, bestTarget, bestTotal));
        }
        return plan;
    }

    private static List<PlanGroup> group(List<PlanStep> plan) {
        List<PlanGroup> groups = new ArrayList<>();
        int i = 0;
        double cum = 0;
        while(i < plan.size()) {
            int j = i;
            double groupTarget = 0;
            while((j < plan.size()) && sameFood(plan.get(j).entry, plan.get(i).entry)) {
                groupTarget += plan.get(j).targetGain;
                j++;
            }
            cum += groupTarget;
            groups.add(new PlanGroup(plan.get(i).entry, j - i, groupTarget, cum));
            i = j;
        }
        return groups;
    }

    private static boolean sameFood(Entry a, Entry b) {
        return a.varietyKey.equals(b.varietyKey);
    }

    /**
     * status and liveStats are both variable-height now that they wrap (status can grow to
     * 2+ lines too, e.g. the "no food found" messages), so everything below each one must be
     * repositioned every time, not just once at construction against their original
     * one-line sizes.
     */
    private void relayout() {
        liveStats.c = status.pos("bl").adds(0, 4);
        results.c = liveStats.pos("bl").adds(0, 6);
        pack();
    }

    private void runQuery() {
        clearBadges();
        lastPlanFeasting = false;

        if((ui == null) || (ui.root == null)) {
            status.settext("No inventory open.");
            liveStats.settext("");
            results.setItems(Collections.emptyList());
            lastPlan = Collections.emptyList();
            relayout();
            return;
        }
        CharWnd cw = ui.gui.chrwdg;
        if((cw == null) || (cw.battr == null)) {
            status.settext("Open your Character Sheet's Base Attributes (Food) tab first, then try again.");
            liveStats.settext("");
            results.setItems(Collections.emptyList());
            lastPlan = Collections.emptyList();
            relayout();
            return;
        }

        Window feastTable = activeFeastTable();
        boolean feasting = feastTable != null;
        lastPlanFeasting = feasting;
        int tableBonus = feasting ? Window.lastFeastBonus : 0;

        liveStats.settext(buildLiveStatsText(cw.battr, feasting, tableBonus));

        // Scan every open container -- main inventory, tables, cabinets, pouches,
        // everything. While Feast mode is active, any of these foods can receive the
        // feast bonus as long as it is consumed through its normal left-click path.
        List<WItem> candidates = new ArrayList<>(ui.root.children(WItem.class));

        List<Entry> entries = new ArrayList<>();
        TreeSet<String> stats = new TreeSet<>();
        for(WItem w : candidates) {
            FoodInfo finf;
            try {
                finf = ItemInfo.find(FoodInfo.class, w.item.info());
            } catch(Loading l) {
                continue; // not loaded yet -- skip for this run rather than block the whole query
            } catch(Exception e) {
                continue;
            }
            if(finf == null) {continue;}
            FoodInfo.Breakdown bd = finf.breakdown(feasting, tableBonus);
            if(bd == null) {continue;}
            entries.add(new Entry(w, finf, bd));
            Collections.addAll(stats, bd.names);
        }

        // Sync the dropdown's data/selection directly via .sel, NOT .change(...) --
        // DropboxOfStrings.change() fires the changed-callback unconditionally, and that
        // callback calls runQuery() again, so calling it from inside runQuery() itself
        // would recurse forever.
        String prevSel = selectedStat;
        List<String> statList = new ArrayList<>(stats);
        statSel.setData(statList);
        if((prevSel != null) && statList.contains(prevSel)) {
            selectedStat = prevSel;
        } else if(!statList.isEmpty()) {
            selectedStat = statList.get(0);
        } else {
            selectedStat = null;
        }
        statSel.sel = selectedStat;

        if(entries.isEmpty()) {
            status.settext("No food found in any open inventory.");
            results.setItems(Collections.emptyList());
            lastPlan = Collections.emptyList();
            relayout();
            return;
        }

        List<PlanStep> plan = simulatePlan(entries, selectedStat, cw.battr, feasting, tableBonus);
        lastPlan = plan;
        List<PlanGroup> groups = group(plan);
        results.setItems(groups);

        // Badge number matches the row number in the list (every item in "3x Egg & Bacon"
        // gets the same badge as that row), not a running per-item count -- easier to match
        // an inventory item back to the list than a sequence of near-duplicate numbers.
        int planIdx = 0;
        int rowNum = 1;
        for(PlanGroup g : groups) {
            for(int i = 0; i < g.count; i++) {
                Entry e = plan.get(planIdx++).entry;
                Widget badge = e.witem.parent.add(new RankBadge(e.witem.sz, rowNum), e.witem.c);
                badges.add(badge);
            }
            rowNum++;
        }

        String etext;
        if(selectedStat == null) {
            etext = "no stat data found";
        } else if(plan.isEmpty()) {
            etext = "no plan found (target stat unreachable with current food)";
        } else {
            double fillFrom = 0;
            for(BAttrWnd.FoodMeter.El el : cw.battr.feps.els) {fillFrom += el.a;}
            double fillTo = fillFrom;
            for(PlanStep s : plan) {fillTo += s.totalGain;}
            boolean crosses = fillTo >= cw.battr.feps.cap;
            etext = String.format("%d-step plan, %,d unique food%s scanned. Target: %s%s", plan.size(), entries.size(),
                entries.size() == 1 ? "" : "s", selectedStat,
                crosses ? " (this plan fills the FEP bar -- expect a level-up)" : " (won't fill the FEP bar on its own)");
        }
        String autoPrefix = autoRunning ? String.format("[Auto-eating, %d item%s toward this level so far] ", autoItemsThisLevel, autoItemsThisLevel == 1 ? "" : "s") : "";
        status.settext(autoPrefix + (feasting ? ("Feasting +" + tableBonus + "% table bonus. ") : "") + etext);
        relayout();
    }

    private static String buildLiveStatsText(BAttrWnd battr, boolean feasting, int tableBonus) {
        StringBuilder sb = new StringBuilder();
        BAttrWnd.GlutMeter glut = battr.glut;
        sb.append(String.format("Hunger: %.1f%% (FEP hunger modifier x%.2f)\n", glut.glut * 100, glut.gmod));

        BAttrWnd.FoodMeter feps = battr.feps;
        double curFep = 0;
        for(BAttrWnd.FoodMeter.El el : feps.els) {curFep += el.a;}
        double pct = (feps.cap != 0) ? (curFep / feps.cap * 100) : 0;
        sb.append(String.format("FEP bar: %.2f / %.2f (%.1f%%)\n", curFep, feps.cap, pct));

        sb.append(feasting ? String.format("Feasting: yes (+%d%% table bonus)\n", tableBonus) : "Feasting: no (no table bonus applied)\n");

        List<String> satParts = new ArrayList<>();
        for(BAttrWnd.Constipations.El el : battr.cons.els) {
            String nm;
            try {
                Resource.Tooltip tt = el.t.res.get().layer(Resource.tooltip);
                nm = (tt != null) ? tt.t : "?";
            } catch(Exception ex) {
                nm = "?";
            }
            int pen = Math.max((int) Math.round((1.0 - el.a) * 100), 1);
            satParts.add(nm + " " + pen + "%");
        }
        sb.append("Satiations: ").append(satParts.isEmpty() ? "none" : String.join(", ", satParts));
        return sb.toString();
    }

    /** Small numbered badge drawn over a ranked WItem in its own inventory -- purely visual, never intercepts clicks/drag (Widget's default mousedown/mouseup already return false). */
    private static class RankBadge extends Widget {
        final int rank;
        RankBadge(Coord sz, int rank) {
            super(sz);
            this.rank = rank;
        }

        @Override
        public void draw(GOut g) {
            Coord bsz = UI.scale(new Coord(18, 14));
            Coord c = sz.sub(bsz);
            g.chcolor(255, 220, 60, 235);
            g.frect(c, bsz);
            g.chcolor(Color.BLACK);
            g.rect(c, bsz.sub(1, 1));
            g.chcolor();
            g.atext(String.valueOf(rank), c.add(bsz.div(2)), 0.5, 0.5);
        }
    }

    private Tex icon(WItem w) {
        try {
            return ItemIconUtil.loadIcon(w.item.resname(), ICON_SZ);
        } catch(Exception e) {
            return null;
        }
    }

    private class GroupedList extends Listbox<PlanGroup> {
        private List<PlanGroup> items = Collections.emptyList();

        GroupedList(int w, int h) {
            super(w, h, ROWH);
            bgcolor = new Color(0, 0, 0, 90);
        }

        void setItems(List<PlanGroup> items) {
            this.items = items;
            sb.val = 0;
        }

        @Override
        protected PlanGroup listitem(int i) {return items.get(i);}

        @Override
        protected int listitems() {return items.size();}

        @Override
        protected void drawitem(GOut g, PlanGroup pg, int idx) {
            g.chcolor((idx % 2 == 0) ? new Color(255, 255, 255, 8) : new Color(255, 255, 255, 18));
            g.frect(Coord.z, g.sz());
            g.chcolor();
            g.atext(String.valueOf(idx + 1), new Coord(UI.scale(4), ROWH / 2), 0, 0.5);
            int x = UI.scale(22);
            Tex t = icon(pg.sample.witem);
            if(t != null) {
                g.image(t, new Coord(x, (ROWH - t.sz().y) / 2));
                x += ICON_SZ.x + UI.scale(4);
            }
            String name;
            try {
                Resource.Tooltip tt = pg.sample.witem.item.getres().layer(Resource.tooltip);
                name = (tt != null) ? tt.t : pg.sample.witem.item.resname();
            } catch(Exception ex) {
                name = "?";
            }
            g.atext(String.format("%dx %s", pg.count, name), new Coord(x, ROWH / 2), 0, 0.5);
            g.atext(String.format("%s +%.2f | cum %.2f", (selectedStat != null) ? selectedStat : "?",
                pg.targetGain, pg.cumTargetAfter), new Coord(sz.x - (sb.vis() ? sb.sz.x : 0) - UI.scale(4), ROWH / 2), 1, 0.5);
        }
    }
}
