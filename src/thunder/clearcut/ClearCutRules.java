package thunder.clearcut;

import haven.Area;
import haven.Coord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure resource/menu rules so the state machine can be tested headlessly. */
public final class ClearCutRules {
    private ClearCutRules() {}

    public static final String[] AXE_TYPES = {
        "/woodsmansaxe", "/stoneaxe", "/butcherscleaver", "/axe-m",
        "/tinkersthrowingaxe", "/b12axe"
    };
    public static final String[] SHOVEL_TYPES = {"/shovel-m", "/shovel-t", "/shovel-w"};

    public static boolean isTree(String resid) {
        if(resid == null) return false;
        String n = baseResid(resid);
        return n.startsWith("gfx/terobjs/trees") && !isLog(n) && !isStump(n);
    }

    public static boolean isLog(String resid) {
        if(resid == null) return false;
        String n = baseResid(resid);
        return n.startsWith("gfx/terobjs/trees") &&
            (n.endsWith("log") || n.endsWith("oldtrunk") || n.contains("/driftwood"));
    }

    public static boolean isStump(String resid) {
        if(resid == null) return false;
        String n = baseResid(resid);
        return n.startsWith("gfx/terobjs/trees") && n.contains("stump");
    }

    public static boolean isBush(String resid) {
        return resid != null && baseResid(resid).startsWith("gfx/terobjs/bushes/");
    }

    public static boolean isBoulder(String resid) {
        if(resid == null) return false;
        String n = baseResid(resid);
        return n.equals("gfx/terobjs/boulder") || n.startsWith("gfx/terobjs/bumlings/");
    }

    /** Gob resources can temporarily include a drawable-state suffix. It is
     * not part of the resource identity and must not hide a live target. */
    static String baseResid(String resid) {
        String n = resid == null ? "" : resid.toLowerCase(Locale.ROOT);
        int bracket = n.indexOf('[');
        return bracket > 0 ? n.substring(0, bracket) : n;
    }

    public static boolean isCart(String resid) {
        if(resid == null) return false;
        String n = resid.toLowerCase(Locale.ROOT);
        return n.equals("gfx/terobjs/vehicle/cart") || n.endsWith("/cart");
    }

    public static boolean isToughRoot(String resid) {
        return resid != null && resid.toLowerCase(Locale.ROOT).equals("gfx/terobjs/items/toughroot");
    }

    public static boolean isRootInventoryItem(String resid) {
        if(resid == null) return false;
        String n = resid.toLowerCase(Locale.ROOT);
        return n.endsWith("/toughroot") || n.endsWith("/strangeroot");
    }

    public static boolean rootInventoryNeedsAttention(int freeOneByOneSlots) {
        return freeOneByOneSlots < 1;
    }

    public static boolean isAxe(String resid) {return hasType(resid, AXE_TYPES);}
    public static boolean isShovel(String resid) {return hasType(resid, SHOVEL_TYPES);}

    private static boolean hasType(String resid, String[] types) {
        if(resid == null) return false;
        String n = resid.toLowerCase(Locale.ROOT);
        // Belt versions insert /small/ before the basename; endsWith handles both.
        for(String type : types) if(n.endsWith(type)) return true;
        return false;
    }

    /** Safe allow-list for non-destructive products exposed by a tree's flower menu. */
    public static boolean isTreeProductAction(String option) {
        if(option == null) return false;
        String n = option.trim().toLowerCase(Locale.ROOT);
        if(n.equals("take bark") || n.equals("take bough") || n.equals("take branch") ||
           n.equals("pick leaf") || n.equals("pick fruit") || n.equals("pick seed") ||
           n.equals("pick seeds") || n.equals("pick nuts") || n.equals("pick nut")) return true;
        return (n.equals("pick") || n.startsWith("pick ")) && !n.equals("pick up") &&
            !n.contains("mushroom") && !n.contains("sprout");
    }

    /** Largest-first reduces fragmented-inventory failures (boughs are 2x1). */
    public static int productPriority(String option) {
        if(option == null) return Integer.MAX_VALUE;
        String n = option.toLowerCase(Locale.ROOT);
        if(n.contains("bough")) return 0;
        if(n.contains("branch")) return 1;
        if(n.contains("bark")) return 2;
        if(n.contains("leaf")) return 3;
        return 4;
    }

    public static List<Integer> occupiedCartSlots(int state) {
        if(state < 0) return Collections.emptyList();
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        int bit = 2;
        for(int slot = 2; slot <= 7; slot++) {
            bit <<= 1;
            if((state & bit) != 0) out.add(slot);
        }
        return out;
    }

    public static int occupiedCartCount(int state) {return occupiedCartSlots(state).size();}
    public static boolean cartFull(int state) {return state >= 0 && occupiedCartCount(state) == 6;}

    public static boolean shouldHaul(int completedTreesInBatch) {
        return completedTreesInBatch >= ClearCutConfig.TREES_PER_HAUL;
    }

    public static boolean needsEnergy(double energy) {
        return energy >= 0 && energy < ClearCutConfig.LOW_ENERGY;
    }

    public static boolean energyTargetReached(double energy) {
        return energy >= ClearCutConfig.EAT_UNTIL_PERCENT / 100.0;
    }

    public static boolean completionSatisfied(int trees, int bushes, int boulders, int stumps, int logs) {
        return trees == 0 && bushes == 0 && boulders == 0 && stumps == 0 && logs == 0;
    }

    /** Boundary-inclusive serpentine survey with no gap larger than {@code maxStep}. */
    public static List<Coord> surveyTiles(Area area, int maxStep) {
        if(area == null || !area.positive() || maxStep <= 0) return Collections.emptyList();
        List<Integer> ys = axisPoints(area.ul.y, area.br.y - 1, maxStep);
        List<Integer> xs = axisPoints(area.ul.x, area.br.x - 1, maxStep);
        List<Coord> out = new ArrayList<>();
        for(int row = 0; row < ys.size(); row++) {
            if((row & 1) == 0) for(Integer x : xs) out.add(Coord.of(x, ys.get(row)));
            else for(int i = xs.size() - 1; i >= 0; i--) out.add(Coord.of(xs.get(i), ys.get(row)));
        }
        return out;
    }

    /** Preferred survey tile first, followed by nearby alternatives in
     * expanding rings. Movement decides which candidate is actually reachable. */
    public static List<Coord> surveyCandidateTiles(Coord preferred, Area allowed, int radius) {
        if(preferred == null || allowed == null || !allowed.positive() || radius < 0)
            return Collections.emptyList();
        List<Coord> out = new ArrayList<>();
        for(int ring = 0; ring <= radius; ring++) {
            for(int dy = -ring; dy <= ring; dy++) for(int dx = -ring; dx <= ring; dx++) {
                if(Math.max(Math.abs(dx), Math.abs(dy)) != ring) continue;
                Coord candidate = preferred.add(dx, dy);
                if(allowed.contains(candidate)) out.add(candidate);
            }
        }
        return out;
    }

    private static List<Integer> axisPoints(int low, int high, int maxStep) {
        List<Integer> out = new ArrayList<>();
        out.add(low);
        if(high <= low) return out;
        for(int v = low + maxStep; v < high; v += maxStep) out.add(v);
        if(out.get(out.size() - 1) != high) out.add(high);
        return out;
    }
}
