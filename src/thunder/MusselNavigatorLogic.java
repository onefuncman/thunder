package thunder;

import haven.Coord;
import haven.Coord2d;
import java.util.*;

/** Deterministic route-following decisions used by the live mussel navigator. */
public final class MusselNavigatorLogic {
    private MusselNavigatorLogic() {}

    /** Explains both the nearest route point and whether the cursor was
     * allowed to use it. Keeping the rejected candidate is important when a
     * boat has overshot the route and the old cursor would otherwise be
     * mysterious in the debug log. */
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

    public static int lookahead(List<Coord> route, int nextIndex, Coord current, double maxTiles) {
        if(route == null || route.isEmpty() || current == null || maxTiles <= 0.0) return 0;
        int at = Math.max(0, Math.min(nextIndex, route.size() - 1));
        double distance = distance(current, route.get(at));
        while(at < route.size() - 1) {
            double next = distance(route.get(at), route.get(at + 1));
            if(distance + next > maxTiles) break;
            distance += next;
            at++;
        }
        return at;
    }

    /**
     * Keeps the saved-map route authoritative while avoiding one click per
     * tile. The returned points are the real corners of the cyan route plus
     * the requested end of the leg. This is the same basic execution shape as
     * Nurgling's path queue: follow the planner's vertices instead of asking a
     * second planner to invent a new route to only the final vertex.
     */
    public static List<Coord> guideCorners(List<Coord> route, int nextIndex, int goalIndex) {
        List<Coord> out = new ArrayList<>();
        if(route == null || route.isEmpty()) return out;
        int from = Math.max(0, Math.min(nextIndex, route.size() - 1));
        int to = Math.max(from, Math.min(goalIndex, route.size() - 1));
        if(from == 0) addDistinct(out, route.get(0));
        if(from == to) {
            addDistinct(out, route.get(to));
            return out;
        }

        int previousIndex = Math.max(0, from - 1);
        Coord previous = route.get(previousIndex);
        Coord current = route.get(from);
        int dx = Integer.signum(current.x - previous.x);
        int dy = Integer.signum(current.y - previous.y);
        for(int i = from + 1; i <= to; i++) {
            Coord next = route.get(i);
            int ndx = Integer.signum(next.x - current.x);
            int ndy = Integer.signum(next.y - current.y);
            if(ndx != dx || ndy != dy) {
                addDistinct(out, current);
                dx = ndx;
                dy = ndy;
            }
            current = next;
        }
        addDistinct(out, route.get(to));
        return out;
    }

