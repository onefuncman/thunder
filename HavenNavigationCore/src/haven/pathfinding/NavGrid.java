package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NavGrid implements GridAStar.Grid {
      final Coord2d origin;
      final int w;
      final int h;
      final boolean[] blocked;
      final double[] cost;
      private boolean[] baseBlocked;
      private List<Coord2d[]> exactSolids = Collections.emptyList();
      private List<Coord2d[]> exactBody = Collections.emptyList();
      private LocalPlanner.PolyBounds exactBounds;
      private Coord exactStartCell;
      private Coord2d exactStartPosition;

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
      public boolean allowsStep(int x, int y, int nx, int ny) {
         if (!exactCollisionEnabled()) {
            return true;
         }
         Coord2d a = exactStartCell != null && exactStartCell.x == x && exactStartCell.y == y
            && exactStartPosition != null ? exactStartPosition : world(Coord.of(x, y));
         Coord2d b = world(Coord.of(nx, ny));
         return segmentClear(a, b, LocalPlanner.bodyHitsAny(a, this.exactBody, this.exactSolids, null, this.exactBounds));
      }

      @Override
      public boolean strictCorners() {
         return !exactCollisionEnabled();
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

      /** Separates tile/hazard blocking from object collision. Objects are
       * validated against the same polygons used by the visible hitbox. */
      public void configureExactCollision(List<Coord2d[]> solids, List<Coord2d[]> body, boolean[] baseBlocked) {
         this.exactSolids = immutablePolygons(solids);
         this.exactBody = immutablePolygons(body);
         this.exactBounds = LocalPlanner.PolyBounds.of(this.exactSolids);
         this.baseBlocked = baseBlocked == null ? new boolean[this.blocked.length] : Arrays.copyOf(baseBlocked, this.blocked.length);
      }

      public void copyExactCollisionFrom(NavGrid src) {
         if (src != null && src.exactCollisionEnabled()) {
            configureExactCollision(src.exactSolids, src.exactBody, src.baseBlocked);
         }
      }

      public boolean exactCollisionEnabled() {
         return this.baseBlocked != null;
      }

      public void blockBase(int x, int y) {
         block(x, y);
         if (this.baseBlocked != null && x >= 0 && y >= 0 && x < this.w && y < this.h) {
            this.baseBlocked[y * this.w + x] = true;
         }
      }

      public void allowStart(Coord start, Coord2d position) {
         if (start == null || start.x < 0 || start.y < 0 || start.x >= this.w || start.y >= this.h) {
            return;
         }
         this.exactStartCell = start;
         this.exactStartPosition = position;
         int i = start.y * this.w + start.x;
         this.blocked[i] = false;
         if (this.baseBlocked != null) {
            this.baseBlocked[i] = false;
         }
      }

      public boolean segmentClear(Coord2d a, Coord2d b, boolean leaving) {
         if (!exactCollisionEnabled()) {
            return true;
         }
         if (!LocalPlanner.clear(a, b, this, this.baseBlocked)) {
            return false;
         }
         return leaving
            ? LocalPlanner.sweptClearLeaving(a, b, this.exactBody, this.exactSolids, null, this.exactBounds)
            : LocalPlanner.sweptClear(a, b, this.exactBody, this.exactSolids, null, this.exactBounds);
      }

      public boolean positionClear(Coord2d at) {
         if (!exactCollisionEnabled() || at == null) {
            return true;
         }
         Coord c = cell(at);
         if (c.x < 0 || c.y < 0 || c.x >= this.w || c.y >= this.h || this.baseBlocked[c.y * this.w + c.x]) {
            return false;
         }
         return !LocalPlanner.bodyHitsAny(at, this.exactBody, this.exactSolids, null, this.exactBounds);
      }

      private static List<Coord2d[]> immutablePolygons(List<Coord2d[]> source) {
         if (source == null || source.isEmpty()) {
            return Collections.emptyList();
         }
         return Collections.unmodifiableList(new ArrayList<Coord2d[]>(source));
      }

      Coord cell(Coord2d p) {
         return Coord.of((int)Math.floor((p.x - this.origin.x) / 2.75), (int)Math.floor((p.y - this.origin.y) / 2.75));
      }

      Coord2d world(Coord c) {
         return this.origin.add(((double)c.x + 0.5) * 2.75, ((double)c.y + 0.5) * 2.75);
      }
   }
