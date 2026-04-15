package haven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GobWarningTest {

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
