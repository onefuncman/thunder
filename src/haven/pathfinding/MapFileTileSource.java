package haven.pathfinding;

import haven.Area;
import haven.Coord;
import haven.Indir;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MapFile.Grid;
import haven.MapFile.Segment;

public final class MapFileTileSource implements CoarseTileSource {
   private final MapFile file;
   private final long segId;
   private final Coord ul;
   private final Coord lr;

   private MapFileTileSource(MapFile file, long segId, Coord ul, Coord lr) {
      this.file = file;
      this.segId = segId;
      this.ul = ul;
      this.lr = lr;
   }

   public static MapFileTileSource of(MapFile file, long segId, Area region) {
      if (file == null) {
         throw new IllegalArgumentException("file must not be null");
      } else if (region == null) {
         throw new IllegalArgumentException("region must not be null");
      } else {
         return of(file, segId, region.ul, region.br);
      }
   }

   public static MapFileTileSource of(MapFile file, long segId, Coord ul, Coord lr) {
      if (file == null) {
         throw new IllegalArgumentException("file must not be null");
      } else if (ul == null || lr == null) {
         throw new IllegalArgumentException("bounds must not be null");
      } else if (lr.x > ul.x && lr.y > ul.y) {
         return new MapFileTileSource(file, segId, ul, lr);
      } else {
         throw new IllegalArgumentException(String.format("empty tile region %s..%s", ul, lr));
      }
   }

   public long segId() {
      return this.segId;
   }

   @Override
   public int width() {
      return this.lr.x - this.ul.x;
   }

   @Override
   public int height() {
      return this.lr.y - this.ul.y;
   }

   @Override
   public CoarseTileSource.Tile tile(int x, int y) {
      if (x >= 0 && y >= 0 && x < this.width() && y < this.height()) {
         Coord wt = Coord.of(this.ul.x + x, this.ul.y + y);
         Coord gc = wt.div(MCache.cmaps);
         Coord lc = wt.sub(gc.mul(MCache.cmaps));
         this.file.lock.readLock().lock();

         CoarseTileSource.Tile var11;
         try {
            Segment seg = (Segment)this.file.segments.get(this.segId);
            if (seg == null) {
               return CoarseTileSource.Tile.UNKNOWN;
            }

            Indir<Grid> ind = seg.grid(gc);
            Grid grid = (Grid)Loading.waitfor(ind);
            if (grid == null) {
               return CoarseTileSource.Tile.UNKNOWN;
            }

            int ti = grid.gettile(lc);
            if (ti < 0 || grid.tilesets == null || ti >= grid.tilesets.length) {
               return CoarseTileSource.Tile.UNKNOWN;
            }

            String name = grid.tilesets[ti].res.name;
            if (name != null) {
               return CoarseTileSource.classify(name);
            }

            var11 = CoarseTileSource.Tile.UNKNOWN;
         } catch (RuntimeException var15) {
            return CoarseTileSource.Tile.UNKNOWN;
         } finally {
            this.file.lock.readLock().unlock();
         }

         return var11;
      } else {
         throw new IllegalArgumentException(String.format("tile (%d, %d) outside region %s..%s", x, y, this.ul, this.lr));
      }
   }
}
