package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DRandomTest {

    @Test
    void deterministicSameSeed() {
        DRandom a = new DRandom(42);
        DRandom b = new DRandom(42);
        assertEquals(a.randi(1), b.randi(1));
        assertEquals(a.randi(100), b.randi(100));
    }

    @Test
    void deterministicTwoParams() {
        DRandom a = new DRandom(42);
        DRandom b = new DRandom(42);
        assertEquals(a.randi(1, 2), b.randi(1, 2));
    }

    @Test
    void deterministicVarargs() {
        DRandom a = new DRandom(42);
        DRandom b = new DRandom(42);
        assertEquals(a.randi(1, 2, 3), b.randi(1, 2, 3));
    }

    @Test
    void differentSeedsDifferentResults() {
        DRandom a = new DRandom(1);
        DRandom b = new DRandom(2);
        // Extremely unlikely to be equal
        assertNotEquals(a.randi(100), b.randi(100));
    }

    @Test
    void differentParamsDifferentResults() {
        DRandom r = new DRandom(42);
        int a = r.randi(1);
        int b = r.randi(2);
        // Different param should give different output (statistically)
        assertNotEquals(a, b);
    }

    @Test
    void sameParamSameResult() {
        DRandom r = new DRandom(42);
        int first = r.randi(5);
        int second = r.randi(5);
        // Same seed + same param = same result (deterministic)
        assertEquals(first, second);
    }

    @Test
    void randlDeterministic() {
        DRandom a = new DRandom(99);
        DRandom b = new DRandom(99);
        assertEquals(a.randl(10), b.randl(10));
        assertEquals(a.randl(10, 20), b.randl(10, 20));
        assertEquals(a.randl(1, 2, 3), b.randl(1, 2, 3));
    }

    @Test
    void randfDeterministic() {
        DRandom a = new DRandom(99);
        DRandom b = new DRandom(99);
        assertEquals(a.randf(10), b.randf(10));
        assertEquals(a.randf(10, 20), b.randf(10, 20));
        assertEquals(a.randf(1, 2, 3), b.randf(1, 2, 3));
    }

    @Test
    void randfInRange() {
        DRandom r = new DRandom(42);
        for (int i = 0; i < 100; i++) {
            float f = r.randf(i);
            assertTrue(f >= 0.0f && f < 1.0f, "randf should be in [0,1): " + f);
        }
    }

    @Test
    void randdDeterministic() {
        DRandom a = new DRandom(99);
        DRandom b = new DRandom(99);
        assertEquals(a.randd(10), b.randd(10));
        assertEquals(a.randd(10, 20), b.randd(10, 20));
        assertEquals(a.randd(1, 2, 3), b.randd(1, 2, 3));
    }

    @Test
    void randdInRange() {
        DRandom r = new DRandom(42);
        for (int i = 0; i < 100; i++) {
            double d = r.randd(i);
            assertTrue(d >= 0.0 && d < 1.0, "randd should be in [0,1): " + d);
        }
    }

    @Test
    void constructFromRandom() {
        java.util.Random src = new java.util.Random(42);
        DRandom a = new DRandom(src);
        // Recreate with same source seed to get same DRandom seed
        java.util.Random src2 = new java.util.Random(42);
        DRandom b = new DRandom(src2);
        assertEquals(a.randi(1), b.randi(1));
    }
}
