package haven;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import static org.junit.jupiter.api.Assertions.*;

public class MessageBufTest {

    // --- Integer round-trips ---

    @Test
    void int8RoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addint8((byte) -42);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(-42, r.int8());
    }

    @Test
    void uint8RoundTrip() {
        MessageBuf w = new MessageBuf();
        w.adduint8(200);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(200, r.uint8());
    }

    @Test
    void int16RoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addint16((short) -12345);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(-12345, r.int16());
    }

    @Test
    void uint16RoundTrip() {
        MessageBuf w = new MessageBuf();
        w.adduint16(50000);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(50000, r.uint16());
    }

    @Test
    void int32RoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addint32(-123456789);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(-123456789, r.int32());
    }

    @Test
    void uint32RoundTrip() {
        MessageBuf w = new MessageBuf();
        w.adduint32(3000000000L);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(3000000000L, r.uint32());
    }

    @Test
    void int64RoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addint64(Long.MAX_VALUE);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(Long.MAX_VALUE, r.int64());

        w = new MessageBuf();
        w.addint64(Long.MIN_VALUE);
        r = new MessageBuf(w.fin());
        assertEquals(Long.MIN_VALUE, r.int64());
    }

    // --- String round-trips ---

    @Test
    void stringRoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addstring("hello world");
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals("hello world", r.string());
    }

    @Test
    void emptyString() {
        MessageBuf w = new MessageBuf();
        w.addstring("");
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals("", r.string());
    }

    @Test
    void unicodeString() {
        MessageBuf w = new MessageBuf();
        w.addstring("\u00e9\u00e8\u00ea"); // accented characters
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals("\u00e9\u00e8\u00ea", r.string());
    }

    // --- Coord ---

    @Test
    void coordRoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addcoord(Coord.of(42, -99));
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(Coord.of(42, -99), r.coord());
    }

    // --- Color ---

    @Test
    void colorRoundTrip() {
        MessageBuf w = new MessageBuf();
        Color c = new Color(255, 128, 0, 200);
        w.addcolor(c);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(c, r.color());
    }

    // --- Float ---

    @Test
    void float32RoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addfloat32(3.14f);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(3.14f, r.float32(), 0.0001f);
    }

    @Test
    void float64RoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addfloat64(2.718281828);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(2.718281828, r.float64(), 0.0000001);
    }

    // --- Bytes ---

    @Test
    void bytesRoundTrip() {
        byte[] data = {1, 2, 3, 4, 5, (byte) 0xFF};
        MessageBuf w = new MessageBuf();
        w.addbytes(data);
        MessageBuf r = new MessageBuf(w.fin());
        assertArrayEquals(data, r.bytes(data.length));
    }

    @Test
    void bytesAll() {
        byte[] data = {10, 20, 30};
        MessageBuf w = new MessageBuf();
        w.addbytes(data);
        MessageBuf r = new MessageBuf(w.fin());
        assertArrayEquals(data, r.bytes());
    }

    // --- Mixed types in sequence ---

    @Test
    void mixedSequence() {
        MessageBuf w = new MessageBuf();
        w.adduint8(42);
        w.addstring("test");
        w.addint32(999);
        w.addcoord(Coord.of(1, 2));

        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(42, r.uint8());
        assertEquals("test", r.string());
        assertEquals(999, r.int32());
        assertEquals(Coord.of(1, 2), r.coord());
    }

    // --- MessageBuf specifics ---

    @Test
    void eom() {
        MessageBuf m = new MessageBuf(new byte[]{1, 2, 3});
        assertFalse(m.eom());
        m.bytes(3);
        assertTrue(m.eom());
    }

    @Test
    void rem() {
        MessageBuf m = new MessageBuf(new byte[]{1, 2, 3, 4, 5});
        assertEquals(5, m.rem());
        m.uint8();
        assertEquals(4, m.rem());
    }

    @Test
    void rewind() {
        MessageBuf m = new MessageBuf(new byte[]{1, 2, 3});
        assertEquals(1, m.uint8());
        assertEquals(2, m.uint8());
        m.rewind();
        assertEquals(1, m.uint8());
    }

    @Test
    void skip() {
        MessageBuf m = new MessageBuf(new byte[]{1, 2, 3, 4, 5});
        m.skip(3);
        assertEquals(4, m.uint8());
    }

    @Test
    void cloneIsIndependent() {
        MessageBuf m = new MessageBuf(new byte[]{1, 2, 3});
        m.uint8(); // advance read pointer
        MessageBuf c = m.clone();
        // clone copies from original start, not current position
        assertEquals(1, c.uint8());
    }

    @Test
    void fin() {
        MessageBuf w = new MessageBuf();
        w.adduint8(0xAB);
        w.adduint8(0xCD);
        byte[] result = w.fin();
        assertEquals(2, result.length);
        assertEquals((byte) 0xAB, result[0]);
        assertEquals((byte) 0xCD, result[1]);
    }

    @Test
    void size() {
        MessageBuf w = new MessageBuf();
        assertEquals(0, w.size());
        w.addint32(42);
        assertEquals(4, w.size());
        w.adduint8(1);
        assertEquals(5, w.size());
    }

    @Test
    void equalsIdenticalContent() {
        MessageBuf a = new MessageBuf(new byte[]{1, 2, 3});
        MessageBuf b = new MessageBuf(new byte[]{1, 2, 3});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equalsDifferentContent() {
        MessageBuf a = new MessageBuf(new byte[]{1, 2, 3});
        MessageBuf b = new MessageBuf(new byte[]{1, 2, 4});
        assertNotEquals(a, b);
    }

    @Test
    void equalsDifferentLength() {
        MessageBuf a = new MessageBuf(new byte[]{1, 2, 3});
        MessageBuf b = new MessageBuf(new byte[]{1, 2});
        assertNotEquals(a, b);
    }

    @Test
    void nilMessage() {
        assertTrue(MessageBuf.nil.eom());
    }

    @Test
    void eofOnUnderflow() {
        MessageBuf m = new MessageBuf(new byte[]{1});
        m.uint8();
        assertThrows(Message.EOF.class, () -> m.uint8());
    }

    @Test
    void nilMessageNotWritable() {
        assertThrows(RuntimeException.class, () -> Message.nil.adduint8(0));
    }

    @Test
    void overflowGrowsBuffer() {
        MessageBuf w = new MessageBuf();
        // Write enough data to trigger multiple buffer growths
        for (int i = 0; i < 1000; i++) {
            w.addint32(i);
        }
        assertEquals(4000, w.size());
        MessageBuf r = new MessageBuf(w.fin());
        for (int i = 0; i < 1000; i++) {
            assertEquals(i, r.int32());
        }
    }

    @Test
    void constructFromBlob() {
        byte[] blob = {0, 1, 2, 3, 4, 5, 6, 7};
        MessageBuf m = new MessageBuf(blob, 2, 4);
        assertEquals(4, m.rem());
        assertEquals(2, m.uint8());
        assertEquals(3, m.uint8());
        assertEquals(4, m.uint8());
        assertEquals(5, m.uint8());
        assertTrue(m.eom());
    }

    @Test
    void nullBlobThrows() {
        assertThrows(NullPointerException.class, () -> new MessageBuf(null, 0, 0));
    }

    @Test
    void toStringFormat() {
        MessageBuf m = new MessageBuf(new byte[]{(byte) 0xAB, (byte) 0xCD});
        String s = m.toString();
        assertTrue(s.contains("ab"));
        assertTrue(s.contains("cd"));
    }

    // --- TTO (tagged type-object) round-trips ---

    @Test
    void ttoIntRoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addtto(42);  // small positive -> T_UINT8
        MessageBuf r = new MessageBuf(w.fin());
        Object val = r.tto();
        assertEquals(42, ((Number) val).intValue());
    }

    @Test
    void ttoNegativeInt() {
        MessageBuf w = new MessageBuf();
        w.addtto(-50); // negative byte range -> T_INT8
        MessageBuf r = new MessageBuf(w.fin());
        Object val = r.tto();
        assertEquals(-50, ((Number) val).intValue());
    }

    @Test
    void ttoLargeInt() {
        MessageBuf w = new MessageBuf();
        w.addtto(100000); // > 65535 -> T_INT
        MessageBuf r = new MessageBuf(w.fin());
        Object val = r.tto();
        assertEquals(100000, ((Number) val).intValue());
    }

    @Test
    void ttoStringRoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addtto("hello");
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals("hello", r.tto());
    }

    @Test
    void ttoCoordRoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addtto(Coord.of(10, 20));
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(Coord.of(10, 20), r.tto());
    }

    @Test
    void ttoNullRoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addtto(null);
        MessageBuf r = new MessageBuf(w.fin());
        assertNull(r.tto());
    }

    @Test
    void ttoBytesRoundTrip() {
        byte[] data = {1, 2, 3, 4, 5};
        MessageBuf w = new MessageBuf();
        w.addtto(data);
        MessageBuf r = new MessageBuf(w.fin());
        assertArrayEquals(data, (byte[]) r.tto());
    }

    @Test
    void ttoColorRoundTrip() {
        Color c = new Color(100, 150, 200, 255);
        MessageBuf w = new MessageBuf();
        w.addtto(c);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(c, r.tto());
    }

    @Test
    void ttoFloat32RoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addtto(3.14f);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(3.14f, (float) r.tto(), 0.001f);
    }

    @Test
    void ttoFloat64RoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addtto(2.718281828);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(2.718281828, (double) r.tto(), 0.0000001);
    }

    @Test
    void ttoListRoundTrip() {
        Object[] list = {42, "test", Coord.of(1, 2)};
        MessageBuf w = new MessageBuf();
        w.addtto(list);
        MessageBuf r = new MessageBuf(w.fin());
        Object[] result = (Object[]) r.tto();
        assertEquals(3, result.length);
        assertEquals(42, ((Number) result[0]).intValue());
        assertEquals("test", result[1]);
        assertEquals(Coord.of(1, 2), result[2]);
    }

    @Test
    void listMethodRoundTrip() {
        MessageBuf w = new MessageBuf();
        w.addlist(1, "two", Coord.of(3, 4));
        w.adduint8(Message.T_END);
        MessageBuf r = new MessageBuf(w.fin());
        Object[] list = r.list();
        assertEquals(3, list.length);
        assertEquals(1, ((Number) list[0]).intValue());
        assertEquals("two", list[1]);
        assertEquals(Coord.of(3, 4), list[2]);
    }

    @Test
    void ttoLongRoundTrip() {
        long big = Long.MAX_VALUE;
        MessageBuf w = new MessageBuf();
        w.addtto(big);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(big, ((Number) r.tto()).longValue());
    }

    @Test
    void ttoUint16Range() {
        // value in 256..65535 should use T_UINT16
        MessageBuf w = new MessageBuf();
        w.addtto(300);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(300, ((Number) r.tto()).intValue());
    }

    @Test
    void ttoNegativeShort() {
        // value in -32768..-129 should use T_INT16
        MessageBuf w = new MessageBuf();
        w.addtto(-500);
        MessageBuf r = new MessageBuf(w.fin());
        assertEquals(-500, ((Number) r.tto()).intValue());
    }
}
