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

    /**
     * Range-select from a shift-click. Mutates {@code marks} in place.
     * If {@code clicked} sits between two already-marked indices, fills the
     * entire span from the nearest upward anchor to the nearest downward
     * anchor (inclusive). If only one side has a marked anchor, fills from
     * that anchor to the clicked index. If nothing else is marked, only the
     * clicked index is set.
     */
    public static void shiftClickRange(boolean[] marks, int clicked) {
	if(clicked < 0 || clicked >= marks.length) return;
	int up = -1;
	for(int i = clicked - 1; i >= 0; i--) if(marks[i]) { up = i; break; }
	int down = -1;
	for(int i = clicked + 1; i < marks.length; i++) if(marks[i]) { down = i; break; }
	int lo, hi;
	if(up >= 0 && down >= 0) { lo = up; hi = down; }
	else if(up >= 0) { lo = up; hi = clicked; }
	else if(down >= 0) { lo = clicked; hi = down; }
	else { marks[clicked] = true; return; }
	for(int i = lo; i <= hi; i++) marks[i] = true;
    }

    /**
     * Extend the selection from the topmost currently-marked index up to index
     * 0, inclusive. Returns false (and does nothing) if no index is marked.
     */
    public static boolean selectToTop(boolean[] marks) {
	int first = -1;
	for(int i = 0; i < marks.length; i++) if(marks[i]) { first = i; break; }
	if(first < 0) return(false);
	for(int i = 0; i <= first; i++) marks[i] = true;
	return(true);
    }

    /**
     * Extend the selection from the bottommost currently-marked index down to
     * the last index, inclusive. Returns false (and does nothing) if no index
     * is marked.
     */
    public static boolean selectToBottom(boolean[] marks) {
	int last = -1;
	for(int i = marks.length - 1; i >= 0; i--) if(marks[i]) { last = i; break; }
	if(last < 0) return(false);
	for(int i = last; i < marks.length; i++) marks[i] = true;
	return(true);
    }
}
