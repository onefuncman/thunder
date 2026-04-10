package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ResIDTest {

    @Test
    void ofCreatesWithId() {
        ResID r = ResID.of(42);
        assertEquals(42, r.id);
    }

    @Test
    void intValue() {
        assertEquals(42, ResID.of(42).intValue());
    }

    @Test
    void longValue() {
        assertEquals(42L, ResID.of(42).longValue());
    }

    @Test
    void floatValue() {
        assertEquals(42.0f, ResID.of(42).floatValue());
    }

    @Test
    void doubleValue() {
        assertEquals(42.0, ResID.of(42).doubleValue());
    }

    @Test
    void byteValue() {
        assertEquals((byte) 42, ResID.of(42).byteValue());
    }

    @Test
    void shortValue() {
        assertEquals((short) 42, ResID.of(42).shortValue());
    }

    @Test
    void equalsAndHashCode() {
        ResID a = ResID.of(10);
        ResID b = ResID.of(10);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqual() {
        assertNotEquals(ResID.of(1), ResID.of(2));
    }

    @Test
    void notEqualToOtherType() {
        assertNotEquals(ResID.of(1), Integer.valueOf(1));
    }

    @Test
    void hashCodeIsId() {
        assertEquals(99, ResID.of(99).hashCode());
    }

    @Test
    void toStringContainsId() {
        String s = ResID.of(42).toString();
        assertTrue(s.contains("42"));
        assertTrue(s.contains("res-id"));
    }

    @Test
    void zeroId() {
        ResID r = ResID.of(0);
        assertEquals(0, r.id);
        assertEquals(0, r.intValue());
    }

    @Test
    void negativeId() {
        ResID r = ResID.of(-1);
        assertEquals(-1, r.id);
    }

    @Test
    void resolveMapperPassesThrough() {
        ResID.ResolveMapper mapper = new ResID.ResolveMapper(null);
        assertEquals("hello", mapper.apply("hello"));
        assertEquals(42, mapper.apply(42));
    }
}
