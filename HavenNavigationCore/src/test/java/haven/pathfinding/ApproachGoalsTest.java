package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.InteractionSpec;
import haven.nav.NavDecision;
import haven.nav.NavObservation;
import haven.nav.NavPlan;
import haven.nav.NavPlanStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Approach-only poses around furniture. Occupancy A* through the aisle is a
 * separate question from standing at a container.
 */
public class ApproachGoalsTest {
   private static final int W = 56;
   private static final int H = 32;
   private static final Coord2d ORIGIN = Coord2d.of(0.0, 0.0);
   private static final double CELL = 2.75;

   private static Coord2d[] rect(double minx, double miny, double maxx, double maxy) {
      return new Coord2d[]{
         Coord2d.of(minx, miny), Coord2d.of(maxx, miny), Coord2d.of(maxx, maxy), Coord2d.of(minx, maxy)
      };
   }

   private static Coord2d[] wall() {
      return rect(0.0, 0.0, W * CELL, 6.0);
   }

   private static Coord2d[] cupboard(int i) {
      double x0 = 22.0 + i * 18.0;
      return rect(x0, 16.0, x0 + 11.0, 24.0);
   }

   private static Coord2d[] desk() {
      return rect(50.0, 6.0, 78.0, 18.0);
   }

   private static Coord2d[] barrel() {
      return rect(55.0, 30.0, 66.0, 41.0);
   }

   private static Coord2d[] leftCrate() {
      return rect(40.0, 30.0, 53.0, 41.0);
   }

   private static Coord2d[] rightCrate() {
      return rect(68.0, 30.0, 81.0, 41.0);
   }

   private static List<Coord2d[]> playerBody() {
      return Collections.singletonList(new Coord2d[]{
         Coord2d.of(0.0, -2.0), Coord2d.of(2.0, 0.0), Coord2d.of(0.0, 2.0), Coord2d.of(-2.0, 0.0)
      });
   }

   private static List<Coord2d[]> playerSquare() {
      return Collections.singletonList(new Coord2d[]{
         Coord2d.of(3.0, -3.0), Coord2d.of(-3.0, -3.0), Coord2d.of(-3.0, 3.0), Coord2d.of(3.0, 3.0)
      });
   }

   private static List<Coord2d[]> liveCrateBody() {
      return Collections.singletonList(new Coord2d[]{
         Coord2d.of(-3.627482411236997, -2.2003116497835435),
         Coord2d.of(-2.2003116497835435, 3.627482411236997),
         Coord2d.of(3.627482411236997, 2.2003116497835435),
         Coord2d.of(2.2003116497835435, -3.627482411236997)
      });
   }

   private static OccupancyGrid raster(Coord2d[][] solids) {
      boolean[] solid = new boolean[W * H];
      for (int i = 0; i < solids.length; i++) {
         LocalPlanner.rasterPolygon(solid, ORIGIN, W, H, solids[i], LocalPlanner.OVERLAP);
      }
      boolean[] dilated = Arrays.copyOf(solid, solid.length);
      return OccupancyGrid.capture(ORIGIN, W, H, CELL, solid, dilated, dilated, Coord.of(0, 0), Coord.of(W - 1, 0), Coord.of(W - 1, 0), Collections.emptyList());
   }

   private static OccupancyGrid rasterExact(Coord2d[][] solids) {
      boolean[] solid = new boolean[W * H];
      for (int i = 0; i < solids.length; i++) {
         LocalPlanner.rasterPolygon(solid, ORIGIN, W, H, solids[i], 0.0);
      }
      boolean[] dilated = Arrays.copyOf(solid, solid.length);
      return OccupancyGrid.capture(ORIGIN, W, H, CELL, solid, dilated, dilated, Coord.of(0, 0), Coord.of(W - 1, 0), Coord.of(W - 1, 0), Collections.emptyList());
   }

