package haven.pathfinding;

import haven.Area;
import haven.Coord;
import haven.GOut;
import haven.MCache;
import haven.MapFile;
import haven.ResCache;
import haven.Resource;
import haven.StreamMessage;
import haven.Text;
import haven.ZMessage;
import haven.MapFile.Grid;
import haven.MapFile.Marker;
import haven.MapFile.TileInfo;
import haven.NamedPlaceResolver.Place;
import haven.Resource.Saved;
import haven.pathfinding.CoarseRoutePlanner.Cause;
import haven.pathfinding.CoarseTileSource.Tile;
import haven.pathfinding.NamedPlaceRouteService.Kind;
import haven.pathfinding.NamedPlaceRouteService.Request;
import haven.pathfinding.NamedPlaceRouteService.Result;
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

public class NamedPlaceRouteServiceTest {
   private static final long SEG = 1311768467463790320L;
   private static final long OTHER_SEG = -77129852519518195L;
   private static final long G0 = 4096L;
   private static final long G1 = 4097L;
   private static final long G2 = 4098L;
   private static final long G3 = 4099L;
   private static final int BIG = 1000000;

   private static MapFile newFile(NamedPlaceRouteServiceTest.MapCache cache) {
      return new MapFile(cache, "fixture");
   }

   private static Marker mark(MapFile f, long seg, int x, int y, String nm) {
      Marker m = new Marker(f, seg, new Coord(x, y), nm) {
         public void draw(GOut g, Coord c, Text tip, float scale, MapFile file) {
         }

         public Area area() {
            return null;
         }
      };
      f.lock.writeLock().lock();

      try {
         f.markers.add(m);
         f.markerseq++;
      } finally {
         f.lock.writeLock().unlock();
      }

      return m;
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

   private static MapFile fileWithGrassGrid(NamedPlaceRouteServiceTest.MapCache cache) throws IOException {
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      writeGrid(file, 4096L, "gfx/tiles/grass", new HashMap<>());
      return file;
   }

   private static Area region(int x0, int y0, int x1, int y1) {
      return Area.corn(Coord.of(x0, y0), Coord.of(x1, y1));
   }

   @Test
   void exactRoute_openGroundCollapsesToStartAndGoal() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 30, 20, "Home");
      NamedPlaceRouteService svc = new NamedPlaceRouteService(file);
      Result r = svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.REACHED, r.kind);
      Assertions.assertTrue(r.reached());
      Assertions.assertNotNull(r.place);
      Assertions.assertEquals("Home", r.place.name);
      Assertions.assertEquals(1311768467463790320L, r.place.seg);
      Assertions.assertEquals(new Coord(30, 20), r.place.tc);
      Assertions.assertEquals(new Coord(2, 2), r.waypoints.get(0));
      Assertions.assertEquals(new Coord(30, 20), r.waypoints.get(r.waypoints.size() - 1));
      Assertions.assertEquals(2, r.waypoints.size(), "open known ground must simplify to exactly start and goal");
      Assertions.assertEquals(r.place.tc, r.waypoints.get(r.waypoints.size() - 1), "the final waypoint must be the resolved marker tile");
      Assertions.assertTrue(r.expanded > 0);
      Assertions.assertNull(r.candidates);
      Assertions.assertNull(r.cause);
   }

   @Test
   void exactRoute_detoursAroundKnownBlockingTerrain_waypointsAllFree() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      Map<Coord, String> ov = new HashMap<>();

      for (int y = 6; y <= 33; y++) {
         ov.put(Coord.of(20, y), "gfx/tiles/cave");
      }

      writeGrid(file, 4096L, "gfx/tiles/grass", ov);
      mark(file, 1311768467463790320L, 35, 20, "Mine");
      NamedPlaceRouteService svc = new NamedPlaceRouteService(file);
      Result r = svc.route(1311768467463790320L, Coord.of(2, 20), "Mine", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.REACHED, r.kind);
      Assertions.assertEquals(new Coord(2, 20), r.waypoints.get(0));
      Assertions.assertEquals(new Coord(35, 20), r.waypoints.get(r.waypoints.size() - 1));
      Assertions.assertTrue(r.waypoints.size() >= 3, "the known cave wall must force a detour");
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, region(0, 0, 40, 40));

      for (Coord w : r.waypoints) {
         Assertions.assertEquals(Tile.FREE, src.tile(w.x, w.y), "every coarse waypoint must be a known-free tile: " + w);
      }

      Assertions.assertTrue(r.waypoints.stream().anyMatch(wx -> wx.y < 6 || wx.y > 33), "detour waypoints must go around the wall, not through it");
   }

   @Test
   void nonOriginBounds_waypointsAreInSegmentAbsoluteTiles() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 80, 60, "Camp");
      Result r = new NamedPlaceRouteService(file).route(1311768467463790320L, Coord.of(52, 32), "Camp", region(50, 30, 90, 70), 1000000);
      Assertions.assertEquals(Kind.REACHED, r.kind);
      Assertions.assertEquals(new Coord(52, 32), r.waypoints.get(0), "start waypoint in absolute tiles");
      Assertions.assertEquals(new Coord(80, 60), r.waypoints.get(r.waypoints.size() - 1), "goal waypoint in absolute tiles (Place.tc), not region-local");
      Assertions.assertEquals(2, r.waypoints.size());
      Assertions.assertEquals(r.place.tc, r.waypoints.get(r.waypoints.size() - 1));
   }

   @Test
   void startEqualsGoalTile_reachedWithSingleWaypoint() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 7, 7, "Here");
      NamedPlaceRouteService svc = new NamedPlaceRouteService(file);

      for (int budget : new int[]{1000000, 0, -1}) {
         Result r = svc.route(1311768467463790320L, Coord.of(7, 7), "Here", region(0, 0, 40, 40), budget);
         Assertions.assertEquals(Kind.REACHED, r.kind, "budget=" + budget);
         Assertions.assertEquals(1, r.waypoints.size());
         Assertions.assertEquals(new Coord(7, 7), r.waypoints.get(0));
         Assertions.assertEquals(0, r.expanded);
      }
   }

   @Test
   void negativeCoordinateBounds_waypointsRemainAbsoluteInSegmentTiles() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(-1, -1), 4098L);
      grids.put(Coord.of(-1, 0), 4099L);
      writeSegment(file, 1311768467463790320L, grids);
      writeGrid(file, 4098L, "gfx/tiles/grass", new HashMap<>());
      writeGrid(file, 4099L, "gfx/tiles/grass", new HashMap<>());
      mark(file, 1311768467463790320L, -30, -5, "Camp");
      Result r = new NamedPlaceRouteService(file).route(1311768467463790320L, Coord.of(-48, -28), "Camp", region(-50, -30, -10, 10), 1000000);
      Assertions.assertEquals(Kind.REACHED, r.kind);
      MapFileTileSource src = MapFileTileSource.of(file, 1311768467463790320L, region(-50, -30, -10, 10));
      Assertions.assertEquals(Tile.FREE, src.tile(0, 0), "negative-y grid must read as known free terrain");
      Assertions.assertEquals(Tile.FREE, src.tile(25, 35), "cross-grid (y>=0) tile must read as known free terrain");
      Assertions.assertEquals(new Coord(-48, -28), r.waypoints.get(0), "start waypoint in negative absolute tiles");
      Assertions.assertEquals(
         new Coord(-30, -5), r.waypoints.get(r.waypoints.size() - 1), "goal waypoint in negative absolute tiles (Place.tc), not region-local"
      );
      Assertions.assertEquals(2, r.waypoints.size());
      Assertions.assertEquals(r.place.tc, r.waypoints.get(r.waypoints.size() - 1));
   }

   @Test
   void duplicateNames_ambiguous_neverPlans() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 5, 7, "Home");
      mark(file, 1311768467463790320L, 30, 20, "Home");
      Result r = new NamedPlaceRouteService(file).route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.DEST_AMBIGUOUS, r.kind);
      Assertions.assertTrue(r.destinationAmbiguous());
      Assertions.assertNull(r.place, "no silent pick between duplicates");
      Assertions.assertTrue(r.waypoints.isEmpty(), "no planning from an ambiguous name");
      Assertions.assertEquals(0, r.expanded);
      Assertions.assertNull(r.cause);
      Assertions.assertEquals(2, r.candidates.size());
      Assertions.assertEquals(new Coord(5, 7), ((Place)r.candidates.get(0)).tc);
      Assertions.assertEquals(new Coord(30, 20), ((Place)r.candidates.get(1)).tc);
   }

   @Test
   void missingName_destMissing() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 5, 7, "Home");
      NamedPlaceRouteService svc = new NamedPlaceRouteService(file);

      for (String name : new String[]{"Nowhere", null, ""}) {
         Result r = svc.route(1311768467463790320L, Coord.of(2, 2), name, region(0, 0, 40, 40), 1000000);
         Assertions.assertEquals(Kind.DEST_MISSING, r.kind, "name=" + name);
         Assertions.assertTrue(r.destinationMissing());
         Assertions.assertNull(r.place);
         Assertions.assertNull(r.candidates);
         Assertions.assertNull(r.cause);
         Assertions.assertTrue(r.waypoints.isEmpty(), "no planning without a resolved destination");
         Assertions.assertEquals(0, r.expanded);
      }
   }

   @Test
   void crossSegmentDestination_refusedEvenWithTerrainThere() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      Map<Coord, Long> otherGrids = new HashMap<>();
      otherGrids.put(Coord.of(0, 0), 4097L);
      writeSegment(file, -77129852519518195L, otherGrids);
      writeGrid(file, 4097L, "gfx/tiles/grass", new HashMap<>());
      mark(file, -77129852519518195L, 10, 10, "FarAway");
      Result r = new NamedPlaceRouteService(file).route(1311768467463790320L, Coord.of(2, 2), "FarAway", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.CROSS_SEGMENT, r.kind);
      Assertions.assertTrue(r.crossSegment());
      Assertions.assertTrue(r.waypoints.isEmpty(), "cross-segment destinations are not yet supported — never planned");
      Assertions.assertEquals(0, r.expanded);
      Assertions.assertNotNull(r.place, "the resolved place is reported so the caller can see where it is");
      Assertions.assertEquals(-77129852519518195L, r.place.seg);
      Assertions.assertEquals(new Coord(10, 10), r.place.tc);
   }

   @Test
   void unknownGap_noKnownRouteWithUnknownCause() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      Map<Coord, String> ov = new HashMap<>();

      for (int y = 0; y < 40; y++) {
         ov.put(Coord.of(20, y), "gfx/tiles/notile");
      }

      writeGrid(file, 4096L, "gfx/tiles/grass", ov);
      mark(file, 1311768467463790320L, 35, 20, "Beyond");
      Result r = new NamedPlaceRouteService(file).route(1311768467463790320L, Coord.of(2, 20), "Beyond", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.NO_KNOWN_ROUTE, r.kind);
      Assertions.assertTrue(r.noKnownRoute());
      Assertions.assertEquals(Cause.UNKNOWN_GAP, r.cause, "unexplored cells may hide a route — never a claimed dead end");
      Assertions.assertTrue(r.waypoints.isEmpty());
      Assertions.assertTrue(r.expanded > 0);
   }

   @Test
   void knownBlocked_noKnownRouteWithBlockedCause() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      Map<Coord, String> ov = new HashMap<>();

      for (int y = 0; y < 40; y++) {
         ov.put(Coord.of(20, y), "gfx/tiles/cave");
      }

      writeGrid(file, 4096L, "gfx/tiles/grass", ov);
      mark(file, 1311768467463790320L, 35, 20, "Beyond");
      Result r = new NamedPlaceRouteService(file).route(1311768467463790320L, Coord.of(2, 20), "Beyond", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.NO_KNOWN_ROUTE, r.kind);
      Assertions.assertEquals(Cause.KNOWN_BLOCKED, r.cause, "known blocking terrain seals the region — a proven dead end");
      Assertions.assertTrue(r.waypoints.isEmpty());
   }

   @Test
   void goalOutsideExplicitBounds_invalidGoal() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 45, 20, "Home");
      Result r = new NamedPlaceRouteService(file).route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.INVALID_GOAL, r.kind);
      Assertions.assertTrue(r.waypoints.isEmpty());
      Assertions.assertEquals(0, r.expanded);
      Assertions.assertNotNull(r.place, "the resolved marker is still reported");
   }

   @Test
   void startOutsideExplicitBounds_invalidStart() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 10, 10, "Home");
      Result r = new NamedPlaceRouteService(file).route(1311768467463790320L, Coord.of(45, 20), "Home", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.INVALID_START, r.kind);
      Assertions.assertTrue(r.waypoints.isEmpty());
      Assertions.assertEquals(0, r.expanded);
   }

   @Test
   void goalOnKnownBlockedTile_invalidGoal() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      Map<Coord, String> ov = new HashMap<>();
      ov.put(Coord.of(20, 20), "gfx/tiles/cave");
      writeGrid(file, 4096L, "gfx/tiles/grass", ov);
      mark(file, 1311768467463790320L, 20, 20, "Home");
      Result r = new NamedPlaceRouteService(file).route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.INVALID_GOAL, r.kind, "a destination on known blocking terrain is an invalid goal, never a route");
      Assertions.assertTrue(r.waypoints.isEmpty());
   }

   @Test
   void startInUnpersistedGrid_invalidStart() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 110, 10, "Home");
      Result r = new NamedPlaceRouteService(file).route(1311768467463790320L, Coord.of(102, 2), "Home", region(100, 0, 140, 40), 1000000);
      Assertions.assertEquals(Kind.INVALID_START, r.kind, "an unknown start tile must be invalid, never routed from");
      Assertions.assertTrue(r.waypoints.isEmpty());
      Assertions.assertEquals(0, r.expanded);
   }

   @Test
   void exhaustedBudget_expandedExactlyAtBound() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 90, 90, "Home");
      Result r = new NamedPlaceRouteService(file).route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 100, 100), 5);
      Assertions.assertEquals(Kind.EXHAUSTED, r.kind);
      Assertions.assertTrue(r.exhausted());
      Assertions.assertEquals(5, r.expanded, "the search must stop exactly at the budget");
      Assertions.assertTrue(r.waypoints.isEmpty());
      Assertions.assertNull(r.cause);
   }

   @Test
   void nonPositiveBudget_exhaustedImmediately() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 10, 10, "Home");
      NamedPlaceRouteService svc = new NamedPlaceRouteService(file);

      for (int budget : new int[]{0, -1}) {
         Result r = svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), budget);
         Assertions.assertEquals(Kind.EXHAUSTED, r.kind, "budget=" + budget);
         Assertions.assertEquals(0, r.expanded);
         Assertions.assertTrue(r.waypoints.isEmpty());
      }
   }

   @Test
   void nullRequestAndNullFieldsRejected() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      NamedPlaceRouteService svc = new NamedPlaceRouteService(file);
      Assertions.assertThrows(NullPointerException.class, () -> svc.route((Request)null));
      Assertions.assertThrows(NullPointerException.class, () -> new Request(1311768467463790320L, null, "Home", region(0, 0, 40, 40), 1000000));
      Assertions.assertThrows(NullPointerException.class, () -> new Request(1311768467463790320L, Coord.of(0, 0), "Home", null, 1000000));
      Assertions.assertThrows(NullPointerException.class, () -> new NamedPlaceRouteService(null));
   }

   @Test
   void degenerateBoundsRejected() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 10, 10, "Home");
      NamedPlaceRouteService svc = new NamedPlaceRouteService(file);
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(5, 5, 5, 5), 1000000), "empty region"
      );
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(6, 5, 5, 5), 1000000), "inverted x"
      );
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(5, 6, 5, 5), 1000000), "inverted y"
      );
   }

   @Test
   void routingNeverWritesPersistence() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 30, 20, "Home");
      NamedPlaceRouteService svc = new NamedPlaceRouteService(file);
      int writesBefore = cache.writes;
      svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), 1000000);
      svc.route(1311768467463790320L, Coord.of(2, 2), "Nowhere", region(0, 0, 40, 40), 1000000);
      svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), 3);
      Assertions.assertEquals(writesBefore, cache.writes, "routing must be read-only");

      for (String key : cache.data.keySet()) {
         Assertions.assertFalse(key.contains("zgrid"), "no derived zoom grids may be written: " + key);
      }
   }

   @Test
   void markerseqExposedForStalenessDetection() throws Exception {
      NamedPlaceRouteServiceTest.MapCache cache = new NamedPlaceRouteServiceTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 30, 20, "Home");
      NamedPlaceRouteService svc = new NamedPlaceRouteService(file);
      long before = svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), 1000000).markerseq;
      mark(file, 1311768467463790320L, 8, 9, "NewPlace");
      long after = svc.route(1311768467463790320L, Coord.of(2, 2), "NewPlace", region(0, 0, 40, 40), 1000000).markerseq;
      Assertions.assertTrue(after > before, "markerseq must advance when the marker set changes");
      Assertions.assertEquals(
         after, svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), 1000000).markerseq, "stable marker set ⇒ stable markerseq"
      );
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
