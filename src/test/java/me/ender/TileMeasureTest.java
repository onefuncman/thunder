package me.ender;

import haven.Coord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TileMeasureTest {
    @Test
    void sameTileIsZero() {
	Coord a = Coord.of(10, 20);
	assertEquals(0, TileMeasure.chebyshev(a, a));
	assertEquals(0, TileMeasure.manhattan(a, a));
	assertEquals(0.0, TileMeasure.euclidean(a, a));
	assertEquals("0 tiles", TileMeasure.formatSegment(a, a));
    }

    @Test
    void axisAlignedUsesIntegerTiles() {
	Coord a = Coord.of(0, 0);
	assertEquals(8, TileMeasure.chebyshev(a, Coord.of(8, 0)));
	assertEquals("8 tiles  Δ+8,+0", TileMeasure.formatSegment(a, Coord.of(8, 0)));
	assertEquals("5 tiles  Δ+0,+5", TileMeasure.formatSegment(a, Coord.of(0, 5)));
	assertEquals("3 tiles  Δ-3,+0", TileMeasure.formatSegment(a, Coord.of(-3, 0)));
    }

    @Test
    void diagonalReportsChebyshevAndEuclidean() {
	Coord a = Coord.of(0, 0);
	Coord b = Coord.of(3, 4);
	assertEquals(4, TileMeasure.chebyshev(a, b));
	assertEquals(7, TileMeasure.manhattan(a, b));
	assertEquals(5.0, TileMeasure.euclidean(a, b));
	assertEquals("4 tiles  Δ+3,+4  (5.0)", TileMeasure.formatSegment(a, b));
	assertEquals("5 tiles  Δ+5,+5  (7.1)", TileMeasure.formatSegment(a, Coord.of(5, 5)));
    }

    @Test
    void polylineSumsChebyshevLegs() {
	List<Coord> path = List.of(Coord.of(0, 0), Coord.of(4, 0), Coord.of(4, 3));
	assertEquals(7, TileMeasure.chebyshevPath(path));
	assertEquals("total 7 tiles  (3 marks)", TileMeasure.formatTotal(path));
    }

    @Test
    void twoPointTotalIsTheSegment() {
	List<Coord> path = List.of(Coord.of(1, 1), Coord.of(1, 6));
	assertEquals("5 tiles  Δ+0,+5", TileMeasure.formatTotal(path));
    }

    @Test
    void shortPathsHaveNoTotal() {
	assertNull(TileMeasure.formatTotal(List.of()));
	assertNull(TileMeasure.formatTotal(List.of(Coord.of(0, 0))));
	assertEquals(0, TileMeasure.chebyshevPath(List.of(Coord.of(0, 0))));
    }

    @Test
    void detachedMarkStartsNewMeasurementWithoutClearingPreviousOne() {
	TileMeasure measure = new TileMeasure();
	Coord a = Coord.of(0, 0);
	Coord b = Coord.of(4, 0);
	Coord c = Coord.of(10, 10);
	Coord d = Coord.of(10, 13);

	measure.mark(a);
	measure.mark(b);
	measure.mark(c, true);
	measure.mark(d);

	assertEquals(List.of(List.of(a, b), List.of(c, d)), measure.measurementSnapshot());
    }

    @Test
    void undoOnlyChangesNewestMeasurement() {
	TileMeasure measure = new TileMeasure();
	Coord a = Coord.of(0, 0);
	Coord b = Coord.of(4, 0);
	Coord c = Coord.of(10, 10);
	Coord d = Coord.of(10, 13);

	measure.mark(a);
	measure.mark(b);
	measure.mark(c, true);
	measure.mark(d);
	measure.undo();
	assertEquals(List.of(List.of(a, b), List.of(c)), measure.measurementSnapshot());

	measure.undo();
	assertEquals(List.of(List.of(a, b)), measure.measurementSnapshot());
    }

    @Test
    void detachedHoverDoesNotReportDistanceFromPreviousMeasurement() {
	TileMeasure measure = new TileMeasure();
	measure.mark(Coord.of(1, 1));
	measure.setHover(Coord.of(20, 30));

	assertEquals("Start: 20, 30", measure.hoverTip(true));
    }
}
