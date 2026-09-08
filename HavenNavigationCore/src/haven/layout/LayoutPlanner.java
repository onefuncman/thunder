package haven.layout;

import haven.Coord;
import haven.Coord2d;
import haven.nav.InteractionSpec;
import haven.nav.NavPlan;
import haven.nav.NavPlanStatus;
import haven.pathfinding.ApproachGoals;
import haven.pathfinding.LocalPlanner;
import haven.pathfinding.OccupancyGrid;
import haven.pathfinding.PlanningTrace;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, renderer-independent layout planner.
 *
 * <p>Given a request it enumerates candidate anchor positions in row-major
 * order (y, then x) across the requested rectangular area and accepts each
 * candidate only if all of the following hold:
 * <ol>
 *   <li>its footprint stays inside the area and inside the occupancy grid,</li>
 *   <li>it does not overlap any earlier accepted placement, any occupied/keep
 *       shape, or any SOLID occupancy cell,</li>
 *   <li>an interaction stand is feasible and reachable from
 *       {@code approachFrom} (a legal stand around the footprint for blocking
 *       footprints; a walkable route onto the footprint's anchor cell for
 *       passable ones),</li>
 *   <li>every earlier accepted placement still has its stand reachable from
 *       {@code approachFrom} after this footprint is added (walkable
 *       interaction lanes are preserved).</li>
 * </ol>
 *
 * <p>Blocking footprints (things you stand beside: furniture, containers) are
 * treated as obstacles while proving stands; passable footprints (things you
 * stand on or over) are not, and their stand is the footprint's anchor cell.
 *
 * <p>Notes:
 * <ul>
 *   <li>Determinism: enumeration order and all internal tie-breaks are fixed;
 *       re-planning the same request yields identical results.</li>
 *   <li>Stands are proven against the occupancy at accept time. Later
 *       placements that would orphan a prior stand are rejected
 *       ({@link #LANE_BLOCKED}) rather than silently invalidated; a route that
 *       is merely cut is re-planned through the committed occupancy.</li>
 *   <li>The placement pitch should be at least the occupancy cell size;
 *       smaller pitches make overlap rejection conservative.</li>
 * </ul>
 */
public final class LayoutPlanner {
   /** Candidate overlaps an earlier accepted placement. */
   public static final String OVERLAP_PLACED = "overlap_placed";
   /** Candidate overlaps a kept shape. */
   public static final String OVERLAP_KEEP = "overlap_keep";
   /** Candidate overlaps an occupied shape or an existing SOLID occupancy cell. */
   public static final String OVERLAP_OCCUPIED = "overlap_occupied";
   /** Candidate footprint leaves the requested area or the occupancy grid. */
   public static final String OUTSIDE_AREA = "outside_area";
   /** No candidate position exists or remains free. */
   public static final String NO_FREE_SPACE = "no_free_space";
   /** No stand could be proven reachable for the candidate itself. */
   public static final String UNREACHABLE = "unreachable";
   /** The candidate's footprint would orphan a stand of an earlier placement. */
   public static final String LANE_BLOCKED = "lane_blocked";
   /** No legal stand pose exists geometrically. */
   public static final String NO_INTERACTION_POSE = "no_interaction_pose";
   /** Approach geometry is unavailable (should not occur for layout planning). */
   public static final String GEOMETRY_UNAVAILABLE = "geometry_unavailable";

   /** Reserved-cell attribution: none. */
   private static final byte NONE = 0;
   private static final byte OCCUPIED = 1;
   private static final byte KEEP = 2;

   private static final double EPS = 1.0E-7;

   private LayoutPlanner() {
   }

   public static LayoutPlanResult plan(LayoutRequest req) {
      OccupancyGrid occ = req.occupancy;
      LayoutFootprint fp = req.footprint;
      double px = req.pitchX;
      double py = req.pitchY;
      int cols = gridCols(req, occ);
      int rows = gridRows(req, occ);
      int bw = fp.bboxW();
      int bh = fp.bboxH();
      List<LayoutPlacement> out = new ArrayList<LayoutPlacement>();
      Map<String, Integer> rejects = new LinkedHashMap<String, Integer>();

      byte[] reservedBy = new byte[occ.w * occ.h];
      OccupancyGrid base = withReserved(req, occ, reservedBy);
      byte[] working = base.occ.clone();
      Coord approachCell = occ.cellOf(req.approachFrom);

      if (req.count == 0) {
         return new LayoutPlanResult(LayoutPlanResult.Status.PLANNED, out, "", rejects);
      }
      if (cols < bw || rows < bh) {
         rejects.put(NO_FREE_SPACE, Integer.valueOf(1));
         return new LayoutPlanResult(LayoutPlanResult.Status.FAILED, out, NO_FREE_SPACE, rejects);
      }

      boolean[] gridTaken = new boolean[cols * rows];
      int maxJ = rows - bh;
      int maxI = cols - bw;
      for (int j = 0; j <= maxJ && out.size() < req.count; j++) {
         for (int i = 0; i <= maxI && out.size() < req.count; i++) {
            List<int[]> coverage = new ArrayList<int[]>();
            String reject = null;
            double anchorX = req.areaMin.x + i * px;
            double anchorY = req.areaMin.y + j * py;

            // 1) exact overlap with earlier placements (placement-grid space)
            for (Coord c : fp.cells()) {
               int gi = i + c.x;
               int gj = j + c.y;
               if (gridTaken[gj * cols + gi]) {
                  reject = OVERLAP_PLACED;
                  break;
               }
            }
            if (reject == null) {
               // 2) bounds + reserved/occupied overlap (occupancy-cell coverage)
               for (Coord c : fp.cells()) {
                  double x0 = anchorX + c.x * px;
                  double x1 = x0 + px;
                  double y0 = anchorY + c.y * py;
                  double y1 = y0 + py;
                  int cx0 = floorCellX(occ, x0 + EPS);
                  int cx1 = floorCellX(occ, x1 - EPS);
                  int cy0 = floorCellY(occ, y0 + EPS);
                  int cy1 = floorCellY(occ, y1 - EPS);
                  if (cx0 < 0 || cy0 < 0 || cx1 >= occ.w || cy1 >= occ.h) {
                     reject = OUTSIDE_AREA;
                     break;
                  }
                  for (int cy = cy0; cy <= cy1; cy++) {
                     for (int cx = cx0; cx <= cx1; cx++) {
                        int idx = cy * occ.w + cx;
                        byte rv = reservedBy[idx];
                        if (rv == KEEP) {
                           reject = OVERLAP_KEEP;
                           break;
                        }
                        if (rv == OCCUPIED || base.occ[idx] == OccupancyGrid.SOLID) {
                           reject = OVERLAP_OCCUPIED;
                           break;
                        }
                        if (fp.blocksApproach() && approachCell != null && approachCell.x == cx && approachCell.y == cy) {
                           reject = UNREACHABLE;
                           break;
                        }
                        coverage.add(new int[]{cx, cy});
                     }
                     if (reject != null) {
                        break;
                     }
                  }
                  if (reject != null) {
                     break;
                  }
               }
            }
            if (reject != null) {
               tally(rejects, reject);
               continue;
            }

            // 3) tentative occupancy: blocking footprints become obstacles for approach
            byte[] tentative = working.clone();
            if (fp.blocksApproach()) {
               for (int[] cell : coverage) {
                  tentative[cell[1] * occ.w + cell[0]] = OccupancyGrid.SOLID;
               }
            }
            OccupancyGrid tentGrid = new OccupancyGrid(
               occ.origin, occ.w, occ.h, occ.cell, tentative, occ.start, occ.goal, occ.freeGoal, occ.astar
            );

            Coord2d anchor = Coord2d.of(anchorX, anchorY);
            Coord2d stand;
            List<Coord2d> standRoute;
            if (fp.blocksApproach()) {
               ApproachGoals.Result ar = approach(req, fp, anchor, out.size(), tentGrid);
               if (!ar.ok()) {
                  tally(rejects, approachReject(ar.status));
                  continue;
               }
               stand = ar.pose.selected.world;
               standRoute = copyRoute(ar.pose.plan.smoothedRoute, stand);
            } else {
               Coord2d standTarget = Coord2d.of(anchor.x + px * 0.5, anchor.y + py * 0.5);
               NavPlan p = LocalPlanner.planFromOccupancy(
                  req.approachFrom, standTarget, false, 0.0, tentGrid, 0, new PlanningTrace()
               );
               if (!reached(p, standTarget, occ)) {
                  tally(rejects, UNREACHABLE);
                  continue;
               }
               stand = standTarget;
               standRoute = copyRoute(p.smoothedRoute, stand);
            }

            // 4) lane retention: earlier placements keep reachable stands; cut routes re-plan
            Map<Integer, List<Coord2d>> refreshed = null;
            if (fp.blocksApproach()) {
               double[] costs = LocalPlanner.occupancyCosts(req.approachFrom, tentGrid);
               Set<Long> fresh = new HashSet<Long>();
               for (int[] cell : coverage) {
                  fresh.add(Long.valueOf((((long) cell[0]) << 32) ^ (cell[1] & 0xffffffffL)));
               }
               for (int k = 0; k < out.size(); k++) {
                  LayoutPlacement q = out.get(k);
                  Coord cq = occ.cellOf(q.stand);
                  if (cq == null || cq.x < 0 || cq.y < 0 || cq.x >= occ.w || cq.y >= occ.h
                     || tentGrid.occ[cq.y * occ.w + cq.x] != OccupancyGrid.FREE
                     || !Double.isFinite(costs[cq.y * occ.w + cq.x])) {
                     tally(rejects, LANE_BLOCKED);
                     reject = LANE_BLOCKED;
                     break;
                  }
                  if (routeTouches(q.standRoute, fresh, occ)) {
                     NavPlan rp = LocalPlanner.planFromOccupancy(
                        req.approachFrom, q.stand, false, 0.0, tentGrid, 0, new PlanningTrace()
                     );
                     if (!reached(rp, q.stand, occ)) {
                        tally(rejects, LANE_BLOCKED);
                        reject = LANE_BLOCKED;
                        break;
                     }
                     if (refreshed == null) {
                        refreshed = new LinkedHashMap<Integer, List<Coord2d>>();
                     }
                     refreshed.put(Integer.valueOf(k), copyRoute(rp.smoothedRoute, q.stand));
                  }
               }
               if (reject != null) {
                  continue;
               }
            }

            // 5) commit
            if (fp.blocksApproach()) {
               working = tentative;
            }
            for (Coord c : fp.cells()) {
               gridTaken[(j + c.y) * cols + (i + c.x)] = true;
            }
            LayoutPlacement placed = new LayoutPlacement(out.size(), i, j, anchor, fp, stand, standRoute);
            out.add(placed);
            if (refreshed != null) {
               for (Map.Entry<Integer, List<Coord2d>> e : refreshed.entrySet()) {
                  int k = e.getKey().intValue();
                  LayoutPlacement old = out.get(k);
                  out.set(k, new LayoutPlacement(
                     old.index, old.gridX, old.gridY, old.world, old.footprint, old.stand, e.getValue()
                  ));
               }
            }
         }
      }

      LayoutPlanResult.Status status;
      if (out.size() >= req.count) {
         status = LayoutPlanResult.Status.PLANNED;
      } else if (out.isEmpty()) {
         status = LayoutPlanResult.Status.FAILED;
      } else {
         status = LayoutPlanResult.Status.PARTIAL;
      }
      String reason = status == LayoutPlanResult.Status.PLANNED ? "" : dominant(rejects);
      return new LayoutPlanResult(status, out, reason, rejects);
   }

   private static ApproachGoals.Result approach(LayoutRequest req, LayoutFootprint fp, Coord2d anchor, int index, OccupancyGrid occ) {
      double hw = fp.bboxW() * req.pitchX * 0.5;
      double hh = fp.bboxH() * req.pitchY * 0.5;
      Coord2d center = Coord2d.of(anchor.x + hw, anchor.y + hh);
      List<Coord2d[]> polys = new ArrayList<Coord2d[]>();
      for (Coord c : fp.cells()) {
         double x0 = anchor.x + c.x * req.pitchX;
         double y0 = anchor.y + c.y * req.pitchY;
         polys.add(new Coord2d[]{
            Coord2d.of(x0, y0),
            Coord2d.of(x0 + req.pitchX, y0),
            Coord2d.of(x0 + req.pitchX, y0 + req.pitchY),
            Coord2d.of(x0, y0 + req.pitchY)
         });
      }
      double maxDist = Math.max(occ.cell * 4.0, Math.hypot(hw, hh) + occ.cell * 4.0);
      InteractionSpec spec = new InteractionSpec(
         "layout-" + index, center, Coord2d.of(hw, hh), InteractionSpec.ALL_SIDES, 0.0, maxDist, 0, null, "", polys, ""
      );
      return ApproachGoals.plan(req.approachFrom, spec, occ);
   }

   private static String approachReject(ApproachGoals.Status status) {
      switch (status) {
         case UNREACHABLE:
            return UNREACHABLE;
         case GEOMETRY_UNAVAILABLE:
            return GEOMETRY_UNAVAILABLE;
         default:
            return NO_INTERACTION_POSE;
      }
   }

   private static OccupancyGrid withReserved(LayoutRequest req, OccupancyGrid occ, byte[] reservedBy) {
      byte[] next = occ.occ.clone();
      addShapes(req, req.occupiedShapes, occ, next, reservedBy, OCCUPIED);
      addShapes(req, req.keepShapes, occ, next, reservedBy, KEEP);
      return new OccupancyGrid(occ.origin, occ.w, occ.h, occ.cell, next, occ.start, occ.goal, occ.freeGoal, occ.astar);
   }

   /** Reserved shapes occupy their cells at the request's pitch (same grid as placements). */
   private static void addShapes(LayoutRequest req, List<LayoutShape> shapes, OccupancyGrid occ, byte[] next, byte[] reservedBy, byte tag) {
      if (shapes == null) {
         return;
      }
      for (LayoutShape s : shapes) {
         double ax = s.worldAnchor.x;
         double ay = s.worldAnchor.y;
         for (Coord c : s.footprint.cells()) {
            double x0 = ax + c.x * req.pitchX;
            double x1 = x0 + req.pitchX;
            double y0 = ay + c.y * req.pitchY;
            double y1 = y0 + req.pitchY;
            int cx0 = floorCellX(occ, x0 + EPS);
            int cx1 = floorCellX(occ, x1 - EPS);
            int cy0 = floorCellY(occ, y0 + EPS);
            int cy1 = floorCellY(occ, y1 - EPS);
            for (int cy = Math.max(0, cy0); cy <= Math.min(occ.h - 1, cy1); cy++) {
               for (int cx = Math.max(0, cx0); cx <= Math.min(occ.w - 1, cx1); cx++) {
                  int idx = cy * occ.w + cx;
                  reservedBy[idx] = tag;
                  next[idx] = OccupancyGrid.SOLID;
               }
            }
         }
      }
   }

   private static int gridCols(LayoutRequest req, OccupancyGrid occ) {
      return Math.max(0, (int) Math.floor((req.areaMax.x - req.areaMin.x + EPS) / req.pitchX));
   }

   private static int gridRows(LayoutRequest req, OccupancyGrid occ) {
      return Math.max(0, (int) Math.floor((req.areaMax.y - req.areaMin.y + EPS) / req.pitchY));
   }

   private static int floorCellX(OccupancyGrid occ, double worldX) {
      return (int) Math.floor((worldX - occ.origin.x) / occ.cell);
   }

   private static int floorCellY(OccupancyGrid occ, double worldY) {
      return (int) Math.floor((worldY - occ.origin.y) / occ.cell);
   }

   private static void tally(Map<String, Integer> rejects, String reason) {
      Integer v = rejects.get(reason);
      rejects.put(reason, Integer.valueOf(v == null ? 1 : v.intValue() + 1));
   }

   private static String dominant(Map<String, Integer> rejects) {
      if (rejects == null || rejects.isEmpty()) {
         return NO_FREE_SPACE;
      }
      String best = null;
      int bestN = -1;
      for (Map.Entry<String, Integer> e : rejects.entrySet()) {
         int v = e.getValue().intValue();
         if (v > bestN || (v == bestN && best != null && e.getKey().compareTo(best) < 0)) {
            best = e.getKey();
            bestN = v;
         }
      }
      return best;
   }

   private static List<Coord2d> copyRoute(List<Coord2d> route, Coord2d stand) {
      if (route == null || route.isEmpty()) {
         return Collections.singletonList(stand);
      }
      return new ArrayList<Coord2d>(route);
   }

   private static boolean routeTouches(List<Coord2d> route, Set<Long> cells, OccupancyGrid occ) {
      if (route == null || route.isEmpty()) {
         return false;
      }
      double step = occ.cell * 0.5;
      for (int i = 1; i < route.size(); i++) {
         Coord2d a = route.get(i - 1);
         Coord2d b = route.get(i);
         if (a == null || b == null) {
            continue;
         }
         if (hitsCell(a, cells, occ) || hitsCell(b, cells, occ)) {
            return true;
         }
         double len = a.dist(b);
         if (len <= step) {
            continue;
         }
         int n = (int) Math.ceil(len / step);
         for (int s = 1; s < n; s++) {
            double t = s / (double) n;
            Coord2d p = Coord2d.of(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            if (hitsCell(p, cells, occ)) {
               return true;
            }
         }
      }
      return false;
   }

   private static boolean hitsCell(Coord2d p, Set<Long> cells, OccupancyGrid occ) {
      Coord c = occ.cellOf(p);
      return c != null && cells.contains(Long.valueOf((((long) c.x) << 32) ^ (c.y & 0xffffffffL)));
   }

   private static boolean reached(NavPlan plan, Coord2d target, OccupancyGrid occ) {
      if (plan == null || target == null) {
         return false;
      }
      if (plan.status == NavPlanStatus.FAILED || plan.status == NavPlanStatus.PARTIAL || plan.status == NavPlanStatus.CLIPPED) {
         return false;
      }
      Coord2d end = planEnd(plan);
      double cell = occ == null ? LocalPlanner.CELL : occ.cell;
      return end != null && end.dist(target) <= cell + 1.0E-4;
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
}
