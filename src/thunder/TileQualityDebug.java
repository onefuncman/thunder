package thunder;

import haven.CFG;
import haven.Console;
import haven.Coord;
import haven.GOut;
import haven.Indir;
import haven.Loading;
import haven.MapView;
import haven.Resource;
import haven.dev.DevFeature;
import haven.dev.Feature;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dev-iteration tooling for the tile-quality tracker. The core primitive is a
 * timestamped event log fed by hooks at every point the tracker's attribution
 * logic touches the outside world: server cursor changes, area-selector
 * lifecycle, map clicks, pending set/clear/expiry, dig-item classification,
 * and quality records. The event stream is what lets us verify actual game
 * behavior (which cursor a dig uses, whether it is an area select, when the
 * cursor reverts) instead of guessing.
 *
 * <p>Events print to stdout as they happen (greppable in a live run's output)
 * and accumulate in a ring buffer surfaced by the painter, {@code dev.tq.dump},
 * and snapshots.
 *
 * <p>Loaded by {@code haven.dev.DebugBoot} on first frame.
 */
public final class TileQualityDebug implements Feature {
    static { DevFeature.register(new TileQualityDebug()); }

    private static final int MAX_EVENTS = 1000;
    private static final ArrayDeque<String> events = new ArrayDeque<>();
    private static final long t0 = System.currentTimeMillis();

    public static boolean on() { return CFG.DEBUG_TILE_QUALITY.get(); }

    public static void event(String fmt, Object... args) {
	if(!on()) return;
	String line;
	try {
	    line = String.format("+%07dms %s", System.currentTimeMillis() - t0, String.format(fmt, args));
	} catch(Exception e) {
	    line = "(format error) " + fmt;
	}
	synchronized(events) {
	    events.addLast(line);
	    while(events.size() > MAX_EVENTS) events.removeFirst();
	}
	System.out.println("[tq] " + line);
	System.out.flush();
    }

    /**
     * Cursor resources may still be loading when the {@code curs} uimsg lands,
     * and the tracker's own handler bails on {@link Loading} — so the event log
     * resolves asynchronously to record every cursor transition, including ones
     * the tracker itself never saw resolved.
     */
    public static void cursorEvent(Indir<Resource> cursor) {
	if(!on()) return;
	if(cursor == null) { event("curs -> (cleared)"); return; }
	Thread t = new Thread(() -> {
	    for(int i = 0; i < 200; i++) {
		try {
		    Resource r = cursor.get();
		    event("curs -> %s", (r == null) ? "(null res)" : r.name);
		    return;
		} catch(Loading l) {
		    try { Thread.sleep(25); } catch(InterruptedException e) { return; }
		}
	    }
	    event("curs -> (never resolved)");
	}, "tq-curs-resolve");
	t.setDaemon(true);
	t.start();
    }

    /** Best-effort cursor name without blocking: "(loading)" if unresolved. */
    public static String cursorName(Indir<Resource> cursor) {
	if(cursor == null) return "(none)";
	try {
	    Resource r = cursor.get();
	    return (r == null) ? "(null res)" : r.name;
	} catch(Loading l) {
	    return "(loading)";
	}
    }

    // ---- Feature plumbing ----

    public String name() { return "tq"; }
    public CFG<Boolean> toggle() { return CFG.DEBUG_TILE_QUALITY; }

    public JSONObject capture() {
	long now = System.currentTimeMillis();
	JSONObject body = new JSONObject();
	body.put("now_ms", now);
	TileQuality tq = TileQuality.current();
	TileQuality.PendingAction p = (tq == null) ? null : tq.debugPeekPending();
	if(p != null) {
	    JSONObject pend = new JSONObject();
	    pend.put("group", TileQuality.debugGroupName(p.group));
	    pend.put("rc", p.rc.toString());
	    pend.put("deadline_ms", p.deadline);
	    if(p.deadline > 0)
		pend.put("ttl_remaining_ms", p.deadline - now);
	    body.put("pending", pend);
	} else {
	    body.put("pending", JSONObject.NULL);
	}
	JSONArray ev = new JSONArray();
	synchronized(events) {
	    for(String s : events) ev.put(s);
	}
	body.put("events", ev);
	return body;
    }

    public void paint(GOut g, MapView mv) {
	JSONObject s = capture();
	int x = 10, y = 260, dy = 14;
	JSONObject pend = s.optJSONObject("pending");
	if(pend != null) {
	    g.atext(String.format("tq pending: %s rc=%s ttl=%sms",
				  pend.optString("group", "?"),
				  pend.optString("rc", "?"),
				  pend.has("ttl_remaining_ms") ? Long.toString(pend.optLong("ttl_remaining_ms")) : "inf"),
		    new Coord(x, y), 0, 0); y += dy;
	} else {
	    g.atext("tq pending: none", new Coord(x, y), 0, 0); y += dy;
	}
	JSONArray ev = s.optJSONArray("events");
	if(ev != null) {
	    for(int i = Math.max(0, ev.length() - 12); i < ev.length(); i++) {
		g.atext(ev.optString(i, ""), new Coord(x, y), 0, 0); y += dy;
	    }
	}
    }

    public void replay(JSONObject body, PrintStream out) {
	JSONObject pend = body.optJSONObject("pending");
	out.println("pending at capture: " + ((pend == null) ? "none" : pend.toString()));
	JSONArray ev = body.optJSONArray("events");
	int n = (ev == null) ? 0 : ev.length();
	out.println("event log (" + n + " entries):");
	for(int i = 0; i < n; i++)
	    out.println("  " + ev.optString(i, ""));
    }

    public Map<String, Console.Command> extraVerbs() {
	Map<String, Console.Command> verbs = new LinkedHashMap<>();
	verbs.put("clear", (cons, args) -> {
		synchronized(events) { events.clear(); }
		cons.out.println("tq: event log cleared");
	    });
	return verbs;
    }
}
