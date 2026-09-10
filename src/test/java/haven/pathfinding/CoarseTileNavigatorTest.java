package haven.pathfinding;

import auto.Bot;
import auto.Bot.BotAction;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
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
import haven.pathfinding.CoarseTileNavigator.CoarsePlanner;
import haven.pathfinding.CoarseTileNavigator.ReplanFact;
import haven.pathfinding.CoarseTileNavigator.Run;
import haven.pathfinding.CoarseTileNavigator.RunStatus;
import haven.pathfinding.NamedPlaceNavigator.Location;
import haven.pathfinding.NamedPlaceNavigator.SessionState;
import haven.pathfinding.RecedingHorizonNavigator.Bounds;
import haven.pathfinding.RecedingHorizonNavigator.LegResult;
import haven.pathfinding.RecedingHorizonNavigator.LegWalker;
import haven.pathfinding.RecedingHorizonNavigator.LocalPlan;
import haven.pathfinding.RecedingHorizonNavigator.LocalPlanner;
import haven.pathfinding.RecedingHorizonNavigator.Outcome;
import haven.pathfinding.RecedingHorizonNavigator.LocalPlan.Status;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CoarseTileNavigatorTest {
   private static final long SEG = 1311768467463790320L;
   private static final long OTHER_SEG = -77129852519518195L;
   private static final long G0 = 4096L;
   private static final int BIG = 1000000;

   private static MapFile newFile(CoarseTileNavigatorTest.MapCache cache) {
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

   private static MapFile fileWithGrassGrid(CoarseTileNavigatorTest.MapCache cache) throws IOException {
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      writeGrid(file, 4096L, "gfx/tiles/grass", new HashMap<>());
      return file;
   }

   private static MapFile fileWithWall(CoarseTileNavigatorTest.MapCache cache) throws IOException {
      MapFile file = newFile(cache);
      Map<Coord, Long> grids = new HashMap<>();
      grids.put(Coord.of(0, 0), 4096L);
      writeSegment(file, 1311768467463790320L, grids);
      Map<Coord, String> wall = new HashMap<>();

      for (int y = 0; y < 100; y++) {
         wall.put(Coord.of(20, y), "gfx/tiles/cave");
      }

      writeGrid(file, 4096L, "gfx/tiles/grass", wall);
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

   private static Route reachedRoute(Coord start, Coord goal) {
      return Route.reached(Arrays.asList(start, goal), 12);
   }

   @Test
   void unavailableSessionState_returnsUnavailableRun() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      CoarseTileNavigator nav = navigator(
         file, new CoarseTileNavigatorTest.ScriptedState(), new CoarseTileNavigatorTest.ScriptedLocal(), new CoarseTileNavigatorTest.ScriptedWalker()
      );
      Run r = nav.navigate(Coord.of(30, 20), region(0, 0, 40, 40));
      Assertions.assertTrue(r.unavailable());
      Assertions.assertEquals(RunStatus.UNAVAILABLE, r.status);
      Assertions.assertNull(r.start);
      Assertions.assertNull(r.routeStatus);
      Assertions.assertNull(r.navOutcome);
      Assertions.assertNull(r.endPos);
      Assertions.assertTrue(r.replanFacts.isEmpty());
      Assertions.assertEquals(new Coord(30, 20), r.goalTile, "the goal tile is preserved in the run facts");
      Assertions.assertFalse(r.routeRejected());
      Assertions.assertFalse(r.navigated());
      Assertions.assertFalse(r.reached());
      Assertions.assertFalse(r.cancelled());
      Assertions.assertTrue(r.elapsedMs >= 0L);
   }

   @Test
   void startOutsideBounds_routeRejected_invalidStart() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      Run r = navigator(
            file,
            new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 45, 20, 500.0, 220.0)),
            new CoarseTileNavigatorTest.ScriptedLocal(),
            new CoarseTileNavigatorTest.ScriptedWalker()
         )
         .navigate(Coord.of(30, 20), region(0, 0, 40, 40));
      Assertions.assertTrue(r.routeRejected());
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.INVALID_START, r.routeStatus);
      Assertions.assertNull(r.navOutcome, "no controller run on a refused initial route");
      Assertions.assertEquals(0, r.legs);
      Assertions.assertEquals(Coord2d.of(500.0, 220.0), r.endPos, "no movement happened");
      Assertions.assertTrue(r.replanFacts.isEmpty());
      Assertions.assertFalse(r.reached());
   }

   @Test
   void goalOutsideBounds_routeRejected_invalidGoal() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      Run r = navigator(
            file,
            new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)),
            new CoarseTileNavigatorTest.ScriptedLocal(),
            new CoarseTileNavigatorTest.ScriptedWalker()
         )
         .navigate(Coord.of(45, 20), region(0, 0, 40, 40));
      Assertions.assertTrue(r.routeRejected());
      Assertions.assertEquals(
         haven.pathfinding.CoarseRoutePlanner.Status.INVALID_GOAL,
         r.routeStatus,
         "a goal outside the explicit planning bounds is a hard boundary, never clipped"
      );
      Assertions.assertEquals(0, r.expanded);
      Assertions.assertNull(r.navOutcome);
   }

   @Test
   void noKnownRoute_routeRejected_withCause() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithWall(cache);
      Run r = navigator(
            file,
            new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)),
            new CoarseTileNavigatorTest.ScriptedLocal(),
            new CoarseTileNavigatorTest.ScriptedWalker()
         )
         .navigate(Coord.of(35, 20), region(0, 0, 40, 40));
      Assertions.assertTrue(r.routeRejected());
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.NO_KNOWN_ROUTE, r.routeStatus);
      Assertions.assertEquals(Cause.KNOWN_BLOCKED, r.routeCause, "the wall is known blocking terrain, not an unexplored gap");
      Assertions.assertNull(r.navOutcome);
   }

   @Test
   void exhaustedBudget_routeRejected_exhausted() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      Bounds tiny = new Bounds(10, 2, 3);
      Run r = navigator(
            file,
            new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)),
            new CoarseTileNavigatorTest.ScriptedLocal(),
            new CoarseTileNavigatorTest.ScriptedWalker(),
            tiny
         )
         .navigate(Coord.of(30, 20), region(0, 0, 40, 40));
      Assertions.assertTrue(r.routeRejected());
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.EXHAUSTED, r.routeStatus);
      Assertions.assertNull(r.navOutcome);
   }

   @Test
   void reachesGoalEndToEnd_withNonZeroSegmentTranslation() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      CoarseTileNavigatorTest.ScriptedLocal local = new CoarseTileNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(245.3, 118.9), Coord2d.of(335.5, 170.5)));
      CoarseTileNavigatorTest.ScriptedWalker walker = new CoarseTileNavigatorTest.ScriptedWalker().walk(success(Coord2d.of(335.5, 170.5)));
      Run r = navigator(file, new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 34, 3, 245.3, 118.9)), local, walker)
         .navigate(Coord.of(42, 8), region(0, 0, 100, 100));
      Assertions.assertTrue(r.navigated());
      Assertions.assertTrue(r.reached());
      Assertions.assertEquals(Outcome.REACHED_DESTINATION, r.navOutcome);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.REACHED, r.routeStatus);
      Assertions.assertEquals(new Coord(42, 8), r.goalTile);
      Assertions.assertEquals(2, r.waypointCount, "open ground collapses to start + goal");
      Assertions.assertTrue(r.expanded > 0);
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
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      Run r = navigator(
            file,
            new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 7, 7, 77.0, 77.0)),
            new CoarseTileNavigatorTest.ScriptedLocal(),
            new CoarseTileNavigatorTest.ScriptedWalker()
         )
         .navigate(Coord.of(7, 7), region(0, 0, 40, 40));
      Assertions.assertTrue(r.reached());
      Assertions.assertEquals(0, r.legs);
      Assertions.assertEquals(0, r.replans);
      Assertions.assertEquals(1, r.waypointCount, "start-equals-goal is a single-waypoint REACHED route");
      Assertions.assertEquals(Coord2d.of(77.0, 77.0), r.endPos);
   }

   @Test
   void midRunSegmentCrossing_replanFailsTypedWithCrossSegmentFact() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      CoarseTileNavigatorTest.ScriptedLocal local = new CoarseTileNavigatorTest.ScriptedLocal().plan(plan(Status.FAILED));
      CoarseTileNavigatorTest.ScriptedWalker walker = new CoarseTileNavigatorTest.ScriptedWalker();
      CoarseTileNavigatorTest.ScriptedState state = new CoarseTileNavigatorTest.ScriptedState()
         .loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0))
         .loc(loc(-77129852519518195L, 105, 2, 1155.0, 22.0));
      Run r = navigator(file, state, local, walker).navigate(Coord.of(30, 20), region(0, 0, 40, 40));
      Assertions.assertTrue(r.navigated());
      Assertions.assertEquals(Outcome.COARSE_PLAN_FAILED, r.navOutcome);
      Assertions.assertTrue(r.detail.contains("CROSS_SEGMENT"), r.detail);
      Assertions.assertEquals(0, r.legs, "the failed local plan has nothing to walk");
      Assertions.assertEquals(1, r.replans);
      Assertions.assertEquals(1, r.replanFacts.size());
      Assertions.assertEquals(
         haven.pathfinding.CoarseRoutePlanner.Status.CROSS_SEGMENT,
         ((ReplanFact)r.replanFacts.get(0)).status,
         "the adapter-synthesized segment refusal survives in the run facts"
      );
      Assertions.assertEquals(1, ((ReplanFact)r.replanFacts.get(0)).index);
      Assertions.assertFalse(r.reached());
   }

   @Test
   void midRunNoKnownRoute_replanFactsCarryStatusAndCause() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      CoarseTileNavigatorTest.ScriptedCoarse coarse = new CoarseTileNavigatorTest.ScriptedCoarse()
         .plan(reachedRoute(Coord.of(2, 2), Coord.of(30, 20)))
         .plan(Route.failed(haven.pathfinding.CoarseRoutePlanner.Status.NO_KNOWN_ROUTE, Cause.KNOWN_BLOCKED));
      CoarseTileNavigatorTest.ScriptedLocal local = new CoarseTileNavigatorTest.ScriptedLocal().plan(plan(Status.FAILED));
      Run r = navigator(
            file,
            coarse,
            new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)),
            local,
            new CoarseTileNavigatorTest.ScriptedWalker()
         )
         .navigate(Coord.of(30, 20), region(0, 0, 40, 40));
      Assertions.assertTrue(r.navigated());
      Assertions.assertEquals(Outcome.COARSE_PLAN_FAILED, r.navOutcome);
      Assertions.assertTrue(r.detail.contains("NO_KNOWN_ROUTE") && r.detail.contains("KNOWN_BLOCKED"), r.detail);
      Assertions.assertEquals(1, r.replanFacts.size());
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.NO_KNOWN_ROUTE, ((ReplanFact)r.replanFacts.get(0)).status);
      Assertions.assertEquals(Cause.KNOWN_BLOCKED, ((ReplanFact)r.replanFacts.get(0)).cause);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.REACHED, r.routeStatus, "the initial route was fine before the world changed");
      Assertions.assertFalse(r.reached());
   }

   @Test
   void walkerFailure_isTypedTerminal() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      CoarseTileNavigatorTest.ScriptedLocal local = new CoarseTileNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(22.0, 22.0), Coord2d.of(335.5, 225.5)));
      CoarseTileNavigatorTest.ScriptedWalker walker = new CoarseTileNavigatorTest.ScriptedWalker()
         .walk(LegResult.failed("walker REJECTED", Coord2d.of(150.0, 100.0)));
      Run r = navigator(file, new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)), local, walker)
         .navigate(Coord.of(30, 20), region(0, 0, 40, 40));
      Assertions.assertTrue(r.navigated());
      Assertions.assertEquals(Outcome.TERMINAL_FAILURE, r.navOutcome);
      Assertions.assertEquals("walker REJECTED", r.detail);
      Assertions.assertEquals(Coord2d.of(150.0, 100.0), r.endPos, "the walker's last known position survives the terminal failure");
      Assertions.assertEquals(1, r.legs);
      Assertions.assertFalse(r.reached());
   }

   @Test
   void walkerCancellation_isTypedAndPreserved() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      CoarseTileNavigatorTest.ScriptedLocal local = new CoarseTileNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(22.0, 22.0), Coord2d.of(335.5, 225.5)));
      CoarseTileNavigatorTest.ScriptedWalker walker = new CoarseTileNavigatorTest.ScriptedWalker()
         .walk(LegResult.cancelled("bot cancelled", Coord2d.of(60.0, 40.0)));
      Run r = navigator(file, new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)), local, walker)
         .navigate(Coord.of(30, 20), region(0, 0, 40, 40));
      Assertions.assertTrue(r.cancelled());
      Assertions.assertEquals(Outcome.CANCELLED, r.navOutcome);
      Assertions.assertEquals("bot cancelled", r.detail, "the walker's opaque cancellation detail is preserved");
      Assertions.assertEquals(Coord2d.of(60.0, 40.0), r.endPos, "partial leg progress is not discarded on cancellation");
      Assertions.assertEquals(1, r.legs);
      Assertions.assertFalse(r.reached());
   }

   @Test
   void planRoute_translatesWaypointsToAbsolute() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      Route r = CoarseTileNavigator.planRoute(file, 1311768467463790320L, Coord.of(2, 2), Coord.of(42, 8), region(0, 0, 100, 100), 1000000);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.REACHED, r.status);
      Assertions.assertEquals(new Coord(2, 2), r.start, "REACHED routes expose absolute waypoints");
      Assertions.assertEquals(new Coord(42, 8), r.goal);
      Assertions.assertEquals(new Coord(2, 2), r.waypoints.get(0), "the first waypoint is the absolute start tile");
      Assertions.assertEquals(new Coord(42, 8), r.waypoints.get(r.waypoints.size() - 1), "the last waypoint is the absolute goal tile");
      Assertions.assertTrue(r.expanded > 0);
      Route out = CoarseTileNavigator.planRoute(file, 1311768467463790320L, Coord.of(2, 2), Coord.of(45, 20), region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.INVALID_GOAL, out.status);
      Route nos = CoarseTileNavigator.planRoute(file, -77129852519518195L, Coord.of(2, 2), Coord.of(30, 20), region(0, 0, 40, 40), 1000000);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.INVALID_START, nos.status, "an absent segment's tiles are unknown, never free");
      Assertions.assertThrows(
         NullPointerException.class,
         () -> CoarseTileNavigator.planRoute(null, 1311768467463790320L, Coord.of(2, 2), Coord.of(30, 20), region(0, 0, 40, 40), 1000000)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> CoarseTileNavigator.planRoute(file, 1311768467463790320L, null, Coord.of(30, 20), region(0, 0, 40, 40), 1000000)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> CoarseTileNavigator.planRoute(file, 1311768467463790320L, Coord.of(2, 2), null, region(0, 0, 40, 40), 1000000)
      );
      Assertions.assertThrows(
         NullPointerException.class, () -> CoarseTileNavigator.planRoute(file, 1311768467463790320L, Coord.of(2, 2), Coord.of(30, 20), null, 1000000)
      );
   }

   @Test
   void scriptedCoarseSeamRecordsSegmentAndBudget() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      CoarseTileNavigatorTest.ScriptedCoarse coarse = new CoarseTileNavigatorTest.ScriptedCoarse().plan(reachedRoute(Coord.of(2, 2), Coord.of(30, 20)));
      CoarseTileNavigatorTest.ScriptedLocal local = new CoarseTileNavigatorTest.ScriptedLocal()
         .plan(plan(Status.FAILED))
         .plan(plan(Status.REACHED, Coord2d.of(22.0, 22.0), Coord2d.of(335.5, 225.5)));
      CoarseTileNavigatorTest.ScriptedWalker walker = new CoarseTileNavigatorTest.ScriptedWalker().walk(success(Coord2d.of(335.5, 225.5)));
      Run r = navigator(
            file,
            coarse,
            new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)).loc(loc(1311768467463790320L, 3, 3, 33.0, 33.0)),
            local,
            walker
         )
         .navigate(Coord.of(30, 20), region(0, 0, 40, 40));
      Assertions.assertTrue(r.reached());
      Assertions.assertEquals(1, r.replans);
      Assertions.assertEquals(2, coarse.segs.size(), "initial plan + one replan");
      Assertions.assertEquals(1311768467463790320L, coarse.segs.get(0)[0], "the initial plan runs in the run's segment");
      Assertions.assertEquals(1311768467463790320L, coarse.segs.get(1)[0], "a same-segment replan stays in the run's segment");
      Assertions.assertEquals((long)Bounds.DEFAULT.coarseExpanded, coarse.segs.get(0)[1]);
   }

   @Test
   void liveAdapterNeverStartsTheBot() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      Bot bot = Bot.execute(new BotAction[0]);
      CoarseTileNavigator nav = CoarseTileNavigator.live(null, file, bot);
      Run r = nav.navigate(Coord.of(30, 20), region(0, 0, 40, 40));
      Assertions.assertTrue(r.unavailable());
      Assertions.assertNull(bot.ui(), "the adapter must never start the caller's bot");
      Assertions.assertFalse(Bot.hasCurrent(), "no bot may have been registered as current");
   }

   @Test
   void nullSeamsRejected() {
      MapFile file = newFile(new CoarseTileNavigatorTest.MapCache());
      SessionState state = new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0));
      Bounds b = Bounds.DEFAULT;
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new CoarseTileNavigator(null, state, new CoarseTileNavigatorTest.ScriptedLocal(), new CoarseTileNavigatorTest.ScriptedWalker(), b)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new CoarseTileNavigator(
               CoarsePlanner.overMapFile(file), null, new CoarseTileNavigatorTest.ScriptedLocal(), new CoarseTileNavigatorTest.ScriptedWalker(), b
            )
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new CoarseTileNavigator(CoarsePlanner.overMapFile(file), state, null, new CoarseTileNavigatorTest.ScriptedWalker(), b)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new CoarseTileNavigator(CoarsePlanner.overMapFile(file), state, new CoarseTileNavigatorTest.ScriptedLocal(), null, b)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new CoarseTileNavigator(
               CoarsePlanner.overMapFile(file), state, new CoarseTileNavigatorTest.ScriptedLocal(), new CoarseTileNavigatorTest.ScriptedWalker(), null
            )
      );
      Assertions.assertThrows(NullPointerException.class, () -> CoarseTileNavigator.live(null, null, Bot.execute(new BotAction[0])));
      Assertions.assertThrows(NullPointerException.class, () -> CoarseTileNavigator.live(null, null, null, b, 1000L, NamedPlaceNavigator.NOOP));
      Assertions.assertThrows(
         NullPointerException.class,
         () -> CoarseTileNavigator.planRoute(null, 1311768467463790320L, Coord.of(0, 0), Coord.of(1, 1), region(0, 0, 10, 10), 1000000)
      );
   }

   @Test
   void nullNavigateArgsRejected() throws Exception {
      CoarseTileNavigatorTest.MapCache cache = new CoarseTileNavigatorTest.MapCache();
      MapFile file = fileWithGrassGrid(cache);
      CoarseTileNavigator nav = navigator(
         file,
         new CoarseTileNavigatorTest.ScriptedState().loc(loc(1311768467463790320L, 2, 2, 22.0, 22.0)),
         new CoarseTileNavigatorTest.ScriptedLocal(),
         new CoarseTileNavigatorTest.ScriptedWalker()
      );
      Assertions.assertThrows(NullPointerException.class, () -> nav.navigate(null, region(0, 0, 40, 40)));
      Assertions.assertThrows(NullPointerException.class, () -> nav.navigate(Coord.of(30, 20), null));
   }

   private static CoarseTileNavigator navigator(MapFile file, SessionState state, LocalPlanner local, LegWalker walker) {
      return navigator(file, CoarsePlanner.overMapFile(file), state, local, walker, Bounds.DEFAULT);
   }

   private static CoarseTileNavigator navigator(MapFile file, SessionState state, LocalPlanner local, LegWalker walker, Bounds bounds) {
      return navigator(file, CoarsePlanner.overMapFile(file), state, local, walker, bounds);
   }

   private static CoarseTileNavigator navigator(MapFile file, CoarsePlanner coarse, SessionState state, LocalPlanner local, LegWalker walker) {
      return new CoarseTileNavigator(coarse, state, local, walker, Bounds.DEFAULT);
   }

   private static CoarseTileNavigator navigator(MapFile file, CoarsePlanner coarse, SessionState state, LocalPlanner local, LegWalker walker, Bounds bounds) {
      return new CoarseTileNavigator(coarse, state, local, walker, bounds);
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

   private static final class ScriptedCoarse implements CoarsePlanner {
      final List<Route> script = new ArrayList<>();
      final List<long[]> segs = new ArrayList<>();
      int at = 0;

      CoarseTileNavigatorTest.ScriptedCoarse plan(Route r) {
         this.script.add(r);
         return this;
      }

      public Route plan(long seg, Coord startTile, Coord goalTile, Area bounds, int maxExpanded) {
         this.segs.add(new long[]{seg, (long)maxExpanded});
         Route r = this.at < this.script.size() ? this.script.get(this.at) : this.script.get(this.script.size() - 1);
         this.at++;
         return r;
      }
   }

   private static class ScriptedLocal implements LocalPlanner {
      final List<LocalPlan> script = new ArrayList<>();
      final List<Coord2d> froms = new ArrayList<>();
      final List<Coord2d> targets = new ArrayList<>();
      int at = 0;

      CoarseTileNavigatorTest.ScriptedLocal plan(LocalPlan p) {
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

      CoarseTileNavigatorTest.ScriptedState loc(Location l) {
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

      CoarseTileNavigatorTest.ScriptedWalker walk(LegResult r) {
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
