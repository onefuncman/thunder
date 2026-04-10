package haven;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class HashedSetTest {

    private HashedSet<String> eqSet() {
        return new HashedSet<>(Hash.eq);
    }

    @Test
    void emptySet() {
        HashedSet<String> s = eqSet();
        assertEquals(0, s.size());
        assertTrue(s.isEmpty());
    }

    @Test
    void addAndContains() {
        HashedSet<String> s = eqSet();
        assertTrue(s.add("hello"));
        assertTrue(s.contains("hello"));
        assertEquals(1, s.size());
    }

    @Test
    void addDuplicate() {
        HashedSet<String> s = eqSet();
        assertTrue(s.add("a"));
        assertFalse(s.add("a"));
        assertEquals(1, s.size());
    }

    @Test
    void addNull() {
        HashedSet<String> s = eqSet();
        assertThrows(NullPointerException.class, () -> s.add(null));
    }

    @Test
    void remove() {
        HashedSet<String> s = eqSet();
        s.add("a");
        s.add("b");
        assertTrue(s.remove("a"));
        assertFalse(s.contains("a"));
        assertTrue(s.contains("b"));
        assertEquals(1, s.size());
    }

    @Test
    void removeNonexistent() {
        HashedSet<String> s = eqSet();
        assertFalse(s.remove("a"));
    }

    @Test
    void removeNull() {
        HashedSet<String> s = eqSet();
        assertFalse(s.remove(null));
    }

    @Test
    void clear() {
        HashedSet<String> s = eqSet();
        s.add("a");
        s.add("b");
        s.clear();
        assertEquals(0, s.size());
        assertTrue(s.isEmpty());
    }

    @Test
    void iteratorCoversAll() {
        HashedSet<String> s = eqSet();
        s.add("a");
        s.add("b");
        s.add("c");
        Set<String> collected = new HashSet<>();
        for (String e : s) {
            collected.add(e);
        }
        assertEquals(Set.of("a", "b", "c"), collected);
    }

    @Test
    void find() {
        HashedSet<String> s = eqSet();
        s.add("hello");
        assertEquals("hello", s.find("hello"));
        assertNull(s.find("world"));
    }

    @Test
    void intern() {
        HashedSet<String> s = eqSet();
        String first = s.intern("hello");
        assertEquals("hello", first);
        assertTrue(s.contains("hello"));
        // Second intern returns existing
        String second = s.intern("hello");
        assertSame(first, second);
    }

    @Test
    void growsWithManyElements() {
        HashedSet<Integer> s = new HashedSet<>(Hash.eq);
        for (int i = 0; i < 1000; i++) {
            s.add(i);
        }
        assertEquals(1000, s.size());
        for (int i = 0; i < 1000; i++) {
            assertTrue(s.contains(i));
        }
    }

    @Test
    void constructFromCollection() {
        List<String> src = Arrays.asList("a", "b", "c");
        HashedSet<String> s = new HashedSet<>(Hash.eq, src);
        assertEquals(3, s.size());
        assertTrue(s.contains("a"));
        assertTrue(s.contains("b"));
        assertTrue(s.contains("c"));
    }

    @Test
    void identityHash() {
        HashedSet<String> s = new HashedSet<>(Hash.id);
        String a = new String("hello");
        String b = new String("hello");
        s.add(a);
        s.add(b);
        // Identity hash treats these as different objects
        assertEquals(2, s.size());
    }
}
