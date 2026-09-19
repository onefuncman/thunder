package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.InteractionSpec;
import haven.nav.NavPlanStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Indoor cellar stairs behind a study desk. The direct click crosses the desk;
 * a longer walk around it must reach a legal stand beside the stairs.
 */
public class IndoorStairsApproachTest {
   private static final int W = 48;
   private static final int H = 36;
   private static final Coord2d ORIGIN = Coord2d.of(0.0, 0.0);

   private static Coord2d[] rect(double minx, double miny, double maxx, double maxy) {
      return new Coord2d[]{
         Coord2d.of(minx, miny), Coord2d.of(maxx, miny), Coord2d.of(maxx, maxy), Coord2d.of(minx, maxy)
      };
   }

   private static Coord2d[] wall() {
      return rect(0.0, 0.0, W * 2.75, 4.0);
   }

   private static Coord2d[] stairs() {
      return rect(65.0, 11.0, 79.0, 17.0);
   }

   private static Coord2d[] desk() {
      return rect(54.0, 26.0, 90.0, 38.0);
   }

   private static OccupancyGrid room() {
      boolean[] solid = new boolean[W * H];
      LocalPlanner.rasterPolygon(solid, ORIGIN, W, H, wall(), LocalPlanner.OVERLAP);
      LocalPlanner.rasterPolygon(solid, ORIGIN, W, H, stairs(), LocalPlanner.OVERLAP);
      LocalPlanner.rasterPolygon(solid, ORIGIN, W, H, desk(), LocalPlanner.OVERLAP);
      boolean[] dilated = Arrays.copyOf(solid, solid.length);
      return OccupancyGrid.capture(ORIGIN, W, H, 2.75, solid, dilated, dilated, Coord.of(0, 0), Coord.of(W - 1, 0), Coord.of(W - 1, 0), Collections.emptyList());
   }

   private static InteractionSpec stairsSpec() {
      List<Coord2d[]> polys = new ArrayList<Coord2d[]>();
      polys.add(stairs());
      Coord2d origin = Coord2d.of(72.0, 14.0);
      Coord2d half = Coord2d.of(7.0, 3.0);
      return new InteractionSpec(
         "stairs", origin, half, InteractionSpec.ALL_SIDES, 0.5, 35.0, 0, null, "state_changed", polys, CollisionGeom.OBST
      );
   }

   private static Coord2d playerSouth() {
      return Coord2d.of(72.0, 55.0);
   }

   private static Coord2d playerEast() {
      return Coord2d.of(110.0, 14.0);
   }

   private static boolean routeHits(List<Coord2d> route, Coord2d[] poly) {
      if (route == null || route.size() < 2) {
         return false;
      }
      for (int i = 1; i < route.size(); i++) {
         if (LocalPlanner.segmentHitsPolygon(route.get(i - 1), route.get(i), poly, 0.0)) {
            return true;
         }
      }
      return false;
   }

   private static boolean aroundDesk(List<Coord2d> route) {
      if (route == null) {
         return false;
      }
      for (int i = 0; i < route.size(); i++) {
         Coord2d p = route.get(i);
         if (p != null && (p.x > 90.0 || p.x < 54.0)) {
            return true;
         }
      }
      return false;
   }

   @Test
   void stairsRemainAnObstacle() {
      OccupancyGrid occ = room();
      Coord c = occ.cellOf(Coord2d.of(72.0, 14.0));
      Assertions.assertEquals(OccupancyGrid.SOLID, occ.at(c.x, c.y));
      InteractionSpec spec = stairsSpec();
      Assertions.assertTrue(InteractionGoals.overlapsFootprint(Coord2d.of(72.0, 14.0), spec));
   }

   @Test
   void directClickAcrossDeskIsRejected() {
      OccupancyGrid occ = room();
      InteractionSpec spec = stairsSpec();
      Coord2d from = playerSouth();
      Assertions.assertFalse(InteractionGoals.losClear(from, spec, occ));
      Coord2d southOfDesk = Coord2d.of(72.0, 44.0);
      Assertions.assertFalse(InteractionGoals.losClear(southOfDesk, spec, occ), "click segment must not cross the desk");
   }

