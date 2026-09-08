package haven.pathfinding;

import haven.Button;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.HackThread;
import haven.Label;
import haven.MapView;
import haven.TextEntry;
import haven.UI;
import haven.Utils;
import haven.GameUI.Hidewnd;
import haven.GameUI.MsgType;
import java.nio.file.Paths;
import org.json.JSONArray;
import org.json.JSONObject;

/** Minimal Navigation Lab board-stockpile panel; planning stays worker-side. */
public final class BoardStockpileWnd extends Hidewnd {
    private static final int W = UI.scale(300);
    private static BoardStockpileWnd current;
    private final TextEntry piles, boards, resource;
    private final Label areaLabel, details, state;
    private BoardStockpileMath.Area area;
    private JSONArray positions;
    private String jobId;
    private boolean confirming;
    private long nextPoll;
    private MapView selecting;
    private MapView.Grabber selector;

    public BoardStockpileWnd() {
        super(Coord.z, "Board stockpile");
        current = this;
        this.justclose = true;
        this.piles = (TextEntry)add(new TextEntry(W, "1"), Coord.z);
        int y = piles.sz.y + UI.scale(4);
        this.boards = (TextEntry)add(new TextEntry(W, "4"), 0, y);
        y += boards.sz.y + UI.scale(4);
        this.resource = (TextEntry)add(new TextEntry(W, "gfx/invobjs/board"), 0, y);
        y += resource.sz.y + UI.scale(6);
        add(new Button(UI.scale(96), "Select area") { public void click() { selectArea(); } }, 0, y);
        add(new Button(UI.scale(76), "Plan") { public void click() { plan(); } }, UI.scale(102), y);
        y += UI.scale(28);
        areaLabel = (Label)add(new Label("Area: none"), 0, y);
        y += UI.scale(18);
        details = (Label)add(new Label("Boards: unknown  Capacity: unknown"), 0, y);
        y += UI.scale(18);
        state = (Label)add(new Label("State: planning"), 0, y);
        y += UI.scale(24);
        add(new Button(UI.scale(76), "Start") { public void click() { start(); } }, 0, y);
        add(new Button(UI.scale(76), "Cancel") { public void click() { cancel(); } }, UI.scale(82), y);
        pack();
        hide();
    }

    public static BoardStockpileMath.Area selection() { return current == null ? null : current.area; }
    public static JSONArray plannedPositions() { return current == null ? null : current.positions; }

    private GameUI gui() { return ui == null ? null : ui.gui; }
    private void message(String text, MsgType type) {
        if (gui() != null) gui().msg(text, type);
        state.settext("State: " + text);
    }
    private void selectArea() {
        GameUI gui = gui();
        if (gui == null || gui.map == null) { message("no map", MsgType.ERROR); return; }
        if (selecting != null && selector != null) selecting.release(selector);
        selecting = gui.map;
        selector = new MapView.Grabber() {
            public boolean mmousedown(Coord c, int button) {
                if (button == 3) { selecting.release(this); selecting = null; selector = null; message("selection cancelled", MsgType.INFO); return true; }
                if (button != 1) return false;
                final MapView map = selecting;
                map.new Maptest(c) {
                    protected void hit(Coord pc, Coord2d mc) { picked(map, mc); }
                }.run();
                return true;
            }
            public boolean mmouseup(Coord c, int button) { return true; }
            public boolean mmousewheel(Coord c, int amount) { return false; }
            public void mmousemove(Coord c) { }
        };
        selecting.grab(selector);
        message("click two map corners", MsgType.INFO);
    }
    private void picked(MapView map, Coord2d point) {
        if (firstPoint == null) {
            firstPoint = point;
            areaLabel.settext("Area: first corner selected");
            return;
        }
        area = BoardStockpileMath.fromClicks(firstPoint.x, firstPoint.y, point.x, point.y);
        firstPoint = null;
        if (selecting != null && selector != null) selecting.release(selector);
        selecting = null; selector = null; positions = null;
        areaLabel.settext(String.format("Area: %.0f,%.0f  %.0fx%.0f", area.x, area.y, area.width, area.height));
        message("area selected", MsgType.INFO);
    }
    private Coord2d firstPoint;

