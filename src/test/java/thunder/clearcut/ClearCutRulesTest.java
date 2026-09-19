package thunder.clearcut;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ClearCutRulesTest {
    @Test public void classifiesAllClearCutTargetKinds() {
        assertTrue(ClearCutRules.isTree("gfx/terobjs/trees/oak"));
        assertTrue(ClearCutRules.isTree("gfx/terobjs/trees/oak-sapling"));
        assertFalse(ClearCutRules.isTree("gfx/terobjs/trees/oaklog"));
        assertFalse(ClearCutRules.isTree("gfx/terobjs/trees/oakstump"));
        assertFalse(ClearCutRules.isTree("gfx/terobjs/bushes/hazel"));
        assertTrue(ClearCutRules.isBush("gfx/terobjs/bushes/hazel"));
        assertTrue(ClearCutRules.isBush("gfx/terobjs/bushes/sandthorn"));
        assertTrue(ClearCutRules.isBush("gfx/terobjs/bushes/hazel[3]"));
        assertFalse(ClearCutRules.isBush("gfx/terobjs/trees/oak"));
        assertTrue(ClearCutRules.isBoulder("gfx/terobjs/boulder"));
        assertTrue(ClearCutRules.isBoulder("gfx/terobjs/boulder[granite]"));
        assertTrue(ClearCutRules.isBoulder("gfx/terobjs/bumlings/rhyolite0"));
        assertTrue(ClearCutRules.isBoulder("gfx/terobjs/bumlings/feldspar1[2]"));
        assertTrue(ClearCutRules.isBoulder("gfx/terobjs/bumlings/basalt1"));
        assertTrue(ClearCutRules.isBoulder("gfx/terobjs/bumlings/cinnabar1"));
        assertFalse(ClearCutRules.isBoulder("gfx/terobjs/items/granite"));
        assertFalse(ClearCutRules.isBoulder("gfx/invobjs/rhyolite"));
        assertTrue(ClearCutRules.isLog("gfx/terobjs/trees/yewoldtrunk"));
        assertTrue(ClearCutRules.isStump("gfx/terobjs/trees/yewstump"));
        assertTrue(ClearCutRules.isTree("gfx/terobjs/trees/larch[17]"));
        assertTrue(ClearCutRules.isLog("gfx/terobjs/trees/larchlog[4]"));
        assertTrue(ClearCutRules.isStump("gfx/terobjs/trees/larchstump[2]"));
        assertTrue(ClearCutRules.isToughRoot("gfx/terobjs/items/toughroot"));
        assertFalse(ClearCutRules.isToughRoot("gfx/terobjs/items/strangeroot"));
        assertTrue(ClearCutRules.isRootInventoryItem("gfx/invobjs/strangeroot"));
        assertTrue(ClearCutRules.isRootInventoryItem("gfx/invobjs/toughroot"));
    }

    @Test public void recognizesToolFamilies() {
        assertTrue(ClearCutRules.isAxe("gfx/invobjs/small/woodsmansaxe"));
        assertTrue(ClearCutRules.isAxe("gfx/invobjs/b12axe"));
        assertFalse(ClearCutRules.isAxe("gfx/invobjs/pickaxe"));
        assertTrue(ClearCutRules.isShovel("gfx/invobjs/small/shovel-m"));
    }

    @Test public void allowsOnlySafeTreeProductActions() {
        assertTrue(ClearCutRules.isTreeProductAction("Take bough"));
        assertTrue(ClearCutRules.isTreeProductAction("Take bark"));
        assertTrue(ClearCutRules.isTreeProductAction("Pick leaf"));
        assertTrue(ClearCutRules.isTreeProductAction("Pick apple"));
        assertTrue(ClearCutRules.isTreeProductAction("Pick cone"));
        assertFalse(ClearCutRules.isTreeProductAction("Pick up"));
        assertFalse(ClearCutRules.isTreeProductAction("Pick mushroom"));
        assertFalse(ClearCutRules.isTreeProductAction("Pick sprout"));
        assertFalse(ClearCutRules.isTreeProductAction("Chop"));
        assertTrue(ClearCutRules.productPriority("Take bough") < ClearCutRules.productPriority("Pick apple"));
        assertEquals(100, ClearCutConfig.MAX_PRODUCT_ACTIONS_PER_SOURCE);
        assertEquals(1, ClearCutConfig.PRODUCT_MENU_ATTEMPTS);
        assertEquals(3000L, ClearCutConfig.PRODUCT_MENU_TIMEOUT_MS);
        assertEquals(10000L, ClearCutConfig.PRODUCT_ACTION_TIMEOUT_MS);
        assertEquals(4, ClearCutConfig.MAX_LOG_PLACEMENT_ANCHORS);
    }

    @Test public void decodesAllSixCartSlots() {
        assertEquals(0, ClearCutRules.occupiedCartCount(0));
        assertEquals(java.util.Arrays.asList(2, 4, 7), ClearCutRules.occupiedCartSlots(4 | 16 | 128));
        assertTrue(ClearCutRules.cartFull(4 | 8 | 16 | 32 | 64 | 128));
        assertFalse(ClearCutRules.cartFull(-1));
    }

    @Test public void fourTreeBatchAndEnergyBoundariesAreExact() {
        assertFalse(ClearCutRules.shouldHaul(3));
        assertTrue(ClearCutRules.shouldHaul(4));
        assertTrue(ClearCutRules.needsEnergy(0.3999));
        assertFalse(ClearCutRules.needsEnergy(0.40));
        assertFalse(ClearCutRules.energyTargetReached(0.7999));
        assertTrue(ClearCutRules.energyTargetReached(0.80));
        assertEquals(3, ClearCutConfig.MAX_ATTEMPTS);
    }

    @Test public void completionRequiresAllFiveSourceKindsGone() {
        assertTrue(ClearCutRules.completionSatisfied(0, 0, 0, 0, 0));
        assertFalse(ClearCutRules.completionSatisfied(1, 0, 0, 0, 0));
        assertFalse(ClearCutRules.completionSatisfied(0, 1, 0, 0, 0));
        assertFalse(ClearCutRules.completionSatisfied(0, 0, 1, 0, 0));
        assertFalse(ClearCutRules.completionSatisfied(0, 0, 0, 1, 0));
        assertFalse(ClearCutRules.completionSatisfied(0, 0, 0, 0, 1));
    }

    @Test public void rootWarningStartsOnlyWhenNoInventorySquareRemains() {
        assertFalse(ClearCutRules.rootInventoryNeedsAttention(1));
        assertTrue(ClearCutRules.rootInventoryNeedsAttention(0));
    }

    @Test public void surveyIsBoundaryInclusiveAndNeverSkipsMoreThanTwentyTiles() {
        Area area = new Area(Coord.of(10, 30), Coord.of(63, 76));
        List<Coord> points = ClearCutRules.surveyTiles(area, 20);
        assertTrue(points.contains(Coord.of(10, 30)));
        assertTrue(points.contains(Coord.of(62, 75)));
        for(Coord p : points) assertTrue(area.contains(p));
        java.util.Set<Integer> ys = new java.util.TreeSet<>();
        for(Coord p : points) ys.add(p.y);
        Integer previous = null;
        for(Integer y : ys) {
            if(previous != null) assertTrue(y - previous <= 20);
            previous = y;
        }
    }

    @Test public void surveyOffersNearbyCandidatesInsteadOfOneExactTile() {
        Area allowed = new Area(Coord.of(10, 20), Coord.of(15, 25));
        Coord preferred = Coord.of(12, 22);
        List<Coord> candidates = ClearCutRules.surveyCandidateTiles(preferred, allowed, 2);
        assertEquals(preferred, candidates.get(0));
        assertTrue(candidates.contains(Coord.of(11, 22)));
        assertTrue(candidates.contains(Coord.of(13, 23)));
        assertEquals(25, candidates.size());
        assertEquals(candidates.size(), new java.util.HashSet<>(candidates).size());
        for(Coord candidate : candidates) assertTrue(allowed.contains(candidate));
    }
}
