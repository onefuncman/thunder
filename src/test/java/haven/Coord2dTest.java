package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class Coord2dTest {

    private static final double EPS = 1e-9;

    @Test
    void factory() {
        Coord2d c = Coord2d.of(1.5, 2.5);
        assertEquals(1.5, c.x, EPS);
        assertEquals(2.5, c.y, EPS);
    }

    @Test
    void factorySingleArg() {
        Coord2d c = Coord2d.of(3.0);
        assertEquals(3.0, c.x, EPS);
        assertEquals(3.0, c.y, EPS);
    }

    @Test
    void fromCoord() {
        Coord2d c = Coord2d.of(Coord.of(3, 4));
        assertEquals(3.0, c.x, EPS);
        assertEquals(4.0, c.y, EPS);
    }

    @Test
    void fromCoordConstructor() {
        Coord2d c = new Coord2d(Coord.of(7, 8));
        assertEquals(7.0, c.x, EPS);
        assertEquals(8.0, c.y, EPS);
    }

    @Test
    void defaultConstructor() {
        Coord2d c = new Coord2d();
        assertEquals(0.0, c.x, EPS);
        assertEquals(0.0, c.y, EPS);
    }

    @Test
    void equalsAndHashCode() {
        Coord2d a = Coord2d.of(1.0, 2.0);
        Coord2d b = Coord2d.of(1.0, 2.0);
        Coord2d c = Coord2d.of(1.0, 3.0);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, "not a coord");
    }

    @Test
    void equalsXY() {
        Coord2d c = Coord2d.of(1.0, 2.0);
        assertTrue(c.equals(1.0, 2.0));
        assertFalse(c.equals(1.0, 3.0));
    }

    @Test
    void add() {
        Coord2d a = Coord2d.of(1.5, 2.5);
        Coord2d b = Coord2d.of(3.0, 4.0);
        Coord2d result = a.add(b);
        assertEquals(4.5, result.x, EPS);
        assertEquals(6.5, result.y, EPS);
    }

    @Test
    void addXY() {
        Coord2d a = Coord2d.of(1.0, 2.0);
        Coord2d result = a.add(0.5, 1.5);
        assertEquals(1.5, result.x, EPS);
        assertEquals(3.5, result.y, EPS);
    }

    @Test
    void sub() {
        Coord2d a = Coord2d.of(5.0, 10.0);
        Coord2d b = Coord2d.of(2.0, 3.0);
        Coord2d result = a.sub(b);
        assertEquals(3.0, result.x, EPS);
        assertEquals(7.0, result.y, EPS);
    }

    @Test
    void subXY() {
        Coord2d a = Coord2d.of(5.0, 10.0);
        Coord2d result = a.sub(1.0, 2.0);
        assertEquals(4.0, result.x, EPS);
        assertEquals(8.0, result.y, EPS);
    }

    @Test
    void inv() {
        Coord2d c = Coord2d.of(3.0, -4.0);
        Coord2d result = c.inv();
        assertEquals(-3.0, result.x, EPS);
        assertEquals(4.0, result.y, EPS);
    }

    @Test
    void mulScalar() {
        Coord2d c = Coord2d.of(3.0, 4.0);
        Coord2d result = c.mul(2.0);
        assertEquals(6.0, result.x, EPS);
        assertEquals(8.0, result.y, EPS);
    }

    @Test
    void mulXY() {
        Coord2d c = Coord2d.of(3.0, 4.0);
        Coord2d result = c.mul(2.0, 3.0);
        assertEquals(6.0, result.x, EPS);
        assertEquals(12.0, result.y, EPS);
    }

    @Test
    void mulCoord2d() {
        Coord2d a = Coord2d.of(3.0, 4.0);
        Coord2d b = Coord2d.of(2.0, 5.0);
        Coord2d result = a.mul(b);
        assertEquals(6.0, result.x, EPS);
        assertEquals(20.0, result.y, EPS);
    }

    @Test
    void divScalar() {
        Coord2d c = Coord2d.of(6.0, 8.0);
        Coord2d result = c.div(2.0);
        assertEquals(3.0, result.x, EPS);
        assertEquals(4.0, result.y, EPS);
    }

    @Test
    void divXY() {
        Coord2d c = Coord2d.of(6.0, 12.0);
        Coord2d result = c.div(2.0, 3.0);
        assertEquals(3.0, result.x, EPS);
        assertEquals(4.0, result.y, EPS);
    }

    @Test
    void divCoord2d() {
        Coord2d a = Coord2d.of(6.0, 12.0);
        Coord2d b = Coord2d.of(2.0, 4.0);
        Coord2d result = a.div(b);
        assertEquals(3.0, result.x, EPS);
        assertEquals(3.0, result.y, EPS);
    }

    @Test
    void round() {
        Coord c = Coord2d.of(1.6, 2.4).round();
        assertEquals(2, c.x);
        assertEquals(2, c.y);
    }

    @Test
    void roundf() {
        Coord2d c = Coord2d.of(1.6, 2.4).roundf();
        assertEquals(2.0, c.x, EPS);
        assertEquals(2.0, c.y, EPS);
    }

    @Test
    void floor() {
        Coord c = Coord2d.of(1.9, 2.1).floor();
        assertEquals(1, c.x);
        assertEquals(2, c.y);
    }

    @Test
    void floorNegative() {
        Coord c = Coord2d.of(-0.1, -1.9).floor();
        assertEquals(-1, c.x);
        assertEquals(-2, c.y);
    }

    @Test
    void floorf() {
        Coord2d c = Coord2d.of(1.9, -0.1).floorf();
        assertEquals(1.0, c.x, EPS);
        assertEquals(-1.0, c.y, EPS);
    }

    @Test
    void floorWithDivisor() {
        Coord c = Coord2d.of(7.0, 15.0).floor(4.0, 5.0);
        assertEquals(1, c.x);  // floor(7/4) = 1
        assertEquals(3, c.y);  // floor(15/5) = 3
    }

    @Test
    void ceil() {
        Coord c = Coord2d.of(1.1, 2.9).ceil();
        assertEquals(2, c.x);
        assertEquals(3, c.y);
    }

    @Test
    void ceilf() {
        Coord2d c = Coord2d.of(1.1, 2.0).ceilf();
        assertEquals(2.0, c.x, EPS);
        assertEquals(2.0, c.y, EPS);
    }

    @Test
    void ceilWithDivisor() {
        Coord c = Coord2d.of(7.0, 15.0).ceil(4.0, 5.0);
        assertEquals(2, c.x);  // ceil(7/4) = 2
        assertEquals(3, c.y);  // ceil(15/5) = 3
    }

    @Test
    void mod() {
        Coord2d c = Coord2d.of(3.5, 7.8).mod();
        assertEquals(0.5, c.x, EPS);
        assertEquals(0.8, c.y, 1e-6);
    }

    @Test
    void modWithDivisor() {
        Coord2d c = Coord2d.of(7.0, 10.0).mod(3.0, 4.0);
        assertEquals(1.0, c.x, EPS);
        assertEquals(2.0, c.y, EPS);
    }

    @Test
    void dist() {
        Coord2d a = Coord2d.of(0, 0);
        Coord2d b = Coord2d.of(3, 4);
        assertEquals(5.0, a.dist(b), EPS);
    }

    @Test
    void abs() {
        Coord2d c = Coord2d.of(3.0, 4.0);
        assertEquals(5.0, c.abs(), EPS);
    }

    @Test
    void norm() {
        Coord2d c = Coord2d.of(3.0, 4.0).norm();
        assertEquals(1.0, c.abs(), EPS);
        assertEquals(0.6, c.x, EPS);
        assertEquals(0.8, c.y, EPS);
    }

    @Test
    void normWithMagnitude() {
        Coord2d c = Coord2d.of(3.0, 4.0).norm(10.0);
        assertEquals(10.0, c.abs(), EPS);
    }

    @Test
    void angle() {
        Coord2d origin = Coord2d.of(0, 0);
        assertEquals(0.0, origin.angle(Coord2d.of(1, 0)), EPS);
        assertEquals(Math.PI / 2, origin.angle(Coord2d.of(0, 1)), EPS);
        assertEquals(Math.PI, origin.angle(Coord2d.of(-1, 0)), EPS);
        assertEquals(-Math.PI / 2, origin.angle(Coord2d.of(0, -1)), EPS);
    }

    @Test
    void rot() {
        Coord2d c = Coord2d.of(1.0, 0.0);
        Coord2d rotated = c.rot(Math.PI / 2);
        assertEquals(0.0, rotated.x, 1e-6);
        assertEquals(1.0, rotated.y, 1e-6);
    }

    @Test
    void rot180() {
        Coord2d c = Coord2d.of(1.0, 0.0);
        Coord2d rotated = c.rot(Math.PI);
        assertEquals(-1.0, rotated.x, 1e-6);
        assertEquals(0.0, rotated.y, 1e-6);
    }

    @Test
    void sc() {
        Coord2d c = Coord2d.sc(0, 5.0);
        assertEquals(5.0, c.x, EPS);
        assertEquals(0.0, c.y, EPS);
    }

    @Test
    void scAtAngle() {
        Coord2d c = Coord2d.sc(Math.PI / 2, 3.0);
        assertEquals(0.0, c.x, 1e-6);
        assertEquals(3.0, c.y, 1e-6);
    }

    @Test
    void staticZero() {
        assertEquals(0.0, Coord2d.z.x, EPS);
        assertEquals(0.0, Coord2d.z.y, EPS);
    }

    @Test
    void toStringFormat() {
        Coord2d c = Coord2d.of(1.0, 2.0);
        assertEquals("(1.0, 2.0)", c.toString());
    }

    @Test
    void compareTo() {
        Coord2d a = Coord2d.of(1.0, 2.0);
        Coord2d b = Coord2d.of(1.0, 2.0);
        Coord2d c = Coord2d.of(1.0, 3.0);
        assertEquals(0, a.compareTo(b));
        assertNotEquals(0, a.compareTo(c));
    }
}