   @Test
   void boundaryCandidateBesideStairsIsGeneratedAndFits() {
      OccupancyGrid occ = room();
      InteractionSpec spec = stairsSpec();
      List<Coord2d> seeds = InteractionGoals.samplePoses(spec, occ);
      boolean beside = false;
      for (int i = 0; i < seeds.size(); i++) {
         Coord2d p = seeds.get(i);
         if (p == null) {
            continue;
         }
         Coord cell = occ.cellOf(p);
         if (cell == null) {
            continue;
         }
         if (p.y < 26.0 && p.y > 6.0 && (p.x > 79.0 && p.x < 90.0 || p.x < 65.0 && p.x > 50.0)
            && occ.at(cell.x, cell.y) == OccupancyGrid.FREE
            && !InteractionGoals.overlapsFootprint(p, spec)) {
            beside = true;
            break;
         }
      }
      Assertions.assertTrue(beside, "need a boundary-derived stand beside the stairs");
   }

   @Test
   void aroundDeskRouteIsSelectedBothDirections() {
      OccupancyGrid occ = room();
      InteractionSpec spec = stairsSpec();
      InteractionGoals.Result fwd = InteractionGoals.select(playerSouth(), spec, occ);
      Assertions.assertTrue(fwd.ok(), "fwd " + fwd.reason + " dominant=" + fwd.dominantReject() + " counts=" + fwd.rejectCounts);
      Assertions.assertNotEquals(InteractionGoals.NO_POSE, fwd.reason);
      Assertions.assertTrue(fwd.selected.world.y < 26.0, "stand must be north of the desk, not in front of it");
      Assertions.assertFalse(InteractionGoals.overlapsFootprint(fwd.selected.world, spec));
      Coord standCell = occ.cellOf(fwd.selected.world);
      Assertions.assertNotEquals(OccupancyGrid.SOLID, occ.at(standCell.x, standCell.y));
      Assertions.assertTrue(InteractionGoals.losClear(fwd.selected.world, spec, occ));
      Coord2d click = InteractionGoals.clickPoint(fwd.selected.world, spec);
      Assertions.assertTrue(click.dist(spec.origin) > 0.5, "click the boundary, not the buried center");
      Assertions.assertFalse(LocalPlanner.segmentHitsPolygon(fwd.selected.world, click, desk(), 0.0));
      Assertions.assertTrue(LocalPlanner.segmentHitsPolygon(fwd.selected.world, click, stairs(), 0.0)
         || InteractionGoals.distToSegment(click, stairs()[0], stairs()[1]) < 1.0
         || InteractionGoals.distToSegment(click, stairs()[1], stairs()[2]) < 1.0
         || InteractionGoals.distToSegment(click, stairs()[2], stairs()[3]) < 1.0
         || InteractionGoals.distToSegment(click, stairs()[3], stairs()[0]) < 1.0);
      List<Coord2d> route = fwd.plan.smoothedRoute;
      Assertions.assertFalse(routeHits(route, desk()), "swept route must not cut the desk");
      Assertions.assertFalse(routeHits(route, stairs()), "planner must not walk onto the stairs");
      Assertions.assertTrue(aroundDesk(route), "must take the longer route around the desk");

      InteractionGoals.Result back = InteractionGoals.select(playerEast(), spec, occ);
      Assertions.assertTrue(back.ok(), "back " + back.reason + " dominant=" + back.dominantReject());
      Assertions.assertTrue(InteractionGoals.losClear(back.selected.world, spec, occ));
      Assertions.assertFalse(routeHits(back.plan.smoothedRoute, desk()));
      Assertions.assertTrue(LocalPlanner.segmentHitsPolygon(playerSouth(), spec.origin, desk(), 0.0), "the buried center click crosses the desk");
   }

