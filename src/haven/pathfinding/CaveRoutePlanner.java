package haven.pathfinding;

import haven.Coord;
import java.util.*;

/** Pure saved-map planning for one connected cave-floor component. */
public final class CaveRoutePlanner {
    public static final int DEFAULT_MAX_TILES = 2_000_000;
    private static final int CLEARANCE_LIMIT = 18;
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

    public enum Cell {OPEN, BLOCKED, UNKNOWN}
    public enum Status {READY, LIMIT_REACHED, INVALID_START, NO_ROUTE}

    public interface Source {
        Cell cell(Coord tile);
    }

    public static final class Plan {
        public final Status status;
        public final List<Coord> route;
        public final Coord destination;
        public final boolean frontier;
        public final int frontierCount;
        public final int knownFloor;
        public final int visibilityScore;
        public final int unseenVisibilityScore;
        public final double distanceTiles;

        Plan(Status status, List<Coord> route, Coord destination, boolean frontier,
             int frontierCount, int knownFloor, int visibilityScore,
             int unseenVisibilityScore, double distanceTiles) {
            this.status = status;
            this.route = Collections.unmodifiableList(copy(route));
            this.destination = destination == null ? null : new Coord(destination);
            this.frontier = frontier;
            this.frontierCount = frontierCount;
            this.knownFloor = knownFloor;
            this.visibilityScore = visibilityScore;
            this.unseenVisibilityScore = unseenVisibilityScore;
            this.distanceTiles = distanceTiles;
        }

        public boolean usable() {
            return (status == Status.READY || status == Status.LIMIT_REACHED) && !route.isEmpty();
        }
    }

    private static final class Node {
        final long key;
        final int x, y;
        final Node parent;
        final int depth;
        boolean frontier;
        int clearance = Integer.MAX_VALUE;
        int exposure;
        int unseenExposure;

        Node(int x, int y, Node parent, int depth) {
            this.key = key(x, y);
            this.x = x;
            this.y = y;
            this.parent = parent;
            this.depth = depth;
        }

        Coord coord() {return Coord.of(x, y);}
    }

    private static final class Open implements Comparable<Open> {
        final long key;
        final double cost;

        Open(long key, double cost) {this.key = key; this.cost = cost;}

        @Override
        public int compareTo(Open other) {
            int cmp = Double.compare(cost, other.cost);
            return cmp != 0 ? cmp : Long.compare(key, other.key);
        }
    }

    private CaveRoutePlanner() {}

    public static Plan plan(Source source, Coord start) {
        return plan(source, start, DEFAULT_MAX_TILES, Integer.MAX_VALUE,
            Collections.emptySet());
    }

    /**
     * Selects a destination from the farthest ten percent of the connected
     * floor. Within that near-longest band, cumulative open-space visibility
     * wins. The final route is then rebuilt with a centerline preference.
     */
    public static Plan plan(Source source, Coord start, int maxTiles) {
        return plan(source, start, maxTiles, Integer.MAX_VALUE,
            Collections.emptySet());
    }

