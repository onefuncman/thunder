package haven;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class WeightListTest {

    @Test
    void emptySize() {
        WeightList<String> wl = new WeightList<>();
        assertEquals(0, wl.size());
    }

    @Test
    void addIncrementsSize() {
        WeightList<String> wl = new WeightList<>();
        wl.add("A", 10);
        assertEquals(1, wl.size());
        wl.add("B", 20);
        assertEquals(2, wl.size());
    }

    @Test
    void pickSingleItem() {
        WeightList<String> wl = new WeightList<>();
        wl.add("only", 5);
        assertEquals("only", wl.pick(0));
        assertEquals("only", wl.pick(3));
    }

    @Test
    void pickFirstItem() {
        WeightList<String> wl = new WeightList<>();
        wl.add("A", 10);
        wl.add("B", 20);
        // p=0 through p=9 should pick A (weight 10)
        assertEquals("A", wl.pick(0));
        assertEquals("A", wl.pick(9));
    }

    @Test
    void pickSecondItem() {
        WeightList<String> wl = new WeightList<>();
        wl.add("A", 10);
        wl.add("B", 20);
        // p=10 through p=29 should pick B (weight 20)
        assertEquals("B", wl.pick(10));
        assertEquals("B", wl.pick(29));
    }

    @Test
    void pickWrapsAround() {
        WeightList<String> wl = new WeightList<>();
        wl.add("A", 5);
        wl.add("B", 5);
        // total weight = 10, p=10 wraps to 0 -> A
        assertEquals("A", wl.pick(10));
        assertEquals("B", wl.pick(15));
    }

    @Test
    void pickWithRandom() {
        WeightList<String> wl = new WeightList<>();
        wl.add("A", 1);
        wl.add("B", 1);
        Random r = new Random(42);
        String result = wl.pick(r);
        assertTrue("A".equals(result) || "B".equals(result));
    }

    @Test
    void weightDistribution() {
        WeightList<String> wl = new WeightList<>();
        wl.add("rare", 1);
        wl.add("common", 99);
        // Sample many times and check distribution
        Random r = new Random(12345);
        int rareCount = 0;
        int total = 10000;
        for (int i = 0; i < total; i++) {
            if ("rare".equals(wl.pick(r))) rareCount++;
        }
        // Expect ~1% rare picks, allow tolerance
        assertTrue(rareCount < 300, "rare should be ~1%, got " + rareCount);
        assertTrue(rareCount > 10, "rare should appear at least sometimes, got " + rareCount);
    }

    @Test
    void totalWeightAccumulates() {
        WeightList<String> wl = new WeightList<>();
        wl.add("A", 3);
        wl.add("B", 7);
        assertEquals(10, wl.tw);
    }

    @Test
    void threeItems() {
        WeightList<String> wl = new WeightList<>();
        wl.add("A", 2);
        wl.add("B", 3);
        wl.add("C", 5);
        // Total = 10
        assertEquals("A", wl.pick(0));
        assertEquals("A", wl.pick(1));
        assertEquals("B", wl.pick(2));
        assertEquals("B", wl.pick(4));
        assertEquals("C", wl.pick(5));
        assertEquals("C", wl.pick(9));
    }
}
