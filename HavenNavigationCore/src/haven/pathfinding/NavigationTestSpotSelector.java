package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class NavigationTestSpotSelector {
   public static final double CELL = 2.75;
   public static final int MAX_OPENNESS = 8;
   static final double OPENNESS_WEIGHT = 1000.0;
   public static final int DEFAULT_MAX_EXPANDED = 100000;
   public static final int DEFAULT_MAX_TOTAL_EXPANDED = 2000000;
   public static final double MIN_DETOUR_RATIO = 1.05;

   private NavigationTestSpotSelector() {
   }

   public static NavigationTestSpotSelector.Selection openGround(OccupancyView scene) {
      return select(scene, NavigationTestSpotSelector.Profile.OPEN_GROUND, NavigationTestSpotSelector.SpotRange.openGround());
   }

   public static NavigationTestSpotSelector.Selection openGround(OccupancyView scene, NavigationTestSpotSelector.SpotRange range) {
      return select(scene, NavigationTestSpotSelector.Profile.OPEN_GROUND, range);
   }

   public static NavigationTestSpotSelector.Selection localObstacleOrCorridor(OccupancyView scene) {
      return select(scene, NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR, NavigationTestSpotSelector.SpotRange.obstacleCorridor());
   }

   public static NavigationTestSpotSelector.Selection localObstacleOrCorridor(
      OccupancyView scene, NavigationTestSpotSelector.SpotRange range, int maxExpanded
   ) {
      return select(scene, NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR, range, maxExpanded);
   }

   public static NavigationTestSpotSelector.Selection knownMapLongLeg(CoarseTileSource src, long segment, Coord start, double minTiles, double maxTiles) {
      return knownMapLongLeg(src, segment, start, minTiles, maxTiles, 1000000, 2000000);
   }

   public static NavigationTestSpotSelector.Selection knownMapLongLeg(
      CoarseTileSource src, long segment, Coord start, double minTiles, double maxTiles, int maxExpanded
   ) {
      return knownMapLongLeg(src, segment, start, minTiles, maxTiles, maxExpanded, 2000000);
   }

   public static NavigationTestSpotSelector.Selection knownMapLongLeg(
      CoarseTileSource src, long segment, Coord start, double minTiles, double maxTiles, int maxExpanded, int maxTotalExpanded
   ) {
      NavigationTestSpotSelector.Profile profile = NavigationTestSpotSelector.Profile.KNOWN_MAP_LONG_LEG;
      if (src == null) {
         return refuse(profile, NavigationTestSpotSelector.Refusal.NO_SOURCE, "no coarse tile source injected");
      } else if (!Double.isNaN(minTiles) && !Double.isNaN(maxTiles) && !(minTiles < 0.0) && !(maxTiles < minTiles)) {
         int w = src.width();
         int h = src.height();
         if (start != null && start.x >= 0 && start.y >= 0 && start.x < w && start.y < h) {
            if (src.tile(start.x, start.y) != CoarseTileSource.Tile.FREE) {
               return refuse(
                  profile,
                  NavigationTestSpotSelector.Refusal.START_UNKNOWN,
                  String.format("start tile %s is not known-free (%s)", start, src.tile(start.x, start.y))
               );
            } else if (maxTotalExpanded <= 0) {
               return refuse(
                  profile,
                  NavigationTestSpotSelector.Refusal.BUDGET_EXHAUSTED,
                  String.format("aggregate coarse route-search budget %d expansions — no route search permitted", maxTotalExpanded)
               );
            } else {
               List<NavigationTestSpotSelector.Candidate> cands = new ArrayList<>();
               int scanned = 0;
               int inBand = 0;
               int searched = 0;
               int totalExpanded = 0;

               for (int y = 0; y < h; y++) {
                  for (int x = 0; x < w; x++) {
                     scanned++;
                     if (src.tile(x, y) == CoarseTileSource.Tile.FREE) {
                        double dist = distTiles(start, Coord.of(x, y));
                        if (!(dist < minTiles) && !(dist > maxTiles)) {
                           inBand++;
                           CoarseRoutePlanner.Route r = CoarseRoutePlanner.plan(src, start, Coord.of(x, y), maxExpanded);
                           searched++;
                           totalExpanded += r.expanded;
                           if (totalExpanded > maxTotalExpanded) {
                              return refuse(
                                 profile,
                                 NavigationTestSpotSelector.Refusal.BUDGET_EXHAUSTED,
                                 String.format(
                                    "scanned %d tiles: %d known-free tiles in band %.1f..%.1f tiles from start %s; aggregate coarse route-search budget %d expansions exceeded after %d in-band candidate route searches (%d expansions total, per-candidate cap %d); refusing rather than continuing unbounded aggregate search",
                                    scanned,
                                    inBand,
                                    minTiles,
                                    maxTiles,
                                    start,
                                    maxTotalExpanded,
                                    searched,
                                    totalExpanded,
                                    maxExpanded
                                 )
                              );
                           }

                           if (r.status == CoarseRoutePlanner.Status.REACHED) {
                              cands.add(
                                 new NavigationTestSpotSelector.Candidate(
                                    Coord.of(x, y),
                                    null,
                                    dist,
                                    String.format("%.1f tiles from start, coarse route %d waypoints / %d expanded", dist, r.waypoints.size(), r.expanded),
                                    r.waypoints
                                 )
                              );
                           }
                        }
                     }
                  }
               }

               if (!cands.isEmpty()) {
                  sortCandidates(cands);
                  NavigationTestSpotSelector.Candidate best = cands.get(0);
                  return new NavigationTestSpotSelector.Selection(
                     profile,
                     NavigationTestSpotSelector.Status.SELECTED,
                     null,
                     null,
                     best.tile,
                     segment,
                     cands,
                     String.format(
                        "KNOWN_MAP_LONG_LEG: selected tile %s (segment %x): %s; %d reachable candidates (best first) of %d tiles scanned",
                        best.tile,
                        segment,
                        best.note,
                        cands.size(),
                        scanned
                     )
                  );
               } else {
                  return inBand == 0
                     ? refuse(
                        profile,
                        NavigationTestSpotSelector.Refusal.NO_CANDIDATE,
                        String.format("scanned %d tiles: 0 known-free tiles in band %.1f..%.1f tiles from start %s", scanned, minTiles, maxTiles, start)
                     )
                     : refuse(
                        profile,
                        NavigationTestSpotSelector.Refusal.NO_KNOWN_ROUTE,
                        String.format("scanned %d tiles: %d known-free tiles in band, 0 with a known coarse route from %s", scanned, inBand, start)
                     );
               }
            }
         } else {
            return refuse(profile, NavigationTestSpotSelector.Refusal.START_UNKNOWN, String.format("start tile %s outside source %dx%d", start, w, h));
         }
      } else {
         return refuse(
            profile, NavigationTestSpotSelector.Refusal.BOUNDS_INVALID, String.format("distance band %.1f..%.1f tiles is invalid", minTiles, maxTiles)
         );
      }
   }

   public static NavigationTestSpotSelector.Selection select(
      OccupancyView scene, NavigationTestSpotSelector.Profile profile, NavigationTestSpotSelector.SpotRange range
   ) {
      return select(scene, profile, range, 100000);
   }

   public static NavigationTestSpotSelector.Selection select(
      OccupancyView scene, NavigationTestSpotSelector.Profile profile, NavigationTestSpotSelector.SpotRange range, int maxExpanded
   ) {
      Objects.requireNonNull(profile, "profile");
      if (!profile.supported) {
         return refuse(
            profile, NavigationTestSpotSelector.Refusal.UNSUPPORTED_PROFILE, String.format("%s (%s) — no selection is attempted", profile, profile.interaction)
         );
      } else if (scene == null) {
         return refuse(profile, NavigationTestSpotSelector.Refusal.NO_SCENE, "no scene injected");
      } else if (scene.occupancy() != null && scene.origin() != null && scene.width() > 0 && scene.height() > 0) {
         byte[] occ = scene.occupancy();
         if (occ == null || occ.length < scene.width() * scene.height()) {
            return refuse(
               profile,
               NavigationTestSpotSelector.Refusal.NO_OCCUPANCY,
               String.format(
                  "occupancy lattice is %d cells but the grid is %dx%d = %d cells", occ == null ? 0 : occ.length, scene.width(), scene.height(), scene.width() * scene.height()
               )
            );
         } else if (scene.player() == null) {
            return refuse(
               profile, NavigationTestSpotSelector.Refusal.PLAYER_UNKNOWN, "scene has no player anchor (the local profiles are anchored at the player)"
            );
         } else if (range != null
            && !Double.isNaN(range.minDist)
            && !Double.isNaN(range.maxDist)
            && !(range.minDist < 0.0)
            && range.maxDist > range.minDist
            && range.minClearance >= 0) {
            Coord start = scene.cellOf(scene.player());
            if (start != null && start.x >= 0 && start.y >= 0 && start.x < scene.width() && start.y < scene.height()) {
               switch (profile) {
                  case OPEN_GROUND:
                     return openGroundCore(scene, range, start);
                  case LOCAL_OBSTACLE_OR_CORRIDOR:
                     return obstacleCore(scene, range, start, maxExpanded);
                  default:
                     throw new AssertionError(profile);
               }
            } else {
               return refuse(
                  profile,
                  NavigationTestSpotSelector.Refusal.PLAYER_OFF_GRID,
                  String.format("player %s outside the %dx%d occupancy grid", scene.player(), scene.width(), scene.height())
               );
            }
         } else {
            return refuse(
               profile,
               NavigationTestSpotSelector.Refusal.BOUNDS_INVALID,
               String.format("range must satisfy 0 <= minDist < maxDist (got %s) and minClearance >= 0", range)
            );
         }
      } else {
         return refuse(profile, NavigationTestSpotSelector.Refusal.NO_OCCUPANCY, "scene has no usable occupancy lattice (origin/w/h/occupancy)");
      }
   }

   private static NavigationTestSpotSelector.Selection openGroundCore(OccupancyView scene, NavigationTestSpotSelector.SpotRange range, Coord start) {
      int w = scene.width();
      int h = scene.height();
      int[] open = openness(scene);
      List<NavigationTestSpotSelector.Candidate> cands = new ArrayList<>();
      int scanned = 0;
      int inRange = 0;

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            scanned++;
            int idx = y * w + x;
            if (!isBodyBlocked(scene, idx)) {
               Coord2d center = cellCenter(scene, x, y);
               double dist = scene.player().dist(center);
               if (!(dist < range.minDist) && !(dist > range.maxDist)) {
                  inRange++;
                  int oc = Math.min(open[idx], 8);
                  if (oc >= range.minClearance) {
                     cands.add(
                        new NavigationTestSpotSelector.Candidate(
                           Coord.of(x, y), center, (double)oc * 1000.0 + dist, String.format("openness %d, %.1fu from player", oc, dist)
                        )
                     );
                  }
               }
            }
         }
      }

      if (cands.isEmpty()) {
         return refuse(
            NavigationTestSpotSelector.Profile.OPEN_GROUND,
            NavigationTestSpotSelector.Refusal.NO_CANDIDATE,
            String.format(
               "scanned %d cells: %d body-free in range %.1f..%.1fu, 0 with clearance >= %d",
               scanned,
               inRange,
               range.minDist,
               range.maxDist,
               range.minClearance
            )
         );
      } else {
         sortCandidates(cands);
         NavigationTestSpotSelector.Candidate best = cands.get(0);
         return new NavigationTestSpotSelector.Selection(
            NavigationTestSpotSelector.Profile.OPEN_GROUND,
            NavigationTestSpotSelector.Status.SELECTED,
            null,
            best.world,
            best.tile,
            0L,
            cands,
            String.format(
               "OPEN_GROUND: selected cell %s world (%.1f, %.1f): %s; %d candidates (best first) of %d cells scanned",
               best.tile,
               best.world.x,
               best.world.y,
               best.note,
               cands.size(),
               scanned
            )
         );
      }
   }

   private static NavigationTestSpotSelector.Selection obstacleCore(
      OccupancyView scene, NavigationTestSpotSelector.SpotRange range, Coord start, int maxExpanded
   ) {
      int w = scene.width();
      int h = scene.height();
      int[] open = openness(scene);
      List<NavigationTestSpotSelector.Candidate> cands = new ArrayList<>();
      int scanned = 0;
      int inRange = 0;
      int occluded = 0;
      int reachable = 0;

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            scanned++;
            int idx = y * w + x;
            if (!isBodyBlocked(scene, idx)) {
               Coord2d center = cellCenter(scene, x, y);
               double dist = scene.player().dist(center);
               if (!(dist < range.minDist) && !(dist > range.maxDist)) {
                  inRange++;
                  Coord cell = Coord.of(x, y);
                  if (lineOccluded(scene, start, cell)) {
                     occluded++;
                     GridAStar.Result r = reachable(scene, start, cell, maxExpanded);
                     if (r.complete && !r.cells.isEmpty()) {
                        reachable++;
                        double route = routeLengthWorld(scene, r.cells);
                        if (!(route <= dist * 1.05)) {
                           int oc = Math.min(open[idx], 8);
                           cands.add(
                              new NavigationTestSpotSelector.Candidate(
                                 cell,
                                 center,
                                 dist * 1000.0 + (double)oc,
                                 String.format(
                                    "occluded (route %d cells / %d expanded, %.1fu vs direct %.1fu, detour ratio %.2f), %.1fu from player",
                                    r.cells.size(),
                                    r.expanded,
                                    route,
                                    dist,
                                    route / dist,
                                    dist
                                 ),
                                 r.cells,
                                 r.expanded
                              )
                           );
                        }
                     }
                  }
               }
            }
         }
      }

      if (cands.isEmpty()) {
         String why;
         if (occluded == 0) {
            why = String.format("scanned %d cells: %d body-free in range, 0 with an occluded direct line (every candidate is LOS-clear)", scanned, inRange);
         } else {
            if (reachable != 0) {
               return refuse(
                  NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR,
                  NavigationTestSpotSelector.Refusal.NO_MATERIAL_DETOUR,
                  String.format(
                     "scanned %d cells: %d body-free in range, %d with an occluded direct line, %d with a verified route, 0 a material detour (route polyline must exceed %.2f&times; the direct distance, the same ratio the move_to_auto_obstacle_corridor consumer revalidates with)",
                     scanned,
                     inRange,
                     occluded,
                     reachable,
                     1.05
                  )
               );
            }

            why = String.format(
               "scanned %d cells: %d body-free in range, %d with an occluded direct line, 0 reachable by a bounded occupancy route", scanned, inRange, occluded
            );
         }

         return refuse(NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR, NavigationTestSpotSelector.Refusal.NO_CANDIDATE, why);
      } else {
         sortCandidates(cands);
         NavigationTestSpotSelector.Candidate best = cands.get(0);
         return new NavigationTestSpotSelector.Selection(
            NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR,
            NavigationTestSpotSelector.Status.SELECTED,
            null,
            best.world,
            best.tile,
            0L,
            cands,
            String.format(
               "LOCAL_OBSTACLE_OR_CORRIDOR: selected cell %s world (%.1f, %.1f): %s; %d material-detour candidates (best first) of %d cells scanned",
               best.tile,
               best.world.x,
               best.world.y,
               best.note,
               cands.size(),
               scanned
            )
         );
      }
   }

   private static boolean isBodyBlocked(OccupancyView scene, int idx) {
      byte[] occ = scene.occupancy();
      if (idx >= 0 && idx < occ.length) {
         byte v = occ[idx];
         return v == 1 || v == 2;
      } else {
         return true;
      }
   }

   private static Coord2d cellCenter(OccupancyView scene, int x, int y) {
      return Coord2d.of(scene.origin().x + ((double)x + 0.5) * scene.cell(), scene.origin().y + ((double)y + 0.5) * scene.cell());
   }

   static int[] openness(OccupancyView scene) {
      int w = scene.width();
      int h = scene.height();
      byte[] occ = scene.occupancy();
      int[] dist = new int[w * h];
      Arrays.fill(dist, 1073741823);
      ArrayDeque<Integer> q = new ArrayDeque<>();
      int n = Math.min(w * h, occ.length);

      for (int i = 0; i < n; i++) {
         byte v = occ[i];
         if (v == 1 || v == 2) {
            dist[i] = 0;
            q.add(i);
         }
      }

      while (!q.isEmpty()) {
         int cur = q.poll();
         if (dist[cur] < 8) {
            int cx = cur % w;
            int cy = cur / w;

            for (int dy = -1; dy <= 1; dy++) {
               for (int dx = -1; dx <= 1; dx++) {
                  if (dx != 0 || dy != 0) {
                     int nx = cx + dx;
                     int ny = cy + dy;
                     if (nx >= 0 && ny >= 0 && nx < w && ny < h) {
                        int ni = ny * w + nx;
                        if (dist[cur] + 1 < dist[ni]) {
                           dist[ni] = dist[cur] + 1;
                           q.add(ni);
                        }
                     }
                  }
               }
            }
         }
      }

      for (int ix = 0; ix < dist.length; ix++) {
         if (dist[ix] > 8) {
            dist[ix] = 8;
         }
      }

      return dist;
   }

   static boolean lineOccluded(OccupancyView scene, Coord from, Coord to) {
      int w = scene.width();
      int h = scene.height();
      byte[] occ = scene.occupancy();
      Coord2d a = cellCenter(scene, from.x, from.y);
      Coord2d b = cellCenter(scene, to.x, to.y);
      double dx = b.x - a.x;
      double dy = b.y - a.y;
      double len = Math.sqrt(dx * dx + dy * dy);
      int steps = Math.max(2, (int)Math.ceil(len / (scene.cell() * 0.25)));

      for (int i = 1; i <= steps; i++) {
         double t = (double)i / (double)steps;
         Coord c = scene.cellOf(Coord2d.of(a.x + dx * t, a.y + dy * t));
         if (c != null && (c.x != from.x || c.y != from.y)) {
            if (c.x < 0 || c.y < 0 || c.x >= w || c.y >= h) {
               return true;
            }

            byte v = occ[c.y * w + c.x];
            if (v == 1 || v == 2) {
               return true;
            }
         }
      }

      return false;
   }

   static GridAStar.Result reachable(OccupancyView scene, Coord start, Coord goal, int maxExpanded) {
      int w = scene.width();
      int h = scene.height();
      byte[] occ = scene.occupancy();
      final boolean[] blocked = new boolean[w * h];
      int n = Math.min(w * h, occ.length);

      for (int i = 0; i < n; i++) {
         blocked[i] = occ[i] == 1 || occ[i] == 2;
      }

      if (start.x >= 0 && start.y >= 0 && start.x < w && start.y < h && blocked[start.y * w + start.x]) {
         int dil = Math.max(1, (int)Math.ceil(Math.max(scene.radius(), 0.0) / Math.max(scene.cell(), 1.0E-9)));
         LocalPlanner.openFootprint(blocked, w, h, start.x, start.y, dil);
         if (blocked[start.y * w + start.x]) {
            LocalPlanner.openStartPocket(blocked, w, h, start.x, start.y, dil + 1);
         }
      }

      final int fw = w;
      final int fh = h;
      GridAStar.Grid grid = new GridAStar.Grid() {
         @Override
         public int width() {
            return fw;
         }

         @Override
         public int height() {
            return fh;
         }

         @Override
         public boolean blocked(int x, int y) {
            return blocked[y * fw + x];
         }
      };
      return GridAStar.find(grid, start, goal, maxExpanded);
   }

   static double routeLengthWorld(OccupancyView scene, List<Coord> cells) {
      if (cells != null && cells.size() >= 2) {
         double len = 0.0;
         Coord prev = cells.get(0);

         for (int i = 1; i < cells.size(); i++) {
            Coord c = cells.get(i);
            Coord2d a = cellCenter(scene, prev.x, prev.y);
            Coord2d b = cellCenter(scene, c.x, c.y);
            len += a.dist(b);
            prev = c;
         }

         return len;
      } else {
         return 0.0;
      }
   }

   private static double distTiles(Coord a, Coord b) {
      double dx = (double)(a.x - b.x);
      double dy = (double)(a.y - b.y);
      return Math.sqrt(dx * dx + dy * dy);
   }

   private static void sortCandidates(List<NavigationTestSpotSelector.Candidate> cands) {
      cands.sort((a, b) -> {
         int c = Double.compare(b.score, a.score);
         if (c != 0) {
            return c;
         } else {
            c = Integer.compare(a.tile.y, b.tile.y);
            return c != 0 ? c : Integer.compare(a.tile.x, b.tile.x);
         }
      });
   }

   private static NavigationTestSpotSelector.Selection refuse(
      NavigationTestSpotSelector.Profile profile, NavigationTestSpotSelector.Refusal refusal, String why
   ) {
      return new NavigationTestSpotSelector.Selection(
         profile,
         NavigationTestSpotSelector.Status.REFUSED,
         refusal,
         null,
         null,
         0L,
         Collections.emptyList(),
         String.format("%s refused: %s — %s", profile, refusal, why)
      );
   }

   public static final class Candidate {
      public final Coord tile;
      public final Coord2d world;
      public final double score;
      public final String note;
      public final List<Coord> route;
      public final int routeExpanded;

      private Candidate(Coord tile, Coord2d world, double score, String note, List<Coord> route, int routeExpanded) {
         this.tile = Objects.requireNonNull(tile, "tile");
         this.world = world;
         this.score = score;
         this.note = note;
         this.route = route == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(route));
         this.routeExpanded = routeExpanded;
      }

      private Candidate(Coord tile, Coord2d world, double score, String note, List<Coord> route) {
         this(tile, world, score, note, route, 0);
      }

      private Candidate(Coord tile, Coord2d world, double score, String note) {
         this(tile, world, score, note, null, 0);
      }

      @Override
      public String toString() {
         return String.format(
            "Candidate[tile=%s, world=%s, score=%.1f, %s]",
            this.tile,
            this.world == null ? "-" : String.format("(%.1f, %.1f)", this.world.x, this.world.y),
            this.score,
            this.note
         );
      }
   }

   public static enum Interaction {
      NO_INTERACTION,
      TRANSITION_FUTURE,
      NOT_SAFELY_INFERABLE;
   }

   public static enum Profile {
      OPEN_GROUND(true, NavigationTestSpotSelector.Interaction.NO_INTERACTION, "clear body-free local destination (no obstacle on the direct line)"),
      LOCAL_OBSTACLE_OR_CORRIDOR(
         true,
         NavigationTestSpotSelector.Interaction.NO_INTERACTION,
         "occluded direct line with a verified material-detour occupancy route (obstacle detour or corridor)"
      ),
      KNOWN_MAP_LONG_LEG(true, NavigationTestSpotSelector.Interaction.NO_INTERACTION, "known explored coarse-map target with a verified coarse route"),
      TRANSITION_APPROACH(
         false, NavigationTestSpotSelector.Interaction.TRANSITION_FUTURE, "declared future — transition approaches (doors, caves, boats) are a later slice"
      ),
      CART_OR_PROPERTY(
         false,
         NavigationTestSpotSelector.Interaction.NOT_SAFELY_INFERABLE,
         "never safely inferable — cart/property ownership and interaction safety cannot be proven from occupancy"
      );

      public final boolean supported;
      public final NavigationTestSpotSelector.Interaction interaction;
      public final String description;

      private Profile(boolean supported, NavigationTestSpotSelector.Interaction interaction, String description) {
         this.supported = supported;
         this.interaction = interaction;
         this.description = description;
      }
   }

   public static enum Refusal {
      UNSUPPORTED_PROFILE,
      NO_SOURCE,
      NO_SCENE,
      NO_OCCUPANCY,
      PLAYER_UNKNOWN,
      PLAYER_OFF_GRID,
      BOUNDS_INVALID,
      NO_CANDIDATE,
      NO_MATERIAL_DETOUR,
      START_UNKNOWN,
      NO_KNOWN_ROUTE,
      BUDGET_EXHAUSTED;
   }

   public static final class Selection {
      public final NavigationTestSpotSelector.Profile profile;
      public final NavigationTestSpotSelector.Status status;
      public final NavigationTestSpotSelector.Refusal refusal;
      public final Coord2d targetWorld;
      public final Coord targetTile;
      public final long segment;
      public final List<NavigationTestSpotSelector.Candidate> candidates;
      public final String evidence;

      private Selection(
         NavigationTestSpotSelector.Profile profile,
         NavigationTestSpotSelector.Status status,
         NavigationTestSpotSelector.Refusal refusal,
         Coord2d targetWorld,
         Coord targetTile,
         long segment,
         List<NavigationTestSpotSelector.Candidate> candidates,
         String evidence
      ) {
         this.profile = profile;
         this.status = status;
         this.refusal = refusal;
         this.targetWorld = targetWorld;
         this.targetTile = targetTile;
         this.segment = segment;
         this.candidates = candidates == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(candidates));
         this.evidence = evidence;
      }

      public boolean selected() {
         return this.status == NavigationTestSpotSelector.Status.SELECTED;
      }

      public boolean refused() {
         return this.status == NavigationTestSpotSelector.Status.REFUSED;
      }

      @Override
      public String toString() {
         return this.status == NavigationTestSpotSelector.Status.SELECTED
            ? String.format(
               "Selection[%s SELECTED target=%s%s, %d candidates]",
               this.profile,
               this.targetTile,
               this.targetWorld == null ? "" : String.format(" world=(%.1f, %.1f)", this.targetWorld.x, this.targetWorld.y),
               this.candidates.size()
            )
            : String.format("Selection[%s REFUSED %s]", this.profile, this.refusal);
      }
   }

   public static final class SpotRange {
      public final double minDist;
      public final double maxDist;
      public final int minClearance;

      public SpotRange(double minDist, double maxDist, int minClearance) {
         this.minDist = minDist;
         this.maxDist = maxDist;
         this.minClearance = minClearance;
      }

      public static NavigationTestSpotSelector.SpotRange openGround() {
         return new NavigationTestSpotSelector.SpotRange(11.0, 41.25, 1);
      }

      public static NavigationTestSpotSelector.SpotRange obstacleCorridor() {
         return new NavigationTestSpotSelector.SpotRange(11.0, 41.25, 0);
      }

      @Override
      public String toString() {
         return String.format("SpotRange[%.1f..%.1fu, clearance >= %d]", this.minDist, this.maxDist, this.minClearance);
      }
   }

   public static enum Status {
      SELECTED,
      REFUSED;
   }
}
