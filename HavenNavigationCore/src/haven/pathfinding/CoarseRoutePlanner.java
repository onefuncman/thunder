package haven.pathfinding;

import haven.Coord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

public final class CoarseRoutePlanner {
   public static final int DEFAULT_MAX_EXPANDED = 1000000;
   private static final int[] DX = new int[]{1, 1, 0, -1, -1, -1, 0, 1};
   private static final int[] DY = new int[]{0, 1, 1, 1, 0, -1, -1, -1};
   private static final double SQRT2 = Math.sqrt(2.0);

   private CoarseRoutePlanner() {
   }

   public static CoarseRoutePlanner.Route plan(CoarseTileSource src, Coord start, Coord goal) {
      return plan(src, start, goal, 1000000);
   }

   public static CoarseRoutePlanner.Route plan(CoarseTileSource src, Coord start, Coord goal, int maxExpanded) {
      if (src == null) {
         return new CoarseRoutePlanner.Route(CoarseRoutePlanner.Status.INVALID_START, null, Collections.emptyList(), 0, null, null);
      } else {
         int w = src.width();
         int h = src.height();
         if (!inside(start, w, h)) {
            return new CoarseRoutePlanner.Route(CoarseRoutePlanner.Status.INVALID_START, null, Collections.emptyList(), 0, start, null);
         } else if (!inside(goal, w, h)) {
            return new CoarseRoutePlanner.Route(CoarseRoutePlanner.Status.INVALID_GOAL, null, Collections.emptyList(), 0, start, goal);
         } else if (src.tile(start.x, start.y) != CoarseTileSource.Tile.FREE) {
            return new CoarseRoutePlanner.Route(CoarseRoutePlanner.Status.INVALID_START, null, Collections.emptyList(), 0, start, goal);
         } else if (src.tile(goal.x, goal.y) != CoarseTileSource.Tile.FREE) {
            return new CoarseRoutePlanner.Route(CoarseRoutePlanner.Status.INVALID_GOAL, null, Collections.emptyList(), 0, start, goal);
         } else if (start.equals(goal)) {
            return new CoarseRoutePlanner.Route(CoarseRoutePlanner.Status.REACHED, null, Collections.singletonList(new Coord(start)), 0, start, goal);
         } else if (maxExpanded <= 0) {
            return new CoarseRoutePlanner.Route(CoarseRoutePlanner.Status.EXHAUSTED, null, Collections.emptyList(), 0, start, goal);
         } else {
            int n = w * h;
            double[] g = new double[n];
            Arrays.fill(g, Double.POSITIVE_INFINITY);
            int[] parent = new int[n];
            Arrays.fill(parent, -1);
            boolean[] closed = new boolean[n];
            PriorityQueue<CoarseRoutePlanner.Open> open = new PriorityQueue<>();
            int sid = start.y * w + start.x;
            int gid = goal.y * w + goal.x;
            g[sid] = 0.0;
            open.add(new CoarseRoutePlanner.Open(sid, heuristic(start.x, start.y, goal)));
            int expanded = 0;

            while (!open.isEmpty() && expanded < maxExpanded) {
               CoarseRoutePlanner.Open item = open.poll();
               int cur = item.id;
               if (!closed[cur]) {
                  closed[cur] = true;
                  expanded++;
                  if (cur == gid) {
                     List<Coord> path = collect(parent, cur, w);
                     return new CoarseRoutePlanner.Route(CoarseRoutePlanner.Status.REACHED, null, simplify(src, path, w, h), expanded, start, goal);
                  }

                  int cx = cur % w;
                  int cy = cur / w;

                  for (int d = 0; d < DX.length; d++) {
                     int nx = cx + DX[d];
                     int ny = cy + DY[d];
                     if (nx >= 0
                        && ny >= 0
                        && nx < w
                        && ny < h
                        && src.tile(nx, ny) == CoarseTileSource.Tile.FREE
                        && (
                           DX[d] == 0
                              || DY[d] == 0
                              || src.tile(cx + DX[d], cy) == CoarseTileSource.Tile.FREE && src.tile(cx, cy + DY[d]) == CoarseTileSource.Tile.FREE
                        )) {
                        int next = ny * w + nx;
                        if (!closed[next]) {
                           double step = DX[d] != 0 && DY[d] != 0 ? SQRT2 : 1.0;
                           double nd = g[cur] + step;
                           if (nd < g[next]) {
                              g[next] = nd;
                              parent[next] = cur;
                              open.add(new CoarseRoutePlanner.Open(next, nd + heuristic(nx, ny, goal)));
                           }
                        }
                     }
                  }
               }
            }

            return !open.isEmpty()
               ? new CoarseRoutePlanner.Route(CoarseRoutePlanner.Status.EXHAUSTED, null, Collections.emptyList(), expanded, start, goal)
               : new CoarseRoutePlanner.Route(
                  CoarseRoutePlanner.Status.NO_KNOWN_ROUTE, gapCause(src, closed, w, h), Collections.emptyList(), expanded, start, goal
               );
         }
      }
   }

