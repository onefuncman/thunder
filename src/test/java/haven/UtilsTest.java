package haven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import static org.junit.jupiter.api.Assertions.*;

public class UtilsTest {

    // --- Integer encoding/decoding ---

    @Test
    void int16RoundTrip() {
        byte[] buf = new byte[2];
        Utils.int16e((short) -1234, buf, 0);
        assertEquals(-1234, Utils.int16d(buf, 0));
    }

    @Test
    void uint16RoundTrip() {
        byte[] buf = new byte[2];
        Utils.uint16e(50000, buf, 0);
        assertEquals(50000, Utils.uint16d(buf, 0));
    }

    @Test
    void int32RoundTrip() {
        byte[] buf = new byte[4];
        Utils.int32e(-123456789, buf, 0);
        assertEquals(-123456789, Utils.int32d(buf, 0));
    }

    @Test
    void uint32RoundTrip() {
        byte[] buf = new byte[4];
        Utils.uint32e(3000000000L, buf, 0);
        assertEquals(3000000000L, Utils.uint32d(buf, 0));
    }

    @Test
    void int64RoundTrip() {
        byte[] buf = new byte[8];
        Utils.int64e(Long.MAX_VALUE, buf, 0);
        assertEquals(Long.MAX_VALUE, Utils.int64d(buf, 0));
    }

    @Test
    void int64MinValue() {
        byte[] buf = new byte[8];
        Utils.int64e(Long.MIN_VALUE, buf, 0);
        assertEquals(Long.MIN_VALUE, Utils.int64d(buf, 0));
    }

    @Test
    void int16dWithOffset() {
        byte[] buf = new byte[4];
        Utils.int16e((short) 42, buf, 2);
        assertEquals(42, Utils.int16d(buf, 2));
    }

    @Test
    void int32dWithOffset() {
        byte[] buf = new byte[8];
        Utils.int32e(999, buf, 4);
        assertEquals(999, Utils.int32d(buf, 4));
    }

    // --- Float encoding/decoding ---

    @Test
    void float32RoundTrip() {
        byte[] buf = new byte[4];
        Utils.float32e(3.14f, buf, 0);
        assertEquals(3.14f, Utils.float32d(buf, 0), 1e-6f);
    }

    @Test
    void float64RoundTrip() {
        byte[] buf = new byte[8];
        Utils.float64e(2.718281828, buf, 0);
        assertEquals(2.718281828, Utils.float64d(buf, 0), 1e-10);
    }

    @Test
    void float32SpecialValues() {
        byte[] buf = new byte[4];
        Utils.float32e(Float.NaN, buf, 0);
        assertTrue(Float.isNaN(Utils.float32d(buf, 0)));

        Utils.float32e(Float.POSITIVE_INFINITY, buf, 0);
        assertEquals(Float.POSITIVE_INFINITY, Utils.float32d(buf, 0));

        Utils.float32e(Float.NEGATIVE_INFINITY, buf, 0);
        assertEquals(Float.NEGATIVE_INFINITY, Utils.float32d(buf, 0));
    }

    // --- floordiv / floormod ---

    @Test
    void floordivPositive() {
        assertEquals(3, Utils.floordiv(7, 2));
    }

    @Test
    void floordivNegative() {
        assertEquals(-4, Utils.floordiv(-7, 2));
    }

    @Test
    void floordivExact() {
        assertEquals(5, Utils.floordiv(10, 2));
    }

    @Test
    void floordivNegativeExact() {
        assertEquals(-5, Utils.floordiv(-10, 2));
    }

    @Test
    void floormodPositive() {
        assertEquals(1, Utils.floormod(7, 3));
    }

    @Test
    void floormodNegative() {
        assertEquals(2, Utils.floormod(-7, 3));
    }

    @Test
    void floormodZero() {
        assertEquals(0, Utils.floormod(9, 3));
    }

    @Test
    void floordivFloat() {
        assertEquals(1, Utils.floordiv(3.5f, 2.0f));
        assertEquals(-2, Utils.floordiv(-3.5f, 2.0f));
    }

    @Test
    void floordivDouble() {
        assertEquals(1, Utils.floordiv(3.5, 2.0));
        assertEquals(-2, Utils.floordiv(-3.5, 2.0));
    }

    @Test
    void floormodFloat() {
        assertEquals(1.5f, Utils.floormod(3.5f, 2.0f), 1e-6f);
    }

    @Test
    void floormodDouble() {
        assertEquals(1.5, Utils.floormod(3.5, 2.0), 1e-10);
    }

