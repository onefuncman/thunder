package haven.pathfinding;

import haven.Area;
import haven.Coord;
import haven.MCache;
import haven.MapFile;
import haven.ResCache;
import haven.Resource;
import haven.StreamMessage;
import haven.ZMessage;
import haven.MapFile.Grid;
import haven.MapFile.TileInfo;
import haven.Resource.Saved;
import haven.pathfinding.CoarseRoutePlanner.Cause;
import haven.pathfinding.CoarseRoutePlanner.Route;
import haven.pathfinding.CoarseRoutePlanner.Status;
import haven.pathfinding.CoarseTileSource.Tile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MapFileTileSourceTest {
   private static final long SEG = 1311768467463790320L;
   private static final long OTHER_SEG = -77129852519518195L;
   private static final long G0 = 4096L;
   private static final long G1 = 4097L;
   private static final long G2 = 4098L;
   private static final long G3 = 4099L;

   private static MapFile newFile(MapFileTileSourceTest.MapCache cache) {
      return new MapFile(cache, "fixture");
   }

   private static void writeSegment(MapFile file, long segId, Map<Coord, Long> grids) throws IOException {
      StreamMessage out = new StreamMessage(file.sstore("seg-%x", new Object[]{segId}));

      try {
         out.adduint8(1);
         ZMessage z = new ZMessage(out);
         z.addint64(segId);
         z.addint32(grids.size());

         for (Entry<Coord, Long> e : grids.entrySet()) {
            z.addcoord(e.getKey()).addint64(e.getValue());
         }

         z.finish();
      } catch (Throwable var9) {
         try {
            out.close();
         } catch (Throwable var8) {
            var9.addSuppressed(var8);
         }

         throw var9;
      }

      out.close();
   }

   private static void writeGrid(MapFile file, long gridId, String defaultName, Map<Coord, String> overrides) throws IOException {
      int w = MCache.cmaps.x;
      int h = MCache.cmaps.y;
      Map<String, Integer> sets = new LinkedHashMap<>();

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            String nm = overrides.getOrDefault(Coord.of(x, y), defaultName);
            sets.putIfAbsent(nm, sets.size());
         }
      }

      TileInfo[] tilesets = new TileInfo[sets.size()];

      for (Entry<String, Integer> e : sets.entrySet()) {
         tilesets[e.getValue()] = new TileInfo(new Saved(Resource.remote(), e.getKey(), 1), 0);
      }

      int[] tiles = new int[w * h];

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            String nm = overrides.getOrDefault(Coord.of(x, y), defaultName);
            tiles[y * w + x] = sets.get(nm);
         }
      }

      float[] zmap = new float[w * h];
      Arrays.fill(zmap, 30.0F);
      new Grid(gridId, tilesets, tiles, zmap, 0L).save(file);
   }

   private static void writeGridMismatchedId(MapFile file, long fileId, long storedId, String defaultName) throws IOException {
      int w = MCache.cmaps.x;
      int h = MCache.cmaps.y;
      TileInfo[] tilesets = new TileInfo[]{new TileInfo(new Saved(Resource.remote(), defaultName, 1), 0)};
      int[] tiles = new int[w * h];
      float[] zmap = new float[w * h];
      Grid grid = new Grid(storedId, tilesets, tiles, zmap, 0L);
      StreamMessage out = new StreamMessage(file.sstore("grid-%x", new Object[]{fileId}));

      try {
         grid.save(out);
      } catch (Throwable var16) {
         try {
            out.close();
         } catch (Throwable var15) {
            var16.addSuppressed(var15);
         }

         throw var16;
      }

      out.close();
   }

   private static MapFile fileWithGrid() throws IOException {
      MapFileTileSourceTest.MapCache cache = new MapFileTileSourceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      writeGrid(file, 4096L, "gfx/tiles/grass", new HashMap<>());
      return file;
   }

   @Test
   void freeTileFromPersistedName() throws Exception {
      MapFile file = fileWithGrid();
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(10, 20), Coord.of(12, 22)));
      Assertions.assertEquals(2, src.width());
      Assertions.assertEquals(2, src.height());
      Assertions.assertEquals(Tile.FREE, src.tile(0, 0));
      Assertions.assertEquals(Tile.FREE, src.tile(1, 1));
   }

   @Test
   void blockedTileFromPersistedName() throws Exception {
      MapFileTileSourceTest.MapCache cache = new MapFileTileSourceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      Map<Coord, String> ov = new HashMap<>();
      ov.put(Coord.of(10, 20), "gfx/tiles/nil");
      ov.put(Coord.of(11, 20), "gfx/tiles/cave");
      ov.put(Coord.of(12, 20), "gfx/tiles/deep/water");
      ov.put(Coord.of(13, 20), "gfx/tiles/rocks/mountain");
      writeGrid(file, 4096L, "gfx/tiles/grass", ov);
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(10, 20), Coord.of(14, 24)));
      Assertions.assertEquals(Tile.BLOCKED, src.tile(0, 0));
      Assertions.assertEquals(Tile.BLOCKED, src.tile(1, 0));
      Assertions.assertEquals(Tile.BLOCKED, src.tile(2, 0));
      Assertions.assertEquals(Tile.BLOCKED, src.tile(3, 0));
      Assertions.assertEquals(Tile.FREE, src.tile(0, 1));
      Assertions.assertEquals(Tile.FREE, src.tile(3, 3));
   }

   @Test
   void notileClassifiesUnknownNeverFree() throws Exception {
      MapFileTileSourceTest.MapCache cache = new MapFileTileSourceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      Map<Coord, String> ov = new HashMap<>();
      ov.put(Coord.of(10, 20), "gfx/tiles/notile");
      writeGrid(file, 4096L, "gfx/tiles/grass", ov);
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(10, 20), Coord.of(12, 22)));
      Assertions.assertEquals(Tile.UNKNOWN, src.tile(0, 0), "notile sentinel must classify unknown");
      Assertions.assertEquals(Tile.FREE, src.tile(1, 1), "neighboring persisted grass stays free");
   }

   @Test
   void missingGridInSegmentIsUnknown() throws Exception {
      MapFileTileSourceTest.MapCache cache = new MapFileTileSourceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      writeGrid(file, 4096L, "gfx/tiles/grass", new HashMap<>());
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(100, 10), Coord.of(103, 13)));
      Assertions.assertEquals(3, src.width());
      Assertions.assertEquals(3, src.height());

      for (int y = 0; y < src.height(); y++) {
         for (int x = 0; x < src.width(); x++) {
            Assertions.assertEquals(Tile.UNKNOWN, src.tile(x, y), "unpersisted grid cell must be unknown, never free");
         }
      }
   }

   @Test
   void absentSegmentIsUnknown() throws Exception {
      MapFileTileSourceTest.MapCache cache = new MapFileTileSourceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      writeGrid(file, 4096L, "gfx/tiles/grass", new HashMap<>());
      MapFileTileSource src = MapFileTileSource.of(file, -77129852519518195L, Area.corn(Coord.of(0, 0), Coord.of(4, 4)));
      Assertions.assertEquals(Tile.UNKNOWN, src.tile(0, 0));
      Assertions.assertEquals(Tile.UNKNOWN, src.tile(2, 2));
      Assertions.assertEquals(Tile.UNKNOWN, src.tile(3, 3));
   }

   @Test
   void mismatchedGridIdIsUnknown() throws Exception {
      MapFileTileSourceTest.MapCache cache = new MapFileTileSourceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      writeGridMismatchedId(file, 4096L, 4097L, "gfx/tiles/grass");
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(5, 5), Coord.of(7, 7)));
      Assertions.assertEquals(Tile.UNKNOWN, src.tile(0, 0), "grid data that does not belong to the mapped id is unavailable");
   }

   @Test
   void outOfRegionQueriesThrow() throws Exception {
      MapFile file = fileWithGrid();
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(10, 20), Coord.of(12, 22)));
      Assertions.assertThrows(IllegalArgumentException.class, () -> src.tile(-1, 0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> src.tile(0, -1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> src.tile(2, 0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> src.tile(0, 2));
      Assertions.assertThrows(IllegalArgumentException.class, () -> src.tile(5, 5));
   }

   @Test
   void degenerateOrNullConstructionIsRejected() throws Exception {
      MapFile file = fileWithGrid();
      Assertions.assertThrows(IllegalArgumentException.class, () -> MapFileTileSource.of(file, 1311768467463790320L, Coord.of(5, 5), Coord.of(5, 5)));
      Assertions.assertThrows(IllegalArgumentException.class, () -> MapFileTileSource.of(file, 1311768467463790320L, Coord.of(6, 5), Coord.of(5, 5)));
      Assertions.assertThrows(IllegalArgumentException.class, () -> MapFileTileSource.of(file, 1311768467463790320L, Coord.of(5, 6), Coord.of(5, 5)));
      Assertions.assertThrows(IllegalArgumentException.class, () -> MapFileTileSource.of(file, 1311768467463790320L, Coord.of(5, 5), null));
      Assertions.assertThrows(IllegalArgumentException.class, () -> MapFileTileSource.of(null, 1311768467463790320L, Coord.of(5, 5), Coord.of(6, 6)));
      Assertions.assertThrows(IllegalArgumentException.class, () -> MapFileTileSource.of(file, 1311768467463790320L, (Area)null));
   }

   @Test
   void regionStraddlingFourGridsTranslatesCoordinates() throws Exception {
      MapFileTileSourceTest.MapCache cache = new MapFileTileSourceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      grids.put(Coord.of(1, 0), 4097L);
      grids.put(Coord.of(0, 1), 4098L);
      grids.put(Coord.of(1, 1), 4099L);
      writeSegment(file, 1311768467463790320L, grids);
      writeGrid(file, 4096L, "gfx/tiles/grass", new HashMap<>());
      writeGrid(file, 4097L, "gfx/tiles/nil", new HashMap<>());
      writeGrid(file, 4098L, "gfx/tiles/cave", new HashMap<>());
      writeGrid(file, 4099L, "gfx/tiles/grass", new HashMap<>());
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(95, 95), Coord.of(105, 105)));
      Assertions.assertEquals(10, src.width());
      Assertions.assertEquals(10, src.height());
      Assertions.assertEquals(Tile.FREE, src.tile(0, 0));
      Assertions.assertEquals(Tile.BLOCKED, src.tile(5, 0));
      Assertions.assertEquals(Tile.BLOCKED, src.tile(0, 5));
      Assertions.assertEquals(Tile.FREE, src.tile(5, 5));
      Assertions.assertEquals(Tile.FREE, src.tile(9, 9));
   }

   @Test
   void adapterAcquiresTheFileLockItself() throws Exception {
      MapFile file = fileWithGrid();
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(0, 0), Coord.of(3, 3)));
      Assertions.assertEquals(Tile.FREE, src.tile(1, 1));
   }

   @Test
   void reentrantFromReadLockedThread() throws Exception {
      MapFile file = fileWithGrid();
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(0, 0), Coord.of(3, 3)));
      file.lock.readLock().lock();

      try {
         Assertions.assertEquals(Tile.FREE, src.tile(1, 1), "nested read lock from a read-locked caller");
      } finally {
         file.lock.readLock().unlock();
      }
   }

   @Test
   void repeatedReadsAreStable() throws Exception {
      MapFile file = fileWithGrid();
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(0, 0), Coord.of(5, 5)));
      Assertions.assertEquals(Tile.FREE, src.tile(2, 2));
      Assertions.assertEquals(Tile.FREE, src.tile(2, 2));
      Assertions.assertEquals(Tile.FREE, src.tile(0, 0));
      Assertions.assertEquals(Tile.FREE, src.tile(2, 2), "same tile, same classification every read");
   }

   @Test
   void readsNeverWritePersistence() throws Exception {
      MapFileTileSourceTest.MapCache cache = new MapFileTileSourceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      writeGrid(file, 4096L, "gfx/tiles/grass", new HashMap<>());
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(0, 0), Coord.of(10, 10)));
      int writesBefore = cache.writes;

      for (int y = 0; y < src.height(); y++) {
         for (int x = 0; x < src.width(); x++) {
            Assertions.assertEquals(Tile.FREE, src.tile(x, y));
         }
      }

      Assertions.assertEquals(writesBefore, cache.writes, "reading a source must never write to the map file");

      for (String key : cache.data.keySet()) {
         Assertions.assertFalse(key.contains("zgrid"), "no derived zoom grids may be written: " + key);
      }
   }

   @Test
   void plannerRoutesAroundKnownBlockedTerrainThroughAdapter() throws Exception {
      MapFileTileSourceTest.MapCache cache = new MapFileTileSourceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      Map<Coord, String> ov = new HashMap<>();

      for (int y = 6; y <= 33; y++) {
         ov.put(Coord.of(20, y), "gfx/tiles/cave");
      }

      writeGrid(file, 4096L, "gfx/tiles/grass", ov);
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(0, 0), Coord.of(40, 40)));
      Route route = CoarseRoutePlanner.plan(src, Coord.of(2, 20), Coord.of(37, 20));
      Assertions.assertEquals(Status.REACHED, route.status);
      Assertions.assertEquals(Coord.of(2, 20), route.waypoints.get(0));
      Assertions.assertEquals(Coord.of(37, 20), route.waypoints.get(route.waypoints.size() - 1));
      Assertions.assertTrue(route.waypoints.size() >= 3, "the known cave wall forces a detour");
      boolean turned = false;

      for (Coord w : route.waypoints) {
         Assertions.assertNotEquals(Tile.BLOCKED, src.tile(w.x, w.y));
         if (w.y < 6 || w.y > 33) {
            turned = true;
         }
      }

      Assertions.assertTrue(turned, "route must go around the persisted wall, not through it");
   }

   @Test
   void plannerRejectsNotileGapThroughAdapter() throws Exception {
      MapFileTileSourceTest.MapCache cache = new MapFileTileSourceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      Map<Coord, String> ov = new HashMap<>();

      for (int y = 0; y < 40; y++) {
         ov.put(Coord.of(20, y), "gfx/tiles/notile");
      }

      writeGrid(file, 4096L, "gfx/tiles/grass", ov);
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, Area.corn(Coord.of(0, 0), Coord.of(40, 40)));
      Route route = CoarseRoutePlanner.plan(src, Coord.of(2, 20), Coord.of(37, 20));
      Assertions.assertEquals(Status.NO_KNOWN_ROUTE, route.status);
      Assertions.assertEquals(Cause.UNKNOWN_GAP, route.cause, "unexplored (notile) cells may hide a route — never a claimed dead end");
      Assertions.assertTrue(route.waypoints.isEmpty());
   }

   @Test
   void plannerSeesEntirelyUnknownSourceAsInvalidStart() throws Exception {
      MapFileTileSourceTest.MapCache cache = new MapFileTileSourceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      writeGrid(file, 4096L, "gfx/tiles/grass", new HashMap<>());
      MapFileTileSource src = MapFileTileSource.of(file, -77129852519518195L, Area.corn(Coord.of(0, 0), Coord.of(10, 10)));
      Route route = CoarseRoutePlanner.plan(src, Coord.of(2, 2), Coord.of(8, 8));
      Assertions.assertEquals(Status.INVALID_START, route.status);
   }

   private static final class MapCache implements ResCache {
      final Map<String, byte[]> data = new HashMap<>();
      int writes = 0;

      public OutputStream store(final String name) {
         return new ByteArrayOutputStream() {
            {
               Objects.requireNonNull(MapCache.this);
            }

            @Override
            public void close() throws IOException {
               super.close();
               synchronized (MapCache.this) {
                  MapCache.this.data.put(name, this.toByteArray());
                  MapCache.this.writes++;
               }
            }
         };
      }

      public InputStream fetch(String name) throws IOException {
         synchronized (this) {
            byte[] b = this.data.get(name);
            if (b == null) {
               throw new FileNotFoundException(name);
            } else {
               return new ByteArrayInputStream(b);
            }
         }
      }
   }
}
