package thunder;

import haven.*;
import haven.dev.FeatureCapture;
import haven.res.ui.croster.CattleId;
import haven.res.ui.croster.CattleRoster;
import haven.res.ui.croster.Entry;
import haven.res.ui.croster.RosterWindow;
import haven.res.ui.tt.level.Level;
import org.json.JSONObject;

/**
 * Auto-deselects a cattle in the roster after it is milked.
 *
 * Detection is sfx-driven, with the action lifecycle tracked through the
 * player gob's movement attrs instead of distance-estimated timeouts.
 *
 * Phases (driven by {@code GItem.tick} via {@link #driveTimers}):
 *   ACCEPT   - armed by the right-click. A non-adjacent target must see the
 *              server start the approach walk (ideally OD_HOMING carrying
 *              our target's gob id) within {@link #ACCEPT_WINDOW_MS}, else
 *              the click was rejected ({@code rejected_no_movement}).
 *              Adjacent targets skip straight to ACTING.
 *   EN_ROUTE - the walk is tracked, not estimated: alive while the player's
 *              {@link Moving} attr persists (a chase of a wandering animal
 *              takes as long as it takes; {@link EN_ROUTE_SANITY_MS} caps
 *              pathological stalls). A homing that switches to another gob
 *              cancels the pending ({@code cancelled_retargeted}).
 *   ACTING   - the walk ended; the milk sfx ({@code sfx/fx/water} via
 *              {@code RootWidget.uimsg("sfx")}) must arrive within
 *              {@link #ACTION_WINDOW_MS}. {@link #onSfx} resolves: clears
 *              the roster mark and (unless any milk container in main
 *              inventory is at capacity) un-memorizes so the floating name
 *              disappears. No sfx = expired (no-milk is signal-free).
 *
 * An sfx heard before ACTING cannot be ours (the player hasn't arrived), so
 * it is ignored -- this kills misattribution of the previous animal's late
 * sfx to a freshly armed pending.
 *
 * Why sfx and not chres on inventory items: empirically, a cattle right-click
 * fires no chres on the bucket if the milk pours into a nearby barrel.
 * The chres traffic that *does* coincide with a milk attempt is the
 * curio-progress tick on a study window, not buckets at all. Sfx is the
 * only protocol signal that consistently fires only on actual milk
 * completion.
 *
 * Server's {@code lactate} flag never flips back to false in practice
 * (animals stay lactating until they hibernate from starvation), so any
 * roster-side backstop on that transition would be dead code.
 *
 * Forensics: {@code dev.milk.capture} arms a one-shot {@link FeatureCapture}.
 * The next {@link #armPending} begins recording protocol traffic via
 * {@code Session.protoBus}; resolve, expiry, or rejection ends the
 * capture and writes a JSONL to {@code play/dev-snapshots/milk/}.
 */
public class MilkingAssist {
    /** World units per tile (matches {@link MCache#tilesz}). */
    private static final double TILE_UNITS = 11.0;
    /** Within this distance, no walk is needed; the pending starts ACTING. */
    private static final double ADJACENT_RANGE_UNITS = 2 * TILE_UNITS;
    /** How long after arm the server gets to start the approach walk.
     * Observed OD_HOMING at +0.084s (capture-expired-20260902-114026);
     * generous for latency. No walk inside this window on a non-adjacent
     * target = the click was rejected. */
    static final long ACCEPT_WINDOW_MS = 1500;
    /** Sanity cap on the walk itself. The walk is tracked by the player's
     * Moving/Homing attrs, not estimated -- a chase takes as long as it
     * takes -- but a pending shouldn't outlive a pathological stall. */
    static final long EN_ROUTE_SANITY_MS = 60000;
    /** Post-arrival window for the milk sfx. The action takes ~1.0-1.5s
     * between arrival and sfx (capture-expired-20260426-144457: click
     * +0.000s, sfx +1.034s on an adjacent attempt). */
    static final long ACTION_WINDOW_MS = 3000;
    /** Minimum time granted to finish loading an sfx that arrived in-window. */
    private static final long SFX_LOAD_GRACE_MS = 5000;
    /** An sfx heard while still walking counts as ours if the walk ends
     * within this window after it -- arrival objdata and the sfx uimsg can
     * land in the same server batch in either order. */
    static final long ARRIVAL_RACE_MS = 500;