    // --- clip ---

    @Test
    void clipInt() {
        assertEquals(5, Utils.clip(5, 0, 10));
        assertEquals(0, Utils.clip(-1, 0, 10));
        assertEquals(10, Utils.clip(15, 0, 10));
    }

    @Test
    void clipFloat() {
        assertEquals(0.5f, Utils.clip(0.5f, 0f, 1f), 1e-6f);
        assertEquals(0f, Utils.clip(-0.5f, 0f, 1f), 1e-6f);
        assertEquals(1f, Utils.clip(1.5f, 0f, 1f), 1e-6f);
    }

    @Test
    void clipDouble() {
        assertEquals(0.5, Utils.clip(0.5, 0.0, 1.0), 1e-10);
        assertEquals(0.0, Utils.clip(-0.5, 0.0, 1.0), 1e-10);
        assertEquals(1.0, Utils.clip(1.5, 0.0, 1.0), 1e-10);
    }

    @Test
    void clipnorm() {
        assertEquals(0.5, Utils.clipnorm(5.0, 0.0, 10.0), 1e-10);
        assertEquals(0.0, Utils.clipnorm(-1.0, 0.0, 10.0), 1e-10);
        assertEquals(1.0, Utils.clipnorm(15.0, 0.0, 10.0), 1e-10);
    }

    // --- Angle normalization ---

    @Test
    void cangle() {
        assertEquals(0.0, Utils.cangle(0.0), 1e-10);
        assertEquals(0.0, Utils.cangle(2 * Math.PI), 1e-10);
        assertEquals(0.0, Utils.cangle(-2 * Math.PI), 1e-10);
        double a = Utils.cangle(4.0);
        assertTrue(a >= -Math.PI && a <= Math.PI);
    }

    @Test
    void cangle2() {
        assertEquals(0.0, Utils.cangle2(0.0), 1e-10);
        // 2*PI stays as 2*PI because the loop condition is strict >
        double twoPi = Utils.cangle2(2 * Math.PI);
        assertTrue(twoPi >= 0 && twoPi <= 2 * Math.PI);
        // Negative angle wraps into [0, 2*PI)
        double a = Utils.cangle2(-1.0);
        assertTrue(a >= 0 && a <= 2 * Math.PI);
        // Value > 2*PI wraps
        double b = Utils.cangle2(3 * Math.PI);
        assertTrue(b >= 0 && b <= 2 * Math.PI);
    }

    // --- Hex ---

    @Test
    void num2hex() {
        assertEquals('0', Utils.num2hex(0, false));
        assertEquals('9', Utils.num2hex(9, false));
        assertEquals('a', Utils.num2hex(10, false));
        assertEquals('f', Utils.num2hex(15, false));
        assertEquals('A', Utils.num2hex(10, true));
        assertEquals('F', Utils.num2hex(15, true));
    }

    @Test
    void hex2num() {
        assertEquals(0, Utils.hex2num('0'));
        assertEquals(9, Utils.hex2num('9'));
        assertEquals(10, Utils.hex2num('a'));
        assertEquals(10, Utils.hex2num('A'));
        assertEquals(15, Utils.hex2num('f'));
        assertEquals(15, Utils.hex2num('F'));
    }

