package thunder;


/**
 * Reusable plumbing for observing inventory reactions to a player action.
 *
 * The pattern (shared by TileQuality's mine/dig tracking and MilkingAssist's
 * milk-receipt tracking, and intended for future water-fill tracking on tile
 * quality): the player triggers an action on a world object, and a short time
 * later one or more GItem widgets in inventory create/transform (observable
 * via GItem.uimsg "chres"/"tt" and GItem.tick). The tricky part is attributing
 * the item update back to the action that caused it.
 *
 * Usage:
 *   1. Instantiate one of these per feature.
 *   2. Call {@link #setPending(Object)} from your action-triggered hook (e.g.
 *      a right-click on a gob, or the start of a mining stroke).
 *   3. Call {@link #clearPending()} when the action context ends (cursor
 *      changes, scope lost, etc.) so stale pendings don't attribute later
 *      unrelated updates.
 *   4. From hooks in GItem.uimsg("chres"/"tt") and GItem.tick, call
 *      {@link #peekPending()} and, on match, call your resolver. Move items
 *      whose resolution is delayed (info not yet loaded) into the retry set
 *      via {@link #retry(GItem)} and poll via {@link #retryFor(GItem)} from
 *      GItem.tick.
 *
 * Single-writer assumption: one player-driven action at a time. `setPending`
 * replaces any existing pending; in-flight items captured into `retries` keep
 * their own copy of the action snapshot so a later `setPending` does not lose
 * earlier attributions.
 */
public class InventoryActionObserver<T> {
    private final Object lock = new Object();
    private T currentPending;
    // WeakHashMap so destroyed items are GC-evicted without explicit cleanup.
    // Keys are typically GItem widgets but typed as Object so the observer
    // stays loosely coupled (and is testable without spinning up widgets).
    private final java.util.Map<Object, T> retries = new java.util.WeakHashMap<>();

    public void setPending(T action) {
	synchronized(lock) { currentPending = action; }
    }

    public void clearPending() {
	synchronized(lock) { currentPending = null; }
    }

    public T peekPending() {
	synchronized(lock) { return currentPending; }
    }

    /** Snapshot the current pending against {@code key} for later retry. */
    public void retry(Object key) {
	synchronized(lock) {
	    if(currentPending != null) retries.put(key, currentPending);
	}
    }

    /** Return the snapshot stashed for {@code key}, or null. */
    public T retryFor(Object key) {
	synchronized(lock) { return retries.get(key); }
    }

    public void dropRetry(Object key) {
	synchronized(lock) { retries.remove(key); }
    }

    /** Number of items currently waiting for a delayed resolve. */
    public int retryCount() {
	synchronized(lock) { return retries.size(); }
    }
}
