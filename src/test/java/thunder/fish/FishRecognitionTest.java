package thunder.fish;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Headless classification tests for the Fish Spit-Roast Bot's pure rules:
 * raw-fish resource names, cooked display names, spit-state interpretation,
 * and output-container priority ordering.
 */
public class FishRecognitionTest {

    @Test
    void rawFishResnameMatchesKnownPatterns() {
        Assertions.assertTrue(FishRecognition.isRawFishResname("gfx/invobjs/fish-perch"));
        Assertions.assertTrue(FishRecognition.isRawFishResname("gfx/invobjs/fish"));
        Assertions.assertTrue(FishRecognition.isRawFishResname("gfx/invobjs/small/fish-roach"));
    }

    @Test
    void rawFishResnameExcludesDerivedProducts() {
        Assertions.assertFalse(FishRecognition.isRawFishResname("gfx/invobjs/fish-filet-perch"));
        Assertions.assertFalse(FishRecognition.isRawFishResname("gfx/invobjs/fishfilet"));
        Assertions.assertFalse(FishRecognition.isRawFishResname("gfx/invobjs/meat"));
        Assertions.assertFalse(FishRecognition.isRawFishResname("gfx/invobjs/fish-cooked"));
        Assertions.assertFalse(FishRecognition.isRawFishResname("gfx/invobjs/fish-roast"));
        Assertions.assertFalse(FishRecognition.isRawFishResname("gfx/invobjs/fish-roast-perch"));
    }

    @Test
    void rawFishResnameHandlesNullAndEmpty() {
        Assertions.assertFalse(FishRecognition.isRawFishResname(null));
        Assertions.assertFalse(FishRecognition.isRawFishResname(""));
    }

    @Test
    void cookedDisplayNameMatchesCaseInsensitively() {
        Assertions.assertTrue(FishRecognition.isCookedDisplayName("Spitroast Fish"));
        Assertions.assertTrue(FishRecognition.isCookedDisplayName("spit roast perch"));
        Assertions.assertTrue(FishRecognition.isCookedDisplayName("Spit Roast Fish"));
    }

    @Test
    void cookedDisplayNameStripsStackSuffix() {
        Assertions.assertTrue(FishRecognition.isCookedDisplayName("Spitroast Fish, stack of"));
        Assertions.assertFalse(FishRecognition.isCookedDisplayName("Roasted fish"));
        Assertions.assertFalse(FishRecognition.isCookedDisplayName("Raw fish"));
        Assertions.assertFalse(FishRecognition.isCookedDisplayName(null));
    }

    @Test
    void stripStackSuffixIsIdempotent() {
        Assertions.assertEquals("Spitroast Fish", FishRecognition.stripStackSuffix("Spitroast Fish, stack of"));
        Assertions.assertEquals("Spitroast Fish", FishRecognition.stripStackSuffix("Spitroast Fish"));
        Assertions.assertNull(FishRecognition.stripStackSuffix(null));
    }

    @Test
    void spitStateClassifiesContentStrings() {
        Assertions.assertEquals(FishRecognition.SpitState.EMPTY, FishRecognition.spitState(null));
        Assertions.assertEquals(FishRecognition.SpitState.RAW,
            FishRecognition.spitState("gfx/terobjs/items/cadaverfish-raw"));
        Assertions.assertEquals(FishRecognition.SpitState.COOKED,
            FishRecognition.spitState("gfx/terobjs/items/cadaverfish-roast"));
        Assertions.assertEquals(FishRecognition.SpitState.COOKED,
            FishRecognition.spitState("gfx/terobjs/items/cadaverfish"));
    }

    @Test
    void outputPriorityOrdersLargeChestFirst() {
        Assertions.assertEquals(FishRecognition.PRIORITY_LARGE_CHEST,
            FishRecognition.outputPriority("gfx/terobjs/largechest"));
        Assertions.assertTrue(FishRecognition.outputPriority("gfx/terobjs/largechest")
            < FishRecognition.outputPriority("gfx/terobjs/metalcabinet"));
    }

    @Test
    void outputPriorityOrdersContainersBeforeTables() {
        int container = FishRecognition.outputPriority("gfx/terobjs/cupboard");
        int table = FishRecognition.outputPriority("gfx/terobjs/furn/table-rustic");
        Assertions.assertEquals(FishRecognition.PRIORITY_CONTAINER, container);
        Assertions.assertEquals(FishRecognition.PRIORITY_TABLE, table);
        Assertions.assertTrue(container < table);
    }

    @Test
    void outputPriorityRecognisesKnownResourcesAndRejectsUnknown() {
        Assertions.assertEquals(FishRecognition.PRIORITY_CONTAINER, FishRecognition.outputPriority("gfx/terobjs/chest"));
        Assertions.assertEquals(FishRecognition.PRIORITY_CONTAINER, FishRecognition.outputPriority("gfx/terobjs/crate"));
        Assertions.assertEquals(FishRecognition.PRIORITY_CONTAINER, FishRecognition.outputPriority("gfx/terobjs/coffer"));
        Assertions.assertEquals(FishRecognition.PRIORITY_TABLE, FishRecognition.outputPriority("gfx/terobjs/furn/table-elegant"));
        Assertions.assertEquals(FishRecognition.PRIORITY_TABLE, FishRecognition.outputPriority("gfx/terobjs/htable"));
        Assertions.assertEquals(FishRecognition.NOT_OUTPUT, FishRecognition.outputPriority("gfx/terobjs/fireplace"));
        Assertions.assertEquals(FishRecognition.NOT_OUTPUT, FishRecognition.outputPriority(null));
    }
}
