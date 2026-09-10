package haven.pathfinding;

/** Pure area-selection math shared by the Navigation Lab panel and tests. */
public final class BoardStockpileMath {
    public static final double TILE = 11.0;
    private BoardStockpileMath() {}

    public static final class Area {
        public final double x, y, width, height;
        Area(double x, double y, double width, double height) {
            this.x=x; this.y=y; this.width=width; this.height=height;
        }
        public boolean valid() { return width > 0 && height > 0; }
    }

    /** Converts two world points to an inclusive, tile-aligned rectangle. */
    public static Area fromClicks(double ax, double ay, double bx, double by) {
        double x = Math.floor(Math.min(ax, bx) / TILE) * TILE;
        double y = Math.floor(Math.min(ay, by) / TILE) * TILE;
        double ex = (Math.floor(Math.max(ax, bx) / TILE) + 1.0) * TILE;
        double ey = (Math.floor(Math.max(ay, by) / TILE) + 1.0) * TILE;
        return new Area(x, y, ex-x, ey-y);
    }

    public static String uiState(String workerState, String outcome) {
        if (outcome != null && !"null".equals(outcome))
            return "CANCELLED".equals(outcome) ? "failed" : "failed";
        if (workerState == null) return "planning";
        if ("QUEUED".equals(workerState) || "RUNNING".equals(workerState)) return "walking";
        if ("SUCCEEDED".equals(workerState)) return "completed";
        if ("CANCELLED".equals(workerState) || "FAILED".equals(workerState)) return "failed";
        return "planning";
    }
}
