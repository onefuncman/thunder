package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NormNumberTest {

    // --- SNorm8 ---

    @Test
    void snorm8Values() {
        NormNumber.SNorm8 max = NormNumber.decsnorm8(127);
        NormNumber.SNorm8 min = NormNumber.decsnorm8(-127);
        NormNumber.SNorm8 zero = NormNumber.decsnorm8(0);
        assertEquals(1.0f, max.floatValue(), 1e-3f);
        assertEquals(-1.0f, min.floatValue(), 1e-3f);
        assertEquals(0.0f, zero.floatValue(), 1e-3f);
        assertEquals(1, max.intValue());
        assertEquals(-1, min.intValue());
        assertEquals(0, zero.intValue());
    }

    @Test
    void snorm8FromFloat() {
        NormNumber.SNorm8 n = NormNumber.snorm8(0.5f);
        assertEquals(0.5f, n.floatValue(), 0.02f);
    }

    @Test
    void snorm8FromDouble() {
        NormNumber.SNorm8 n = NormNumber.snorm8(0.5);
        assertEquals(0.5, n.doubleValue(), 0.02);
    }

    @Test
    void snorm8Clamps() {
        NormNumber.SNorm8 n = NormNumber.snorm8(2.0f);
        assertEquals(1.0f, n.floatValue(), 0.02f);
    }

    @Test
    void snorm8RejectsInvalid() {
        // 0x80 is an invalid SNorm8 value
        assertThrows(IllegalArgumentException.class, () -> new NormNumber.SNorm8((byte) 0x80));
    }

    @Test
    void snorm8TtoRoundTrip() {
        NormNumber.SNorm8 original = NormNumber.snorm8(0.75f);
        MessageBuf w = new MessageBuf();
        w.addtto(original);
        MessageBuf r = new MessageBuf(w.fin());
        Object decoded = r.tto();
        assertTrue(decoded instanceof NormNumber.SNorm8);
        assertEquals(original.floatValue(), ((NormNumber.SNorm8) decoded).floatValue(), 0.02f);
    }

    // --- UNorm8 ---

    @Test
    void unorm8Values() {
        NormNumber.UNorm8 max = NormNumber.decunorm8(255);
        NormNumber.UNorm8 zero = NormNumber.decunorm8(0);
        assertEquals(1.0f, max.floatValue(), 1e-3f);
        assertEquals(0.0f, zero.floatValue(), 1e-3f);
        assertEquals(1, max.intValue());
        assertEquals(0, zero.intValue());
    }

    @Test
    void unorm8FromFloat() {
        NormNumber.UNorm8 n = NormNumber.unorm8(0.5f);
        assertEquals(0.5f, n.floatValue(), 0.02f);
    }

    @Test
    void unorm8TtoRoundTrip() {
        NormNumber.UNorm8 original = NormNumber.unorm8(0.75f);
        MessageBuf w = new MessageBuf();
        w.addtto(original);
        MessageBuf r = new MessageBuf(w.fin());
        Object decoded = r.tto();
        assertTrue(decoded instanceof NormNumber.UNorm8);
        assertEquals(original.floatValue(), ((NormNumber.UNorm8) decoded).floatValue(), 0.02f);
    }

    // --- MNorm8 ---

    @Test
    void mnorm8Values() {
        NormNumber.MNorm8 half = NormNumber.decmnorm8(128);
        assertEquals(0.5f, half.floatValue(), 0.01f);
        assertEquals(0, half.intValue()); // intValue always 0
    }

    @Test
    void mnorm8FromFloat() {
        NormNumber.MNorm8 n = NormNumber.mnorm8(0.25f);
        assertEquals(0.25f, n.floatValue(), 0.02f);
    }

    @Test
    void mnorm8TtoRoundTrip() {
        NormNumber.MNorm8 original = NormNumber.mnorm8(0.3f);
        MessageBuf w = new MessageBuf();
        w.addtto(original);
        MessageBuf r = new MessageBuf(w.fin());
        Object decoded = r.tto();
        assertTrue(decoded instanceof NormNumber.MNorm8);
        assertEquals(original.floatValue(), ((NormNumber.MNorm8) decoded).floatValue(), 0.02f);
    }

    // --- SNorm16 ---

    @Test
    void snorm16Values() {
        NormNumber.SNorm16 max = NormNumber.decsnorm16(32767);
        NormNumber.SNorm16 min = NormNumber.decsnorm16(-32767);
        assertEquals(1.0f, max.floatValue(), 1e-4f);
        assertEquals(-1.0f, min.floatValue(), 1e-4f);
        assertEquals(1, max.intValue());
        assertEquals(-1, min.intValue());
    }

    @Test
    void snorm16RejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> new NormNumber.SNorm16((short) 0x8000));
    }

    @Test
    void snorm16TtoRoundTrip() {
        NormNumber.SNorm16 original = NormNumber.snorm16(0.5f);
        MessageBuf w = new MessageBuf();
        w.addtto(original);
        MessageBuf r = new MessageBuf(w.fin());
        Object decoded = r.tto();
        assertTrue(decoded instanceof NormNumber.SNorm16);
        assertEquals(original.floatValue(), ((NormNumber.SNorm16) decoded).floatValue(), 0.001f);
    }

    // --- UNorm16 ---

    @Test
    void unorm16Values() {
        NormNumber.UNorm16 max = NormNumber.decunorm16(65535);
        NormNumber.UNorm16 zero = NormNumber.decunorm16(0);
        assertEquals(1.0f, max.floatValue(), 1e-4f);
        assertEquals(0.0f, zero.floatValue(), 1e-4f);
    }

    @Test
    void unorm16TtoRoundTrip() {
        NormNumber.UNorm16 original = NormNumber.unorm16(0.6f);
        MessageBuf w = new MessageBuf();
        w.addtto(original);
        MessageBuf r = new MessageBuf(w.fin());
        Object decoded = r.tto();
        assertTrue(decoded instanceof NormNumber.UNorm16);
        assertEquals(original.floatValue(), ((NormNumber.UNorm16) decoded).floatValue(), 0.001f);
    }

    // --- MNorm16 ---

    @Test
    void mnorm16Values() {
        NormNumber.MNorm16 half = NormNumber.decmnorm16(32768);
        assertEquals(0.5f, half.floatValue(), 0.01f);
    }

    @Test
    void mnorm16TtoRoundTrip() {
        NormNumber.MNorm16 original = NormNumber.mnorm16(0.4f);
        MessageBuf w = new MessageBuf();
        w.addtto(original);
        MessageBuf r = new MessageBuf(w.fin());
        Object decoded = r.tto();
        assertTrue(decoded instanceof NormNumber.MNorm16);
        assertEquals(original.floatValue(), ((NormNumber.MNorm16) decoded).floatValue(), 0.001f);
    }

    // --- SNorm32 ---

    @Test
    void snorm32Values() {
        NormNumber.SNorm32 max = NormNumber.decsnorm32(0x7fffffff);
        assertEquals(1.0, max.doubleValue(), 1e-8);
        assertEquals(1, max.intValue());
    }

    @Test
    void snorm32RejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> new NormNumber.SNorm32(0x80000000));
    }

    @Test
    void snorm32TtoRoundTrip() {
        NormNumber.SNorm32 original = NormNumber.snorm32(0.5);
        MessageBuf w = new MessageBuf();
        w.addtto(original);
        MessageBuf r = new MessageBuf(w.fin());
        Object decoded = r.tto();
        assertTrue(decoded instanceof NormNumber.SNorm32);
        assertEquals(original.doubleValue(), ((NormNumber.SNorm32) decoded).doubleValue(), 1e-6);
    }

    // --- UNorm32 ---

    @Test
    void unorm32TtoRoundTrip() {
        NormNumber.UNorm32 original = NormNumber.unorm32(0.7);
        MessageBuf w = new MessageBuf();
        w.addtto(original);
        MessageBuf r = new MessageBuf(w.fin());
        Object decoded = r.tto();
        assertTrue(decoded instanceof NormNumber.UNorm32);
        assertEquals(original.doubleValue(), ((NormNumber.UNorm32) decoded).doubleValue(), 1e-6);
    }

    // --- MNorm32 ---

    @Test
    void mnorm32TtoRoundTrip() {
        NormNumber.MNorm32 original = NormNumber.mnorm32(0.6);
        MessageBuf w = new MessageBuf();
        w.addtto(original);
        MessageBuf r = new MessageBuf(w.fin());
        Object decoded = r.tto();
        assertTrue(decoded instanceof NormNumber.MNorm32);
        assertEquals(original.doubleValue(), ((NormNumber.MNorm32) decoded).doubleValue(), 0.001);
    }

    // --- Equality ---

    @Test
    void equalsAndHashCode() {
        NormNumber.SNorm8 a = NormNumber.decsnorm8(50);
        NormNumber.SNorm8 b = NormNumber.decsnorm8(50);
        NormNumber.SNorm8 c = NormNumber.decsnorm8(51);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void equalsDifferentTypes() {
        NormNumber.SNorm8 s = NormNumber.decsnorm8(50);
        NormNumber.UNorm8 u = NormNumber.decunorm8(50);
        assertNotEquals(s, u); // different classes
    }

    @Test
    void longValue() {
        NormNumber.SNorm8 n = NormNumber.decsnorm8(127);
        assertEquals(1L, n.longValue());
    }

    @Test
    void toStringProducesDouble() {
        NormNumber.UNorm8 n = NormNumber.unorm8(0.5f);
        assertNotNull(n.toString());
        assertFalse(n.toString().isEmpty());
    }

    // --- Message decode ---

    @Test
    void decodeFromMessage() {
        MessageBuf buf = new MessageBuf();
        buf.addint8((byte) 64);
        MessageBuf r = new MessageBuf(buf.fin());
        NormNumber.SNorm8 n = NormNumber.decsnorm8(r);
        assertEquals(64, n.val);
        assertEquals(64.0f / 127.0f, n.floatValue(), 0.01f);
    }
}
