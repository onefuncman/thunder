package thunder.roster;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RosterLogicTest {

    // ----------------------------------------------------------------------
    // shouldRestoreMark
    // ----------------------------------------------------------------------

    @Test
    void unmarkedEntryStaysUnmarked() {
	assertFalse(RosterLogic.shouldRestoreMark(false, true, false, true));
	assertFalse(RosterLogic.shouldRestoreMark(false, false, false, false));
    }

    @Test
    void markedEntryWithoutMilkingAssistAlwaysRestores() {
	assertTrue(RosterLogic.shouldRestoreMark(true, true, false, false));  // lactate flipped
	assertTrue(RosterLogic.shouldRestoreMark(true, true, true, false));   // no flip
	assertTrue(RosterLogic.shouldRestoreMark(true, false, false, false));
    }

    @Test
    void milkingAssistDropsMarkOnLactateFlip() {
	assertFalse(RosterLogic.shouldRestoreMark(true, true, false, true));
    }

    @Test
    void milkingAssistKeepsMarkWhenLactateUnchanged() {
	assertTrue(RosterLogic.shouldRestoreMark(true, true, true, true));
	assertTrue(RosterLogic.shouldRestoreMark(true, false, false, true));
    }

    @Test
    void milkingAssistKeepsMarkWhenLactateGainedNotLost() {
	// false -> true is not a milk-completion signal.
	assertTrue(RosterLogic.shouldRestoreMark(true, false, true, true));
    }

    // ----------------------------------------------------------------------
    // combineOrder
    // ----------------------------------------------------------------------

    static class Row {
	final String name; final int grp; final int q;
	Row(String name, int grp, int q) { this.name = name; this.grp = grp; this.q = q; }
	@Override public String toString() { return name + "[grp=" + grp + ",q=" + q + "]"; }
    }

    static final Comparator<Row> byName = Comparator.comparing(r -> r.name);
    static final Comparator<Row> byGrp  = Comparator.comparingInt(r -> r.grp);
    static final Comparator<Row> byQ    = Comparator.comparingInt(r -> r.q);

    private static List<Row> sort(List<Row> rows, Comparator<? super Row> c) {
	List<Row> out = new ArrayList<>(rows);
	out.sort(c);
	return out;
    }

    @Test
    void primaryOnlySortsByThatColumn() {
	Comparator<? super Row> c = RosterLogic.combineOrder(byGrp, false, null, false, byName);
	List<Row> sorted = sort(Arrays.asList(
	    new Row("b", 2, 10), new Row("a", 1, 10), new Row("c", 1, 10)), c);
	assertEquals("a", sorted.get(0).name);
	assertEquals("c", sorted.get(1).name);
	assertEquals("b", sorted.get(2).name);
    }

    @Test
    void reversedPrimaryFlipsOrder() {
	Comparator<? super Row> c = RosterLogic.combineOrder(byGrp, true, null, false, byName);
	List<Row> sorted = sort(Arrays.asList(
	    new Row("a", 1, 10), new Row("b", 2, 10)), c);
	assertEquals(2, sorted.get(0).grp);
	assertEquals(1, sorted.get(1).grp);
    }

    @Test
    void secondaryBreaksTiesWithinPrimary() {
	Comparator<? super Row> c = RosterLogic.combineOrder(byGrp, false, byQ, false, byName);
	List<Row> sorted = sort(Arrays.asList(
	    new Row("x", 1, 50),
	    new Row("y", 1, 10),
	    new Row("z", 1, 30)), c);
	// within grp 1: ascending quality
	assertEquals(10, sorted.get(0).q);
	assertEquals(30, sorted.get(1).q);
	assertEquals(50, sorted.get(2).q);
    }

    @Test
    void reversedSecondaryBreaksTiesDescending() {
	Comparator<? super Row> c = RosterLogic.combineOrder(byGrp, false, byQ, true, byName);
	List<Row> sorted = sort(Arrays.asList(
	    new Row("x", 1, 50),
	    new Row("y", 1, 10)), c);
	assertEquals(50, sorted.get(0).q);
	assertEquals(10, sorted.get(1).q);
    }

    @Test
    void tieBreakerAppendedAfterPrimary() {
	// When primary ties and there's no secondary, tieBreaker (name) orders.
	Comparator<? super Row> c = RosterLogic.combineOrder(byGrp, false, null, false, byName);
	List<Row> sorted = sort(Arrays.asList(
	    new Row("charlie", 1, 10),
	    new Row("alice",   1, 10),
	    new Row("bob",     1, 10)), c);
	assertEquals("alice",   sorted.get(0).name);
	assertEquals("bob",     sorted.get(1).name);
	assertEquals("charlie", sorted.get(2).name);
    }

    @Test
    void secondarySameAsPrimaryIgnored() {
	// Guard against redundant secondary equal to the primary column.
	Comparator<? super Row> c = RosterLogic.combineOrder(byGrp, false, byGrp, true, byName);
	// Should sort like primary-only-ascending (secondary dropped since it == primary).
	List<Row> sorted = sort(Arrays.asList(
	    new Row("b", 2, 10), new Row("a", 1, 10)), c);
	assertEquals(1, sorted.get(0).grp);
	assertEquals(2, sorted.get(1).grp);
    }

    @Test
    void nullPrimaryFallsBackToTieBreaker() {
	Comparator<? super Row> c = RosterLogic.combineOrder(null, false, null, false, byName);
	List<Row> sorted = sort(Arrays.asList(
	    new Row("beta", 0, 0), new Row("alpha", 0, 0)), c);
	assertEquals("alpha", sorted.get(0).name);
	assertEquals("beta",  sorted.get(1).name);
    }

    // ----------------------------------------------------------------------
    // packColumns
    // ----------------------------------------------------------------------

    @Test
    void packColumnsAssignsSequentialXPositions() {
	List<RosterLogic.ColSpec> cols = Arrays.asList(
	    new RosterLogic.ColSpec(10, false),
	    new RosterLogic.ColSpec(20, false),
	    new RosterLogic.ColSpec(30, false));
	RosterLogic.packColumns(cols, 100, 5, 1);
	assertEquals(100, cols.get(0).x);
	assertEquals(111, cols.get(1).x);  // 100 + 10 + 1
	assertEquals(132, cols.get(2).x);  // 111 + 20 + 1
    }

    @Test
    void packColumnsUsesRunonGapAfterRunonColumns() {
	List<RosterLogic.ColSpec> cols = Arrays.asList(
	    new RosterLogic.ColSpec(10, true),   // runon: 5px gap after
	    new RosterLogic.ColSpec(20, false));
	RosterLogic.packColumns(cols, 0, 5, 1);
	assertEquals(0, cols.get(0).x);
	assertEquals(15, cols.get(1).x);  // 0 + 10 + 5
    }

    @Test
    void packColumnsEmptyListNoop() {
	RosterLogic.packColumns(new ArrayList<>(), 50, 5, 1);
	// no throw
    }

    // ----------------------------------------------------------------------
    // columnAtEdge
    // ----------------------------------------------------------------------

    @Test
    void columnAtEdgeHitsRightEdgeWithinGrab() {
	List<RosterLogic.ColSpec> cols = Arrays.asList(
	    new RosterLogic.ColSpec(50, false, 100),  // edge at 150
	    new RosterLogic.ColSpec(50, false, 160)); // edge at 210
	assertEquals(0, RosterLogic.columnAtEdge(cols, 148, 5, 40, 4));
	assertEquals(0, RosterLogic.columnAtEdge(cols, 150, 5, 40, 4));
	assertEquals(0, RosterLogic.columnAtEdge(cols, 154, 5, 40, 4));
	assertEquals(-1, RosterLogic.columnAtEdge(cols, 155, 5, 40, 4)); // outside grab
    }

    @Test
    void columnAtEdgeMissesOutsideHeaderBand() {
	List<RosterLogic.ColSpec> cols = Arrays.asList(
	    new RosterLogic.ColSpec(50, false, 100));
	assertEquals(-1, RosterLogic.columnAtEdge(cols, 150, -1, 40, 4));
	assertEquals(-1, RosterLogic.columnAtEdge(cols, 150, 40, 40, 4));
	assertEquals(-1, RosterLogic.columnAtEdge(cols, 150, 100, 40, 4));
    }

    @Test
    void columnAtEdgeReturnsSecondWhenHittingItsEdge() {
	List<RosterLogic.ColSpec> cols = Arrays.asList(
	    new RosterLogic.ColSpec(50, false, 0),    // edge at 50
	    new RosterLogic.ColSpec(50, false, 51));  // edge at 101
	assertEquals(1, RosterLogic.columnAtEdge(cols, 101, 5, 40, 4));
    }

    // ----------------------------------------------------------------------
    // applyResize
    // ----------------------------------------------------------------------

    @Test
    void applyResizeClampsBelowMin() {
	RosterLogic.ColSpec col = new RosterLogic.ColSpec(50, false);
	assertTrue(RosterLogic.applyResize(col, 20, 50, -40));
	assertEquals(20, col.w);  // clamped
    }

    @Test
    void applyResizeGrowsPastStart() {
	RosterLogic.ColSpec col = new RosterLogic.ColSpec(50, false);
	assertTrue(RosterLogic.applyResize(col, 20, 50, 30));
	assertEquals(80, col.w);
    }

    @Test
    void applyResizeNoChangeReportsFalse() {
	RosterLogic.ColSpec col = new RosterLogic.ColSpec(50, false);
	assertFalse(RosterLogic.applyResize(col, 20, 50, 0));
	assertEquals(50, col.w);
    }

    @Test
    void applyResizeClampedToMinReportsFalseWhenAlreadyAtMin() {
	RosterLogic.ColSpec col = new RosterLogic.ColSpec(20, false);
	assertFalse(RosterLogic.applyResize(col, 20, 50, -40));
	assertEquals(20, col.w);
    }

    // ----------------------------------------------------------------------
    // shiftClickRange
    // ----------------------------------------------------------------------

    private static boolean[] marks(String s) {
	boolean[] m = new boolean[s.length()];
	for(int i = 0; i < s.length(); i++) m[i] = (s.charAt(i) == 'x');
	return(m);
    }

    private static String str(boolean[] m) {
	StringBuilder sb = new StringBuilder();
	for(boolean b : m) sb.append(b ? 'x' : '.');
	return(sb.toString());
    }

    @Test
    void shiftClickWithNoExistingSelectionJustMarksClicked() {
	boolean[] m = marks("......");
	RosterLogic.shiftClickRange(m, 3);
	assertEquals("...x..", str(m));
    }

    @Test
    void shiftClickFromSingleSelectionFillsRangeDownward() {
	boolean[] m = marks("x.....");
	RosterLogic.shiftClickRange(m, 4);
	assertEquals("xxxxx.", str(m));
    }

    @Test
    void shiftClickFromSingleSelectionFillsRangeUpward() {
	boolean[] m = marks(".....x");
	RosterLogic.shiftClickRange(m, 1);
	assertEquals(".xxxxx", str(m));
    }

    @Test
    void shiftClickBetweenTwoSelectionsFillsTheWholeSpan() {
	boolean[] m = marks("x.........x");
	RosterLogic.shiftClickRange(m, 2);
	// Clicking between two selections fills everything between them,
	// regardless of which side is closer.
	assertEquals("xxxxxxxxxxx", str(m));

	m = marks("x.........x");
	RosterLogic.shiftClickRange(m, 8);
	assertEquals("xxxxxxxxxxx", str(m));
    }

    @Test
    void shiftClickBetweenEquidistantSelectionsFillsBothSides() {
	boolean[] m = marks("x...x");
	RosterLogic.shiftClickRange(m, 2);
	assertEquals("xxxxx", str(m));
    }

    @Test
    void shiftClickOnAlreadySelectedRowStillExtendsFromNearestOther() {
	boolean[] m = marks("x...x..");
	RosterLogic.shiftClickRange(m, 4);
	// Row 4 already selected; nearest other (row 0) fills the gap.
	assertEquals("xxxxx..", str(m));
    }

    @Test
    void shiftClickPreservesUnrelatedMarks() {
	boolean[] m = marks(".x...x.....x");
	RosterLogic.shiftClickRange(m, 9);
	// Between anchors at idx 5 and idx 11; fill the whole span.
	// Row 1's selection is outside the span and left alone.
	assertEquals(".x...xxxxxxx", str(m));
    }

    @Test
    void shiftClickOutOfBoundsIsNoOp() {
	boolean[] m = marks("x...x");
	RosterLogic.shiftClickRange(m, -1);
	assertEquals("x...x", str(m));
	RosterLogic.shiftClickRange(m, 5);
	assertEquals("x...x", str(m));
    }

    @Test
    void shiftClickOnEmptyIsNoOp() {
	boolean[] m = new boolean[0];
	RosterLogic.shiftClickRange(m, 0);
	assertEquals(0, m.length);
    }

    // ----------------------------------------------------------------------
    // selectToTop / selectToBottom
    // ----------------------------------------------------------------------

    @Test
    void selectToTopFromMultipleSelectionFillsUpToFirstSelected() {
	boolean[] m = marks("....x..x..x..");
	assertTrue(RosterLogic.selectToTop(m));
	assertEquals("xxxxx..x..x..", str(m));
    }

    @Test
    void selectToTopWithSelectionAtTopIsIdempotent() {
	boolean[] m = marks("x....x.");
	assertTrue(RosterLogic.selectToTop(m));
	assertEquals("x....x.", str(m));
    }

    @Test
    void selectToTopWithNothingSelectedReturnsFalse() {
	boolean[] m = marks(".....");
	assertFalse(RosterLogic.selectToTop(m));
	assertEquals(".....", str(m));
    }

    @Test
    void selectToBottomFromMultipleSelectionFillsFromLastSelectedDown() {
	boolean[] m = marks("..x..x..x....");
	assertTrue(RosterLogic.selectToBottom(m));
	assertEquals("..x..x..xxxxx", str(m));
    }

    @Test
    void selectToBottomWithSelectionAtBottomIsIdempotent() {
	boolean[] m = marks(".x....x");
	assertTrue(RosterLogic.selectToBottom(m));
	assertEquals(".x....x", str(m));
    }

    @Test
    void selectToBottomWithNothingSelectedReturnsFalse() {
	boolean[] m = marks(".....");
	assertFalse(RosterLogic.selectToBottom(m));
	assertEquals(".....", str(m));
    }

    @Test
    void selectToTopAndBottomOnSingleRowRosterNoOps() {
	boolean[] m = marks(".");
	assertFalse(RosterLogic.selectToTop(m));
	assertFalse(RosterLogic.selectToBottom(m));
	m = marks("x");
	assertTrue(RosterLogic.selectToTop(m));
	assertEquals("x", str(m));
	assertTrue(RosterLogic.selectToBottom(m));
	assertEquals("x", str(m));
    }
}
