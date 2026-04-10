package haven;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class TopoSortTest {

    private TopoSort<String> eqSort() {
        return new TopoSort<>(Hash.eq);
    }

    @Test
    void emptySort() {
        List<String> result = eqSort().sort();
        assertTrue(result.isEmpty());
    }

    @Test
    void singleEdge() {
        List<String> result = eqSort()
            .add("A", "B")
            .sort();
        assertEquals(2, result.size());
        assertTrue(result.indexOf("A") < result.indexOf("B"));
    }

    @Test
    void linearChain() {
        List<String> result = eqSort()
            .add("A", "B")
            .add("B", "C")
            .add("C", "D")
            .sort();
        assertEquals(4, result.size());
        assertEquals("A", result.get(0));
        assertEquals("B", result.get(1));
        assertEquals("C", result.get(2));
        assertEquals("D", result.get(3));
    }

    @Test
    void diamond() {
        List<String> result = eqSort()
            .add("A", "B")
            .add("A", "C")
            .add("B", "D")
            .add("C", "D")
            .sort();
        assertEquals(4, result.size());
        assertTrue(result.indexOf("A") < result.indexOf("B"));
        assertTrue(result.indexOf("A") < result.indexOf("C"));
        assertTrue(result.indexOf("B") < result.indexOf("D"));
        assertTrue(result.indexOf("C") < result.indexOf("D"));
    }

    @Test
    void cycleThrows() {
        TopoSort<String> sorter = eqSort()
            .add("A", "B")
            .add("B", "C")
            .add("C", "A");
        assertThrows(TopoSort.InconsistentOrder.class, sorter::sort);
    }

    @Test
    void selfCycleThrows() {
        TopoSort<String> sorter = eqSort()
            .add("A", "A");
        assertThrows(TopoSort.InconsistentOrder.class, sorter::sort);
    }

    @Test
    void addIterable() {
        List<String> order = Arrays.asList("X", "Y", "Z");
        List<String> result = eqSort()
            .add(order)
            .sort();
        assertEquals(3, result.size());
        assertEquals("X", result.get(0));
        assertEquals("Y", result.get(1));
        assertEquals("Z", result.get(2));
    }

    @Test
    void addIterableSingleElement() {
        List<String> order = Collections.singletonList("X");
        List<String> result = eqSort()
            .add(order)
            .sort();
        assertEquals(1, result.size());
        assertEquals("X", result.get(0));
    }

    @Test
    void addIterableEmpty() {
        List<String> result = eqSort()
            .add(Collections.<String>emptyList())
            .sort();
        assertTrue(result.isEmpty());
    }

    @Test
    void disconnectedComponents() {
        List<String> result = eqSort()
            .add("A", "B")
            .add("C", "D")
            .sort();
        assertEquals(4, result.size());
        assertTrue(result.indexOf("A") < result.indexOf("B"));
        assertTrue(result.indexOf("C") < result.indexOf("D"));
    }

    @Test
    void multipleEdgesSameNodes() {
        // Adding same edge twice should still work
        List<String> result = eqSort()
            .add("A", "B")
            .add("A", "B")
            .sort();
        assertEquals(2, result.size());
        assertTrue(result.indexOf("A") < result.indexOf("B"));
    }

    // --- Graph tests ---

    @Test
    void graphAddAndQuery() {
        TopoSort.Graph<String> g = new TopoSort.Graph<>(Hash.eq);
        g.add("A", "B");
        g.add("A", "C");
        Collection<String> fromA = g.from("A");
        assertEquals(2, fromA.size());
        assertTrue(fromA.contains("B"));
        assertTrue(fromA.contains("C"));
    }

    @Test
    void graphReverseEdges() {
        TopoSort.Graph<String> g = new TopoSort.Graph<>(Hash.eq);
        g.add("A", "B");
        g.add("C", "B");
        Collection<String> toB = g.to("B");
        assertEquals(2, toB.size());
        assertTrue(toB.contains("A"));
        assertTrue(toB.contains("C"));
    }

    @Test
    void graphFromEmpty() {
        TopoSort.Graph<String> g = new TopoSort.Graph<>(Hash.eq);
        Collection<String> result = g.from("X");
        assertTrue(result.isEmpty());
    }

    @Test
    void graphToEmpty() {
        TopoSort.Graph<String> g = new TopoSort.Graph<>(Hash.eq);
        Collection<String> result = g.to("X");
        assertTrue(result.isEmpty());
    }

    @Test
    void graphRemoveEdge() {
        TopoSort.Graph<String> g = new TopoSort.Graph<>(Hash.eq);
        g.add("A", "B");
        g.add("A", "C");
        g.remove("A", "B");
        Collection<String> fromA = g.from("A");
        assertEquals(1, fromA.size());
        assertTrue(fromA.contains("C"));
    }

    @Test
    void graphRemoveNonexistentThrows() {
        TopoSort.Graph<String> g = new TopoSort.Graph<>(Hash.eq);
        assertThrows(NoSuchElementException.class, () -> g.remove("A", "B"));
    }

    @Test
    void graphRemovefrom() {
        TopoSort.Graph<String> g = new TopoSort.Graph<>(Hash.eq);
        g.add("A", "B");
        g.add("A", "C");
        assertTrue(g.removefrom("A"));
        assertTrue(g.from("A").isEmpty());
    }

    @Test
    void graphRemovefromNoEdges() {
        TopoSort.Graph<String> g = new TopoSort.Graph<>(Hash.eq);
        assertFalse(g.removefrom("A"));
    }

    @Test
    void graphRemoveto() {
        TopoSort.Graph<String> g = new TopoSort.Graph<>(Hash.eq);
        g.add("A", "C");
        g.add("B", "C");
        assertTrue(g.removeto("C"));
        assertTrue(g.to("C").isEmpty());
    }

    @Test
    void graphRemovetoNoEdges() {
        TopoSort.Graph<String> g = new TopoSort.Graph<>(Hash.eq);
        assertFalse(g.removeto("A"));
    }

    @Test
    void graphCopyConstructor() {
        TopoSort.Graph<String> orig = new TopoSort.Graph<>(Hash.eq);
        orig.add("A", "B");
        orig.add("B", "C");
        TopoSort.Graph<String> copy = new TopoSort.Graph<>(Hash.eq, orig);
        // Copy has same edges
        assertTrue(copy.from("A").contains("B"));
        assertTrue(copy.from("B").contains("C"));
        // Modifying copy doesn't affect original
        copy.remove("A", "B");
        assertTrue(orig.from("A").contains("B"));
    }
}
