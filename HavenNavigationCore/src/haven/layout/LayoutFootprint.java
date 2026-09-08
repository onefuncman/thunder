package haven.layout;

import haven.Coord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Renderer-independent footprint of a placeable thing, expressed in cells of the
 * placement grid: each cell covers {@code pitchX x pitchY} world units anchored
 * at a placement's world anchor. Cells are normalized so the minimum cell is
 * {@code (0,0)}. Arbitrary shapes are supported, including holes and concavities.
 *
 * <p>{@code blocksApproach} selects how the planner proves an interaction stand:
 * when true (solid things you stand beside), the footprint is treated as an
 * obstacle while proving the stand; when false (things you can stand on or over),
 * the planner proves a walkable route onto the footprint's anchor cell instead.
 */
public final class LayoutFootprint {
   private final int bboxW;
   private final int bboxH;
   private final boolean blocksApproach;
   private final List<Coord> cells;

   private LayoutFootprint(int bboxW, int bboxH, boolean blocksApproach, List<Coord> cells) {
      this.bboxW = bboxW;
      this.bboxH = bboxH;
      this.blocksApproach = blocksApproach;
      this.cells = cells;
   }

   /** A solid {@code w x h} block of cells; the footprint blocks its own approach. */
   public static LayoutFootprint rect(int w, int h) {
      return rect(w, h, true);
   }

   /** A solid {@code w x h} block of cells. */
   public static LayoutFootprint rect(int w, int h, boolean blocksApproach) {
      if (w < 1 || h < 1) {
         throw new IllegalArgumentException("size");
      }
      List<Coord> cells = new ArrayList<Coord>();
      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            cells.add(Coord.of(x, y));
         }
      }
      return new LayoutFootprint(w, h, blocksApproach, Collections.unmodifiableList(cells));
   }

   /**
    * Arbitrary shape; cells are normalized so the minimum cell becomes {@code (0,0)}.
    * Duplicate cells are ignored.
    */
   public static LayoutFootprint ofCells(List<Coord> cells, boolean blocksApproach) {
      if (cells == null || cells.isEmpty()) {
         throw new IllegalArgumentException("cells");
      }
      Set<Long> uniq = new LinkedHashSet<Long>();
      int minx = Integer.MAX_VALUE;
      int miny = Integer.MAX_VALUE;
      for (Coord c : cells) {
         if (c == null) {
            throw new IllegalArgumentException("cells");
         }
         uniq.add(Long.valueOf((((long) c.x) << 32) ^ (c.y & 0xffffffffL)));
         minx = Math.min(minx, c.x);
         miny = Math.min(miny, c.y);
      }
      List<Coord> norm = new ArrayList<Coord>();
      int bw = 0;
      int bh = 0;
      for (Long k : uniq) {
         int x = (int)(k.longValue() >> 32);
         int y = (int)(k.longValue() & 0xffffffffL);
         norm.add(Coord.of(x - minx, y - miny));
         bw = Math.max(bw, x - minx + 1);
         bh = Math.max(bh, y - miny + 1);
      }
      norm.sort(new Comparator<Coord>() {
         @Override
         public int compare(Coord a, Coord b) {
            int c = Integer.compare(a.y, b.y);
            return c != 0 ? c : Integer.compare(a.x, b.x);
         }
      });
      return new LayoutFootprint(bw, bh, blocksApproach, Collections.unmodifiableList(norm));
   }

   /** Bounding-box width in grid cells (always >= 1). */
   public int bboxW() {
      return this.bboxW;
   }

   /** Bounding-box height in grid cells (always >= 1). */
   public int bboxH() {
      return this.bboxH;
   }

   public boolean blocksApproach() {
      return this.blocksApproach;
   }

   /** Normalized cell offsets (minimum cell is {@code (0,0)}), row-major order. */
   public List<Coord> cells() {
      return this.cells;
   }

   @Override
   public String toString() {
      return "LayoutFootprint{" + this.bboxW + "x" + this.bboxH + ", cells=" + this.cells.size()
         + ", blocksApproach=" + this.blocksApproach + "}";
   }
}