   private static boolean routeThroughSouthSqueeze(List<Coord2d> route) {
      if (route == null) {
         return false;
      }
      for (int i = 0; i < route.size(); i++) {
         Coord2d p = route.get(i);
         if (p != null && p.y > 27.4 && p.y < 31.2 && p.x > 20.0 && p.x < 62.0) {
            return true;
         }
      }
      for (int i = 1; i < route.size(); i++) {
         Coord2d a = route.get(i - 1);
         Coord2d b = route.get(i);
         if (a == null || b == null) {
            continue;
         }
         for (int s = 1; s < 8; s++) {
            double t = s / 8.0;
            double x = a.x + (b.x - a.x) * t;
            double y = a.y + (b.y - a.y) * t;
            if (y > 27.4 && y < 31.2 && x > 20.0 && x < 62.0) {
               return true;
            }
         }
      }
      return false;
   }

   private static InteractionSpec spec(String id, Coord2d[] poly) {
      List<Coord2d[]> polys = new ArrayList<Coord2d[]>();
      polys.add(poly);
      double minx = poly[0].x;
      double miny = poly[0].y;
      double maxx = poly[1].x;
      double maxy = poly[2].y;
      Coord2d origin = Coord2d.of((minx + maxx) * 0.5, (miny + maxy) * 0.5);
      Coord2d half = Coord2d.of((maxx - minx) * 0.5, (maxy - miny) * 0.5);
      return new InteractionSpec(id, origin, half, InteractionSpec.ALL_SIDES, 0.5, 16.5, 0, null, "", polys, CollisionGeom.OBST);
   }

