package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class Coord3fTest {

    private static final float EPS = 1e-5f;

    @Test
    void factory() {
        Coord3f c = Coord3f.of(1f, 2f, 3f);
        assertEquals(1f, c.x, EPS);
        assertEquals(2f, c.y, EPS);
        assertEquals(3f, c.z, EPS);
    }

    @Test
    void copyConstructor() {
        Coord3f a = Coord3f.of(1f, 2f, 3f);
        Coord3f b = new Coord3f(a);
        assertTrue(a.equals(b));
        assertNotSame(a, b);
    }

    @Test
    void fromCoord() {
        Coord c = Coord.of(5, 7);
        Coord3f c3 = new Coord3f(c);
        assertEquals(5f, c3.x, EPS);
        assertEquals(7f, c3.y, EPS);
        assertEquals(0f, c3.z, EPS);
    }

    @Test
    void fromCoordFactory() {
        Coord3f c = Coord3f.of(Coord.of(3, 4));
        assertEquals(3f, c.x, EPS);
        assertEquals(4f, c.y, EPS);
        assertEquals(0f, c.z, EPS);
    }

    @Test
    void equalsMethod() {
        Coord3f a = Coord3f.of(1f, 2f, 3f);
        Coord3f b = Coord3f.of(1f, 2f, 3f);
        Coord3f c = Coord3f.of(1f, 2f, 4f);
        assertTrue(a.equals(b));
        assertFalse(a.equals(c));
        assertFalse(a.equals("not a coord"));
    }

    @Test
    void add() {
        Coord3f a = Coord3f.of(1f, 2f, 3f);
        Coord3f b = Coord3f.of(4f, 5f, 6f);
        Coord3f r = a.add(b);
        assertEquals(5f, r.x, EPS);
        assertEquals(7f, r.y, EPS);
        assertEquals(9f, r.z, EPS);
    }

    @Test
    void addXYZ() {
        Coord3f a = Coord3f.of(1f, 2f, 3f);
        Coord3f r = a.add(10f, 20f, 30f);
        assertEquals(11f, r.x, EPS);
        assertEquals(22f, r.y, EPS);
        assertEquals(33f, r.z, EPS);
    }

    @Test
    void sub() {
        Coord3f a = Coord3f.of(5f, 10f, 15f);
        Coord3f b = Coord3f.of(1f, 2f, 3f);
        Coord3f r = a.sub(b);
        assertEquals(4f, r.x, EPS);
        assertEquals(8f, r.y, EPS);
        assertEquals(12f, r.z, EPS);
    }

    @Test
    void neg() {
        Coord3f c = Coord3f.of(3f, -4f, 5f);
        Coord3f r = c.neg();
        assertEquals(-3f, r.x, EPS);
        assertEquals(4f, r.y, EPS);
        assertEquals(-5f, r.z, EPS);
    }

    @Test
    void inv() {
        Coord3f c = Coord3f.of(3f, -4f, 5f);
        Coord3f r = c.inv();
        assertEquals(-3f, r.x, EPS);
        assertEquals(4f, r.y, EPS);
        assertEquals(-5f, r.z, EPS);
    }

    @Test
    void invy() {
        Coord3f c = Coord3f.of(3f, 4f, 5f);
        Coord3f r = c.invy();
        assertEquals(3f, r.x, EPS);
        assertEquals(-4f, r.y, EPS);
        assertEquals(5f, r.z, EPS);
    }

    @Test
    void mulScalar() {
        Coord3f c = Coord3f.of(2f, 3f, 4f);
        Coord3f r = c.mul(3f);
        assertEquals(6f, r.x, EPS);
        assertEquals(9f, r.y, EPS);
        assertEquals(12f, r.z, EPS);
    }

    @Test
    void mulXYZ() {
        Coord3f c = Coord3f.of(2f, 3f, 4f);
        Coord3f r = c.mul(1f, 2f, 3f);
        assertEquals(2f, r.x, EPS);
        assertEquals(6f, r.y, EPS);
        assertEquals(12f, r.z, EPS);
    }

    @Test
    void mulCoord3f() {
        Coord3f a = Coord3f.of(2f, 3f, 4f);
        Coord3f b = Coord3f.of(5f, 6f, 7f);
        Coord3f r = a.mul(b);
        assertEquals(10f, r.x, EPS);
        assertEquals(18f, r.y, EPS);
        assertEquals(28f, r.z, EPS);
    }

    @Test
    void divScalar() {
        Coord3f c = Coord3f.of(6f, 9f, 12f);
        Coord3f r = c.div(3f);
        assertEquals(2f, r.x, EPS);
        assertEquals(3f, r.y, EPS);
        assertEquals(4f, r.z, EPS);
    }

    @Test
    void divXYZ() {
        Coord3f c = Coord3f.of(6f, 12f, 20f);
        Coord3f r = c.div(2f, 3f, 4f);
        assertEquals(3f, r.x, EPS);
        assertEquals(4f, r.y, EPS);
        assertEquals(5f, r.z, EPS);
    }

    @Test
    void divCoord3f() {
        Coord3f a = Coord3f.of(6f, 12f, 20f);
        Coord3f b = Coord3f.of(2f, 3f, 5f);
        Coord3f r = a.div(b);
        assertEquals(3f, r.x, EPS);
        assertEquals(4f, r.y, EPS);
        assertEquals(4f, r.z, EPS);
    }

    @Test
    void dotProduct() {
        Coord3f a = Coord3f.of(1f, 2f, 3f);
        Coord3f b = Coord3f.of(4f, 5f, 6f);
        // 1*4 + 2*5 + 3*6 = 32
        assertEquals(32f, a.dmul(b), EPS);
    }

    @Test
    void dotProductXYZ() {
        Coord3f a = Coord3f.of(1f, 0f, 0f);
        assertEquals(1f, a.dmul(1f, 0f, 0f), EPS);
        assertEquals(0f, a.dmul(0f, 1f, 0f), EPS);
    }

    @Test
    void crossProduct() {
        Coord3f a = Coord3f.of(1f, 0f, 0f);
        Coord3f b = Coord3f.of(0f, 1f, 0f);
        Coord3f r = a.cmul(b);
        assertEquals(0f, r.x, EPS);
        assertEquals(0f, r.y, EPS);
        assertEquals(1f, r.z, EPS); // i x j = k
    }

    @Test
    void crossProductAnticommutative() {
        Coord3f a = Coord3f.of(1f, 2f, 3f);
        Coord3f b = Coord3f.of(4f, 5f, 6f);
        Coord3f ab = a.cmul(b);
        Coord3f ba = b.cmul(a);
        assertEquals(-ab.x, ba.x, EPS);
        assertEquals(-ab.y, ba.y, EPS);
        assertEquals(-ab.z, ba.z, EPS);
    }

    @Test
    void abs() {
        Coord3f c = Coord3f.of(3f, 4f, 0f);
        assertEquals(5f, c.abs(), EPS);
    }

    @Test
    void abs3d() {
        Coord3f c = Coord3f.of(1f, 2f, 2f);
        assertEquals(3f, c.abs(), EPS);
    }

    @Test
    void norm() {
        Coord3f c = Coord3f.of(3f, 4f, 0f);
        Coord3f n = c.norm();
        assertEquals(1f, n.abs(), EPS);
        assertEquals(0.6f, n.x, EPS);
        assertEquals(0.8f, n.y, EPS);
        assertEquals(0f, n.z, EPS);
    }

    @Test
    void normZero() {
        Coord3f c = Coord3f.of(0f, 0f, 0f);
        Coord3f n = c.norm();
        assertEquals(0f, n.x, EPS);
        assertEquals(0f, n.y, EPS);
        assertEquals(0f, n.z, EPS);
    }

    @Test
    void dist() {
        Coord3f a = Coord3f.of(0f, 0f, 0f);
        Coord3f b = Coord3f.of(1f, 2f, 2f);
        assertEquals(3f, a.dist(b), EPS);
    }

    @Test
    void xyangle() {
        Coord3f origin = Coord3f.of(0f, 0f, 0f);
        Coord3f right = Coord3f.of(10f, 0f, 0f);
        assertEquals(0f, origin.xyangle(right), EPS);

        Coord3f up = Coord3f.of(0f, -10f, 0f);
        assertEquals(-(float) Math.PI / 2, origin.xyangle(up), EPS);
    }

    @Test
    void to3a() {
        float[] a = Coord3f.of(1f, 2f, 3f).to3a();
        assertEquals(3, a.length);
        assertEquals(1f, a[0], EPS);
        assertEquals(2f, a[1], EPS);
        assertEquals(3f, a[2], EPS);
    }

    @Test
    void to4a() {
        float[] a = Coord3f.of(1f, 2f, 3f).to4a(4f);
        assertEquals(4, a.length);
        assertEquals(1f, a[0], EPS);
        assertEquals(2f, a[1], EPS);
        assertEquals(3f, a[2], EPS);
        assertEquals(4f, a[3], EPS);
    }

    @Test
    void round2() {
        Coord c = Coord3f.of(1.6f, 2.4f, 99f).round2();
        assertEquals(2, c.x);
        assertEquals(2, c.y);
    }

    @Test
    void staticConstants() {
        assertTrue(Coord3f.o.equals(Coord3f.of(0f, 0f, 0f)));
        assertTrue(Coord3f.xu.equals(Coord3f.of(1f, 0f, 0f)));
        assertTrue(Coord3f.yu.equals(Coord3f.of(0f, 1f, 0f)));
        assertTrue(Coord3f.zu.equals(Coord3f.of(0f, 0f, 1f)));
    }

    @Test
    void rotAroundZAxis() {
        Coord3f v = Coord3f.of(1f, 0f, 0f);
        Coord3f axis = Coord3f.of(0f, 0f, 1f);
        Coord3f r = v.rot(axis, (float) (Math.PI / 2));
        assertEquals(0f, r.x, 1e-4f);
        assertEquals(1f, r.y, 1e-4f);
        assertEquals(0f, r.z, 1e-4f);
    }

    @Test
    void sadd() {
        Coord3f origin = Coord3f.of(0f, 0f, 0f);
        // elevation=0, azimuth=0, radius=5 -> (5, 0, 0)
        Coord3f r = origin.sadd(0f, 0f, 5f);
        assertEquals(5f, r.x, EPS);
        assertEquals(0f, r.y, EPS);
        assertEquals(0f, r.z, EPS);
    }

    @Test
    void toStringFormat() {
        String s = Coord3f.of(1f, 2f, 3f).toString();
        assertTrue(s.startsWith("("));
        assertTrue(s.endsWith(")"));
    }
}
