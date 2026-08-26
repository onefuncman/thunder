package haven;

import org.junit.jupiter.api.Test;

import static haven.GobWarning.UpdateAction.*;
import static haven.GobWarning.WarnTarget.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GobWarningTest {

    /* refreshApplies: a refresh request for one target must never touch a
     * warning for another target. */

    @Test
    void animalRefreshDoesNotTouchPlayerWarning() {
	// The original regression: the animal toggle rebuilt player warnings.
	assertFalse(GobWarning.refreshApplies(player, animal));
    }

    @Test
    void animalRefreshDoesNotTouchGemOrMidgeWarnings() {
	assertFalse(GobWarning.refreshApplies(gem, animal));
	assertFalse(GobWarning.refreshApplies(midges, animal));
    }

    @Test
    void refreshAppliesToMatchingTarget() {
	assertTrue(GobWarning.refreshApplies(animal, animal));
	assertTrue(GobWarning.refreshApplies(player, player));
    }

    @Test
    void refreshSkipsGobsWithoutWarning() {
	assertFalse(GobWarning.refreshApplies(null, animal));
	assertFalse(GobWarning.refreshApplies(null, player));
    }

    @Test
    void playerRefreshDoesNotTouchAnimalWarning() {
	assertFalse(GobWarning.refreshApplies(animal, player));
    }

    /* updateAction: the updateWarnings state machine. */

    @Test
    void uncategorizedGobDropsItsWarning() {
	// e.g. a foe that died or was KO'd
	assertEquals(DROP, GobWarning.updateAction(null, player));
	assertEquals(DROP, GobWarning.updateAction(null, animal));
    }

    @Test
    void uncategorizedGobWithoutWarningStaysBare() {
	// DROP on a gob with no attrib is a no-op delattr
	assertEquals(DROP, GobWarning.updateAction(null, null));
    }

    @Test
    void newlyCategorizedGobGetsWarning() {
	// A player whose equipment finished loading gets detected.
	assertEquals(CREATE, GobWarning.updateAction(player, null));
	assertEquals(CREATE, GobWarning.updateAction(animal, null));
    }

    @Test
    void huskWarningIsRebuiltNotKept() {
	// A targetless husk (built while categorize() returned null) reports
	// a null current target and must be rebuilt, or the gob would never
	// warn again.
	assertEquals(CREATE, GobWarning.updateAction(player, null));
    }

    @Test
    void reclassifiedGobIsRebuilt() {
	// Target changed: the stale radius/colors must not be kept.
	assertEquals(CREATE, GobWarning.updateAction(player, animal));
	assertEquals(CREATE, GobWarning.updateAction(animal, player));
    }

    @Test
    void stableGobKeepsItsWarning() {
	assertEquals(KEEP, GobWarning.updateAction(player, player));
	assertEquals(KEEP, GobWarning.updateAction(animal, animal));
	assertEquals(KEEP, GobWarning.updateAction(gem, gem));
    }

    /* toggledState: both on, both off semantics of the one-key toggle. */

    @Test
    void toggleTurnsOffWhenAnythingIsOn() {
	assertFalse(GobWarning.toggledState(true, true));
	assertFalse(GobWarning.toggledState(true, false));
	assertFalse(GobWarning.toggledState(false, true));
    }

    @Test
    void toggleTurnsOnWhenFullyOff() {
	assertTrue(GobWarning.toggledState(false, false));
    }

    @Test
    void matchesExactMannequinStand() {
	assertTrue(GobWarning.isMannequinStandRes("gfx/terobjs/mannequin-stand"));
    }

    @Test
    void rejectsPlainMannequinMod() {
	// gfx/terobjs/mannequin is a composite mod seen on statues, not the
	// equipment-side stand that marks a wearable dummy.
	assertFalse(GobWarning.isMannequinStandRes("gfx/terobjs/mannequin"));
    }

    @Test
    void rejectsPlayerGear() {
	assertFalse(GobWarning.isMannequinStandRes("gfx/borka/caveanglercape-head"));
	assertFalse(GobWarning.isMannequinStandRes("gfx/borka/boots"));
    }

    @Test
    void rejectsNull() {
	assertFalse(GobWarning.isMannequinStandRes(null));
    }

    @Test
    void rejectsEmpty() {
	assertFalse(GobWarning.isMannequinStandRes(""));
    }

    @Test
    void matchesPathWithSuffix() {
	assertTrue(GobWarning.isMannequinStandRes("gfx/terobjs/mannequin-stand-variant"));
    }

    @Test
    void hasMannequinStandNullList() {
	assertFalse(GobWarning.hasMannequinStand(null));
    }

    @Test
    void hasMannequinStandEmptyList() {
	assertFalse(GobWarning.hasMannequinStand(java.util.Collections.emptyList()));
    }
}