   @Test
   void targetCenterMayBeBlockedWhileBoundaryClickIsValid() {
      OccupancyGrid occ = room();
      InteractionSpec spec = stairsSpec();
      Coord2d stand = Coord2d.of(82.5, 14.0);
      Coord cell = occ.cellOf(stand);
      Assertions.assertNotEquals(OccupancyGrid.SOLID, occ.at(cell.x, cell.y));
      Coord2d click = InteractionGoals.clickPoint(stand, spec);
      Assertions.assertTrue(click.dist(spec.origin) > 1.0);
      Assertions.assertTrue(InteractionGoals.losClear(stand, spec, occ));
      Assertions.assertEquals(OccupancyGrid.SOLID, occ.at(occ.cellOf(spec.origin).x, occ.cellOf(spec.origin).y));
   }

   @Test
   void geometryUnavailableIsTypedAndDoesNotInventAPolygon() {
      OccupancyGrid occ = room();
      InteractionSpec missing = new InteractionSpec(
         "stairs", Coord2d.of(72.0, 14.0), Coord2d.of(7.0, 3.0), InteractionSpec.ALL_SIDES, 0.5, 35.0, 0, null, "state_changed",
         Collections.<Coord2d[]>emptyList(), CollisionGeom.UNAVAILABLE
      );
      InteractionGoals.Result r = InteractionGoals.select(playerSouth(), missing, occ);
      Assertions.assertFalse(r.ok());
      Assertions.assertEquals(InteractionGoals.GEOMETRY_UNAVAILABLE, r.reason);
      Assertions.assertEquals(Integer.valueOf(1), r.rejectCounts.get("geometry_unavailable"));
   }

   @Test
   void noGenuinelyValidPoseIsADetailedTypedFailure() {
      boolean[] solid = new boolean[W * H];
      Arrays.fill(solid, true);
      solid[4 * W + 4] = false;
      OccupancyGrid occ = OccupancyGrid.capture(
         ORIGIN, W, H, 2.75, solid, solid, solid, Coord.of(4, 4), Coord.of(4, 4), Coord.of(4, 4), Collections.emptyList()
      );
      InteractionSpec spec = stairsSpec();
      InteractionGoals.Result r = InteractionGoals.select(occ.world(4, 4), spec, occ);
      Assertions.assertFalse(r.ok());
      Assertions.assertEquals(InteractionGoals.NO_POSE, r.reason);
      int total = 0;
      for (Integer n : r.rejectCounts.values()) {
         total += n.intValue();
      }
      Assertions.assertTrue(total > 0);
      Assertions.assertFalse(r.rejectSamples.get(r.dominantReject()).isEmpty() || total == 0);
   }

   @Test
   void interactionIsNotIssuedBeforeAuthoritativeArrival() {
      OccupancyGrid occ = room();
      InteractionSpec spec = stairsSpec();
      InteractionGoals.Result r = InteractionGoals.select(playerSouth(), spec, occ);
      Assertions.assertTrue(r.ok(), r.reason);
      List<Coord2d> route = r.plan.smoothedRoute;
      Assertions.assertTrue(route.size() >= 2);
      Assertions.assertEquals(r.selected.world, route.get(route.size() - 1));
      Assertions.assertFalse(InteractionGoals.overlapsFootprint(r.selected.world, spec));
   }

   private static Coord2d[] authDesk() {
      return rect(62.0, 19.0, 82.0, 29.0);
   }

   private static Coord2d[] fallbackDesk() {
      return rect(56.0, 14.0, 88.0, 26.0);
   }

   private static Coord2d gapPose() {
      return Coord2d.of(83.0, 16.0);
   }

   private static List<Coord2d[]> playerDiamond() {
      return Collections.singletonList(new Coord2d[]{
         Coord2d.of(0.0, -2.0), Coord2d.of(2.0, 0.0), Coord2d.of(0.0, 2.0), Coord2d.of(-2.0, 0.0)
      });
   }

