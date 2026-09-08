package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.pathfinding.CoarseRoutePlanner.Route;
import haven.pathfinding.CoarseTileSource.Tile;
import haven.pathfinding.GridAStar.Result;
import haven.pathfinding.NavigationTestSpotSelector.Candidate;
import haven.pathfinding.NavigationTestSpotSelector.Interaction;
import haven.pathfinding.NavigationTestSpotSelector.Profile;
import haven.pathfinding.NavigationTestSpotSelector.Refusal;
import haven.pathfinding.NavigationTestSpotSelector.Selection;
import haven.pathfinding.NavigationTestSpotSelector.SpotRange;
import haven.pathfinding.NavigationTestSpotSelector.Status;
import haven.pathfinding.PathfinderLog.Occupancy;
import haven.pathfinding.PrototypePathfinder.GobGeom;
import haven.pathfinding.PrototypePathfinder.Scene;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class NavigationTestSpotSelectorTest {
   private static final double CELL = 2.75;

   private static Scene scene(int w, int h, char[][] g, double px, double py) {
      boolean[] solid = new boolean[w * h];
      boolean[] dilated = new boolean[w * h];

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            char c = g[y][x];
            if (c == '#') {
               solid[y * w + x] = true;
            } else if (c == '+') {
               dilated[y * w + x] = true;
            }
         }
      }

      Scene s = new Scene();
      s.origin = Coord2d.of(0.0, 0.0);
      s.w = w;
      s.h = h;
      s.cell = 2.75;
      s.player = Coord2d.of(px, py);
      s.playerCell = s.cellOf(s.player);
      s.occupancy = Occupancy.capture(s.origin, w, h, 2.75, solid, dilated, dilated, s.playerCell, null, null, Collections.emptyList());
      return s;
   }

   private static double cellWorld(int i) {
      return ((double)i + 0.5) * 2.75;
   }

   private static char[][] fill(int w, int h, char c) {
      char[][] g = new char[h][w];

      for (char[] row : g) {
         Arrays.fill(row, c);
      }

      return g;
   }

   private static CoarseTileSource names(int w, int h, String name) {
      String[] names = new String[w * h];
      Arrays.fill(names, name);
      return CoarseTileSource.fromNames(w, h, names);
   }

   private static double distTiles(Coord a, Coord b) {
      return Math.sqrt((double)((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)));
   }

   @Test
   void openGroundSelectsClearBodyFreeDestinationDeterministically() {
      Scene s = scene(40, 40, fill(40, 40, ' '), cellWorld(5), cellWorld(5));
      Selection sel = NavigationTestSpotSelector.openGround(s, new SpotRange(11.0, 41.25, 1));
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(Profile.OPEN_GROUND, sel.profile);
      Assertions.assertNull(sel.refusal);
      Assertions.assertEquals(Coord.of(20, 5), sel.targetTile);
      Assertions.assertNotNull(sel.targetWorld);
      Assertions.assertTrue(s.bodyFree(sel.targetWorld), "the selected target must be body-free");
      double dist = s.player.dist(sel.targetWorld);
      Assertions.assertTrue(dist >= 11.0 && dist <= 41.250000001, "selected target must lie within the requested band");
      Assertions.assertTrue(sel.candidates.size() >= 4);

      for (int i = 1; i < sel.candidates.size(); i++) {
         Assertions.assertTrue(
            ((Candidate)sel.candidates.get(i - 1)).score >= ((Candidate)sel.candidates.get(i)).score, "candidates must be ordered by score descending"
         );
      }

      Assertions.assertTrue(sel.evidence.contains("OPEN_GROUND"));
      Assertions.assertEquals(0L, sel.segment);
   }

   @Test
   void openGroundPrefersClearGroundOverFarthestWallHuggingCell() {
      char[][] g = fill(40, 40, ' ');

      for (int y = 0; y < 40; y++) {
         for (int x = 35; x < 40; x++) {
            g[y][x] = '#';
         }
      }

      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      Selection sel = NavigationTestSpotSelector.openGround(s, new SpotRange(11.0, 82.5, 1));
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(Coord.of(23, 29), sel.targetTile);
      Assertions.assertTrue(sel.targetTile.x <= 27, "the target must stay at least 8 cells clear of the wall");

      for (Candidate c : sel.candidates) {
         Assertions.assertTrue(NavigationTestSpotSelector.openness(s)[c.tile.y * s.w + c.tile.x] >= 1);
      }
   }

   @Test
   void openGroundRefusesWhenNoClearCandidateExists() {
      char[][] g = fill(20, 20, '#');

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            g[10 + dy][10 + dx] = ' ';
         }
      }

      Scene s = scene(20, 20, g, cellWorld(10), cellWorld(10));
      Selection sel = NavigationTestSpotSelector.openGround(s, new SpotRange(5.5, 22.0, 1));
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_CANDIDATE, sel.refusal);
      Assertions.assertNull(sel.targetTile);
      Assertions.assertNull(sel.targetWorld);
      Assertions.assertTrue(sel.candidates.isEmpty());
      Assertions.assertTrue(sel.evidence.contains("NO_CANDIDATE"));
      Assertions.assertTrue(sel.evidence.contains("0 with clearance >= 1"));
   }

   @Test
   void openGroundRefusesMissingOrInvalidInputsPrecisely() {
      Assertions.assertEquals(Refusal.NO_SCENE, NavigationTestSpotSelector.openGround(null, new SpotRange(1.0, 5.0, 1)).refusal);
      Scene empty = new Scene();
      Assertions.assertEquals(Refusal.NO_OCCUPANCY, NavigationTestSpotSelector.openGround(empty, new SpotRange(1.0, 5.0, 1)).refusal);
      Scene noPlayer = scene(20, 20, fill(20, 20, ' '), cellWorld(2), cellWorld(2));
      noPlayer.player = null;
      Assertions.assertEquals(Refusal.PLAYER_UNKNOWN, NavigationTestSpotSelector.openGround(noPlayer, new SpotRange(1.0, 5.0, 1)).refusal);
      Scene offGrid = scene(20, 20, fill(20, 20, ' '), 500.0, 500.0);
      Assertions.assertEquals(Refusal.PLAYER_OFF_GRID, NavigationTestSpotSelector.openGround(offGrid, new SpotRange(1.0, 5.0, 1)).refusal);
      Scene ok = scene(20, 20, fill(20, 20, ' '), cellWorld(2), cellWorld(2));
      Assertions.assertEquals(Refusal.BOUNDS_INVALID, NavigationTestSpotSelector.openGround(ok, new SpotRange(9.0, 5.0, 1)).refusal, "minDist >= maxDist");
      Assertions.assertEquals(Refusal.BOUNDS_INVALID, NavigationTestSpotSelector.openGround(ok, new SpotRange(-1.0, 5.0, 1)).refusal, "negative minDist");
      Assertions.assertEquals(Refusal.BOUNDS_INVALID, NavigationTestSpotSelector.openGround(ok, new SpotRange(1.0, 5.0, -1)).refusal, "negative clearance");
      Assertions.assertEquals(Refusal.BOUNDS_INVALID, NavigationTestSpotSelector.openGround(ok, null).refusal, "null range");
      Selection r = NavigationTestSpotSelector.openGround(offGrid, new SpotRange(1.0, 5.0, 1));
      Assertions.assertTrue(r.evidence.contains("PLAYER_OFF_GRID"));
   }

   @Test
   void openGroundDefaultRangeSelectsOnAClearedRoom() {
      Scene s = scene(60, 60, fill(60, 60, ' '), cellWorld(30), cellWorld(30));
      Selection sel = NavigationTestSpotSelector.openGround(s);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      double dist = s.player.dist(sel.targetWorld);
      Assertions.assertTrue(dist >= 11.0 && dist <= 41.25, "default range is 4..15 tiles");
   }

   private static Scene gapWallScene() {
      char[][] g = fill(40, 40, ' ');

      for (int y = 0; y < 40; y++) {
         if (y != 19) {
            g[y][15] = '#';
         }
      }

      return scene(40, 40, g, cellWorld(5), cellWorld(5));
   }

   @Test
   void obstacleProfileSelectsOccludedTargetReachedThroughGap() {
      Scene s = gapWallScene();
      Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(s, new SpotRange(8.25, 68.75, 0), 100000);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(Profile.LOCAL_OBSTACLE_OR_CORRIDOR, sel.profile);
      Assertions.assertTrue(sel.targetTile.x >= 16, "the target must lie beyond the wall");
      Assertions.assertTrue(s.bodyFree(sel.targetWorld), "the target must be body-free");
      Candidate best = (Candidate)sel.candidates.get(0);
      Assertions.assertFalse(best.route.isEmpty());
      Assertions.assertEquals(Coord.of(5, 5), best.route.get(0), "route starts at the player cell");
      Assertions.assertEquals(sel.targetTile, best.route.get(best.route.size() - 1), "route ends at the target cell");
      Assertions.assertTrue(best.route.contains(Coord.of(15, 19)), "route must pass through the gap");

      for (Candidate c : sel.candidates) {
         double direct = s.player.dist(c.world);
         double route = NavigationTestSpotSelector.routeLengthWorld(s, c.route);
         Assertions.assertTrue(
            route > direct * 1.05, "candidate " + c.tile + " must be a material detour (route " + route + " vs direct " + direct + "): " + c.note
         );
         Assertions.assertTrue(c.note.contains("detour ratio"), c.note);
      }

      for (int i = 1; i < sel.candidates.size(); i++) {
         Assertions.assertTrue(((Candidate)sel.candidates.get(i - 1)).score >= ((Candidate)sel.candidates.get(i)).score);
      }

      Assertions.assertTrue(sel.evidence.contains("occluded"));
      Assertions.assertTrue(sel.evidence.contains("material-detour candidates"), sel.evidence);
      Selection again = NavigationTestSpotSelector.localObstacleOrCorridor(s, new SpotRange(8.25, 68.75, 0), 100000);
      Assertions.assertEquals(sel.targetTile, again.targetTile);
      Assertions.assertEquals(sel.candidates.toString(), again.candidates.toString());
   }

   @Test
   void obstacleProfileRefusesWhenOccludedTargetsAreUnreachable() {
      char[][] g = fill(40, 40, ' ');

      for (int y = 0; y < 40; y++) {
         g[y][15] = '#';
      }

      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(s, new SpotRange(8.25, 68.75, 0), 100000);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_CANDIDATE, sel.refusal);
      Assertions.assertTrue(sel.evidence.contains("0 reachable"), sel.evidence);
   }

   private static Scene trivialOcclusionCorridor() {
      char[][] g = fill(40, 40, '#');

      for (int y = 0; y < 40; y++) {
         g[y][10] = ' ';
      }

      g[10][10] = '+';
      g[11][10] = '+';
      return scene(40, 40, g, cellWorld(10), cellWorld(10));
   }

   @Test
   void obstacleProfileRefusesTrivialOcclusionAsNotMaterialDetour() {
      Scene s = trivialOcclusionCorridor();
      Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(s, new SpotRange(11.0, 41.25, 0), 100000);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_MATERIAL_DETOUR, sel.refusal);
      Assertions.assertNull(sel.targetTile);
      Assertions.assertNull(sel.targetWorld);
      Assertions.assertTrue(sel.candidates.isEmpty());
      Assertions.assertTrue(sel.evidence.contains("NO_MATERIAL_DETOUR"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("occluded direct line"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("verified route"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("0 a material detour"), sel.evidence);
      Assertions.assertFalse(sel.evidence.contains("0 reachable"), "trivial occlusion has verified routes; it is not a no-route refusal: " + sel.evidence);
      Coord start = s.cellOf(s.player);

      for (int y = 14; y <= 25; y++) {
         Coord cell = Coord.of(10, y);
         Assertions.assertTrue(NavigationTestSpotSelector.lineOccluded(s, start, cell), "cell " + cell + " must be line-occluded");
         Result r = NavigationTestSpotSelector.reachable(s, start, cell, 100000);
         Assertions.assertTrue(r.complete && !r.cells.isEmpty(), "cell " + cell + " must be reachable");
         double direct = s.player.dist(Coord2d.of(cellWorld(10), cellWorld(y)));
         double route = NavigationTestSpotSelector.routeLengthWorld(s, r.cells);
         Assertions.assertTrue(route <= direct * 1.05, "cell " + cell + " route " + route + " vs direct " + direct + " must fail the material-detour bar");
      }
   }

   @Test
   void obstacleProfileDistinguishesSealedCorridorNoRouteFromTrivialOcclusion() {
      char[][] g = fill(40, 40, '#');

      for (int y = 0; y < 40; y++) {
         g[y][10] = ' ';
      }

      g[11][10] = '+';
      Scene s = scene(40, 40, g, cellWorld(10), cellWorld(10));
      Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(s, new SpotRange(11.0, 41.25, 0), 100000);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_CANDIDATE, sel.refusal);
      Assertions.assertTrue(sel.evidence.contains("0 reachable"), sel.evidence);
   }

   @Test
   void obstacleProfileRefusesWhenNothingIsOccluded() {
      Scene s = scene(40, 40, fill(40, 40, ' '), cellWorld(5), cellWorld(5));
      Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(s, new SpotRange(8.25, 55.0, 0), 100000);
      Assertions.assertEquals(Refusal.NO_CANDIDATE, sel.refusal);
      Assertions.assertTrue(sel.evidence.contains("0 with an occluded direct line"), sel.evidence);
   }

   @Test
   void obstacleProfileRefusesMissingInputsPrecisely() {
      Assertions.assertEquals(Refusal.NO_SCENE, NavigationTestSpotSelector.localObstacleOrCorridor(null, new SpotRange(1.0, 5.0, 0), 1000).refusal);
      Assertions.assertEquals(
         Refusal.BOUNDS_INVALID, NavigationTestSpotSelector.localObstacleOrCorridor(gapWallScene(), new SpotRange(5.0, 5.0, 0), 1000).refusal
      );
   }

   private static CoarseTileSource riverWithGap() {
      String[] names = new String[1600];
      Arrays.fill(names, "gfx/tiles/grass");

      for (int y = 0; y < 40; y++) {
         if (y != 35) {
            for (int x = 15; x <= 20; x++) {
               names[y * 40 + x] = "gfx/tiles/cave";
            }
         }
      }

      return CoarseTileSource.fromNames(40, 40, names);
   }

   @Test
   void longLegSelectsFarthestKnownFreeReachableTile() {
      CoarseTileSource src = riverWithGap();
      Selection sel = NavigationTestSpotSelector.knownMapLongLeg(src, 4660L, Coord.of(2, 20), 4.0, 30.0, 1000000);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(Profile.KNOWN_MAP_LONG_LEG, sel.profile);
      Assertions.assertEquals(4660L, sel.segment);
      Assertions.assertNull(sel.targetWorld, "coarse selection is tile-space; no invented world coordinates");
      Coord t = sel.targetTile;
      Assertions.assertNotNull(t);
      Assertions.assertEquals(Tile.FREE, src.tile(t.x, t.y), "the target must be a known-free tile");
      double dist = distTiles(Coord.of(2, 20), t);
      Assertions.assertTrue(dist >= 4.0 && dist <= 30.000000001, "target must lie in the requested tile band");
      Assertions.assertTrue(t.x > 20, "the river forces the target to the far (farthest) side");
      Route r = CoarseRoutePlanner.plan(src, Coord.of(2, 20), t, 1000000);
      Assertions.assertEquals(haven.pathfinding.CoarseRoutePlanner.Status.REACHED, r.status);
      Assertions.assertEquals(Coord.of(2, 20), r.waypoints.get(0));
      Assertions.assertEquals(t, r.waypoints.get(r.waypoints.size() - 1));
      Assertions.assertEquals(t, ((Candidate)sel.candidates.get(0)).tile);
      Assertions.assertTrue(sel.evidence.contains("KNOWN_MAP_LONG_LEG"));
   }

   @Test
   void longLegTieBreaksByRowMajorOrder() {
      CoarseTileSource src = names(21, 21, "gfx/tiles/grass");
      Selection a = NavigationTestSpotSelector.knownMapLongLeg(src, 7L, Coord.of(10, 10), 10.0, 10.0, 100000);
      Selection b = NavigationTestSpotSelector.knownMapLongLeg(src, 7L, Coord.of(10, 10), 10.0, 10.0, 100000);
      Assertions.assertTrue(a.selected(), a.evidence);
      Assertions.assertEquals(Coord.of(10, 0), a.targetTile, "exact-distance ties resolve to the row-major-first tile");
      Assertions.assertEquals(a.candidates.toString(), b.candidates.toString());

      for (int i = 1; i < a.candidates.size(); i++) {
         Assertions.assertTrue(((Candidate)a.candidates.get(i - 1)).score >= ((Candidate)a.candidates.get(i)).score);
      }

      for (Candidate c : a.candidates) {
         Assertions.assertFalse(c.route.isEmpty());
         Assertions.assertEquals(c.tile, c.route.get(c.route.size() - 1));
      }
   }

   @Test
   void longLegRefusesUnknownStartBoundsAndSource() {
      CoarseTileSource src = names(10, 10, "gfx/tiles/cave");
      Assertions.assertEquals(Refusal.NO_SOURCE, NavigationTestSpotSelector.knownMapLongLeg(null, 1L, Coord.of(0, 0), 1.0, 5.0, 100).refusal);
      Assertions.assertEquals(
         Refusal.START_UNKNOWN,
         NavigationTestSpotSelector.knownMapLongLeg(src, 1L, Coord.of(0, 0), 1.0, 5.0, 100).refusal,
         "a known-blocked start is unknown/unusable"
      );
      Assertions.assertEquals(
         Refusal.START_UNKNOWN,
         NavigationTestSpotSelector.knownMapLongLeg(src, 1L, Coord.of(10, 0), 1.0, 5.0, 100).refusal,
         "an off-bounds start is unknown/unusable"
      );
      Assertions.assertEquals(Refusal.START_UNKNOWN, NavigationTestSpotSelector.knownMapLongLeg(src, 1L, null, 1.0, 5.0, 100).refusal);
      CoarseTileSource grass = names(10, 10, "gfx/tiles/grass");
      Assertions.assertEquals(Refusal.BOUNDS_INVALID, NavigationTestSpotSelector.knownMapLongLeg(grass, 1L, Coord.of(0, 0), 10.0, 5.0, 100).refusal);
      Assertions.assertEquals(Refusal.BOUNDS_INVALID, NavigationTestSpotSelector.knownMapLongLeg(grass, 1L, Coord.of(0, 0), -1.0, 5.0, 100).refusal);
      Selection r = NavigationTestSpotSelector.knownMapLongLeg(src, 1L, Coord.of(0, 0), 1.0, 5.0, 100);
      Assertions.assertTrue(r.evidence.contains("START_UNKNOWN"));
   }

   @Test
   void longLegAggregateBudgetExhaustionYieldsTypedRefusalWithEvidence() {
      CoarseTileSource src = riverWithGap();
      Selection sel = NavigationTestSpotSelector.knownMapLongLeg(src, 119L, Coord.of(2, 20), 4.0, 30.0, 100000, 1);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.BUDGET_EXHAUSTED, sel.refusal);
      Assertions.assertNull(sel.targetTile);
      Assertions.assertNull(sel.targetWorld);
      Assertions.assertTrue(sel.candidates.isEmpty());
      Assertions.assertTrue(sel.evidence.contains("BUDGET_EXHAUSTED"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("aggregate coarse route-search budget 1"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("after 1 in-band candidate route searches"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("refusing rather than continuing unbounded aggregate search"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("per-candidate cap 100000"), sel.evidence);
      int total = aggregateTotalFrom(sel.evidence);
      Assertions.assertTrue(total > 1 && total <= 100001, "reported total " + total + " must overshoot the cap by at most one search: " + sel.evidence);
      Selection again = NavigationTestSpotSelector.knownMapLongLeg(src, 119L, Coord.of(2, 20), 4.0, 30.0, 100000, 1);
      Assertions.assertEquals(sel.evidence, again.evidence);
   }

   private static int aggregateTotalFrom(String evidence) {
      Matcher m = Pattern.compile("(\\d+) expansions total").matcher(evidence);
      Assertions.assertTrue(m.find(), "evidence must carry the aggregate running total: " + evidence);
      return Integer.parseInt(m.group(1));
   }

   @Test
   void longLegZeroAggregateBudgetRefusesImmediately() {
      Selection sel = NavigationTestSpotSelector.knownMapLongLeg(riverWithGap(), 1L, Coord.of(2, 20), 4.0, 30.0, 100000, 0);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.BUDGET_EXHAUSTED, sel.refusal);
      Assertions.assertNull(sel.targetTile);
      Assertions.assertTrue(sel.candidates.isEmpty());
      Assertions.assertTrue(sel.evidence.contains("no route search permitted"), sel.evidence);
   }

   @Test
   void longLegAggregateBudgetPermitsNormalSelection() {
      CoarseTileSource src = riverWithGap();
      Selection viaDefault = NavigationTestSpotSelector.knownMapLongLeg(src, 153L, Coord.of(2, 20), 4.0, 30.0, 100000);
      Selection viaExplicit = NavigationTestSpotSelector.knownMapLongLeg(src, 153L, Coord.of(2, 20), 4.0, 30.0, 100000, 100000000);
      Assertions.assertTrue(viaExplicit.selected(), viaExplicit.evidence);
      Assertions.assertEquals(viaDefault.targetTile, viaExplicit.targetTile);
      Assertions.assertEquals(viaDefault.candidates.toString(), viaExplicit.candidates.toString());

      for (Candidate c : viaExplicit.candidates) {
         Assertions.assertFalse(c.route.isEmpty());
         Assertions.assertEquals(c.tile, c.route.get(c.route.size() - 1));
      }

      Assertions.assertTrue(true);
   }

   @Test
   void longLegRefusesEmptyBandOrUnreachableRegion() {
      CoarseTileSource grass = names(20, 20, "gfx/tiles/grass");
      Selection emptyBand = NavigationTestSpotSelector.knownMapLongLeg(grass, 1L, Coord.of(2, 2), 50.0, 60.0, 1000);
      Assertions.assertEquals(Refusal.NO_CANDIDATE, emptyBand.refusal);
      Assertions.assertTrue(emptyBand.evidence.contains("0 known-free tiles in band"), emptyBand.evidence);
      String[] sealed = new String[400];
      Arrays.fill(sealed, "gfx/tiles/grass");

      for (int y = 0; y < 20; y++) {
         for (int x = 0; x < 20; x++) {
            int dx = Math.abs(x - 5);
            int dy = Math.abs(y - 5);
            if (Math.max(dx, dy) == 2) {
               sealed[y * 20 + x] = "gfx/tiles/cave";
            }
         }
      }

      CoarseTileSource ring = CoarseTileSource.fromNames(20, 20, sealed);
      Selection noRoute = NavigationTestSpotSelector.knownMapLongLeg(ring, 1L, Coord.of(5, 5), 3.0, 10.0, 100000);
      Assertions.assertEquals(Refusal.NO_KNOWN_ROUTE, noRoute.refusal);
      Assertions.assertTrue(noRoute.evidence.contains("NO_KNOWN_ROUTE"));
   }

   @Test
   void malformedOccupancyArrayYieldsTypedRefusalNotAioobe() {
      Scene shortOcc = scene(20, 20, fill(20, 20, ' '), cellWorld(2), cellWorld(2));
      shortOcc.occupancy = new Occupancy(
         shortOcc.origin,
         shortOcc.w,
         shortOcc.h,
         2.75,
         Arrays.copyOf(shortOcc.occupancy.occ, shortOcc.w * shortOcc.h - 1),
         shortOcc.playerCell,
         null,
         null,
         Collections.emptyList()
      );
      Selection og = NavigationTestSpotSelector.openGround(shortOcc, new SpotRange(1.0, 5.0, 1));
      Assertions.assertEquals(Status.REFUSED, og.status);
      Assertions.assertEquals(Refusal.NO_OCCUPANCY, og.refusal);
      Assertions.assertTrue(og.evidence.contains("occupancy lattice is 399 cells"), og.evidence);
      Selection ob = NavigationTestSpotSelector.localObstacleOrCorridor(shortOcc, new SpotRange(1.0, 5.0, 0), 1000);
      Assertions.assertEquals(Refusal.NO_OCCUPANCY, ob.refusal);
      Scene nullOcc = scene(20, 20, fill(20, 20, ' '), cellWorld(2), cellWorld(2));
      nullOcc.occupancy = null;
      Assertions.assertEquals(Refusal.NO_OCCUPANCY, NavigationTestSpotSelector.openGround(nullOcc, new SpotRange(1.0, 5.0, 1)).refusal);
   }

   @Test
   void transitionAndCartProfilesAreDeclaredButNeverSelected() {
      Scene s = scene(20, 20, fill(20, 20, ' '), cellWorld(2), cellWorld(2));

      for (Profile p : List.of(Profile.TRANSITION_APPROACH, Profile.CART_OR_PROPERTY)) {
         Selection sel = NavigationTestSpotSelector.select(s, p, new SpotRange(1.0, 5.0, 1));
         Assertions.assertFalse(p.supported, p + " must be marked unsupported");
         Assertions.assertEquals(Status.REFUSED, sel.status);
         Assertions.assertEquals(Refusal.UNSUPPORTED_PROFILE, sel.refusal);
         Assertions.assertNull(sel.targetTile);
         Assertions.assertTrue(sel.candidates.isEmpty());
         Assertions.assertTrue(sel.evidence.contains(p.name()), sel.evidence);
      }

      Assertions.assertEquals(3L, Arrays.stream(Profile.values()).filter(px -> px.supported).count());

      for (Profile p : List.of(Profile.OPEN_GROUND, Profile.LOCAL_OBSTACLE_OR_CORRIDOR, Profile.KNOWN_MAP_LONG_LEG)) {
         Assertions.assertTrue(p.supported);
         Assertions.assertEquals(Interaction.NO_INTERACTION, p.interaction);
      }

      Assertions.assertEquals(Interaction.TRANSITION_FUTURE, Profile.TRANSITION_APPROACH.interaction);
      Assertions.assertEquals(Interaction.NOT_SAFELY_INFERABLE, Profile.CART_OR_PROPERTY.interaction);
   }

   @Test
   void selectionIsDeterministicAndSideEffectFree() {
      char[][] g = fill(40, 40, ' ');

      for (int y = 0; y < 40; y++) {
         for (int x = 35; x < 40; x++) {
            g[y][x] = '#';
         }
      }

      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      byte[] occBefore = Arrays.copyOf(s.occupancy.occ, s.occupancy.occ.length);
      Coord playerCellBefore = s.playerCell;
      Coord2d playerBefore = s.player;
      SpotRange range = new SpotRange(11.0, 82.5, 1);
      Selection a = NavigationTestSpotSelector.openGround(s, range);
      Selection b = NavigationTestSpotSelector.openGround(s, range);
      Assertions.assertEquals(a.targetTile, b.targetTile);
      Assertions.assertEquals(a.targetWorld, b.targetWorld);
      Assertions.assertEquals(a.evidence, b.evidence);
      Assertions.assertEquals(a.candidates.toString(), b.candidates.toString());
      Assertions.assertArrayEquals(occBefore, s.occupancy.occ, "selection must never mutate the occupancy lattice");
      Assertions.assertEquals(playerCellBefore, s.playerCell);
      Assertions.assertEquals(playerBefore, s.player);
      Scene withGobs = scene(40, 40, g, cellWorld(5), cellWorld(5));
      Scene withGobsShuffled = scene(40, 40, g, cellWorld(5), cellWorld(5));

      for (long id : new long[]{7L, 1L, 42L}) {
         GobGeom gob = new GobGeom();
         gob.id = id;
         gob.rc = Coord2d.of((double)id * 3.0, (double)id * 7.0);
         withGobs.gobs.add(gob);
      }

      for (long id : new long[]{42L, 7L, 1L}) {
         GobGeom gob = new GobGeom();
         gob.id = id;
         gob.rc = Coord2d.of((double)id * 3.0, (double)id * 7.0);
         withGobsShuffled.gobs.add(gob);
      }

      Selection c = NavigationTestSpotSelector.openGround(withGobs, range);
      Selection d = NavigationTestSpotSelector.openGround(withGobsShuffled, range);
      Assertions.assertEquals(a.targetTile, c.targetTile, "gob presence must not change the selection");
      Assertions.assertEquals(c.targetTile, d.targetTile, "gob iteration order must not change the selection");
      CoarseTileSource src = riverWithGap();
      Selection la = NavigationTestSpotSelector.knownMapLongLeg(src, 9L, Coord.of(2, 20), 4.0, 30.0, 1000000);
      Selection lb = NavigationTestSpotSelector.knownMapLongLeg(src, 9L, Coord.of(2, 20), 4.0, 30.0, 1000000);
      Assertions.assertEquals(la.targetTile, lb.targetTile);
      Assertions.assertEquals(la.candidates.toString(), lb.candidates.toString());
      Assertions.assertThrows(UnsupportedOperationException.class, () -> a.candidates.add(null));
      Assertions.assertThrows(UnsupportedOperationException.class, () -> la.candidates.add(null));
      List<Coord> route = selRoute();
      Assertions.assertThrows(UnsupportedOperationException.class, () -> route.add(null));
   }

   private static List<Coord> selRoute() {
      Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(gapWallScene(), new SpotRange(8.25, 68.75, 0), 100000);
      return ((Candidate)sel.candidates.get(0)).route;
   }
}
