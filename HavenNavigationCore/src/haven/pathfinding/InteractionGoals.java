package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.InteractionSpec;
import haven.nav.NavPlan;
import haven.nav.NavPlanStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Legal stand poses around an interaction footprint. The target stays
 * blocked; this class never mutates occupancy or paths to the object center.
 */
public final class InteractionGoals {
   public static final String NO_POSE = "no_interaction_pose";
   public static final String GEOMETRY_UNAVAILABLE = "geometry_unavailable";
   /** Rebuild a real occupancy path only for the chosen stand (and a few fallbacks). */
   private static final int MAX_PLAN = 8;
   /** Occupancy A* retries after blacklisting a body-blocked squeeze. Winner only. */
   private static final int MAX_SWEPT_REPLAN = 8;
   private static final int MAX_SWEPT_CANDIDATES = 8;
   /** A free stand farther than this from the closest free stand is not flushed to the target. */
   private static final double OCCUPANCY_STAND_SLACK = LocalPlanner.CELL * 0.5;
   /** Walkable occupancy cells within this Chebyshev radius may last-hop onto a SOLID pose. */
   static final int APPROACH_PAD = 3;
   private static final double FACE_CONE = Math.PI / 4.0;
   private static final double[] EDGE_T = new double[] {0.0, 0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0};

   private InteractionGoals() {
   }

   public static final class Candidate {
      public final Coord cell;
      public final Coord2d world;
      public final int side;
      public final double dist;
      public final int clearance;
      public final boolean reachable;
      public final double routeCost;
      public final String reject;
      public final NavPlan plan;

      Candidate(Coord cell, Coord2d world, int side, double dist, int clearance, boolean reachable, double routeCost, String reject, NavPlan plan) {
         this.cell = cell;
         this.world = world;
         this.side = side;
         this.dist = dist;
         this.clearance = clearance;
         this.reachable = reachable;
         this.routeCost = routeCost;
         this.reject = reject;
         this.plan = plan;
      }
   }

   public static final class Result {
      public final InteractionSpec spec;
      public final Candidate selected;
      public final List<Candidate> considered;
      public final NavPlan plan;
      public final String reason;
      public final Map<String, Integer> rejectCounts;
      public final Map<String, List<Coord2d>> rejectSamples;

      Result(InteractionSpec spec, Candidate selected, List<Candidate> considered, NavPlan plan, String reason) {
         this.spec = spec;
         this.selected = selected;
         this.considered = considered == null ? Collections.<Candidate>emptyList() : Collections.unmodifiableList(considered);
         this.plan = plan;
         this.reason = reason == null ? "" : reason;
         this.rejectCounts = tally(this.considered);
         if (GEOMETRY_UNAVAILABLE.equals(this.reason) && this.rejectCounts.get("geometry_unavailable").intValue() == 0) {
            this.rejectCounts.put("geometry_unavailable", Integer.valueOf(1));
         }
         this.rejectSamples = samples(this.considered);
      }

      public String dominantReject() {
         String best = NO_POSE;
         int n = -1;
         for (Map.Entry<String, Integer> e : this.rejectCounts.entrySet()) {
            int v = e.getValue().intValue();
            if (v > n) {
               n = v;
               best = e.getKey();
            }
         }
         return n <= 0 ? this.reason : best;
      }

      public boolean ok() {
         return this.selected != null && this.plan != null && this.plan.status != NavPlanStatus.FAILED;
      }
   }

   /**
    * Exact obstacle polygons and the player footprint relative to origin.
    * When solids are present, the final pose is validated continuously and
    * may sit in a conservatively rasterized SOLID cell.
    */
   public static final class Geometry {
      public final List<Coord2d[]> solids;
      public final List<Coord2d[]> playerBody;

      final LocalPlanner.PolyBounds bounds;

      public Geometry(List<Coord2d[]> solids, List<Coord2d[]> playerBody) {
         this.solids = solids == null ? Collections.<Coord2d[]>emptyList() : solids;
         this.playerBody = playerBody == null ? Collections.<Coord2d[]>emptyList() : playerBody;
         this.bounds = LocalPlanner.PolyBounds.of(this.solids);
      }

      public boolean enabled() {
         return !this.solids.isEmpty();
      }
   }

   public static Result select(Coord2d from, InteractionSpec spec, OccupancyGrid occ) {
      return select(from, spec, occ, null);
   }

   public static Result select(Coord2d from, InteractionSpec spec, OccupancyGrid occ, Geometry geom) {
      return ensureSweptRoute(from, selectOccupancy(from, spec, occ, geom), occ, geom);
   }

