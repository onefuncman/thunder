package haven.pathfinding;

import haven.Coord2d;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Single resolver for navigation, interaction and placement facts. */
public final class ObjectSpatialProfiles {
    /** Confirmed by the 2026-09-14 manual parallel-log recording. */
    public static final double DEFAULT_PLACEMENT_GAP = 0.125;

    private static final Map<String, Double> OBSERVED_GAPS = new ConcurrentHashMap<String, Double>();

    private ObjectSpatialProfiles() {}

    public static ObjectSpatialProfile resolve(String resource) {
        String base = MovementScene.baseResid(resource);
        if (base == null) base = "";
        Coord2d navigation = NurglingFallbacks.half(base);
        String navigationSource = navigation == null ? "live-movement" : "nurgling-hitbox";

        boolean log = ordinaryTreeLog(base);
        boolean stockpile = base.startsWith("gfx/terobjs/stockpile-");
        Coord2d placement = null;
        String placementSource = "live-placement";
        boolean gapConfirmed = false;
        if (log) {
            // Nurgling NModelBox and the manual tight-placement recording agree.
            placement = Coord2d.of(10.0, 2.0);
            placementSource = "nurgling-model+observed";
            gapConfirmed = true;
        } else if (stockpile) {
            // Provisional until a manual example for this stockpile family is recorded.
            // Deliberately scoped to placement instead of globally reusing NHitBox.
            placement = navigation;
            placementSource = placement == null ? "live-placement" : "provisional-stockpile-catalog";
        }

        Double learned = OBSERVED_GAPS.get(base);
        double gap = learned == null ? DEFAULT_PLACEMENT_GAP : learned;
        if (learned != null) gapConfirmed = true;
        return new ObjectSpatialProfile(
            base, navigation, navigationSource, placement, placementSource,
            gap, gapConfirmed, !stockpile, true,
            stockpile ? ObjectSpatialProfile.PlacementFailure.MESSAGE_OR_CREATED
                      : ObjectSpatialProfile.PlacementFailure.SILENT_OR_GROUNDED
        );
    }

    /** Records only a server-observed success; callers must not record planned gaps. */
    public static void confirmPlacementGap(String resource, double gap) {
        if (!Double.isFinite(gap) || gap < 0.0) return;
        String base = MovementScene.baseResid(resource);
        if (base != null && !base.isEmpty()) OBSERVED_GAPS.put(base, gap);
    }

    static void clearObservedGapsForTests() {
        OBSERVED_GAPS.clear();
    }

    public static boolean ordinaryTreeLog(String resource) {
        String base = MovementScene.baseResid(resource);
        return base != null && base.startsWith("gfx/terobjs/trees/") && base.endsWith("log");
    }
}
