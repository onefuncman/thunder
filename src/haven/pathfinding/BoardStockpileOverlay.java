package haven.pathfinding;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.GOut;
import haven.MapView;
import haven.dev.DebugDraw;
import java.awt.Color;
import org.json.JSONArray;
import org.json.JSONObject;

/** Screen-space rectangle and worker-proposed positions for the panel. */
public final class BoardStockpileOverlay implements DebugDraw {
    private static final Color AREA = new Color(70, 210, 255, 180);
    private static final Color PLAN = new Color(120, 255, 100, 220);
    static { DebugDraw.Registry.register(new BoardStockpileOverlay()); }
    public CFG<Boolean> toggle() { return CFG.PF_ROUTE_OVERLAY; }
    public void paint(GOut g, MapView mv) {
        BoardStockpileMath.Area a = BoardStockpileWnd.selection();
        if (mv == null || a == null) return;
        Coord[] c = new Coord[] {
            PathfinderDebug.screen(mv, Coord2d.of(a.x, a.y)),
            PathfinderDebug.screen(mv, Coord2d.of(a.x + a.width, a.y)),
            PathfinderDebug.screen(mv, Coord2d.of(a.x + a.width, a.y + a.height)),
            PathfinderDebug.screen(mv, Coord2d.of(a.x, a.y + a.height))
        };
        g.chcolor(AREA);
        for (int i = 0; i < c.length; i++) if (c[i] != null && c[(i+1)%c.length] != null)
            g.line(c[i], c[(i+1)%c.length], 2);
        JSONArray ps = BoardStockpileWnd.plannedPositions();
        if (ps != null) {
            g.chcolor(PLAN);
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p == null) continue;
                Coord q = PathfinderDebug.screen(mv, Coord2d.of(p.optDouble("x"), p.optDouble("y")));
                if (q != null) { g.fellipse(q, Coord.of(5,5)); g.atext(Integer.toString(i+1), q.add(7,-4), 0, 0); }
            }
        }
        g.chcolor();
    }
}