   static Result selectOccupancy(Coord2d from, InteractionSpec spec, OccupancyGrid occ, Geometry geom) {
      if (spec == null || occ == null || from == null) {
         return fail(spec, NO_POSE);
      }
      if (CollisionGeom.UNAVAILABLE.equals(spec.geometrySource)) {
         return fail(spec, GEOMETRY_UNAVAILABLE);
      }
      Coord originCell = occ.cellOf(spec.origin);
      if (originCell == null) {
         return fail(spec, NO_POSE);
      }
      int[] clr = SurfaceStream.clearance(occ);
      List<Coord2d> seeds = samplePoses(spec, occ);
      List<Candidate> raw = new ArrayList<Candidate>();
      for (int i = 0; i < seeds.size(); i++) {
         Coord2d seed = seeds.get(i);
         Coord2d world = seed;
         if (geom != null && geom.enabled() && !occupancyWalkable(occ, seed) && !insideTarget(seed, spec)
            && distanceToFootprint(seed, spec) <= spec.maxDist) {
            world = refinePose(seed, spec, occ, geom);
         }
         Coord cell = occ.cellOf(world);
         if (cell == null || cell.x < 0 || cell.y < 0 || cell.x >= occ.w || cell.y >= occ.h) {
            raw.add(new Candidate(cell == null ? Coord.of(-1, -1) : cell, world, 0, Double.POSITIVE_INFINITY, 0, false, Double.POSITIVE_INFINITY, "outside_range", null));
            continue;
         }
         int side = sideOf(spec.origin, world);
         double dist = distanceToFootprint(world, spec);
         int clearance = clr[cell.y * occ.w + cell.x];
         String reject = rejectReason(occ, spec, cell.x, cell.y, world, side, dist, clearance, geom);
         raw.add(new Candidate(cell, world, side, dist, clearance, false, Double.POSITIVE_INFINITY, reject, null));
      }
      List<Candidate> considered = compactByCell(raw);
      List<Candidate> legal = new ArrayList<Candidate>();
      for (int i = 0; i < considered.size(); i++) {
         if (considered.get(i).reject == null) {
            legal.add(considered.get(i));
         }
      }
      Collections.sort(legal, new Comparator<Candidate>() {
         @Override
         public int compare(Candidate a, Candidate b) {
            int c = Double.compare(a.dist, b.dist);
            if (c != 0) {
               return c;
            }
            c = Integer.compare(b.clearance, a.clearance);
            if (c != 0) {
               return c;
            }
            c = Integer.compare(a.cell.x, b.cell.x);
            if (c != 0) {
               return c;
            }
            return Integer.compare(a.cell.y, b.cell.y);
         }
      });
      double[] costs = LocalPlanner.occupancyCosts(from, occ);
      List<Candidate> reachable = new ArrayList<Candidate>();
      for (int i = 0; i < legal.size(); i++) {
         Candidate c = legal.get(i);
         Coord2d approach = c.world;
         if (geom != null && geom.enabled()) {
            approach = nearestGridApproach(from, c.world, occ);
         }
         double cost = fillCost(costs, occ, approach);
         boolean ok = Double.isFinite(cost);
         Candidate scored = new Candidate(c.cell, c.world, c.side, c.dist, c.clearance, ok, cost, ok ? null : "unreachable", null);
         replace(considered, c, scored);
         if (ok) {
            reachable.add(scored);
         }
      }
      for (int i = 0; i < considered.size(); i++) {
         Candidate c = considered.get(i);
         if (c.reject == null && !c.reachable) {
            considered.set(i, new Candidate(c.cell, c.world, c.side, c.dist, c.clearance, false, Double.POSITIVE_INFINITY, "unreachable", null));
         }
      }
      if (reachable.isEmpty()) {
         return new Result(spec, null, considered, NavPlan.failed(0, NO_POSE), NO_POSE);
      }
      final boolean exact = geom != null && geom.enabled();
      Collections.sort(reachable, new Comparator<Candidate>() {
         @Override
         public int compare(Candidate a, Candidate b) {
            int c;
            if (exact) {
               c = Double.compare(a.dist, b.dist);
               if (c != 0) {
                  return c;
               }
               c = Double.compare(a.routeCost, b.routeCost);
               if (c != 0) {
                  return c;
               }
            }
            c = Integer.compare(b.clearance, a.clearance);
            if (c != 0) {
               return c;
            }
            if (!exact) {
               c = Double.compare(a.routeCost, b.routeCost);
               if (c != 0) {
                  return c;
               }
            }
            c = Integer.compare(a.cell.x, b.cell.x);
            if (c != 0) {
               return c;
            }
            return Integer.compare(a.cell.y, b.cell.y);
         }
      });
      Candidate best = null;
      int planned = 0;
      for (int i = 0; i < reachable.size() && planned < MAX_PLAN; i++) {
         Candidate c = reachable.get(i);
         planned++;
         NavPlan plan = planTo(from, c.world, spec, occ, geom);
         boolean ok = poseReached(plan, c.world, spec, occ, geom);
         Candidate scored = new Candidate(c.cell, c.world, c.side, c.dist, c.clearance, ok, c.routeCost, ok ? null : "unreachable", ok ? plan : null);
         replace(considered, c, scored);
         replaceList(reachable, c, scored);
         if (ok) {
            best = scored;
            break;
         }
      }
      if (best == null || best.plan == null) {
         return new Result(spec, null, considered, NavPlan.failed(0, NO_POSE), NO_POSE);
      }
      return new Result(spec, best, considered, withSelected(best.plan, best.world), "");
   }

   /**
    * Approach stands in the aisle. If a reachable occupancy-FREE pose exists,
    * use the highest-clearance one instead of last-hopping onto furniture.
    */
   static Result preferOccupancyStand(Coord2d from, Result pose, OccupancyGrid occ, Geometry geom) {
      if (pose == null || occ == null || pose.considered.isEmpty()) {
         return pose;
      }
      double closest = Double.POSITIVE_INFINITY;
      for (int i = 0; i < pose.considered.size(); i++) {
         Candidate c = pose.considered.get(i);
         if (c == null || !c.reachable || c.reject != null || c.world == null) {
            continue;
         }
         if (!occupancyWalkable(occ, c.world)) {
            continue;
         }
         if (c.dist < closest) {
            closest = c.dist;
         }
      }
      Candidate best = null;
      for (int i = 0; i < pose.considered.size(); i++) {
         Candidate c = pose.considered.get(i);
         if (c == null || !c.reachable || c.reject != null || c.world == null) {
            continue;
         }
         if (!occupancyWalkable(occ, c.world)) {
            continue;
         }
         if (c.dist > closest + OCCUPANCY_STAND_SLACK) {
            continue;
         }
         if (best == null || occupancyStandBetter(c, best)) {
            best = c;
         }
      }
      if (best == null) {
         return pose;
      }
      if (pose.selected != null && pose.selected.world != null && occupancyWalkable(occ, pose.selected.world) && best.cell.equals(pose.selected.cell)) {
         return pose;
      }
      NavPlan plan = best.plan;
      if (plan == null || !poseReached(plan, best.world, pose.spec, occ, geom)) {
         plan = planTo(from, best.world, pose.spec, occ, geom);
      }
      if (!poseReached(plan, best.world, pose.spec, occ, geom)) {
         return pose;
      }
      Candidate scored = new Candidate(best.cell, best.world, best.side, best.dist, best.clearance, true, best.routeCost, null, plan);
      List<Candidate> considered = new ArrayList<Candidate>(pose.considered);
      replace(considered, best, scored);
      return new Result(pose.spec, scored, considered, withSelected(plan, scored.world), pose.reason);
   }