    public static Plan plan(Source source, Coord start, int maxTiles,
                            int maxDepth, Set<Coord> seen) {
        if(source == null || start == null || maxTiles <= 0 || maxDepth <= 0)
            return empty(Status.INVALID_START);
        Map<Long, Cell> observed = new HashMap<>();
        if(cell(source, observed, start.x, start.y) != Cell.OPEN)
            return empty(Status.INVALID_START);
        Set<Coord> seenTiles = seen == null ? Collections.emptySet() : seen;

        Map<Long, Node> nodes = new HashMap<>();
        List<Node> order = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Node root = new Node(start.x, start.y, null, 0);
        nodes.put(root.key, root);
        order.add(root);
        queue.add(root);
        boolean limited = false;

        scan:
        while(!queue.isEmpty()) {
            Node current = queue.removeFirst();
            current.frontier = adjacentUnknown(source, observed, current.x, current.y);
            if(current.depth >= maxDepth) continue;
            for(int d = 0; d < DX.length; d++) {
                int nx = current.x + DX[d], ny = current.y + DY[d];
                long nk = key(nx, ny);
                if(nodes.containsKey(nk) || cell(source, observed, nx, ny) != Cell.OPEN) continue;
                if(DX[d] != 0 && DY[d] != 0
                    && (cell(source, observed, current.x + DX[d], current.y) != Cell.OPEN
                        || cell(source, observed, current.x, current.y + DY[d]) != Cell.OPEN)) continue;
                if(nodes.size() >= maxTiles) {limited = true; break scan;}
                Node next = new Node(nx, ny, current, current.depth + 1);
                nodes.put(nk, next);
                order.add(next);
                queue.addLast(next);
            }
        }

        markClearance(nodes, order);
        for(Node node : order) {
            int localVisibility = 1 + Math.min(CLEARANCE_LIMIT, node.clearance);
            node.exposure = localVisibility + (node.parent == null ? 0 : node.parent.exposure);
            int unseenVisibility = seenTiles.contains(node.coord()) ? 0 : localVisibility;
            node.unseenExposure = unseenVisibility
                + (node.parent == null ? 0 : node.parent.unseenExposure);
        }

        List<Node> frontiers = new ArrayList<>();
        for(Node node : order) if(node.frontier) frontiers.add(node);
        List<Node> pool = frontiers.isEmpty() ? order : frontiers;
        int farthest = 0;
        for(Node node : pool) farthest = Math.max(farthest, node.depth);
        int cutoff = (int)Math.ceil(farthest * 0.90);
        Node best = null;
        for(Node node : pool) {
            if(node.depth >= cutoff && better(node, best)) best = node;
        }
        if(best == null) return new Plan(Status.NO_ROUTE, Collections.emptyList(), null,
            false, frontiers.size(), nodes.size(), 0, 0, 0.0);

        List<Coord> route = centeredRoute(nodes, root, best);
        int visibility = 0, unseenVisibility = 0;
        for(Coord tile : route) {
            Node node = nodes.get(key(tile.x, tile.y));
            if(node != null) {
                int localVisibility = 1 + Math.min(CLEARANCE_LIMIT, node.clearance);
                visibility += localVisibility;
                if(!seenTiles.contains(tile)) unseenVisibility += localVisibility;
            }
        }
        return new Plan(limited ? Status.LIMIT_REACHED : Status.READY, route, best.coord(),
            best.frontier, frontiers.size(), nodes.size(), visibility,
            unseenVisibility, distance(route));
    }

