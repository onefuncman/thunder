package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.NavPlan;
import haven.nav.NavPlanStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Local occupancy planner: body clearance, eight-way A*, no-corner-cutting,
 * collision-checked smoothing, and bounded local-horizon clipping. No live-client or renderer types.
 */
public final class LocalPlanner {
   public static final double CELL = 2.75;
   public static final double PAD = 1.0;
   public static final double DEFAULT_AGENT_RADIUS = 4.5;
   public static final int MAX_SIDE = 192;
   public static final int MAX_EXPANDED = 36864;
   /** Padding around start→dest so A* can walk around a village yard, not only along the straight line. */
   private static final double MARGIN = 110.0;
   public static final double OVERLAP = 2.75 * Math.sqrt(2.0) / 2.0;
   public static final double MAX_REACH = 462.0;

   private LocalPlanner() {
   }

   public static NavPlan planCore(
      Coord2d start,
      List<Coord2d> destinations,
      List<Coord2d> targets,
      List<Boolean> clippedFlags,
      boolean snap,
      double radius,
      NavGrid grid,
      boolean[] solid,
      boolean[] dilated,
      int obstacles,
      PlanningTrace tr
   ) {
      int w = grid.w;
      int h = grid.h;
      if (targets != null && !targets.isEmpty()) {
         Coord sc = grid.cell(start);
         tr.startCell = sc;
         if (sc.x >= 0 && sc.y >= 0 && sc.x < w && sc.y < h) {
            int dil = dilationCells(radius);
            tr.dilation = dil;
            tr.startInSolid = solid[sc.y * w + sc.x];
            applyClearanceCost(grid.cost, solid, w, h);
            tr.startBlocked = dilated[sc.y * w + sc.x];
            if (grid.exactCollisionEnabled()) {
               // The server may leave the player touching a hitbox after an
               // interaction. Open only the occupied start node; exact swept
               // collision governs the departure edge. Never carve a radius
               // through neighboring object hitboxes.
               grid.allowStart(sc, start);
            } else {
               openFootprint(grid.blocked, w, h, sc.x, sc.y, dil, solid);
               if (grid.blocked[sc.y * w + sc.x]) {
                  openStartPocket(grid.blocked, w, h, sc.x, sc.y, dil + 1);
               }
            }

            tr.startBlockedAfter = grid.blocked[sc.y * w + sc.x];
            List<Coord> goalCells = new ArrayList<>();
            List<Coord2d> exactAt = new ArrayList<>();
            List<Boolean> clippedOf = new ArrayList<>();
            Coord requestedGoal = clamp(grid.cell(targets.get(0)), w, h);
            tr.goalCell = requestedGoal;
            tr.goalBlocked = grid.blocked[requestedGoal.y * w + requestedGoal.x];

            for (int ti = 0; ti < targets.size(); ti++) {
               Coord2d t = targets.get(ti);
               Coord req = clamp(grid.cell(t), w, h);
               if (!grid.exactCollisionEnabled() && !solid[req.y * w + req.x] && grid.blocked[req.y * w + req.x]) {
                  openFootprint(grid.blocked, w, h, req.x, req.y, dil, solid);
               }

               Coord gc = req;
               boolean exactPointBlocked = grid.exactCollisionEnabled() && !grid.positionClear(t);
               if (grid.blocked[req.y * w + req.x] || exactPointBlocked) {
                  if (!snap) {
                     continue;
                  }

                  gc = nearestFree(grid.blocked, w, h, req, sc);
               }

               if (gc != null) {
                  goalCells.add(gc);
                  exactAt.add(gc.equals(req) ? t : null);
                  clippedOf.add(clippedFlags.get(ti));
               }
            }

            if (goalCells.isEmpty()) {
               tr.reason = "no_free_goal";
               return NavPlan.failed(obstacles, tr.reason);
            } else {
               tr.freeGoal = goalCells.get(0);
               GridAStar.Result result = GridAStar.find(grid, sc, goalCells, 36864);
               List<Coord2d> raw = new ArrayList<>();

               for (Coord c : result.cells) {
                  raw.add(grid.world(c));
               }
               // Execution begins at the authoritative live coordinate, not
               // at the center of its occupancy cell. This matters when the
               // server has left the body fractionally overlapping a hitbox.
               if (!raw.isEmpty()) {
                  raw.set(0, start);
               }

               tr.astar = new ArrayList<>(raw);
               tr.occupancy = OccupancyGrid.capture(
                  grid.origin, w, h, 2.75, solid, dilated, grid.blocked, sc, requestedGoal, goalCells.get(0), result.cells
               );
               boolean reachedRequested = false;
               boolean reachedClipped = false;
               if (result.complete && !raw.isEmpty()) {
                  Coord last = result.cells.get(result.cells.size() - 1);

                  for (int i = 0; i < goalCells.size(); i++) {
                     if (goalCells.get(i).equals(last)) {
                        reachedClipped = clippedOf.get(i);
                        Coord2d exact = exactAt.get(i);
                        if (exact != null
                           && exact.x >= grid.origin.x
                           && exact.y >= grid.origin.y
                           && exact.x < grid.origin.x + (double)w * 2.75
                           && exact.y < grid.origin.y + (double)h * 2.75
                           && (!grid.exactCollisionEnabled() || grid.positionClear(exact))) {
                           Coord2d before = raw.size() >= 2 ? raw.get(raw.size() - 2) : start;
                           if (!grid.exactCollisionEnabled() || grid.segmentClear(before, exact, raw.size() <= 2)) {
                              raw.set(raw.size() - 1, exact);
                              reachedRequested = true;
                           }
                        }
                        break;
                     }
                  }
               }

               List<Coord2d> waypoints = smooth(raw, grid, grid.blocked);
               boolean complete = result.complete;
               NavPlanStatus status;
               if (waypoints.size() < 2) {
                  status = complete ? NavPlanStatus.REACHED : NavPlanStatus.FAILED;
               } else if (!complete) {
                  status = NavPlanStatus.PARTIAL;
               } else if (reachedClipped) {
                  status = NavPlanStatus.CLIPPED;
               } else if (!reachedRequested) {
                  status = NavPlanStatus.SNAPPED;
               } else {
                  status = NavPlanStatus.REACHED;
               }

               if (waypoints.size() < 2) {
                  tr.reason = minDist(start, destinations) <= 11.0 ? "already_there" : "no_route";
               } else if (!complete) {
                  tr.reason = "partial";
               } else if (reachedClipped) {
                  tr.reason = "clipped";
               } else if (!reachedRequested) {
                  tr.reason = "snapped";
               } else {
                  tr.reason = "ok";
               }

               tr.waypoints = waypoints;
               tr.complete = complete;
               tr.expanded = result.expanded;
               return NavPlan.create(status, raw, waypoints, complete, !reachedRequested, result.expanded, obstacles, tr.reason);
            }
         } else {
            tr.reason = "start_off_grid";
            return NavPlan.failed(obstacles, tr.reason);
         }
      } else {
         tr.reason = "no_goal";
         return NavPlan.failed(obstacles, tr.reason);
      }
   }

