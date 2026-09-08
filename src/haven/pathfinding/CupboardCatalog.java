package haven.pathfinding;

import haven.Coord2d;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CupboardCatalog {
   public static final double TILE = 11.0;
   public static final double NEIGHBOR = 14.850000000000001;
   public static final double BOX_HALF = 5.5;
   public static final double FACE = 2.0;
   public static final double STAND = 7.5;
   public static final double STAND_MAX = 11.0;
   public static final double INTERACT = 14.850000000000001;
   public static final double EXTENDED = 22.0;
   public static final double ROOM = 220.0;

   public static List<CupboardCatalog.Entry> merge(List<CupboardCatalog.Entry> items) {
      if (items != null && !items.isEmpty()) {
         List<CupboardCatalog.Entry> out = new ArrayList<>();
         Map<String, Integer> at = new HashMap<>();

         for (CupboardCatalog.Entry e : items) {
            if (e != null) {
               String key = e.mergeKey();
               Integer i = at.get(key);
               if (i == null) {
                  at.put(key, out.size());
                  out.add(e);
               } else {
                  CupboardCatalog.Entry cur = out.get(i);
                  out.set(i, cur.withAmount(cur.amount + e.amount));
               }
            }
         }

         return out;
      } else {
         return Collections.emptyList();
      }
   }

   public static String format(CupboardCatalog.Entry e) {
      if (e == null) {
         return "";
      } else {
         StringBuilder sb = new StringBuilder();
         if (e.amount > 1.01) {
            sb.append(trimAmount(e.amount)).append("× ");
         }

         sb.append(e.name.isEmpty() ? "???" : e.name);
         if (e.quality > 0.0) {
            sb.append("  ").append(qualityText(e.quality));
         }

         if (e.lp != null && e.lp > 0) {
            sb.append("  LP ").append(e.lp);
         }

         if (e.lph != null && e.lph > 0) {
            sb.append("  LP/H ").append(e.lph);
         }

         return sb.toString();
      }
   }

   public static String qualityText(double q) {
      return Math.abs(q - (double)Math.round(q)) < 0.05 ? String.format("q%.0f", q) : String.format("q%.1f", q);
   }

   private static String trimAmount(double amount) {
      return Math.abs(amount - (double)Math.round(amount)) < 0.01 ? String.format("%.0f", amount) : String.format("%.1f", amount);
   }

   private CupboardCatalog() {
   }

   public static boolean isCupboardResid(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && name.equals("gfx/terobjs/cupboard");
   }

   public static Coord2d[] standCandidates(double x, double y) {
      return new Coord2d[]{
         Coord2d.of(x, y - 7.5),
         Coord2d.of(x + 7.5, y),
         Coord2d.of(x, y + 7.5),
         Coord2d.of(x - 7.5, y),
         Coord2d.of(x, y - 11.0),
         Coord2d.of(x + 11.0, y),
         Coord2d.of(x, y + 11.0),
         Coord2d.of(x - 11.0, y)
      };
   }

   public static Coord2d[] standCandidates(Coord2d rc) {
      return rc == null ? new Coord2d[0] : standCandidates(rc.x, rc.y);
   }

   public static Coord2d faceSnap(Coord2d gob, Coord2d stand) {
      if (gob != null && stand != null) {
         double dx = stand.x - gob.x;
         double dy = stand.y - gob.y;
         if (Math.abs(dx) >= Math.abs(dy)) {
            double sign = dx < 0.0 ? -1.0 : 1.0;
            return Coord2d.of(gob.x + sign * 7.5, gob.y);
         } else {
            double sign = dy < 0.0 ? -1.0 : 1.0;
            return Coord2d.of(gob.x, gob.y + sign * 7.5);
         }
      } else {
         return stand;
      }
   }

   public static Coord2d lateralStand(Coord2d viaGob, Coord2d nookGob, Coord2d frozen, List<CupboardCatalog.Node> cups, long viaId) {
      return lateralStand(viaGob, nookGob, frozen, cups, viaId, frozen);
   }

   public static Coord2d lateralStand(Coord2d viaGob, Coord2d nookGob, Coord2d frozen, List<CupboardCatalog.Node> cups, long viaId, Coord2d preferNear) {
      if (viaGob != null && nookGob != null) {
         if (usableLateral(viaGob, nookGob, frozen, cups, viaId)) {
            return frozen;
         } else {
            Coord2d ref = preferNear != null ? preferNear : frozen;
            Coord2d best = null;

            for (Coord2d c : standCandidates(viaGob)) {
               if (usableLateral(viaGob, nookGob, c, cups, viaId) && (best == null || ref != null && c.dist(ref) < best.dist(ref))) {
                  best = c;
               }
            }

            return best != null ? best : frozen;
         }
      } else {
         return frozen;
      }
   }

   private static boolean usableLateral(Coord2d viaGob, Coord2d nookGob, Coord2d stand, List<CupboardCatalog.Node> cups, long viaId) {
      if (stand != null && standClear(stand, cups, viaId)) {
         return standOnRoomWall(viaGob, stand, cups) && faceIndex(viaGob, stand) == 1
            ? false
            : lateral(nookGob.x, nookGob.y, viaGob.x, viaGob.y, extendAxis(viaGob, stand));
      } else {
         return false;
      }
   }

   public static Coord2d playerStand(Coord2d gob, Coord2d player, List<CupboardCatalog.Node> cups, long selfId) {
      return playerFacingStand(gob, player, cups, selfId, 7.5);
   }

   public static Coord2d playerStandFar(Coord2d gob, Coord2d player, List<CupboardCatalog.Node> cups, long selfId) {
      return playerFacingStand(gob, player, cups, selfId, 11.0);
   }

   public static Coord2d aisleStand(Coord2d gob, Coord2d player, List<CupboardCatalog.Node> cups, long selfId) {
      if (gob == null) {
         return null;
      } else if (!hasPackedNeighbor(selfId, cups)) {
         return bestStand(gob, player, cups, selfId);
      } else {
         boolean corner = isCorner(node(cups, selfId), cups);
         int[] counts = faceCounts(cups);
         double[] box = aabb(cups);
         Coord2d west = hugIfClear(gob, -1, 0, 7.5, cups, selfId);
         Coord2d east = hugIfClear(gob, 1, 0, 7.5, cups, selfId);
         Coord2d south = hugIfClear(gob, 0, 1, 7.5, cups, selfId);
         Coord2d north = hugIfClear(gob, 0, -1, 7.5, cups, selfId);
         boolean room = box[1] - box[0] > 27.5 && box[3] - box[2] > 27.5;
         if (corner && room) {
            if (gob.x <= box[0] + 0.5) {
               west = null;
            }

            if (gob.x >= box[1] - 0.5) {
               east = null;
            }

            if (gob.y <= box[2] + 0.5) {
               north = null;
            }

            if (gob.y >= box[3] + 0.5) {
               south = null;
            }
         }

         Coord2d mid = centroid(cups);
         if (west != null && east != null) {
            return towardRoom(mid, west, east, true);
         } else {
            boolean wallRowNS = north != null && south != null && (counts[0] >= 3 || counts[2] >= 3);
            if (wallRowNS) {
               return towardRoom(mid, north, south, false);
            } else if (legalFace(west, 3, counts, box, gob, false)) {
               return west;
            } else if (legalFace(east, 1, counts, box, gob, false)) {
               return east;
            } else if (north != null && south != null) {
               return towardRoom(mid, north, south, false);
            } else if (corner && west == null && east == null) {
               return null;
            } else if (legalFace(south, 2, counts, box, gob, false)) {
               return south;
            } else {
               return legalFace(north, 0, counts, box, gob, false) ? north : null;
            }
         }
      }
   }

   static Coord2d centroid(List<CupboardCatalog.Node> cups) {
      double x = 0.0;
      double y = 0.0;
      int n = 0;
      if (cups != null) {
         for (CupboardCatalog.Node c : cups) {
            if (c != null) {
               x += c.x;
               y += c.y;
               n++;
            }
         }
      }

      return n == 0 ? Coord2d.of(0.0, 0.0) : Coord2d.of(x / (double)n, y / (double)n);
   }

   private static Coord2d towardRoom(Coord2d mid, Coord2d a, Coord2d b, boolean eastWest) {
      if (a == null) {
         return b;
      } else if (b == null) {
         return a;
      } else if (mid == null) {
         return eastWest ? (a.x >= b.x ? a : b) : (a.y >= b.y ? a : b);
      } else {
         double da = mid.dist(a);
         double db = mid.dist(b);
         if (Math.abs(da - db) < 0.5) {
            return eastWest ? (a.x >= b.x ? a : b) : (a.y >= b.y ? a : b);
         } else {
            return da <= db ? a : b;
         }
      }
   }

   private static boolean legalFace(Coord2d hug, int f, int[] counts, double[] box, Coord2d gob, boolean corner) {
      if (hug != null && gob != null && counts != null && box != null) {
         boolean shared = counts[f] >= 2;
         if (f == 1 || f == 3) {
            boolean outerEW = f == 1 && gob.x >= box[1] - 0.5 || f == 3 && gob.x <= box[0] + 0.5;
            return shared || outerEW;
         } else {
            return f == 0 ? gob.y > box[2] + 0.5 : gob.y < box[3] - 0.5;
         }
      } else {
         return false;
      }
   }

   public static boolean standOnRoomWall(Coord2d gob, Coord2d stand, List<CupboardCatalog.Node> cups) {
      if (gob != null && stand != null && cups != null) {
         double[] box = aabb(cups);
         if (!(box[1] - box[0] <= 27.5) && !(box[3] - box[2] <= 27.5)) {
            int f = faceIndex(gob, stand);
            if (f == 3) {
               return gob.x <= box[0] + 0.5;
            } else if (f == 1) {
               return gob.x >= box[1] - 0.5;
            } else if (f == 0) {
               return gob.y <= box[2] + 0.5;
            } else {
               return f == 2 ? gob.y >= box[3] - 0.5 : false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   static CupboardCatalog.Node node(List<CupboardCatalog.Node> cups, long id) {
      if (cups == null) {
         return null;
      } else {
         for (CupboardCatalog.Node n : cups) {
            if (n != null && n.id == id) {
               return n;
            }
         }

         return null;
      }
   }

   private static double[] aabb(List<CupboardCatalog.Node> cups) {
      double minx = Double.POSITIVE_INFINITY;
      double maxx = Double.NEGATIVE_INFINITY;
      double miny = Double.POSITIVE_INFINITY;
      double maxy = Double.NEGATIVE_INFINITY;
      if (cups != null) {
         for (CupboardCatalog.Node n : cups) {
            if (n != null) {
               if (n.x < minx) {
                  minx = n.x;
               }

               if (n.x > maxx) {
                  maxx = n.x;
               }

               if (n.y < miny) {
                  miny = n.y;
               }

               if (n.y > maxy) {
                  maxy = n.y;
               }
            }
         }
      }

      return new double[]{minx, maxx, miny, maxy};
   }

   public static boolean hasPackedNeighbor(long id, List<CupboardCatalog.Node> cups) {
      if (cups == null) {
         return false;
      } else {
         CupboardCatalog.Node self = null;

         for (CupboardCatalog.Node n : cups) {
            if (n != null && n.id == id) {
               self = n;
               break;
            }
         }

         if (self == null) {
            return false;
         } else {
            for (CupboardCatalog.Node nx : cups) {
               if (adjacent(self, nx)) {
                  return true;
               }
            }

            return false;
         }
      }
   }

   static int[] faceCounts(List<CupboardCatalog.Node> cups) {
      int[] counts = new int[4];
      if (cups == null) {
         return counts;
      } else {
         int[][] dir = new int[][]{{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

         for (CupboardCatalog.Node n : cups) {
            if (n != null) {
               Coord2d gob = Coord2d.of(n.x, n.y);

               for (int f = 0; f < 4; f++) {
                  if (hugIfClear(gob, dir[f][0], dir[f][1], 7.5, cups, n.id) != null) {
                     counts[f]++;
                  }
               }
            }
         }

         return counts;
      }
   }

   public static Coord2d bestStand(Coord2d gob, Coord2d player, List<CupboardCatalog.Node> cups, long selfId) {
      Coord2d west = hugIfClear(gob, -1, 0, 7.5, cups, selfId);
      Coord2d east = hugIfClear(gob, 1, 0, 7.5, cups, selfId);
      if (west != null && east != null && player != null) {
         return player.dist(west) <= player.dist(east) ? west : east;
      } else if (west != null) {
         return west;
      } else if (east != null) {
         return east;
      } else {
         Coord2d north = hugIfClear(gob, 0, -1, 7.5, cups, selfId);
         Coord2d south = hugIfClear(gob, 0, 1, 7.5, cups, selfId);
         if (north != null && south != null && player != null) {
            return player.dist(north) <= player.dist(south) ? north : south;
         } else {
            return north != null ? north : south;
         }
      }
   }

   public static Coord2d sameFaceFar(Coord2d gob, Coord2d stand) {
      if (gob != null && stand != null) {
         double dx = stand.x - gob.x;
         double dy = stand.y - gob.y;
         return Math.abs(dx) >= Math.abs(dy) ? Coord2d.of(gob.x + (dx < 0.0 ? -11.0 : 11.0), gob.y) : Coord2d.of(gob.x, gob.y + (dy < 0.0 ? -11.0 : 11.0));
      } else {
         return null;
      }
   }

   public static boolean throughPacked(Coord2d from, Coord2d to, List<CupboardCatalog.Node> cups, long ignoreId) {
      if (from != null && to != null && cups != null) {
         double dist = from.dist(to);
         if (dist < 1.0) {
            return false;
         } else {
            int steps = Math.max(8, (int)Math.ceil(dist / 2.0));

            for (int i = 1; i < steps; i++) {
               double t = (double)i / (double)steps;
               Coord2d p = Coord2d.of(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t);

               for (CupboardCatalog.Node n : cups) {
                  if (n != null && n.id != ignoreId && Math.abs(p.x - n.x) <= 5.5 && Math.abs(p.y - n.y) <= 5.5) {
                     return true;
                  }
               }
            }

            return false;
         }
      } else {
         return false;
      }
   }

   public static List<Coord2d> detour(Coord2d from, Coord2d dest, List<CupboardCatalog.Node> cups) {
      List<List<Coord2d>> opts = detourOptions(from, dest, cups);
      return opts.isEmpty() ? Arrays.asList(from, dest) : opts.get(0);
   }

   public static List<List<Coord2d>> detourOptions(Coord2d from, Coord2d dest, List<CupboardCatalog.Node> cups) {
      List<List<Coord2d>> out = new ArrayList<>();
      if (from != null && dest != null) {
         if (!throughPacked(from, dest, cups, -1L)) {
            out.add(Arrays.asList(from, dest));
            return out;
         } else {
            List<CupboardCatalog.Node> cluster = packedCluster(from, dest, cups);
            if (cluster.isEmpty()) {
               cluster = cups;
            }

            double minx = Double.POSITIVE_INFINITY;
            double maxx = Double.NEGATIVE_INFINITY;
            double miny = Double.POSITIVE_INFINITY;
            double maxy = Double.NEGATIVE_INFINITY;

            for (CupboardCatalog.Node n : cluster) {
               if (n != null) {
                  if (n.x < minx) {
                     minx = n.x;
                  }

                  if (n.x > maxx) {
                     maxx = n.x;
                  }

                  if (n.y < miny) {
                     miny = n.y;
                  }

                  if (n.y > maxy) {
                     maxy = n.y;
                  }
               }
            }

            double nY = miny - 7.5;
            double sY = maxy + 7.5;
            double wX = minx - 7.5;
            double eX = maxx + 7.5;
            List<List<Coord2d>> cands = new ArrayList<>();
            cands.add(Arrays.asList(from, Coord2d.of(from.x, nY), Coord2d.of(dest.x, nY), dest));
            cands.add(Arrays.asList(from, Coord2d.of(from.x, sY), Coord2d.of(dest.x, sY), dest));
            cands.add(Arrays.asList(from, Coord2d.of(wX, from.y), Coord2d.of(wX, dest.y), dest));
            cands.add(Arrays.asList(from, Coord2d.of(eX, from.y), Coord2d.of(eX, dest.y), dest));
            List<List<Coord2d>> clear = new ArrayList<>();
            List<List<Coord2d>> any = new ArrayList<>();

            for (List<Coord2d> route : cands) {
               List<Coord2d> slim = collapse(route);
               any.add(slim);
               if (!routeThroughPacked(slim, cups)) {
                  clear.add(slim);
               }
            }

            List<List<Coord2d>> pick = clear.isEmpty() ? any : clear;
            pick.sort(Comparator.comparingDouble(CupboardCatalog::routeLength));
            return pick;
         }
      } else {
         return out;
      }
   }

   private static List<Coord2d> collapse(List<Coord2d> route) {
      List<Coord2d> out = new ArrayList<>();

      for (Coord2d p : route) {
         if (p != null && (out.isEmpty() || !(out.get(out.size() - 1).dist(p) < 1.0))) {
            out.add(p);
         }
      }

      return out;
   }

   private static boolean routeThroughPacked(List<Coord2d> route, List<CupboardCatalog.Node> cups) {
      for (int i = 1; i < route.size(); i++) {
         if (throughPacked(route.get(i - 1), route.get(i), cups, -1L)) {
            return true;
         }
      }

      return false;
   }

   static List<CupboardCatalog.Node> packedCluster(Coord2d from, Coord2d dest, List<CupboardCatalog.Node> cups) {
      List<CupboardCatalog.Node> out = new ArrayList<>();
      if (from != null && dest != null && cups != null) {
         Set<Long> ids = new HashSet<>();
         double dist = from.dist(dest);
         if (dist >= 1.0) {
            int steps = Math.max(8, (int)Math.ceil(dist / 2.0));

            for (int i = 1; i < steps; i++) {
               double t = (double)i / (double)steps;
               Coord2d p = Coord2d.of(from.x + (dest.x - from.x) * t, from.y + (dest.y - from.y) * t);

               for (CupboardCatalog.Node n : cups) {
                  if (n != null && !ids.contains(n.id) && Math.abs(p.x - n.x) <= 5.5 && Math.abs(p.y - n.y) <= 5.5) {
                     ids.add(n.id);
                  }
               }
            }
         }

         if (ids.isEmpty()) {
            return out;
         } else {
            boolean grew;
            do {
               grew = false;

               for (CupboardCatalog.Node nx : cups) {
                  if (nx != null && !ids.contains(nx.id)) {
                     for (CupboardCatalog.Node o : cups) {
                        if (o != null && ids.contains(o.id) && adjacent(nx, o)) {
                           ids.add(nx.id);
                           grew = true;
                           break;
                        }
                     }
                  }
               }
            } while (grew);

            for (CupboardCatalog.Node nxx : cups) {
               if (nxx != null && ids.contains(nxx.id)) {
                  out.add(nxx);
               }
            }

            return out;
         }
      } else {
         return out;
      }
   }

   static double routeLength(List<Coord2d> route) {
      double n = 0.0;

      for (int i = 1; i < route.size(); i++) {
         n += route.get(i - 1).dist(route.get(i));
      }

      return n;
   }

   public static int faceIndex(Coord2d gob, Coord2d stand) {
      if (gob != null && stand != null) {
         double dx = stand.x - gob.x;
         double dy = stand.y - gob.y;
         if (Math.abs(dx) >= Math.abs(dy)) {
            return dx < 0.0 ? 3 : 1;
         } else {
            return dy < 0.0 ? 0 : 2;
         }
      } else {
         return 1;
      }
   }

   public static int extendAxis(Coord2d gob, Coord2d stand) {
      int face = faceIndex(gob, stand);
      return face != 0 && face != 2 ? 1 : 0;
   }

   private static Coord2d hugIfClear(Coord2d gob, int sx, int sy, double dist, List<CupboardCatalog.Node> cups, long selfId) {
      if (gob == null) {
         return null;
      } else {
         Coord2d stand = Coord2d.of(gob.x + (double)sx * dist, gob.y + (double)sy * dist);
         return !standClear(stand, cups, selfId) ? null : stand;
      }
   }

   private static Coord2d playerFacingStand(Coord2d gob, Coord2d player, List<CupboardCatalog.Node> cups, long selfId, double dist) {
      if (gob != null && player != null) {
         double dx = player.x - gob.x;
         double dy = player.y - gob.y;
         Coord2d stand;
         if (Math.abs(dx) >= Math.abs(dy)) {
            stand = Coord2d.of(gob.x + (dx < 0.0 ? -dist : dist), gob.y);
         } else {
            stand = Coord2d.of(gob.x, gob.y + (dy < 0.0 ? -dist : dist));
         }

         return !standClear(stand, cups, selfId) ? null : stand;
      } else {
         return null;
      }
   }

   public static boolean throughWest(Coord2d gob, Coord2d stand) {
      return gob != null && stand != null && stand.x < gob.x - 0.5;
   }

   public static boolean standInside(Coord2d stand, CupboardCatalog.Node cup) {
      return stand != null && cup != null ? Math.abs(stand.x - cup.x) <= 5.5 && Math.abs(stand.y - cup.y) <= 5.5 : false;
   }

   public static boolean standClear(Coord2d stand, List<CupboardCatalog.Node> cups, long selfId) {
      if (stand == null) {
         return false;
      } else if (cups == null) {
         return true;
      } else {
         for (CupboardCatalog.Node n : cups) {
            if (n != null && n.id != selfId && standInside(stand, n)) {
               return false;
            }
         }

         return true;
      }
   }

   public static boolean isCorner(CupboardCatalog.Node node, List<CupboardCatalog.Node> all) {
      return isCorner(node, all, 14.850000000000001);
   }

   public static boolean isCorner(CupboardCatalog.Node node, List<CupboardCatalog.Node> all, double neighborDist) {
      if (node != null && all != null) {
         boolean eastWest = false;
         boolean northSouth = false;
         double n2 = neighborDist * neighborDist;

         for (CupboardCatalog.Node other : all) {
            if (other != null && other.id != node.id) {
               double dx = other.x - node.x;
               double dy = other.y - node.y;
               if (!(dx * dx + dy * dy > n2)) {
                  if (Math.abs(dx) >= Math.abs(dy)) {
                     eastWest = true;
                  }

                  if (Math.abs(dy) >= Math.abs(dx)) {
                     northSouth = true;
                  }
               }
            }
         }

         return eastWest && northSouth;
      } else {
         return false;
      }
   }

   public static boolean adjacent(CupboardCatalog.Node a, CupboardCatalog.Node b) {
      return a != null && b != null && a.id != b.id && a.dist(b) <= 14.850000000000001;
   }

   public static int viaRank(double fromX, double fromY, double viaX, double viaY) {
      double dx = viaX - fromX;
      double dy = viaY - fromY;
      if (Math.abs(dy) >= Math.abs(dx)) {
         return dy < 0.0 ? 0 : 2;
      } else {
         return dx > 0.0 ? 1 : 3;
      }
   }

   public static CupboardCatalog.Node preferredVia(CupboardCatalog.Node corner, List<CupboardCatalog.Node> aisle) {
      return preferredVia(corner, aisle, null);
   }

   public static CupboardCatalog.Node preferredVia(CupboardCatalog.Node corner, List<CupboardCatalog.Node> aisle, Map<Long, Integer> lateralAxis) {
      return preferredVia(corner, aisle, lateralAxis, null);
   }

   public static CupboardCatalog.Node preferredVia(
      CupboardCatalog.Node corner, List<CupboardCatalog.Node> aisle, Map<Long, Integer> lateralAxis, Set<Long> prefer
   ) {
      return preferredVia(corner, aisle, lateralAxis, prefer, null);
   }

   public static CupboardCatalog.Node preferredVia(
      CupboardCatalog.Node corner, List<CupboardCatalog.Node> aisle, Map<Long, Integer> lateralAxis, Set<Long> prefer, Coord2d player
   ) {
      if (corner != null && aisle != null && !aisle.isEmpty()) {
         CupboardCatalog.Node best = null;
         CupboardCatalog.Node bestPref = null;
         CupboardCatalog.Node bestChain = null;
         CupboardCatalog.Node bestAny = null;
         int bestRank = Integer.MAX_VALUE;
         int bestPrefRank = Integer.MAX_VALUE;
         int bestChainRank = Integer.MAX_VALUE;
         int bestAnyRank = Integer.MAX_VALUE;
         double bestD = Double.POSITIVE_INFINITY;
         double bestPrefD = Double.POSITIVE_INFINITY;
         double bestWalkD = Double.POSITIVE_INFINITY;
         double bestChainD = Double.POSITIVE_INFINITY;
         double bestAnyD = Double.POSITIVE_INFINITY;
         double bestPrefPlayer = Double.POSITIVE_INFINITY;

         for (CupboardCatalog.Node a : aisle) {
            if (a != null && a.id != corner.id && adjacent(corner, a)) {
               Integer axis = lateralAxis == null ? null : lateralAxis.get(a.id);
               boolean along = axis == null || lateral(corner, a, axis);
               int rank = viaRank(corner.x, corner.y, a.x, a.y);
               double d = corner.dist(a);
               double pd = player == null ? d : a.dist(player.x, player.y);
               if (rank < bestAnyRank || rank == bestAnyRank && d < bestAnyD) {
                  bestAny = a;
                  bestAnyRank = rank;
                  bestAnyD = d;
               }

               if (player != null
                  && prefer != null
                  && prefer.contains(a.id)
                  && a.dist(player.x, player.y) < corner.dist(player.x, player.y) - 0.5
                  && (bestPref == null || pd < bestPrefPlayer - 0.5)) {
                  bestPref = a;
                  bestPrefPlayer = pd;
                  bestPrefRank = rank;
                  bestPrefD = d;
               }

               if (along) {
                  if (rank < bestRank || rank == bestRank && d < bestD) {
                     best = a;
                     bestRank = rank;
                     bestD = d;
                  }

                  if (prefer != null
                     && prefer.contains(a.id)
                     && (
                        bestPref == null
                           || pd < bestPrefPlayer - 0.5
                           || Math.abs(pd - bestPrefPlayer) <= 0.5 && (rank < bestPrefRank || rank == bestPrefRank && d < bestPrefD)
                     )) {
                     bestPref = a;
                     bestPrefRank = rank;
                     bestPrefD = d;
                     bestPrefPlayer = pd;
                  }

                  double walkD = distToSet(a, aisle, prefer);
                  if (walkD < bestWalkD - 1.0E-4 || Math.abs(walkD - bestWalkD) <= 1.0E-4 && (rank < bestChainRank || rank == bestChainRank && d < bestChainD)) {
                     bestChain = a;
                     bestWalkD = walkD;
                     bestChainRank = rank;
                     bestChainD = d;
                  }
               }
            }
         }

         if (bestPref == null && prefer != null) {
            for (CupboardCatalog.Node ax : aisle) {
               if (ax != null && ax.id != corner.id && adjacent(corner, ax) && prefer.contains(ax.id)) {
                  double pdx = player == null ? ax.dist(corner) : ax.dist(player.x, player.y);
                  int rankx = viaRank(corner.x, corner.y, ax.x, ax.y);
                  if (bestPref == null || pdx < bestPrefPlayer - 0.5 || Math.abs(pdx - bestPrefPlayer) <= 0.5 && rankx < bestPrefRank) {
                     bestPref = ax;
                     bestPrefPlayer = pdx;
                     bestPrefRank = rankx;
                  }
               }
            }
         }

         if (bestPref != null) {
            return bestPref;
         } else if (prefer != null && !prefer.isEmpty() && bestChain != null) {
            return bestChain;
         } else {
            return best != null ? best : bestAny;
         }
      } else {
         return null;
      }
   }

   private static double distToSet(CupboardCatalog.Node node, List<CupboardCatalog.Node> all, Set<Long> ids) {
      if (node != null && ids != null && !ids.isEmpty() && all != null) {
         double best = Double.POSITIVE_INFINITY;

         for (CupboardCatalog.Node o : all) {
            if (o != null && ids.contains(o.id)) {
               double d = node.dist(o);
               if (d < best) {
                  best = d;
               }
            }
         }

         return best;
      } else {
         return Double.POSITIVE_INFINITY;
      }
   }

   static boolean lateral(CupboardCatalog.Node target, CupboardCatalog.Node via, int axis) {
      return target != null && via != null ? lateral(target.x, target.y, via.x, via.y, axis) : false;
   }

   public static boolean lateral(double tx, double ty, double vx, double vy, int axis) {
      double dx = Math.abs(tx - vx);
      double dy = Math.abs(ty - vy);
      return axis == 0 ? dx >= dy : dy >= dx;
   }

   public static List<CupboardCatalog.Step> tour(double px, double py, List<CupboardCatalog.Node> cups) {
      return tour(px, py, cups, null);
   }

   public static List<CupboardCatalog.Step> tour(double px, double py, List<CupboardCatalog.Node> cups, Set<Long> walkable) {
      return tour(px, py, cups, walkable, null);
   }

   public static List<CupboardCatalog.Step> tour(double px, double py, List<CupboardCatalog.Node> cups, Set<Long> walkable, Map<Long, Integer> lateralAxis) {
      return tour(px, py, cups, walkable, lateralAxis, null);
   }

   public static List<CupboardCatalog.Step> tour(
      double px, double py, List<CupboardCatalog.Node> cups, Set<Long> walkable, Map<Long, Integer> lateralAxis, Map<Long, Integer> faces
   ) {
      return tour(px, py, cups, walkable, lateralAxis, faces, null);
   }

   public static List<CupboardCatalog.Step> tour(
      double px,
      double py,
      List<CupboardCatalog.Node> cups,
      Set<Long> walkable,
      Map<Long, Integer> lateralAxis,
      Map<Long, Integer> faces,
      Map<Long, Coord2d> stands
   ) {
      if (cups != null && !cups.isEmpty()) {
         List<CupboardCatalog.Node> aisle = new ArrayList<>();
         List<CupboardCatalog.Node> corners = new ArrayList<>();

         for (CupboardCatalog.Node n : cups) {
            if (n != null) {
               boolean corner = walkable == null ? isCorner(n, cups) : !walkable.contains(n.id);
               if (corner) {
                  corners.add(n);
               } else {
                  aisle.add(n);
               }
            }
         }

         if (aisle.isEmpty()) {
            return clusterTour(px, py, corners);
         } else {
            Map<Long, Long> viaOf = new HashMap<>();
            Map<Long, List<CupboardCatalog.Node>> byVia = new HashMap<>();
            Map<Long, Integer> axes = lateralAxis == null ? null : new HashMap<>(lateralAxis);
            Set<Long> prefer = new HashSet<>();

            for (CupboardCatalog.Node a : aisle) {
               prefer.add(a.id);
            }

            List<CupboardCatalog.Node> available = new ArrayList<>(aisle);
            List<CupboardCatalog.Node> pending = new ArrayList<>(corners);

            boolean changed;
            do {
               changed = false;
               int i = 0;

               while (i < pending.size()) {
                  CupboardCatalog.Node c = pending.get(i);
                  CupboardCatalog.Node via = preferredVia(c, available, axes, prefer);
                  if (via == null) {
                     i++;
                  } else {
                     viaOf.put(c.id, via.id);
                     byVia.computeIfAbsent(via.id, k -> new ArrayList<>()).add(c);
                     if (axes != null && axes.containsKey(via.id)) {
                        axes.put(c.id, axes.get(via.id));
                     }

                     pending.remove(i);
                     changed = true;
                  }
               }
            } while (changed);

            for (CupboardCatalog.Node c : pending) {
               viaOf.put(c.id, 0L);
            }

            List<CupboardCatalog.Step> out = new ArrayList<>();
            Set<Long> done = new HashSet<>();
            List<CupboardCatalog.Node> remaining = new ArrayList<>(aisle);
            double cx = px;
            double cy = py;
            boolean first = true;

            while (!remaining.isEmpty()) {
               CupboardCatalog.Node seed = first ? canonicalStart(remaining, faces) : nearest(cx, cy, remaining);
               List<CupboardCatalog.Node> corridor = sameAisle(seed, remaining, faces, stands);
               List<CupboardCatalog.Node> ordered = orderAlongAisle(corridor, first ? null : Coord2d.of(cx, cy), faces, stands);
               first = false;

               for (CupboardCatalog.Node next : ordered) {
                  if (remaining.remove(next)) {
                     out.add(new CupboardCatalog.Step(next.id, 0L, false));
                     done.add(next.id);
                     cx = next.x;
                     cy = next.y;
                     appendLateral(next, byVia, out, done);
                  }
               }
            }

            List<CupboardCatalog.Node> leftover = new ArrayList<>();

            for (CupboardCatalog.Node c : corners) {
               if (c != null && !done.contains(c.id)) {
                  leftover.add(c);
               }
            }

            List<CupboardCatalog.Node> emitted = new ArrayList<>();

            for (CupboardCatalog.Step s : out) {
               CupboardCatalog.Node nx = node(cups, s.id);
               if (nx != null) {
                  emitted.add(nx);
               }
            }

            while (!leftover.isEmpty()) {
               CupboardCatalog.Node pick = nearest(cx, cy, leftover);
               leftover.remove(pick);
               CupboardCatalog.Node via = latestAdjacent(pick, emitted, prefer);
               out.add(new CupboardCatalog.Step(pick.id, via == null ? 0L : via.id, true));
               done.add(pick.id);
               emitted.add(pick);
               cx = pick.x;
               cy = pick.y;
            }

            return out;
         }
      } else {
         return Collections.emptyList();
      }
   }

   private static CupboardCatalog.Node latestAdjacent(CupboardCatalog.Node corner, List<CupboardCatalog.Node> emitted, Set<Long> aisleIds) {
      if (corner != null && emitted != null) {
         for (int i = emitted.size() - 1; i >= 0; i--) {
            CupboardCatalog.Node n = emitted.get(i);
            if (adjacent(corner, n) && (aisleIds == null || aisleIds.contains(n.id))) {
               return n;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private static void appendLateral(CupboardCatalog.Node via, Map<Long, List<CupboardCatalog.Node>> byVia, List<CupboardCatalog.Step> out, Set<Long> done) {
      List<CupboardCatalog.Node> mine = new ArrayList<>(byVia.getOrDefault(via.id, Collections.emptyList()));
      mine.sort(Comparator.<CupboardCatalog.Node>comparingDouble(via::dist).thenComparingLong(n -> n.id));

      for (CupboardCatalog.Node c : mine) {
         if (!done.contains(c.id)) {
            out.add(new CupboardCatalog.Step(c.id, via.id, true));
            done.add(c.id);
         }
      }
   }

   private static CupboardCatalog.Node canonicalStart(List<CupboardCatalog.Node> nodes, Map<Long, Integer> faces) {
      if (nodes != null && !nodes.isEmpty()) {
         int[] order = new int[]{3, 2, 1, 0};
         if (faces != null) {
            for (int face : order) {
               List<CupboardCatalog.Node> same = new ArrayList<>();

               for (CupboardCatalog.Node n : nodes) {
                  if (n != null && Integer.valueOf(face).equals(faces.get(n.id))) {
                     same.add(n);
                  }
               }

               if (!same.isEmpty()) {
                  return northWest(same);
               }
            }
         }

         return northWest(nodes);
      } else {
         return null;
      }
   }

   private static CupboardCatalog.Node northWest(List<CupboardCatalog.Node> nodes) {
      CupboardCatalog.Node best = null;

      for (CupboardCatalog.Node n : nodes) {
         if (n != null
            && (
               best == null
                  || n.y < best.y - 1.0E-4
                  || Math.abs(n.y - best.y) <= 1.0E-4 && n.x < best.x - 1.0E-4
                  || Math.abs(n.y - best.y) <= 1.0E-4 && Math.abs(n.x - best.x) <= 1.0E-4 && n.id < best.id
            )) {
            best = n;
         }
      }

      return best;
   }

   private static List<CupboardCatalog.Node> sameAisle(
      CupboardCatalog.Node seed, List<CupboardCatalog.Node> nodes, Map<Long, Integer> faces, Map<Long, Coord2d> stands
   ) {
      List<CupboardCatalog.Node> out = new ArrayList<>();
      if (seed == null) {
         return out;
      } else {
         String key = aisleKey(seed, faces, stands);

         for (CupboardCatalog.Node n : nodes) {
            if (n != null && key.equals(aisleKey(n, faces, stands))) {
               out.add(n);
            }
         }

         if (out.isEmpty()) {
            out.add(seed);
         }

         return out;
      }
   }

   private static List<CupboardCatalog.Node> orderAlongAisle(
      List<CupboardCatalog.Node> corridor, Coord2d from, Map<Long, Integer> faces, Map<Long, Coord2d> stands
   ) {
      List<CupboardCatalog.Node> out = new ArrayList<>(corridor);
      if (out.size() <= 1) {
         return out;
      } else {
         boolean vertical = verticalAisle(out.get(0), faces);
         if (vertical) {
            out.sort(Comparator.<CupboardCatalog.Node>comparingDouble(n -> n.y).thenComparingDouble(n -> n.x).thenComparingLong(n -> n.id));
         } else {
            out.sort(Comparator.<CupboardCatalog.Node>comparingDouble(n -> n.x).thenComparingDouble(n -> n.y).thenComparingLong(n -> n.id));
         }

         if (from != null) {
            CupboardCatalog.Node a = out.get(0);
            CupboardCatalog.Node b = out.get(out.size() - 1);
            if (from.dist(Coord2d.of(b.x, b.y)) + 1.0E-4 < from.dist(Coord2d.of(a.x, a.y))) {
               Collections.reverse(out);
            }
         }

         return out;
      }
   }

   private static boolean verticalAisle(CupboardCatalog.Node n, Map<Long, Integer> faces) {
      if (n != null && faces != null && faces.containsKey(n.id)) {
         int f = faces.get(n.id);
         return f == 1 || f == 3;
      } else {
         return true;
      }
   }

   static String aisleKey(CupboardCatalog.Node n, Map<Long, Integer> faces, Map<Long, Coord2d> stands) {
      if (n == null) {
         return "";
      } else {
         Coord2d st = standOf(n, faces, stands);
         return verticalAisle(n, faces) ? "V:" + Math.round(st.x / 11.0) : "H:" + Math.round(st.y / 11.0);
      }
   }

   private static Coord2d standOf(CupboardCatalog.Node n, Map<Long, Integer> faces, Map<Long, Coord2d> stands) {
      if (n == null) {
         return Coord2d.of(0.0, 0.0);
      } else {
         if (stands != null) {
            Coord2d s = stands.get(n.id);
            if (s != null) {
               return s;
            }
         }

         int f = faces != null && faces.containsKey(n.id) ? faces.get(n.id) : 3;
         if (f == 0) {
            return Coord2d.of(n.x, n.y - 7.5);
         } else if (f == 1) {
            return Coord2d.of(n.x + 7.5, n.y);
         } else {
            return f == 2 ? Coord2d.of(n.x, n.y + 7.5) : Coord2d.of(n.x - 7.5, n.y);
         }
      }
   }

   private static List<CupboardCatalog.Step> clusterTour(double px, double py, List<CupboardCatalog.Node> cups) {
      List<CupboardCatalog.Step> out = new ArrayList<>();
      if (cups.isEmpty()) {
         return out;
      } else {
         List<CupboardCatalog.Node> rest = new ArrayList<>(cups);
         CupboardCatalog.Node first = nearest(px, py, rest);
         rest.remove(first);
         out.add(new CupboardCatalog.Step(first.id, 0L, true));
         rest.sort(Comparator.comparingDouble(first::dist));

         for (CupboardCatalog.Node n : rest) {
            out.add(new CupboardCatalog.Step(n.id, first.id, true));
         }

         return out;
      }
   }

   public static CupboardCatalog.Node nearest(double x, double y, List<CupboardCatalog.Node> nodes) {
      CupboardCatalog.Node best = null;
      double bestD = Double.POSITIVE_INFINITY;

      for (CupboardCatalog.Node n : nodes) {
         if (n != null) {
            double d = n.dist(x, y);
            if (d < bestD - 1.0E-4 || Math.abs(d - bestD) <= 1.0E-4 && before(n, best)) {
               bestD = d;
               best = n;
            }
         }
      }

      return best;
   }

   private static boolean before(CupboardCatalog.Node a, CupboardCatalog.Node b) {
      if (b == null) {
         return true;
      } else {
         int cy = Double.compare(a.y, b.y);
         if (cy != 0) {
            return cy < 0;
         } else {
            int cx = Double.compare(a.x, b.x);
            return cx != 0 ? cx < 0 : a.id < b.id;
         }
      }
   }

   public static final class Entry {
      public final String name;
      public final double quality;
      public final double amount;
      public final Integer lp;
      public final Integer lph;

      public Entry(String name, double quality, double amount, Integer lp, Integer lph) {
         this.name = name == null ? "" : name;
         this.quality = quality;
         this.amount = amount;
         this.lp = lp;
         this.lph = lph;
      }

      public CupboardCatalog.Entry withAmount(double amount) {
         return new CupboardCatalog.Entry(this.name, this.quality, amount, this.lp, this.lph);
      }

      String mergeKey() {
         return this.name + "|" + Math.round(this.quality * 10.0) + "|" + this.lp + "|" + this.lph;
      }
   }

   public static final class Node {
      public final long id;
      public final double x;
      public final double y;

      public Node(long id, double x, double y) {
         this.id = id;
         this.x = x;
         this.y = y;
      }

      public double dist(CupboardCatalog.Node o) {
         return this.dist(o.x, o.y);
      }

      public double dist(double ox, double oy) {
         double dx = ox - this.x;
         double dy = oy - this.y;
         return Math.sqrt(dx * dx + dy * dy);
      }
   }

   public static final class Step {
      public final long id;
      public final long via;
      public final boolean corner;

      public Step(long id, long via, boolean corner) {
         this.id = id;
         this.via = via;
         this.corner = corner;
      }
   }
}
