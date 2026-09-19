package haven.pathfinding;

import haven.Coord;
import java.util.*;

/** Pure saved-map planning for a single connected body of water. */
public final class WaterwayRoutePlanner {
    public static final int DEFAULT_MAX_TILES = 2_000_000;
    public static final int SHALLOW_VIEW_TILES = 35;
    private static final int BANK_CLEARANCE_LIMIT = 12;
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

    public enum Cell {
        SHALLOW,
        DEEP,
        LAND,
        UNKNOWN;

        public boolean water() {return this == SHALLOW || this == DEEP;}
    }

    public enum Status {
        READY,
        LIMIT_REACHED,
        INVALID_START,
        NO_ROUTE
    }

    public interface Source {
        Cell cell(Coord tile);
    }

    public static final class Plan {
        public final Status status;
        public final List<Coord> route;
        public final Coord destination;
        public final boolean frontier;
        public final int frontierCount;
        public final int knownWater;
        public final int shallowWater;
        public final int shallowExposure;
        public final double distanceTiles;

        Plan(Status status, List<Coord> route, Coord destination, boolean frontier,
             int frontierCount, int knownWater, int shallowWater,
             int shallowExposure, double distanceTiles) {
            this.status = status;
            this.route = Collections.unmodifiableList(copy(route));
            this.destination = destination == null ? null : new Coord(destination);
            this.frontier = frontier;
            this.frontierCount = frontierCount;
            this.knownWater = knownWater;
            this.shallowWater = shallowWater;
            this.shallowExposure = shallowExposure;
            this.distanceTiles = distanceTiles;
        }

        public boolean usable() {
            return (status == Status.READY || status == Status.LIMIT_REACHED) && !route.isEmpty();
        }
    }

    public static final class Route {
        public final Status status;
        public final List<Coord> tiles;
        public final Coord destination;
        public final int expanded;

        Route(Status status, List<Coord> tiles, Coord destination, int expanded) {
            this.status = status;
            this.tiles = Collections.unmodifiableList(copy(tiles));
            this.destination = destination == null ? null : new Coord(destination);
            this.expanded = expanded;
        }

        public boolean reached() {return status == Status.READY && destination != null;}
    }

    private static final class Node {
        final long key;
        final int x;
        final int y;
        final Cell cell;
        final Node parent;
        final int depth;
        boolean frontier;
        int nearShallow = Integer.MAX_VALUE;
        int bankClearance = Integer.MAX_VALUE;
        int exposure;

        Node(long key, int x, int y, Cell cell, Node parent, int depth) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.cell = cell;
            this.parent = parent;
            this.depth = depth;
        }

