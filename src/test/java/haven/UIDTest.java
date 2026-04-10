package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class UIDTest {

    @Test
    void ofZeroReturnsNil() {
        assertSame(UID.nil, UID.of(0));
    }

    @Test
    void nilHasZeroBits() {
        assertEquals(0, UID.nil.bits);
    }

    @Test
    void ofNonZero() {
        UID uid = UID.of(12345);
        assertEquals(12345, uid.bits);
        assertNotSame(UID.nil, uid);
    }

    @Test
    void equalsAndHashCode() {
        UID a = UID.of(42);
        UID b = UID.of(42);
        UID c = UID.of(43);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, "not a uid");
    }

    @Test
    void nilEquals() {
        assertEquals(UID.nil, UID.of(0));
    }

    @Test
    void longValue() {
        UID uid = UID.of(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, uid.longValue());
    }

    @Test
    void intValue() {
        UID uid = UID.of(42);
        assertEquals(42, uid.intValue());
    }

    @Test
    void shortValue() {
        UID uid = UID.of(100);
        assertEquals((short) 100, uid.shortValue());
    }

    @Test
    void byteValue() {
        UID uid = UID.of(10);
        assertEquals((byte) 10, uid.byteValue());
    }

    @Test
    void floatValue() {
        UID uid = UID.of(42);
        assertEquals(42f, uid.floatValue(), 0.1f);
    }

    @Test
    void doubleValue() {
        UID uid = UID.of(42);
        assertEquals(42.0, uid.doubleValue(), 0.1);
    }

    @Test
    void toStringHex() {
        UID uid = UID.of(255);
        assertEquals("ff", uid.toString());
    }

    @Test
    void toStringZero() {
        assertEquals("0", UID.nil.toString());
    }

    @Test
    void toStringLargeValue() {
        UID uid = UID.of(0xDEADBEEFL);
        assertEquals("deadbeef", uid.toString());
    }

    @Test
    void negativeAsUnsigned() {
        UID uid = UID.of(-1);
        assertEquals("ffffffffffffffff", uid.toString());
    }
}
