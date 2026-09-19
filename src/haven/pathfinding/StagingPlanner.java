package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.InteractionSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stage 1 of distant-object interaction. Detects when a target sits outside
 * the reliable local interaction-planning range and picks a deterministic,
 * walkable staging coordinate beside it so fresh geometry can be observed
 * before the final pose is selected.
 *
 * <p>The target object's center is never a staging destination: every
 * candidate lies on a ray/arc around the target at a standoff outside the
 * target's expanded collision region, on a walkable occupancy cell.</p>
 */
public final class StagingPlanner {
   /** Bounded failure reason: no walkable, reachable staging coordinate exists. */
   public static final String STAGING_UNREACHABLE = "STAGING_UNREACHABLE";
   /** Slack beyond spec.maxDist before staging is considered required. */
   public static final double PLANNING_SLACK = LocalPlanner.CELL * 4.0;
   /** Safety margin added on top of bounding radius + player radius. */
   static final double SAFETY_MARGIN = 1.0;
   static final double CELL = 2.75;
   /** Cap the number of telemetry candidates. */
   static final int MAX_CONSIDERED = 64;

   private static final double[] ANGLES = new double[] {
      0.0, 15.0, -15.0, 30.0, -30.0, 45.0, -45.0, 60.0, -60.0,
      75.0, -75.0, 90.0, -90.0, 105.0, -105.0, 120.0, -120.0,
      135.0, -135.0, 150.0, -150.0, 165.0, -165.0, 180.0
   };
   private static final double[] EDGE_FRACS = new double[] {0.9, 0.8, 0.7, 0.6, 0.5};

   private StagingPlanner() {
   }

   /**
    * True when the final interaction pose cannot be selected reliably from
    * the current scene: the target lies outside the observed occupancy grid,
    * or beyond the normal local interaction-planning distance (maxDist plus
    * slack). Missing distant geometry alone does not prove no pose exists —
    * it routes through staging for a refresh instead of failing outright.
    */
   public static boolean required(Coord2d player, OccupancyGrid occ, InteractionSpec spec) {
      if (player == null || spec == null || spec.origin == null) {
         return false;
      }
      if (occ == null || occ.occ == null) {
         return true;
      }
      if (!containsTarget(occ, spec)) {
         return true;
      }
      return player.dist(spec.origin) > spec.maxDist + PLANNING_SLACK;
   }

   /** The target footprint plus the pose band must lie inside the grid. */
   static boolean containsTarget(OccupancyGrid occ, InteractionSpec spec) {
      double pad = spec.maxDist + PLANNING_SLACK;
      double minX = spec.minX() - pad;
      double maxX = spec.maxX() + pad;
      double minY = spec.minY() - pad;
      double maxY = spec.maxY() + pad;
      return inGrid(occ, Coord2d.of(minX, minY)) && inGrid(occ, Coord2d.of(maxX, maxY));
   }

   /**
    * True when a point's cell lies inside the occupancy grid bounds. Note that
    * {@link OccupancyGrid#cellOf} never returns null for an in-grid point, so
    * "cellOf == null" is not an out-of-grid test; this is.
    */
   public static boolean inGrid(OccupancyGrid occ, Coord2d p) {
      if (occ == null || p == null || occ.origin == null || !(occ.cell > 0.0)) {
         return false;
      }
      Coord c = occ.cellOf(p);
      return c != null && c.x >= 0 && c.y >= 0 && c.x < occ.w && c.y < occ.h;
   }

   public static final class Candidate {
      public final Coord2d world;
      /** Angular offset (deg) from the player-facing ray around the target. */
      public final double angle;
      /** Distance from the target origin. */
      public final double radius;
      /** Occupancy travel cost from the player; finite when reachable. */
      public final double cost;
      /** Distance from the target origin (same as radius; kept explicit for telemetry). */
      public final double targetDist;
      public final String reject;

      Candidate(Coord2d world, double angle, double radius, double cost, String reject) {
         this.world = world;
         this.angle = angle;
         this.radius = radius;
         this.cost = cost;
         this.targetDist = radius;
         this.reject = reject;
      }
   }

   public static final class Result {
      public final Candidate selected;
      public final List<Candidate> considered;
      /** Walkable staging positions for the exact live pathfinder to choose between. */
      public final List<Coord2d> goals;
      public final String reason;

      Result(Candidate selected, List<Candidate> considered, List<Coord2d> goals, String reason) {
         this.selected = selected;
         this.considered = Collections.unmodifiableList(considered);
         this.goals = Collections.unmodifiableList(goals);
         this.reason = reason == null ? "" : reason;
      }

      public boolean ok() {
         return this.selected != null;
      }
   }

