package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.NavPlan;
import haven.nav.NavPlanStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Renderer-independent continuous-surface helpers: farthest legal waypoint,
 * corridor validation, failed-segment blacklist, and escape-cell selection.
 * Does not issue movement commands.
 */
public final class SurfaceStream {
   public static final int MAX_RECOVERY = 2;
   public static final double CLICK_DEDUP = 1.0;
   public static final double DEVIATION = 16.5;
   /** Max occupancy-blocked last click onto an interaction pose. Not the 35u interact radius. */
   public static final double LAST_HOP = LocalPlanner.CELL * 3.0;

   public enum Reason {
      STOPPED_EARLY,
      CORRIDOR_INVALID,
      OBSTACLE_CHANGED,
      MOBILITY_CHANGED,
      DEVIATED,
      RECOVERY,
      HORIZON_ADVANCE,
      START_TIMEOUT,
      NO_PROGRESS,
      WALK_TIMEOUT,
      HANDOFF,
      STREAM,
      ARRIVED,
      INTERACT,
      STUCK,
      CANCELLED;
   }

   private SurfaceStream() {
   }

   public static final class Farthest {
      public final int selectedIndex;
      public final Coord2d selected;
      public final int consideredIndex;
      public final Coord2d considered;
      public final boolean corridorValid;
      public final String whyShorter;

      public Farthest(int selectedIndex, Coord2d selected, int consideredIndex, Coord2d considered, boolean corridorValid, String whyShorter) {
         this.selectedIndex = selectedIndex;
         this.selected = selected;
         this.consideredIndex = consideredIndex;
         this.considered = considered;
         this.corridorValid = corridorValid;
         this.whyShorter = whyShorter;
      }
   }

   public static boolean wouldBeFalseSuccess(NavPlanStatus status, Coord2d pos, Coord2d originalGoal, double eps) {
      if (pos == null || originalGoal == null) {
         return true;
      }
      if (pos.dist(originalGoal) <= eps) {
         return false;
      }
      return status == NavPlanStatus.CLIPPED || status == NavPlanStatus.PARTIAL;
   }

   public static boolean arrived(Coord2d pos, Coord2d goal, double eps, boolean moving, boolean requireIdle) {
      if (pos == null || goal == null || pos.dist(goal) > eps) {
         return false;
      }
      return !requireIdle || !moving;
   }

   public static boolean sameCommand(Coord2d lastSent, Coord2d next) {
      if (next == null) {
         return true;
      }
      return lastSent != null && lastSent.dist(next) < CLICK_DEDUP;
   }

   public static boolean abaOscillation(Coord2d older, Coord2d newer, Coord2d candidate, double eps) {
      if (older == null || newer == null || candidate == null) {
         return false;
      }
      return older.dist(candidate) <= eps && newer.dist(candidate) > eps;
   }

   public static Farthest farthestValid(Coord2d from, List<Coord2d> smoothed, OccupancyGrid occ) {
      return farthestValid(from, smoothed, occ, null, null, null);
   }

   public static Farthest farthestValid(
      Coord2d from,
      List<Coord2d> smoothed,
      OccupancyGrid occ,
      List<Coord2d[]> solids,
      List<Coord2d[]> playerBody,
      haven.nav.InteractionSpec ignoreTarget
   ) {
      return farthestValid(from, smoothed, occ, solids, playerBody, ignoreTarget, null);
   }

