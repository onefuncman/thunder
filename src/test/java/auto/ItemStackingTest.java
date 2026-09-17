package auto;

import org.junit.jupiter.api.Test;

import java.util.Random;

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
    void onlyProbesPlausibleStackItems() {
	assertTrue(ItemStacking.mayStack("Chives", "gfx/invobjs/chives", 1, 1));
	assertFalse(ItemStacking.mayStack("Waterskin", "gfx/invobjs/waterskin", 1, 1));
	assertFalse(ItemStacking.mayStack("Bucket", "gfx/invobjs/bucket-water", 1, 1));
	assertFalse(ItemStacking.mayStack("Large item", "gfx/invobjs/large", 2, 1));
	assertFalse(ItemStacking.mayStack("0.25 l of Water", "gfx/invobjs/water", 1, 1));
    }

    @Test
    void twoSmallestPicksLowestThenNext() {
	assertArrayEquals(new int[] {2, 0}, ItemStacking.twoSmallest(new int[] {5, 9, 1, 8}));
	assertArrayEquals(new int[] {0, 1}, ItemStacking.twoSmallest(new int[] {1, 1, 4}));
	assertNull(ItemStacking.twoSmallest(new int[] {3}));
	assertNull(ItemStacking.twoSmallest(new int[] {}));
    }

    @Test
    void stackedDetectsFullStackNoOp() {
	assertFalse(ItemStacking.stacked(false, 4, 4));
	assertTrue(ItemStacking.stacked(true, 4, 4));
	assertTrue(ItemStacking.stacked(false, 3, 4));
    }

    @Test
    void closestPairKeepsSimilarQualitiesTogether() {
	int[] pair = ItemStacking.closestQualityPair(
	    new double[] {12, 31.2, 25.2, 30},
	    new double[] {12, 31.2, 25.2, 30},
	    new int[] {1, 1, 1, 1});
	assertArrayEquals(new int[] {1, 3}, pair);
    }

    @Test
    void closestPairUsesWholeExistingStackRange() {
	int[] pair = ItemStacking.closestQualityPair(
	    new double[] {10, 19, 20},
	    new double[] {30, 21, 22},
	    new int[] {4, 2, 2});
	assertArrayEquals(new int[] {1, 2}, pair);
    }

    @Test
    void closestPairHoldsTheSmallerPile() {
	int[] pair = ItemStacking.closestQualityPair(
	    new double[] {20, 20},
	    new double[] {20, 20},
	    new int[] {4, 1});
	assertArrayEquals(new int[] {1, 0}, pair);
    }

    @Test
    void failedEqualPilesAreBothKnownFull() {
	assertTrue(ItemStacking.failedSourceIsAlsoFull(4, 4));
	assertFalse(ItemStacking.failedSourceIsAlsoFull(2, 4));
    }

    @Test
    void plansAClosedQualityRedistributionCycle() {
	double[][] qualities = {
	    {1, 2, 3, 5},
	    {6, 7, 8, 9},
	    {4, 10, 11, 12}
	};
	assertEquals(3, ItemStacking.qualityMisplacementCount(qualities));
	assertArrayEquals(new int[][] {
	    {2, 0, 0},
	    {0, 3, 1},
	    {1, 3, 2}
	}, ItemStacking.nextQualityCycle(qualities));
    }

    @Test
    void qualityCycleIgnoresUnknownsAndKeepsEqualQualitiesStill() {
	assertEquals(2, ItemStacking.qualityMisplacementCount(new double[][] {
	    {Double.NaN, 30},
	    {Double.NaN, 20}
	}));
	assertEquals(0, ItemStacking.qualityMisplacementCount(new double[][] {
	    {1, 2},
	    {2, 3}
	}));
	assertNull(ItemStacking.nextQualityCycle(new double[][] {
	    {Double.NaN},
	    {Double.NaN}
	}));
    }

    @Test
    void repeatedQualityCyclesConvergeAndStrictlyReduceMisplacements() {
	double[][] qualities = {
	    {10, 60, 30, 40},
	    {55, 15, 45, 25},
	    {20, 50, 35, 65}
	};
	int cycles = 0;
	int[][] cycle;
	while((cycle = ItemStacking.nextQualityCycle(qualities)) != null) {
	    int before = ItemStacking.qualityMisplacementCount(qualities);
	    double[] moving = new double[cycle.length];
	    for(int i = 0; i < cycle.length; i++)
		moving[i] = qualities[cycle[i][0]][cycle[i][1]];
	    for(int i = 0; i < cycle.length; i++) {
		int next = (i + 1) % cycle.length;
		qualities[cycle[i][2]][cycle[next][1]] = moving[i];
	    }
	    assertTrue(ItemStacking.qualityMisplacementCount(qualities) < before);
	    assertTrue(++cycles < 20, "quality planner did not converge");
	}
	for(int lower = 0; lower < qualities.length; lower++) {
	    for(int higher = lower + 1; higher < qualities.length; higher++) {
		for(double lowBand : qualities[lower])
		    for(double highBand : qualities[higher])
			assertTrue(lowBand <= highBand);
	    }
	}
    }

    @Test
    void qualityCyclesConvergeForVariedSizesAndDuplicateQualities() {
	Random random = new Random(0x5a17cL);
	for(int sample = 0; sample < 200; sample++) {
	    double[][] qualities = new double[2 + random.nextInt(7)][];
	    int itemCount = 0;
	    for(int stack = 0; stack < qualities.length; stack++) {
		qualities[stack] = new double[1 + random.nextInt(6)];
		itemCount += qualities[stack].length;
		for(int item = 0; item < qualities[stack].length; item++)
		    qualities[stack][item] = 1 + random.nextInt(15);
	    }
	    int cycles = 0;
	    int[][] cycle;
	    while((cycle = ItemStacking.nextQualityCycle(qualities)) != null) {
		int before = ItemStacking.qualityMisplacementCount(qualities);
		assertTrue(cycle.length >= 2);
		double[] moving = new double[cycle.length];
		for(int i = 0; i < cycle.length; i++) {
		    assertEquals(cycle[i][2], cycle[(i + 1) % cycle.length][0]);
		    moving[i] = qualities[cycle[i][0]][cycle[i][1]];
		}
		for(int i = 0; i < cycle.length; i++) {
		    int next = (i + 1) % cycle.length;
		    qualities[cycle[i][2]][cycle[next][1]] = moving[i];
		}
		assertTrue(ItemStacking.qualityMisplacementCount(qualities) < before);
		assertTrue(++cycles <= itemCount);
	    }
	    assertEquals(0, ItemStacking.qualityMisplacementCount(qualities));
	}
    }

    @Test
    void latestRetroCupboardFixtureConvergesWithoutMovingAnItemTwice() {
	double[][] qualities = {
	    {18, 18, 15, 10},
	    {17, 17, 17, 16},
	    {17, 17, 17, 16},
	    {17, 17, 17, 17},
	    {21, 20, 21, 16},
	    {21, 21, 21, 16},
	    {18, 18, 19.2, 25.2}
	};
	int initiallyMisplaced = ItemStacking.qualityMisplacementCount(qualities);
	int moved = 0;
	int[][] cycle;
	while((cycle = ItemStacking.nextQualityCycle(qualities)) != null) {
	    double[] moving = new double[cycle.length];
	    for(int i = 0; i < cycle.length; i++)
		moving[i] = qualities[cycle[i][0]][cycle[i][1]];
	    for(int i = 0; i < cycle.length; i++) {
		int next = (i + 1) % cycle.length;
		qualities[cycle[i][2]][cycle[next][1]] = moving[i];
	    }
	    moved += cycle.length;
	}
	assertEquals(0, ItemStacking.qualityMisplacementCount(qualities));
	assertEquals(initiallyMisplaced, moved);
    }
}