   /**
    * Replay planning against a captured occupancy grid. Reconstructs the
    * pre-carve solid/dilated masks and calls {@link #planCore} — the same
    * planner used by live {@link #planAny}.
    */
   public static NavPlan planFromOccupancy(
      Coord2d start, Coord2d dest, boolean snap, double radius, OccupancyGrid occ, int obstacles, PlanningTrace tr
   ) {
      if (tr == null) {
         tr = new PlanningTrace();
      }
      if (start == null || occ == null || occ.w <= 0 || occ.h <= 0 || occ.occ == null) {
         tr.reason = start == null ? "no_player" : "no_goal";
         return NavPlan.failed(obstacles, tr.reason);
      }
      List<Coord2d> destinations = dest == null ? Collections.emptyList() : Collections.singletonList(dest);
      LocalPlanner.ClipResult clip = clipToHorizon(start, destinations);
      List<Coord2d> targets = clip.targets;
      tr.sx = start.x;
      tr.sy = start.y;
      if (dest != null) {
         tr.dx = dest.x;
         tr.dy = dest.y;
      }
      tr.radius = radius;
      tr.gridW = occ.w;
      tr.gridH = occ.h;
      if (targets.isEmpty()) {
         tr.reason = dest == null ? "no_goal" : "no_player";
         return NavPlan.failed(obstacles, tr.reason);
      }
      NavGrid grid = new NavGrid(occ.origin, occ.w, occ.h);
      boolean[] solid = new boolean[occ.w * occ.h];
      boolean[] dilated = new boolean[occ.w * occ.h];
      int n = Math.min(occ.occ.length, solid.length);
      for (int i = 0; i < n; i++) {
         byte v = occ.occ[i];
         if (v == OccupancyGrid.SOLID) {
            solid[i] = true;
            grid.blocked[i] = true;
         } else if (v == OccupancyGrid.DILATED || v == OccupancyGrid.CARVED) {
            dilated[i] = true;
            grid.blocked[i] = true;
         }
      }
      return planCore(start, destinations, targets, clip.clipped, snap, radius, grid, solid, dilated, obstacles, tr);
   }

   /**
    * Occupancy g-score from {@code start} to every cell, using the same
    * blocked mask, clearance costs, and start-pocket carve as
    * {@link #planFromOccupancy} with radius 0.
    */
   public static double[] occupancyCosts(Coord2d start, OccupancyGrid occ) {
      return occupancyDistances(start, occ, true);
   }

   /**
    * Shortest legal route length from {@code start} to every cell. Unlike
    * {@link #occupancyCosts}, this does not add a soft penalty for walking
    * near an obstacle. Dilated/body-blocked cells remain impassable.
    */
   public static double[] occupancyRouteLengths(Coord2d start, OccupancyGrid occ) {
      return occupancyDistances(start, occ, false);
   }

   private static double[] occupancyDistances(Coord2d start, OccupancyGrid occ,
                                               boolean penalizeLowClearance) {
      if (start == null || occ == null || occ.w <= 0 || occ.h <= 0 || occ.occ == null) {
         return new double[0];
      }
      int w = occ.w;
      int h = occ.h;
      NavGrid grid = new NavGrid(occ.origin, w, occ.h);
      boolean[] solid = new boolean[w * h];
      int n = Math.min(occ.occ.length, solid.length);
      for (int i = 0; i < n; i++) {
         byte v = occ.occ[i];
         if (v == OccupancyGrid.SOLID) {
            solid[i] = true;
            grid.blocked[i] = true;
         } else if (v == OccupancyGrid.DILATED || v == OccupancyGrid.CARVED) {
            grid.blocked[i] = true;
         }
      }
      Coord sc = occ.cellOf(start);
      if (sc == null || sc.x < 0 || sc.y < 0 || sc.x >= w || sc.y >= h) {
         double[] miss = new double[w * h];
         Arrays.fill(miss, Double.POSITIVE_INFINITY);
         return miss;
      }
      int dil = dilationCells(0.0);
      if (penalizeLowClearance) {
         applyClearanceCost(grid.cost, solid, w, h);
      }
      openFootprint(grid.blocked, w, h, sc.x, sc.y, dil, solid);
      if (grid.blocked[sc.y * w + sc.x]) {
         openStartPocket(grid.blocked, w, h, sc.x, sc.y, dil + 1);
      }
      return GridAStar.fill(grid, sc, MAX_EXPANDED).distance;
   }

   public static LocalPlanner.ClipResult clipToHorizon(Coord2d start, List<Coord2d> destinations) {
      ClipResult out = new ClipResult();
      double maxReach = maxReach();
      if (start != null && destinations != null) {
         for (Coord2d d : destinations) {
            if (d != null) {
               Coord2d delta = d.sub(start);
               boolean wasClipped = delta.abs() > maxReach;
               out.targets.add(wasClipped ? start.add(delta.norm().mul(maxReach)) : d);
               out.clipped.add(wasClipped);
            }
         }

         return out;
      } else {
         return out;
      }
   }