    private int number(TextEntry entry, int fallback) {
        try { return Integer.parseInt(entry.text().trim()); } catch (RuntimeException e) { return fallback; }
    }
    private BoardStockpileClient client() {
        return new BoardStockpileClient(Paths.get(System.getProperty("haven.worker.command.socket", "/tmp/haven-worker.sock")));
    }
    private void plan() {
        if (area == null || !area.valid()) { message("select area first", MsgType.ERROR); return; }
        final BoardStockpileMath.Area requestArea = area;
        final int count = number(piles, 0), perPile = number(boards, 0);
        final String res = resource.text().trim();
        if (count < 1 || perPile < 1 || res.length() == 0) { message("invalid values", MsgType.ERROR); return; }
        message("planning", MsgType.INFO);
        new HackThread(() -> {
            try {
                JSONObject result = client().plan(requestArea, count, perPile, res);
                Utils.defer(() -> applyPlan(result));
            } catch (Exception e) { Utils.defer(() -> message(shortError(e), MsgType.ERROR)); }
        }, "board-stockpile-plan").start();
    }
    private void applyPlan(JSONObject result) {
        positions = result.optJSONArray("positions");
        details.settext("Boards: " + result.optInt("available_boards", 0) + "/" + result.optInt("boards_needed", 0)
            + "  Capacity: " + result.optInt("capacity", 0));
        String why = result.optString("outcome", "null");
        message("null".equals(why) ? "ready" : "blocked: " + why, "null".equals(why) ? MsgType.INFO : MsgType.ERROR);
    }
    private void start() {
        if (confirming) { confirming = false; submit(); }
        else { confirming = true; message("click Start again to confirm", MsgType.INFO); }
    }
    private void submit() {
        if (area == null || positions == null) { message("plan first", MsgType.ERROR); return; }
        final BoardStockpileMath.Area requestArea = area;
        final int count = number(piles, 0), perPile = number(boards, 0); final String res = resource.text().trim();
        message("planning", MsgType.INFO);
        new HackThread(() -> {
            try {
                JSONObject result = client().execute(requestArea, count, perPile, res);
                jobId = result.optString("job_id", null); nextPoll = 0;
                Utils.defer(() -> message(jobId == null ? "failed" : "walking", MsgType.INFO));
            } catch (Exception e) { Utils.defer(() -> message(shortError(e), MsgType.ERROR)); }
        }, "board-stockpile-start").start();
    }
    private void cancel() {
        final String id = jobId;
        if (id == null) { confirming = false; message("idle", MsgType.INFO); return; }
        new HackThread(() -> { try { client().cancel(id); Utils.defer(() -> { jobId = null; message("failed", MsgType.INFO); }); }
            catch (Exception e) { Utils.defer(() -> message(shortError(e), MsgType.ERROR)); } }, "board-stockpile-cancel").start();
    }
    public void tick(double dt) {
        super.tick(dt);
        if (jobId == null || System.currentTimeMillis() < nextPoll) return;
        nextPoll = System.currentTimeMillis() + 500L;
        final String id = jobId;
        new HackThread(() -> { try { JSONObject result = client().status(id); Utils.defer(() -> applyStatus(result)); }
            catch (Exception e) { Utils.defer(() -> message(shortError(e), MsgType.ERROR)); } }, "board-stockpile-status").start();
    }
    private void applyStatus(JSONObject result) {
        String workerState = result.optString("state", null), phase = result.optString("phase", null);
        String label = "RUNNING".equals(workerState) && phase != null ? phase.toLowerCase() : BoardStockpileMath.uiState(workerState, result.optString("outcome", null));
        if ("wait_nav".equals(label) || "navigate".equals(label)) label = "walking";
        else if ("take".equals(label) || "itemact".equals(label) || "place".equals(label)) label = "creating";
        else if (label.startsWith("transfer") || label.contains("window")) label = "filling";
        state.settext("State: " + label);
        if ("SUCCEEDED".equals(workerState) || "FAILED".equals(workerState) || "CANCELLED".equals(workerState)) jobId = null;
    }
    private static String shortError(Exception e) { String s = e.getMessage(); return s == null || s.length() == 0 ? "worker disconnected" : s.length() > 80 ? s.substring(0, 80) : s; }
}
