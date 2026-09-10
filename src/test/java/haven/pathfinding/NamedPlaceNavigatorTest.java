package haven.pathfinding;

import auto.Bot;
import auto.Bot.BotAction;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
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
import haven.Resource.Saved;
import haven.pathfinding.CoarseRoutePlanner.Cause;
import haven.pathfinding.CoarseRoutePlanner.Route;
import haven.pathfinding.NamedPlaceNavigator.Location;
import haven.pathfinding.NamedPlaceNavigator.ReplanFact;
import haven.pathfinding.NamedPlaceNavigator.Run;
import haven.pathfinding.NamedPlaceNavigator.RunStatus;
import haven.pathfinding.NamedPlaceNavigator.SessionState;
import haven.pathfinding.NamedPlaceRouteService.Kind;
import haven.pathfinding.RecedingHorizonNavigator.Bounds;
import haven.pathfinding.RecedingHorizonNavigator.LegResult;
import haven.pathfinding.RecedingHorizonNavigator.LegWalker;
import haven.pathfinding.RecedingHorizonNavigator.LocalPlan;
import haven.pathfinding.RecedingHorizonNavigator.LocalPlanner;
import haven.pathfinding.RecedingHorizonNavigator.Outcome;
import haven.pathfinding.RecedingHorizonNavigator.TileMap;
import haven.pathfinding.RecedingHorizonNavigator.LocalPlan.Status;
import haven.pathfinding.WaypointWalker.Result;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class NamedPlaceNavigatorTest {
   private static final long SEG = 1311768467463790320L;
   private static final long OTHER_SEG = -77129852519518195L;
   private static final long G0 = 4096L;
   private static final long G1 = 4097L;
   private static final int BIG = 1000000;

   private static MapFile newFile(NamedPlaceNavigatorTest.MapCache cache) {
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

   private static MapFile fileWithGrassGrid(NamedPlaceNavigatorTest.MapCache cache) throws IOException {
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

   private static Location loc(long seg, int tx, int ty, double wx, double wy) {
      return new Location(seg, Coord.of(tx, ty), Coord2d.of(wx, wy));
   }

   private static LocalPlan plan(Status st, Coord2d... waypoints) {
      return new LocalPlan(st, Arrays.asList(waypoints));
   }

   private static LegResult success(Coord2d end) {
      return LegResult.success(end);
   }

   @Test
   void playerTileMatchesMapWndGobMarkerFormula() {
      Coord2d world = Coord2d.of(245.3, 118.9);
      Coord tr = Coord.of(12, -7);
      Coord tile = NamedPlaceNavigator.playerTile(world, tr);
      Assertions.assertEquals(new Coord(34, 3), tile, "245.3/11=22, 118.9/11=10, + (12,-7)");
      Assertions.assertEquals(world.add(tr.mul(MCache.tilesz)).floor(MCache.tilesz), tile, "must equal the GobMarker formula (rc + tr*tilesz).floor(tilesz)");
      Assertions.assertEquals(world.floor(MCache.tilesz).add(tr), tile, "must equal the MapLocator form: world tile + segment translation");
   }

   @Test
   void worldPosIsTileCenterWithMiniMapClickFormula() {
      Coord tr = Coord.of(12, -7);
      Coord2d w = NamedPlaceNavigator.worldPos(new Coord(42, 8), tr);
      Assertions.assertEquals(30.0 * MCache.tilesz.x + MCache.tilesz.x / 2.0, w.x, 1.0E-9);
      Assertions.assertEquals(15.0 * MCache.tilesz.y + MCache.tilesz.y / 2.0, w.y, 1.0E-9);
      Assertions.assertEquals(Coord2d.of(335.5, 170.5), w);
   }

   @Test
   void tileMapRoundTripsAndTranslationDerivesFromLocation() {
      for (Coord tr : new Coord[]{Coord.of(0, 0), Coord.of(12, -7), Coord.of(-100, 50)}) {
         TileMap tm = NamedPlaceNavigator.tileMap(tr);

         for (Coord t : new Coord[]{Coord.of(0, 0), Coord.of(34, 3), Coord.of(-25, 60)}) {
            Assertions.assertEquals(t, tm.toTile(tm.toWorld(t)), "round trip with translation " + tr);
            Coord2d inside = tm.toWorld(t).add(Coord2d.of(3.3, 4.9));
            Assertions.assertEquals(t, tm.toTile(inside), "in-tile offset with translation " + tr);
         }

         Location l = loc(1311768467463790320L, 34, 3, 245.3, 118.9);
         Assertions.assertEquals(Coord.of(12, -7), NamedPlaceNavigator.translation(l));
      }
   }

   @Test
   void unavailableSessionState_returnsUnavailableRun() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 30, 20, "Home");
      NamedPlaceNavigator nav = new NamedPlaceNavigator(
         new NamedPlaceRouteService(file),
         new NamedPlaceNavigatorTest.ScriptedState(),
         new NamedPlaceNavigatorTest.ScriptedLocal(),
         new NamedPlaceNavigatorTest.ScriptedWalker(),
         Bounds.DEFAULT
      );
      Run r = nav.navigate("Home", region(0, 0, 40, 40));
      Assertions.assertTrue(r.unavailable());
      Assertions.assertEquals(RunStatus.UNAVAILABLE, r.status);
      Assertions.assertNull(r.start);
      Assertions.assertNull(r.routeKind);
      Assertions.assertNull(r.navOutcome);
      Assertions.assertNull(r.endPos);
      Assertions.assertTrue(r.replanFacts.isEmpty());
      Assertions.assertFalse(r.routeRejected());
      Assertions.assertFalse(r.navigated());
      Assertions.assertFalse(r.reached());
      Assertions.assertFalse(r.cancelled());
      Assertions.assertTrue(r.elapsedMs >= 0L);
   }

   @Test
   void missingName_routeRejected() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 30, 20, "Home");
      Run r = navigator(
            file,
            new NamedPlaceNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)),
            new NamedPlaceNavigatorTest.ScriptedLocal(),
            new NamedPlaceNavigatorTest.ScriptedWalker()
         )
         .navigate("Nowhere", region(0, 0, 40, 40));
      Assertions.assertTrue(r.routeRejected());
      Assertions.assertEquals(Kind.DEST_MISSING, r.routeKind);
      Assertions.assertNull(r.place);
      Assertions.assertNull(r.navOutcome, "no controller run on a refused lookup");
      Assertions.assertEquals(0, r.legs);
      Assertions.assertEquals(0, r.replans);
      Assertions.assertEquals(Coord2d.of(22.0, 22.0), r.endPos, "no movement happened");
      Assertions.assertTrue(r.replanFacts.isEmpty());
      Assertions.assertFalse(r.reached());
   }

   @Test
   void ambiguousName_routeRejected() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 5, 7, "Home");
      mark(file, 1311768467463790320L, 30, 20, "Home");
      Run r = navigator(
            file,
            new NamedPlaceNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)),
            new NamedPlaceNavigatorTest.ScriptedLocal(),
            new NamedPlaceNavigatorTest.ScriptedWalker()
         )
         .navigate("Home", region(0, 0, 40, 40));
      Assertions.assertTrue(r.routeRejected());
      Assertions.assertEquals(Kind.DEST_AMBIGUOUS, r.routeKind);
      Assertions.assertNull(r.place, "never silently picked between duplicates");
      Assertions.assertTrue(r.replanFacts.isEmpty());
   }

   @Test
   void crossSegmentDestination_routeRejected_beforeAnyPlanning() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, -77129852519518195L, 10, 10, "FarAway");
      Run r = navigator(
            file,
            new NamedPlaceNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)),
            new NamedPlaceNavigatorTest.ScriptedLocal(),
            new NamedPlaceNavigatorTest.ScriptedWalker()
         )
         .navigate("FarAway", region(0, 0, 40, 40));
      Assertions.assertTrue(r.routeRejected());
      Assertions.assertEquals(Kind.CROSS_SEGMENT, r.routeKind, "same-segment refusal must come from the route service");
      Assertions.assertNotNull(r.place, "the resolved marker is reported");
      Assertions.assertEquals(-77129852519518195L, r.place.seg);
      Assertions.assertNull(r.navOutcome);
   }

   @Test
   void startOutsideExplicitBounds_routeRejected_invalidStart() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 10, 10, "Home");
      Run r = navigator(
            file,
            new NamedPlaceNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 45, 20, 500.0, 220.0)),
            new NamedPlaceNavigatorTest.ScriptedLocal(),
            new NamedPlaceNavigatorTest.ScriptedWalker()
         )
         .navigate("Home", region(0, 0, 40, 40));
      Assertions.assertTrue(r.routeRejected());
      Assertions.assertEquals(Kind.INVALID_START, r.routeKind, "a start outside the explicit planning bounds is a hard boundary, never clipped");
      Assertions.assertNull(r.navOutcome);
   }

   @Test
   void degenerateBounds_areACallerError() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 10, 10, "Home");
      NamedPlaceNavigator nav = navigator(
         file,
         new NamedPlaceNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)),
         new NamedPlaceNavigatorTest.ScriptedLocal(),
         new NamedPlaceNavigatorTest.ScriptedWalker()
      );
      Assertions.assertThrows(IllegalArgumentException.class, () -> nav.navigate("Home", region(5, 5, 5, 5)));
      Assertions.assertThrows(IllegalArgumentException.class, () -> nav.navigate("Home", region(6, 5, 5, 5)));
   }

   @Test
   void reachesNamedPlaceEndToEnd_withNonZeroSegmentTranslation() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 42, 8, "Home");
      NamedPlaceNavigatorTest.ScriptedLocal local = new NamedPlaceNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(245.3, 118.9), Coord2d.of(335.5, 170.5)));
      NamedPlaceNavigatorTest.ScriptedWalker walker = new NamedPlaceNavigatorTest.ScriptedWalker().walk(success(Coord2d.of(335.5, 170.5)));
      Run r = navigator(file, new NamedPlaceNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 34, 3, 245.3, 118.9)), local, walker)
         .navigate("Home", region(0, 0, 100, 100));
      Assertions.assertTrue(r.navigated());
      Assertions.assertTrue(r.reached());
      Assertions.assertEquals(Outcome.REACHED_DESTINATION, r.navOutcome);
      Assertions.assertEquals(Kind.REACHED, r.routeKind);
      Assertions.assertNotNull(r.place);
      Assertions.assertEquals(new Coord(42, 8), r.place.tc);
      Assertions.assertEquals(2, r.waypointCount, "open ground collapses to start + goal");
      Assertions.assertTrue(r.expanded > 0);
      Assertions.assertTrue(r.markerseq > 0L);
      Assertions.assertEquals(1, r.legs);
      Assertions.assertEquals(0, r.replans);
      Assertions.assertEquals(2, r.routeIndex, "the coarse route was advanced past its final waypoint");
      Assertions.assertNull(r.detail);
      Assertions.assertEquals(0.0, r.endPos.dist(Coord2d.of(335.5, 170.5)), 1.0E-9, "end position is the walker-verified arrival, translated to world space");
      Assertions.assertEquals(new Coord(34, 3), r.start.tile, "run facts carry the canonical absolute start tile");
      Assertions.assertEquals(1311768467463790320L, r.start.seg);
      Assertions.assertTrue(r.replanFacts.isEmpty());
      Assertions.assertEquals(Coord2d.of(335.5, 170.5), local.targets.get(0));
      Assertions.assertEquals(Coord2d.of(245.3, 118.9), local.froms.get(0), "the first leg starts at the player");
   }

   @Test
   void alreadyAtGoalTile_reachedWithZeroLegs() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 7, 7, "Here");
      Run r = navigator(
            file,
            new NamedPlaceNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 7, 7, 77.0, 77.0)),
            new NamedPlaceNavigatorTest.ScriptedLocal(),
            new NamedPlaceNavigatorTest.ScriptedWalker()
         )
         .navigate("Here", region(0, 0, 40, 40));
      Assertions.assertTrue(r.reached());
      Assertions.assertEquals(0, r.legs);
      Assertions.assertEquals(0, r.replans);
      Assertions.assertEquals(1, r.waypointCount, "start-equals-goal is a single-waypoint REACHED route");
      Assertions.assertEquals(Coord2d.of(77.0, 77.0), r.endPos);
   }

   @Test
   void midRunSegmentCrossing_replanFailsTypedWithCrossSegmentFact() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 30, 20, "Home");
      NamedPlaceNavigatorTest.ScriptedLocal local = new NamedPlaceNavigatorTest.ScriptedLocal().plan(plan(Status.FAILED));
      NamedPlaceNavigatorTest.ScriptedWalker walker = new NamedPlaceNavigatorTest.ScriptedWalker();
      NamedPlaceNavigatorTest.ScriptedState state = new NamedPlaceNavigatorTest.ScriptedState()
         .loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0))
         .loc(loc(-77129852519518195L, 105, 2, 1155.0, 22.0));
      Run r = navigator(file, state, local, walker).navigate("Home", region(0, 0, 40, 40));
      Assertions.assertTrue(r.navigated());
      Assertions.assertEquals(Outcome.COARSE_PLAN_FAILED, r.navOutcome);
      Assertions.assertTrue(r.detail.contains("CROSS_SEGMENT"), r.detail);
      Assertions.assertEquals(0, r.legs, "the failed local plan has nothing to walk");
      Assertions.assertEquals(1, r.replans);
      Assertions.assertEquals(1, r.replanFacts.size());
      Assertions.assertEquals(Kind.CROSS_SEGMENT, ((ReplanFact)r.replanFacts.get(0)).kind, "the true refusal kind survives in the run facts");
      Assertions.assertEquals(1, ((ReplanFact)r.replanFacts.get(0)).index);
      Assertions.assertFalse(r.reached());
   }

   @Test
   void midRunMarkerRemoved_replanFactsCarryDestMissing() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      final MapFile file = fileWithGrassGrid(cache);
      final Marker home = mark(file, 1311768467463790320L, 30, 20, "Home");
      NamedPlaceNavigatorTest.ScriptedLocal local = (new NamedPlaceNavigatorTest.ScriptedLocal() {
         {
            Objects.requireNonNull(NamedPlaceNavigatorTest.this);
         }

         @Override
         public LocalPlan plan(Coord2d from, Coord2d target) {
            file.lock.writeLock().lock();

            try {
               file.markers.remove(home);
               file.markerseq++;
            } finally {
               file.lock.writeLock().unlock();
            }

            return super.plan(from, target);
         }
      }).plan(plan(Status.FAILED));
      NamedPlaceNavigatorTest.ScriptedWalker walker = new NamedPlaceNavigatorTest.ScriptedWalker();
      Run r = navigator(file, new NamedPlaceNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)), local, walker)
         .navigate("Home", region(0, 0, 40, 40));
      Assertions.assertTrue(r.navigated());
      Assertions.assertEquals(Outcome.COARSE_PLAN_FAILED, r.navOutcome);
      Assertions.assertEquals(1, r.replanFacts.size());
      Assertions.assertEquals(Kind.DEST_MISSING, ((ReplanFact)r.replanFacts.get(0)).kind, "the marker-set change surfaces typed, not as a stale success");
      Assertions.assertEquals(Kind.REACHED, r.routeKind, "the initial lookup was fine before the marker vanished");
      Assertions.assertFalse(r.reached());
   }

   @Test
   void walkerFailure_isTypedTerminal() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 30, 20, "Home");
      NamedPlaceNavigatorTest.ScriptedLocal local = new NamedPlaceNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(22.0, 22.0), Coord2d.of(335.5, 225.5)));
      NamedPlaceNavigatorTest.ScriptedWalker walker = new NamedPlaceNavigatorTest.ScriptedWalker()
         .walk(LegResult.failed("walker REJECTED", Coord2d.of(150.0, 100.0)));
      Run r = navigator(file, new NamedPlaceNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)), local, walker)
         .navigate("Home", region(0, 0, 40, 40));
      Assertions.assertTrue(r.navigated());
      Assertions.assertEquals(Outcome.TERMINAL_FAILURE, r.navOutcome);
      Assertions.assertEquals("walker REJECTED", r.detail);
      Assertions.assertEquals(Coord2d.of(150.0, 100.0), r.endPos, "the walker's last known position survives the terminal failure");
      Assertions.assertEquals(1, r.legs);
      Assertions.assertFalse(r.reached());
   }

   @Test
   void walkerCancellation_isTypedAndPreserved() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 30, 20, "Home");
      NamedPlaceNavigatorTest.ScriptedLocal local = new NamedPlaceNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(22.0, 22.0), Coord2d.of(335.5, 225.5)));
      NamedPlaceNavigatorTest.ScriptedWalker walker = new NamedPlaceNavigatorTest.ScriptedWalker()
         .walk(LegResult.cancelled("bot cancelled", Coord2d.of(60.0, 40.0)));
      Run r = navigator(file, new NamedPlaceNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)), local, walker)
         .navigate("Home", region(0, 0, 40, 40));
      Assertions.assertTrue(r.cancelled());
      Assertions.assertEquals(Outcome.CANCELLED, r.navOutcome);
      Assertions.assertEquals("bot cancelled", r.detail, "the walker's opaque cancellation detail is preserved");
      Assertions.assertEquals(Coord2d.of(60.0, 40.0), r.endPos, "partial leg progress is not discarded on cancellation");
      Assertions.assertEquals(1, r.legs);
      Assertions.assertFalse(r.reached());
   }

   @Test
   void walkerResultMapping_mapsEveryWaypointWalkerResult() {
      Coord2d end = Coord2d.of(10.0, 20.0);
      Coord2d fallback = Coord2d.of(0.0, 0.0);
      LegResult arr = NamedPlaceNavigator.walkerResult(Result.ARRIVED, end, fallback);
      Assertions.assertEquals(haven.pathfinding.RecedingHorizonNavigator.LegResult.Outcome.SUCCESS, arr.outcome);
      Assertions.assertEquals(end, arr.end);
      Assertions.assertEquals(fallback, NamedPlaceNavigator.walkerResult(Result.ARRIVED, null, fallback).end);

      for (Result r : new Result[]{Result.REJECTED, Result.TIMEOUT}) {
         LegResult lr = NamedPlaceNavigator.walkerResult(r, end, fallback);
         Assertions.assertEquals(haven.pathfinding.RecedingHorizonNavigator.LegResult.Outcome.FAILED, lr.outcome, r.toString());
         Assertions.assertEquals("walker " + r, lr.failure);
         Assertions.assertEquals(end, lr.end);
      }
      Assertions.assertEquals(
         haven.pathfinding.RecedingHorizonNavigator.LegResult.Outcome.STOPPED_EARLY,
         NamedPlaceNavigator.walkerResult(Result.SHORT_STOP, end, fallback).outcome
      );
      Assertions.assertEquals(
         haven.pathfinding.RecedingHorizonNavigator.LegResult.Outcome.SUCCESS,
         NamedPlaceNavigator.walkerResult(Result.READY_TO_INTERACT, end, fallback).outcome
      );
      Assertions.assertEquals(
         haven.pathfinding.RecedingHorizonNavigator.LegResult.Outcome.STUCK,
         NamedPlaceNavigator.walkerResult(Result.STUCK, end, fallback).outcome
      );

      LegResult canc = NamedPlaceNavigator.walkerCancelled(new InterruptedException("Waypoint walk cancelled"), end, fallback);
      Assertions.assertEquals(haven.pathfinding.RecedingHorizonNavigator.LegResult.Outcome.CANCELLED, canc.outcome);
      Assertions.assertEquals("Waypoint walk cancelled", canc.failure, "the walker's abort detail survives");
      LegResult bare = NamedPlaceNavigator.walkerCancelled(new InterruptedException(), null, fallback);
      Assertions.assertEquals("bot cancelled", bare.failure);
      Assertions.assertEquals(fallback, bare.end);
   }

   @Test
   void toCoarseRoute_mapsEveryServiceKind() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 30, 20, "Home");
      mark(file, -77129852519518195L, 10, 10, "FarAway");
      NamedPlaceRouteService svc = new NamedPlaceRouteService(file);
      haven.pathfinding.NamedPlaceRouteService.Result ok = svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), 1000000);
      Route cr = NamedPlaceNavigator.toCoarseRoute(ok);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.REACHED, cr.status);
      Assertions.assertEquals(ok.waypoints, cr.waypoints, "REACHED keeps the service's waypoints");
      Assertions.assertEquals(ok.waypoints.get(0), cr.start);
      Assertions.assertEquals(ok.waypoints.get(ok.waypoints.size() - 1), cr.goal);
      haven.pathfinding.NamedPlaceRouteService.Result xseg = svc.route(1311768467463790320L, Coord.of(2, 2), "FarAway", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.CROSS_SEGMENT, xseg.kind);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.CROSS_SEGMENT, NamedPlaceNavigator.toCoarseRoute(xseg).status);
      haven.pathfinding.NamedPlaceRouteService.Result missing = svc.route(1311768467463790320L, Coord.of(2, 2), "Nope", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(
         haven.pathfinding.CoarseRoutePlanner.Status.INVALID_GOAL,
         NamedPlaceNavigator.toCoarseRoute(missing).status,
         "a vanished/ambiguous goal is not a single known tile -> INVALID_GOAL"
      );
      haven.pathfinding.NamedPlaceRouteService.Result ex = svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), 3);
      Assertions.assertEquals(Kind.EXHAUSTED, ex.kind);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.EXHAUSTED, NamedPlaceNavigator.toCoarseRoute(ex).status);
      haven.pathfinding.NamedPlaceRouteService.Result ist = svc.route(1311768467463790320L, Coord.of(45, 20), "Home", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.INVALID_START, ist.kind);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.INVALID_START, NamedPlaceNavigator.toCoarseRoute(ist).status);
      mark(file, 1311768467463790320L, 8, 9, "Home");
      haven.pathfinding.NamedPlaceRouteService.Result amb = svc.route(1311768467463790320L, Coord.of(2, 2), "Home", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.DEST_AMBIGUOUS, amb.kind);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.INVALID_GOAL, NamedPlaceNavigator.toCoarseRoute(amb).status);
      NamedPlaceNavigatorTest.MapCache wallCache = new NamedPlaceNavigatorTest.MapCache();
      MapFile wallFile = fileWithGrassGrid(wallCache);
      Map<Coord, String> wall = new HashMap<>();

      for (int y = 0; y < 40; y++) {
         wall.put(Coord.of(20, y), "gfx/tiles/cave");
      }

      writeGrid(wallFile, 4096L, "gfx/tiles/grass", wall);
      mark(wallFile, 1311768467463790320L, 35, 20, "Beyond");
      NamedPlaceRouteService wallSvc = new NamedPlaceRouteService(wallFile);
      haven.pathfinding.NamedPlaceRouteService.Result nore = wallSvc.route(1311768467463790320L, Coord.of(2, 2), "Beyond", region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(Kind.NO_KNOWN_ROUTE, nore.kind);
      Route nr = NamedPlaceNavigator.toCoarseRoute(nore);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.NO_KNOWN_ROUTE, nr.status);
      Assertions.assertEquals(Cause.KNOWN_BLOCKED, nr.cause);
   }

   @Test
   void routeFactories_validateInputs() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> Route.reached(null, 0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> Route.reached(Collections.emptyList(), 0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> Route.failed(haven.pathfinding.CoarseRoutePlanner.Status.REACHED, null));
      Route single = Route.reached(Collections.singletonList(Coord.of(7, 7)), 0);
      Assertions.assertTrue(single.reached());
      Assertions.assertEquals(new Coord(7, 7), single.start);
      Assertions.assertEquals(new Coord(7, 7), single.goal);
      Assertions.assertEquals(0, single.expanded);
      Route xseg = Route.failed(haven.pathfinding.CoarseRoutePlanner.Status.CROSS_SEGMENT, null);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.CROSS_SEGMENT, xseg.status);
      Assertions.assertFalse(xseg.reached());
      Assertions.assertTrue(xseg.waypoints.isEmpty());
   }

   @Test
   void liveAdapterNeverStartsTheBot() throws Exception {
      NamedPlaceNavigatorTest.MapCache cache = new NamedPlaceNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      mark(file, 1311768467463790320L, 30, 20, "Home");
      Bot bot = Bot.execute(new BotAction[0]);
      NamedPlaceNavigator nav = NamedPlaceNavigator.live(null, file, bot);
      Run r = nav.navigate("Home", region(0, 0, 40, 40));
      Assertions.assertTrue(r.unavailable());
      Assertions.assertNull(bot.ui(), "the adapter must never start the caller's bot");
      Assertions.assertFalse(Bot.hasCurrent(), "no bot may have been registered as current");
   }

   @Test
   void nullSeamsRejected() {
      NamedPlaceRouteService svc = new NamedPlaceRouteService(newFile(new NamedPlaceNavigatorTest.MapCache()));
      NamedPlaceNavigatorTest.ScriptedState state = new NamedPlaceNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0));
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new NamedPlaceNavigator(null, state, new NamedPlaceNavigatorTest.ScriptedLocal(), new NamedPlaceNavigatorTest.ScriptedWalker(), Bounds.DEFAULT)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new NamedPlaceNavigator(svc, null, new NamedPlaceNavigatorTest.ScriptedLocal(), new NamedPlaceNavigatorTest.ScriptedWalker(), Bounds.DEFAULT)
      );
      Assertions.assertThrows(
         NullPointerException.class, () -> new NamedPlaceNavigator(svc, state, null, new NamedPlaceNavigatorTest.ScriptedWalker(), Bounds.DEFAULT)
      );
      Assertions.assertThrows(
         NullPointerException.class, () -> new NamedPlaceNavigator(svc, state, new NamedPlaceNavigatorTest.ScriptedLocal(), null, Bounds.DEFAULT)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new NamedPlaceNavigator(svc, state, new NamedPlaceNavigatorTest.ScriptedLocal(), new NamedPlaceNavigatorTest.ScriptedWalker(), null)
      );
      Assertions.assertThrows(NullPointerException.class, () -> NamedPlaceNavigator.live(null, null, Bot.execute(new BotAction[0])));
      Assertions.assertThrows(NullPointerException.class, () -> NamedPlaceNavigator.liveWalker(null, null, 1000L, NamedPlaceNavigator.NOOP));
   }

   @Test
   void nullTransformArgsRejected() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> NamedPlaceNavigator.playerTile(null, Coord.of(0, 0)));
      Assertions.assertThrows(IllegalArgumentException.class, () -> NamedPlaceNavigator.playerTile(Coord2d.of(0.0, 0.0), null));
      Assertions.assertThrows(IllegalArgumentException.class, () -> NamedPlaceNavigator.worldPos(null, Coord.of(0, 0)));
      Assertions.assertThrows(IllegalArgumentException.class, () -> NamedPlaceNavigator.worldPos(Coord.of(0, 0), null));
      Assertions.assertThrows(NullPointerException.class, () -> new Location(1311768467463790320L, null, Coord2d.of(0.0, 0.0)));
      Assertions.assertThrows(NullPointerException.class, () -> new Location(1311768467463790320L, Coord.of(0, 0), null));
   }

   private static NamedPlaceNavigator navigator(MapFile file, SessionState state, LocalPlanner local, LegWalker walker) {
      return new NamedPlaceNavigator(new NamedPlaceRouteService(file), state, local, walker, Bounds.DEFAULT);
   }

   private static final class MapCache implements ResCache {
      final Map<String, byte[]> data = new HashMap<>();

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

   private static class ScriptedLocal implements LocalPlanner {
      final List<LocalPlan> script = new ArrayList<>();
      final List<Coord2d> froms = new ArrayList<>();
      final List<Coord2d> targets = new ArrayList<>();
      int at = 0;

      NamedPlaceNavigatorTest.ScriptedLocal plan(LocalPlan p) {
         this.script.add(p);
         return this;
      }

      public LocalPlan plan(Coord2d from, Coord2d target) {
         this.froms.add(from);
         this.targets.add(target);
         LocalPlan p = this.at < this.script.size() ? this.script.get(this.at) : this.script.get(this.script.size() - 1);
         this.at++;
         return p;
      }
   }

   private static final class ScriptedState implements SessionState {
      final List<Location> script = new ArrayList<>();
      int at = 0;

      NamedPlaceNavigatorTest.ScriptedState loc(Location l) {
         this.script.add(l);
         return this;
      }

      public Location current() {
         if (this.script.isEmpty()) {
            return null;
         } else {
            Location l = this.at < this.script.size() ? this.script.get(this.at) : this.script.get(this.script.size() - 1);
            this.at++;
            return l;
         }
      }
   }

   private static final class ScriptedWalker implements LegWalker {
      final List<LegResult> script = new ArrayList<>();
      int at = 0;

      NamedPlaceNavigatorTest.ScriptedWalker walk(LegResult r) {
         this.script.add(r);
         return this;
      }

      public LegResult walk(Coord2d from, LocalPlan plan) {
         LegResult r = this.at < this.script.size() ? this.script.get(this.at) : this.script.get(this.script.size() - 1);
         this.at++;
         return r;
      }
   }
}