    private static final MilkingAssist INSTANCE = new MilkingAssist();
    public static MilkingAssist get() { return INSTANCE; }

    /**
     * Action lifecycle, driven by the player gob's movement attrs rather
     * than distance-estimated timeouts. The server homes the player on the
     * clicked gob (OD_HOMING carries the target id), so accept, travel, and
     * arrival are all observable; only the post-arrival window is a timer,
     * because a no-milk rejection at melee range is signal-free and the sfx
     * carries no gob reference.
     */
    enum Phase { ACCEPT, EN_ROUTE, ACTING }

    public static class Pending {
	final UID cattleId;
	final long armMs;
	Phase phase;
	/** Deadline for the CURRENT phase (also read by MilkingAssistDebug). */
	long deadline;
	final long playerGobId;
	final Coord2d playerRcAtArm;
	final long targetGobId;
	final Coord2d targetRcAtArm;
	final double distanceUnits;
	/* An sfx that arrived while ACTING but whose resource was still
	 * loading (typical when the uimsg lands together with its first
	 * RMSG_RESID binding). The message is one-shot, so it is stashed
	 * here and re-checked from driveTimers until it loads. */
	Indir<Resource> loadingSfx;
	UI loadingSfxUi;
	/* The last sfx heard before ACTING, kept in case it was the arrival
	 * race: the walk-clear objdata and the sfx uimsg can arrive in the
	 * same batch in either order. Consumed on the EN_ROUTE -> ACTING
	 * transition if it landed within ARRIVAL_RACE_MS of the walk end. */
	Indir<Resource> preArrivalSfx;
	UI preArrivalSfxUi;
	long preArrivalSfxAtMs;

	Pending(UID id, long playerGobId, Coord2d playerRcAtArm, double distanceUnits) {
	    this(id, playerGobId, playerRcAtArm, distanceUnits, -1, null);
	}

	Pending(UID id, long playerGobId, Coord2d playerRcAtArm, double distanceUnits,
		long targetGobId, Coord2d targetRcAtArm) {
	    this.cattleId = id;
	    this.armMs = System.currentTimeMillis();
	    this.distanceUnits = distanceUnits;
	    this.playerGobId = playerGobId;
	    this.playerRcAtArm = playerRcAtArm;
	    this.targetGobId = targetGobId;
	    this.targetRcAtArm = targetRcAtArm;
	    if(distanceUnits <= ADJACENT_RANGE_UNITS) {
		// No walk needed; the action starts with the click.
		beginActing(armMs);
	    } else {
		this.phase = Phase.ACCEPT;
		this.deadline = armMs + ACCEPT_WINDOW_MS;
	    }
	}

	final void beginEnRoute(long now) {
	    this.phase = Phase.EN_ROUTE;
	    this.deadline = now + EN_ROUTE_SANITY_MS;
	}

	final void beginActing(long now) {
	    this.phase = Phase.ACTING;
	    this.deadline = now + ACTION_WINDOW_MS;
	}
    }

    private final InventoryActionObserver<Pending> observer = new InventoryActionObserver<>();
    private final FeatureCapture cap = new FeatureCapture("milk");
    public FeatureCapture capture() { return cap; }

    /** Hook from MapView.click(Gob, int, ...) -- empty-hand right-click. */
    public static void onGobRightClick(Gob gob, int button) {
	if(button != 3) return;
	armPending(gob, "right_click");
    }

    /** Hook from MapView.iteminteract -- right-click with item (bucket) in hand. */
    public static void onItemInteract(Gob gob) {
	armPending(gob, "item_interact");
    }

