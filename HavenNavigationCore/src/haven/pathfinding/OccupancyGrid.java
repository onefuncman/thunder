package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OccupancyGrid {
      public static final byte FREE = 0;
      public static final byte SOLID = 1;
      public static final byte DILATED = 2;
      public static final byte CARVED = 3;
      public final Coord2d origin;
      public final int w;
      public final int h;
      public final double cell;
      public final byte[] occ;
      public final Coord start;
      public final Coord goal;
      public final Coord freeGoal;
      public final List<Coord> astar;

      public OccupancyGrid(Coord2d origin, int w, int h, double cell, byte[] occ, Coord start, Coord goal, Coord freeGoal, List<Coord> astar) {
         this.origin = origin;
         this.w = w;
         this.h = h;
         this.cell = cell;
         this.occ = occ;
         this.start = start;
         this.goal = goal;
         this.freeGoal = freeGoal;
         this.astar = astar == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(astar));
      }

      public static OccupancyGrid capture(
         Coord2d origin,
         int w,
         int h,
         double cell,
         boolean[] solid,
         boolean[] dilated,
         boolean[] blockedAfter,
         Coord start,
         Coord goal,
         Coord freeGoal,
         List<Coord> astar
      ) {
         byte[] occ = new byte[Math.max(0, w * h)];
         int n = Math.min(occ.length, Math.min(len(solid), len(dilated)));

         for (int i = 0; i < n; i++) {
            if (solid != null && solid[i]) {
               occ[i] = 1;
            } else if (dilated != null && dilated[i]) {
               occ[i] = (byte)(blockedAfter != null && i < blockedAfter.length && blockedAfter[i] ? 2 : 3);
            } else {
               occ[i] = 0;
            }
         }

         return new OccupancyGrid(origin, w, h, cell, occ, start, goal, freeGoal, astar);
      }

      public static String encode(OccupancyGrid occ) {
         if (occ == null || occ.occ == null) {
            return "";
         }
         char[] chars = new char[occ.occ.length];
         for (int i = 0; i < occ.occ.length; i++) {
            int v = occ.occ[i] & 0xff;
            chars[i] = (char)('0' + Math.min(9, v));
         }
         return new String(chars);
      }

      public static OccupancyGrid decode(
         Coord2d origin, int w, int h, double cell, String encoded, Coord start, Coord goal, Coord freeGoal
      ) {
         byte[] occ = new byte[Math.max(0, w * h)];
         if (encoded != null) {
            int n = Math.min(occ.length, encoded.length());
            for (int i = 0; i < n; i++) {
               char c = encoded.charAt(i);
               occ[i] = (byte)(c >= '0' && c <= '9' ? c - '0' : 1);
            }
         }
         return new OccupancyGrid(origin, w, h, cell, occ, start, goal, freeGoal, Collections.emptyList());
      }

      public Coord cellOf(Coord2d p) {
         return p != null && this.origin != null && !(this.cell <= 0.0)
            ? Coord.of((int)Math.floor((p.x - this.origin.x) / this.cell), (int)Math.floor((p.y - this.origin.y) / this.cell))
            : null;
      }

      public Coord2d world(int x, int y) {
         return this.origin.add(((double)x + 0.5) * this.cell, ((double)y + 0.5) * this.cell);
      }

      public Coord2d corner(int x, int y) {
         return this.origin.add((double)x * this.cell, (double)y * this.cell);
      }

      public byte at(int x, int y) {
         return x >= 0 && y >= 0 && x < this.w && y < this.h ? this.occ[y * this.w + x] : 1;
      }

      public boolean onPath(int x, int y) {
         for (Coord c : this.astar) {
            if (c != null && c.x == x && c.y == y) {
               return true;
            }
         }

         return false;
      }

      public static String name(byte v) {
         switch (v) {
            case 1:
               return "SOLID";
            case 2:
               return "INFLATED";
            case 3:
               return "carve";
            default:
               return "free";
         }
      }

      public String ascii(Coord focus, int radius) {
         if (focus == null) {
            focus = this.start == null ? Coord.z : this.start;
         }

         int r = Math.max(1, radius);
         Set<Long> path = new HashSet<>();

         for (Coord c : this.astar) {
            if (c != null) {
               path.add((long)c.y << 32 ^ (long)c.x & 4294967295L);
            }
         }

         StringBuilder sb = new StringBuilder();
         sb.append("  ");

         for (int x = focus.x - r; x <= focus.x + r; x++) {
            sb.append(Math.abs(x) % 10);
         }

         sb.append('\n');

         for (int y = focus.y - r; y <= focus.y + r; y++) {
            sb.append(Math.abs(y) % 10).append(' ');

            for (int x = focus.x - r; x <= focus.x + r; x++) {
               char ch = '.';
               if (x >= 0 && y >= 0 && x < this.w && y < this.h) {
                  byte v = this.occ[y * this.w + x];
                  if (v == 1) {
                     ch = '#';
                  } else if (v == 2) {
                     ch = '+';
                  } else if (v == 3) {
                     ch = 'o';
                  }

                  if (path.contains((long)y << 32 ^ (long)x & 4294967295L)) {
                     ch = '*';
                  }

                  if (this.freeGoal != null && this.freeGoal.x == x && this.freeGoal.y == y) {
                     ch = 'F';
                  }

                  if (this.goal != null && this.goal.x == x && this.goal.y == y) {
                     ch = 'G';
                  }

                  if (this.start != null && this.start.x == x && this.start.y == y) {
                     ch = 'S';
                  }

                  if (focus.x == x && focus.y == y) {
                     ch = '@';
                  }
               } else {
                  ch = ' ';
               }

               sb.append(ch);
            }

            sb.append('\n');
         }

         sb.append("@ here  S start  G wanted  F snapped  * path  # solid  + inflated  o carve  . free");
         return sb.toString();
      }

      private static int len(boolean[] a) {
         return a == null ? 0 : a.length;
      }
   }