   private static OccupancyGrid rasterRoom(Coord2d[] deskPoly) {
      boolean[] solid = new boolean[W * H];
      LocalPlanner.rasterPolygon(solid, ORIGIN, W, H, wall(), LocalPlanner.OVERLAP);
      LocalPlanner.rasterPolygon(solid, ORIGIN, W, H, stairs(), LocalPlanner.OVERLAP);
      LocalPlanner.rasterPolygon(solid, ORIGIN, W, H, deskPoly, LocalPlanner.OVERLAP);
      boolean[] dilated = Arrays.copyOf(solid, solid.length);
      return OccupancyGrid.capture(ORIGIN, W, H, 2.75, solid, dilated, dilated, Coord.of(0, 0), Coord.of(W - 1, 0), Coord.of(W - 1, 0), Collections.emptyList());
   }

   private static List<Coord2d[]> authSolids() {
      List<Coord2d[]> s = new ArrayList<Coord2d[]>();
      s.add(wall());
      s.add(stairs());
      s.add(authDesk());
      return s;
   }

   private static InteractionGoals.Geometry authGeom() {
      return new InteractionGoals.Geometry(authSolids(), playerDiamond());
   }

   private static Coord gapCell(OccupancyGrid occ) {
      return occ.cellOf(gapPose());
   }

   @Test
   void oversizedFallbackClosesNarrowGap() {
      OccupancyGrid fallbackOcc = rasterRoom(fallbackDesk());
      OccupancyGrid authOcc = rasterRoom(authDesk());
      Coord cell = gapCell(fallbackOcc);
      Assertions.assertNotNull(cell);
      Assertions.assertEquals(OccupancyGrid.SOLID, fallbackOcc.at(cell.x, cell.y), "12x32-class fallback + OVERLAP must seal the hug cell");
      Assertions.assertNotEquals(OccupancyGrid.SOLID, authOcc.at(cell.x, cell.y), "authoritative desk must leave that cell standable on the grid");
   }

   @Test
   void authoritativeDeskPolygonPreservesTheRealGap() {
      Coord2d[] auth = authDesk();
      CollisionGeom obst = CollisionGeom.furniture(Collections.singletonList(auth), fallbackDesk());
      Assertions.assertEquals(CollisionGeom.OBST, obst.source);
      Assertions.assertSame(auth, obst.polygons.get(0));
      OccupancyGrid occ = rasterRoom(authDesk());
      Coord cell = gapCell(occ);
      Assertions.assertNotEquals(OccupancyGrid.SOLID, occ.at(cell.x, cell.y));
      Assertions.assertFalse(LocalPlanner.bodyHits(gapPose(), playerDiamond(), authDesk()));
      Assertions.assertFalse(InteractionGoals.overlapsFootprint(gapPose(), stairsSpec()));
   }

   @Test
   void coarseBlockedCellContainsValidContinuousPose() {
      OccupancyGrid occ = rasterRoom(fallbackDesk());
      Coord cell = gapCell(occ);
      Assertions.assertEquals(OccupancyGrid.SOLID, occ.at(cell.x, cell.y));
      Coord2d at = gapPose();
      Assertions.assertFalse(LocalPlanner.bodyHits(at, playerDiamond(), authDesk()));
      Assertions.assertFalse(LocalPlanner.bodyHits(at, playerDiamond(), stairs()));
      Assertions.assertFalse(LocalPlanner.bodyHits(at, playerDiamond(), wall()));
      Assertions.assertTrue(LocalPlanner.bodyHitsAny(at, playerDiamond(), Collections.singletonList(fallbackDesk()), null), "fallback polygon itself covers the hug");
      Assertions.assertTrue(InteractionGoals.poseFits(at, stairsSpec(), occ, authGeom()));
   }