   public static Farthest farthestValid(
      Coord2d from,
      List<Coord2d> smoothed,
      OccupancyGrid occ,
      List<Coord2d[]> solids,
      List<Coord2d[]> playerBody,
      haven.nav.InteractionSpec ignoreTarget,
      LocalPlanner.PolyBounds solidBounds
   ) {
      if (smoothed == null || smoothed.isEmpty()) {
         return new Farthest(-1, null, -1, null, false, "empty_route");
      }
      int last = smoothed.size() - 1;
      Coord2d considered = smoothed.get(last);
      if (occ == null) {
         return new Farthest(last, considered, last, considered, true, null);
      }
      NavGrid grid = occ == null ? null : gridOf(occ);
      boolean[] blocked = occ == null ? null : blockedOf(occ);
      int selected = -1;
      String why = null;
      for (int i = last; i >= 0; i--) {
         Coord2d wp = smoothed.get(i);
         if (wp == null) {
            continue;
         }
         if (from != null && from.dist(wp) <= 1.0E-6 && i != last) {
            continue;
         }
         boolean clear = corridorClear(from, wp, grid, blocked);
         boolean lastHopFallback = false;
         if (!clear && i == last && lastHopClear(from, wp, ignoreTarget, solids, playerBody, solidBounds)) {
            clear = true;
            lastHopFallback = true;
         }
         if (clear && !lastHopFallback
            && solids != null && !solids.isEmpty()
            && playerBody != null && !playerBody.isEmpty()
            && hasSolidNearSegment(from, wp, occ, LocalPlanner.bodyExtent(playerBody))) {
            List<Coord2d[]> ignore = (ignoreTarget != null) ? ignoreTarget.polygons : null;
            if (!LocalPlanner.sweptClear(from, wp, playerBody, solids, ignore, solidBounds)) {
               clear = false;
            }
         }
         if (clear) {
            selected = i;
            if (i != last) {
               why = "corridor_blocked_beyond";
            }
            break;
         }
      }
      if (selected < 0) {
         return new Farthest(-1, null, last, considered, false, "no_legal_corridor");
      }
      return new Farthest(selected, smoothed.get(selected), last, considered, true, why);
   }

   static boolean lastHopClear(Coord2d from, Coord2d pose, haven.nav.InteractionSpec spec) {
      return lastHopClear(from, pose, spec, null, null);
   }

   static boolean lastHopClear(
      Coord2d from,
      Coord2d pose,
      haven.nav.InteractionSpec spec,
      List<Coord2d[]> solids,
      List<Coord2d[]> playerBody
   ) {
      return lastHopClear(from, pose, spec, solids, playerBody, null);
   }

   static boolean lastHopClear(
      Coord2d from,
      Coord2d pose,
      haven.nav.InteractionSpec spec,
      List<Coord2d[]> solids,
      List<Coord2d[]> playerBody,
      LocalPlanner.PolyBounds solidBounds
   ) {
      if (spec == null || from == null || pose == null) {
         return false;
      }
      if (from.dist(pose) > LAST_HOP) {
         return false;
      }
      if (solids != null && !solids.isEmpty()) {
         return LocalPlanner.sweptClear(from, pose, playerBody, solids, spec.polygons, solidBounds);
      }
      return true;
   }

   public static boolean corridorClear(Coord2d a, Coord2d b, OccupancyGrid occ) {
      if (occ == null) {
         return true;
      }
      if (a == null || b == null) {
         return false;
      }
      return corridorClear(a, b, gridOf(occ), blockedOf(occ));
   }

   public static boolean corridorClear(Coord2d a, Coord2d b, NavGrid grid, boolean[] blocked) {
      if (grid == null || blocked == null) {
         return true;
      }
      return LocalPlanner.clear(a, b, grid, blocked);
   }

   static boolean hasSolidNearSegment(Coord2d a, Coord2d b, OccupancyGrid occ, double pad) {
      if (occ == null || occ.occ == null || a == null || b == null) {
         return false;
      }
      Coord ca = occ.cellOf(a);
      Coord cb = occ.cellOf(b);
      if (ca == null || cb == null) {
         return false;
      }
      int cellPad = Math.max(1, (int)Math.ceil(pad / occ.cell));
      int minx = Math.max(0, Math.min(ca.x, cb.x) - cellPad);
      int maxx = Math.min(occ.w - 1, Math.max(ca.x, cb.x) + cellPad);
      int miny = Math.max(0, Math.min(ca.y, cb.y) - cellPad);
      int maxy = Math.min(occ.h - 1, Math.max(ca.y, cb.y) + cellPad);
      for (int y = miny; y <= maxy; y++) {
         for (int x = minx; x <= maxx; x++) {
            if (occ.at(x, y) == OccupancyGrid.SOLID) {
               return true;
            }
         }
      }
      return false;
   }

