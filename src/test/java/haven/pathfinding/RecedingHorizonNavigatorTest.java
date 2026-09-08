package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.pathfinding.CoarseRoutePlanner.Route;
import haven.pathfinding.PathfinderLog.Trace;
import haven.pathfinding.PrototypePathfinder.ClipResult;
import haven.pathfinding.PrototypePathfinder.Grid;
import haven.pathfinding.PrototypePathfinder.Plan;
import haven.pathfinding.RecedingHorizonNavigator.Bounds;
import haven.pathfinding.RecedingHorizonNavigator.CoarsePlanner;
import haven.pathfinding.RecedingHorizonNavigator.LegResult;
import haven.pathfinding.RecedingHorizonNavigator.LegWalker;
import haven.pathfinding.RecedingHorizonNavigator.LocalPlan;
import haven.pathfinding.RecedingHorizonNavigator.LocalPlanner;
import haven.pathfinding.RecedingHorizonNavigator.Outcome;
import haven.pathfinding.RecedingHorizonNavigator.Result;
import haven.pathfinding.RecedingHorizonNavigator.TileMap;
import haven.pathfinding.RecedingHorizonNavigator.LocalPlan.Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RecedingHorizonNavigatorTest {
   private static final TileMap TILES = TileMap.scale(1.0);

   private static LocalPlan plan(Status st, Coord2d... waypoints) {
      return new LocalPlan(st, Arrays.asList(waypoints));
   }

   private static LegResult success(Coord2d end) {
      return LegResult.success(end);
   }

   private static Route openRoute(int w, int h, Coord start, Coord goal) {
      String[] names = new String[w * h];
      Arrays.fill(names, "tiles/grass");
      return CoarseRoutePlanner.plan(CoarseTileSource.fromNames(w, h, names), start, goal);
   }

   private static Route walledRoute(int w, int h, Coord start, Coord goal, List<Coord> blocked) {
      String[] names = new String[w * h];
      Arrays.fill(names, "tiles/grass");

      for (Coord c : blocked) {
         names[c.y * w + c.x] = "tiles/cave";
      }

      return CoarseRoutePlanner.plan(CoarseTileSource.fromNames(w, h, names), start, goal);
   }

   @Test
   void reachesDestinationOnOpenRoute() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(0.0, 0.0), Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker().walk(success(Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse();
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, coarse, TILES);
      Result r = nav.run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.REACHED_DESTINATION, r.outcome);
      Assertions.assertEquals(1, r.legs);
      Assertions.assertEquals(0, r.replans);
      Assertions.assertEquals(2, r.routeIndex);
      Assertions.assertNull(r.detail);
      Assertions.assertEquals(0.0, r.endPos.dist(Coord2d.of(2.0, 0.0)), 1.0E-9);
      Assertions.assertTrue(r.reached());
      Assertions.assertTrue(coarse.starts.isEmpty(), "a clean run never consults the replan seam");
      Assertions.assertEquals(1, local.targets.size());
      Assertions.assertEquals(Coord2d.of(2.0, 0.0), local.targets.get(0));
      Assertions.assertEquals(Coord2d.of(0.0, 0.0), local.froms.get(0));
   }

   @Test
   void advancesThroughEveryCoarseWaypoint() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(0.0, 0.0), Coord2d.of(2.0, 0.0)))
         .plan(plan(Status.REACHED, Coord2d.of(2.0, 0.0), Coord2d.of(4.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker()
         .walk(success(Coord2d.of(2.0, 0.0)))
         .walk(success(Coord2d.of(4.0, 0.0)));
      Result r = new RecedingHorizonNavigator(local, walker, new RecedingHorizonNavigatorTest.ScriptedCoarse(), TILES)
         .run(Coord.of(4, 0), List.of(Coord.of(0, 0), Coord.of(2, 0), Coord.of(4, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.REACHED_DESTINATION, r.outcome);
      Assertions.assertEquals(2, r.legs);
      Assertions.assertEquals(3, r.routeIndex);
      Assertions.assertEquals(Coord2d.of(2.0, 0.0), local.targets.get(0));
      Assertions.assertEquals(Coord2d.of(4.0, 0.0), local.targets.get(1));
      Assertions.assertEquals(Coord2d.of(2.0, 0.0), local.froms.get(1), "the second leg starts where the first ended");
   }

   @Test
   void alreadyAtGoalReturnsReachedWithZeroLegs() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal();
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker();
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, new RecedingHorizonNavigatorTest.ScriptedCoarse(), TILES);
      Result r = nav.run(Coord.of(2, 0), Collections.singletonList(Coord.of(2, 0)), Coord2d.of(2.0, 0.0));
      Assertions.assertEquals(Outcome.REACHED_DESTINATION, r.outcome);
      Assertions.assertEquals(0, r.legs);
      Assertions.assertEquals(0, r.replans);
      Assertions.assertEquals(0, local.targets.size());
      Assertions.assertEquals(0, walker.calls);
   }

   @Test
   void doesNotReportReachedWhenWalkerStopsOffGoalTile() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(0.0, 0.0), Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker()
         .walk(success(Coord2d.of(1.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse().plan(null);
      Result r = new RecedingHorizonNavigator(local, walker, coarse, TILES)
         .run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertNotEquals(Outcome.REACHED_DESTINATION, r.outcome);
      Assertions.assertEquals(Outcome.COARSE_PLAN_FAILED, r.outcome);
      Assertions.assertEquals(1, coarse.starts.size());
      Assertions.assertEquals(Coord.of(1, 0), coarse.starts.get(0));
   }

   @Test
   void clippedLegsContinueWithoutAdvancingOrCompleting() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.CLIPPED, Coord2d.of(0.0, 0.0), Coord2d.of(2.0, 0.0)))
         .plan(plan(Status.REACHED, Coord2d.of(2.0, 0.0), Coord2d.of(4.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker()
         .walk(success(Coord2d.of(2.0, 0.0)))
         .walk(success(Coord2d.of(4.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse();
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, coarse, TILES);
      Result r = nav.run(Coord.of(4, 0), List.of(Coord.of(0, 0), Coord.of(4, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.REACHED_DESTINATION, r.outcome);
      Assertions.assertEquals(2, r.legs);
      Assertions.assertEquals(0, r.replans);
      Assertions.assertEquals(2, r.routeIndex);
      Assertions.assertEquals(0.0, r.endPos.dist(Coord2d.of(4.0, 0.0)), 1.0E-9);
      Assertions.assertEquals(2, local.targets.size());
      Assertions.assertEquals(Coord2d.of(4.0, 0.0), local.targets.get(0));
      Assertions.assertEquals(Coord2d.of(4.0, 0.0), local.targets.get(1));
      Assertions.assertEquals(Coord2d.of(2.0, 0.0), local.froms.get(1));
      Assertions.assertTrue(coarse.starts.isEmpty(), "clipped legs never re-plan the coarse route");
   }

   @Test
   void clippedLoopHitsLegLimitWithoutFalseCompletion() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.CLIPPED, Coord2d.of(0.0, 0.0), Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker().walk(success(Coord2d.of(1.0, 0.0)));
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(
         local, walker, new RecedingHorizonNavigatorTest.ScriptedCoarse(), TILES, new Bounds(3, 10, 1000)
      );
      Result r = nav.run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.LEG_LIMIT_EXHAUSTED, r.outcome);
      Assertions.assertEquals(3, r.legs);
      Assertions.assertEquals(0, r.replans);
      Assertions.assertEquals(1, r.routeIndex, "clipped success never advanced the coarse index");
      Assertions.assertFalse(r.reached());
      Assertions.assertEquals(4, local.targets.size(), "one plan per loop turn, including the turn that hit the bound");

      for (Coord2d t : local.targets) {
         Assertions.assertEquals(Coord2d.of(2.0, 0.0), t, "the coarse waypoint never changes while clipped");
      }

      Assertions.assertEquals(3, walker.calls, "the fourth leg was refused by the bound before walking");
   }

   @Test
   void partialLegReplansCoarseBeforeContinuing() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.PARTIAL, Coord2d.of(0.0, 0.0), Coord2d.of(1.0, 0.0)))
         .plan(plan(Status.REACHED, Coord2d.of(1.0, 0.0), Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker()
         .walk(success(Coord2d.of(1.0, 0.0)))
         .walk(success(Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse()
         .plan(openRoute(3, 1, Coord.of(1, 0), Coord.of(2, 0)));
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, coarse, TILES);
      Result r = nav.run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.REACHED_DESTINATION, r.outcome);
      Assertions.assertEquals(2, r.legs);
      Assertions.assertEquals(1, r.replans);
      Assertions.assertEquals(1, coarse.starts.size());
      Assertions.assertEquals(Coord.of(1, 0), coarse.starts.get(0), "the replan starts at the current tile");
      Assertions.assertEquals(Coord.of(2, 0), coarse.goals.get(0));
      Assertions.assertEquals(2, r.routeIndex);
   }

   @Test
   void snappedLegReplansCoarseWithoutAdvancing() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.SNAPPED, Coord2d.of(0.0, 0.0), Coord2d.of(1.0, 0.0)))
         .plan(plan(Status.REACHED, Coord2d.of(1.0, 0.0), Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker()
         .walk(success(Coord2d.of(1.0, 0.0)))
         .walk(success(Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse()
         .plan(openRoute(3, 1, Coord.of(1, 0), Coord.of(2, 0)));
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, coarse, TILES);
      Result r = nav.run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.REACHED_DESTINATION, r.outcome);
      Assertions.assertEquals(2, r.legs);
      Assertions.assertEquals(1, r.replans);
      Assertions.assertEquals(Coord.of(1, 0), coarse.starts.get(0), "the snapped walker success was not an advance — the coarse plan was re-planned");
   }

   @Test
   void failedLocalPlanReplansWithoutWalking() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.FAILED))
         .plan(plan(Status.REACHED, Coord2d.of(0.0, 0.0), Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker().walk(success(Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse()
         .plan(openRoute(3, 1, Coord.of(0, 0), Coord.of(2, 0)));
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, coarse, TILES);
      Result r = nav.run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.REACHED_DESTINATION, r.outcome);
      Assertions.assertEquals(1, r.legs, "the failed plan has nothing to walk");
      Assertions.assertEquals(1, r.replans);
      Assertions.assertEquals(Coord.of(0, 0), coarse.starts.get(0));
   }

   @Test
   void walkerFailureIsTypedTerminal() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(0.0, 0.0), Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker()
         .walk(LegResult.failed("REJECTED", Coord2d.of(1.5, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse();
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, coarse, TILES);
      Result r = nav.run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.TERMINAL_FAILURE, r.outcome);
      Assertions.assertEquals("REJECTED", r.detail);
      Assertions.assertEquals(1, r.legs);
      Assertions.assertEquals(1, r.routeIndex, "no advance on walker failure");
      Assertions.assertTrue(r.terminalFailure());
      Assertions.assertEquals(Coord2d.of(1.5, 0.0), r.endPos, "the walker's last known position survives the terminal failure");
      Assertions.assertTrue(coarse.starts.isEmpty(), "walker failures never trigger replans");
   }

   @Test
   void walkerCancellationPropagates() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(0.0, 0.0), Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker()
         .walk(LegResult.cancelled("user abort", Coord2d.of(0.5, 0.0)));
      Result r = new RecedingHorizonNavigator(local, walker, new RecedingHorizonNavigatorTest.ScriptedCoarse(), TILES)
         .run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.CANCELLED, r.outcome);
      Assertions.assertEquals("user abort", r.detail);
      Assertions.assertEquals(1, r.legs);
      Assertions.assertTrue(r.cancelled());
      Assertions.assertEquals(Coord2d.of(0.5, 0.0), r.endPos, "the walker's last known position survives the cancellation");
   }

   @Test
   void stoppedEarlyReplansFromActualPosition() {
      Route fresh = openRoute(4, 1, Coord.of(1, 0), Coord.of(2, 0));
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(0.0, 0.0), Coord2d.of(2.0, 0.0)))
         .plan(plan(Status.REACHED, Coord2d.of(1.0, 0.0), Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker()
         .walk(LegResult.stoppedEarly("stopped_early", Coord2d.of(1.0, 0.0)))
         .walk(success(Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse().plan(fresh);
      Result r = new RecedingHorizonNavigator(local, walker, coarse, TILES)
         .run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.REACHED_DESTINATION, r.outcome);
      Assertions.assertEquals(1, r.replans);
      Assertions.assertEquals(Coord.of(1, 0), coarse.starts.get(0));
      Assertions.assertEquals(2, walker.calls);
   }

   @Test
   void stuckIsTypedAndDoesNotReplan() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.REACHED, Coord2d.of(0.0, 0.0), Coord2d.of(2.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker()
         .walk(LegResult.stuck("STUCK", Coord2d.of(1.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse();
      Result r = new RecedingHorizonNavigator(local, walker, coarse, TILES)
         .run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.STUCK, r.outcome);
      Assertions.assertTrue(coarse.starts.isEmpty());
   }

   @Test
   void staleCoarsePlanIsReplacedByReplan() {
      Route fresh = walledRoute(3, 3, Coord.of(0, 0), Coord.of(2, 0), Collections.singletonList(Coord.of(1, 0)));
      Assertions.assertTrue(fresh.reached(), "the walled grid still has a detour: " + fresh);
      Assertions.assertTrue(fresh.waypoints.size() > 2, "the detour must keep turn points: " + fresh.waypoints);
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.PARTIAL, Coord2d.of(0.0, 0.0), Coord2d.of(0.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker().walk(success(Coord2d.of(0.0, 0.0)));

      for (int i = 1; i < fresh.waypoints.size(); i++) {
         Coord2d wp = TILES.toWorld((Coord)fresh.waypoints.get(i));
         local.plan(plan(Status.REACHED, wp, wp));
         walker.walk(success(wp));
      }

      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse().plan(fresh);
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, coarse, TILES);
      Result r = nav.run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.REACHED_DESTINATION, r.outcome);
      Assertions.assertEquals(1, r.replans, "exactly one coarse replan");
      Assertions.assertEquals(fresh.waypoints.size(), r.legs, "partial leg + every fresh-route leg");
      Assertions.assertEquals(Coord.of(0, 0), coarse.starts.get(0));
      Assertions.assertEquals(Coord.of(2, 0), coarse.goals.get(0));
      Assertions.assertEquals(fresh.waypoints.size(), local.targets.size(), "initial goal target + every fresh-route waypoint target");
      Assertions.assertEquals(
         TILES.toWorld((Coord)fresh.waypoints.get(1)), local.targets.get(1), "the first post-replan target is the fresh route's turn point, not the stale goal"
      );
   }

   @Test
   void coarseReplanFailureIsTyped() {
      Route noRoute = walledRoute(3, 3, Coord.of(0, 0), Coord.of(2, 0), Arrays.asList(Coord.of(1, 0), Coord.of(1, 1), Coord.of(1, 2)));
      Assertions.assertTrue(noRoute.noKnownRoute(), noRoute.toString());
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal().plan(plan(Status.FAILED));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker();
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse().plan(noRoute);
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, coarse, TILES);
      Result r = nav.run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.COARSE_PLAN_FAILED, r.outcome);
      Assertions.assertTrue(r.detail.contains("NO_KNOWN_ROUTE"), r.detail);
      Assertions.assertEquals(0, r.legs);
      Assertions.assertEquals(1, r.replans);
   }

   @Test
   void replanLimitExhaustionIsTyped() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal().plan(plan(Status.FAILED));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker();
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse()
         .plan(openRoute(3, 1, Coord.of(0, 0), Coord.of(2, 0)));
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, coarse, TILES, new Bounds(100, 3, 1000));
      Result r = nav.run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.REPLAN_LIMIT_EXHAUSTED, r.outcome);
      Assertions.assertEquals(0, r.legs, "a FAILED local plan never walks");
      Assertions.assertEquals(3, r.replans);
      Assertions.assertEquals(3, coarse.starts.size());
      Assertions.assertFalse(r.reached());
   }

   @Test
   void legLimitExhaustionOnSnapReplanLoop() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal()
         .plan(plan(Status.SNAPPED, Coord2d.of(0.0, 0.0), Coord2d.of(1.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker().walk(success(Coord2d.of(1.0, 0.0)));
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse()
         .plan(openRoute(3, 1, Coord.of(1, 0), Coord.of(2, 0)));
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, coarse, TILES, new Bounds(4, 100, 1000));
      Result r = nav.run(Coord.of(2, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.LEG_LIMIT_EXHAUSTED, r.outcome);
      Assertions.assertEquals(4, r.legs);
      Assertions.assertEquals(4, r.replans, "each snapped leg costs exactly one replan");
   }

   @Test
   void invalidRoutesAreRefused() {
      RecedingHorizonNavigatorTest.ScriptedLocal local = new RecedingHorizonNavigatorTest.ScriptedLocal();
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker();
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse();
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(local, walker, coarse, TILES);
      Assertions.assertEquals(Outcome.COARSE_PLAN_INVALID, nav.run(Coord.of(2, 0), null, Coord2d.of(0.0, 0.0)).outcome);
      Assertions.assertEquals(Outcome.COARSE_PLAN_INVALID, nav.run(Coord.of(2, 0), Collections.emptyList(), Coord2d.of(0.0, 0.0)).outcome);
      Result wrongGoal = nav.run(Coord.of(3, 0), List.of(Coord.of(0, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.COARSE_PLAN_INVALID, wrongGoal.outcome);
      Assertions.assertTrue(wrongGoal.detail.contains("goal tile"), wrongGoal.detail);
      Result wrongStart = nav.run(Coord.of(2, 0), List.of(Coord.of(1, 0), Coord.of(2, 0)), Coord2d.of(0.0, 0.0));
      Assertions.assertEquals(Outcome.COARSE_PLAN_INVALID, wrongStart.outcome);
      Assertions.assertTrue(wrongStart.detail.contains("current tile"), wrongStart.detail);
      Assertions.assertEquals(0, local.targets.size(), "no planning happens for an invalid route");
      Assertions.assertEquals(0, walker.calls, "no walking happens for an invalid route");
      Assertions.assertEquals(0, coarse.starts.size());
   }

   @Test
   void nullGoalAndStartAreRejected() {
      RecedingHorizonNavigator nav = new RecedingHorizonNavigator(
         new RecedingHorizonNavigatorTest.ScriptedLocal(),
         new RecedingHorizonNavigatorTest.ScriptedWalker(),
         new RecedingHorizonNavigatorTest.ScriptedCoarse(),
         TILES
      );
      Assertions.assertThrows(NullPointerException.class, () -> nav.run(null, List.of(Coord.of(0, 0)), Coord2d.of(0.0, 0.0)));
      Assertions.assertThrows(NullPointerException.class, () -> nav.run(Coord.of(0, 0), List.of(Coord.of(0, 0)), null));
   }

   @Test
   void boundsRejectInvalidValues() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Bounds(0, 5, 1000));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Bounds(100, 0, 1000));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Bounds(100, 5, 0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Bounds(-1, 5, 1000));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new Bounds(100, -2, 1000));
   }

   @Test
   void nullSeamsAndBadTileMapAreRejected() {
      RecedingHorizonNavigatorTest.ScriptedWalker walker = new RecedingHorizonNavigatorTest.ScriptedWalker();
      RecedingHorizonNavigatorTest.ScriptedCoarse coarse = new RecedingHorizonNavigatorTest.ScriptedCoarse();
      Assertions.assertThrows(NullPointerException.class, () -> new RecedingHorizonNavigator(null, walker, coarse, TILES));
      Assertions.assertThrows(
         NullPointerException.class, () -> new RecedingHorizonNavigator(new RecedingHorizonNavigatorTest.ScriptedLocal(), null, coarse, TILES)
      );
      Assertions.assertThrows(
         NullPointerException.class, () -> new RecedingHorizonNavigator(new RecedingHorizonNavigatorTest.ScriptedLocal(), walker, null, TILES)
      );
      Assertions.assertThrows(
         NullPointerException.class, () -> new RecedingHorizonNavigator(new RecedingHorizonNavigatorTest.ScriptedLocal(), walker, coarse, null)
      );
      Assertions.assertThrows(
         NullPointerException.class, () -> new RecedingHorizonNavigator(new RecedingHorizonNavigatorTest.ScriptedLocal(), walker, coarse, TILES, null)
      );
      Assertions.assertThrows(IllegalArgumentException.class, () -> TileMap.scale(0.0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> TileMap.scale(-1.0));
      Assertions.assertThrows(NullPointerException.class, () -> CoarsePlanner.of(null));
   }

   private static Plan planOn(Coord2d start, Coord2d dest, boolean snap, RecedingHorizonNavigatorTest.Blocker blocker) {
      ClipResult clip = PrototypePathfinder.clipToHorizon(start, List.of(dest));
      Grid grid = PrototypePathfinder.planGrid(start, clip.targets);
      boolean[] solid = new boolean[grid.w * grid.h];
      boolean[] dilated = new boolean[grid.w * grid.h];
      if (blocker != null) {
         blocker.block(grid, solid, dilated);
      }

      return PrototypePathfinder.planCore(start, List.of(dest), clip.targets, clip.clipped, snap, 4.5, grid, solid, dilated, 0, new Trace());
   }

   @Test
   void fromPrototypeKeepsClippedContinuationSemantics() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      Coord2d dest = Coord2d.of(0.0, 500.0);
      Assertions.assertTrue(dest.dist(start) > PrototypePathfinder.maxReach(), "fixture must be beyond the horizon");
      Plan plan = planOn(start, dest, true, null);
      Assertions.assertEquals(haven.pathfinding.PrototypePathfinder.Plan.Status.CLIPPED, plan.status, "the prototype must report the far goal as a clipped leg");
      LocalPlan lp = LocalPlan.from(plan.status, plan.waypoints);
      Assertions.assertEquals(Status.CLIPPED, lp.status, "a horizon-clipped leg stays CLIPPED — never destination arrival");
      Assertions.assertEquals(plan.waypoints.size(), lp.waypoints.size());
      Assertions.assertEquals(plan.waypoints, lp.waypoints);
   }

   @Test
   void fromPrototypeMapsReachedAndFailed() {
      Plan reached = planOn(Coord2d.of(0.0, 0.0), Coord2d.of(0.0, 100.0), true, null);
      Assertions.assertEquals(haven.pathfinding.PrototypePathfinder.Plan.Status.REACHED, reached.status);
      Assertions.assertEquals(Status.REACHED, LocalPlan.from(reached.status, reached.waypoints).status);
      Coord2d dest = Coord2d.of(0.0, 100.0);
      Plan failed = planOn(Coord2d.of(0.0, 0.0), dest, false, (grid, solid, dilated) -> {
         Coord destCell = PrototypePathfinder.worldCell(grid.origin, dest);
         solid[destCell.y * grid.w + destCell.x] = true;
         dilated[destCell.y * grid.w + destCell.x] = true;
         grid.block(destCell.x, destCell.y);
      });
      Assertions.assertEquals(
         haven.pathfinding.PrototypePathfinder.Plan.Status.FAILED, failed.status, "snap=false with a blocked goal yields FAILED (see PathfinderPlanStatusTest)"
      );
      Assertions.assertEquals(Status.FAILED, LocalPlan.from(failed.status, failed.waypoints).status);
      Assertions.assertTrue(LocalPlan.from(failed.status, failed.waypoints).waypoints.isEmpty());
   }

   private interface Blocker {
      void block(Grid var1, boolean[] var2, boolean[] var3);
   }

   private static final class ScriptedCoarse implements CoarsePlanner {
      final List<Route> script = new ArrayList<>();
      final List<Coord> starts = new ArrayList<>();
      final List<Coord> goals = new ArrayList<>();
      final List<Integer> budgets = new ArrayList<>();
      int at = 0;

      RecedingHorizonNavigatorTest.ScriptedCoarse plan(Route r) {
         this.script.add(r);
         return this;
      }

      public Route plan(Coord startTile, Coord goalTile, int maxExpanded) {
         this.starts.add(startTile);
         this.goals.add(goalTile);
         this.budgets.add(maxExpanded);
         Route r = this.at < this.script.size() ? this.script.get(this.at) : this.script.get(this.script.size() - 1);
         this.at++;
         return r;
      }
   }

   private static final class ScriptedLocal implements LocalPlanner {
      final List<LocalPlan> script = new ArrayList<>();
      final List<Coord2d> froms = new ArrayList<>();
      final List<Coord2d> targets = new ArrayList<>();
      int at = 0;

      RecedingHorizonNavigatorTest.ScriptedLocal plan(LocalPlan p) {
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

   private static final class ScriptedWalker implements LegWalker {
      final List<LegResult> script = new ArrayList<>();
      int calls = 0;
      int at = 0;

      RecedingHorizonNavigatorTest.ScriptedWalker walk(LegResult r) {
         this.script.add(r);
         return this;
      }

      public LegResult walk(Coord2d from, LocalPlan plan) {
         this.calls++;
         LegResult r = this.at < this.script.size() ? this.script.get(this.at) : this.script.get(this.script.size() - 1);
         this.at++;
         return r;
      }
   }
}
