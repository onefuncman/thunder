package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class HalfFloatTest {

    @Test
    void zeroRoundTrip() {
        short bits = HalfFloat.bits(0.0f);
        assertEquals(0.0f, HalfFloat.bits(bits), 0f);
    }

    @Test
    void negativeZero() {
        short bits = HalfFloat.bits(-0.0f);
        assertEquals(-0.0f, HalfFloat.bits(bits), 0f);
    }

    @Test
    void oneRoundTrip() {
        short bits = HalfFloat.bits(1.0f);
        assertEquals(1.0f, HalfFloat.bits(bits), 1e-3f);
    }

    @Test
    void negativeOneRoundTrip() {
        short bits = HalfFloat.bits(-1.0f);
        assertEquals(-1.0f, HalfFloat.bits(bits), 1e-3f);
    }

    @Test
    void smallValue() {
        float val = 0.001f;
        short bits = HalfFloat.bits(val);
        assertEquals(val, HalfFloat.bits(bits), 0.001f);
    }

    @Test
    void largeValueClampsToInfinity() {
        short bits = HalfFloat.bits(100000.0f);
        assertTrue(Float.isInfinite(HalfFloat.bits(bits)));
    }

    @Test
    void infinity() {
        short bits = HalfFloat.bits(Float.POSITIVE_INFINITY);
        assertTrue(Float.isInfinite(HalfFloat.bits(bits)));
    }

    @Test
    void negativeInfinity() {
        short bits = HalfFloat.bits(Float.NEGATIVE_INFINITY);
        float result = HalfFloat.bits(bits);
        assertTrue(Float.isInfinite(result));
        assertTrue(result < 0);
    }

    @Test
    void nan() {
        short bits = HalfFloat.bits(Float.NaN);
        assertTrue(Float.isNaN(HalfFloat.bits(bits)));
    }

    @Test
    void ofFactory() {
        HalfFloat hf = HalfFloat.of(2.5f);
        assertEquals(2.5f, hf.floatValue(), 0.01f);
    }

    @Test
    void ofDouble() {
        HalfFloat hf = HalfFloat.of(2.5);
        assertEquals(2.5f, hf.floatValue(), 0.01f);
    }

    @Test
    void decode() {
        short bits = HalfFloat.bits(3.0f);
        HalfFloat hf = HalfFloat.decode(bits);
        assertEquals(3.0f, hf.floatValue(), 0.01f);
    }

    @Test
    void equalsAndHashCode() {
        HalfFloat a = HalfFloat.of(1.5f);
        HalfFloat b = HalfFloat.of(1.5f);
        HalfFloat c = HalfFloat.of(2.5f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, "not a halffloat");
    }

    @Test
    void numberConversions() {
        HalfFloat hf = HalfFloat.of(3.0f);
        assertEquals(3, hf.intValue());
        assertEquals(3L, hf.longValue());
        assertEquals(3.0, hf.doubleValue(), 0.01);
        assertEquals((byte) 3, hf.byteValue());
        assertEquals((short) 3, hf.shortValue());
    }

    @Test
    void toStringProducesFloat() {
        HalfFloat hf = HalfFloat.of(1.5f);
        assertEquals("1.5", hf.toString());
    }

    @Test
    void variousValuesRoundTrip() {
        float[] values = {0.5f, 1.0f, 2.0f, 4.0f, 8.0f, 0.25f, -0.5f, -2.0f};
        for (float v : values) {
            short bits = HalfFloat.bits(v);
            assertEquals(v, HalfFloat.bits(bits), Math.abs(v) * 0.01f,
                "Round-trip failed for " + v);
        }
    }
}