   /**
    * Occupancy A* can thread a cell-wide gap the body cannot fit. Repair only
    * the chosen stand (and a few fallbacks), not every candidate.
    */
   static Result ensureSweptRoute(Coord2d from, Result pose, OccupancyGrid occ, Geometry geom) {
      if (pose == null || pose.spec == null || geom == null || !geom.enabled() || from == null || occ == null) {
         return pose;
      }
      if (pose.ok() && routePolygonClear(planRoute(pose), pose.spec, geom)) {
         return pose;
      }
      if (pose.selected != null && pose.selected.world != null) {
         Result repaired = sweptCandidate(from, pose, pose.selected, occ, geom);
         if (repaired != null) {
            return repaired;
         }
      }
      List<Candidate> alts = new ArrayList<Candidate>();
      for (int i = 0; i < pose.considered.size(); i++) {
         Candidate c = pose.considered.get(i);
         if (c == null || !c.reachable || c.reject != null || c.world == null) {
            continue;
         }
         if (pose.selected != null && c.cell != null && pose.selected.cell != null && c.cell.equals(pose.selected.cell)) {
            continue;
         }
         alts.add(c);
      }
      Collections.sort(alts, new Comparator<Candidate>() {
         @Override
         public int compare(Candidate a, Candidate b) {
            boolean aw = occupancyWalkable(occ, a.world);
            boolean bw = occupancyWalkable(occ, b.world);
            if (aw != bw) {
               return aw ? -1 : 1;
            }
            if (aw) {
               return occupancyStandBetter(a, b) ? -1 : occupancyStandBetter(b, a) ? 1 : 0;
            }
            return Double.compare(a.dist, b.dist);
         }
      });
      int tried = 0;
      for (int i = 0; i < alts.size() && tried < MAX_SWEPT_CANDIDATES; i++) {
         Result repaired = sweptCandidate(from, pose, alts.get(i), occ, geom);
         if (repaired != null) {
            return repaired;
         }
         tried++;
      }
      if (pose.ok()) {
         return new Result(pose.spec, null, pose.considered, NavPlan.failed(0, "unreachable"), "unreachable");
      }
      return pose;
   }

   private static List<Coord2d> planRoute(Result pose) {
      return pose == null || pose.plan == null ? null : pose.plan.smoothedRoute;
   }

   private static Result sweptCandidate(Coord2d from, Result pose, Candidate cand, OccupancyGrid occ, Geometry geom) {
      if (cand == null || cand.world == null) {
         return null;
      }
      NavPlan plan = repairSwept(from, cand.world, pose.spec, occ, geom);
      if (plan == null || !poseReached(plan, cand.world, pose.spec, occ, geom) || !routePolygonClear(plan.smoothedRoute, pose.spec, geom)) {
         return null;
      }
      Candidate neu = new Candidate(
         cand.cell, cand.world, cand.side, cand.dist, cand.clearance, true, plan.cost, null, plan
      );
      List<Candidate> considered = new ArrayList<Candidate>(pose.considered);
      replace(considered, cand, neu);
      return new Result(pose.spec, neu, considered, withSelected(plan, cand.world), "");
   }

   private static boolean occupancyStandBetter(Candidate a, Candidate b) {
      int cost = Double.compare(a.routeCost, b.routeCost);
      if (cost != 0) {
         return cost < 0;
      }
      if (a.clearance != b.clearance) {
         return a.clearance > b.clearance;
      }
      int x = Integer.compare(a.cell.x, b.cell.x);
      if (x != 0) {
         return x < 0;
      }
      return Integer.compare(a.cell.y, b.cell.y) < 0;
   }

   public static double distanceToFootprint(Coord2d p, InteractionSpec spec) {
      if (p == null || spec == null) {
         return Double.POSITIVE_INFINITY;
      }
      if (insideTarget(p, spec)) {
         return 0.0;
      }
      Coord2d nearest = nearestBoundary(p, spec);
      return nearest == null ? aabbDistance(p, spec) : p.dist(nearest);
   }

   public static boolean overlapsFootprint(Coord2d p, InteractionSpec spec) {
      return insideTarget(p, spec);
   }

   public static int sideOf(Coord2d origin, Coord2d p) {
      double dx = p.x - origin.x;
      double dy = p.y - origin.y;
      if (Math.abs(dx) >= Math.abs(dy)) {
         return dx >= 0.0 ? InteractionSpec.SIDE_E : InteractionSpec.SIDE_W;
      }
      return dy >= 0.0 ? InteractionSpec.SIDE_S : InteractionSpec.SIDE_N;
   }

   public static boolean facingOk(Coord2d pose, Coord2d origin, Double facing) {
      if (facing == null || pose == null || origin == null) {
         return true;
      }
      double ang = Math.atan2(origin.y - pose.y, origin.x - pose.x);
      double d = ang - facing.doubleValue();
      while (d > Math.PI) {
         d -= 2.0 * Math.PI;
      }
      while (d < -Math.PI) {
         d += 2.0 * Math.PI;
      }
      return Math.abs(d) <= FACE_CONE;
   }

   /**
    * Click segment from a stand to the nearest visible point on the target
    * boundary. Target polygons may be entered at the far end; other solids block.
    */
   public static boolean losClear(Coord2d from, InteractionSpec spec, OccupancyGrid occ) {
      return losClear(from, spec, occ, null);
   }

   /**
    * Click segment to the nearest target-boundary point. Target polygons may
    * be entered at the far end; every other solid blocks. When exact solids
    * are provided, occupancy squares are not the click test.
    */
   public static boolean losClear(Coord2d from, InteractionSpec spec, OccupancyGrid occ, Geometry geom) {
      if (from == null || spec == null) {
         return false;
      }
      Coord2d dest = clickPoint(from, spec);
      if (geom != null && geom.enabled()) {
         return LocalPlanner.sweptClear(from, dest, null, geom.solids, spec.polygons, geom.bounds);
      }
      if (occ == null || occ.occ == null) {
         return from.dist(spec.origin) <= spec.maxDist;
      }
      Coord ca = occ.cellOf(from);
      Coord cb = occ.cellOf(dest);
      if (ca == null || cb == null) {
         return from.dist(spec.origin) <= spec.maxDist;
      }
      int minx = Math.max(0, Math.min(ca.x, cb.x));
      int maxx = Math.min(occ.w - 1, Math.max(ca.x, cb.x));
      int miny = Math.max(0, Math.min(ca.y, cb.y));
      int maxy = Math.min(occ.h - 1, Math.max(ca.y, cb.y));
      for (int y = miny; y <= maxy; y++) {
         for (int x = minx; x <= maxx; x++) {
            if (occ.at(x, y) != OccupancyGrid.SOLID) {
               continue;
            }
            Coord2d world = occ.world(x, y);
            if (insideTarget(world, spec)) {
               continue;
            }
            Coord2d c0 = occ.corner(x, y);
            if (LocalPlanner.segmentHitsSquare(from, dest, c0.x, c0.y, c0.x + occ.cell, c0.y + occ.cell)) {
               return false;
            }
         }
      }
      return true;
   }

