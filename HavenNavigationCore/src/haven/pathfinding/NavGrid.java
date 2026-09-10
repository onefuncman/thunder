package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.util.Arrays;

public class NavGrid implements GridAStar.Grid {
      final Coord2d origin;
      final int w;
      final int h;
      final boolean[] blocked;
      final double[] cost;

      public NavGrid(Coord2d origin, int w, int h) {
         this.origin = origin;
         this.w = w;
         this.h = h;
         this.blocked = new boolean[w * h];
         this.cost = new double[w * h];
         Arrays.fill(this.cost, 1.0);
      }

      @Override
      public int width() {
         return this.w;
      }

      @Override
      public int height() {
         return this.h;
      }

      @Override
      public boolean blocked(int x, int y) {
         return this.blocked[y * this.w + x];
      }

      @Override
      public double cost(int x, int y) {
         return x >= 0 && y >= 0 && x < this.w && y < this.h ? this.cost[y * this.w + x] : 1.0;
      }

      public void block(int x, int y) {
         if (x >= 0 && y >= 0 && x < this.w && y < this.h) {
            this.blocked[y * this.w + x] = true;
         }
      }

      Coord cell(Coord2d p) {
         return Coord.of((int)Math.floor((p.x - this.origin.x) / 2.75), (int)Math.floor((p.y - this.origin.y) / 2.75));
      }

      Coord2d world(Coord c) {
         return this.origin.add(((double)c.x + 0.5) * 2.75, ((double)c.y + 0.5) * 2.75);
      }
   }