   public static NavGrid planGrid(Coord2d start, List<Coord2d> targets) {
      double minx = start.x;
      double miny = start.y;
      double maxx = start.x;
      double maxy = start.y;

      for (Coord2d t : targets) {
         if (t != null) {
            minx = Math.min(minx, t.x);
            miny = Math.min(miny, t.y);
            maxx = Math.max(maxx, t.x);
            maxy = Math.max(maxy, t.y);
         }
      }

      double pad = 2.0 * MARGIN;
      double spanX = Math.min(528.0, maxx - minx + pad);
      double spanY = Math.min(528.0, maxy - miny + pad);
      double cx = (minx + maxx) * 0.5;
      double cy = (miny + maxy) * 0.5;
      int w = Math.max(8, Math.min(192, (int)Math.ceil(spanX / 2.75)));
      int h = Math.max(8, Math.min(192, (int)Math.ceil(spanY / 2.75)));
      return new NavGrid(alignedOrigin(cx - (double)w * 2.75 * 0.5, cy - (double)h * 2.75 * 0.5), w, h);
   }

   public static double minDist(Coord2d start, List<Coord2d> dests) {
      double best = Double.POSITIVE_INFINITY;
      if (start != null && dests != null) {
         for (Coord2d d : dests) {
            if (d != null) {
               best = Math.min(best, start.dist(d));
            }
         }

         return best;
      } else {
         return best;
      }
   }
   public static double maxReach() {
      return MAX_REACH;
   }

   public static Coord2d alignedOrigin(double x, double y) {
      return Coord2d.of(Math.floor(x / 2.75) * 2.75, Math.floor(y / 2.75) * 2.75);
   }
   public static Coord clamp(Coord c, int w, int h) {
      return Coord.of(Math.max(0, Math.min(w - 1, c.x)), Math.max(0, Math.min(h - 1, c.y)));
   }