   public static Coord2d clickPoint(Coord2d from, InteractionSpec spec) {
      Coord2d n = nearestBoundary(from, spec);
      return n == null ? spec.origin : n;
   }

   public static Map<String, Integer> tally(List<Candidate> considered) {
      Map<String, Integer> out = new LinkedHashMap<String, Integer>();
      String[] keys = new String[] {
         "body_collision",
         "target_overlap",
         "other_obstacle_overlap",
         "no_los",
         "insufficient_clearance",
         "unreachable",
         "outside_range",
         "wrong_side",
         "geometry_unavailable"
      };
      for (int i = 0; i < keys.length; i++) {
         out.put(keys[i], Integer.valueOf(0));
      }
      if (considered == null) {
         return out;
      }
      for (int i = 0; i < considered.size(); i++) {
         String mapped = mapReject(considered.get(i).reject);
         if (mapped != null && out.containsKey(mapped)) {
            out.put(mapped, Integer.valueOf(out.get(mapped).intValue() + 1));
         }
      }
      return out;
   }

   static Map<String, List<Coord2d>> samples(List<Candidate> considered) {
      Map<String, List<Coord2d>> out = new LinkedHashMap<String, List<Coord2d>>();
      String[] keys = new String[] {
         "body_collision",
         "target_overlap",
         "other_obstacle_overlap",
         "no_los",
         "insufficient_clearance",
         "unreachable",
         "outside_range",
         "wrong_side",
         "geometry_unavailable"
      };
      for (int i = 0; i < keys.length; i++) {
         out.put(keys[i], new ArrayList<Coord2d>());
      }
      if (considered == null) {
         return out;
      }
      for (int i = 0; i < considered.size(); i++) {
         Candidate c = considered.get(i);
         String mapped = mapReject(c.reject);
         if (mapped == null || !out.containsKey(mapped) || c.world == null) {
            continue;
         }
         List<Coord2d> list = out.get(mapped);
         if (list.size() < 4) {
            list.add(c.world);
         }
      }
      return out;
   }

   static String mapReject(String reject) {
      if (reject == null || reject.isEmpty()) {
         return null;
      }
      if ("body_collision".equals(reject)) {
         return "body_collision";
      }
      if ("solid".equals(reject) || "occupancy".equals(reject) || "other_obstacle_overlap".equals(reject)) {
         return "other_obstacle_overlap";
      }
      if ("overlap_target".equals(reject) || "target_overlap".equals(reject)) {
         return "target_overlap";
      }
      if ("no_los".equals(reject)) {
         return "no_los";
      }
      if ("dilated".equals(reject) || "clearance".equals(reject) || "insufficient_clearance".equals(reject)) {
         return "insufficient_clearance";
      }
      if ("unreachable".equals(reject) || "unplanned".equals(reject)) {
         return "unreachable";
      }
      if ("too_close".equals(reject) || "too_far".equals(reject) || "outside_range".equals(reject)) {
         return "outside_range";
      }
      if ("disallowed_side".equals(reject) || "facing".equals(reject) || "wrong_side".equals(reject)) {
         return "wrong_side";
      }
      if (GEOMETRY_UNAVAILABLE.equals(reject) || "geometry_unavailable".equals(reject)) {
         return "geometry_unavailable";
      }
      return reject;
   }

   static double distToSegment(Coord2d p, Coord2d a, Coord2d b) {
      Coord2d q = closestOnSegment(p, a, b);
      return q == null ? Double.POSITIVE_INFINITY : p.dist(q);
   }

   private static String rejectReason(OccupancyGrid occ, InteractionSpec spec, int x, int y, Coord2d world, int side, double dist, int clearance, Geometry geom) {
      if (insideTarget(world, spec)) {
         return "target_overlap";
      }
      if (geom != null && geom.enabled()) {
         if (dist + 1.0E-6 < spec.minDist) {
            return "outside_range";
         }
         if (dist - 1.0E-6 > spec.maxDist) {
            return "outside_range";
         }
         if ((spec.allowedSides & side) == 0) {
            return "wrong_side";
         }
         if (!facingOk(world, spec.origin, spec.facing)) {
            return "wrong_side";
         }
         if (LocalPlanner.bodyHitsAny(world, geom.playerBody, spec.polygons, null)) {
            return "target_overlap";
         }
         if (LocalPlanner.bodyHitsAny(world, geom.playerBody, geom.solids, spec.polygons, geom.bounds)) {
            return "body_collision";
         }
         if (!losClear(world, spec, occ, geom)) {
            return "no_los";
         }
         return null;
      }
      byte v = occ.at(x, y);
      if (v == OccupancyGrid.SOLID) {
         return "other_obstacle_overlap";
      }
      if (v == OccupancyGrid.DILATED && spec.requiredClearance > 0) {
         return "insufficient_clearance";
      }
      if (v != OccupancyGrid.FREE && v != OccupancyGrid.CARVED && !(v == OccupancyGrid.DILATED && spec.requiredClearance == 0)) {
         return "other_obstacle_overlap";
      }
      if (dist + 1.0E-6 < spec.minDist) {
         return "outside_range";
      }
      if (dist - 1.0E-6 > spec.maxDist) {
         return "outside_range";
      }
      if ((spec.allowedSides & side) == 0) {
         return "wrong_side";
      }
      if (clearance < spec.requiredClearance) {
         return "insufficient_clearance";
      }
      if (!facingOk(world, spec.origin, spec.facing)) {
         return "wrong_side";
      }
      if (!losClear(world, spec, occ)) {
         return "no_los";
      }
      return null;
   }

