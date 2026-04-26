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
 * Detection is sfx-driven, with a server-movement probe to short-circuit
 * the silent no-milk case (server simply doesn't queue the walk).
 *
 * Pipeline:
 *   1. Right-click a cattle gob -> {@link #armPending} captures the cattle
 *      UID and snapshots the player gob's position + the distance to the cow.
 *   2. Adaptive TTL = 1000ms base + ~150ms per tile of distance, capped at
 *      15s. Covers slow walks; the sfx usually arrives much sooner.
 *   3. Movement probe at +500ms (driven by {@code GItem.tick}): if the player
 *      hasn't started moving AND the cow is more than ~2 tiles away, the
 *      server rejected the click outright -- end early as
 *      {@code rejected_no_movement}.
 *   4. Server processes the action; on success, {@code RootWidget.uimsg("sfx")}
 *      dispatches the milking sound (resource {@code sfx/fx/water}).
 *      {@link #onSfx} resolves: clears the roster mark and (unless any
 *      milk container in main inventory is at capacity) un-memorizes so
 *      the floating name disappears.
 *   5. Pending expires at the adaptive deadline if no sfx arrives.
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
    /** Estimated player run speed, tiles per second. */
    private static final double RUN_SPEED_TPS = 7.0;
    /** Per-tile travel-time budget added to TTL. */
    private static final long PER_TILE_MS = (long)(1000.0 / RUN_SPEED_TPS);
    /** Base + buffer added to the adaptive TTL (action processing + sfx). */
    private static final long BASE_TTL_MS = 1000;
    /** Maximum TTL cap, regardless of distance. */
    private static final long MAX_TTL_MS = 15000;
    /** How long after arm to expect the server to have started a walk. */
    private static final long MOVEMENT_PROBE_MS = 500;
    /** Within this distance, no walk is needed; skip the movement probe. */
    private static final double ADJACENT_RANGE_UNITS = 2 * TILE_UNITS;
    /** A position delta below this is considered "still standing." */
    private static final double STILL_EPSILON_UNITS = 0.1;

    private static final MilkingAssist INSTANCE = new MilkingAssist();
    public static MilkingAssist get() { return INSTANCE; }

    public static class Pending {
	final UID cattleId;
	final long armMs;
	final long deadline;
	final long movementProbeAt;
	final long playerGobId;
	final Coord2d playerRcAtArm;
	final double distanceUnits;
	boolean movementSeen;

	Pending(UID id, long playerGobId, Coord2d playerRcAtArm, double distanceUnits) {
	    this.cattleId = id;
	    this.armMs = System.currentTimeMillis();
	    this.distanceUnits = distanceUnits;
	    long ttl = BASE_TTL_MS + (long)((distanceUnits / TILE_UNITS) * PER_TILE_MS);
	    this.deadline = armMs + Math.min(MAX_TTL_MS, ttl);
	    this.movementProbeAt = armMs + MOVEMENT_PROBE_MS;
	    this.playerGobId = playerGobId;
	    this.playerRcAtArm = playerRcAtArm;
	    this.movementSeen = distanceUnits <= ADJACENT_RANGE_UNITS;
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
	Pending pending = new Pending(cid.id, player.id, playerRc, distance);

	INSTANCE.cap.endIfActive("superseded", endMeta(null, null));
	INSTANCE.observer.setPending(pending);

	JSONObject meta = new JSONObject();
	meta.put("uid", cid.id.toString());
	meta.put("source", source);
	meta.put("distance_units", distance);
	meta.put("distance_tiles", distance / TILE_UNITS);
	meta.put("ttl_ms", pending.deadline - pending.armMs);
	meta.put("expects_movement", !pending.movementSeen);
	if(ui.sess != null) INSTANCE.cap.beginIfArmed(ui.sess, meta);
    }

    /** Hook from RootWidget.uimsg("sfx") -- the milking sound is the resolve trigger. */
    public static void onSfx(UI ui, Indir<Resource> resid) {
	Pending p = INSTANCE.observer.peekPending();
	if(p == null) return;
	if(System.currentTimeMillis() > p.deadline) {
	    INSTANCE.observer.clearPending();
	    INSTANCE.cap.endIfActive("expired", endMeta(p.cattleId, null));
	    return;
	}
	String name;
	try { name = resid.get().name; }
	catch(Loading l) { return; }
	catch(RuntimeException re) { return; }
	if(name == null || !isMilkSfx(name)) return;
	INSTANCE.resolveBySfx(ui, name);
    }

    /** Hook from GItem.tick -- drives passive expiry, movement probe, and post-walk timeout. */
    public static void onItemTick(GItem item) { INSTANCE.driveTimers(item); }

    private void driveTimers(GItem item) {
	Pending p = observer.peekPending();
	if(p == null) return;
	long now = System.currentTimeMillis();
	if(now > p.deadline) {
	    observer.clearPending();
	    cap.endIfActive("expired", endMeta(p.cattleId, null));
	    return;
	}
	if(p.movementSeen) return;
	if(now < p.movementProbeAt) return;
	UI ui = item.ui;
	if(ui == null || ui.gui == null || ui.gui.map == null) return;
	Gob player = ui.gui.map.player();
	if(player == null || player.id != p.playerGobId) return;

	boolean moving = (player.getattr(Moving.class) != null);
	boolean displaced = (player.rc != null && player.rc.dist(p.playerRcAtArm) > STILL_EPSILON_UNITS);
	if(moving || displaced) {
	    p.movementSeen = true;
	    return;
	}
	// Past probe, distance was non-adjacent, no walk queued, no displacement
	// -- server rejected the click outright (cow has no milk, etc.).
	observer.clearPending();
	cap.endIfActive("rejected_no_movement", endMeta(p.cattleId, null));
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
    static void    debugClearPending()   { INSTANCE.observer.clearPending(); }
}
