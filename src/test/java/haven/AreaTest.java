package haven;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class AreaTest {

    @Test
    void corn() {
        Area a = Area.corn(Coord.of(1, 2), Coord.of(5, 6));
        assertEquals(Coord.of(1, 2), a.ul);
        assertEquals(Coord.of(5, 6), a.br);
    }

    @Test
    void corni() {
        // corni adds (1,1) to br to make it inclusive
        Area a = Area.corni(Coord.of(0, 0), Coord.of(4, 4));
        assertEquals(Coord.of(0, 0), a.ul);
        assertEquals(Coord.of(5, 5), a.br);
    }

    @Test
    void sizedFromOrigin() {
        Area a = Area.sized(Coord.of(10, 10));
        assertEquals(Coord.of(0, 0), a.ul);
        assertEquals(Coord.of(10, 10), a.br);
    }

    @Test
    void sizedWithOffset() {
        Area a = Area.sized(Coord.of(5, 5), Coord.of(10, 10));
        assertEquals(Coord.of(5, 5), a.ul);
        assertEquals(Coord.of(15, 15), a.br);
    }

    @Test
    void sz() {
        Area a = Area.corn(Coord.of(2, 3), Coord.of(7, 8));
        assertEquals(Coord.of(5, 5), a.sz());
    }

    @Test
    void area() {
        Area a = Area.sized(Coord.of(4, 5));
        assertEquals(20, a.area());
    }

    @Test
    void positive() {
        assertTrue(Area.corn(Coord.of(0, 0), Coord.of(5, 5)).positive());
        assertFalse(Area.corn(Coord.of(5, 5), Coord.of(5, 5)).positive()); // zero size
        assertFalse(Area.corn(Coord.of(5, 5), Coord.of(3, 3)).positive()); // negative
    }

    @Test
    void containsCoord() {
        Area a = Area.corn(Coord.of(0, 0), Coord.of(10, 10));
        assertTrue(a.contains(Coord.of(0, 0)));   // ul inclusive
        assertTrue(a.contains(Coord.of(5, 5)));
        assertTrue(a.contains(Coord.of(9, 9)));
        assertFalse(a.contains(Coord.of(10, 10))); // br exclusive
        assertFalse(a.contains(Coord.of(-1, 5)));
        assertFalse(a.contains(Coord.of(5, -1)));
    }

    @Test
    void containsArea() {
        Area outer = Area.corn(Coord.of(0, 0), Coord.of(10, 10));
        Area inner = Area.corn(Coord.of(2, 2), Coord.of(8, 8));
        Area partial = Area.corn(Coord.of(5, 5), Coord.of(15, 15));
        assertTrue(outer.contains(inner));
        assertTrue(outer.contains(outer));
        assertFalse(outer.contains(partial));
    }

    @Test
    void isects() {
        Area a = Area.corn(Coord.of(0, 0), Coord.of(10, 10));
        Area b = Area.corn(Coord.of(5, 5), Coord.of(15, 15));
        Area c = Area.corn(Coord.of(10, 10), Coord.of(20, 20));
        assertTrue(a.isects(b));
        assertTrue(b.isects(a));
        assertFalse(a.isects(c)); // touching edge, not overlapping
    }

    @Test
    void overlap() {
        Area a = Area.corn(Coord.of(0, 0), Coord.of(10, 10));
        Area b = Area.corn(Coord.of(5, 5), Coord.of(15, 15));
        Area o = a.overlap(b);
        assertNotNull(o);
        assertEquals(Coord.of(5, 5), o.ul);
        assertEquals(Coord.of(10, 10), o.br);
    }

    @Test
    void overlapNoIntersection() {
        Area a = Area.corn(Coord.of(0, 0), Coord.of(5, 5));
        Area b = Area.corn(Coord.of(10, 10), Coord.of(20, 20));
        assertNull(a.overlap(b));
    }

    @Test
    void include() {
        Area a = Area.corn(Coord.of(2, 2), Coord.of(5, 5));
        Area b = Area.corn(Coord.of(8, 8), Coord.of(10, 10));
        Area u = a.include(b);
        assertEquals(Coord.of(2, 2), u.ul);
        assertEquals(Coord.of(10, 10), u.br);
    }

    @Test
    void xl() {
        Area a = Area.corn(Coord.of(0, 0), Coord.of(5, 5));
        Area shifted = a.xl(Coord.of(10, 10));
        assertEquals(Coord.of(10, 10), shifted.ul);
        assertEquals(Coord.of(15, 15), shifted.br);
    }

    @Test
    void margin() {
        Area a = Area.corn(Coord.of(5, 5), Coord.of(10, 10));
        Area expanded = a.margin(2);
        assertEquals(Coord.of(3, 3), expanded.ul);
        assertEquals(Coord.of(12, 12), expanded.br);
    }

    @Test
    void mulArea() {
        Area a = Area.corn(Coord.of(1, 2), Coord.of(3, 4));
        Area scaled = a.mul(Coord.of(2, 3));
        assertEquals(Coord.of(2, 6), scaled.ul);
        assertEquals(Coord.of(6, 12), scaled.br);
    }

    @Test
    void iteratorCoversAllPoints() {
        Area a = Area.corn(Coord.of(0, 0), Coord.of(3, 2));
        List<Coord> points = new ArrayList<>();
        for (Coord c : a) points.add(c);
        assertEquals(6, points.size());
        assertEquals(Coord.of(0, 0), points.get(0));
        assertEquals(Coord.of(1, 0), points.get(1));
        assertEquals(Coord.of(2, 0), points.get(2));
        assertEquals(Coord.of(0, 1), points.get(3));
        assertEquals(Coord.of(1, 1), points.get(4));
        assertEquals(Coord.of(2, 1), points.get(5));
    }

    @Test
    void iteratorEmpty() {
        Area a = Area.corn(Coord.of(5, 5), Coord.of(5, 5));
        List<Coord> points = new ArrayList<>();
        for (Coord c : a) points.add(c);
        assertEquals(0, points.size());
    }

    @Test
    void ridx() {
        Area a = Area.corn(Coord.of(0, 0), Coord.of(3, 3));
        assertEquals(0, a.ridx(Coord.of(0, 0)));
        assertEquals(1, a.ridx(Coord.of(1, 0)));
        assertEquals(3, a.ridx(Coord.of(0, 1)));
        assertEquals(-1, a.ridx(Coord.of(5, 5)));
    }

    @Test
    void ri() {
        Area a = Area.corn(Coord.of(0, 0), Coord.of(4, 4));
        assertEquals(0, a.ri(Coord.of(0, 0)));
        assertEquals(5, a.ri(Coord.of(1, 1)));
    }

    @Test
    void rsz() {
        Area a = Area.corn(Coord.of(0, 0), Coord.of(4, 3));
        assertEquals(12, a.rsz());
    }

    @Test
    void equalsAndHashCode() {
        Area a = Area.corn(Coord.of(1, 2), Coord.of(3, 4));
        Area b = Area.corn(Coord.of(1, 2), Coord.of(3, 4));
        Area c = Area.corn(Coord.of(1, 2), Coord.of(5, 6));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, "not an area");
    }

    @Test
    void closest() {
        Area a = Area.corn(Coord.of(0, 0), Coord.of(10, 10));
        assertEquals(Coord.of(5, 5), a.closest(Coord.of(5, 5)));   // inside
        assertEquals(Coord.of(9, 9), a.closest(Coord.of(20, 20))); // outside, clamp to br-1
        assertEquals(Coord.of(0, 0), a.closest(Coord.of(-5, -5))); // outside, clamp to ul
    }

    @Test
    void toStringFormat() {
        Area a = Area.corn(Coord.of(1, 2), Coord.of(3, 4));
        assertEquals("((1, 2) - (3, 4))", a.toString());
    }
}
