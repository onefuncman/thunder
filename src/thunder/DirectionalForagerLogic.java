package thunder;

import haven.Coord;
import haven.Coord2d;
import java.util.*;

/** Pure decisions used by the live runner and offline tests. */
public final class DirectionalForagerLogic {
    public enum Direction {
        NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);
        final int dx, dy;
        Direction(int dx, int dy) {this.dx = dx; this.dy = dy;}
    }

    public static final class Candidate {
        public final long id;
        public final String key;
        public final Coord2d position;
        public Candidate(long id, String key, Coord2d position) {this.id = id; this.key = key; this.position = position;}
    }

    /** Route-cursor diagnostics retained when a nearby point is rejected. */
    public static final class RejoinDecision {
        public final int selectedIndex;
        public final int nearestIndex;
        public final double nearestDistance;
        public final boolean accepted;

        RejoinDecision(int selectedIndex, int nearestIndex, double nearestDistance, boolean accepted) {
            this.selectedIndex = selectedIndex;
            this.nearestIndex = nearestIndex;
            this.nearestDistance = nearestDistance;
            this.accepted = accepted;
        }
    }

    private DirectionalForagerLogic() {}

    public static Coord2d forward(Coord2d from, Direction direction, double distance) {
        return from.add(direction.dx * distance, direction.dy * distance);
    }

    /** A probe direction rotated around the player's preferred compass heading. */
    public static Coord2d probe(Coord2d from, Direction direction, double distance, double angle) {
        double cs = Math.cos(angle), sn = Math.sin(angle);
        double x = direction.dx * cs - direction.dy * sn;
        double y = direction.dx * sn + direction.dy * cs;
        return from.add(x * distance, y * distance);
    }

    /**
     * Exact goals spread around the preferred compass heading. The movement
     * boundary deliberately does not snap blocked destinations, so exploratory
     * bots must offer their own equally valid alternatives.
     */
    public static List<Coord2d> forwardProbes(Coord2d from, Direction direction,
                                               double distance, double... angles) {
        List<Coord2d> probes = new ArrayList<>();
        if(from == null || direction == null || distance <= 0.0 || angles == null) return probes;
        for(double angle : angles) probes.add(probe(from, direction, distance, angle));
        return probes;
    }

    /** Exact goals spread around the heading from {@code from} to {@code target}. */
    public static List<Coord2d> towardProbes(Coord2d from, Coord2d target,
                                              double distance, double... angles) {
        List<Coord2d> probes = new ArrayList<>();
        if(from == null || target == null || distance <= 0.0 || angles == null) return probes;
        double heading = Math.atan2(target.y - from.y, target.x - from.x);
        for(double offset : angles) {
            double angle = heading + offset;
            probes.add(from.add(Math.cos(angle) * distance, Math.sin(angle) * distance));
        }
        return probes;
    }

    /**
     * Stand points on a ring around a collision-free point target. Angle zero
     * is the near side facing the player; later angles are fallback sides.
     */
    public static List<Coord2d> approachProbes(Coord2d from, Coord2d target,
                                                double standoff, double... angles) {
        List<Coord2d> probes = new ArrayList<>();
        if(from == null || target == null || standoff <= 0.0 || angles == null) return probes;
        double dx = from.x - target.x;
        double dy = from.y - target.y;
        double length = Math.sqrt(dx * dx + dy * dy);
        if(length <= 1.0e-9) {dx = 1.0; dy = 0.0; length = 1.0;}
        dx /= length;
        dy /= length;
        for(double angle : angles) {
            double cs = Math.cos(angle), sn = Math.sin(angle);
            double x = dx * cs - dy * sn;
            double y = dx * sn + dy * cs;
            probes.add(target.add(x * standoff, y * standoff));
        }
        return probes;
    }

    public static double forwardProgress(Coord2d from, Coord2d to, Direction direction) {
        return (to.x - from.x) * direction.dx + (to.y - from.y) * direction.dy;
    }

    /** Farthest route tile reachable inside the requested leg length. */
    public static int lookahead(List<Coord> route, int nextIndex, Coord current, double maxTiles) {
        if(route == null || route.isEmpty() || current == null || maxTiles <= 0.0) return 0;
        int at = Math.max(0, Math.min(nextIndex, route.size() - 1));
        double distance = tileDistance(current, route.get(at));
        while(at < route.size() - 1) {
            double next = tileDistance(route.get(at), route.get(at + 1));
            if(distance + next > maxTiles) break;
            distance += next;
            at++;
        }
        return at;
    }

    /** Rejoins only a bounded future section, preventing a nearby later bend
     * from skipping most of a cave-coverage route. */
    public static RejoinDecision rejoinDecision(List<Coord> route, Coord current, int nextIndex,
                                                 int back, int forward, double maxDistance) {
        if(route == null || route.isEmpty() || current == null)
            return new RejoinDecision(nextIndex, -1, Double.POSITIVE_INFINITY, false);
        int from = Math.max(0, nextIndex - Math.max(0, back));
        int to = Math.min(route.size() - 1, nextIndex + Math.max(0, forward));
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for(int i = from; i <= to; i++) {
            double distance = tileDistance(current, route.get(i));
            if(distance < bestDistance || (distance == bestDistance && i > best)) {
                bestDistance = distance;
                best = i;
            }
        }
        boolean accepted = best >= nextIndex && bestDistance <= maxDistance;
        return new RejoinDecision(accepted ? best : nextIndex, best, bestDistance, accepted);
    }

    private static double tileDistance(Coord a, Coord b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    public static boolean safeFrom(Coord2d point, Collection<Coord2d> dangers, double radius) {
        if(point == null) return false;
        if(dangers == null || radius <= 0.0) return true;
        for(Coord2d danger : dangers) {
            if(danger != null && point.dist(danger) < radius) return false;
        }
        return true;
    }

    /**
     * Target disappearance is the authoritative completion signal for a
     * forage pickup. Inventory changes are useful diagnostics, but are not a
     * reliable gate when the server merges or represents the picked item in a
     * way that leaves the inventory signature unchanged.
     */
    public static boolean pickupConfirmed(boolean targetRemoved, boolean storageChanged) {
        return targetRemoved;
    }

    /** The first strategic route tile beyond the point where live movement
     * stopped. Used to remember walls/ridges missing from the saved map. */
    public static Coord firstBlockedRouteTile(List<Coord> route, Coord stopped,
                                               int fromIndex, int throughIndex) {
        if(route == null || route.isEmpty() || stopped == null) return null;
        int from = Math.max(0, Math.min(fromIndex, route.size() - 1));
        int through = Math.max(from, Math.min(throughIndex, route.size() - 1));
        int nearest = Math.max(0, from - 1);
        double nearestDistance = Double.POSITIVE_INFINITY;
        for(int i = nearest; i <= through; i++) {
            Coord tile = route.get(i);
            double distance = Math.hypot(tile.x - stopped.x, tile.y - stopped.y);
            if(distance < nearestDistance || (distance == nearestDistance && i > nearest)) {
                nearest = i;
                nearestDistance = distance;
            }
        }
        return new Coord(route.get(Math.min(through, Math.max(from, nearest + 1))));
    }

    /** Previously completed cave-route tiles are walls for future chunks.
     * Covered terrain is also closed outside a small exit bubble around the
     * new start, preventing a route from circling through the old scene merely
     * to reach a little unseen floor at its far end. */
    public static boolean caveRouteBlocked(Coord tile, Coord start,
                                            Set<Coord> blocked, Set<Coord> traversed,
                                            Set<Coord> covered, double coveredExitRadius) {
        if(tile == null) return true;
        if(blocked != null && blocked.contains(tile)) return true;
        if(tile.equals(start)) return false;
        if(traversed != null && traversed.contains(tile)) return true;
        return covered != null && covered.contains(tile) && coveredExitRadius >= 0.0
            && Math.hypot(tile.x - start.x, tile.y - start.y) > coveredExitRadius;
    }

    public static Candidate nearest(Coord2d player, Collection<Candidate> candidates, Set<String> selected, Set<Long> failed) {
        Candidate best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        if(player == null || candidates == null || selected == null) return null;
        for(Candidate c : candidates) {
            if(c == null || c.position == null || !selected.contains(c.key) || (failed != null && failed.contains(c.id))) continue;
            double distance = player.dist(c.position);
            if(distance < bestDistance || (distance == bestDistance && (best == null || c.id < best.id))) {
                best = c; bestDistance = distance;
            }
        }
        return best;
    }
}
