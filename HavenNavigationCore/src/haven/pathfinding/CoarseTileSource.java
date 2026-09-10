package haven.pathfinding;

import java.util.Arrays;

public interface CoarseTileSource {
   int width();

   int height();

   CoarseTileSource.Tile tile(int var1, int var2);

   static CoarseTileSource.Tile classify(String name) {
      if (TerrainPolicy.isUnknownTile(name)) {
         return CoarseTileSource.Tile.UNKNOWN;
      } else {
         return TerrainPolicy.terrainBlocks(name) ? CoarseTileSource.Tile.BLOCKED : CoarseTileSource.Tile.FREE;
      }
   }

   static CoarseTileSource.Tile classify(String name, haven.nav.MobilityProfile mob) {
      if (TerrainPolicy.isUnknownTile(name)) {
         return CoarseTileSource.Tile.UNKNOWN;
      } else {
         return TerrainPolicy.terrainBlocks(name, mob) ? CoarseTileSource.Tile.BLOCKED : CoarseTileSource.Tile.FREE;
      }
   }

   static CoarseTileSource fromNames(int w, final int h, String[] names) {
      if (w > 0 && h > 0) {
         final String[] copy = names == null ? new String[0] : Arrays.copyOf(names, names.length);
         final int fw = w;
         return new CoarseTileSource() {
            @Override
            public int width() {
               return fw;
            }

            @Override
            public int height() {
               return h;
            }

            @Override
            public CoarseTileSource.Tile tile(int x, int y) {
               int i = y * fw + x;
               return i >= 0 && i < copy.length ? CoarseTileSource.classify(copy[i]) : CoarseTileSource.Tile.UNKNOWN;
            }
         };
      } else {
         throw new IllegalArgumentException("bad size " + w + "x" + h);
      }
   }

   public static enum Tile {
      UNKNOWN,
      BLOCKED,
      FREE;
   }
}
