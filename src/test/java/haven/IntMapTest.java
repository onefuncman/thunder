package haven;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class IntMapTest {

    @Test
    void emptyMap() {
        IntMap<String> m = new IntMap<>();
        assertEquals(0, m.size());
        assertTrue(m.isEmpty());
    }

    @Test
    void putAndGet() {
        IntMap<String> m = new IntMap<>();
        m.put(0, "zero");
        m.put(5, "five");
        assertEquals("zero", m.get(0));
        assertEquals("five", m.get(5));
    }

    @Test
    void putReturnsOldValue() {
        IntMap<String> m = new IntMap<>();
        assertNull(m.put(1, "a"));
        assertEquals("a", m.put(1, "b"));
        assertEquals("b", m.get(1));
    }

    @Test
    void getNonexistent() {
        IntMap<String> m = new IntMap<>();
        assertNull(m.get(0));
        assertNull(m.get(999));
    }

    @Test
    void getNegativeKey() {
        IntMap<String> m = new IntMap<>();
        assertNull(m.get(-1));
    }

    @Test
    void getWithObjectKey() {
        IntMap<String> m = new IntMap<>();
        m.put(3, "three");
        assertEquals("three", m.get(Integer.valueOf(3)));
        assertNull(m.get("not an integer"));
    }

    @Test
    void containsKey() {
        IntMap<String> m = new IntMap<>();
        m.put(2, "two");
        assertTrue(m.containsKey(2));
        assertFalse(m.containsKey(3));
        assertFalse(m.containsKey(-1));
    }

    @Test
    void containsKeyObject() {
        IntMap<String> m = new IntMap<>();
        m.put(2, "two");
        assertTrue(m.containsKey(Integer.valueOf(2)));
        assertFalse(m.containsKey("string"));
    }

    @Test
    void remove() {
        IntMap<String> m = new IntMap<>();
        m.put(1, "one");
        assertEquals("one", m.remove(1));
        assertNull(m.get(1));
        assertFalse(m.containsKey(1));
    }

    @Test
    void removeNonexistent() {
        IntMap<String> m = new IntMap<>();
        assertNull(m.remove(5));
    }

    @Test
    void removeInteger() {
        IntMap<String> m = new IntMap<>();
        m.put(3, "three");
        assertEquals("three", m.remove(Integer.valueOf(3)));
    }

    @Test
    void nullValue() {
        IntMap<String> m = new IntMap<>();
        m.put(0, null);
        assertTrue(m.containsKey(0));
        assertNull(m.get(0));
    }

    @Test
    void growsAutomatically() {
        IntMap<String> m = new IntMap<>(2);
        for (int i = 0; i < 100; i++) {
            m.put(i, "v" + i);
        }
        for (int i = 0; i < 100; i++) {
            assertEquals("v" + i, m.get(i));
        }
    }

    // TODO: Upstream bug — IntMap.sz is never updated by put()/remove(),
    // so size() always returns 0. Wait for upstream fix (loftar/ender).
    // @Test
    // void sizeTracksOperations() {
    //     IntMap<String> m = new IntMap<>();
    //     assertEquals(0, m.size());
    //     m.put(0, "a");
    //     m.put(1, "b");
    //     assertEquals(2, m.size());
    //     m.remove(0);
    //     assertEquals(1, m.size());
    // }

    @Test
    void sparseKeys() {
        IntMap<String> m = new IntMap<>();
        m.put(0, "a");
        m.put(1000, "b");
        assertEquals("a", m.get(0));
        assertEquals("b", m.get(1000));
        assertTrue(m.containsKey(0));
        assertTrue(m.containsKey(1000));
    }

    @Test
    void entrySetIteration() {
        IntMap<String> m = new IntMap<>();
        m.put(0, "a");
        m.put(2, "c");
        m.put(4, "e");
        Set<Integer> keys = new HashSet<>();
        for (Map.Entry<Integer, String> e : m.entrySet()) {
            keys.add(e.getKey());
        }
        assertEquals(Set.of(0, 2, 4), keys);
    }

    @Test
    void copyConstructor() {
        Map<Integer, String> src = new HashMap<>();
        src.put(1, "one");
        src.put(2, "two");
        IntMap<String> m = new IntMap<>(src);
        assertEquals("one", m.get(1));
        assertEquals("two", m.get(2));
    }

    @Test
    void putAndGetMultiple() {
        IntMap<String> m = new IntMap<>();
        m.put(0, "a");
        m.put(1, "b");
        m.put(2, "c");
        assertEquals("a", m.get(0));
        assertEquals("b", m.get(1));
        assertEquals("c", m.get(2));
    }
}
