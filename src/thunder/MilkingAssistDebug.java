package thunder;

import haven.CFG;
import haven.Console;
import haven.Coord;
import haven.GOut;
import haven.Glob;
import haven.MapView;
import haven.UI;
import haven.UID;
import haven.dev.DevFeature;
import haven.dev.Feature;
import haven.dev.FeatureCapture;
import haven.res.ui.croster.CattleRoster;
import haven.res.ui.croster.Entry;
import haven.res.ui.croster.RosterWindow;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dev-iteration tooling for the milking assistant feature. Implements the
 * {@link Feature} interface so the toolkit auto-wires the painter, the
 * {@code dev.milk.dump|snapshot} commands, and the replay handler. The
 * extra verbs {@code dev.milk.fire <uid>} and {@code dev.milk.clear} are
 * contributed via {@link #extraVerbs()}.
 *
 * <p>Loaded by {@code haven.dev.DebugBoot} on first frame.
 */
public final class MilkingAssistDebug implements Feature {
    static { DevFeature.register(new MilkingAssistDebug()); }

    public String name() { return "milk"; }
    public CFG<Boolean> toggle() { return CFG.DEBUG_MILKING_ASSIST; }
    public FeatureCapture protoCapture() { return MilkingAssist.get().capture(); }

    /** Build the JSON snapshot used by dump, snapshot, and (read back) the painter. */
    public JSONObject capture() {
	long now = System.currentTimeMillis();
	Glob glob = anyGlob();
	JSONObject body = new JSONObject();
	body.put("now_ms", now);
	body.put("retry_count", MilkingAssist.debugRetryCount());

	MilkingAssist.Pending p = MilkingAssist.debugPeekPending();
	if(p != null) {
	    JSONObject pend = new JSONObject();
	    pend.put("cattle_id_bits",   p.cattleId.bits);
	    pend.put("cattle_id_hex",    p.cattleId.toString());
	    pend.put("deadline_ms",      p.deadline);
	    pend.put("ttl_remaining_ms", p.deadline - now);
	    body.put("pending", pend);
	} else {
	    body.put("pending", JSONObject.NULL);
	}

	RosterWindow rw = (glob != null) ? RosterWindow.rosters.get(glob) : null;
	body.put("has_roster_window", rw != null);
	body.put("toggle_on", rw != null && rw.milkingAssist);

	JSONArray roster = new JSONArray();
	int marked = 0, lactating = 0;
	boolean pendingInRoster = false;
	long pendBits = (p != null) ? p.cattleId.bits : 0L;
	if(rw != null) {
	    for(CattleRoster<?> cr : rw.children(CattleRoster.class)) {
		for(Object oe : cr.entries.values()) {
		    Entry e = (Entry) oe;
		    boolean m = (e.mark != null) && e.mark.a;
		    boolean l = entryLactating(e);
		    if(m) marked++;
		    if(l) lactating++;
		    if(p != null && e.id.bits == pendBits) pendingInRoster = true;
		    JSONObject jo = new JSONObject();
		    jo.put("id_bits",   e.id.bits);
		    jo.put("id_hex",    e.id.toString());
		    jo.put("name",      e.name == null ? JSONObject.NULL : e.name);
		    jo.put("marked",    m);
		    jo.put("lactating", l);
		    roster.put(jo);
		}
	    }
	}
	body.put("roster", roster);
	body.put("marked_count",     marked);
	body.put("lactating_count",  lactating);
	body.put("pending_in_roster", pendingInRoster);
	return body;
    }

    public void paint(GOut g, MapView mv) {
	UI ui = mv.ui;
	if(ui == null || ui.sess == null) return;
	JSONObject s = capture();
	int x = 10, y = 200, dy = 14;
	g.atext("milk: " + (s.optBoolean("toggle_on", false) ? "ON" : "off")
		+ (s.optBoolean("has_roster_window", false) ? "" : " (no roster window)"),
		new Coord(x, y), 0, 0); y += dy;
	JSONObject pend = s.optJSONObject("pending");
	if(pend != null) {
	    g.atext(String.format("pending: uid=%s ttl=%+dms%s",
				  pend.optString("cattle_id_hex", "?"),
				  pend.optLong("ttl_remaining_ms", 0L),
				  s.optBoolean("pending_in_roster", false) ? " in-roster" : " NOT in roster"),
		    new Coord(x, y), 0, 0); y += dy;
	} else {
	    g.atext("pending: none", new Coord(x, y), 0, 0); y += dy;
	}
	g.atext(String.format("retries: %d  roster: %d entries (%d marked, %d lactating)",
			      s.optInt("retry_count", 0),
			      s.optJSONArray("roster") != null ? s.getJSONArray("roster").length() : 0,
			      s.optInt("marked_count", 0),
			      s.optInt("lactating_count", 0)),
		new Coord(x, y), 0, 0);
    }

    public void replay(JSONObject body, PrintStream out) {
	boolean toggleOn        = body.optBoolean("toggle_on", false);
	boolean hasRosterWindow = body.optBoolean("has_roster_window", false);
	int retryCount          = body.optInt("retry_count", 0);
	long now                = body.optLong("now_ms", 0L);
	JSONObject pend         = body.optJSONObject("pending");
	out.println("captured: toggle=" + (toggleOn ? "ON" : "off")
		    + " roster_window=" + hasRosterWindow
		    + " retries=" + retryCount);

	if(pend == null) {
	    out.println("decision: pending was null at capture; nothing to resolve.");
	    return;
	}
	long pendBits  = pend.optLong("cattle_id_bits", 0L);
	String pendHex = pend.optString("cattle_id_hex", "0");
	long deadline  = pend.optLong("deadline_ms", 0L);
	long ttl       = pend.optLong("ttl_remaining_ms", deadline - now);

	JSONArray roster = body.optJSONArray("roster");
	int rosterSize = (roster == null) ? 0 : roster.length();
	JSONObject match = null;
	if(roster != null) {
	    for(int i = 0; i < roster.length(); i++) {
		JSONObject e = roster.optJSONObject(i);
		if(e == null) continue;
		if(e.optLong("id_bits", 0L) == pendBits) { match = e; break; }
	    }
	}
	out.println("pending: uid=" + pendHex + " deadline=" + deadline + " ttl=" + ttl + "ms");
	out.println("roster: " + rosterSize + " entries (" + (match != null ? "match" : "no match") + ")");

	String reason = null;
	if(!toggleOn)              reason = "milking-assist toggle OFF -- assist gates the right-click; in practice this means the attribution path was never armed.";
	else if(!hasRosterWindow)  reason = "no roster window present -- tryResolve early-returns at findRosterWindow.";
	else if(ttl < 0)           reason = "pending expired (overshot deadline by " + (-ttl) + "ms) -- tryResolve clears pending without deselecting.";
	else if(match == null)     reason = "pending UID not in roster -- tryResolve loops over entries and finds no match, leaves pending alone.";

	if(reason != null) {
	    out.println("decision: NO RESOLVE -- " + reason);
	    return;
	}
	boolean wasMarked = match.optBoolean("marked", false);
	boolean lactating = match.optBoolean("lactating", false);
	out.println("decision: WOULD DESELECT cattle uid=" + pendHex
		    + " name=" + match.optString("name", "?")
		    + " (currently marked=" + wasMarked + ", lactating=" + lactating + ")");
    }

    public Map<String, Console.Command> extraVerbs() {
	Map<String, Console.Command> verbs = new LinkedHashMap<>();
	verbs.put("fire", (cons, args) -> {
		if(args.length < 2) throw new Exception("usage: dev.milk.fire <uid_decimal_or_hex>");
		UID uid = parseUid(args[1]);
		MilkingAssist.debugSetPending(uid);
		MilkingAssist.Pending p = MilkingAssist.debugPeekPending();
		long ttl = (p != null) ? (p.deadline - System.currentTimeMillis()) : 0;
		cons.out.println("milk: pending set to uid=" + uid + " (ttl=" + ttl + "ms)");
	    });
	verbs.put("clear", (cons, args) -> {
		MilkingAssist.debugClearPending();
		cons.out.println("milk: pending cleared");
	    });
	return verbs;
    }

    // ---- Helpers ----

    private static Glob anyGlob() {
	Iterator<Glob> it = RosterWindow.rosters.keySet().iterator();
	return it.hasNext() ? it.next() : null;
    }

    private static UID parseUid(String s) {
	String x = s.trim();
	long bits;
	if(x.startsWith("0x") || x.startsWith("0X")) bits = Long.parseUnsignedLong(x.substring(2), 16);
	else if(x.matches("[0-9a-fA-F]{16}"))         bits = Long.parseUnsignedLong(x, 16);
	else                                          bits = Long.parseUnsignedLong(x);
	return UID.of(bits);
    }

    private static boolean entryLactating(Object entry) {
	if(entry == null) return false;
	Field f = findField(entry.getClass(), "lactate");
	if(f == null) return false;
	try {
	    Object v = f.get(entry);
	    return (v instanceof Boolean) && ((Boolean)v).booleanValue();
	} catch(IllegalAccessException ignored) { return false; }
    }

    private static Field findField(Class<?> cls, String name) {
	for(Class<?> c = cls; c != null; c = c.getSuperclass()) {
	    try {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	    } catch(NoSuchFieldException ignored) {}
	}
	return null;
    }
}