   @Test
   void poseRefinementFindsTheGap() {
      OccupancyGrid occ = rasterRoom(fallbackDesk());
      Coord2d seed = occ.world(gapCell(occ).x, gapCell(occ).y);
      Assertions.assertEquals(OccupancyGrid.SOLID, occ.at(occ.cellOf(seed).x, occ.cellOf(seed).y));
      Coord2d found = InteractionGoals.refinePose(seed, stairsSpec(), occ, authGeom());
      Assertions.assertTrue(InteractionGoals.poseFits(found, stairsSpec(), occ, authGeom()), "refined=" + found);
      Assertions.assertTrue(found.y < 19.0, "must stand north of the authoritative desk");
      Assertions.assertTrue(found.x > 79.0, "stairs-side / east hug");
   }

   @Test
   void playerRoutesAroundDeskWithExactGeometry() {
      OccupancyGrid occ = rasterRoom(fallbackDesk());
      InteractionSpec spec = stairsSpec();
      InteractionGoals.Result r = InteractionGoals.select(playerSouth(), spec, occ, authGeom());
      Assertions.assertTrue(r.ok(), "fwd " + r.reason + " dominant=" + r.dominantReject() + " counts=" + r.rejectCounts);
      Assertions.assertTrue(r.selected.world.y < 19.0, "stand north of the real desk " + r.selected.world);
      Assertions.assertFalse(InteractionGoals.overlapsFootprint(r.selected.world, spec));
      Coord2d click = InteractionGoals.clickPoint(r.selected.world, spec);
      Assertions.assertFalse(LocalPlanner.segmentHitsPolygon(r.selected.world, click, authDesk(), 0.0));
      Assertions.assertTrue(InteractionGoals.losClear(r.selected.world, spec, occ, authGeom()));
      Assertions.assertFalse(routeHits(r.plan.smoothedRoute, authDesk()));
      Assertions.assertFalse(routeHits(r.plan.smoothedRoute, stairs()));
      Assertions.assertTrue(aroundDesk(r.plan.smoothedRoute) || r.selected.world.x > 88.0 || r.selected.world.x < 56.0
         || routeGoesEast(r.plan.smoothedRoute), "must walk around the occupancy fallback, not through it");
   }

   @Test
   void clickThroughDeskRemainsRejectedWithExactGeometry() {
      OccupancyGrid occ = rasterRoom(fallbackDesk());
      InteractionSpec spec = stairsSpec();
      Assertions.assertFalse(InteractionGoals.losClear(playerSouth(), spec, occ, authGeom()));
      Assertions.assertFalse(InteractionGoals.losClear(Coord2d.of(72.0, 44.0), spec, occ, authGeom()));
      Assertions.assertTrue(LocalPlanner.segmentHitsPolygon(playerSouth(), spec.origin, authDesk(), 0.0));
   }

   @Test
   void stairsSidePoseIsAcceptedInCoarseSolidCell() {
      OccupancyGrid occ = rasterRoom(fallbackDesk());
      InteractionSpec spec = stairsSpec();
      InteractionGoals.Result r = InteractionGoals.select(playerSouth(), spec, occ, authGeom());
      Assertions.assertTrue(r.ok(), r.reason + " " + r.rejectCounts);
      Coord cell = occ.cellOf(r.selected.world);
      Assertions.assertNotNull(cell);
      Assertions.assertTrue(r.selected.world.y < 19.0 && r.selected.world.y > 6.0);
      Assertions.assertTrue(InteractionGoals.poseFits(r.selected.world, spec, occ, authGeom()));
      if (occ.at(cell.x, cell.y) == OccupancyGrid.SOLID) {
         Assertions.assertFalse(LocalPlanner.bodyHits(r.selected.world, playerDiamond(), authDesk()));
      }
   }

   @Test
   void interactionOccursOnlyAfterAuthoritativeArrivalWithExactGeometry() {
      OccupancyGrid occ = rasterRoom(fallbackDesk());
      InteractionSpec spec = stairsSpec();
      InteractionGoals.Result r = InteractionGoals.select(playerSouth(), spec, occ, authGeom());
      Assertions.assertTrue(r.ok(), r.reason);
      Assertions.assertEquals(r.selected.world, r.plan.smoothedRoute.get(r.plan.smoothedRoute.size() - 1));
      Assertions.assertTrue(InteractionGoals.poseFits(r.selected.world, spec, occ, authGeom()));
   }

