package haven;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class FastArrayListTest {

    @Test
    void emptyList() {
        FastArrayList<String> l = new FastArrayList<>();
        assertEquals(0, l.size());
        assertTrue(l.isEmpty());
    }

    @Test
    void addAndGet() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add("a");
        l.add("b");
        l.add("c");
        assertEquals(3, l.size());
        assertEquals("a", l.get(0));
        assertEquals("b", l.get(1));
        assertEquals("c", l.get(2));
    }

    @Test
    void getOutOfBounds() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add("a");
        assertThrows(IndexOutOfBoundsException.class, () -> l.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> l.get(5));
    }

    @Test
    void set() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add("a");
        l.add("b");
        String old = l.set(1, "B");
        assertEquals("b", old);
        assertEquals("B", l.get(1));
    }

    @Test
    void setOutOfBounds() {
        FastArrayList<String> l = new FastArrayList<>();
        assertThrows(IndexOutOfBoundsException.class, () -> l.set(0, "x"));
    }

    @Test
    void removeSwapsLastElement() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add("a");
        l.add("b");
        l.add("c");
        // Removing index 0 should swap last element into position 0
        String removed = l.remove(0);
        assertEquals("a", removed);
        assertEquals(2, l.size());
        // "c" (was last) should now be at index 0
        assertEquals("c", l.get(0));
        assertEquals("b", l.get(1));
    }

    @Test
    void removeLastElement() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add("a");
        l.add("b");
        String removed = l.remove(1);
        assertEquals("b", removed);
        assertEquals(1, l.size());
        assertEquals("a", l.get(0));
    }

    @Test
    void removeOutOfBounds() {
        FastArrayList<String> l = new FastArrayList<>();
        assertThrows(IndexOutOfBoundsException.class, () -> l.remove(0));
    }

    @Test
    void addAtIndex() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add("a");
        l.add("b");
        l.add("c");
        // add(1, "X") puts "X" at index 1, moves old index-1 element to end
        l.add(1, "X");
        assertEquals(4, l.size());
        assertEquals("a", l.get(0));
        assertEquals("X", l.get(1));
        // "b" (was at index 1) should now be at end
        assertEquals("b", l.get(3));
    }

    @Test
    void addAtEnd() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add("a");
        l.add(1, "b");
        assertEquals(2, l.size());
        assertEquals("b", l.get(1));
    }

    @Test
    void addAtIndexOutOfBounds() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add("a");
        assertThrows(IndexOutOfBoundsException.class, () -> l.add(5, "x"));
    }

    @Test
    void clear() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add("a");
        l.add("b");
        l.clear();
        assertEquals(0, l.size());
        assertTrue(l.isEmpty());
    }

    @Test
    void iteratorBasic() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add("a");
        l.add("b");
        l.add("c");
        List<String> collected = new ArrayList<>();
        for (String s : l) {
            collected.add(s);
        }
        assertEquals(Arrays.asList("a", "b", "c"), collected);
    }

    @Test
    void listIteratorPrevious() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add("a");
        l.add("b");
        ListIterator<String> it = l.listIterator(2);
        assertTrue(it.hasPrevious());
        assertEquals("b", it.previous());
        assertEquals("a", it.previous());
        assertFalse(it.hasPrevious());
    }

    @Test
    void preAllocated() {
        FastArrayList<String> l = new FastArrayList<>(100);
        assertEquals(0, l.size());
        l.add("a");
        assertEquals(1, l.size());
    }

    @Test
    void growsBeyondInitialCapacity() {
        FastArrayList<Integer> l = new FastArrayList<>(2);
        for (int i = 0; i < 100; i++) {
            l.add(i);
        }
        assertEquals(100, l.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(i, l.get(i));
        }
    }

    @Test
    void nullElements() {
        FastArrayList<String> l = new FastArrayList<>();
        l.add(null);
        l.add("a");
        assertEquals(2, l.size());
        assertNull(l.get(0));
        assertEquals("a", l.get(1));
    }
}