   private static InteractionGoals.Geometry geom(Coord2d[][] solids) {
      List<Coord2d[]> list = new ArrayList<Coord2d[]>();
      for (int i = 0; i < solids.length; i++) {
         list.add(solids[i]);
      }
      return new InteractionGoals.Geometry(list, playerBody());
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

   @Test
   void packedCupboardRowApproachesEachWithoutEnteringCenter() {
      Coord2d[][] solids = new Coord2d[][]{wall(), cupboard(0), cupboard(1), cupboard(2), cupboard(3)};
      OccupancyGrid occ = raster(solids);
      String before = OccupancyGrid.encode(occ);
      Coord2d from = Coord2d.of(45.5, 55.0);
      InteractionGoals.Geometry g = geom(solids);
      for (int i = 0; i < 4; i++) {
         InteractionSpec spec = spec("cup-" + i, cupboard(i));
         ApproachGoals.Result r = ApproachGoals.plan(from, spec, occ, g);
         Assertions.assertEquals(ApproachGoals.Status.POSE_OK, r.status, "cupboard " + i + " " + (r.pose == null ? "" : r.pose.dominantReject()));
         Assertions.assertFalse(InteractionGoals.overlapsFootprint(r.pose.selected.world, spec));
         Assertions.assertTrue(r.pose.selected.world.dist(spec.origin) > 4.0, "must not walk to the blocked center");
         Assertions.assertTrue(InteractionGoals.losClear(r.pose.selected.world, spec, occ, g));
         Assertions.assertTrue(r.pose.selected.dist + 1.0E-6 >= spec.minDist);
         Assertions.assertTrue(r.pose.selected.dist - 1.0E-6 <= spec.maxDist);
         Assertions.assertFalse(routeHits(r.pose.plan.smoothedRoute, cupboard(i)));
         List<Coord2d> route = r.pose.plan.smoothedRoute;
         if (route != null && route.size() >= 2 && occ.at(occ.cellOf(r.pose.selected.world).x, occ.cellOf(r.pose.selected.world).y) != OccupancyGrid.FREE) {
            Assertions.assertTrue(route.get(route.size() - 2).dist(r.pose.selected.world) <= SurfaceStream.LAST_HOP + 1.0E-6);
         }
         Assertions.assertEquals(OccupancyGrid.SOLID, occ.at(occ.cellOf(spec.origin).x, occ.cellOf(spec.origin).y));
      }
      Assertions.assertEquals(before, OccupancyGrid.encode(occ), "targets stay solid");
   }

   @Test
   void deskBesideWallUsesTheOpenSide() {
      Coord2d[][] solids = new Coord2d[][]{wall(), desk()};
      OccupancyGrid occ = raster(solids);
      InteractionSpec spec = spec("desk", desk());
      ApproachGoals.Result r = ApproachGoals.plan(Coord2d.of(64.0, 50.0), spec, occ, geom(solids));
      Assertions.assertEquals(ApproachGoals.Status.POSE_OK, r.status, r.pose == null ? "" : r.pose.reason);
      Assertions.assertNotEquals(InteractionSpec.SIDE_N, r.pose.selected.side, "north is the wall");
      Assertions.assertTrue(r.pose.selected.world.y > 6.0, "not inside the north wall " + r.pose.selected.world);
      Assertions.assertFalse(InteractionGoals.overlapsFootprint(r.pose.selected.world, spec));
      Assertions.assertTrue(InteractionGoals.losClear(r.pose.selected.world, spec, occ, geom(solids)));
   }

   @Test
   void bigDeskAgainstWallStandsInTheAisleNotTheSqueeze() {
      Coord2d[] desk = rect(50.0, 6.0, 65.6, 14.0);
      Coord2d[] cabinet = rect(66.5, 6.0, 77.5, 20.0);
      Coord2d[][] solids = new Coord2d[][]{wall(), desk, cabinet};
      OccupancyGrid occ = raster(solids);
      InteractionSpec spec = spec("studydesk-big", desk);
      InteractionGoals.Geometry g = new InteractionGoals.Geometry(Arrays.asList(solids), playerSquare());
      ApproachGoals.Result r = ApproachGoals.plan(Coord2d.of(58.0, 50.0), spec, occ, g);
      Assertions.assertEquals(ApproachGoals.Status.POSE_OK, r.status, r.pose == null ? "" : r.pose.dominantReject() + " " + r.pose.rejectCounts);
      Coord2d stand = r.pose.selected.world;
      Coord cell = occ.cellOf(stand);
      Assertions.assertEquals(OccupancyGrid.FREE, occ.at(cell.x, cell.y), "must not last-hop into the desk " + stand);
      Assertions.assertTrue(stand.y > 14.0, "open side of the desk " + stand);
      Assertions.assertFalse(stand.x > 65.6 && stand.x < 77.5 && stand.y < 20.0, "must not stand in the cabinet squeeze " + stand);
      Assertions.assertFalse(LocalPlanner.bodyHitsAny(stand, playerSquare(), Arrays.asList(solids), null));
      Assertions.assertTrue(InteractionGoals.losClear(stand, spec, occ, g));
      Assertions.assertFalse(routeHits(r.pose.plan.smoothedRoute, desk));
   }

   @Test
   void crateInAPackedClusterStandsOnTheNearFace() {
      Coord2d[] north = rect(79.0, 16.6, 86.8, 30.4);
      Coord2d[] target = rect(78.6, 45.7, 86.4, 59.5);
      Coord2d[] east = rect(86.8, 45.8, 94.5, 59.6);
      Coord2d[][] solids = new Coord2d[][]{wall(), north, target, east};
      OccupancyGrid occ = raster(solids);
      InteractionSpec spec = spec("crate", target);
      InteractionGoals.Geometry g = new InteractionGoals.Geometry(Arrays.asList(solids), playerSquare());
      Coord2d from = Coord2d.of(82.5, 38.0);
      ApproachGoals.Result r = ApproachGoals.plan(from, spec, occ, g);
      Assertions.assertEquals(ApproachGoals.Status.POSE_OK, r.status, r.pose == null ? "" : r.pose.dominantReject() + " " + r.pose.rejectCounts);
      Coord2d stand = r.pose.selected.world;
      Assertions.assertEquals(OccupancyGrid.FREE, occ.at(occ.cellOf(stand).x, occ.cellOf(stand).y));
      Assertions.assertTrue(stand.y < 45.7, "must stand on the near face, not walk around the packing " + stand);
      Assertions.assertTrue(stand.dist(from) < 20.0, "near-side stand, not the far side " + stand + " from " + from);
      Assertions.assertFalse(LocalPlanner.bodyHitsAny(stand, playerSquare(), Arrays.asList(solids), null));
      Assertions.assertTrue(InteractionGoals.losClear(stand, spec, occ, g));
   }

   @Test
   void approachWalksTowardTheCrateNotThePlayersOwnCell() {
      Coord2d[] target = rect(78.6, 45.7, 86.4, 59.5);
      Coord2d[] east = rect(86.8, 45.8, 94.5, 59.6);
      Coord2d[][] solids = new Coord2d[][]{wall(), target, east};
      OccupancyGrid occ = raster(solids);
      InteractionSpec spec = spec("crate", target);
      InteractionGoals.Geometry g = new InteractionGoals.Geometry(Arrays.asList(solids), playerSquare());
      Coord2d from = Coord2d.of(82.5, 66.0);
      ApproachGoals.Result r = ApproachGoals.plan(from, spec, occ, g);
      Assertions.assertEquals(ApproachGoals.Status.POSE_OK, r.status, r.pose == null ? "" : r.pose.dominantReject() + " " + r.pose.rejectCounts);
      double startDist = InteractionGoals.distanceToFootprint(from, spec);
      Assertions.assertTrue(r.pose.selected.dist + 1.0 < startDist,
         "stand must move toward the crate (" + r.pose.selected.world + "), not stay at start (" + from + ")");
      Assertions.assertTrue(InteractionGoals.losClear(r.pose.selected.world, spec, occ, g));
      Assertions.assertFalse(LocalPlanner.bodyHitsAny(r.pose.selected.world, playerSquare(), Arrays.asList(solids), null));
   }

   @Test
   void packedCrateClusterWalksAroundABodyBlockedSqueeze() {
      Coord2d[] nw = rect(28.6, 60.7, 36.4, 74.5);
      Coord2d[] n = rect(36.8, 60.8, 44.5, 74.6);
      Coord2d[] ne = rect(53.6, 60.6, 61.4, 74.4);
      Coord2d[] mid = rect(45.5, 45.8, 53.3, 59.6);
      Coord2d[] midE = rect(54.2, 46.0, 61.9, 59.7);
      Coord2d[] sw = rect(20.4, 31.2, 28.2, 45.0);
      Coord2d[] s = rect(29.0, 31.6, 36.7, 45.4);
      Coord2d[] s2 = rect(36.9, 31.2, 44.7, 45.0);
      Coord2d[] s3 = rect(45.1, 31.9, 52.9, 45.6);
      Coord2d[] target = rect(53.5, 32.0, 61.2, 45.8);
      Coord2d[] chestW = rect(20.6, 19.6, 28.4, 27.4);
      Coord2d[] chest = rect(31.6, 19.6, 39.4, 27.4);
      Coord2d[] chestE = rect(42.6, 19.6, 50.4, 27.4);
      Coord2d[] chestT = rect(53.6, 19.6, 61.4, 27.4);
      Coord2d[][] solids = new Coord2d[][]{
         wall(), nw, n, ne, mid, midE, sw, s, s2, s3, target, chestW, chest, chestE, chestT
      };
      OccupancyGrid occ = rasterExact(solids);
      InteractionSpec spec = spec("crate", target);
      InteractionGoals.Geometry g = new InteractionGoals.Geometry(Arrays.asList(solids), liveCrateBody());
      Coord2d from = Coord2d.of(36.86, 57.35);
      ApproachGoals.Result r = ApproachGoals.plan(from, spec, occ, g);
      Assertions.assertEquals(ApproachGoals.Status.POSE_OK, r.status, r.pose == null ? "" : r.pose.dominantReject() + " " + r.pose.rejectCounts);
      Coord2d stand = r.pose.selected.world;
      Assertions.assertFalse(LocalPlanner.bodyHitsAny(stand, liveCrateBody(), Arrays.asList(solids), Collections.singletonList(target)));
      Assertions.assertTrue(InteractionGoals.losClear(stand, spec, occ, g));
      List<Coord2d> route = r.pose.plan.smoothedRoute;
      Assertions.assertTrue(InteractionGoals.routePolygonClear(route, spec, g), "must not click through a body-blocked crate squeeze " + route);
      Assertions.assertFalse(routeThroughSouthSqueeze(route), "south gap is occupancy-thin and body-blocked " + route);
      Assertions.assertTrue(stand.x > 61.0 || stand.y < 32.0, "open east/south face, not a packed near face " + stand);
   }

   @Test
   void barrelBetweenContainersStaysReachableFromTheAisle() {
      Coord2d[][] solids = new Coord2d[][]{wall(), leftCrate(), barrel(), rightCrate()};
      OccupancyGrid occ = raster(solids);
      InteractionSpec spec = spec("barrel", barrel());
      ApproachGoals.Result r = ApproachGoals.plan(Coord2d.of(60.5, 60.0), spec, occ, geom(solids));
      Assertions.assertEquals(ApproachGoals.Status.POSE_OK, r.status, r.pose == null ? "" : String.valueOf(r.pose.rejectCounts));
      Assertions.assertTrue(r.pose.selected.world.y > 41.0 || r.pose.selected.world.y < 30.0, r.pose.selected.world.toString());
      Assertions.assertFalse(LocalPlanner.bodyHitsAny(r.pose.selected.world, playerBody(), Collections.singletonList(barrel()), null));
      Assertions.assertTrue(InteractionGoals.losClear(r.pose.selected.world, spec, occ, geom(solids)));
   }

   @Test
   void bothDirectionsPickDeterministicPoses() {
      Coord2d[][] solids = new Coord2d[][]{wall(), cupboard(1)};
      OccupancyGrid occ = raster(solids);
      InteractionSpec spec = spec("cup", cupboard(1));
      InteractionGoals.Geometry g = geom(solids);
      Coord2d west = Coord2d.of(12.0, 20.0);
      Coord2d east = Coord2d.of(80.0, 20.0);
      ApproachGoals.Result a = ApproachGoals.plan(west, spec, occ, g);
      ApproachGoals.Result b = ApproachGoals.plan(east, spec, occ, g);
      Assertions.assertEquals(ApproachGoals.Status.POSE_OK, a.status);
      Assertions.assertEquals(ApproachGoals.Status.POSE_OK, b.status);
      Assertions.assertFalse(InteractionGoals.overlapsFootprint(a.pose.selected.world, spec));
      Assertions.assertFalse(InteractionGoals.overlapsFootprint(b.pose.selected.world, spec));
      ApproachGoals.Result a2 = ApproachGoals.plan(west, spec, occ, g);
      ApproachGoals.Result b2 = ApproachGoals.plan(east, spec, occ, g);
      Assertions.assertEquals(a.pose.selected.world.x, a2.pose.selected.world.x, 1.0E-6);
      Assertions.assertEquals(a.pose.selected.world.y, a2.pose.selected.world.y, 1.0E-6);
      Assertions.assertEquals(b.pose.selected.world.x, b2.pose.selected.world.x, 1.0E-6);
      Assertions.assertEquals(b.pose.selected.world.y, b2.pose.selected.world.y, 1.0E-6);
   }

   @Test
   void sealedContainerIsUnreachableNotACenterClick() {
      Coord2d[] divider = rect(28.0, 0.0, 70.0, H * CELL);
      Coord2d[][] solids = new Coord2d[][]{wall(), cupboard(3), divider};
      OccupancyGrid occ = raster(solids);
      InteractionSpec spec = spec("east", cupboard(3));
      Coord2d from = Coord2d.of(12.0, 50.0);
      Coord fc = occ.cellOf(from);
      Coord mid = occ.cellOf(Coord2d.of(49.0, 40.0));
      Assertions.assertEquals(OccupancyGrid.FREE, occ.at(fc.x, fc.y));
      Assertions.assertEquals(OccupancyGrid.SOLID, occ.at(mid.x, mid.y), "barrier must stay solid");
      ApproachGoals.Result r = ApproachGoals.plan(from, spec, occ, geom(solids));
      Assertions.assertEquals(ApproachGoals.Status.UNREACHABLE, r.status, r.pose == null ? "" : r.pose.dominantReject() + " " + r.pose.rejectCounts);
      Assertions.assertFalse(r.ok());
      Assertions.assertEquals(OccupancyGrid.SOLID, occ.at(occ.cellOf(spec.origin).x, occ.cellOf(spec.origin).y));
   }

   @Test
   void missingGeometryFailsClosed() {
      OccupancyGrid occ = raster(new Coord2d[][]{wall()});
      InteractionSpec spec = new InteractionSpec(
         "gone", Coord2d.of(40.0, 40.0), Coord2d.of(5.5, 5.5), InteractionSpec.ALL_SIDES, 0.5, 16.5, 0, null, "",
         Collections.<Coord2d[]>emptyList(), CollisionGeom.UNAVAILABLE
      );
      ApproachGoals.Result r = ApproachGoals.plan(Coord2d.of(20.0, 40.0), spec, occ);
      Assertions.assertEquals(ApproachGoals.Status.GEOMETRY_UNAVAILABLE, r.status);
   }

   @Test
   void noLegalStandIsNoValidPose() {
      OccupancyGrid occ = raster(new Coord2d[][]{wall()});
      InteractionSpec spec = new InteractionSpec(
         "noway", Coord2d.of(40.0, 20.0), Coord2d.of(5.5, 5.5), 0, 0.5, 16.5, 0, null, ""
      );
      ApproachGoals.Result r = ApproachGoals.plan(Coord2d.of(20.0, 40.0), spec, occ);
      Assertions.assertEquals(ApproachGoals.Status.NO_VALID_POSE, r.status, r.pose == null ? "" : r.pose.dominantReject());
   }

   @Test
   void groundAisleAroundTheSameFurnitureDoesNotUseTheObjectCenter() {
      Coord2d[][] solids = new Coord2d[][]{wall(), cupboard(0), cupboard(1), cupboard(2), cupboard(3)};
      OccupancyGrid occ = raster(solids);
      String before = OccupancyGrid.encode(occ);
      Coord2d from = Coord2d.of(16.0, 50.0);
      Coord2d dest = Coord2d.of(100.0, 50.0);
      NavPlan plan = LocalPlanner.planFromOccupancy(from, dest, false, 0.0, occ, 0, new PlanningTrace());
      Assertions.assertNotNull(plan);
      Assertions.assertNotEquals(NavPlanStatus.FAILED, plan.status, plan.reason);
      Assertions.assertFalse(routeHits(plan.smoothedRoute, cupboard(0)));
      Assertions.assertFalse(routeHits(plan.smoothedRoute, cupboard(1)));
      Assertions.assertFalse(routeHits(plan.smoothedRoute, cupboard(2)));
      Assertions.assertFalse(routeHits(plan.smoothedRoute, cupboard(3)));
      Coord2d c1 = spec("cup-1", cupboard(1)).origin;
      Assertions.assertTrue(dest.dist(c1) > 20.0, "aisle dest is not the cupboard");
      Assertions.assertEquals(before, OccupancyGrid.encode(occ));
   }

   @Test
   void arrivingAtThePoseIsAStopSignalNotAGameClick() {
      Coord2d[][] solids = new Coord2d[][]{wall(), cupboard(1)};
      OccupancyGrid occ = raster(solids);
      InteractionSpec spec = spec("cup", cupboard(1));
      ApproachGoals.Result r = ApproachGoals.plan(Coord2d.of(45.5, 55.0), spec, occ, geom(solids));
      Assertions.assertTrue(r.ok());
      SurfaceController ctl = SurfaceController.forInteraction(
         r.pose.selected.world, r.pose.plan.smoothedRoute, r.pose.plan.status, 2.475, 0.6875, 800L, 20000L, 3000L, spec
      );
      NavObservation moving = new NavObservation(0L, Coord2d.of(45.5, 55.0), true, false, 0L, false, null, null);
      SurfaceController.Tick first = ctl.step(moving, occ);
      Assertions.assertNotEquals(NavDecision.Kind.INTERACT, first.decision.kind);
      NavObservation arrived = new NavObservation(4000L, r.pose.selected.world, false, false, 0L, false, null, null);
      SurfaceController.Tick stop = ctl.step(arrived, occ);
      Assertions.assertEquals(NavDecision.Kind.INTERACT, stop.decision.kind, "stream stop; the client must not turn this into rclick");
   }
}
