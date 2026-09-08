package haven.layout;

import haven.Coord2d;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One accepted placement in a layout plan: the footprint anchored at a
 * placement-grid position, together with the interaction stand that was proven
 * reachable from the request's approach position at accept time.
 */
public final class LayoutPlacement {
   /** Acceptance order, 0-based. */
   public final int index;
   /** Anchor cell of the footprint's {@code (0,0)} in placement-grid coordinates. */
   public final int gridX;
   public final int gridY;
   /** World-space corner of the footprint's {@code (0,0)} cell. */
   public final Coord2d world;
   public final LayoutFootprint footprint;
   /** Proven interaction stand (world position). */
   public final Coord2d stand;
   /** Waypoints from the request's approachFrom to {@link #stand}. */
   public final List<Coord2d> standRoute;

   public LayoutPlacement(
      int index, int gridX, int gridY, Coord2d world, LayoutFootprint footprint, Coord2d stand, List<Coord2d> standRoute
   ) {
      if (world == null) {
         throw new IllegalArgumentException("world");
      }
      if (footprint == null) {
         throw new IllegalArgumentException("footprint");
      }
      if (stand == null) {
         throw new IllegalArgumentException("stand");
      }
      this.index = index;
      this.gridX = gridX;
      this.gridY = gridY;
      this.world = world;
      this.footprint = footprint;
      this.stand = stand;
      this.standRoute = standRoute == null
         ? Collections.singletonList(stand)
         : Collections.unmodifiableList(new ArrayList<Coord2d>(standRoute));
   }

   @Override
   public String toString() {
      return "LayoutPlacement{" + this.index + " @ grid(" + this.gridX + "," + this.gridY + ") world "
         + this.world + " stand " + this.stand + "}";
   }
}