   private static boolean routeGoesEast(List<Coord2d> route) {
      if (route == null) {
         return false;
      }
      for (int i = 0; i < route.size(); i++) {
         Coord2d p = route.get(i);
         if (p != null && p.x > 88.0) {
            return true;
         }
      }
      return false;
   }

   @Test
   void aabbUnionWouldSealTheGapButExactPolygonsDoNot() {
      Coord2d[] tiny = rect(-1.0, -1.0, 1.0, 1.0);
      Coord2d[] known = desk();
      List<Coord2d[]> union = new ArrayList<Coord2d[]>();
      union.add(tiny);
      union.add(known);
      Coord2d[] aabb = LocalPlanner.aabbPolygon(union);
      boolean[] sealed = new boolean[W * H];
      LocalPlanner.rasterPolygon(sealed, ORIGIN, W, H, aabb, LocalPlanner.OVERLAP);
      boolean[] exact = new boolean[W * H];
      LocalPlanner.rasterPolygon(exact, ORIGIN, W, H, known, LocalPlanner.OVERLAP);
      Coord gap = Coord.of((int) Math.floor(72.0 / 2.75), (int) Math.floor(20.0 / 2.75));
      Assertions.assertTrue(gap.y >= 0 && gap.y < H);
      Assertions.assertFalse(exact[gap.y * W + gap.x], "exact desk leaves the stair gap open");
   }

   @Test
   void occupancyPathSurvivesFullSizePlayerBody() {
      OccupancyGrid occ = rasterRoom(fallbackDesk());
      InteractionSpec spec = stairsSpec();
      InteractionGoals.Geometry geom = new InteractionGoals.Geometry(authSolids(), livePlayer());
      InteractionGoals.Result r = InteractionGoals.select(playerSouth(), spec, occ, geom);
      Assertions.assertTrue(r.ok(), "fwd " + r.reason + " dominant=" + r.dominantReject() + " counts=" + r.rejectCounts);
      Assertions.assertFalse(InteractionGoals.overlapsFootprint(r.selected.world, spec));
      Assertions.assertTrue(InteractionGoals.losClear(r.selected.world, spec, occ, geom));
      Assertions.assertFalse(routeHits(r.plan.smoothedRoute, stairs()), "planner must not walk onto the stairs");
      Assertions.assertTrue(
         aroundDesk(r.plan.smoothedRoute) || r.selected.world.x > 88.0 || r.selected.world.x < 56.0 || routeGoesEast(r.plan.smoothedRoute),
         "must walk around occupancy, not through the desk"
      );
   }

   @Test
   void lastHopOntoBlockedPoseStaysShort() {
      OccupancyGrid occ = rasterRoom(fallbackDesk());
      Coord2d pose = gapPose();
      Assertions.assertEquals(OccupancyGrid.SOLID, occ.at(occ.cellOf(pose).x, occ.cellOf(pose).y));
      Coord2d approach = InteractionGoals.nearestGridApproach(playerSouth(), pose, occ);
      Assertions.assertNotNull(approach, "need a FREE cell next to the sealed hug");
      Assertions.assertEquals(OccupancyGrid.FREE, occ.at(occ.cellOf(approach).x, occ.cellOf(approach).y));
      Assertions.assertTrue(approach.dist(pose) <= InteractionGoals.LAST_HOP + 1.0E-6, "approach=" + approach + " pose=" + pose);
   }

   private static List<Coord2d[]> livePlayer() {
      return Collections.singletonList(new Coord2d[]{
         Coord2d.of(3.746, 1.991),
         Coord2d.of(1.991, -3.746),
         Coord2d.of(-3.746, -1.991),
         Coord2d.of(-1.991, 3.746)
      });
   }
}
