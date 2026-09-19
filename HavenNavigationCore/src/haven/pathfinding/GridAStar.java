package haven.pathfinding;

import haven.Coord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

public final class GridAStar {
   private static final int[] DX = new int[]{1, 1, 0, -1, -1, -1, 0, 1};
   private static final int[] DY = new int[]{0, 1, 1, 1, 0, -1, -1, -1};
   private static final double SQRT2 = Math.sqrt(2.0);

   private GridAStar() {
   }

   public static GridAStar.Result find(GridAStar.Grid grid, Coord start, Coord goal, int maxExpanded) {
      return goal == null ? new GridAStar.Result(Collections.emptyList(), false, 0) : find(grid, start, Collections.singletonList(goal), maxExpanded);
   }

   public static GridAStar.Result find(GridAStar.Grid grid, Coord start, Collection<Coord> goals, int maxExpanded) {
      int w = grid.width();
      int h = grid.height();
      if (w > 0 && h > 0 && inside(start, w, h) && goals != null && !goals.isEmpty()) {
         int[] gx = new int[goals.size()];
         int[] gy = new int[goals.size()];
         boolean[] isGoal = new boolean[w * h];
         int ng = 0;

         for (Coord g : goals) {
            if (inside(g, w, h) && (!grid.blocked(g.x, g.y) || g.x == start.x && g.y == start.y)) {
               int gid = id(g.x, g.y, w);
               if (!isGoal[gid]) {
                  isGoal[gid] = true;
                  gx[ng] = g.x;
                  gy[ng] = g.y;
                  ng++;
               }
            }
         }

         if (ng == 0) {
            return new GridAStar.Result(Collections.emptyList(), false, 0);
         } else {
            int n = w * h;
            double[] distance = new double[n];
            Arrays.fill(distance, Double.POSITIVE_INFINITY);
            int[] parent = new int[n];
            Arrays.fill(parent, -1);
            boolean[] closed = new boolean[n];
            PriorityQueue<GridAStar.Open> open = new PriorityQueue<>();
            int sid = id(start.x, start.y, w);
            distance[sid] = 0.0;
            open.add(new GridAStar.Open(sid, heuristic(start.x, start.y, gx, gy, ng)));
            int best = sid;
            int expanded = 0;
            double bestH = heuristic(start.x, start.y, gx, gy, ng);

            while (!open.isEmpty() && expanded < maxExpanded) {
               GridAStar.Open item = open.poll();
               int cur = item.id;
               if (!closed[cur]) {
                  closed[cur] = true;
                  expanded++;
                  if (isGoal[cur]) {
                     return new GridAStar.Result(collect(parent, cur, w), true, expanded);
                  }

                  int x = cur % w;
                  int y = cur / w;
                  double ch = heuristic(x, y, gx, gy, ng);
                  if (ch < bestH) {
                     bestH = ch;
                     best = cur;
                  }

                  for (int d = 0; d < DX.length; d++) {
                     int nx = x + DX[d];
                     int ny = y + DY[d];
                     if (nx >= 0
                        && ny >= 0
                        && nx < w
                        && ny < h
                        && !grid.blocked(nx, ny)
                        && grid.allowsStep(x, y, nx, ny)
                        && (
                           DX[d] == 0
                              || DY[d] == 0
                              || !grid.strictCorners()
                              || !grid.blocked(x + DX[d], y) && !grid.blocked(x, y + DY[d]) && !besideObstacle(grid, x, y) && !besideObstacle(grid, nx, ny)
                        )) {
                        int next = id(nx, ny, w);
                        double step = DX[d] != 0 && DY[d] != 0 ? SQRT2 : 1.0;
                        double nd = distance[cur] + step * Math.max(0.01, grid.cost(nx, ny));
                        if (nd < distance[next]) {
                           distance[next] = nd;
                           parent[next] = cur;
                           open.add(new GridAStar.Open(next, nd + heuristic(nx, ny, gx, gy, ng)));
                        }
                     }
                  }
               }
            }

            return new GridAStar.Result(collect(parent, best, w), false, expanded);
         }
      } else {
         return new GridAStar.Result(Collections.emptyList(), false, 0);
      }
   }