    private static void armPending(Gob gob, String source) {
	if(gob == null) return;
	CattleId cid = gob.getattr(CattleId.class);
	if(cid == null) return;
	if(!isMilkingAssistOn(gob)) return;
	UI ui = uiFor(gob);
	if(ui == null || ui.gui == null || ui.gui.map == null) return;
	Gob player = ui.gui.map.player();
	if(player == null) return;
	Coord2d playerRc = player.rc;
	double distance = playerRc.dist(gob.rc);
	Pending pending = new Pending(cid.id, player.id, playerRc, distance, gob.id, gob.rc);

	INSTANCE.cap.endIfActive("superseded", endMeta(null, null));
	INSTANCE.observer.setPending(pending);

	JSONObject meta = new JSONObject();
	meta.put("uid", cid.id.toString());
	meta.put("source", source);
	meta.put("distance_units", distance);
	meta.put("distance_tiles", distance / TILE_UNITS);
	meta.put("initial_phase", pending.phase.name());
	meta.put("initial_window_ms", pending.deadline - pending.armMs);
	meta.put("expects_movement", pending.phase == Phase.ACCEPT);
	meta.put("player_gob", player.id);
	meta.put("target_gob", gob.id);
	if(playerRc != null) meta.put("player_rc", playerRc.toString());
	if(gob.rc != null) meta.put("target_rc", gob.rc.toString());
	if(ui.sess != null) INSTANCE.cap.beginIfArmed(ui.sess, meta);
	INSTANCE.cap.note(String.format("milk: armed uid=%s dist=%.2f tiles phase=%s window=%dms",
					cid.id, distance / TILE_UNITS, pending.phase,
					pending.deadline - pending.armMs),
			  null, gob.id);
    }

    /** Hook from RootWidget.uimsg("sfx") -- the milking sound is the resolve trigger. */
    public static void onSfx(UI ui, Indir<Resource> resid) {
	Pending p = INSTANCE.observer.peekPending();
	if(p == null) return;
	if(p.phase != Phase.ACTING) {
	    // Our own milk sfx cannot fire before arrival -- but "arrival"
	    // is known only through the Moving attr, and the clearing
	    // objdata can be ordered after the sfx uimsg in the same batch.
	    // Hold the sfx; the EN_ROUTE -> ACTING transition consumes it
	    // if the walk ends within ARRIVAL_RACE_MS.
	    p.preArrivalSfx = resid;
	    p.preArrivalSfxUi = ui;
	    p.preArrivalSfxAtMs = System.currentTimeMillis();
	    INSTANCE.cap.note("milk: sfx during " + p.phase + " -- held for arrival race", null, 0);
	    return;
	}
	if(System.currentTimeMillis() > p.deadline) {
	    INSTANCE.cap.note("milk: sfx heard past deadline -- expiring pending", "uid=" + p.cattleId, 0);
	    INSTANCE.observer.clearPending();
	    INSTANCE.cap.endIfActive("expired", INSTANCE.expiryMeta(p, ui));
	    return;
	}
	String name;
	try { name = resid.get().name; }
	catch(Loading l) {
	    if(p.loadingSfx == null) {
		p.loadingSfx = resid;
		p.loadingSfxUi = ui;
		p.deadline = Math.max(p.deadline, System.currentTimeMillis() + SFX_LOAD_GRACE_MS);
		INSTANCE.cap.note("milk: sfx still loading -- stashed, deadline now +"
				  + (p.deadline - p.armMs) + "ms", null, 0);
	    } else {
		INSTANCE.cap.note("milk: sfx still loading -- stash occupied, DROPPED", null, 0);
	    }
	    return;
	}
	catch(RuntimeException re) {
	    INSTANCE.cap.note("milk: sfx resid.get threw " + re.getClass().getSimpleName(), null, 0);
	    return;
	}
	if(name == null || !isMilkSfx(name)) {
	    INSTANCE.cap.note("milk: sfx ignored (non-milk): " + name, null, 0);
	    return;
	}
	INSTANCE.cap.note("milk: milk sfx " + name + " at +" + (System.currentTimeMillis() - p.armMs)
			  + "ms -- resolving", null, 0);
	INSTANCE.resolveBySfx(ui, name);
    }

    /** Hook from GItem.tick -- drives passive expiry, movement probe, and post-walk timeout. */
    public static void onItemTick(GItem item) { INSTANCE.driveTimers(item); }

