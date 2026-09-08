package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.Loading;
import haven.MCache;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure state and geometry for the opt-in organizer's multi-click area tool:
 * four ground clicks (the user walks between corners) define a quadrilateral.
 * Each vertex is captured both as a session world tile and — when a live map
 * is supplied — in durable (grid_id, local_x, local_y) form so the exported
 * area survives map re-exports. The selection also exposes the axis-aligned
 * bounding box (min/max tiles) used by the stockpile planner.
 */
public final class OrganizerAreaSelector {
   public static final int VERTEX_COUNT = 4;

   public enum State { EMPTY, IN_PROGRESS, COMPLETE }

   public static final class Selection {
      /** Ordered polygon vertices, each {@code {gridId, lx, ly}} (empty when unresolved). */
      public final List<long[]> gridVertices;
      /** The corresponding session world tiles (in click order). */
      public final List<Coord> sessionTiles;
      /** Axis-aligned bounding box of the vertices (tile space). */
      public final Coord minTile;
      public final Coord maxTile;
      /** Axis-aligned bounding box (world space). */
      public final Coord2d min;
      public final Coord2d max;

      private Selection(List<long[]> gridVertices, List<Coord> sessionTiles) {
         this.gridVertices = gridVertices;
         this.sessionTiles = sessionTiles;
         int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE;
         int maxx = Integer.MIN_VALUE, maxy = Integer.MIN_VALUE;
         for (Coord t : sessionTiles) {
            minx = Math.min(minx, t.x);
            miny = Math.min(miny, t.y);
            maxx = Math.max(maxx, t.x);
            maxy = Math.max(maxy, t.y);
         }
         this.minTile = Coord.of(minx, miny);
         this.maxTile = Coord.of(maxx, maxy);
         this.min = Coord2d.of(minTile).mul(MCache.tilesz);
         this.max = Coord2d.of(maxTile.add(1, 1)).mul(MCache.tilesz);
      }

      public int widthTiles() { return maxTile.x - minTile.x + 1; }
      public int heightTiles() { return maxTile.y - minTile.y + 1; }
      public int tileCount() { return widthTiles() * heightTiles(); }
      public int vertexCount() { return sessionTiles.size(); }

      @Override public String toString() {
         return vertexCount() + "-vertex area";
      }
   }

   private State state = State.EMPTY;
   private final List<long[]> gridVertices = new ArrayList<>();
   private final List<Coord> tiles = new ArrayList<>();
   private Selection selection;

   public State state() { return state; }
   public int vertexCount() { return tiles.size(); }
   public Coord firstTile() { return tiles.isEmpty() ? null : tiles.get(0); }
   public Selection selection() { return selection; }

   /** Records a session-tile vertex without resolving its grid id (pure geometry; used by tests). */
   public Selection click(Coord2d world) {
      return click(world, null, false);
   }

   /** Records a vertex, resolving its grid id + local cell from the live map. */
   public Selection click(Coord2d world, MCache map) {
      return click(world, map, true);
   }

   private Selection click(Coord2d world, MCache map, boolean resolveGrid) {
      if (world == null) throw new IllegalArgumentException("world");
      Coord tile = world.floor(MCache.tilesz);
      if (resolveGrid) {
         Coord gc = tile.div(MCache.cmaps);
         MCache.Grid g;
         try {
            g = (map == null) ? null : map.getgrid(gc);
         } catch (Loading l) {
            g = null;
         }
         if (g == null) {
            return null; // grid not loaded: caller tells the user to move closer
         }
         int lx = tile.x - gc.x * MCache.cmaps.x;
         int ly = tile.y - gc.y * MCache.cmaps.y;
         gridVertices.add(new long[]{g.id, lx, ly});
      }
      tiles.add(tile);
      if (tiles.size() >= VERTEX_COUNT) {
         selection = new Selection(new ArrayList<>(gridVertices), new ArrayList<>(tiles));
         state = State.COMPLETE;
         return selection;
      }
      state = State.IN_PROGRESS;
      return null;
   }

   public void cancel() {
      state = State.EMPTY;
      selection = null;
      gridVertices.clear();
      tiles.clear();
   }
}
