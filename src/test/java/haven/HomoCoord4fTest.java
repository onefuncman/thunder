package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class HomoCoord4fTest {

    @Test
    void constructor() {
        HomoCoord4f c = new HomoCoord4f(1, 2, 3, 4);
        assertEquals(1, c.x);
        assertEquals(2, c.y);
        assertEquals(3, c.z);
        assertEquals(4, c.w);
    }

    @Test
    void ofXYZW() {
        HomoCoord4f c = HomoCoord4f.of(1, 2, 3, 4);
        assertEquals(1, c.x);
        assertEquals(4, c.w);
    }

    @Test
    void ofXYZDefaultsW1() {
        HomoCoord4f c = HomoCoord4f.of(1, 2, 3);
        assertEquals(1, c.x);
        assertEquals(2, c.y);
        assertEquals(3, c.z);
        assertEquals(1.0f, c.w);
    }

    @Test
    void ofCoord3f() {
        Coord3f src = new Coord3f(5, 6, 7);
        HomoCoord4f c = HomoCoord4f.of(src);
        assertEquals(5, c.x);
        assertEquals(6, c.y);
        assertEquals(7, c.z);
        assertEquals(1.0f, c.w);
    }

    @Test
    void equalsIdentical() {
        HomoCoord4f a = HomoCoord4f.of(1, 2, 3, 4);
        HomoCoord4f b = HomoCoord4f.of(1, 2, 3, 5);
        // Note: equals only checks x, y, z — not w
        assertTrue(a.equals(b));
    }

    @Test
    void equalsDifferent() {
        HomoCoord4f a = HomoCoord4f.of(1, 2, 3);
        HomoCoord4f b = HomoCoord4f.of(1, 2, 4);
        assertFalse(a.equals(b));
    }

    @Test
    void equalsObject() {
        HomoCoord4f a = HomoCoord4f.of(1, 2, 3);
        assertFalse(a.equals("not a coord"));
        assertFalse(a.equals((Object) null));
    }

    // --- clipped ---

    @Test
    void notClippedInside() {
        // Point well inside clip volume: x,y,z all within [-w, w]
        HomoCoord4f c = HomoCoord4f.of(0.5f, 0.5f, 0.5f, 1.0f);
        assertFalse(c.clipped());
    }

    @Test
    void clippedNegativeW() {
        HomoCoord4f c = HomoCoord4f.of(0, 0, 0, -1.0f);
        assertTrue(c.clipped());
    }

    @Test
    void clippedZeroW() {
        HomoCoord4f c = HomoCoord4f.of(0, 0, 0, 0);
        assertTrue(c.clipped());
    }

    @Test
    void clippedPX() {
        HomoCoord4f c = HomoCoord4f.of(2.0f, 0, 0, 1.0f);
        assertTrue(c.clipped(HomoCoord4f.PX));
        assertFalse(c.clipped(HomoCoord4f.NX));
    }

    @Test
    void clippedNX() {
        HomoCoord4f c = HomoCoord4f.of(-2.0f, 0, 0, 1.0f);
        assertTrue(c.clipped(HomoCoord4f.NX));
        assertFalse(c.clipped(HomoCoord4f.PX));
    }

    @Test
    void clippedPY() {
        HomoCoord4f c = HomoCoord4f.of(0, 2.0f, 0, 1.0f);
        assertTrue(c.clipped(HomoCoord4f.PY));
    }

    @Test
    void clippedNZ() {
        HomoCoord4f c = HomoCoord4f.of(0, 0, -2.0f, 1.0f);
        assertTrue(c.clipped(HomoCoord4f.NZ));
    }

    @Test
    void clippedSelectivePlanes() {
        // x=2 is outside PX but we only check NZ
        HomoCoord4f c = HomoCoord4f.of(2.0f, 0, 0, 1.0f);
        assertFalse(c.clipped(HomoCoord4f.NZ));
    }

    // --- pdiv ---

    @Test
    void pdivW1() {
        HomoCoord4f c = HomoCoord4f.of(3, 6, 9, 1);
        Coord3f r = c.pdiv();
        assertEquals(3, r.x, 1e-5f);
        assertEquals(6, r.y, 1e-5f);
        assertEquals(9, r.z, 1e-5f);
    }

    @Test
    void pdivW2() {
        HomoCoord4f c = HomoCoord4f.of(4, 8, 12, 2);
        Coord3f r = c.pdiv();
        assertEquals(2, r.x, 1e-5f);
        assertEquals(4, r.y, 1e-5f);
        assertEquals(6, r.z, 1e-5f);
    }

    // --- toview ---

    @Test
    void toviewSimple() {
        // NDC (0,0,0) with w=1 -> center of viewport
        HomoCoord4f c = HomoCoord4f.of(0, 0, 0, 1);
        Area view = Area.sized(Coord.of(0, 0), Coord.of(100, 100));
        Coord3f v = c.toview(view);
        assertEquals(50.0f, v.x, 0.01f);
        assertEquals(50.0f, v.y, 0.01f);
        assertEquals(0.5f, v.z, 0.01f);
    }

    @Test
    void toviewCorner() {
        // NDC (-1, 1, -1) with w=1 -> top-left corner of viewport
        HomoCoord4f c = HomoCoord4f.of(-1, 1, -1, 1);
        Area view = Area.sized(Coord.of(0, 0), Coord.of(200, 100));
        Coord3f v = c.toview(view);
        assertEquals(0.0f, v.x, 0.01f);
        assertEquals(0.0f, v.y, 0.01f);
        assertEquals(0.0f, v.z, 0.01f);
    }

    // --- toString ---

    @Test
    void toStringFormat() {
        HomoCoord4f c = HomoCoord4f.of(1, 2, 3, 4);
        String s = c.toString();
        assertNotNull(s);
        assertTrue(s.contains("1"));
        assertTrue(s.contains("4"));
    }

    // --- static constants ---

    @Test
    void planeConstants() {
        assertEquals(1, HomoCoord4f.NX);
        assertEquals(2, HomoCoord4f.PX);
        assertEquals(4, HomoCoord4f.NY);
        assertEquals(8, HomoCoord4f.PY);
        assertEquals(16, HomoCoord4f.NZ);
        assertEquals(32, HomoCoord4f.PZ);
        assertEquals(3, HomoCoord4f.AX);    // NX | PX
        assertEquals(12, HomoCoord4f.AY);   // NY | PY
        assertEquals(48, HomoCoord4f.AZ);   // NZ | PZ
    }
}