    private void driveTimers(GItem item) {
	Pending p = observer.peekPending();
	if(p == null) return;
	long now = System.currentTimeMillis();
	if(now > p.deadline) {
	    String outcome = (p.phase == Phase.ACCEPT) ? "rejected_no_movement" : "expired";
	    if(p.phase == Phase.ACCEPT)
		cap.note("milk: no walk started within accept window -- click rejected", null, 0);
	    observer.clearPending();
	    cap.endIfActive(outcome, expiryMeta(p, (item != null) ? item.ui : null));
	    return;
	}
	if(p.loadingSfx != null) {
	    String name;
	    try { name = p.loadingSfx.get().name; }
	    catch(Loading l) { return; }
	    catch(RuntimeException re) {
		cap.note("milk: stashed sfx load failed: " + re.getClass().getSimpleName(), null, 0);
		p.loadingSfx = null;
		p.loadingSfxUi = null;
		return;
	    }
	    UI sfxUi = p.loadingSfxUi;
	    p.loadingSfx = null;
	    p.loadingSfxUi = null;
	    if(name != null && isMilkSfx(name)) {
		cap.note("milk: stashed sfx loaded as " + name + " -- resolving", null, 0);
		resolveBySfx(sfxUi, name);
		return;
	    }
	    cap.note("milk: stashed sfx loaded as non-milk " + name + " -- discarded", null, 0);
	}
	if(p.phase == Phase.ACTING) return;
	UI ui = (item != null) ? item.ui : null;
	if(ui == null || ui.gui == null || ui.gui.map == null) return;
	Gob player = ui.gui.map.player();
	if(player == null || player.id != p.playerGobId) return;

	Moving moving = player.getattr(Moving.class);
	if(p.phase == Phase.ACCEPT) {
	    if(moving instanceof Homing && ((Homing)moving).tgt == p.targetGobId) {
		cap.note(String.format("milk: homing on target at +%dms -- en route", now - p.armMs),
			 null, p.targetGobId);
		p.beginEnRoute(now);
	    } else if(moving != null) {
		// A walk started but not a homing on our target: either the
		// server used a plain move, or the player was mid-walk when
		// they clicked. Treat as accepted; arrival still gates ACTING.
		cap.note(String.format("milk: %s at +%dms -- en route (no homing link)",
				       moving.getClass().getSimpleName(), now - p.armMs), null, 0);
		p.beginEnRoute(now);
	    }
	    return;
	}
	// EN_ROUTE: track the walk itself instead of estimating it.
	if(moving == null) {
	    cap.note(String.format("milk: walk ended at +%dms -- acting window %dms",
				   now - p.armMs, ACTION_WINDOW_MS), null, 0);
	    enterActing(p, now);
	    return;
	}
	if(moving instanceof Homing && ((Homing)moving).tgt != p.targetGobId) {
	    cap.note("milk: homing retargeted to gob " + ((Homing)moving).tgt + " -- cancelled",
		     null, ((Homing)moving).tgt);
	    observer.clearPending();
	    cap.endIfActive("cancelled_retargeted", expiryMeta(p, ui));
	}
    }

    /**
     * EN_ROUTE -> ACTING: start the action window and re-evaluate an sfx
     * heard just before the walk-clear objdata (arrival race). Routing it
     * through {@link #onSfx} reuses the normal ACTING machinery: a loaded
     * milk sfx resolves, a still-loading one goes to the retry stash, and
     * anything else is ignored.
     */
    private void enterActing(Pending p, long now) {
	p.beginActing(now);
	Indir<Resource> held = p.preArrivalSfx;
	UI heldUi = p.preArrivalSfxUi;
	long heldAt = p.preArrivalSfxAtMs;
	p.preArrivalSfx = null;
	p.preArrivalSfxUi = null;
	if(held == null) return;
	if(now - heldAt > ARRIVAL_RACE_MS) {
	    cap.note("milk: held sfx preceded walk end by " + (now - heldAt) + "ms -- discarded", null, 0);
	    return;
	}
	cap.note("milk: held sfx within arrival race window -- evaluating", null, 0);
	onSfx(heldUi, held);
    }

    private void resolveBySfx(UI ui, String sfxResname) {
	Pending p = observer.peekPending();
	if(p == null) return;
	if(ui == null || ui.gui == null) return;
	RosterWindow rw = findRosterWindow(ui);
	if(rw == null) return;

	boolean containerFull = anyMilkContainerFull(ui);

	for(CattleRoster<?> r : rw.children(CattleRoster.class)) {
	    Entry e = r.entries.get(p.cattleId);
	    if(e == null) continue;
	    if(e.mark.a) e.mark.set(false);
	    if(!containerFull) rw.unmemorize(p.cattleId);
	    observer.clearPending();
	    String outcome = containerFull ? "resolved_container_full" : "resolved";
	    cap.endIfActive(outcome, endMeta(p.cattleId, sfxResname));
	    return;
	}
	// Fell through every roster without finding the entry: the pending
	// stays armed (it may still expire) but this was previously invisible.
	cap.note("milk: resolve found NO roster entry for uid=" + p.cattleId, null, 0);
    }

