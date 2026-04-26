package thunder;

import haven.*;
import haven.res.ui.croster.CattleId;
import haven.res.ui.croster.CattleRoster;
import haven.res.ui.croster.Entry;
import haven.res.ui.croster.RosterWindow;

/**
 * Auto-deselects a cattle in the roster after it is milked.
 *
 * Detection pipeline (mirrors TileQuality's attribution pattern; see
 * {@link InventoryActionObserver} for the shared scaffolding):
 *   1. Player right-clicks a cattle gob with a carried container -> we set
 *      the pending cattle UID from {@link #onGobRightClick}.
 *   2. Server fills inventory containers and broadcasts `chres` + `tt` on
 *      those GItems.
 *   3. Hooks in {@link GItem#uimsg} call {@link #onItemChres} /
 *      {@link #onItemInfoUpdate}; first matching update within the window
 *      resolves the pending UID, deselecting the matching roster entry.
 *   4. Pending expires after {@link #PENDING_TTL_MS} if no update arrives.
 *
 * Only the primary detection layer. A simpler backstop (server lactate flag
 * flipping true->false on an `upd` message) lives in
 * {@link CattleRoster#uimsg}; together they cover both fast ack (layer B
 * here) and eventual consistency (layer A in CattleRoster).
 */
public class MilkingAssist {
    public static final long PENDING_TTL_MS = 5000;

    private static final MilkingAssist INSTANCE = new MilkingAssist();
    public static MilkingAssist get() { return INSTANCE; }

    public static class Pending {
	final UID cattleId;
	final long deadline;
	Pending(UID id) { this.cattleId = id; this.deadline = System.currentTimeMillis() + PENDING_TTL_MS; }
    }

    private final InventoryActionObserver<Pending> observer = new InventoryActionObserver<>();

    /** Hook from MapView.click(Gob, int, ...) -- fires once per gob click. */
    public static void onGobRightClick(Gob gob, int button) {
	if(button != 3 || gob == null) return;
	CattleId cid = gob.getattr(CattleId.class);
	if(cid == null) return;
	if(!isMilkingAssistOn(gob)) return;
	INSTANCE.observer.setPending(new Pending(cid.id));
    }

    /** Hook from GItem.uimsg "chres" -- the strong signal that milking succeeded. */
    public static void onItemChres(GItem item) { INSTANCE.tryResolve(item); }

    /** Hook from GItem.uimsg "tt" / info refresh. */
    public static void onItemInfoUpdate(GItem item) { INSTANCE.tryResolve(item); }

    /** Hook from GItem.tick -- retries after info load. */
    public static void onItemTick(GItem item) {
	Pending p = INSTANCE.observer.retryFor(item);
	if(p != null) INSTANCE.tryResolve(item);
    }

    private void tryResolve(GItem item) {
	Pending p = observer.peekPending();
	if(p == null) return;
	if(System.currentTimeMillis() > p.deadline) { observer.clearPending(); return; }
	UI ui = item.ui;
	if(ui == null || ui.gui == null) return;
	RosterWindow rw = findRosterWindow(ui);
	if(rw == null) return;
	for(CattleRoster<?> r : rw.children(CattleRoster.class)) {
	    Entry e = r.entries.get(p.cattleId);
	    if(e == null) continue;
	    if(e.mark.a) e.mark.set(false);
	    observer.clearPending();
	    observer.dropRetry(item);
	    return;
	}
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
	INSTANCE.observer.setPending(new Pending(id));
    }
    static void    debugClearPending()   { INSTANCE.observer.clearPending(); }
}
