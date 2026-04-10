package haven;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class EnumIntervalTest {

    private enum Color { RED, GREEN, BLUE, YELLOW, PURPLE }

    @Test
    void sizeFullRange() {
        EnumInterval<Color> ei = new EnumInterval<>(Color.RED, Color.PURPLE);
        assertEquals(5, ei.size());
    }

    @Test
    void sizeSingleElement() {
        EnumInterval<Color> ei = new EnumInterval<>(Color.GREEN, Color.GREEN);
        assertEquals(1, ei.size());
    }

    @Test
    void sizeSubRange() {
        EnumInterval<Color> ei = new EnumInterval<>(Color.GREEN, Color.YELLOW);
        assertEquals(3, ei.size());
    }

    @Test
    void get() {
        EnumInterval<Color> ei = new EnumInterval<>(Color.GREEN, Color.YELLOW);
        assertEquals(Color.GREEN, ei.get(0));
        assertEquals(Color.BLUE, ei.get(1));
        assertEquals(Color.YELLOW, ei.get(2));
    }

    @Test
    void getOutOfBounds() {
        EnumInterval<Color> ei = new EnumInterval<>(Color.GREEN, Color.BLUE);
        assertThrows(NoSuchElementException.class, () -> ei.get(2));
        assertThrows(NoSuchElementException.class, () -> ei.get(-1));
    }

    @Test
    void contains() {
        EnumInterval<Color> ei = new EnumInterval<>(Color.GREEN, Color.YELLOW);
        assertFalse(ei.contains(Color.RED));
        assertTrue(ei.contains(Color.GREEN));
        assertTrue(ei.contains(Color.BLUE));
        assertTrue(ei.contains(Color.YELLOW));
        assertFalse(ei.contains(Color.PURPLE));
    }

    @Test
    void containsNull() {
        EnumInterval<Color> ei = new EnumInterval<>(Color.RED, Color.BLUE);
        assertFalse(ei.contains(null));
    }

    @Test
    void containsWrongType() {
        EnumInterval<Color> ei = new EnumInterval<>(Color.RED, Color.BLUE);
        assertFalse(ei.contains("RED"));
    }

    @Test
    void indexOf() {
        EnumInterval<Color> ei = new EnumInterval<>(Color.GREEN, Color.YELLOW);
        assertEquals(-1, ei.indexOf(Color.RED));
        assertEquals(0, ei.indexOf(Color.GREEN));
        assertEquals(1, ei.indexOf(Color.BLUE));
        assertEquals(2, ei.indexOf(Color.YELLOW));
        assertEquals(-1, ei.indexOf(Color.PURPLE));
    }

    @Test
    void indexOfNull() {
        EnumInterval<Color> ei = new EnumInterval<>(Color.RED, Color.BLUE);
        assertEquals(-1, ei.indexOf(null));
    }

    @Test
    void invertedRangeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new EnumInterval<>(Color.BLUE, Color.RED));
    }

    @Test
    void iterableAsListView() {
        EnumInterval<Color> ei = new EnumInterval<>(Color.RED, Color.BLUE);
        List<Color> list = new ArrayList<>();
        for (Color c : ei) {
            list.add(c);
        }
        assertEquals(Arrays.asList(Color.RED, Color.GREEN, Color.BLUE), list);
    }
}