   static List<Coord2d> samplePoses(InteractionSpec spec, OccupancyGrid occ) {
      List<Coord2d> out = new ArrayList<Coord2d>();
      List<Coord2d[]> polys = spec.footprintPolygons();
      double standOff = Math.max(spec.minDist, occ.cell * 0.5);
      for (int p = 0; p < polys.size(); p++) {
         Coord2d[] poly = polys.get(p);
         if (poly == null || poly.length < 2) {
            continue;
         }
         int n = poly.length;
         for (int i = 0; i < n; i++) {
            Coord2d a = poly[i];
            Coord2d b = poly[(i + 1) % n];
            if (a == null || b == null) {
               continue;
            }
            Coord2d outward = outward(a, b, poly);
            for (int t = 0; t < EDGE_T.length; t++) {
               double u = EDGE_T[t];
               Coord2d along = Coord2d.of(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u);
               out.add(along.add(outward.x * standOff, outward.y * standOff));
               out.add(along.add(outward.x * (standOff + occ.cell * 0.5), outward.y * (standOff + occ.cell * 0.5)));
               out.add(along.add(outward.x * (standOff + occ.cell), outward.y * (standOff + occ.cell)));
               out.add(along.add(outward.x * (standOff + occ.cell * 2.0), outward.y * (standOff + occ.cell * 2.0)));
            }
         }
      }
      Coord originCell = occ.cellOf(spec.origin);
      int pad = (int) Math.ceil((Math.max(spec.half.x, spec.half.y) + spec.maxDist) / occ.cell) + 2;
      int x0 = Math.max(0, originCell.x - pad);
      int x1 = Math.min(occ.w - 1, originCell.x + pad);
      int y0 = Math.max(0, originCell.y - pad);
      int y1 = Math.min(occ.h - 1, originCell.y + pad);
      for (int y = y0; y <= y1; y++) {
         for (int x = x0; x <= x1; x++) {
            if (occ.at(x, y) == OccupancyGrid.SOLID) {
               continue;
            }
            Coord2d cell = occ.world(x, y);
            if (aabbDistance(cell, spec) > spec.maxDist) {
               continue;
            }
            out.add(cell);
            if (occ.at(x, y) == OccupancyGrid.FREE || occ.at(x, y) == OccupancyGrid.DILATED || occ.at(x, y) == OccupancyGrid.CARVED) {
               Coord2d on = nearestBoundary(cell, spec);
               if (on != null) {
                  Coord2d delta = cell.sub(on);
                  double len = Math.hypot(delta.x, delta.y);
                  if (len > 1.0E-6) {
                     out.add(on.add(delta.x / len * standOff, delta.y / len * standOff));
                  }
               }
            }
         }
      }
      return out;
   }

   static Coord2d outward(Coord2d a, Coord2d b, Coord2d[] poly) {
      double dx = b.x - a.x;
      double dy = b.y - a.y;
      double len = Math.hypot(dx, dy);
      if (len < 1.0E-9) {
         return Coord2d.of(0.0, 1.0);
      }
      Coord2d n = Coord2d.of(-dy / len, dx / len);
      Coord2d mid = Coord2d.of((a.x + b.x) * 0.5, (a.y + b.y) * 0.5);
      Coord2d trial = mid.add(n.x * 0.35, n.y * 0.35);
      if (LocalPlanner.pointInside(trial, poly)) {
         return Coord2d.of(-n.x, -n.y);
      }
      return n;
   }

   static boolean insideTarget(Coord2d p, InteractionSpec spec) {
      if (p == null || spec == null) {
         return false;
      }
      List<Coord2d[]> polys = spec.footprintPolygons();
      for (int i = 0; i < polys.size(); i++) {
         Coord2d[] poly = polys.get(i);
         if (poly != null && poly.length >= 3 && LocalPlanner.pointInside(p, poly)) {
            return true;
         }
      }
      return p.x >= spec.minX() && p.x <= spec.maxX() && p.y >= spec.minY() && p.y <= spec.maxY() && spec.polygons.isEmpty();
   }

   static Coord2d nearestBoundary(Coord2d from, InteractionSpec spec) {
      if (from == null || spec == null) {
         return null;
      }
      Coord2d best = null;
      double bestD = Double.POSITIVE_INFINITY;
      List<Coord2d[]> polys = spec.footprintPolygons();
      for (int p = 0; p < polys.size(); p++) {
         Coord2d[] poly = polys.get(p);
         if (poly == null || poly.length < 2) {
            continue;
         }
         int n = poly.length;
         for (int i = 0; i < n; i++) {
            Coord2d a = poly[i];
            Coord2d b = poly[(i + 1) % n];
            Coord2d q = closestOnSegment(from, a, b);
            if (q == null) {
               continue;
            }
            double d = from.dist(q);
            if (d < bestD) {
               bestD = d;
               best = q;
            }
         }
      }
      return best;
   }

   static Coord2d closestOnSegment(Coord2d p, Coord2d a, Coord2d b) {
      if (p == null || a == null || b == null) {
         return null;
      }
      double dx = b.x - a.x;
      double dy = b.y - a.y;
      double len2 = dx * dx + dy * dy;
      if (len2 < 1.0E-12) {
         return a;
      }
      double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2;
      if (t < 0.0) {
         t = 0.0;
      } else if (t > 1.0) {
         t = 1.0;
      }
      return Coord2d.of(a.x + t * dx, a.y + t * dy);
   }

   private static double aabbDistance(Coord2d p, InteractionSpec spec) {
      double dx = 0.0;
      if (p.x < spec.minX()) {
         dx = spec.minX() - p.x;
      } else if (p.x > spec.maxX()) {
         dx = p.x - spec.maxX();
      }
      double dy = 0.0;
      if (p.y < spec.minY()) {
         dy = spec.minY() - p.y;
      } else if (p.y > spec.maxY()) {
         dy = p.y - spec.maxY();
      }
      return Math.hypot(dx, dy);
   }

   private static NavPlan planTo(Coord2d from, Coord2d pose, InteractionSpec spec, OccupancyGrid occ) {
      return planTo(from, pose, spec, occ, null);
   }

   private static NavPlan planTo(Coord2d from, Coord2d pose, InteractionSpec spec, OccupancyGrid occ, Geometry geom) {
      Coord fc = occ.cellOf(from);
      Coord pc = occ.cellOf(pose);
      if (fc != null && pc != null && fc.x == pc.x && fc.y == pc.y) {
         List<Coord2d> route = new ArrayList<Coord2d>();
         route.add(from);
         route.add(pose);
         return NavPlan.create(NavPlanStatus.REACHED, route, route, true, false, 0, 0, "");
      }
      Coord2d approach = pose;
      if (geom != null && geom.enabled()) {
         approach = nearestGridApproach(from, pose, occ);
         if (approach == null) {
            return NavPlan.failed(0, "unreachable");
         }
      }
      NavPlan plan = occupancyToPose(from, pose, approach, geom, occ);
      if (plan == null || plan.status == NavPlanStatus.FAILED || plan.status == NavPlanStatus.PARTIAL || plan.status == NavPlanStatus.CLIPPED) {
         return plan == null ? NavPlan.failed(0, "unreachable") : plan;
      }
      Coord2d end = planEnd(plan);
      double cell = occ == null ? LocalPlanner.CELL : occ.cell;
      if (end == null || end.dist(approach) > cell + 1.0E-4) {
         return NavPlan.failed(0, "unreachable");
      }
      return plan;
   }