    @Test
    void hex2numInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Utils.hex2num('g'));
    }

    @Test
    void hexRoundTrip() {
        byte[] data = {0x01, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF};
        String encoded = Utils.byte2hex(data);
        assertArrayEquals(data, Utils.hex2byte(encoded));
    }

    @Test
    void hexEmptyArray() {
        assertEquals("", Utils.byte2hex(new byte[0]));
        assertArrayEquals(new byte[0], Utils.hex2byte(""));
    }

    @Test
    void hexDecodeOddLength() {
        assertThrows(IllegalArgumentException.class, () -> Utils.hex2byte("ABC"));
    }

    // --- Base64 ---

    @Test
    void base64RoundTrip() {
        byte[] data = "Hello, World!".getBytes();
        String encoded = Utils.b64.enc(data);
        assertArrayEquals(data, Utils.b64.dec(encoded));
    }

    @Test
    void base64Empty() {
        assertEquals("", Utils.b64.enc(new byte[0]));
        assertArrayEquals(new byte[0], Utils.b64.dec(""));
    }

    @Test
    void base64PaddingSingleByte() {
        byte[] data = {0x01};
        String encoded = Utils.b64.enc(data);
        assertTrue(encoded.endsWith("=="));
        assertArrayEquals(data, Utils.b64.dec(encoded));
    }

    @Test
    void base64PaddingTwoBytes() {
        byte[] data = {0x01, 0x02};
        String encoded = Utils.b64.enc(data);
        assertTrue(encoded.endsWith("="));
        assertArrayEquals(data, Utils.b64.dec(encoded));
    }

    @Test
    void base64NoPadRoundTrip() {
        byte[] data = {0x01, 0x02, 0x03};
        String encoded = Utils.b64np.enc(data);
        assertFalse(encoded.contains("="));
        assertArrayEquals(data, Utils.b64np.dec(encoded));
    }

    @Test
    void urlSafeBase64RoundTrip() {
        byte[] data = {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
        String encoded = Utils.ub64.enc(data);
        assertFalse(encoded.contains("+"));
        assertFalse(encoded.contains("/"));
        assertArrayEquals(data, Utils.ub64.dec(encoded));
    }

    // --- splitwords ---

    @Test
    void splitwordsSimple() {
        String[] words = Utils.splitwords("hello world");
        assertArrayEquals(new String[]{"hello", "world"}, words);
    }

    @Test
    void splitwordsQuoted() {
        String[] words = Utils.splitwords("hello \"world of code\"");
        assertArrayEquals(new String[]{"hello", "world of code"}, words);
    }

    @Test
    void splitwordsEscaped() {
        String[] words = Utils.splitwords("hello\\ world");
        assertArrayEquals(new String[]{"hello world"}, words);
    }

    @Test
    void splitwordsEmpty() {
        String[] words = Utils.splitwords("");
        assertNotNull(words);
        assertEquals(0, words.length);
    }

    @Test
    void splitwordsMultipleSpaces() {
        String[] words = Utils.splitwords("  hello   world  ");
        assertArrayEquals(new String[]{"hello", "world"}, words);
    }

    // --- splitlines ---

    @Test
    void splitlines() {
        String[] lines = Utils.splitlines("a\nb\nc");
        assertArrayEquals(new String[]{"a", "b", "c"}, lines);
    }

    @Test
    void splitlinesNoNewline() {
        String[] lines = Utils.splitlines("hello");
        assertArrayEquals(new String[]{"hello"}, lines);
    }

    @Test
    void splitlinesTrailingNewline() {
        String[] lines = Utils.splitlines("a\n");
        assertArrayEquals(new String[]{"a", ""}, lines);
    }

    // --- smoothstep ---

    @Test
    void smoothstepFloat() {
        assertEquals(0f, Utils.smoothstep(0f), 1e-6f);
        assertEquals(1f, Utils.smoothstep(1f), 1e-6f);
        assertEquals(0.5f, Utils.smoothstep(0.5f), 1e-6f);
    }

    @Test
    void smoothstepDouble() {
        assertEquals(0.0, Utils.smoothstep(0.0), 1e-10);
        assertEquals(1.0, Utils.smoothstep(1.0), 1e-10);
        assertEquals(0.5, Utils.smoothstep(0.5), 1e-10);
    }

    // --- uint32 ---

    @Test
    void uint32() {
        assertEquals(0xFFFFFFFFL, Utils.uint32(-1));
        assertEquals(0L, Utils.uint32(0));
        assertEquals(1L, Utils.uint32(1));
    }

    // --- intvard ---

    @Test
    void intvard1Byte() {
        assertEquals(42, Utils.intvard(new byte[]{42}, 0));
    }

    @Test
    void intvard2Bytes() {
        byte[] buf = new byte[2];
        Utils.int16e((short) 1000, buf, 0);
        assertEquals(1000, Utils.intvard(buf, 0));
    }

    @Test
    void intvard4Bytes() {
        byte[] buf = new byte[4];
        Utils.int32e(123456, buf, 0);
        assertEquals(123456, Utils.intvard(buf, 0));
    }

    @Test
    void intvardInvalidLength() {
        assertThrows(IllegalArgumentException.class, () -> Utils.intvard(new byte[3], 0));
    }

    // --- f2s8, f2u8 ---

    @Test
    void f2s8() {
        assertEquals(127, Utils.f2s8(1.0f));
        assertEquals(-127, Utils.f2s8(-1.0f));
        assertEquals(0, Utils.f2s8(0.0f));
    }

    @Test
    void f2u8() {
        assertEquals((byte) 255, Utils.f2u8(1.0f));
        assertEquals((byte) 0, Utils.f2u8(0.0f));
        assertEquals((byte) 0, Utils.f2u8(-1.0f));
    }
}
