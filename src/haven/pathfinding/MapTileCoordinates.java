package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;

/** Converts between the live session world and saved-map tile coordinates. */
public final class MapTileCoordinates {
   private MapTileCoordinates() {
   }

   public static Coord playerTile(Coord2d worldPosition, Coord sessionTile) {
      if (worldPosition == null || sessionTile == null) {
         throw new IllegalArgumentException("worldPosition and sessionTile are required");
      }
      return worldPosition.floor(MCache.tilesz).add(sessionTile);
   }

   public static Coord2d worldPosition(Coord tile, Coord sessionTile) {
      if (tile == null || sessionTile == null) {
         throw new IllegalArgumentException("tile and sessionTile are required");
      }
      return tile.sub(sessionTile).mul(MCache.tilesz).add(MCache.tilesz.div(2.0));
   }
}