   /**
    * Deterministic staging pick beside a target inside the observed grid.
    * Starts on the player-facing ray outside the target bounding radius plus
    * player radius and safety margin, then evaluates angular alternatives on
    * an arc. Only walkable, reachable candidates qualify; the lowest travel
    * cost wins, ties break toward the player-facing side and closer radius.
    */
   public static Result select(Coord2d player, InteractionSpec spec, OccupancyGrid occ) {
      if (player == null || spec == null || spec.origin == null || occ == null || occ.occ == null) {
         return new Result(null, Collections.<Candidate>emptyList(), Collections.<Coord2d>emptyList(), "no_scene");
      }
      Coord2d origin = spec.origin;
      // Player-facing ray: direction from the target toward the player, so
      // the first candidate sits between player and target (target->player).
      Coord2d toward = unit(player.sub(origin));
      double standoff = standoffRadius(spec);
      double cap = Math.max(spec.maxDist + PLANNING_SLACK, standoff + CELL * 2.0);
      double[] costs = LocalPlanner.occupancyCosts(player, occ);
      List<Candidate> all = new ArrayList<Candidate>();
      List<Coord2d> goals = new ArrayList<Coord2d>();
      Candidate best = null;
      for (double radius = standoff; radius <= cap + 1.0E-9; radius += CELL) {
         for (int i = 0; i < ANGLES.length; i++) {
            Candidate c = probe(origin, rotate(toward, Math.toRadians(ANGLES[i])), radius, ANGLES[i], occ, costs);
            if (all.size() < MAX_CONSIDERED) {
               all.add(c);
            }
            // MAX_CONSIDERED limits diagnostics only. The safer or cheaper
            // staging pose can be on a later, wider ring.
            if (c.reject == null) {
               goals.add(c.world);
               if (best == null || better(c, best)) {
                  best = c;
               }
            }
         }
      }
      if (best == null) {
         return new Result(null, all, goals, STAGING_UNREACHABLE);
      }
      return new Result(best, all, goals, "");
   }

   /**
    * Conservative staging when the target geometry is not yet observed: walk
    * as far along the player-to-target ray as the currently observed region
    * allows, then repeat after refreshing. Repeats are bounded by the caller.
    */
   public static Result selectToward(Coord2d player, Coord2d targetRc, OccupancyGrid occ) {
      if (player == null || targetRc == null || occ == null || occ.occ == null) {
         return new Result(null, Collections.<Candidate>emptyList(), Collections.<Coord2d>emptyList(), "no_scene");
      }
      double halfExtent = Math.min(occ.w, occ.h) * occ.cell * 0.5;
      Coord2d toward = unit(targetRc.sub(player));
      double[] costs = LocalPlanner.occupancyCosts(player, occ);
      List<Candidate> all = new ArrayList<Candidate>();
      Candidate best = null;
      for (int a = 0; a < 7; a++) {
         double angle = a % 2 == 0 ? (a / 2) * 30.0 : -((a + 1) / 2) * 30.0;
         Coord2d dir = rotate(toward, Math.toRadians(angle));
         for (int f = 0; f < EDGE_FRACS.length; f++) {
            double d = halfExtent * EDGE_FRACS[f];
            Candidate c = probe(player, dir, d, angle, occ, costs, targetRc);
            if (all.size() < MAX_CONSIDERED) {
               all.add(c);
            }
            if (c.reject == null && (best == null || c.world.dist(targetRc) < best.world.dist(targetRc))) {
               best = c;
            }
         }
      }
      if (best == null) {
         return new Result(null, all, Collections.<Coord2d>emptyList(), STAGING_UNREACHABLE);
      }
      // A toward-target hop has a different job from object-side staging: it
      // must make maximum forward progress. Keep that destination singular.
      return new Result(best, all, Collections.singletonList(best.world), "");
   }

   private static Candidate probe(Coord2d from, Coord2d dir, double d, double angle, OccupancyGrid occ, double[] costs) {
      return probe(from, dir, d, angle, occ, costs, from);
   }

   private static Candidate probe(Coord2d from, Coord2d dir, double d, double angle, OccupancyGrid occ, double[] costs, Coord2d ref) {
      Coord2d p = from.add(dir.x * d, dir.y * d);
      if (p.dist(ref) < 1.0E-9) {
         return new Candidate(p, angle, d, Double.POSITIVE_INFINITY, "no_move");
      }
      Coord cell = occ.cellOf(p);
      if (cell == null) {
         return new Candidate(p, angle, d, Double.POSITIVE_INFINITY, "outside_grid");
      }
      byte v = occ.at(cell.x, cell.y);
      if (v != OccupancyGrid.FREE) {
         return new Candidate(p, angle, d, Double.POSITIVE_INFINITY, "not_walkable");
      }
      double cost = Double.POSITIVE_INFINITY;
      if (costs != null && costs.length > 0) {
         int idx = cell.y * occ.w + cell.x;
         if (idx >= 0 && idx < costs.length) {
            cost = costs[idx];
         }
      }
      if (!Double.isFinite(cost)) {
         return new Candidate(p, angle, d, cost, "unreachable");
      }
      return new Candidate(p, angle, d, cost, null);
   }

   /** Bounding radius of the target footprint plus player radius and safety. */
   static double standoffRadius(InteractionSpec spec) {
      double r = Math.max(spec.half.x, spec.half.y);
      for (int i = 0; i < spec.polygons.size(); i++) {
         Coord2d[] poly = spec.polygons.get(i);
         if (poly == null) {
            continue;
         }
         for (int v = 0; v < poly.length; v++) {
            if (poly[v] != null) {
               r = Math.max(r, poly[v].dist(spec.origin));
            }
         }
      }
      return r + LocalPlanner.DEFAULT_AGENT_RADIUS + SAFETY_MARGIN;
   }

   private static boolean better(Candidate a, Candidate b) {
      int c = Double.compare(a.cost, b.cost);
      if (c != 0) {
         return c < 0;
      }
      c = Double.compare(Math.abs(a.angle), Math.abs(b.angle));
      if (c != 0) {
         return c < 0;
      }
      return Double.compare(a.radius, b.radius) < 0;
   }

   static Coord2d unit(Coord2d v) {
      double len = v.abs();
      return len < 1.0E-9 ? Coord2d.of(0.0, 1.0) : Coord2d.of(v.x / len, v.y / len);
   }

   static Coord2d rotate(Coord2d v, double a) {
      double cs = Math.cos(a);
      double sn = Math.sin(a);
      return Coord2d.of(v.x * cs - v.y * sn, v.x * sn + v.y * cs);
   }
}
