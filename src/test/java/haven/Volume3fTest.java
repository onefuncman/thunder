package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class Volume3fTest {

    private static Coord3f c(float x, float y, float z) {
        return Coord3f.of(x, y, z);
    }

    @Test
    void constructor() {
        Volume3f v = new Volume3f(c(1, 2, 3), c(4, 5, 6));
        assertEquals(1, v.n.x);
        assertEquals(6, v.p.z);
    }

    @Test
    void corn() {
        Volume3f v = Volume3f.corn(c(0, 0, 0), c(1, 1, 1));
        assertEquals(0, v.n.x);
        assertEquals(1, v.p.x);
    }

    @Test
    void point() {
        Volume3f v = Volume3f.point(c(3, 4, 5));
        assertEquals(v.n, v.p);
    }

    @Test
    void sizedFromOrigin() {
        Volume3f v = Volume3f.sized(c(10, 20, 30));
        assertEquals(0, v.n.x);
        assertEquals(0, v.n.y);
        assertEquals(0, v.n.z);
        assertEquals(10, v.p.x);
        assertEquals(20, v.p.y);
        assertEquals(30, v.p.z);
    }

    @Test
    void sizedWithOffset() {
        Volume3f v = Volume3f.sized(c(1, 2, 3), c(10, 10, 10));
        assertEquals(1, v.n.x);
        assertEquals(11, v.p.x);
    }

    @Test
    void sz() {
        Volume3f v = Volume3f.corn(c(1, 2, 3), c(4, 6, 8));
        Coord3f sz = v.sz();
        assertEquals(3, sz.x, 1e-5f);
        assertEquals(4, sz.y, 1e-5f);
        assertEquals(5, sz.z, 1e-5f);
    }

    @Test
    void positive() {
        assertTrue(Volume3f.corn(c(0, 0, 0), c(1, 1, 1)).positive());
        assertFalse(Volume3f.corn(c(0, 0, 0), c(0, 1, 1)).positive());
        assertFalse(Volume3f.corn(c(1, 0, 0), c(0, 1, 1)).positive());
    }

    @Test
    void containsPoint() {
        Volume3f v = Volume3f.corn(c(0, 0, 0), c(10, 10, 10));
        assertTrue(v.contains(c(5, 5, 5)));
        assertTrue(v.contains(c(0, 0, 0)));     // boundary inclusive
        assertTrue(v.contains(c(10, 10, 10)));   // boundary inclusive
        assertFalse(v.contains(c(11, 5, 5)));
        assertFalse(v.contains(c(-1, 5, 5)));
    }

    @Test
    void containsVolume() {
        Volume3f outer = Volume3f.corn(c(0, 0, 0), c(10, 10, 10));
        Volume3f inner = Volume3f.corn(c(2, 2, 2), c(8, 8, 8));
        assertTrue(outer.contains(inner));
        assertFalse(inner.contains(outer));
    }

    @Test
    void containsVolumeSelf() {
        Volume3f v = Volume3f.corn(c(0, 0, 0), c(10, 10, 10));
        assertTrue(v.contains(v));
    }

    @Test
    void isects() {
        Volume3f a = Volume3f.corn(c(0, 0, 0), c(5, 5, 5));
        Volume3f b = Volume3f.corn(c(3, 3, 3), c(8, 8, 8));
        assertTrue(a.isects(b));
        assertTrue(b.isects(a));
    }

    @Test
    void isectsNoOverlap() {
        Volume3f a = Volume3f.corn(c(0, 0, 0), c(1, 1, 1));
        Volume3f b = Volume3f.corn(c(2, 2, 2), c(3, 3, 3));
        assertFalse(a.isects(b));
    }

    @Test
    void isectsTouching() {
        // Volumes that share a face but don't overlap in volume
        Volume3f a = Volume3f.corn(c(0, 0, 0), c(1, 1, 1));
        Volume3f b = Volume3f.corn(c(1, 0, 0), c(2, 1, 1));
        assertFalse(a.isects(b)); // strict inequality in isects
    }

    @Test
    void closest() {
        Volume3f v = Volume3f.corn(c(0, 0, 0), c(10, 10, 10));
        // Point inside returns itself
        Coord3f inside = v.closest(c(5, 5, 5));
        assertEquals(5, inside.x, 1e-5f);
        // Point outside gets clamped
        Coord3f outside = v.closest(c(15, -3, 5));
        assertEquals(10, outside.x, 1e-5f);
        assertEquals(0, outside.y, 1e-5f);
        assertEquals(5, outside.z, 1e-5f);
    }

    @Test
    void xl() {
        Volume3f v = Volume3f.corn(c(0, 0, 0), c(5, 5, 5));
        Volume3f shifted = v.xl(c(10, 20, 30));
        assertEquals(10, shifted.n.x, 1e-5f);
        assertEquals(15, shifted.p.x, 1e-5f);
        assertEquals(20, shifted.n.y, 1e-5f);
    }

    @Test
    void marginSymmetric() {
        Volume3f v = Volume3f.corn(c(5, 5, 5), c(10, 10, 10));
        Volume3f m = v.margin(1.0f);
        assertEquals(4, m.n.x, 1e-5f);
        assertEquals(11, m.p.x, 1e-5f);
    }

    @Test
    void marginAsymmetric() {
        Volume3f v = Volume3f.corn(c(5, 5, 5), c(10, 10, 10));
        Volume3f m = v.margin(c(1, 2, 3), c(4, 5, 6));
        assertEquals(4, m.n.x, 1e-5f);
        assertEquals(3, m.n.y, 1e-5f);
        assertEquals(2, m.n.z, 1e-5f);
        assertEquals(14, m.p.x, 1e-5f);
    }

    @Test
    void includePoint() {
        Volume3f v = Volume3f.corn(c(0, 0, 0), c(5, 5, 5));
        Volume3f expanded = v.include(c(10, -2, 3));
        assertEquals(0, expanded.n.x, 1e-5f);
        assertEquals(-2, expanded.n.y, 1e-5f);
        assertEquals(10, expanded.p.x, 1e-5f);
        assertEquals(5, expanded.p.y, 1e-5f);
    }

    @Test
    void includeVolume() {
        Volume3f a = Volume3f.corn(c(0, 0, 0), c(5, 5, 5));
        Volume3f b = Volume3f.corn(c(3, -1, 2), c(8, 4, 7));
        Volume3f merged = a.include(b);
        assertEquals(0, merged.n.x, 1e-5f);
        assertEquals(-1, merged.n.y, 1e-5f);
        assertEquals(0, merged.n.z, 1e-5f);
        assertEquals(8, merged.p.x, 1e-5f);
        assertEquals(5, merged.p.y, 1e-5f);
        assertEquals(7, merged.p.z, 1e-5f);
    }

    @Test
    void equalsAndNotEquals() {
        Volume3f a = Volume3f.corn(c(0, 0, 0), c(1, 1, 1));
        Volume3f b = Volume3f.corn(c(0, 0, 0), c(1, 1, 1));
        Volume3f c = Volume3f.corn(c(0, 0, 0), c(2, 2, 2));
        assertTrue(a.equals(b));
        assertFalse(a.equals(c));
        assertFalse(a.equals("not a volume"));
    }

    @Test
    void toStringFormat() {
        Volume3f v = Volume3f.corn(c(1, 2, 3), c(4, 5, 6));
        assertNotNull(v.toString());
    }
}
