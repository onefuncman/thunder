package haven;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class HashedMapTest {

    private HashedMap<String, Integer> eqMap() {
        return new HashedMap<>(Hash.eq);
    }

    @Test
    void emptyMap() {
        HashedMap<String, Integer> m = eqMap();
        assertEquals(0, m.size());
        assertTrue(m.isEmpty());
    }

    @Test
    void putAndGet() {
        HashedMap<String, Integer> m = eqMap();
        m.put("a", 1);
        m.put("b", 2);
        assertEquals(1, m.get("a"));
        assertEquals(2, m.get("b"));
        assertEquals(2, m.size());
    }

    @Test
    void putReturnsOldValue() {
        HashedMap<String, Integer> m = eqMap();
        assertNull(m.put("a", 1));
        assertEquals(1, m.put("a", 2));
        assertEquals(2, m.get("a"));
    }

    @Test
    void getNonexistent() {
        HashedMap<String, Integer> m = eqMap();
        assertNull(m.get("nope"));
    }

    @Test
    void containsKey() {
        HashedMap<String, Integer> m = eqMap();
        m.put("x", 10);
        assertTrue(m.containsKey("x"));
        assertFalse(m.containsKey("y"));
    }

    @Test
    void remove() {
        HashedMap<String, Integer> m = eqMap();
        m.put("a", 1);
        assertEquals(1, m.remove("a"));
        assertNull(m.get("a"));
        assertFalse(m.containsKey("a"));
        assertEquals(0, m.size());
    }

    @Test
    void removeNonexistent() {
        HashedMap<String, Integer> m = eqMap();
        assertNull(m.remove("nope"));
    }

    @Test
    void clear() {
        HashedMap<String, Integer> m = eqMap();
        m.put("a", 1);
        m.put("b", 2);
        m.clear();
        assertEquals(0, m.size());
        assertTrue(m.isEmpty());
        assertNull(m.get("a"));
    }

    @Test
    void entrySetIteration() {
        HashedMap<String, Integer> m = eqMap();
        m.put("a", 1);
        m.put("b", 2);
        m.put("c", 3);
        Map<String, Integer> collected = new HashMap<>();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            collected.put(e.getKey(), e.getValue());
        }
        assertEquals(Map.of("a", 1, "b", 2, "c", 3), collected);
    }

    @Test
    void growsWithManyElements() {
        HashedMap<Integer, Integer> m = new HashedMap<>(Hash.eq);
        for (int i = 0; i < 500; i++) {
            m.put(i, i * 10);
        }
        assertEquals(500, m.size());
        for (int i = 0; i < 500; i++) {
            assertEquals(i * 10, m.get(i));
        }
    }

    @Test
    void copyConstructor() {
        Map<String, Integer> src = new HashMap<>();
        src.put("a", 1);
        src.put("b", 2);
        HashedMap<String, Integer> m = new HashedMap<>(Hash.eq, src);
        assertEquals(1, m.get("a"));
        assertEquals(2, m.get("b"));
    }

    @Test
    void identityHash() {
        HashedMap<String, Integer> m = new HashedMap<>(Hash.id);
        String a = new String("key");
        String b = new String("key");
        m.put(a, 1);
        m.put(b, 2);
        assertEquals(2, m.size());
        assertEquals(1, m.get(a));
        assertEquals(2, m.get(b));
    }

    @Test
    void entrySetSize() {
        HashedMap<String, Integer> m = eqMap();
        m.put("x", 1);
        m.put("y", 2);
        assertEquals(2, m.entrySet().size());
    }
}
