package auto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ItemStackingTest {
    @Test
    void groupsLooseAndStackedByStrippedName() {
	assertEquals("Fox Meat", ItemStacking.stackKey("Fox Meat"));
	assertEquals("Fox Meat", ItemStacking.stackKey("Fox Meat, stack of"));
	assertEquals("Board", ItemStacking.stackKey("Board, stack of"));
    }

    @Test
    void skipsRingsQuantityAndUnloaded() {
	assertNull(ItemStacking.stackKey("Gold Ring"));
	assertNull(ItemStacking.stackKey("Silver Ring, stack of"));
	assertNull(ItemStacking.stackKey("0.50 kg of Flour"));
	assertNull(ItemStacking.stackKey("0.25 l of Water"));
	assertNull(ItemStacking.stackKey("???"));
	assertNull(ItemStacking.stackKey(""));
	assertNull(ItemStacking.stackKey(null));
    }

    @Test
    void detectsStackNames() {
	assertTrue(ItemStacking.isStackName("Board, stack of"));
	assertFalse(ItemStacking.isStackName("Board"));
	assertFalse(ItemStacking.isStackName(null));
    }

    @Test
    void twoSmallestPicksLowestThenNext() {
	assertArrayEquals(new int[] {2, 0}, ItemStacking.twoSmallest(new int[] {5, 9, 1, 8}));
	assertArrayEquals(new int[] {0, 1}, ItemStacking.twoSmallest(new int[] {1, 1, 4}));
	assertNull(ItemStacking.twoSmallest(new int[] {3}));
	assertNull(ItemStacking.twoSmallest(new int[] {}));
    }
}