   /**
    * Dijkstra from {@code start} to every reachable cell. Same neighbor
    * rules as {@link #find}; used to rank many stands without one A* each.
    */
   public static GridAStar.Fill fill(GridAStar.Grid grid, Coord start, int maxExpanded) {
      int w = grid.width();
      int h = grid.height();
      int n = w * h;
      double[] distance = new double[n];
      Arrays.fill(distance, Double.POSITIVE_INFINITY);
      int[] parent = new int[n];
      Arrays.fill(parent, -1);
      if (w <= 0 || h <= 0 || !inside(start, w, h) || maxExpanded <= 0) {
         return new GridAStar.Fill(distance, parent, 0);
      }
      boolean[] closed = new boolean[n];
      PriorityQueue<GridAStar.Open> open = new PriorityQueue<GridAStar.Open>();
      int sid = id(start.x, start.y, w);
      distance[sid] = 0.0;
      open.add(new GridAStar.Open(sid, 0.0));
      int expanded = 0;
      while (!open.isEmpty() && expanded < maxExpanded) {
         GridAStar.Open item = open.poll();
         int cur = item.id;
         if (closed[cur]) {
            continue;
         }
         closed[cur] = true;
         expanded++;
         int x = cur % w;
         int y = cur / w;
         for (int d = 0; d < DX.length; d++) {
            int nx = x + DX[d];
            int ny = y + DY[d];
            if (nx < 0 || ny < 0 || nx >= w || ny >= h || grid.blocked(nx, ny)) {
               continue;
            }
            if (!grid.allowsStep(x, y, nx, ny)) {
               continue;
            }
            if (grid.strictCorners() && DX[d] != 0 && DY[d] != 0
               && (grid.blocked(x + DX[d], y) || grid.blocked(x, y + DY[d])
                  || besideObstacle(grid, x, y) || besideObstacle(grid, nx, ny))) {
               continue;
            }
            int next = id(nx, ny, w);
            double step = DX[d] != 0 && DY[d] != 0 ? SQRT2 : 1.0;
            double nd = distance[cur] + step * Math.max(0.01, grid.cost(nx, ny));
            if (nd < distance[next]) {
               distance[next] = nd;
               parent[next] = cur;
               open.add(new GridAStar.Open(next, nd));
            }
         }
      }
      return new GridAStar.Fill(distance, parent, expanded);
   }

   public static boolean besideObstacle(GridAStar.Grid grid, int x, int y) {
      if (grid == null) {
         return false;
      } else {
         int w = grid.width();
         int h = grid.height();

         for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
               if (dx != 0 || dy != 0) {
                  int nx = x + dx;
                  int ny = y + dy;
                  if (nx >= 0 && ny >= 0 && nx < w && ny < h && grid.blocked(nx, ny)) {
                     return true;
                  }
               }
            }
         }

         return false;
      }
   }

   private static boolean inside(Coord c, int w, int h) {
      return c != null && c.x >= 0 && c.y >= 0 && c.x < w && c.y < h;
   }

   private static int id(int x, int y, int w) {
      return y * w + x;
   }

   private static double heuristic(int x, int y, int gx, int gy) {
      int dx = Math.abs(x - gx);
      int dy = Math.abs(y - gy);
      return (double)Math.max(dx, dy) + (SQRT2 - 1.0) * (double)Math.min(dx, dy);
   }

   private static double heuristic(int x, int y, int[] gx, int[] gy, int n) {
      double best = Double.POSITIVE_INFINITY;

      for (int i = 0; i < n; i++) {
         best = Math.min(best, heuristic(x, y, gx[i], gy[i]));
      }

      return best;
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

   public interface Grid {
      int width();

      int height();

      boolean blocked(int var1, int var2);

      /** Continuous edge validation. Cell-only grids keep the legacy default. */
      default boolean allowsStep(int x, int y, int nx, int ny) {
         return true;
      }

      /** Legacy occupancy needs conservative diagonal corner rules. Exact
       * geometry grids validate the complete movement segment instead. */
      default boolean strictCorners() {
         return true;
      }

      default double cost(int x, int y) {
         return 1.0;
      }
   }

   private static final class Open implements Comparable<GridAStar.Open> {
      final int id;
      final double score;

      Open(int id, double score) {
         this.id = id;
         this.score = score;
      }

      public int compareTo(GridAStar.Open that) {
         return Double.compare(this.score, that.score);
      }
   }

   public static final class Result {
      public final List<Coord> cells;
      public final boolean complete;
      public final int expanded;

      private Result(List<Coord> cells, boolean complete, int expanded) {
         this.cells = cells;
         this.complete = complete;
         this.expanded = expanded;
      }
   }

   public static final class Fill {
      public final double[] distance;
      public final int[] parent;
      public final int expanded;

      private Fill(double[] distance, int[] parent, int expanded) {
         this.distance = distance;
         this.parent = parent;
         this.expanded = expanded;
      }
   }
}
