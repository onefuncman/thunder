package thunder.roster;

import java.util.Comparator;
import java.util.List;

/**
 * Pure logic helpers for the cattle roster. Extracted from {@code CattleRoster}
 * so the non-UI pieces are unit-testable without spinning up a full Haven widget
 * tree.
 */
public class RosterLogic {
    private RosterLogic() {}

    /**
     * Mark-preservation decision for {@code "upd"} messages on the roster.
     *
     * <p>The server sends {@code upd} whenever a roster entry's data changes
     * (name, quality, lactate, branding, etc). The old entry is destroyed and
     * a fresh one is added, so its {@code mark} checkbox state would normally
     * be lost. We restore it unless the update represents a completed milking
     * (lactate flipped true -> false) and milking-assist is enabled, in which
     * case we leave the new entry unmarked so it disappears from the active
     * selection.
     *
     * @return true if the caller should re-apply the old mark state to the
     *         newly-added entry.
     */
    public static boolean shouldRestoreMark(boolean wasMarked,
					    boolean wasLactating,
					    boolean nowLactating,
					    boolean milkingAssistOn) {
	if(!wasMarked) return(false);
	boolean justMilked = wasLactating && !nowLactating;
	return(!(milkingAssistOn && justMilked));
    }

    /**
     * Build the effective sort comparator from a user-selected primary and
     * (optional) secondary. Either may be null. {@code tieBreaker} is appended
     * when the primary is non-null and differs from the tie-breaker itself,
     * so sort order is stable across equal primary-plus-secondary keys.
     */
    public static <T> Comparator<? super T> combineOrder(Comparator<? super T> primary,
							 boolean revPrimary,
							 Comparator<? super T> secondary,
							 boolean revSecondary,
							 Comparator<? super T> tieBreaker) {
	@SuppressWarnings({"rawtypes", "unchecked"})
	Comparator result = (primary != null) ? (revPrimary ? primary.reversed() : primary) : tieBreaker;
	if(secondary != null && secondary != primary) {
	    @SuppressWarnings({"rawtypes", "unchecked"})
	    Comparator sec = revSecondary ? secondary.reversed() : secondary;
	    result = result.thenComparing(sec);
	}
	if(tieBreaker != null && primary != null && primary != tieBreaker)
	    result = result.thenComparing(tieBreaker);
	return(result);
    }

    /** Mutable {x, w, runon} triple used by the column-layout helpers. */
    public static final class ColSpec {
	public int w;
	public final boolean runon;
	public int x;
	public ColSpec(int w, boolean runon) { this.w = w; this.runon = runon; }
	public ColSpec(int w, boolean runon, int x) { this.w = w; this.runon = runon; this.x = x; }
    }

    /**
     * Assign x positions to a sequence of columns starting at {@code startX}.
     * {@code runonGap} is applied after run-on columns, {@code normalGap}
     * after regular columns. Mutates the passed specs in place.
     */
    public static void packColumns(List<ColSpec> cols, int startX, int runonGap, int normalGap) {
	int x = startX;
	for(ColSpec col : cols) {
	    col.x = x;
	    x += col.w;
	    x += col.runon ? runonGap : normalGap;
	}
    }

    /**
     * Return the index of the column whose right edge is within {@code grab}
     * pixels of the hit point, or -1 if none. Only columns inside the header
     * band (y in [0, headH)) are considered.
     */
    public static int columnAtEdge(List<ColSpec> cols, int px, int py, int headH, int grab) {
	if(py < 0 || py >= headH) return(-1);
	for(int i = 0; i < cols.size(); i++) {
	    ColSpec col = cols.get(i);
	    int edge = col.x + col.w;
	    if(px >= edge - grab && px <= edge + grab) return(i);
	}
	return(-1);
    }

    /**
     * Resize a column while respecting a minimum width, and return whether
     * the width actually changed. After calling, the caller should re-run
     * {@link #packColumns} so downstream x positions stay consistent.
     */
    public static boolean applyResize(ColSpec col, int minW, int grabStartW, int deltaX) {
	int nw = Math.max(minW, grabStartW + deltaX);
	if(nw == col.w) return(false);
	col.w = nw;
	return(true);
    }
}
