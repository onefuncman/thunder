package thunder.cellar;

import haven.Coord2d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CellarDiggerRulesTest {
    @Test public void recognizesChippedStoneOreAndStackNames() {
        assertTrue(CellarDiggerMaterials.isRockMaterialName("Granite"));
        assertTrue(CellarDiggerMaterials.isRockMaterialName("Cinnabar"));
        assertTrue(CellarDiggerMaterials.isRockMaterialName("Rhyolite, stack of"));
        assertFalse(CellarDiggerMaterials.isRockMaterialName("Bar of Iron"));
        assertFalse(CellarDiggerMaterials.isRockMaterialName(null));
    }

    @Test public void recognizesOnlyTheExactCellarDoor() {
        assertTrue(CellarDiggerRules.isCellarDoor("gfx/terobjs/arch/cellardoor"));
        assertTrue(CellarDiggerRules.isCellarDoor("gfx/terobjs/arch/cellardoor[3]"));
        assertFalse(CellarDiggerRules.isCellarDoor("gfx/terobjs/arch/cellarstairs"));
        assertFalse(CellarDiggerRules.isCellarDoor("gfx/terobjs/arch/cellardoorstep"));
        assertFalse(CellarDiggerRules.isCellarDoor("gfx/terobjs/arch/cellardoors"));
    }

    @Test public void recognizesAllCellarTransitionsAndNoNearMisses() {
        assertTrue(CellarDiggerRules.isCellarTransition("gfx/terobjs/arch/cellarstairs"));
        assertTrue(CellarDiggerRules.isCellarTransition("gfx/terobjs/arch/downstairs[1]"));
        assertTrue(CellarDiggerRules.isCellarTransition("gfx/terobjs/arch/upstairs"));
        assertFalse(CellarDiggerRules.isCellarTransition("gfx/terobjs/arch/cellardoor"));
        assertFalse(CellarDiggerRules.isCellarTransition("gfx/terobjs/arch/downstairs-extra"));
    }

    @Test public void acceptsOnlyStagedBumlings() {
        assertTrue(CellarDiggerRules.isBumling("gfx/terobjs/bumlings/rhyolite0"));
        assertTrue(CellarDiggerRules.isBumling("gfx/terobjs/bumlings/feldspar1[2]"));
        assertTrue(CellarDiggerRules.isBumling("GFX/TEROBJS/BUMLINGS/CINNABAR1"));
        assertFalse(CellarDiggerRules.isBumling("gfx/terobjs/bumlings/"));
        assertFalse(CellarDiggerRules.isBumling("gfx/terobjs/boulder"));
        assertFalse(CellarDiggerRules.isBumling("gfx/terobjs/items/granite"));
        assertFalse(CellarDiggerRules.isBumling("gfx/invobjs/rhyolite"));
    }

    @Test public void meterBoundariesAreExact() {
        assertTrue(CellarDiggerRules.energyTooLow(0.2499));
        assertFalse(CellarDiggerRules.energyTooLow(0.25));
        assertTrue(CellarDiggerRules.staminaNeedsRecovery(0.3999));
        assertFalse(CellarDiggerRules.staminaNeedsRecovery(0.40));
        assertFalse(CellarDiggerRules.staminaRecovered(0.7999));
        assertTrue(CellarDiggerRules.staminaRecovered(0.80));
        assertFalse(CellarDiggerRules.autoDrinkThresholdSafe(39));
        assertTrue(CellarDiggerRules.autoDrinkThresholdSafe(40));
    }

    @Test public void doorOutcomeRequiresAnAuthoritativeSignal() {
        assertEquals(CellarDiggerRules.DoorOutcome.CONTINUE,
            CellarDiggerRules.doorOutcome(true, true, false));
        assertEquals(CellarDiggerRules.DoorOutcome.COMPLETE,
            CellarDiggerRules.doorOutcome(false, false, true));
        assertEquals(CellarDiggerRules.DoorOutcome.RETRY,
            CellarDiggerRules.doorOutcome(false, true, false));
        assertEquals(CellarDiggerRules.DoorOutcome.RETRY,
            CellarDiggerRules.doorOutcome(false, false, false));
        assertEquals(CellarDiggerRules.DoorOutcome.RETRY,
            CellarDiggerRules.doorOutcome(false, true, true));
    }

    @Test public void safetyCapsStayBounded() {
        assertEquals(3, CellarDiggerRules.MAX_ATTEMPTS);
        assertEquals(500, CellarDiggerRules.MAX_CHIPS_PER_BOULDER);
        assertEquals(128, CellarDiggerRules.MAX_DOOR_CYCLES);
    }

    @Test public void groundedBoulderRequiresThreeStableSamples() {
        assertFalse(CellarDiggerRules.groundedBoulderStable(0));
        assertFalse(CellarDiggerRules.groundedBoulderStable(2));
        assertTrue(CellarDiggerRules.groundedBoulderStable(3));
        assertTrue(CellarDiggerRules.groundedBoulderStable(4));
    }

    @Test public void chipProtocolRequiresTheExactServerMenuOption() {
        assertEquals(1, CellarDiggerRules.exactMenuOption(
            new String[]{"Inspect", "Chip stone"}, "Chip stone"));
        assertEquals(-1, CellarDiggerRules.exactMenuOption(
            new String[]{"Inspect", "Chip Stone"}, "Chip stone"));
        assertEquals(-1, CellarDiggerRules.exactMenuOption(null, "Chip stone"));
    }

    @Test public void directInteractionRangeIncludesItsBoundary() {
        assertTrue(CellarDiggerRules.withinDirectInteractionRange(0.0, 55.0));
        assertTrue(CellarDiggerRules.withinDirectInteractionRange(55.0, 55.0));
        assertFalse(CellarDiggerRules.withinDirectInteractionRange(55.001, 55.0));
        assertFalse(CellarDiggerRules.withinDirectInteractionRange(Double.NaN, 55.0));
    }

    @Test public void boulderDropTargetsTheOpenApproachSide() {
        Coord2d target = CellarDiggerRules.boulderDropTarget(
            Coord2d.of(10.0, 20.0), Coord2d.of(13.0, 24.0), 0.0, 10.0, 1);
        assertEquals(16.0, target.x, 1e-9);
        assertEquals(28.0, target.y, 1e-9);
        assertEquals(10.0, target.dist(Coord2d.of(10.0, 20.0)), 1e-9);
    }

    @Test public void coincidentApproachFallsBackOppositeFacingAndFansRetries() {
        Coord2d door = Coord2d.of(4.0, 7.0);
        Coord2d first = CellarDiggerRules.boulderDropTarget(door, door, 0.0, 10.0, 1);
        Coord2d second = CellarDiggerRules.boulderDropTarget(door, door, 0.0, 10.0, 2);
        Coord2d third = CellarDiggerRules.boulderDropTarget(door, door, 0.0, 10.0, 3);
        assertEquals(-6.0, first.x, 1e-9);
        assertEquals(7.0, first.y, 1e-9);
        assertTrue(second.y < door.y);
        assertTrue(third.y > door.y);
        assertEquals(10.0, second.dist(door), 1e-9);
        assertEquals(10.0, third.dist(door), 1e-9);
    }
}
