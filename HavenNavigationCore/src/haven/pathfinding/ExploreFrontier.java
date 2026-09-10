package haven.pathfinding;

import haven.Coord;
import haven.nav.NavOutcome;
import java.util.ArrayDeque;
import java.util.HashSet;

/**
 * Known-safe frontier toward a remote objective. Never paths UNKNOWN cells.
 */
public final class ExploreFrontier {
   private ExploreFrontier() {
   }

   public static final class Result {
      public final Coord cell;
      public final NavOutcome outcome;
      public final String reason;
      public final int expanded;

      Result(Coord cell, NavOutcome outcome, String reason, int expanded) {
         this.cell = cell;
         this.outcome = outcome;
         this.reason = reason;
         this.expanded = expanded;
      }

      public boolean picked() {
         return this.cell != null && this.outcome != NavOutcome.BUDGET_EXHAUSTED && this.outcome != NavOutcome.UNKNOWN_TERRAIN;
      }
   }

   public static Result select(CoarseTileSource src, Coord start, Coord destHint, int budget) {
      if (src == null || start == null || !inside(src, start) || src.tile(start.x, start.y) != CoarseTileSource.Tile.FREE) {
         return new Result(null, NavOutcome.UNKNOWN_TERRAIN, "unknown_terrain", 0);
      }
      int cap = budget < 1 ? 1 : budget;
      ArrayDeque<Coord> q = new ArrayDeque<Coord>();
      HashSet<Long> seen = new HashSet<Long>();
      q.add(start);
      seen.add(key(start));
      Coord bestFrontier = null;
      int bestScore = Integer.MAX_VALUE;
      int expanded = 0;
      boolean destReached = false;
      while (!q.isEmpty()) {
         if (expanded >= cap) {
            if (bestFrontier != null) {
               return new Result(bestFrontier, NavOutcome.PARTIAL, "partial", expanded);
            }
            return new Result(null, NavOutcome.BUDGET_EXHAUSTED, "budget_exhausted", expanded);
         }
         Coord c = q.remove();
         expanded++;
         if (destHint != null && c.equals(destHint)) {
            destReached = true;
            break;
         }
         if (isFrontier(src, c)) {
            int score = destHint == null ? chebyshev(c, start) : chebyshev(c, destHint);
            if (bestFrontier == null || score < bestScore || score == bestScore && (c.x < bestFrontier.x || c.x == bestFrontier.x && c.y < bestFrontier.y)) {
               bestFrontier = c;
               bestScore = score;
            }
         }
         for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
               if (dx == 0 && dy == 0) {
                  continue;
               }
               Coord n = Coord.of(c.x + dx, c.y + dy);
               if (!inside(src, n) || seen.contains(key(n))) {
                  continue;
               }
               if (src.tile(n.x, n.y) != CoarseTileSource.Tile.FREE) {
                  continue;
               }
               seen.add(key(n));
               q.add(n);
            }
         }
      }
      if (destReached) {
         return new Result(destHint, null, "", expanded);
      }
      if (bestFrontier != null) {
         return new Result(bestFrontier, NavOutcome.PARTIAL, "partial", expanded);
      }
      return new Result(null, NavOutcome.UNKNOWN_TERRAIN, "unknown_terrain", expanded);
   }

   private static boolean isFrontier(CoarseTileSource src, Coord c) {
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) {
               continue;
            }
            Coord n = Coord.of(c.x + dx, c.y + dy);
            if (inside(src, n) && src.tile(n.x, n.y) == CoarseTileSource.Tile.UNKNOWN) {
               return true;
            }
         }
      }
      return false;
   }

   private static boolean inside(CoarseTileSource src, Coord c) {
      return c != null && c.x >= 0 && c.y >= 0 && c.x < src.width() && c.y < src.height();
   }

   private static int chebyshev(Coord a, Coord b) {
      return Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
   }

   private static long key(Coord c) {
      return ((long) c.x << 32) ^ (c.y & 0xffffffffL);
   }
}
