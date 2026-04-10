package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CoordTest {

    @Test
    void factoryMethod() {
        Coord c = Coord.of(3, 7);
        assertEquals(3, c.x);
        assertEquals(7, c.y);
    }

    @Test
    void factoryMethodSingleArg() {
        Coord c = Coord.of(5);
        assertEquals(5, c.x);
        assertEquals(5, c.y);
    }

    @Test
    void copyConstructor() {
        Coord a = Coord.of(2, 4);
        Coord b = new Coord(a);
        assertEquals(a, b);
        assertNotSame(a, b);
    }

    @Test
    void defaultConstructorIsZero() {
        Coord c = new Coord();
        assertEquals(0, c.x);
        assertEquals(0, c.y);
    }

    @Test
    void equalsAndHashCode() {
        Coord a = Coord.of(10, 20);
        Coord b = Coord.of(10, 20);
        Coord c = Coord.of(10, 21);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "not a coord");
    }

    @Test
    void equalsXY() {
        Coord c = Coord.of(3, 4);
        assertTrue(c.equals(3, 4));
        assertFalse(c.equals(3, 5));
        assertFalse(c.equals(4, 4));
    }

    @Test
    void add() {
        Coord a = Coord.of(1, 2);
        Coord b = Coord.of(3, 4);
        assertEquals(Coord.of(4, 6), a.add(b));
        assertEquals(Coord.of(4, 6), a.add(3, 4));
    }

    @Test
    void sub() {
        Coord a = Coord.of(5, 10);
        Coord b = Coord.of(2, 3);
        assertEquals(Coord.of(3, 7), a.sub(b));
        assertEquals(Coord.of(3, 7), a.sub(2, 3));
    }

    @Test
    void mulInt() {
        Coord c = Coord.of(3, 4);
        assertEquals(Coord.of(6, 8), c.mul(2));
        assertEquals(Coord.of(0, 0), c.mul(0));
        assertEquals(Coord.of(-3, -4), c.mul(-1));
    }

    @Test
    void mulIntXY() {
        Coord c = Coord.of(3, 4);
        assertEquals(Coord.of(6, 12), c.mul(2, 3));
    }

    @Test
    void mulDouble() {
        Coord c = Coord.of(10, 10);
        assertEquals(Coord.of(5, 5), c.mul(0.5));
    }

    @Test
    void mulCoord() {
        Coord a = Coord.of(3, 4);
        Coord b = Coord.of(2, 5);
        assertEquals(Coord.of(6, 20), a.mul(b));
    }

    @Test
    void inv() {
        Coord c = Coord.of(3, -4);
        assertEquals(Coord.of(-3, 4), c.inv());
    }

    @Test
    void divInt() {
        Coord c = Coord.of(10, 20);
        assertEquals(Coord.of(5, 10), c.div(2));
    }

    @Test
    void divFloors() {
        // div(Coord) uses Utils.floordiv, so -1/2 should be -1 not 0
        Coord c = Coord.of(-1, -3);
        Coord d = Coord.of(2, 2);
        assertEquals(Coord.of(-1, -2), c.div(d));
    }

    @Test
    void mod() {
        Coord c = Coord.of(7, -3);
        Coord d = Coord.of(3, 3);
        Coord result = c.mod(d);
        assertEquals(Coord.of(1, 0), result);
    }

    @Test
    void isect() {
        Coord p = Coord.of(5, 5);
        assertTrue(p.isect(Coord.of(0, 0), Coord.of(10, 10)));
        assertTrue(p.isect(Coord.of(5, 5), Coord.of(1, 1)));
        assertFalse(p.isect(Coord.of(6, 6), Coord.of(10, 10)));
        assertFalse(p.isect(Coord.of(0, 0), Coord.of(5, 5))); // exclusive upper bound
    }

    @Test
    void dist() {
        Coord a = Coord.of(0, 0);
        Coord b = Coord.of(3, 4);
        assertEquals(5.0, a.dist(b), 0.0001);
        assertEquals(0.0, a.dist(a), 0.0001);
    }

    @Test
    void abs() {
        Coord c = Coord.of(3, 4);
        assertEquals(5.0, c.abs(), 0.0001);
    }

    @Test
    void angle() {
        Coord origin = Coord.of(0, 0);
        Coord right = Coord.of(10, 0);
        assertEquals(0.0, origin.angle(right), 0.0001);

        Coord up = Coord.of(0, -10);
        assertEquals(-Math.PI / 2, origin.angle(up), 0.0001);

        Coord down = Coord.of(0, 10);
        assertEquals(Math.PI / 2, origin.angle(down), 0.0001);
    }

    @Test
    void manhattan2() {
        Coord a = Coord.of(0, 0);
        Coord b = Coord.of(3, 5);
        assertEquals(5, a.manhattan2(b)); // Chebyshev distance
    }

    @Test
    void minMax() {
        Coord c = Coord.of(5, 10);
        assertEquals(Coord.of(3, 7), c.min(3, 7));
        assertEquals(Coord.of(5, 10), c.min(8, 12));
        assertEquals(Coord.of(8, 12), c.max(8, 12));
        assertEquals(Coord.of(5, 10), c.max(3, 7));
    }

    @Test
    void addXaddY() {
        Coord c = Coord.of(5, 10);
        assertEquals(Coord.of(8, 10), c.addx(3));
        assertEquals(Coord.of(5, 13), c.addy(3));
    }

    @Test
    void wy() {
        Coord c = Coord.of(5, 10);
        assertEquals(Coord.of(5, 99), c.wy(99));
    }

    @Test
    void clipToAreaBounds() {
        Coord c = Coord.of(100, 100);
        Coord clipped = c.clip(Coord.of(0, 0), Coord.of(50, 50));
        assertEquals(Coord.of(50, 50), clipped);
    }

    @Test
    void clipAlreadyInside() {
        Coord c = Coord.of(5, 5);
        Coord clipped = c.clip(Coord.of(0, 0), Coord.of(50, 50));
        assertEquals(Coord.of(5, 5), clipped);
    }

    @Test
    void compareTo() {
        Coord a = Coord.of(1, 2);
        Coord b = Coord.of(1, 2);
        Coord c = Coord.of(1, 3);
        assertEquals(0, a.compareTo(b));
        assertTrue(a.compareTo(c) != 0);
    }

    @Test
    void toStringFormat() {
        assertEquals("(3, 4)", Coord.of(3, 4).toString());
    }

    @Test
    void staticConstants() {
        assertEquals(Coord.of(0, 0), Coord.z);
        assertEquals(Coord.of(-1, 0), Coord.left);
        assertEquals(Coord.of(1, 0), Coord.right);
        assertEquals(Coord.of(0, -1), Coord.up);
        assertEquals(Coord.of(0, 1), Coord.down);
    }

    @Test
    void offsets() {
        Coord base = Coord.of(10, 10);
        int count = 0;
        for (Coord c : base.offsets(Coord.of(1, 0), Coord.of(0, 1))) {
            if (count == 0) assertEquals(Coord.of(11, 10), c);
            if (count == 1) assertEquals(Coord.of(10, 11), c);
            count++;
        }
        assertEquals(2, count);
    }

    @Test
    void sc() {
        // sc(0, 10) should be approximately (10, 0)
        Coord c = Coord.sc(0, 10);
        assertEquals(10, c.x);
        assertEquals(0, c.y);
    }

    @Test
    void rotate() {
        Coord c = Coord.of(10, 0);
        Coord rotated = c.rotate(Math.PI / 2);
        // After 90 degree rotation: (10,0) -> (0,10)
        assertEquals(0, rotated.x, 1);  // allow int truncation tolerance
        assertEquals(10, rotated.y, 1);
    }
}
