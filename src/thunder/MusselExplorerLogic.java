package thunder;

import haven.Coord2d;
import java.util.*;

/** Small, deterministic decisions for river exploration. */
public final class MusselExplorerLogic {
    public enum Direction {
        NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);
        public final int dx, dy;
        Direction(int dx, int dy) {this.dx = dx; this.dy = dy;}
    }

    public static final class Candidate {
        public final Coord2d point;
        public final double angle;
        public final int visits;
        public final boolean shallow;
        public final boolean frontier;
        public final double forward;

        public Candidate(Coord2d point, double angle, int visits, boolean shallow, boolean frontier) {
            this(point, angle, visits, shallow, frontier, 0.0);
        }

        public Candidate(Coord2d point, double angle, int visits, boolean shallow,
                         boolean frontier, double forward) {
            this.point = point;
            this.angle = angle;
            this.visits = visits;
            this.shallow = shallow;
            this.frontier = frontier;
            this.forward = forward;
        }
    }

    /** A boat first reaches staging water, turns there, then travels straight
     * to the stop point with the mussel beside the hull. */
    public static final class Approach {
        public final Coord2d stop;
        public final Coord2d staging;
        public final Coord2d departure;
        public final double heading;

        public Approach(Coord2d stop, Coord2d staging, Coord2d departure, double heading) {
            this.stop = stop;
            this.staging = staging;
            this.departure = departure;
            this.heading = heading;
        }
    }

    private MusselExplorerLogic() {}

    public static double heading(Direction direction) {
        return Math.atan2(direction.dy, direction.dx);
    }

    public static Coord2d probe(Coord2d from, double heading, double distance, double offset) {
        double angle = heading + offset;
        return from.add(Math.cos(angle) * distance, Math.sin(angle) * distance);
    }

    public static double score(Candidate candidate) {
        if(candidate == null || candidate.point == null) return -Double.MAX_VALUE;
        double straight = Math.cos(candidate.angle);
        return -(candidate.visits * 1000.0)
            + (candidate.shallow ? 260.0 : 0.0)
            + (candidate.frontier ? 180.0 : 0.0)
            + (straight * 70.0)
            + (candidate.forward * 3.0);
    }

    public static List<Candidate> ranked(Collection<Candidate> candidates) {
        List<Candidate> out = new ArrayList<>();
        if(candidates != null) for(Candidate candidate : candidates) if(candidate != null) out.add(candidate);
        out.sort((a, b) -> {
            int score = Double.compare(score(b), score(a));
            if(score != 0) return score;
            int x = Double.compare(a.point.x, b.point.x);
            return x != 0 ? x : Double.compare(a.point.y, b.point.y);
        });
        return out;
    }

    /** Remove only tiny grid-alignment corrections at the beginning and end.
     * Interior points describe real obstacle bends and must be preserved. */
    public static List<Coord2d> smoothBoatPath(List<Coord2d> waypoints, double tinyDistance) {
        if(waypoints == null || waypoints.size() < 3 || tinyDistance <= 0) {
            return waypoints == null ? Collections.emptyList() : new ArrayList<>(waypoints);
        }
        List<Coord2d> out = new ArrayList<>();
        int last = waypoints.size() - 1;
        Coord2d start = waypoints.get(0);
        out.add(start);
        for(int i = 1; i < last; i++) {
            Coord2d current = waypoints.get(i);
            if(current == null) continue;
            if(i == 1 && start != null && start.dist(current) <= tinyDistance) continue;
            Coord2d end = waypoints.get(last);
            if(i == last - 1 && end != null && current.dist(end) <= tinyDistance) continue;
            out.add(current);
        }
        Coord2d end = waypoints.get(last);
        if(end != null && (out.isEmpty() || out.get(out.size() - 1).dist(end) > 0.001)) out.add(end);
        return out;
    }

    public static List<Coord2d> reversedRoute(List<Coord2d> route, Coord2d current) {
        if(route == null || route.size() < 2 || current == null) return Collections.emptyList();
        List<Coord2d> out = new ArrayList<>();
        out.add(current);
        for(int i = route.size() - 2; i >= 0; i--) {
            Coord2d point = route.get(i);
            if(point != null && out.get(out.size() - 1).dist(point) > 1.0) out.add(point);
        }
        return out;
    }

    /** Make side-on approaches instead of pointing the bow at the bank. */
    public static List<Approach> sideApproaches(Coord2d target, double stopDistance,
                                                double leadDistance, int samples) {
        if(target == null || stopDistance <= 0 || leadDistance <= 0 || samples < 4)
            return Collections.emptyList();
        List<Approach> out = new ArrayList<>(samples * 2);
        for(int i = 0; i < samples; i++) {
            double radial = (Math.PI * 2.0 * i) / samples;
            Coord2d stop = target.add(Math.cos(radial) * stopDistance,
                Math.sin(radial) * stopDistance);
            for(double heading : new double[]{radial + Math.PI / 2.0, radial - Math.PI / 2.0}) {
                Coord2d staging = stop.add(-Math.cos(heading) * leadDistance,
                    -Math.sin(heading) * leadDistance);
                Coord2d departure = stop.add(Math.cos(heading) * leadDistance,
                    Math.sin(heading) * leadDistance);
                out.add(new Approach(stop, staging, departure, normalizeAngle(heading)));
            }
        }
        return out;
    }

    /** Points covering the actual rotated rectangular boat body. */
    public static List<Coord2d> footprintSamples(Coord2d center, double heading,
                                                 double halfLength, double halfWidth,
                                                 double spacing) {
        if(center == null || halfLength <= 0 || halfWidth <= 0 || spacing <= 0)
            return Collections.emptyList();
        int nx = Math.max(1, (int)Math.ceil((halfLength * 2.0) / spacing));
        int ny = Math.max(1, (int)Math.ceil((halfWidth * 2.0) / spacing));
        double cs = Math.cos(heading), sn = Math.sin(heading);
        List<Coord2d> out = new ArrayList<>((nx + 1) * (ny + 1));
        for(int ix = 0; ix <= nx; ix++) {
            double lx = -halfLength + (halfLength * 2.0 * ix / nx);
            for(int iy = 0; iy <= ny; iy++) {
                double ly = -halfWidth + (halfWidth * 2.0 * iy / ny);
                out.add(Coord2d.of(center.x + lx * cs - ly * sn,
                    center.y + lx * sn + ly * cs));
            }
        }
        return out;
    }

    public static List<Coord2d> circleSamples(Coord2d center, double radius, int samples) {
        if(center == null || radius <= 0 || samples < 4) return Collections.emptyList();
        List<Coord2d> out = new ArrayList<>(samples + 1);
        out.add(center);
        for(int i = 0; i < samples; i++) {
            double a = Math.PI * 2.0 * i / samples;
            out.add(center.add(Math.cos(a) * radius, Math.sin(a) * radius));
        }
        return out;
    }

    public static double approachScore(Coord2d from, double currentHeading, Approach approach) {
        if(from == null || approach == null || approach.staging == null) return Double.POSITIVE_INFINITY;
        double turn = Math.abs(normalizeAngle(approach.heading - currentHeading));
        return from.dist(approach.staging) + turn * 12.0;
    }

    public static double routeLength(List<Coord2d> route) {
        if(route == null || route.size() < 2) return 0.0;
        double total = 0.0;
        for(int i = 1; i < route.size(); i++) {
            Coord2d a = route.get(i - 1), b = route.get(i);
            if(a != null && b != null) total += a.dist(b);
        }
        return total;
    }

    /** Reject a route that reaches a nearby exploration point by taking a
     * large loop. Those loops are usually shoreline snaps, not useful river
     * progress. */
    public static boolean reasonableCruiseRoute(Coord2d from, Coord2d to, List<Coord2d> route) {
        if(from == null || to == null || route == null || route.size() < 2) return false;
        double direct = from.dist(to);
        if(direct < 1.0) return false;
        return routeLength(route) <= (direct * 1.65) + 22.0;
    }

    /** An incomplete local route is useful when it makes a real, efficient
     * step toward the saved-map goal. A boat pathfinder can legitimately
     * return one water tile at a time beside a tight bank; requiring ten
     * percent of a long cruise leg discards that safe escape step. Comparing
     * path length with actual forward gain still rejects sideways loops. */
    public static boolean usefulPartialCruiseRoute(Coord2d from, Coord2d to,
                                                   List<Coord2d> route, double tileSize) {
        if(from == null || to == null || route == null || route.size() < 2 || tileSize <= 0.0)
            return false;
        Coord2d end = route.get(route.size() - 1);
        if(end == null) return false;
        double direct = from.dist(to);
        double progress = direct - end.dist(to);
        double traveled = routeLength(route);
        return progress >= tileSize * 0.50
            && traveled <= (progress * 2.5) + tileSize;
    }

    public static double directionalProgress(Coord2d origin, Coord2d point, double heading) {
        if(origin == null || point == null) return Double.NEGATIVE_INFINITY;
        return (point.x - origin.x) * Math.cos(heading) + (point.y - origin.y) * Math.sin(heading);
    }

    public static boolean withinBacktrack(double progress, double furthest, double allowance) {
        return allowance >= 0.0 && progress >= furthest - allowance;
    }

    static double normalizeAngle(double angle) {
        while(angle <= -Math.PI) angle += Math.PI * 2.0;
        while(angle > Math.PI) angle -= Math.PI * 2.0;
        return angle;
    }
}
