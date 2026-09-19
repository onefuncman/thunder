package haven.pathfinding;

import haven.Coord2d;

/**
 * Purpose-specific spatial facts for one resource family.
 *
 * <p>A grounded collision box is deliberately not assumed to be the shape the
 * server accepts for placement. A carried world object has no collision at all;
 * {@link #navigationHalf} is used only while the object is grounded.</p>
 */
public final class ObjectSpatialProfile {
    public enum PlacementFailure {
        SILENT_OR_GROUNDED,
        MESSAGE_OR_CREATED
    }

    public final String resource;
    public final Coord2d navigationHalf;
    public final String navigationSource;
    public final Coord2d placementHalf;
    public final String placementSource;
    public final double placementGap;
    public final boolean placementGapConfirmed;
    public final boolean playerMayOverlapPlacement;
    public final boolean stableApproachPorts;
    public final PlacementFailure placementFailure;

    ObjectSpatialProfile(String resource, Coord2d navigationHalf, String navigationSource,
                         Coord2d placementHalf, String placementSource,
                         double placementGap, boolean placementGapConfirmed,
                         boolean playerMayOverlapPlacement, boolean stableApproachPorts,
                         PlacementFailure placementFailure) {
        this.resource = resource == null ? "" : resource;
        this.navigationHalf = navigationHalf;
        this.navigationSource = navigationSource == null ? "" : navigationSource;
        this.placementHalf = placementHalf;
        this.placementSource = placementSource == null ? "" : placementSource;
        this.placementGap = Math.max(0.0, placementGap);
        this.placementGapConfirmed = placementGapConfirmed;
        this.playerMayOverlapPlacement = playerMayOverlapPlacement;
        this.stableApproachPorts = stableApproachPorts;
        this.placementFailure = placementFailure == null
            ? PlacementFailure.SILENT_OR_GROUNDED : placementFailure;
    }
}