   private static NavPlan repairSwept(Coord2d from, Coord2d pose, InteractionSpec spec, OccupancyGrid occ, Geometry geom) {
      Coord2d approach = pose;
      if (geom != null && geom.enabled()) {
         approach = nearestGridApproach(from, pose, occ);
         if (approach == null) {
            return null;
         }
      }
      OccupancyGrid use = occ;
      for (int attempt = 0; attempt <= MAX_SWEPT_REPLAN; attempt++) {
         NavPlan plan = occupancyToPose(from, pose, approach, geom, use);
         if (plan == null || plan.status == NavPlanStatus.FAILED || plan.status == NavPlanStatus.PARTIAL || plan.status == NavPlanStatus.CLIPPED) {
            return null;
         }
         Coord2d end = planEnd(plan);
         double cell = occ == null ? LocalPlanner.CELL : occ.cell;
         if (end == null || end.dist(approach) > cell + 1.0E-4) {
            return null;
         }
         if (routePolygonClear(plan.smoothedRoute, spec, geom)) {
            return plan;
         }
         if (routePolygonClear(plan.rawRoute, spec, geom)) {
            return new NavPlan(
               plan.status, plan.rawRoute, plan.rawRoute, pose, plan.cost, plan.expanded, plan.obstacles, plan.complete, plan.snapped, plan.reason
            );
         }
         Coord2d failAt = firstFailingPoint(plan.smoothedRoute, spec, geom);
         if (failAt == null) {
            failAt = firstFailingPoint(plan.rawRoute, spec, geom);
         }
         if (failAt == null) {
            return plan;
         }
         boolean lastHop = geom != null && geom.enabled() && pose.dist(approach) > 1.0E-4;
         if (lastHop && failAt.dist(pose) <= 1.0) {
            return null;
         }
         OccupancyGrid next = blockPoint(use, failAt, from);
         if (next == null) {
            return null;
         }
         use = next;
      }
      return null;
   }

   private static NavPlan occupancyToPose(Coord2d from, Coord2d pose, Coord2d approach, Geometry geom, OccupancyGrid use) {
      NavPlan plan = occupancyApproach(from, approach, use);
      if (plan == null || plan.status == NavPlanStatus.FAILED || plan.status == NavPlanStatus.PARTIAL || plan.status == NavPlanStatus.CLIPPED) {
         return plan;
      }
      if (geom != null && geom.enabled() && pose.dist(approach) > 1.0E-4) {
         if (approach.dist(pose) > SurfaceStream.LAST_HOP + 1.0E-6) {
            return NavPlan.failed(0, "unreachable");
         }
         return appendPose(plan, pose);
      }
      return plan;
   }

   private static NavPlan occupancyApproach(Coord2d from, Coord2d approach, OccupancyGrid occ) {
      return LocalPlanner.planFromOccupancy(from, approach, false, 0.0, occ, 0, new PlanningTrace());
   }

   private static boolean poseReached(NavPlan plan, Coord2d pose, InteractionSpec spec, OccupancyGrid occ) {
      return poseReached(plan, pose, spec, occ, null);
   }

   private static boolean poseReached(NavPlan plan, Coord2d pose, InteractionSpec spec, OccupancyGrid occ, Geometry geom) {
      if (plan == null || pose == null || plan.status == NavPlanStatus.FAILED || plan.status == NavPlanStatus.PARTIAL || plan.status == NavPlanStatus.CLIPPED) {
         return false;
      }
      Coord2d end = planEnd(plan);
      double slop = occ == null ? LocalPlanner.CELL : occ.cell;
      if (end == null || end.dist(pose) > slop) {
         return false;
      }
      List<Coord2d> route = plan.smoothedRoute;
      if (route != null) {
         for (int i = 0; i < route.size(); i++) {
            Coord2d p = (Coord2d) route.get(i);
            if (insideTarget(p, spec)) {
               return false;
            }
         }
         if (geom != null && geom.enabled() && !occupancyWalkable(occ, pose) && route.size() >= 2) {
            Coord2d prev = route.get(route.size() - 2);
            if (prev == null || prev.dist(pose) > SurfaceStream.LAST_HOP + 1.0E-6) {
               return false;
            }
         }
      }
      return true;
   }

   private static Coord2d planEnd(NavPlan plan) {
      if (plan == null) {
         return null;
      }
      if (plan.selectedGoal != null) {
         return plan.selectedGoal;
      }
      if (plan.smoothedRoute != null && !plan.smoothedRoute.isEmpty()) {
         return plan.smoothedRoute.get(plan.smoothedRoute.size() - 1);
      }
      return null;
   }

   private static NavPlan withSelected(NavPlan plan, Coord2d pose) {
      if (plan == null) {
         return NavPlan.failed(0, NO_POSE);
      }
      return new NavPlan(plan.status, plan.rawRoute, plan.smoothedRoute, pose, plan.cost, plan.expanded, plan.obstacles, plan.complete, plan.snapped, plan.reason);
   }

   private static void replace(List<Candidate> considered, Candidate old, Candidate neu) {
      for (int i = 0; i < considered.size(); i++) {
         if (considered.get(i) == old) {
            considered.set(i, neu);
            return;
         }
      }
   }

   private static void replaceList(List<Candidate> list, Candidate old, Candidate neu) {
      replace(list, old, neu);
   }

   private static double fillCost(double[] costs, OccupancyGrid occ, Coord2d world) {
      if (costs == null || occ == null || world == null) {
         return Double.POSITIVE_INFINITY;
      }
      Coord c = occ.cellOf(world);
      if (c == null || c.x < 0 || c.y < 0 || c.x >= occ.w || c.y >= occ.h) {
         return Double.POSITIVE_INFINITY;
      }
      int i = c.y * occ.w + c.x;
      if (i < 0 || i >= costs.length) {
         return Double.POSITIVE_INFINITY;
      }
      return costs[i];
   }

   private static List<Candidate> compactByCell(List<Candidate> raw) {
      LinkedHashMap<Long, Integer> at = new LinkedHashMap<Long, Integer>();
      List<Candidate> out = new ArrayList<Candidate>();
      for (int i = 0; i < raw.size(); i++) {
         Candidate c = raw.get(i);
         if (c.cell == null || c.cell.x < 0 || c.cell.y < 0) {
            out.add(c);
            continue;
         }
         long key = ((long) c.cell.x << 32) ^ (c.cell.y & 0xffffffffL);
         Integer prev = at.get(Long.valueOf(key));
         if (prev == null) {
            at.put(Long.valueOf(key), Integer.valueOf(out.size()));
            out.add(c);
         } else if (betterCellCandidate(c, out.get(prev.intValue()))) {
            out.set(prev.intValue(), c);
         }
      }
      return out;
   }