   private static CoarseRoutePlanner.Cause gapCause(CoarseTileSource src, boolean[] closed, int w, int h) {
      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            if (closed[y * w + x]) {
               for (int d = 0; d < DX.length; d++) {
                  int nx = x + DX[d];
                  int ny = y + DY[d];
                  if (nx >= 0 && ny >= 0 && nx < w && ny < h && !closed[ny * w + nx] && src.tile(nx, ny) == CoarseTileSource.Tile.UNKNOWN) {
                     return CoarseRoutePlanner.Cause.UNKNOWN_GAP;
                  }
               }
            }
         }
      }

      return CoarseRoutePlanner.Cause.KNOWN_BLOCKED;
   }

   static List<Coord> simplify(CoarseTileSource src, List<Coord> path, int w, int h) {
      if (path != null && !path.isEmpty()) {
         List<Coord> out = new ArrayList<>();
         int i = 0;
         out.add(path.get(0));

         while (i < path.size() - 1) {
            int j = path.size() - 1;

            while (j > i + 1 && !clear(src, path.get(i), path.get(j), w, h)) {
               j--;
            }

            out.add(path.get(j));
            i = j;
         }

         return out;
      } else {
         return Collections.emptyList();
      }
   }

   private static boolean clear(CoarseTileSource src, Coord a, Coord b, int w, int h) {
      int x = a.x;
      int y = a.y;
      int dx = Math.abs(b.x - a.x);
      int dy = Math.abs(b.y - a.y);
      int sx = a.x < b.x ? 1 : -1;
      int sy = a.y < b.y ? 1 : -1;
      int err = dx - dy;

      while (src.tile(x, y) == CoarseTileSource.Tile.FREE) {
         if (x == b.x && y == b.y) {
            return true;
         }

         int px = x;
         int py = y;
         int e2 = 2 * err;
         boolean moveX = e2 > -dy;
         boolean moveY = e2 < dx;
         if (moveX) {
            err -= dy;
            x += sx;
         }

         if (moveY) {
            err += dx;
            y += sy;
         }

         if (moveX && moveY) {
            if (src.tile(px + sx, py) != CoarseTileSource.Tile.FREE) {
               return false;
            }

            if (src.tile(px, py + sy) != CoarseTileSource.Tile.FREE) {
               return false;
            }
         }
      }

      return false;
   }

   private static boolean inside(Coord c, int w, int h) {
      return c != null && c.x >= 0 && c.y >= 0 && c.x < w && c.y < h;
   }

   private static double heuristic(int x, int y, Coord goal) {
      int dx = Math.abs(x - goal.x);
      int dy = Math.abs(y - goal.y);
      return (double)Math.max(dx, dy) + (SQRT2 - 1.0) * (double)Math.min(dx, dy);
   }

   private static List<Coord> collect(int[] parent, int at, int w) {
      List<Coord> ret = new ArrayList<>();

      while (at >= 0) {
         ret.add(Coord.of(at % w, at / w));
         at = parent[at];
      }

      Collections.reverse(ret);
      return ret;
   }

   public static enum Cause {
      UNKNOWN_GAP,
      KNOWN_BLOCKED;
   }

   private static final class Open implements Comparable<CoarseRoutePlanner.Open> {
      final int id;
      final double score;

      Open(int id, double score) {
         this.id = id;
         this.score = score;
      }

      public int compareTo(CoarseRoutePlanner.Open that) {
         return Double.compare(this.score, that.score);
      }
   }

   public static final class Route {
      public final CoarseRoutePlanner.Status status;
      public final CoarseRoutePlanner.Cause cause;
      public final List<Coord> waypoints;
      public final int expanded;
      public final Coord start;
      public final Coord goal;

      private Route(CoarseRoutePlanner.Status status, CoarseRoutePlanner.Cause cause, List<Coord> waypoints, int expanded, Coord start, Coord goal) {
         this.status = status;
         this.cause = cause;
         this.waypoints = waypoints == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(waypoints));
         this.expanded = expanded;
         this.start = start;
         this.goal = goal;
      }

      public boolean reached() {
         return this.status == CoarseRoutePlanner.Status.REACHED;
      }

      public boolean noKnownRoute() {
         return this.status == CoarseRoutePlanner.Status.NO_KNOWN_ROUTE;
      }

      public boolean exhausted() {
         return this.status == CoarseRoutePlanner.Status.EXHAUSTED;
      }

      public static CoarseRoutePlanner.Route reached(List<Coord> waypoints, int expanded) {
         if (waypoints != null && !waypoints.isEmpty()) {
            return new CoarseRoutePlanner.Route(
               CoarseRoutePlanner.Status.REACHED, null, waypoints, expanded, waypoints.get(0), waypoints.get(waypoints.size() - 1)
            );
         } else {
            throw new IllegalArgumentException("a REACHED route needs at least one waypoint");
         }
      }

      public static CoarseRoutePlanner.Route failed(CoarseRoutePlanner.Status status, CoarseRoutePlanner.Cause cause) {
         if (status == CoarseRoutePlanner.Status.REACHED) {
            throw new IllegalArgumentException("failed() is only for non-REACHED statuses: " + status);
         } else {
            return new CoarseRoutePlanner.Route(status, cause, Collections.emptyList(), 0, null, null);
         }
      }

      @Override
      public String toString() {
         return String.format(
            "Route[%s%s, %d waypoints, %d expanded, %s -> %s]",
            this.status,
            this.cause == null ? "" : ":" + this.cause,
            this.waypoints.size(),
            this.expanded,
            this.start,
            this.goal
         );
      }
   }

   public static enum Status {
      REACHED,
      NO_KNOWN_ROUTE,
      INVALID_START,
      INVALID_GOAL,
      EXHAUSTED,
      CROSS_SEGMENT;
   }
}
