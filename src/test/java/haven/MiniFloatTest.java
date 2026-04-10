package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MiniFloatTest {

    @Test
    void zeroRoundTrip() {
        byte bits = MiniFloat.bits(0.0f);
        assertEquals(0.0f, MiniFloat.bits(bits), 0f);
    }

    @Test
    void oneRoundTrip() {
        byte bits = MiniFloat.bits(1.0f);
        assertEquals(1.0f, MiniFloat.bits(bits), 0.1f);
    }

    @Test
    void negativeOneRoundTrip() {
        byte bits = MiniFloat.bits(-1.0f);
        assertEquals(-1.0f, MiniFloat.bits(bits), 0.1f);
    }

    @Test
    void largeValueClampsToInfinity() {
        byte bits = MiniFloat.bits(100000.0f);
        assertTrue(Float.isInfinite(MiniFloat.bits(bits)));
    }

    @Test
    void infinity() {
        byte bits = MiniFloat.bits(Float.POSITIVE_INFINITY);
        assertTrue(Float.isInfinite(MiniFloat.bits(bits)));
    }

    @Test
    void negativeInfinity() {
        byte bits = MiniFloat.bits(Float.NEGATIVE_INFINITY);
        float result = MiniFloat.bits(bits);
        assertTrue(Float.isInfinite(result));
        assertTrue(result < 0);
    }

    @Test
    void nan() {
        byte bits = MiniFloat.bits(Float.NaN);
        assertTrue(Float.isNaN(MiniFloat.bits(bits)));
    }

    @Test
    void ofFactory() {
        MiniFloat mf = MiniFloat.of(2.0f);
        assertEquals(2.0f, mf.floatValue(), 0.5f);
    }

    @Test
    void ofDouble() {
        MiniFloat mf = MiniFloat.of(2.0);
        assertEquals(2.0f, mf.floatValue(), 0.5f);
    }

    @Test
    void decode() {
        byte bits = MiniFloat.bits(1.0f);
        MiniFloat mf = MiniFloat.decode(bits);
        assertEquals(1.0f, mf.floatValue(), 0.1f);
    }

    @Test
    void equalsAndHashCode() {
        MiniFloat a = MiniFloat.of(1.0f);
        MiniFloat b = MiniFloat.of(1.0f);
        MiniFloat c = MiniFloat.of(2.0f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, "not a minifloat");
    }

    @Test
    void numberConversions() {
        MiniFloat mf = MiniFloat.of(2.0f);
        assertEquals(2, mf.intValue());
        assertEquals(2L, mf.longValue());
        assertEquals(2.0, mf.doubleValue(), 0.5);
    }

    @Test
    void toStringProducesFloat() {
        MiniFloat mf = MiniFloat.of(1.0f);
        assertNotNull(mf.toString());
        assertFalse(mf.toString().isEmpty());
    }

    @Test
    void powersOfTwoRoundTrip() {
        float[] values = {0.5f, 1.0f, 2.0f, 4.0f, 8.0f, 16.0f};
        for (float v : values) {
            byte bits = MiniFloat.bits(v);
            assertEquals(v, MiniFloat.bits(bits), v * 0.15f,
                "Round-trip failed for " + v);
        }
    }
}