    /**
     * Rich end-of-pending metadata for expiry/rejection outcomes: where the
     * player and target were, whether the player was still walking, and the
     * pending's internal state. Every field is best-effort.
     */
    private JSONObject expiryMeta(Pending p, UI ui) {
	JSONObject o = endMeta(p.cattleId, null);
	o.put("phase", p.phase.name());
	o.put("sfx_stash_pending", p.loadingSfx != null);
	o.put("age_ms", System.currentTimeMillis() - p.armMs);
	try {
	    if(ui == null || ui.gui == null || ui.gui.map == null) return o;
	    Gob player = ui.gui.map.player();
	    if(player != null && player.id == p.playerGobId) {
		o.put("player_moving_at_end", player.getattr(Moving.class) != null);
		if(player.rc != null) {
		    o.put("player_rc_at_end", player.rc.toString());
		    if(p.playerRcAtArm != null)
			o.put("player_displaced_tiles", player.rc.dist(p.playerRcAtArm) / TILE_UNITS);
		}
	    }
	    if(p.targetGobId >= 0 && ui.sess != null) {
		Gob target = ui.sess.glob.oc.getgob(p.targetGobId);
		if(target == null) {
		    o.put("target_gone", true);
		} else if(target.rc != null) {
		    o.put("target_rc_at_end", target.rc.toString());
		    if(p.targetRcAtArm != null)
			o.put("target_displaced_tiles", target.rc.dist(p.targetRcAtArm) / TILE_UNITS);
		    if(player != null && player.rc != null)
			o.put("dist_remaining_tiles", player.rc.dist(target.rc) / TILE_UNITS);
		}
	    }
	} catch(RuntimeException ignored) {}
	return o;
    }

    /** True if any milk-content item in the main inventory is at capacity. */
    private static boolean anyMilkContainerFull(UI ui) {
	if(ui == null || ui.gui == null || ui.gui.maininv == null) return false;
	try {
	    for(WItem wi : ui.gui.maininv.children(WItem.class)) {
		ItemData.Content c = wi.item.contains.get();
		if(c == null || c.name == null) continue;
		if(!c.name.toLowerCase().contains("milk")) continue;
		try {
		    Level lvl = ItemInfo.find(Level.class, wi.item.info());
		    if(lvl != null && lvl.cur >= lvl.max) return true;
		} catch(Loading l) { /* skip */ }
	    }
	} catch(RuntimeException ignored) {}
	return false;
    }

    private static boolean isMilkSfx(String resname) {
	return resname.equals("sfx/fx/water") || resname.startsWith("sfx/fx/milk");
    }

    private static JSONObject endMeta(UID uid, String trigger) {
	JSONObject o = new JSONObject();
	if(uid != null) o.put("uid", uid.toString());
	if(trigger != null) o.put("trigger", trigger);
	return o;
    }

    private static UI uiFor(Gob gob) {
	try {
	    RosterWindow rw = RosterWindow.rosters.get(gob.glob);
	    return rw != null ? rw.ui : null;
	} catch(Exception e) { return null; }
    }

    private static RosterWindow findRosterWindow(UI ui) {
	if(ui == null || ui.sess == null) return null;
	return RosterWindow.rosters.get(ui.sess.glob);
    }

    private static boolean isMilkingAssistOn(Gob gob) {
	try {
	    RosterWindow rw = RosterWindow.rosters.get(gob.glob);
	    return rw != null && rw.milkingAssist;
	} catch(Exception e) { return false; }
    }

    // -- Debug accessors (used by thunder.MilkingAssistDebug). Package-private
    // so the public API stays a tight set of hooks.
    static Pending debugPeekPending()    { return INSTANCE.observer.peekPending(); }
    static int     debugRetryCount()     { return INSTANCE.observer.retryCount(); }
    static void    debugSetPending(UID id) {
	if(id == null) return;
	INSTANCE.observer.setPending(new Pending(id, -1, null, 0));
    }
    static void    debugSetPending(Pending p) { INSTANCE.observer.setPending(p); }
    static void    debugEnterActing(Pending p, long now) { INSTANCE.enterActing(p, now); }
    static void    debugClearPending()   { INSTANCE.observer.clearPending(); }
}