    /** Nearest monotonic point inside the leg that was actually attempted. */
    public static int boundedProgressIndex(List<Coord> route, Coord current,
                                           int nextIndex, int attemptedGoalIndex) {
        if(route == null || route.isEmpty() || current == null) return nextIndex;
        int from = Math.max(0, Math.min(nextIndex, route.size() - 1));
        int to = Math.max(from, Math.min(attemptedGoalIndex, route.size() - 1));
        int best = from;
        double bestDistance = Double.POSITIVE_INFINITY;
        for(int i = from; i <= to; i++) {
            double distance = distance(current, route.get(i));
            if(distance < bestDistance || (distance == bestDistance && i > best)) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static void addDistinct(List<Coord> out, Coord value) {
        if(value == null) return;
        Coord copy = new Coord(value);
        if(out.isEmpty() || !out.get(out.size() - 1).equals(copy)) out.add(copy);
    }

    /** Rejoins only a bounded future section, so a nearby bend cannot skip a large loop. */
    public static int rejoinIndex(List<Coord> route, Coord current, int nextIndex,
                                  int back, int forward, double maxDistance) {
        return rejoinDecision(route, current, nextIndex, back, forward, maxDistance).selectedIndex;
    }

    public static RejoinDecision rejoinDecision(List<Coord> route, Coord current, int nextIndex,
                                                 int back, int forward, double maxDistance) {
        if(route == null || route.isEmpty() || current == null)
            return new RejoinDecision(nextIndex, -1, Double.POSITIVE_INFINITY, false);
        int from = Math.max(0, nextIndex - Math.max(0, back));
        int to = Math.min(route.size() - 1, nextIndex + Math.max(0, forward));
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for(int i = from; i <= to; i++) {
            double distance = distance(current, route.get(i));
            if(distance < bestDistance || (distance == bestDistance && i > best)) {
                bestDistance = distance;
                best = i;
            }
        }
        boolean accepted = best >= nextIndex && bestDistance <= maxDistance;
        return new RejoinDecision(accepted ? best : nextIndex, best, bestDistance, accepted);
    }

    /** Reduces a troublesome cruise leg without ever producing a zero-length
     * or backward recovery target. */
    public static int shorterLeg(int current, int minimum) {
        int floor = Math.max(1, minimum);
        return Math.max(floor, Math.max(1, current) / 2);
    }

    /**
     * Boat movement occasionally reports one idle update between otherwise
     * continuous updates. Keep that flicker from ending a command, while
     * still allowing a genuine, stable stop through after a short wait.
     */
    public static final class MotionDebouncer {
        private final long confirmMs;
        private final double stableDistance;
        private boolean sawMovement;
        private long stoppedSince = -1L;
        private Coord2d stoppedAt;

        public MotionDebouncer(long confirmMs, double stableDistance) {
            if(confirmMs < 0L || stableDistance < 0.0)
                throw new IllegalArgumentException("invalid stop confirmation settings");
            this.confirmMs = confirmMs;
            this.stableDistance = stableDistance;
        }

        public boolean moving(long now, Coord2d position, boolean reportedMoving) {
            if(reportedMoving) {
                sawMovement = true;
                stoppedSince = -1L;
                stoppedAt = null;
                return true;
            }
            if(!sawMovement) return false;
            if(stoppedSince < 0L || now < stoppedSince || movedSinceCandidate(position)) {
                stoppedSince = now;
                stoppedAt = copy(position);
                return true;
            }
            if(now - stoppedSince < confirmMs) return true;
            sawMovement = false;
            stoppedSince = -1L;
            stoppedAt = null;
            return false;
        }

        private boolean movedSinceCandidate(Coord2d position) {
            return stoppedAt != null && position != null && stoppedAt.dist(position) > stableDistance;
        }

        private static Coord2d copy(Coord2d value) {
            return value == null ? null : Coord2d.of(value.x, value.y);
        }
    }

    public static boolean oscillating(Collection<Coord> positions) {
        if(positions == null || positions.size() < 4) return false;
        List<Coord> list = new ArrayList<>(positions);
        int n = list.size();
        Coord a = list.get(n - 1), b = list.get(n - 2);
        return a != null && b != null && !a.equals(b)
            && a.equals(list.get(n - 3)) && b.equals(list.get(n - 4));
    }

    public static boolean needsSavedMapStage(double distance, double localApproachRadius) {
        return distance > localApproachRadius;
    }

    public static boolean readyForNativePickup(double distance, double pickupRadius) {
        return distance >= 0.0 && distance <= pickupRadius;
    }

    /** Sideways or backward motion is not route progress, even when the boat moved. */
    public static boolean madeForwardProgress(double moved, double beforeRemaining,
                                              double afterRemaining, double tileSize) {
        return tileSize > 0.0 && moved >= tileSize * 0.5
            && afterRemaining <= beforeRemaining - tileSize * 0.25;
    }

    /** A saved-map route must not be followed from an old position after the
     * boat drifted while that route was being calculated. */
    public static boolean stalePlanStart(Coord plannedStart, Coord current, double maxDistance) {
        return plannedStart == null || current == null || maxDistance < 0.0
            || distance(plannedStart, current) > maxDistance;
    }

    private static double distance(Coord a, Coord b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }
}
