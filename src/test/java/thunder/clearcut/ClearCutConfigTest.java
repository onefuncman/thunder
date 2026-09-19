package thunder.clearcut;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ClearCutConfigTest {
    private static Area area(int x, int y) {return new Area(Coord.of(x, y), Coord.of(x + 2, y + 2));}

    @Test public void acceptsOptionalWaterAndFoodAreasWithoutProducts() {
        ClearCutConfig cfg = new ClearCutConfig(area(0, 0), null, null, area(11, 0), null, false);
        assertNull(cfg.validationError());
        assertFalse(cfg.hasWaterArea());
        assertFalse(cfg.hasFoodArea());
    }

    @Test public void recognizesSelectedSupplyAreas() {
        ClearCutConfig cfg = new ClearCutConfig(area(0, 0), area(5, 0), area(8, 0), area(11, 0), null, false);
        assertNull(cfg.validationError());
        assertTrue(cfg.hasWaterArea());
        assertTrue(cfg.hasFoodArea());
    }

    @Test public void productAreaIsConditional() {
        ClearCutConfig cfg = new ClearCutConfig(area(0, 0), area(5, 0), area(8, 0), area(11, 0), null, true);
        assertTrue(cfg.validationError().contains("tree-product"));
    }

    @Test public void rejectsWorkAndLogDropOverlap() {
        ClearCutConfig cfg = new ClearCutConfig(area(0, 0), area(5, 0), area(8, 0), area(1, 1), area(14, 0), true);
        assertTrue(cfg.validationError().contains("must not overlap"));
    }

    @Test public void allowsButWarnsAboutOtherOverlaps() {
        ClearCutConfig cfg = new ClearCutConfig(area(0, 0), area(1, 1), area(8, 0), area(11, 0), area(8, 1), true);
        assertNull(cfg.validationError());
        assertEquals(2, cfg.overlapWarnings().size());
    }
}