   private static boolean betterCellCandidate(Candidate neu, Candidate old) {
      if (old.reject != null && neu.reject == null) {
         return true;
      }
      if (old.reject == null && neu.reject != null) {
         return false;
      }
      int d = Double.compare(neu.dist, old.dist);
      if (d != 0) {
         return d < 0;
      }
      if (neu.clearance != old.clearance) {
         return neu.clearance > old.clearance;
      }
      int x = Integer.compare(neu.cell.x, old.cell.x);
      if (x != 0) {
         return x < 0;
      }
      return Integer.compare(neu.cell.y, old.cell.y) < 0;
   }

   static Result replanExisting(Coord2d from, Coord2d pose, InteractionSpec spec, OccupancyGrid occ, Geometry geom) {
      if (from == null || pose == null || spec == null || occ == null) {
         return fail(spec, NO_POSE);
      }
      NavPlan plan = repairSwept(from, pose, spec, occ, geom);
      if (plan == null || !poseReached(plan, pose, spec, occ, geom)) {
         return fail(spec, NO_POSE);
      }
      if (geom != null && geom.enabled() && !routePolygonClear(plan.smoothedRoute, spec, geom)
         && !routePolygonClear(plan.rawRoute, spec, geom)) {
         return fail(spec, NO_POSE);
      }
      Coord cell = occ.cellOf(pose);
      Candidate c = new Candidate(
         cell == null ? Coord.of(-1, -1) : cell,
         pose,
         sideOf(spec.origin, pose),
         distanceToFootprint(pose, spec),
         0,
         true,
         plan.cost,
         null,
         plan
      );
      return new Result(spec, c, Collections.singletonList(c), withSelected(plan, pose), "");
   }

   private static Result fail(InteractionSpec spec, String reason) {
      return new Result(spec, null, Collections.<Candidate>emptyList(), NavPlan.failed(0, reason), reason);
   }

   static Coord2d refinePose(Coord2d seed, InteractionSpec spec, OccupancyGrid occ, Geometry geom) {
      if (seed == null || geom == null || !geom.enabled()) {
         return seed;
      }
      if (occupancyWalkable(occ, seed)) {
         return seed;
      }
      if (poseFits(seed, spec, occ, geom)) {
         return seed;
      }
      Coord2d best = null;
      double bestDist = Double.POSITIVE_INFINITY;
      List<Coord2d> trials = refineTrials(seed, spec, occ);
      for (int i = 0; i < trials.size(); i++) {
         Coord2d p = trials.get(i);
         if (p == null || !poseFits(p, spec, occ, geom)) {
            continue;
         }
         double d = distanceToFootprint(p, spec);
         if (d < bestDist) {
            bestDist = d;
            best = p;
         }
      }
      return best == null ? seed : best;
   }

   static boolean poseFits(Coord2d world, InteractionSpec spec, OccupancyGrid occ, Geometry geom) {
      if (world == null || spec == null) {
         return false;
      }
      Coord cell = occ == null ? null : occ.cellOf(world);
      if (cell == null || cell.x < 0 || cell.y < 0 || cell.x >= occ.w || cell.y >= occ.h) {
         return false;
      }
      int side = sideOf(spec.origin, world);
      double dist = distanceToFootprint(world, spec);
      return rejectReason(occ, spec, cell.x, cell.y, world, side, dist, 0, geom) == null;
   }

   static List<Coord2d> refineTrials(Coord2d seed, InteractionSpec spec, OccupancyGrid occ) {
      List<Coord2d> out = new ArrayList<Coord2d>();
      out.add(seed);
      Coord2d on = nearestBoundary(seed, spec);
      if (on != null) {
         Coord2d delta = seed.sub(on);
         double len = Math.hypot(delta.x, delta.y);
         if (len < 1.0E-6) {
            delta = Coord2d.of(0.0, 1.0);
            len = 1.0;
         }
         double nx = delta.x / len;
         double ny = delta.y / len;
         double standOff = Math.max(spec.minDist, occ == null ? 1.375 : occ.cell * 0.5);
         double[] offs = new double[]{standOff, standOff + 0.75, standOff + 1.5, standOff + 2.75, standOff + 4.0, standOff + 5.5};
         for (int i = 0; i < offs.length; i++) {
            out.add(on.add(nx * offs[i], ny * offs[i]));
         }
      }
      double cell = occ == null ? 2.75 : occ.cell;
      double[] d = new double[]{0.5, 1.0, 1.5, 2.0, cell * 0.5, cell, cell * 1.5, cell * 2.0};
      int[] dirx = new int[]{1, -1, 0, 0, 1, 1, -1, -1};
      int[] diry = new int[]{0, 0, 1, -1, 1, -1, 1, -1};
      for (int i = 0; i < d.length; i++) {
         for (int k = 0; k < dirx.length; k++) {
            out.add(seed.add(dirx[k] * d[i], diry[k] * d[i]));
         }
      }
      return out;
   }

   static Coord2d nearestGridApproach(Coord2d from, Coord2d pose, OccupancyGrid occ) {
      if (pose == null || occ == null) {
         return pose;
      }
      Coord pc = occ.cellOf(pose);
      if (pc == null) {
         return null;
      }
      if (occupancyWalkable(occ, pc.x, pc.y)) {
         return pose;
      }
      Coord2d best = null;
      double bestD = Double.POSITIVE_INFINITY;
      int pad = APPROACH_PAD;
      int x0 = Math.max(0, pc.x - pad);
      int x1 = Math.min(occ.w - 1, pc.x + pad);
      int y0 = Math.max(0, pc.y - pad);
      int y1 = Math.min(occ.h - 1, pc.y + pad);
      for (int y = y0; y <= y1; y++) {
         for (int x = x0; x <= x1; x++) {
            if (!occupancyWalkable(occ, x, y)) {
               continue;
            }
            Coord2d world = occ.world(x, y);
            double d = world.dist(pose);
            if (d < bestD) {
               bestD = d;
               best = world;
            }
         }
      }
      return best;
   }