   public static void openFootprint(boolean[] blocked, int w, int h, int sx, int sy, int radius, boolean[] solid) {
      if (blocked != null && radius >= 0 && w > 0 && h > 0) {
         int r2 = radius * radius;

         for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
               if (dx * dx + dy * dy <= r2) {
                  int x = sx + dx;
                  int y = sy + dy;
                  if (x >= 0 && y >= 0 && x < w && y < h && (solid == null || !solid[y * w + x])) {
                     blocked[y * w + x] = false;
                  }
               }
            }
         }
      }
   }

   public static void openFootprint(boolean[] blocked, int w, int h, int sx, int sy, int radius) {
      openFootprint(blocked, w, h, sx, sy, radius, null);
   }

   public static void openStartPocket(boolean[] blocked, int w, int h, int sx, int sy, int radius) {
      if (blocked != null && radius >= 0 && w > 0 && h > 0) {
         int r2 = radius * radius;

         for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
               if (dx * dx + dy * dy <= r2) {
                  int x = sx + dx;
                  int y = sy + dy;
                  if (x >= 0 && y >= 0 && x < w && y < h) {
                     blocked[y * w + x] = false;
                  }
               }
            }
         }
      }
   }

   public static Coord nearestFree(boolean[] blocked, int w, int h, Coord goal, Coord from) {
      if (blocked != null && goal != null) {
         if (goal.x >= 0 && goal.y >= 0 && goal.x < w && goal.y < h) {
            if (!blocked[goal.y * w + goal.x]) {
               return goal;
            } else {
               Coord origin = from == null ? goal : from;

               for (int radius = 1; radius < Math.max(w, h); radius++) {
                  Coord best = null;
                  double bestD = Double.POSITIVE_INFINITY;

                  for (int y = goal.y - radius; y <= goal.y + radius; y++) {
                     for (int x = goal.x - radius; x <= goal.x + radius; x++) {
                        if (Math.max(Math.abs(x - goal.x), Math.abs(y - goal.y)) == radius && x >= 0 && y >= 0 && x < w && y < h && !blocked[y * w + x]) {
                           double d = (double)((x - origin.x) * (x - origin.x) + (y - origin.y) * (y - origin.y));
                           if (d < bestD) {
                              bestD = d;
                              best = Coord.of(x, y);
                           }
                        }
                     }
                  }

                  if (best != null) {
                     if (radius == 1) {
                        return standoff(blocked, w, h, goal, best);
                     }

                     return best;
                  }
               }

               return null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   public static Coord standoff(boolean[] blocked, int w, int h, Coord goal, Coord at) {
      int sx = Integer.signum(at.x - goal.x);
      int sy = Integer.signum(at.y - goal.y);
      int nx = at.x + sx;
      int ny = at.y + sy;
      if (nx >= 0 && ny >= 0 && nx < w && ny < h && !blocked[ny * w + nx]) {
         return Coord.of(nx, ny);
      } else {
         if (sx != 0) {
            nx = at.x + sx;
            ny = at.y;
            if (nx >= 0 && ny >= 0 && nx < w && ny < h && !blocked[ny * w + nx]) {
               return Coord.of(nx, ny);
            }
         }

         if (sy != 0) {
            nx = at.x;
            ny = at.y + sy;
            if (nx >= 0 && ny >= 0 && nx < w && ny < h && !blocked[ny * w + nx]) {
               return Coord.of(nx, ny);
            }
         }

         return at;
      }
   }
   public static void inflateMasked(NavGrid grid, boolean[] mask, List<Coord2d[]> body) {
      if (grid != null && mask != null && body != null && !body.isEmpty()) {
         int w = grid.w;
         int h = grid.h;

         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               if (y * w + x < mask.length && mask[y * w + x]) {
                  Coord2d[] sq = new Coord2d[]{
                     grid.origin.add((double)x * 2.75, (double)y * 2.75),
                     grid.origin.add((double)(x + 1) * 2.75, (double)y * 2.75),
                     grid.origin.add((double)(x + 1) * 2.75, (double)(y + 1) * 2.75),
                     grid.origin.add((double)x * 2.75, (double)(y + 1) * 2.75)
                  };
                  rasterPolygon(grid, sq, body);
               }
            }
         }
      }
   }

   public static void rasterAabb(NavGrid grid, List<Coord2d[]> polygons, List<Coord2d[]> body) {
      double[] box = aabb(polygons);
      if (box != null) {
         Coord2d[] rect = new Coord2d[]{Coord2d.of(box[0], box[1]), Coord2d.of(box[2], box[1]), Coord2d.of(box[2], box[3]), Coord2d.of(box[0], box[3])};
         rasterAabb(grid, polygons, OVERLAP);
         rasterPolygon(grid, rect, body);
      }
   }

   public static boolean isHollowRing(List<Coord2d[]> polygons) {
      if (polygons != null && polygons.size() >= 2) {
         double[] union = aabb(polygons);
         if (union == null) {
            return false;
         } else {
            double unionArea = (union[2] - union[0]) * (union[3] - union[1]);
            double largest = 0.0;

            for (Coord2d[] polygon : polygons) {
               double[] box = aabb(Collections.singletonList(polygon));
               if (box != null) {
                  largest = Math.max(largest, (box[2] - box[0]) * (box[3] - box[1]));
               }
            }

            return unionArea > 4.0 * Math.max(largest, 1.0);
         }
      } else {
         return false;
      }
   }

   public static double[] aabb(List<Coord2d[]> polygons) {
      double minx = Double.POSITIVE_INFINITY;
      double miny = Double.POSITIVE_INFINITY;
      double maxx = Double.NEGATIVE_INFINITY;
      double maxy = Double.NEGATIVE_INFINITY;
      boolean any = false;
      if (polygons == null) {
         return null;
      } else {
         for (Coord2d[] polygon : polygons) {
            if (polygon != null) {
               for (Coord2d p : polygon) {
                  if (p != null) {
                     any = true;
                     minx = Math.min(minx, p.x);
                     miny = Math.min(miny, p.y);
                     maxx = Math.max(maxx, p.x);
                     maxy = Math.max(maxy, p.y);
                  }
               }
            }
         }

         return any ? new double[]{minx, miny, maxx, maxy} : null;
      }
   }

   public static Coord2d[] aabbPolygon(List<Coord2d[]> polygons) {
      return aabbPolygon(polygons, 0.0);
   }

   public static Coord2d[] aabbPolygon(List<Coord2d[]> polygons, double pad) {
      double[] box = aabb(polygons);
      return box == null
         ? new Coord2d[0]
         : new Coord2d[]{
            Coord2d.of(box[0] - pad, box[1] - pad),
            Coord2d.of(box[2] + pad, box[1] - pad),
            Coord2d.of(box[2] + pad, box[3] + pad),
            Coord2d.of(box[0] - pad, box[3] + pad)
         };
   }

   public static void rasterAabb(NavGrid grid, List<Coord2d[]> polygons) {
      rasterAabb(grid, polygons, OVERLAP);
   }

   public static void rasterAabb(NavGrid grid, List<Coord2d[]> polygons, double pad) {
      double[] box = aabb(polygons);
      if (box != null) {
         Coord lo = grid.cell(Coord2d.of(box[0] - pad, box[1] - pad));
         Coord hi = grid.cell(Coord2d.of(box[2] + pad, box[3] + pad));

         for (int y = Math.max(0, lo.y); y <= Math.min(grid.h - 1, hi.y); y++) {
            for (int x = Math.max(0, lo.x); x <= Math.min(grid.w - 1, hi.x); x++) {
               grid.block(x, y);
            }
         }
      }
   }

   public static void rasterDisk(NavGrid grid, Coord2d center, double radius) {
      if (center != null && !(radius <= 0.0)) {
         Coord lo = grid.cell(Coord2d.of(center.x - radius, center.y - radius));
         Coord hi = grid.cell(Coord2d.of(center.x + radius, center.y + radius));
         double r2 = radius * radius;

         for (int y = Math.max(0, lo.y); y <= Math.min(grid.h - 1, hi.y); y++) {
            for (int x = Math.max(0, lo.x); x <= Math.min(grid.w - 1, hi.x); x++) {
               Coord2d at = grid.world(Coord.of(x, y));
               if ((at.x - center.x) * (at.x - center.x) + (at.y - center.y) * (at.y - center.y) <= r2) {
                  grid.block(x, y);
               }
            }
         }
      }
   }

   public static double boundingRadius(Coord2d origin, List<Coord2d[]> polygons) {
      double best = 0.0;
      if (origin != null && polygons != null) {
         for (Coord2d[] polygon : polygons) {
            if (polygon != null) {
               for (Coord2d p : polygon) {
                  if (p != null) {
                     best = Math.max(best, p.dist(origin));
                  }
               }
            }
         }

         return best;
      } else {
         return 0.0;
      }
   }

   public static Coord worldCell(Coord2d origin, Coord2d p) {
      return origin != null && p != null ? Coord.of((int)Math.floor((p.x - origin.x) / 2.75), (int)Math.floor((p.y - origin.y) / 2.75)) : Coord.z;
   }

   public static int dilationCells(double agentRadius) {
      return Math.max(1, (int)Math.ceil(Math.max(agentRadius, 0.0) / 2.75));
   }

   public static void applyClearanceCost(double[] cost, boolean[] solid, int w, int h) {
      if (cost != null && solid != null && w > 0 && h > 0) {
         int n = w * h;
         Arrays.fill(cost, 1.0);
         int[] dist = new int[n];
         Arrays.fill(dist, 536870911);
         ArrayDeque<Integer> q = new ArrayDeque<>();

         for (int i = 0; i < n && i < solid.length; i++) {
            if (solid[i]) {
               dist[i] = 0;
               q.add(i);
            }
         }

         int[] dx = new int[]{1, 1, 0, -1, -1, -1, 0, 1};
         int[] dy = new int[]{0, 1, 1, 1, 0, -1, -1, -1};

         while (!q.isEmpty()) {
            int cur = q.poll();
            int x = cur % w;
            int y = cur / w;
            int nd = dist[cur] + 1;

            for (int d = 0; d < dx.length; d++) {
               int nx = x + dx[d];
               int ny = y + dy[d];
               if (nx >= 0 && ny >= 0 && nx < w && ny < h) {
                  int ni = ny * w + nx;
                  if (nd < dist[ni]) {
                     dist[ni] = nd;
                     q.add(ni);
                  }
               }
            }
         }

         int lim = Math.min(n, Math.min(cost.length, dist.length));

         for (int ix = 0; ix < lim; ix++) {
            if (ix >= solid.length || !solid[ix]) {
               if (dist[ix] <= 1) {
                  cost[ix] = 4.5;
               } else if (dist[ix] == 2) {
                  cost[ix] = 1.8;
               }
            }
         }
      }
   }

   public static Coord2d approach(Coord2d from, Coord2d dest, double dist) {
      if (dest == null) {
         return from;
      } else if (from == null) {
         return dest;
      } else {
         double d = from.dist(dest);
         return !(d < 1.0E-6) && !(d <= dist) ? dest.add(from.sub(dest).norm().mul(dist)) : from;
      }
   }

   public static void dilate(boolean[] blocked, int w, int h, int radiusCells) {
      if (blocked != null && radiusCells > 0 && w > 0 && h > 0) {
         boolean[] src = Arrays.copyOf(blocked, blocked.length);

         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               if (src[y * w + x]) {
                  for (int dy = -radiusCells; dy <= radiusCells; dy++) {
                     for (int dx = -radiusCells; dx <= radiusCells; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) <= radiusCells) {
                           int nx = x + dx;
                           int ny = y + dy;
                           if (nx >= 0 && ny >= 0 && nx < w && ny < h) {
                              blocked[ny * w + nx] = true;
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public static void rasterPolygon(boolean[] blocked, Coord2d origin, int w, int h, Coord2d[] polygon, double pad) {
      if (blocked != null && origin != null && w > 0 && h > 0) {
         NavGrid grid = new NavGrid(origin, w, h);
         int n = Math.min(blocked.length, grid.blocked.length);
         System.arraycopy(blocked, 0, grid.blocked, 0, n);
         rasterPolygon(grid, polygon, pad);
         System.arraycopy(grid.blocked, 0, blocked, 0, n);
      }
   }

   public static void rasterPolygon(boolean[] blocked, Coord2d origin, int w, int h, Coord2d[] polygon, List<Coord2d[]> body) {
      if (blocked != null && origin != null && w > 0 && h > 0) {
         NavGrid grid = new NavGrid(origin, w, h);
         int n = Math.min(blocked.length, grid.blocked.length);
         System.arraycopy(blocked, 0, grid.blocked, 0, n);
         rasterPolygon(grid, polygon, body);
         System.arraycopy(grid.blocked, 0, blocked, 0, n);
      }
   }

   public static void rasterPolygon(NavGrid grid, Coord2d[] polygon, double pad) {
      if (polygon != null && polygon.length >= 2) {
         double minx = Double.POSITIVE_INFINITY;
         double miny = Double.POSITIVE_INFINITY;
         double maxx = Double.NEGATIVE_INFINITY;
         double maxy = Double.NEGATIVE_INFINITY;

         for (Coord2d p : polygon) {
            minx = Math.min(minx, p.x);
            miny = Math.min(miny, p.y);
            maxx = Math.max(maxx, p.x);
            maxy = Math.max(maxy, p.y);
         }

         Coord lo = grid.cell(Coord2d.of(minx - pad, miny - pad));
         Coord hi = grid.cell(Coord2d.of(maxx + pad, maxy + pad));

         for (int y = Math.max(0, lo.y); y <= Math.min(grid.h - 1, hi.y); y++) {
            for (int x = Math.max(0, lo.x); x <= Math.min(grid.w - 1, hi.x); x++) {
               Coord2d p = grid.world(Coord.of(x, y));
               if (pointInside(p, polygon) || edgeDistance(p, polygon) <= pad) {
                  grid.block(x, y);
               }
            }
         }
      }
   }

   public static void rasterPolygon(NavGrid grid, Coord2d[] polygon, List<Coord2d[]> body) {
      if (polygon != null && polygon.length >= 2 && body != null && !body.isEmpty()) {
         double hx = 0.0;
         double hy = 0.0;

         for (Coord2d[] b : body) {
            if (b != null) {
               for (Coord2d p : b) {
                  if (p != null) {
                     hx = Math.max(hx, Math.abs(p.x));
                     hy = Math.max(hy, Math.abs(p.y));
                  }
               }
            }
         }

         double minx = Double.POSITIVE_INFINITY;
         double miny = Double.POSITIVE_INFINITY;
         double maxx = Double.NEGATIVE_INFINITY;
         double maxy = Double.NEGATIVE_INFINITY;

         for (Coord2d px : polygon) {
            minx = Math.min(minx, px.x);
            miny = Math.min(miny, px.y);
            maxx = Math.max(maxx, px.x);
            maxy = Math.max(maxy, px.y);
         }

         Coord lo = grid.cell(Coord2d.of(minx - hx, miny - hy));
         Coord hi = grid.cell(Coord2d.of(maxx + hx, maxy + hy));

         for (int y = Math.max(0, lo.y); y <= Math.min(grid.h - 1, hi.y); y++) {
            for (int x = Math.max(0, lo.x); x <= Math.min(grid.w - 1, hi.x); x++) {
               if (bodyHits(grid.world(Coord.of(x, y)), body, polygon)) {
                  grid.block(x, y);
               }
            }
         }
      }
   }
   public static boolean polygonEquals(Coord2d[] a, Coord2d[] b) {
      if (a == b) {
         return true;
      }
      if (a == null || b == null || a.length != b.length) {
         return false;
      }
      for (int i = 0; i < a.length; i++) {
         Coord2d p = a[i];
         Coord2d q = b[i];
         if (p == q) {
            continue;
         }
         if (p == null || q == null) {
            return false;
         }
         if (p.x != q.x || p.y != q.y) {
            return false;
         }
      }
      return true;
   }

   public static boolean listed(Coord2d[] poly, List<Coord2d[]> list) {
      if (poly == null || list == null) {
         return false;
      }
      for (int i = 0; i < list.size(); i++) {
         if (polygonEquals(poly, list.get(i))) {
            return true;
         }
      }
      return false;
   }

   public static final class PolyBounds {
      public final List<Coord2d[]> polys;
      public final double[] minx;
      public final double[] miny;
      public final double[] maxx;
      public final double[] maxy;

      PolyBounds(List<Coord2d[]> polys, double[] minx, double[] miny, double[] maxx, double[] maxy) {
         this.polys = polys;
         this.minx = minx;
         this.miny = miny;
         this.maxx = maxx;
         this.maxy = maxy;
      }

      public static PolyBounds of(List<Coord2d[]> solids) {
         List<Coord2d[]> list = solids == null ? Collections.<Coord2d[]>emptyList() : solids;
         int n = list.size();
         double[] minx = new double[n];
         double[] miny = new double[n];
         double[] maxx = new double[n];
         double[] maxy = new double[n];
         for (int i = 0; i < n; i++) {
            Coord2d[] poly = list.get(i);
            double x0 = Double.POSITIVE_INFINITY;
            double y0 = Double.POSITIVE_INFINITY;
            double x1 = Double.NEGATIVE_INFINITY;
            double y1 = Double.NEGATIVE_INFINITY;
            if (poly != null) {
               for (int k = 0; k < poly.length; k++) {
                  if (poly[k] == null) {
                     continue;
                  }
                  x0 = Math.min(x0, poly[k].x);
                  y0 = Math.min(y0, poly[k].y);
                  x1 = Math.max(x1, poly[k].x);
                  y1 = Math.max(y1, poly[k].y);
               }
            }
            minx[i] = x0;
            miny[i] = y0;
            maxx[i] = x1;
            maxy[i] = y1;
         }
         return new PolyBounds(list, minx, miny, maxx, maxy);
      }

      boolean nearPoint(int i, Coord2d at, double pad) {
         if (at == null || i < 0 || i >= this.minx.length) {
            return false;
         }
         return at.x + pad >= this.minx[i] && at.x - pad <= this.maxx[i]
            && at.y + pad >= this.miny[i] && at.y - pad <= this.maxy[i];
      }

      boolean nearSegment(int i, Coord2d a, Coord2d b, double pad) {
         if (a == null || b == null || i < 0 || i >= this.minx.length) {
            return false;
         }
         double x0 = Math.min(a.x, b.x) - pad;
         double y0 = Math.min(a.y, b.y) - pad;
         double x1 = Math.max(a.x, b.x) + pad;
         double y1 = Math.max(a.y, b.y) + pad;
         return x1 >= this.minx[i] && x0 <= this.maxx[i] && y1 >= this.miny[i] && y0 <= this.maxy[i];
      }
   }

   public static boolean bodyHitsAny(Coord2d at, List<Coord2d[]> body, List<Coord2d[]> solids, List<Coord2d[]> ignore) {
      return bodyHitsAny(at, body, solids, ignore, null);
   }

   public static boolean bodyHitsAny(
      Coord2d at, List<Coord2d[]> body, List<Coord2d[]> solids, List<Coord2d[]> ignore, PolyBounds bounds
   ) {
      if (at == null || solids == null) {
         return false;
      }
      boolean hasBody = body != null && !body.isEmpty();
      double pad = hasBody ? bodyExtent(body) : 0.05;
      for (int i = 0; i < solids.size(); i++) {
         Coord2d[] poly = solids.get(i);
         if (poly == null || poly.length < 2) {
            continue;
         }
         if (bounds != null) {
            if (!bounds.nearPoint(i, at, pad)) {
               continue;
            }
         } else if (!pointNearPoly(at, poly, pad)) {
            continue;
         }
         if (listed(poly, ignore)) {
            continue;
         }
         if (hasBody) {
            if (bodyHits(at, body, poly)) {
               return true;
            }
         } else if (pointInside(at, poly) || edgeDistance(at, poly) <= 0.05) {
            return true;
         }
      }
      return false;
   }

   /**
    * Centerline plus sampled footprints. {@code ignore} is identity/vertex equal
    * (the selected target), never a geometric overlap test.
    */
   public static boolean sweptClear(Coord2d a, Coord2d b, List<Coord2d[]> body, List<Coord2d[]> solids, List<Coord2d[]> ignore) {
      return sweptClear(a, b, body, solids, ignore, null);
   }

   public static boolean sweptClear(
      Coord2d a, Coord2d b, List<Coord2d[]> body, List<Coord2d[]> solids, List<Coord2d[]> ignore, PolyBounds bounds
   ) {
      if (a == null || b == null) {
         return false;
      }
      if (solids == null || solids.isEmpty()) {
         return true;
      }
      double pad = bodyExtent(body);
      boolean hasBody = body != null && !body.isEmpty();
      for (int i = 0; i < solids.size(); i++) {
         Coord2d[] poly = solids.get(i);
         if (poly == null || poly.length < 2 || listed(poly, ignore)) {
            continue;
         }
         if (bounds != null) {
            if (!bounds.nearSegment(i, a, b, pad)) {
               continue;
            }
         } else if (!segmentNearPoly(a, b, poly, pad)) {
            continue;
         }
         if (hasBody ? sweptBodyHits(a, b, body, poly) : segmentHitsPolygon(a, b, poly, 0.0)) {
            return false;
         }
      }
      return true;
   }

   /** Exact translational sweep for the player's polygon. The swept area is
    * the union of both endpoint bodies and one quadrilateral per body edge. */
   public static boolean sweptBodyHits(Coord2d a, Coord2d b, List<Coord2d[]> body, Coord2d[] obstacle) {
      if (a == null || b == null || body == null || obstacle == null || obstacle.length < 2) {
         return false;
      }
      for (int p = 0; p < body.size(); p++) {
         Coord2d[] local = body.get(p);
         if (local == null || local.length < 2) {
            continue;
         }
         Coord2d[] atA = new Coord2d[local.length];
         Coord2d[] atB = new Coord2d[local.length];
         for (int i = 0; i < local.length; i++) {
            Coord2d point = local[i] == null ? Coord2d.of(0.0, 0.0) : local[i];
            atA[i] = a.add(point);
            atB[i] = b.add(point);
         }
         if (polygonsOverlap(atA, obstacle) || polygonsOverlap(atB, obstacle)) {
            return true;
         }
         for (int i = 0; i < local.length; i++) {
            int j = (i + 1) % local.length;
            Coord2d[] sweptEdge = new Coord2d[]{atA[i], atA[j], atB[j], atB[i]};
            if (polygonsOverlap(sweptEdge, obstacle)) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * Exact swept check for leaving a conservative collision boundary. The
    * server may have placed the player a fraction inside our body-expanded
    * model after interacting with a neighboring object. Only obstacles that
    * overlap the initial body are forgiven, and only for the short initial
    * portion of this one segment; its endpoint and remainder stay exact.
    */
   public static boolean sweptClearLeaving(
      Coord2d a, Coord2d b, List<Coord2d[]> body, List<Coord2d[]> solids, List<Coord2d[]> ignore, PolyBounds bounds
   ) {
      if (a == null || b == null) {
         return false;
      }
      if (solids == null || solids.isEmpty()) {
         return true;
      }
      if (bodyHitsAny(b, body, solids, ignore, bounds)) {
         return false;
      }
      double skip = bodyExtent(body) + 1.0;
      for (int s = 1; s < 8; s++) {
         double t = s / 8.0;
         Coord2d p = Coord2d.of(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
         if (p.dist(a) <= skip) {
            continue;
         }
         if (bodyHitsAny(p, body, solids, ignore, bounds)) {
            return false;
         }
      }
      double pad = bodyExtent(body);
      for (int i = 0; i < solids.size(); i++) {
         Coord2d[] poly = solids.get(i);
         if (poly == null || poly.length < 2 || listed(poly, ignore)) {
            continue;
         }
         if (bodyHits(a, body, poly)) {
            continue;
         }
         if (bounds != null) {
            if (!bounds.nearSegment(i, a, b, pad)) {
               continue;
            }
         } else if (!segmentNearPoly(a, b, poly, pad)) {
            continue;
         }
         if (segmentHitsPolygon(a, b, poly, 0.0)) {
            return false;
         }
      }
      return true;
   }

   static double bodyExtent(List<Coord2d[]> body) {
      double e = 0.5;
      if (body == null) {
         return e;
      }
      for (int i = 0; i < body.size(); i++) {
         Coord2d[] poly = body.get(i);
         if (poly == null) {
            continue;
         }
         for (int k = 0; k < poly.length; k++) {
            if (poly[k] != null) {
               e = Math.max(e, Math.max(Math.abs(poly[k].x), Math.abs(poly[k].y)));
            }
         }
      }
      return e;
   }

   static boolean pointNearPoly(Coord2d at, Coord2d[] poly, double pad) {
      if (at == null || poly == null || poly.length < 2) {
         return false;
      }
      double minx = Double.POSITIVE_INFINITY;
      double miny = Double.POSITIVE_INFINITY;
      double maxx = Double.NEGATIVE_INFINITY;
      double maxy = Double.NEGATIVE_INFINITY;
      for (int i = 0; i < poly.length; i++) {
         if (poly[i] == null) {
            continue;
         }
         minx = Math.min(minx, poly[i].x);
         miny = Math.min(miny, poly[i].y);
         maxx = Math.max(maxx, poly[i].x);
         maxy = Math.max(maxy, poly[i].y);
      }
      return at.x + pad >= minx && at.x - pad <= maxx && at.y + pad >= miny && at.y - pad <= maxy;
   }

   static boolean segmentNearPoly(Coord2d a, Coord2d b, Coord2d[] poly, double pad) {
      if (a == null || b == null || poly == null || poly.length < 2) {
         return false;
      }
      double minx = Math.min(a.x, b.x) - pad;
      double miny = Math.min(a.y, b.y) - pad;
      double maxx = Math.max(a.x, b.x) + pad;
      double maxy = Math.max(a.y, b.y) + pad;
      double px0 = Double.POSITIVE_INFINITY;
      double py0 = Double.POSITIVE_INFINITY;
      double px1 = Double.NEGATIVE_INFINITY;
      double py1 = Double.NEGATIVE_INFINITY;
      for (int i = 0; i < poly.length; i++) {
         if (poly[i] == null) {
            continue;
         }
         px0 = Math.min(px0, poly[i].x);
         py0 = Math.min(py0, poly[i].y);
         px1 = Math.max(px1, poly[i].x);
         py1 = Math.max(py1, poly[i].y);
      }
      return maxx >= px0 && minx <= px1 && maxy >= py0 && miny <= py1;
   }

   public static boolean bodyHits(Coord2d at, List<Coord2d[]> body, Coord2d[] obstacle) {
      if (at != null && body != null && obstacle != null && obstacle.length >= 2) {
         for (Coord2d[] b : body) {
            if (b != null && b.length >= 2) {
               Coord2d[] placed = new Coord2d[b.length];

               for (int i = 0; i < b.length; i++) {
                  placed[i] = at.add(b[i] == null ? Coord2d.of(0.0, 0.0) : b[i]);
               }

               if (polygonsOverlap(placed, obstacle)) {
                  return true;
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static boolean polygonsOverlap(Coord2d[] a, Coord2d[] b) {
      if (a != null && b != null && a.length >= 2 && b.length >= 2) {
         for (Coord2d p : a) {
            if (p != null && (pointInside(p, b) || edgeDistance(p, b) <= 0.05)) {
               return true;
            }
         }

         for (Coord2d px : b) {
            if (px != null && (pointInside(px, a) || edgeDistance(px, a) <= 0.05)) {
               return true;
            }
         }

         for (int i = 0; i < a.length; i++) {
            Coord2d a0 = a[i];
            Coord2d a1 = a[(i + 1) % a.length];

            for (int j = 0; j < b.length; j++) {
               if (segmentsCross(a0, a1, b[j], b[(j + 1) % b.length])) {
                  return true;
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static boolean pointInside(Coord2d p, Coord2d[] poly) {
      boolean in = false;
      int i = 0;

      for (int j = poly.length - 1; i < poly.length; j = i++) {
         Coord2d a = poly[i];
         Coord2d b = poly[j];
         if (a.y > p.y != b.y > p.y && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) {
            in = !in;
         }
      }

      return in;
   }

   public static double edgeDistance(Coord2d p, Coord2d[] poly) {
      double best = Double.POSITIVE_INFINITY;

      for (int i = 0; i < poly.length; i++) {
         Coord2d a = poly[i];
         Coord2d b = poly[(i + 1) % poly.length];
         Coord2d ab = b.sub(a);
         Coord2d ap = p.sub(a);
         double d2 = ab.x * ab.x + ab.y * ab.y;
         double t = d2 == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, (ap.x * ab.x + ap.y * ab.y) / d2));
         best = Math.min(best, p.dist(a.add(ab.mul(t))));
      }

      return best;
   }

   public static List<Coord2d> smooth(List<Coord2d> raw, NavGrid grid, boolean[] los) {
      if (raw.size() < 3) {
         return raw;
      } else {
         List<Coord2d> ret = new ArrayList<>();
         int at = 0;
         ret.add(raw.get(0));

         while (at < raw.size() - 1) {
            int next = raw.size() - 1;

            while (next > at + 1 && !canSmooth(raw.get(at), raw.get(next), grid, los, at == 0)) {
               next--;
            }

            ret.add(raw.get(next));
            at = next;
         }

         return ret;
      }
   }

   public static boolean canSmooth(Coord2d a, Coord2d b, NavGrid grid, boolean[] los) {
      return canSmooth(a, b, grid, los, false);
   }

   private static boolean canSmooth(Coord2d a, Coord2d b, NavGrid grid, boolean[] los, boolean leaving) {
      if (grid.exactCollisionEnabled()) {
         return grid.segmentClear(a, b, leaving);
      }
      if (!clear(a, b, grid, los)) {
         return false;
      } else {
         Coord ca = grid.cell(a);
         Coord cb = grid.cell(b);
         return !besideLos(grid, los, ca.x, ca.y) && !besideLos(grid, los, cb.x, cb.y) ? true : ca.x == cb.x || ca.y == cb.y;
      }
   }

   public static boolean besideLos(NavGrid grid, boolean[] blocked, int x, int y) {
      if (blocked == null) {
         return false;
      } else {
         for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
               if (dx != 0 || dy != 0) {
                  int nx = x + dx;
                  int ny = y + dy;
                  if (nx >= 0 && ny >= 0 && nx < grid.w && ny < grid.h && blocked[ny * grid.w + nx]) {
                     return true;
                  }
               }
            }
         }

         return false;
      }
   }

   public static boolean segmentHitsPolygon(Coord2d a, Coord2d b, Coord2d[] poly, double pad) {
      if (a == null || b == null || poly == null || poly.length < 2) {
         return false;
      } else if (!pointInside(a, poly) && !pointInside(b, poly)) {
         if (!(edgeDistance(a, poly) <= pad) && !(edgeDistance(b, poly) <= pad)) {
            for (int i = 0; i < poly.length; i++) {
               Coord2d c = poly[i];
               Coord2d d = poly[(i + 1) % poly.length];
               if (segmentsCross(a, b, c, d)) {
                  return true;
               }
            }

            return false;
         } else {
            return true;
         }
      } else {
         return true;
      }
   }

   public static boolean segmentsCross(Coord2d a, Coord2d b, Coord2d c, Coord2d d) {
      double abx = b.x - a.x;
      double aby = b.y - a.y;
      double acx = c.x - a.x;
      double acy = c.y - a.y;
      double adx = d.x - a.x;
      double ady = d.y - a.y;
      double cdx = d.x - c.x;
      double cdy = d.y - c.y;
      double cax = a.x - c.x;
      double cay = a.y - c.y;
      double cbx = b.x - c.x;
      double cby = b.y - c.y;
      double abac = abx * acy - aby * acx;
      double abad = abx * ady - aby * adx;
      double cdca = cdx * cay - cdy * cax;
      double cdcb = cdx * cby - cdy * cbx;
      return abac > 0.0 != abad > 0.0 && cdca > 0.0 != cdcb > 0.0;
   }

   public static boolean segmentHitsSquare(Coord2d a, Coord2d b, double minx, double miny, double maxx, double maxy) {
      if (a != null && b != null) {
         double x0 = a.x;
         double y0 = a.y;
         double x1 = b.x;
         double y1 = b.y;
         double dx = x1 - x0;
         double dy = y1 - y0;
         double t0 = 0.0;
         double t1 = 1.0;
         double[] p = new double[]{-dx, dx, -dy, dy};
         double[] q = new double[]{x0 - minx, maxx - x0, y0 - miny, maxy - y0};

         for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0) {
               if (q[i] < 0.0) {
                  return false;
               }
            } else {
               double r = q[i] / p[i];
               if (p[i] < 0.0) {
                  if (r > t1) {
                     return false;
                  }

                  if (r > t0) {
                     t0 = r;
                  }
               } else {
                  if (r < t0) {
                     return false;
                  }

                  if (r < t1) {
                     t1 = r;
                  }
               }
            }
         }

         return t0 <= t1;
      } else {
         return false;
      }
   }

   public static boolean lineOfSightClear(Coord2d origin, int w, int h, boolean[] blocked, Coord2d a, Coord2d b) {
      return clear(a, b, new NavGrid(origin, w, h), blocked);
   }

   public static boolean clear(Coord2d a, Coord2d b, NavGrid grid, boolean[] blocked) {
      Coord ca = grid.cell(a);
      Coord cb = grid.cell(b);
      int minx = Math.min(ca.x, cb.x);
      int maxx = Math.max(ca.x, cb.x);
      int miny = Math.min(ca.y, cb.y);
      int maxy = Math.max(ca.y, cb.y);
      minx = Math.max(0, minx);
      miny = Math.max(0, miny);
      maxx = Math.min(grid.w - 1, maxx);
      maxy = Math.min(grid.h - 1, maxy);

      for (int y = miny; y <= maxy; y++) {
         for (int x = minx; x <= maxx; x++) {
            if (blocked[y * grid.w + x]) {
               double x0 = grid.origin.x + (double)x * 2.75;
               double y0 = grid.origin.y + (double)y * 2.75;
               if (segmentHitsSquare(a, b, x0, y0, x0 + 2.75, y0 + 2.75)) {
                  return false;
               }
            }
         }
      }

      return true;
   }

   public static class ClipResult {
      public final java.util.List<haven.Coord2d> targets = new java.util.ArrayList<haven.Coord2d>();
      public final java.util.List<Boolean> clipped = new java.util.ArrayList<Boolean>();
   }

}
