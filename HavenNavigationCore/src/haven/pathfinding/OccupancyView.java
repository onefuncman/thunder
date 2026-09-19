package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;

/** Renderer-independent occupancy snapshot used by goal-region selection. */
public interface OccupancyView {
   Coord2d origin();
   int width();
   int height();
   double cell();
   double radius();
   byte[] occupancy();
   Coord2d player();
   Coord cellOf(Coord2d p);
}