   public static boolean corridorChanged(OccupancyGrid prev, OccupancyGrid next, Coord2d a, Coord2d b) {
      if (prev == null || next == null || a == null || b == null) {
         return false;
      }
      if (prev.w != next.w || prev.h != next.h || prev.occ == null || next.occ == null) {
         return true;
      }
      List<Integer> cells = supercover(prev, a, b);
      for (int i = 0; i < cells.size(); i++) {
         int idx = cells.get(i).intValue();
         if (idx < 0 || idx >= prev.occ.length || idx >= next.occ.length) {
            continue;
         }
         if (blockedByte(prev.occ[idx]) != blockedByte(next.occ[idx])) {
            return true;
         }
      }
      return false;
   }

   public static OccupancyGrid blacklistSegment(OccupancyGrid src, Coord2d a, Coord2d b) {
      if (src == null || src.occ == null || a == null || b == null) {
         return src;
      }
      byte[] occ = Arrays.copyOf(src.occ, src.occ.length);
      List<Integer> cells = supercover(src, a, b);
      for (int i = 0; i < cells.size(); i++) {
         int idx = cells.get(i).intValue();
         if (idx >= 0 && idx < occ.length) {
            occ[idx] = OccupancyGrid.SOLID;
         }
      }
      return new OccupancyGrid(src.origin, src.w, src.h, src.cell, occ, src.start, src.goal, src.freeGoal, src.astar);
   }

   public static Coord pickEscape(OccupancyGrid occ, Coord2d pos) {
      if (occ == null || pos == null) {
         return null;
      }
      Coord start = occ.cellOf(pos);
      if (start == null || start.x < 0 || start.y < 0 || start.x >= occ.w || start.y >= occ.h) {
         return null;
      }
      int[] clear = clearance(occ);
      int cur = clear[start.y * occ.w + start.x];
      boolean[] walkableBlocked = blockedOf(occ);
      if (walkableBlocked[start.y * occ.w + start.x]) {
         LocalPlanner.openFootprint(walkableBlocked, occ.w, occ.h, start.x, start.y, 1, solidOf(occ));
      }
      NavGrid grid = gridOf(occ);
      System.arraycopy(walkableBlocked, 0, grid.blocked, 0, walkableBlocked.length);
      Coord nearestSolid = nearestSolid(occ, start);
      Coord best = null;
      int bestDist = Integer.MAX_VALUE;
      int bestClear = cur;
      for (int y = 0; y < occ.h; y++) {
         for (int x = 0; x < occ.w; x++) {
            int i = y * occ.w + x;
            if (walkableBlocked[i] || clear[i] <= cur) {
               continue;
            }
            Coord c = Coord.of(x, y);
            if (nearestSolid != null && towardSolid(start, c, nearestSolid)) {
               continue;
            }
            GridAStar.Result route = GridAStar.find(grid, start, c, LocalPlanner.MAX_EXPANDED);
            if (!route.complete) {
               continue;
            }
            int d = Math.abs(x - start.x) + Math.abs(y - start.y);
            if (best == null || d < bestDist || d == bestDist && clear[i] > bestClear) {
               best = c;
               bestDist = d;
               bestClear = clear[i];
            }
         }
      }
      return best;
   }

   public static NavPlan replan(Coord2d from, Coord2d goal, OccupancyGrid occ, OccupancyGrid blacklisted) {
      OccupancyGrid use = blacklisted != null ? blacklisted : occ;
      return LocalPlanner.planFromOccupancy(from, goal, true, LocalPlanner.DEFAULT_AGENT_RADIUS, use, 0, new PlanningTrace());
   }

   public static NavGrid gridOf(OccupancyGrid occ) {
      NavGrid grid = new NavGrid(occ.origin, occ.w, occ.h);
      boolean[] blocked = blockedOf(occ);
      System.arraycopy(walkCopy(blocked), 0, grid.blocked, 0, blocked.length);
      return grid;
   }

   public static boolean[] blockedOf(OccupancyGrid occ) {
      boolean[] blocked = new boolean[occ.w * occ.h];
      int n = Math.min(blocked.length, occ.occ == null ? 0 : occ.occ.length);
      for (int i = 0; i < n; i++) {
         blocked[i] = blockedByte(occ.occ[i]);
      }
      return blocked;
   }