    /** Distance from each open tile to the nearest edge of connected floor. */
    private static void markClearance(Map<Long, Node> nodes, List<Node> order) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        for(Node node : order) {
            if(nodes.get(key(node.x + 1, node.y)) == null
                || nodes.get(key(node.x - 1, node.y)) == null
                || nodes.get(key(node.x, node.y + 1)) == null
                || nodes.get(key(node.x, node.y - 1)) == null) {
                node.clearance = 0;
                queue.addLast(node);
            }
        }
        while(!queue.isEmpty()) {
            Node current = queue.removeFirst();
            if(current.clearance >= CLEARANCE_LIMIT) continue;
            for(int d = 0; d < DX.length; d++) {
                Node next = nodes.get(key(current.x + DX[d], current.y + DY[d]));
                if(next != null && next.clearance > current.clearance + 1) {
                    next.clearance = current.clearance + 1;
                    queue.addLast(next);
                }
            }
        }
    }

    private static List<Coord> centeredRoute(Map<Long, Node> nodes, Node start, Node goal) {
        if(start == goal) return Collections.singletonList(start.coord());
        Map<Long, Double> costs = new HashMap<>();
        Map<Long, Long> parents = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<Open> open = new PriorityQueue<>();
        costs.put(start.key, 0.0);
        open.add(new Open(start.key, 0.0));
        while(!open.isEmpty()) {
            Open item = open.poll();
            if(!closed.add(item.key)) continue;
            if(item.key == goal.key) break;
            Node current = nodes.get(item.key);
            if(current == null) continue;
            for(int d = 0; d < DX.length; d++) {
                Node next = nodes.get(key(current.x + DX[d], current.y + DY[d]));
                if(next == null || closed.contains(next.key)) continue;
                if(DX[d] != 0 && DY[d] != 0
                    && (nodes.get(key(current.x + DX[d], current.y)) == null
                        || nodes.get(key(current.x, current.y + DY[d])) == null)) continue;
                double step = DX[d] != 0 && DY[d] != 0 ? Math.sqrt(2.0) : 1.0;
                double cost = costs.get(current.key) + step * travelCost(next.clearance);
                Double old = costs.get(next.key);
                if(old == null || cost < old - 0.000001) {
                    costs.put(next.key, cost);
                    parents.put(next.key, current.key);
                    open.add(new Open(next.key, cost));
                }
            }
        }
        if(!parents.containsKey(goal.key)) return collect(goal);
        List<Coord> route = new ArrayList<>();
        long at = goal.key;
        while(true) {
            Node node = nodes.get(at);
            if(node == null) return collect(goal);
            route.add(node.coord());
            if(at == start.key) break;
            Long previous = parents.get(at);
            if(previous == null) return collect(goal);
            at = previous;
        }
        Collections.reverse(route);
        return route;
    }

    private static double travelCost(int clearance) {
        if(clearance <= 0) return 6.0;
        if(clearance == 1) return 3.0;
        if(clearance == 2) return 1.8;
        if(clearance == 3) return 1.35;
        if(clearance == 4) return 1.15;
        return 1.0;
    }

    private static boolean better(Node candidate, Node current) {
        if(current == null) return true;
        if(candidate.unseenExposure != current.unseenExposure)
            return candidate.unseenExposure > current.unseenExposure;
        if(candidate.exposure != current.exposure) return candidate.exposure > current.exposure;
        if(candidate.depth != current.depth) return candidate.depth > current.depth;
        if(candidate.frontier != current.frontier) return candidate.frontier;
        if(candidate.x != current.x) return candidate.x < current.x;
        return candidate.y < current.y;
    }

    private static boolean adjacentUnknown(Source source, Map<Long, Cell> observed, int x, int y) {
        return cell(source, observed, x + 1, y) == Cell.UNKNOWN
            || cell(source, observed, x - 1, y) == Cell.UNKNOWN
            || cell(source, observed, x, y + 1) == Cell.UNKNOWN
            || cell(source, observed, x, y - 1) == Cell.UNKNOWN;
    }

    private static Cell cell(Source source, Map<Long, Cell> observed, int x, int y) {
        long key = key(x, y);
        Cell cached = observed.get(key);
        if(cached != null) return cached;
        Cell value;
        try {value = source.cell(Coord.of(x, y));}
        catch(RuntimeException failure) {value = Cell.UNKNOWN;}
        if(value == null) value = Cell.UNKNOWN;
        observed.put(key, value);
        return value;
    }

    private static List<Coord> collect(Node end) {
        List<Coord> route = new ArrayList<>();
        for(Node node = end; node != null; node = node.parent) route.add(node.coord());
        Collections.reverse(route);
        return route;
    }

    private static double distance(List<Coord> route) {
        double total = 0.0;
        for(int i = 1; i < route.size(); i++)
            total += Math.hypot(route.get(i).x - route.get(i - 1).x,
                route.get(i).y - route.get(i - 1).y);
        return total;
    }

    private static long key(int x, int y) {
        return ((long)x << 32) ^ (y & 0xffffffffL);
    }

    private static List<Coord> copy(Collection<Coord> source) {
        List<Coord> out = new ArrayList<>();
        if(source != null) for(Coord point : source) if(point != null) out.add(new Coord(point));
        return out;
    }

    private static Plan empty(Status status) {
        return new Plan(status, Collections.emptyList(), null, false, 0, 0, 0, 0, 0.0);
    }
}
