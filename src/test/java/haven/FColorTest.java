package haven;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import static org.junit.jupiter.api.Assertions.*;

public class FColorTest {

    private static final float EPS = 1e-5f;

    @Test
    void constructRGBA() {
        FColor c = new FColor(0.1f, 0.2f, 0.3f, 0.4f);
        assertEquals(0.1f, c.r, EPS);
        assertEquals(0.2f, c.g, EPS);
        assertEquals(0.3f, c.b, EPS);
        assertEquals(0.4f, c.a, EPS);
    }

    @Test
    void constructRGB() {
        FColor c = new FColor(0.5f, 0.6f, 0.7f);
        assertEquals(0.5f, c.r, EPS);
        assertEquals(0.6f, c.g, EPS);
        assertEquals(0.7f, c.b, EPS);
        assertEquals(1.0f, c.a, EPS);
    }

    @Test
    void constructFromAwtColor() {
        FColor c = new FColor(new Color(255, 128, 0, 200));
        assertEquals(1.0f, c.r, 0.01f);
        assertEquals(128f / 255f, c.g, 0.01f);
        assertEquals(0f, c.b, 0.01f);
        assertEquals(200f / 255f, c.a, 0.01f);
    }

    @Test
    void constructFromAwtColorWithFactor() {
        FColor c = new FColor(new Color(255, 255, 255), 0.5f);
        assertEquals(0.5f, c.r, 0.01f);
        assertEquals(0.5f, c.g, 0.01f);
        assertEquals(0.5f, c.b, 0.01f);
    }

    @Test
    void fromColorAndAlpha() {
        FColor c = FColor.fromColorAndAlpha(new Color(255, 0, 0), 0.5f);
        assertEquals(1.0f, c.r, 0.01f);
        assertEquals(0f, c.g, 0.01f);
        assertEquals(0f, c.b, 0.01f);
        assertEquals(0.5f, c.a, EPS);
    }

    @Test
    void mulFColor() {
        FColor a = new FColor(0.5f, 0.5f, 0.5f, 1.0f);
        FColor b = new FColor(0.5f, 1.0f, 0.0f, 0.5f);
        FColor r = a.mul(b);
        assertEquals(0.25f, r.r, EPS);
        assertEquals(0.5f, r.g, EPS);
        assertEquals(0.0f, r.b, EPS);
        assertEquals(0.5f, r.a, EPS);
    }

    @Test
    void mulScalar() {
        FColor c = new FColor(0.5f, 0.5f, 0.5f, 0.8f);
        FColor r = c.mul(2.0f);
        assertEquals(1.0f, r.r, EPS);
        assertEquals(1.0f, r.g, EPS);
        assertEquals(1.0f, r.b, EPS);
        assertEquals(0.8f, r.a, EPS); // alpha unchanged
    }

    @Test
    void blend() {
        FColor a = new FColor(1.0f, 0.0f, 0.0f); // red
        FColor b = new FColor(0.0f, 0.0f, 1.0f, 0.5f); // blue, 50% alpha
        FColor r = a.blend(b);
        assertEquals(0.5f, r.r, EPS);
        assertEquals(0.0f, r.g, EPS);
        assertEquals(0.5f, r.b, EPS);
        assertEquals(1.0f, r.a, EPS); // preserves 'this' alpha
    }

    @Test
    void blendWithFactor() {
        FColor a = new FColor(1.0f, 0.0f, 0.0f, 1.0f);
        FColor b = new FColor(0.0f, 1.0f, 0.0f, 1.0f);
        FColor r = a.blend(b, 0.5f);
        assertEquals(0.5f, r.r, EPS);
        assertEquals(0.5f, r.g, EPS);
        assertEquals(0.0f, r.b, EPS);
        assertEquals(1.0f, r.a, EPS);
    }

    @Test
    void blendWithFactorZero() {
        FColor a = new FColor(1.0f, 0.0f, 0.0f);
        FColor b = new FColor(0.0f, 1.0f, 0.0f);
        FColor r = a.blend(b, 0.0f);
        assertEquals(1.0f, r.r, EPS);
        assertEquals(0.0f, r.g, EPS);
    }

    @Test
    void blendWithFactorOne() {
        FColor a = new FColor(1.0f, 0.0f, 0.0f);
        FColor b = new FColor(0.0f, 1.0f, 0.0f);
        FColor r = a.blend(b, 1.0f);
        assertEquals(0.0f, r.r, EPS);
        assertEquals(1.0f, r.g, EPS);
    }

    @Test
    void to3a() {
        float[] a = new FColor(0.1f, 0.2f, 0.3f).to3a();
        assertEquals(3, a.length);
        assertEquals(0.1f, a[0], EPS);
        assertEquals(0.2f, a[1], EPS);
        assertEquals(0.3f, a[2], EPS);
    }

    @Test
    void to4a() {
        float[] a = new FColor(0.1f, 0.2f, 0.3f, 0.4f).to4a();
        assertEquals(4, a.length);
        assertEquals(0.1f, a[0], EPS);
        assertEquals(0.2f, a[1], EPS);
        assertEquals(0.3f, a[2], EPS);
        assertEquals(0.4f, a[3], EPS);
    }

    @Test
    void equalsAndHashCode() {
        FColor a = new FColor(0.1f, 0.2f, 0.3f, 0.4f);
        FColor b = new FColor(0.1f, 0.2f, 0.3f, 0.4f);
        FColor c = new FColor(0.1f, 0.2f, 0.3f, 0.5f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, "not a color");
    }

    @Test
    void staticConstants() {
        assertEquals(new FColor(0, 0, 0), FColor.BLACK);
        assertEquals(new FColor(1, 1, 1), FColor.WHITE);
        assertEquals(new FColor(1, 0, 0), FColor.RED);
        assertEquals(new FColor(0, 1, 0), FColor.GREEN);
        assertEquals(new FColor(0, 0, 1), FColor.BLUE);
        assertEquals(new FColor(1, 1, 0), FColor.YELLOW);
        assertEquals(new FColor(1, 0, 1), FColor.MAGENTA);
        assertEquals(new FColor(0, 1, 1), FColor.CYAN);
    }

    @Test
    void lin2srgbRoundTrip() {
        FColor c = new FColor(0.5f, 0.3f, 0.8f, 1.0f);
        FColor srgb = c.lin2srgb();
        FColor back = srgb.srgb2lin();
        assertEquals(c.r, back.r, 0.01f);
        assertEquals(c.g, back.g, 0.01f);
        assertEquals(c.b, back.b, 0.01f);
        assertEquals(c.a, back.a, EPS);
    }

    @Test
    void lin2srgbfBlackHandled() {
        FColor c = new FColor(0f, 0f, 0f);
        FColor r = c.lin2srgbf();
        assertEquals(0f, r.r, EPS);
        assertEquals(0f, r.g, EPS);
        assertEquals(0f, r.b, EPS);
    }

    @Test
    void srgb2linfBlackHandled() {
        FColor c = new FColor(0f, 0f, 0f);
        FColor r = c.srgb2linf();
        assertEquals(0f, r.r, EPS);
        assertEquals(0f, r.g, EPS);
        assertEquals(0f, r.b, EPS);
    }

    @Test
    void toStringContainsColor() {
        String s = new FColor(0.5f, 0.5f, 0.5f).toString();
        assertTrue(s.startsWith("color("));
    }
}
