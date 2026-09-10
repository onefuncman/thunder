package haven.layout;

import haven.Coord2d;

/**
 * An existing, arbitrary-shaped occupied area in the world that a layout must
 * respect (objects that stay in place, or blocked ground). The anchor is the
 * world-space corner of the footprint's {@code (0,0)} cell. Shape cells are
 * sized at the request's pitch, the same grid placements are planned on.
 */
public final class LayoutShape {
   public final LayoutFootprint footprint;
   public final Coord2d worldAnchor;

   public LayoutShape(LayoutFootprint footprint, Coord2d worldAnchor) {
      if (footprint == null) {
         throw new IllegalArgumentException("footprint");
      }
      if (worldAnchor == null) {
         throw new IllegalArgumentException("worldAnchor");
      }
      this.footprint = footprint;
      this.worldAnchor = worldAnchor;
   }

   public static LayoutShape at(LayoutFootprint footprint, Coord2d worldAnchor) {
      return new LayoutShape(footprint, worldAnchor);
   }

   @Override
   public String toString() {
      return "LayoutShape{" + this.footprint + " @ " + this.worldAnchor + "}";
   }
}