   static boolean occupancyWalkable(OccupancyGrid occ, Coord2d world) {
      if (occ == null || world == null) {
         return false;
      }
      Coord c = occ.cellOf(world);
      return c != null && occupancyWalkable(occ, c.x, c.y);
   }

   static boolean occupancyWalkable(OccupancyGrid occ, int x, int y) {
      if (occ == null || x < 0 || y < 0 || x >= occ.w || y >= occ.h) {
         return false;
      }
      return occ.at(x, y) == OccupancyGrid.FREE;
   }

   private static NavPlan appendPose(NavPlan plan, Coord2d pose) {
      if (plan == null || pose == null) {
         return plan;
      }
      List<Coord2d> raw = new ArrayList<Coord2d>();
      if (plan.rawRoute != null) {
         raw.addAll(plan.rawRoute);
      }
      List<Coord2d> sm = new ArrayList<Coord2d>();
      if (plan.smoothedRoute != null) {
         sm.addAll(plan.smoothedRoute);
      }
      if (sm.isEmpty() || sm.get(sm.size() - 1).dist(pose) > 1.0E-4) {
         sm.add(pose);
      }
      if (raw.isEmpty() || raw.get(raw.size() - 1).dist(pose) > 1.0E-4) {
         raw.add(pose);
      }
      return new NavPlan(NavPlanStatus.REACHED, raw, sm, pose, plan.cost, plan.expanded, plan.obstacles, true, false, plan.reason);
   }

   static boolean routePolygonClear(List<Coord2d> route, InteractionSpec spec, Geometry geom) {
      return firstBlockedSegment(route, spec, geom) < 0;
   }

   static int firstBlockedSegment(List<Coord2d> route, InteractionSpec spec, Geometry geom) {
      if (route == null || route.size() < 2 || geom == null || !geom.enabled()) {
         return -1;
      }
      for (int i = 1; i < route.size(); i++) {
         Coord2d a = route.get(i - 1);
         Coord2d b = route.get(i);
         if (!segmentWalkable(a, b, spec, geom, i == 1)) {
            return i;
         }
         if (insideTarget(b, spec) && (spec == null || b.dist(InteractionGoals.clickPoint(a, spec)) > 1.0E-3)) {
            return i;
         }
      }
      return -1;
   }

   private static boolean segmentWalkable(Coord2d a, Coord2d b, InteractionSpec spec, Geometry geom, boolean leaving) {
      List<Coord2d[]> ignore = spec == null ? null : spec.polygons;
      LocalPlanner.PolyBounds bounds = geom.bounds;
      if (!leaving && LocalPlanner.bodyHitsAny(a, geom.playerBody, geom.solids, ignore, bounds)) {
         return false;
      }
      if (LocalPlanner.bodyHitsAny(b, geom.playerBody, geom.solids, ignore, bounds)) {
         return false;
      }
      double skip = leaving ? LocalPlanner.bodyExtent(geom.playerBody) + 1.0 : 0.0;
      for (int s = 1; s < 8; s++) {
         double t = s / 8.0;
         Coord2d p = Coord2d.of(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
         if (skip > 0.0 && p.dist(a) <= skip) {
            continue;
         }
         if (LocalPlanner.bodyHitsAny(p, geom.playerBody, geom.solids, ignore, bounds)) {
            return false;
         }
      }
      double pad = LocalPlanner.bodyExtent(geom.playerBody);
      for (int i = 0; i < geom.solids.size(); i++) {
         Coord2d[] poly = geom.solids.get(i);
         if (poly == null || poly.length < 2) {
            continue;
         }
         if (ignore != null && LocalPlanner.listed(poly, ignore)) {
            continue;
         }
         if (leaving && LocalPlanner.bodyHits(a, geom.playerBody, poly)) {
            continue;
         }
         if (bounds != null) {
            if (!bounds.nearSegment(i, a, b, pad)) {
               continue;
            }
         } else if (!LocalPlanner.segmentNearPoly(a, b, poly, pad)) {
            continue;
         }
         if (LocalPlanner.segmentHitsPolygon(a, b, poly, 0.0)) {
            return false;
         }
      }
      return true;
   }

   static Coord2d firstFailingPoint(List<Coord2d> route, InteractionSpec spec, Geometry geom) {
      if (route == null || route.size() < 2 || geom == null || !geom.enabled()) {
         return null;
      }
      List<Coord2d[]> ignore = spec == null ? null : spec.polygons;
      LocalPlanner.PolyBounds bounds = geom.bounds;
      double skip0 = LocalPlanner.bodyExtent(geom.playerBody) + 1.0;
      for (int i = 1; i < route.size(); i++) {
         Coord2d a = route.get(i - 1);
         Coord2d b = route.get(i);
         boolean leaving = i == 1;
         if (a == null || b == null) {
            continue;
         }
         if (LocalPlanner.bodyHitsAny(b, geom.playerBody, geom.solids, ignore, bounds)) {
            return b;
         }
         for (int s = 1; s < 8; s++) {
            double t = s / 8.0;
            Coord2d p = Coord2d.of(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            if (leaving && p.dist(a) <= skip0) {
               continue;
            }
            if (LocalPlanner.bodyHitsAny(p, geom.playerBody, geom.solids, ignore, bounds)) {
               return p;
            }
         }
      }
      int blocked = firstBlockedSegment(route, spec, geom);
      if (blocked < 1) {
         return null;
      }
      Coord2d a = route.get(blocked - 1);
      Coord2d b = route.get(blocked);
      if (a == null || b == null) {
         return null;
      }
      return Coord2d.of((a.x + b.x) * 0.5, (a.y + b.y) * 0.5);
   }

   private static OccupancyGrid blockPoint(OccupancyGrid src, Coord2d at, Coord2d keep) {
      if (src == null || src.occ == null || at == null) {
         return null;
      }
      Coord cell = src.cellOf(at);
      Coord keepCell = src.cellOf(keep);
      if (cell == null || cell.x < 0 || cell.y < 0 || cell.x >= src.w || cell.y >= src.h) {
         return null;
      }
      if (keepCell != null && cell.x == keepCell.x && cell.y == keepCell.y) {
         return null;
      }
      int idx = cell.y * src.w + cell.x;
      if (src.occ[idx] == OccupancyGrid.SOLID) {
         return null;
      }
      byte[] next = Arrays.copyOf(src.occ, src.occ.length);
      next[idx] = OccupancyGrid.SOLID;
      return new OccupancyGrid(src.origin, src.w, src.h, src.cell, next, src.start, src.goal, src.freeGoal, src.astar);
   }
}
