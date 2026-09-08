package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.GraphEdge;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;
import haven.nav.NavGoal;
import haven.nav.NavOutcome;
import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WorldGraphTest {
   private static GraphNode n(String world, String seg, String layer, String area) {
      return GraphNode.of(world, seg, layer, area);
   }

   @Test
   void nodeIdentityIsFourPartAndNotOrdinal() {
      GraphNode a = n("w", "seg", "0", "a0");
      GraphNode b = n("w", "seg", "0", "a0");
      GraphNode c = n("w", "seg", "1", "a0");
      Assertions.assertEquals(a, b);
      Assertions.assertNotEquals(a, c);
      Assertions.assertTrue(a.sameWalkRegion(b));
      Assertions.assertFalse(a.sameWalkRegion(c), "layer strings are identities, not stacked floors");
   }

   @Test
   void numericLayersDoNotCreateEdges() {
      WorldGraph g = new WorldGraph();
      GraphNode surface = n("w", "seg", "0", "a");
      GraphNode below = n("w", "seg", "1", "a");
      g.observe(surface);
      Assertions.assertNull(g.learn(surface, below, GraphEdge.Kind.CAVE, "hole"), "unobserved dest must not be fabricated");
      g.observe(below);
      WorldGraph.Path path = g.route(surface, below, MobilityProfile.land());
      Assertions.assertFalse(path.ok());
      Assertions.assertTrue(path.edges.isEmpty(), "observing two layers must not invent a stair");
      GraphEdge e = g.learn(surface, below, GraphEdge.Kind.CAVE, "hole");
      Assertions.assertNotNull(e);
      Assertions.assertEquals(GraphEdge.Kind.CAVE, e.kind);
      Assertions.assertTrue(g.route(surface, below, MobilityProfile.land()).ok());
   }

   @Test
   void learnRejectsUnobservedDestination() {
      WorldGraph g = new WorldGraph();
      GraphNode src = n("w", "s", "L", "a");
      GraphNode dst = n("w", "t", "L", "a");
      g.observe(src);
      Assertions.assertNull(g.learn(src, dst, GraphEdge.Kind.CELLAR_STAIRS, "stairs"));
      Assertions.assertNull(g.learn(src, null, GraphEdge.Kind.CAVE, "x"));
   }

   @Test
   void strategicWalkThenTransitionIsDeterministic() {
      WorldGraph g = new WorldGraph();
      GraphNode a = n("w", "s0", "L", "a");
      GraphNode b = n("w", "s1", "L", "a");
      GraphNode c = n("w", "s2", "L", "a");
      g.observe(a);
      g.observe(b);
      g.observe(c);
      g.learn(a, b, GraphEdge.Kind.DOOR_GATE, "d1");
      g.learn(b, c, GraphEdge.Kind.CELLAR_STAIRS, "st");
      WorldRouter.Result r = WorldRouter.route(g, a, c, MobilityProfile.land(), false);
      Assertions.assertTrue(r.ok());
      Assertions.assertEquals(2, r.hops.size());
      Assertions.assertEquals(WorldRouter.Hop.Kind.TRANSITION, r.hops.get(0).kind);
      Assertions.assertEquals(GraphEdge.Kind.DOOR_GATE, r.hops.get(0).edge.kind);
      Assertions.assertEquals(GraphEdge.Kind.CELLAR_STAIRS, r.hops.get(1).edge.kind);
   }

   @Test
   void sameWalkRegionUsesLocalPlannerNotGraphSearch() {
      WorldGraph g = new WorldGraph();
      GraphNode a = n("w", "s", "L", "g0");
      GraphNode b = n("w", "s", "L", "g1");
      g.observe(a);
      WorldRouter.Result r = WorldRouter.route(g, a, b, MobilityProfile.land(), false);
      Assertions.assertTrue(r.ok());
      Assertions.assertEquals(1, r.hops.size());
      Assertions.assertEquals(WorldRouter.Hop.Kind.WALK, r.hops.get(0).kind);
   }

   @Test
   void staleEdgeIsSkippedAndDoesNotRetry() {
      WorldGraph g = new WorldGraph();
      GraphNode a = n("w", "s0", "L", "a");
      GraphNode b = n("w", "s1", "L", "a");
      g.observe(a);
      g.observe(b);
      GraphEdge e = g.learn(a, b, GraphEdge.Kind.MINEHOLE, "mh");
      e.invalidate();
      WorldGraph.Path path = g.route(a, b, MobilityProfile.land());
      Assertions.assertFalse(path.ok());
      Assertions.assertEquals("stale_graph_edge", path.reason);
      WorldRouter.Result r = WorldRouter.route(g, a, b, MobilityProfile.land(), false);
      Assertions.assertEquals(NavOutcome.TRANSITION_FAILED, r.outcome);
      Assertions.assertEquals("stale_graph_edge", r.reason);
   }

   @Test
   void waterWalkRejectedWithoutSwimOrBoat() {
      WorldGraph g = new WorldGraph();
      GraphNode shore = n("w", "s", "L", "land");
      GraphNode sea = n("w", "s", "L", "sea");
      g.observe(shore);
      g.observe(sea);
      Assertions.assertNotNull(g.learnWalk(shore, sea, true));
      WorldGraph.Path land = g.route(shore, sea, MobilityProfile.land());
      Assertions.assertFalse(land.ok());
      Assertions.assertEquals("mobility_unavailable", land.reason);
      Assertions.assertTrue(g.route(shore, sea, MobilityProfile.boat()).ok());
      Assertions.assertTrue(g.route(shore, sea, MobilityProfile.swim()).ok());
   }

   @Test
   void unknownDestinationDoesNotFabricateANode() {
      WorldGraph g = new WorldGraph();
      GraphNode a = n("w", "s", "L", "a");
      g.observe(a);
      WorldRouter.Result r = WorldRouter.route(g, a, n("w", "other", "L", "a"), MobilityProfile.land(), false);
      Assertions.assertEquals(NavOutcome.UNKNOWN_TERRAIN, r.outcome);
      Assertions.assertEquals("landing_unknown", r.reason);
      WorldRouter.Result ex = WorldRouter.route(g, a, n("w", "other", "L", "a"), MobilityProfile.land(), true);
      Assertions.assertEquals(1, ex.hops.size());
      Assertions.assertEquals(WorldRouter.Hop.Kind.EXPLORE, ex.hops.get(0).kind);
   }

   @Test
   void originalPointGoalSurvivesGraphRouting() {
      NavGoal goal = NavGoal.point(Coord2d.of(100.0, 20.0));
      WorldGraph g = new WorldGraph();
      GraphNode a = n("w", "s0", "L", "a");
      GraphNode b = n("w", "s1", "L", "a");
      g.observe(a);
      g.observe(b);
      g.learn(a, b, GraphEdge.Kind.HEARTH, "h");
      WorldRouter.Result r = WorldRouter.route(g, a, b, MobilityProfile.land(), false);
      Assertions.assertTrue(r.ok());
      Assertions.assertEquals(NavGoal.Kind.POINT, goal.kind);
      Assertions.assertEquals(100.0, goal.position.x, 1.0E-9);
   }

   @Test
   void waterTilesNeedSwimOrBoat() {
      Assertions.assertTrue(TerrainPolicy.isWaterTile("gfx/tiles/water"));
      Assertions.assertTrue(TerrainPolicy.terrainBlocks("gfx/tiles/water", MobilityProfile.land()));
      Assertions.assertFalse(TerrainPolicy.terrainBlocks("gfx/tiles/water", MobilityProfile.boat()));
      Assertions.assertFalse(TerrainPolicy.terrainBlocks("gfx/tiles/water", MobilityProfile.swim()));
      Assertions.assertFalse(TerrainPolicy.terrainBlocks("gfx/tiles/grass", MobilityProfile.land()));
      Assertions.assertTrue(TerrainPolicy.agentRadius(MobilityProfile.boat(), 4.5) > TerrainPolicy.agentRadius(MobilityProfile.land(), 4.5));
      Assertions.assertTrue(TerrainPolicy.agentRadius(MobilityProfile.cart(), 4.5) > TerrainPolicy.agentRadius(MobilityProfile.land(), 4.5));
      Assertions.assertEquals(CoarseTileSource.Tile.BLOCKED, CoarseTileSource.classify("gfx/tiles/water", MobilityProfile.land()));
      Assertions.assertEquals(CoarseTileSource.Tile.FREE, CoarseTileSource.classify("gfx/tiles/water", MobilityProfile.boat()));
   }

   @Test
   void exploreSelectsKnownSafeFrontierAndReplansAfterExpansion() {
      Tiles src = new Tiles(12, 8);
      src.fill(6, 0, 11, 7, CoarseTileSource.Tile.UNKNOWN);
      Coord start = Coord.of(1, 4);
      Coord dest = Coord.of(10, 4);
      ExploreFrontier.Result first = ExploreFrontier.select(src, start, dest, 64);
      Assertions.assertTrue(first.picked());
      Assertions.assertEquals(NavOutcome.PARTIAL, first.outcome);
      Assertions.assertNotEquals(CoarseTileSource.Tile.UNKNOWN, src.tile(first.cell.x, first.cell.y));
      src.fill(6, 0, 11, 7, CoarseTileSource.Tile.FREE);
      ExploreFrontier.Result second = ExploreFrontier.select(src, start, dest, 128);
      Assertions.assertEquals(dest, second.cell);
      Assertions.assertNull(second.outcome);
   }

   @Test
   void exploreBudgetExhaustedAndUnknownTerrain() {
      Tiles src = new Tiles(20, 8);
      src.fill(2, 0, 19, 7, CoarseTileSource.Tile.UNKNOWN);
      ExploreFrontier.Result budget = ExploreFrontier.select(src, Coord.of(0, 4), Coord.of(19, 4), 1);
      Assertions.assertEquals(NavOutcome.BUDGET_EXHAUSTED, budget.outcome);
      Tiles wall = new Tiles(10, 6);
      wall.fill(4, 0, 4, 5, CoarseTileSource.Tile.BLOCKED);
      ExploreFrontier.Result none = ExploreFrontier.select(wall, Coord.of(1, 3), Coord.of(8, 3), 64);
      Assertions.assertEquals(NavOutcome.UNKNOWN_TERRAIN, none.outcome);
      Assertions.assertNull(none.cell);
   }

   @Test
   void exploreWithoutHintPicksNearestKnownSafeFrontier() {
      Tiles src = new Tiles(12, 8);
      src.fill(6, 0, 11, 7, CoarseTileSource.Tile.UNKNOWN);
      ExploreFrontier.Result r = ExploreFrontier.select(src, Coord.of(1, 4), null, 64);
      Assertions.assertTrue(r.picked());
      Assertions.assertEquals(Coord.of(5, 0), r.cell);
      Assertions.assertNotEquals(CoarseTileSource.Tile.UNKNOWN, src.tile(r.cell.x, r.cell.y));
   }

   private static final class Tiles implements CoarseTileSource {
      final int w;
      final int h;
      final Tile[] t;

      Tiles(int w, int h) {
         this.w = w;
         this.h = h;
         this.t = new Tile[w * h];
         Arrays.fill(this.t, Tile.FREE);
      }

      void fill(int x0, int y0, int x1, int y1, Tile v) {
         for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
               this.t[y * this.w + x] = v;
            }
         }
      }

      public int width() {
         return this.w;
      }

      public int height() {
         return this.h;
      }

      public Tile tile(int x, int y) {
         return this.t[y * this.w + x];
      }
   }
}
