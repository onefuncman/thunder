package thunder;

import haven.CheckBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Roster-side semantics of a milk resolve (MilkingAssist.applyResolve),
 * against the real mark widget. (Entry itself is not headless-constructible
 * -- its statics load remote-pool roster icons -- so the seam takes the
 * CheckBox, the only part of the entry the resolve touches. Needs the res
 * jars on the test classpath for the checkbox art.)
 *
 * Pinned behavior (2026-09-02):
 * - container at capacity: the take was capped, the animal likely has
 *   milk left -- it stays fully selected;
 * - otherwise only the mark clears (glow + name-side checkmark); the
 *   floating name must never be hidden by the milk path, so this seam
 *   deliberately has no access to RosterWindow.memorized.
 */
public class MilkingAssistResolveTest {
    private static CheckBox mark(boolean marked) {
	CheckBox cb = new CheckBox("");
	cb.set(marked);
	return(cb);
    }

    @Test
    void containerFullLeavesAnimalSelected() {
	CheckBox m = mark(true);
	assertEquals("resolved_container_full", MilkingAssist.applyResolve(m, true));
	assertTrue(m.a, "a capped take means milk left -- the animal must stay selected");
    }

    @Test
    void normalResolveClearsTheMark() {
	CheckBox m = mark(true);
	assertEquals("resolved", MilkingAssist.applyResolve(m, false));
	assertFalse(m.a, "the mark (glow + checkmark) clears on a full milking");
    }

    @Test
    void resolveOnUnmarkedEntryIsANoOp() {
	CheckBox m = mark(false);
	assertEquals("resolved", MilkingAssist.applyResolve(m, false));
	assertFalse(m.a);
    }
}