        Coord coord() {return Coord.of(x, y);}
    }

    private WaterwayRoutePlanner() {}

    public static Plan plan(Source source, Coord start) {
        return plan(source, start, DEFAULT_MAX_TILES, Collections.emptySet());
    }

    /**
     * Finds the farthest useful path from {@code start}. Open saved-map
     * frontiers are preferred so a running navigator can reveal more water.
     * Within ten percent of the farthest candidate, shallow-water exposure
     * wins, then distance, then stable coordinates.
     */
    public static Plan plan(Source source, Coord start, int maxTiles, Collection<Coord> closedFrontiers) {
        if(source == null || start == null || maxTiles <= 0)
            return empty(Status.INVALID_START);
        Map<Long, Cell> observed = new HashMap<>();
        Cell startCell = cell(source, observed, start.x, start.y);
        if(!startCell.water()) return empty(Status.INVALID_START);

        Set<Long> closed = keys(closedFrontiers);
        Map<Long, Node> nodes = new HashMap<>();
        List<Node> order = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Node root = new Node(key(start.x, start.y), start.x, start.y, startCell, null, 0);
        nodes.put(root.key, root);
        order.add(root);
        queue.add(root);
        boolean limited = false;
        int shallow = startCell == Cell.SHALLOW ? 1 : 0;

        scan:
        while(!queue.isEmpty()) {
            Node current = queue.removeFirst();
            current.frontier = !closed.contains(current.key) && adjacentUnknown(source, observed, current.x, current.y);
            for(int d = 0; d < DX.length; d++) {
                int nx = current.x + DX[d], ny = current.y + DY[d];
                long nk = key(nx, ny);
                if(nodes.containsKey(nk)) continue;
                Cell next = cell(source, observed, nx, ny);
                if(!next.water()) continue;
                if(DX[d] != 0 && DY[d] != 0
                    && (!cell(source, observed, current.x + DX[d], current.y).water()
                        || !cell(source, observed, current.x, current.y + DY[d]).water())) continue;
                if(nodes.size() >= maxTiles) {
                    limited = true;
                    break scan;
                }
                Node node = new Node(nk, nx, ny, next, current, current.depth + 1);
                nodes.put(nk, node);
                order.add(node);
                queue.addLast(node);
                if(next == Cell.SHALLOW) shallow++;
            }
        }

        markShallowBand(nodes, order);
        markBankClearance(nodes, order);
        List<Node> frontiers = new ArrayList<>();
        for(Node node : order) if(node.frontier) frontiers.add(node);
        // A closed point is an unsuitable destination, not fake land. Keep it
        // traversable so closing one troublesome endpoint cannot divide a
        // river, but also honor the closure on fully explored waterways where
        // there are no UNKNOWN frontier tiles.
        List<Node> available = new ArrayList<>();
        for(Node node : order) if(!closed.contains(node.key) || node == root) available.add(node);
        List<Node> pool = frontiers.isEmpty() ? available : frontiers;
        int farthest = 0;
        for(Node node : pool) farthest = Math.max(farthest, node.depth);
        int cutoff = (int)Math.ceil(farthest * 0.90);
        Node best = null;
        for(Node node : pool) {
            if(node.depth < cutoff) continue;
            if(better(node, best)) best = node;
        }
        if(best == null) return new Plan(Status.NO_ROUTE, Collections.emptyList(), null,
            false, frontiers.size(), nodes.size(), shallow, 0, 0.0);
        List<Coord> route = centeredRoute(nodes, root, best);
        int routeExposure = 0;
        for(Coord tile : route) {
            Node node = nodes.get(key(tile.x, tile.y));
            if(node != null && node.nearShallow <= SHALLOW_VIEW_TILES) routeExposure++;
        }
        return new Plan(limited ? Status.LIMIT_REACHED : Status.READY, route, best.coord(),
            best.frontier, frontiers.size(), nodes.size(), shallow,
            routeExposure, distance(route));
    }

    /** Shortest known-water route to any of the supplied staging tiles. */
    public static Route routeToAny(Source source, Coord start, Collection<Coord> goals, int maxTiles) {
        if(source == null || start == null || goals == null || goals.isEmpty() || maxTiles <= 0)
            return new Route(Status.INVALID_START, Collections.emptyList(), null, 0);
        Map<Long, Cell> observed = new HashMap<>();
        if(!cell(source, observed, start.x, start.y).water())
            return new Route(Status.INVALID_START, Collections.emptyList(), null, 0);
        Set<Long> goalKeys = keys(goals);
        Map<Long, Node> nodes = new HashMap<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Node root = new Node(key(start.x, start.y), start.x, start.y,
            cell(source, observed, start.x, start.y), null, 0);
        nodes.put(root.key, root);
        queue.add(root);
        if(goalKeys.contains(root.key))
            return new Route(Status.READY, Collections.singletonList(root.coord()), root.coord(), 1);

        while(!queue.isEmpty()) {
            Node current = queue.removeFirst();
            for(int d = 0; d < DX.length; d++) {
                int nx = current.x + DX[d], ny = current.y + DY[d];
                long nk = key(nx, ny);
                if(nodes.containsKey(nk)) continue;
                Cell next = cell(source, observed, nx, ny);
                if(!next.water()) continue;
                if(DX[d] != 0 && DY[d] != 0
                    && (!cell(source, observed, current.x + DX[d], current.y).water()
                        || !cell(source, observed, current.x, current.y + DY[d]).water())) continue;
                if(nodes.size() >= maxTiles)
                    return new Route(Status.LIMIT_REACHED, Collections.emptyList(), null, nodes.size());
                Node node = new Node(nk, nx, ny, next, current, current.depth + 1);
                nodes.put(nk, node);
                if(goalKeys.contains(nk))
                    return new Route(Status.READY, collect(node), node.coord(), nodes.size());
                queue.addLast(node);
            }
        }
        return new Route(Status.NO_ROUTE, Collections.emptyList(), null, nodes.size());
    }

    private static void markShallowBand(Map<Long, Node> nodes, List<Node> order) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        for(Node node : order) {
            if(node.cell == Cell.SHALLOW) {
                node.nearShallow = 0;
                queue.addLast(node);
            }
        }
        while(!queue.isEmpty()) {
            Node current = queue.removeFirst();
            if(current.nearShallow >= SHALLOW_VIEW_TILES) continue;
            for(int d = 0; d < DX.length; d++) {
                Node next = nodes.get(key(current.x + DX[d], current.y + DY[d]));
                if(next != null && next.nearShallow > current.nearShallow + 1) {
                    next.nearShallow = current.nearShallow + 1;
                    queue.addLast(next);
                }
            }
        }
        for(Node node : order) {
            int seen = node.nearShallow <= SHALLOW_VIEW_TILES ? 1 : 0;
            node.exposure = seen + (node.parent == null ? 0 : node.parent.exposure);
        }
    }

    /** Measures how many water tiles separate each route tile from a bank. */
    private static void markBankClearance(Map<Long, Node> nodes, List<Node> order) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        for(Node node : order) {
            if(nodes.get(key(node.x + 1, node.y)) == null
                || nodes.get(key(node.x - 1, node.y)) == null
                || nodes.get(key(node.x, node.y + 1)) == null
                || nodes.get(key(node.x, node.y - 1)) == null) {
                node.bankClearance = 0;
                queue.addLast(node);
            }
        }
        while(!queue.isEmpty()) {
            Node current = queue.removeFirst();
            if(current.bankClearance >= BANK_CLEARANCE_LIMIT) continue;
            for(int d = 0; d < DX.length; d++) {
                Node next = nodes.get(key(current.x + DX[d], current.y + DY[d]));
                if(next != null && next.bankClearance > current.bankClearance + 1) {
                    next.bankClearance = current.bankClearance + 1;
                    queue.addLast(next);
                }
            }
        }
    }

    /** Rebuilds the selected route for safe, fast cruising. Shallow water is
     * valuable to the destination's visibility score, but is deliberately
     * expensive to travel through when a deep center lane is available.
     * Narrow or entirely shallow rivers remain usable because no water tile
     * is made impassable here. */
    private static List<Coord> centeredRoute(Map<Long, Node> nodes, Node start, Node goal) {
        if(start == goal) return Collections.singletonList(start.coord());
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<PathOpen> open = new PriorityQueue<>();
        cost.put(start.key, 0.0);
        open.add(new PathOpen(start.key, 0.0));
        while(!open.isEmpty()) {
            PathOpen item = open.poll();
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
                double nd = cost.get(current.key) + step * travelCost(next);
                Double previous = cost.get(next.key);
                if(previous == null || nd < previous - 0.000001) {
                    cost.put(next.key, nd);
                    parent.put(next.key, current.key);
                    open.add(new PathOpen(next.key, nd));
                }
            }
        }
        if(!parent.containsKey(goal.key)) return collect(goal);
        List<Coord> route = new ArrayList<>();
        long at = goal.key;
        while(true) {
            Node node = nodes.get(at);
            if(node == null) return collect(goal);
            route.add(node.coord());
            if(at == start.key) break;
            Long previous = parent.get(at);
            if(previous == null) return collect(goal);
            at = previous;
        }
        Collections.reverse(route);
        return route;
    }

    private static double travelCost(Node node) {
        double depthCost = node.cell == Cell.SHALLOW ? 3.0 : 1.0;
        return depthCost * bankCost(node.bankClearance);
    }

    private static double bankCost(int clearance) {
        if(clearance <= 0) return 10.0;
        if(clearance == 1) return 5.0;
        if(clearance == 2) return 2.75;
        if(clearance == 3) return 1.75;
        if(clearance == 4) return 1.35;
        if(clearance == 5) return 1.15;
        return 1.0;
    }

    private static final class PathOpen implements Comparable<PathOpen> {
        final long key;
        final double cost;

        PathOpen(long key, double cost) {
            this.key = key;
            this.cost = cost;
        }

        @Override
        public int compareTo(PathOpen other) {
            int cmp = Double.compare(cost, other.cost);
            return cmp != 0 ? cmp : Long.compare(key, other.key);
        }
    }

    private static boolean better(Node candidate, Node current) {
        if(current == null) return true;
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
        for(int i = 1; i < route.size(); i++) {
            int dx = Math.abs(route.get(i).x - route.get(i - 1).x);
            int dy = Math.abs(route.get(i).y - route.get(i - 1).y);
            total += dx != 0 && dy != 0 ? Math.sqrt(2.0) : 1.0;
        }
        return total;
    }

    private static Set<Long> keys(Collection<Coord> coords) {
        Set<Long> out = new HashSet<>();
        if(coords != null) for(Coord coord : coords)
            if(coord != null) out.add(key(coord.x, coord.y));
        return out;
    }

    private static long key(int x, int y) {
        return ((long)x << 32) ^ (y & 0xffffffffL);
    }

    private static List<Coord> copy(Collection<Coord> coords) {
        List<Coord> out = new ArrayList<>();
        if(coords != null) for(Coord coord : coords) if(coord != null) out.add(new Coord(coord));
        return out;
    }

    private static Plan empty(Status status) {
        return new Plan(status, Collections.emptyList(), null, false, 0, 0, 0, 0, 0.0);
    }
}