   static boolean[] solidOf(OccupancyGrid occ) {
      boolean[] solid = new boolean[occ.w * occ.h];
      int n = Math.min(solid.length, occ.occ == null ? 0 : occ.occ.length);
      for (int i = 0; i < n; i++) {
         solid[i] = occ.occ[i] == OccupancyGrid.SOLID;
      }
      return solid;
   }

   static boolean blockedByte(byte v) {
      return v == OccupancyGrid.SOLID || v == OccupancyGrid.DILATED;
   }

   static List<Integer> supercover(OccupancyGrid occ, Coord2d a, Coord2d b) {
      List<Integer> cells = new ArrayList<Integer>();
      if (occ == null || a == null || b == null) {
         return cells;
      }
      Coord ca = occ.cellOf(a);
      Coord cb = occ.cellOf(b);
      if (ca == null || cb == null) {
         return cells;
      }
      int minx = Math.max(0, Math.min(ca.x, cb.x));
      int maxx = Math.min(occ.w - 1, Math.max(ca.x, cb.x));
      int miny = Math.max(0, Math.min(ca.y, cb.y));
      int maxy = Math.min(occ.h - 1, Math.max(ca.y, cb.y));
      for (int y = miny; y <= maxy; y++) {
         for (int x = minx; x <= maxx; x++) {
            Coord2d c0 = occ.corner(x, y);
            if (LocalPlanner.segmentHitsSquare(a, b, c0.x, c0.y, c0.x + occ.cell, c0.y + occ.cell)) {
               cells.add(Integer.valueOf(y * occ.w + x));
            }
         }
      }
      return cells;
   }

   static int[] clearance(OccupancyGrid occ) {
      int n = occ.w * occ.h;
      int[] dist = new int[n];
      Arrays.fill(dist, 1073741823);
      ArrayDeque<Integer> q = new ArrayDeque<Integer>();
      int m = Math.min(n, occ.occ == null ? 0 : occ.occ.length);
      for (int i = 0; i < n; i++) {
         if (i < m && occ.occ[i] == OccupancyGrid.SOLID) {
            dist[i] = 0;
            q.add(Integer.valueOf(i));
         }
      }
      int[] dx = new int[]{1, 1, 0, -1, -1, -1, 0, 1};
      int[] dy = new int[]{0, 1, 1, 1, 0, -1, -1, -1};
      while (!q.isEmpty()) {
         int cur = q.poll().intValue();
         int x = cur % occ.w;
         int y = cur / occ.w;
         int nd = dist[cur] + 1;
         for (int d = 0; d < dx.length; d++) {
            int nx = x + dx[d];
            int ny = y + dy[d];
            if (nx >= 0 && ny >= 0 && nx < occ.w && ny < occ.h) {
               int ni = ny * occ.w + nx;
               if (nd < dist[ni]) {
                  dist[ni] = nd;
                  q.add(Integer.valueOf(ni));
               }
            }
         }
      }
      return dist;
   }

   static Coord nearestSolid(OccupancyGrid occ, Coord from) {
      Coord best = null;
      int bestD = Integer.MAX_VALUE;
      for (int y = 0; y < occ.h; y++) {
         for (int x = 0; x < occ.w; x++) {
            if (occ.at(x, y) == OccupancyGrid.SOLID) {
               int d = Math.abs(x - from.x) + Math.abs(y - from.y);
               if (d < bestD) {
                  bestD = d;
                  best = Coord.of(x, y);
               }
            }
         }
      }
      return best;
   }

   static boolean towardSolid(Coord from, Coord cand, Coord solid) {
      int dFrom = Math.abs(from.x - solid.x) + Math.abs(from.y - solid.y);
      int dCand = Math.abs(cand.x - solid.x) + Math.abs(cand.y - solid.y);
      return dCand < dFrom;
   }

   private static boolean[] walkCopy(boolean[] in) {
      return in == null ? new boolean[0] : Arrays.copyOf(in, in.length);
   }
}
