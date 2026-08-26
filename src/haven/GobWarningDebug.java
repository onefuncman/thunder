package haven;

import haven.dev.DevFeature;
import haven.dev.Feature;
import me.ender.gob.KinInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static haven.GobWarning.WarnMethod.*;

/* Dev-toolkit feature exposing the warning decision chain (dev.warn.*).
 * dev.warn.dump prints, per candidate gob, every stage of detection --
 * tags, kin, mannequin check, categorize result, attrib state, config
 * gates -- so a "player warnings don't work" report can be pinned to the
 * exact failing stage. The per-gob diagnosis is a pure function over the
 * captured JSON so the replay driver and unit tests share it. */
public final class GobWarningDebug implements Feature {
    static { DevFeature.register(new GobWarningDebug()); }

    private static volatile Glob lastGlob;

    /* Called from Gob.updateWarnings so a glob is known whenever any gob
     * has ticked, independent of whether any warning was ever created. */
    static void noteGlob(Glob glob) {
	lastGlob = glob;
    }

    public String name() { return "warn"; }
    public CFG<Boolean> toggle() { return CFG.DEBUG_WARN; }

    public JSONObject capture() {
	JSONObject body = new JSONObject();
	JSONObject cfg = new JSONObject();
	for(GobWarning.WarnTarget t : GobWarning.WarnTarget.values()) {
	    cfg.put(t.name(), new JSONObject()
		.put("highlight", GobWarning.cfg(t, highlight))
		.put("message", GobWarning.cfg(t, message)));
	}
	body.put("cfg", cfg);
	Glob glob = lastGlob;
	if(glob == null) {
	    body.put("gobs", new JSONArray());
	    body.put("note", "no glob ticked yet -- not logged in?");
	    return body;
	}
	List<Gob> gobs = new ArrayList<>();
	synchronized(glob.oc) {
	    for(Gob gob : glob.oc) {gobs.add(gob);}
	}
	JSONArray arr = new JSONArray();
	for(Gob gob : gobs) {
	    GobWarning w = gob.getattr(GobWarning.class);
	    if(!gob.anyOf(GobTag.PLAYER, GobTag.AGGRESSIVE, GobTag.GEM, GobTag.MIDGES) && (w == null))
		continue;
	    JSONObject j = new JSONObject();
	    j.put("id", gob.id);
	    String res = gob.resid();
	    j.put("res", (res == null) ? "?" : res);
	    JSONArray tags = new JSONArray();
	    for(GobTag t : new GobTag[]{GobTag.PLAYER, GobTag.ME, GobTag.FRIEND, GobTag.FOE,
					GobTag.AGGRESSIVE, GobTag.DEAD, GobTag.KO, GobTag.IN_COMBAT}) {
		if(gob.is(t)) {tags.put(t.name());}
	    }
	    j.put("tags", tags);
	    Boolean me = gob.isMe();
	    j.put("isMe", (me == null) ? "unresolved" : me.toString());
	    KinInfo ki = gob.kin();
	    j.put("kin", (ki == null) ? "none" : ("group=" + ki.group + " villager=" + ki.isVillager));
	    j.put("foe_eval", KinInfo.isFoe(gob));
	    j.put("mannequin", GobWarning.mannequinState(gob));
	    GobWarning.WarnTarget cat = GobWarning.categorize(gob);
	    j.put("categorize", (cat == null) ? "null" : cat.name());
	    j.put("warning_attrib", (w == null) ? "absent" : String.valueOf(w.target()));
	    arr.put(j);
	}
	body.put("gobs", arr);
	return body;
    }

    public void paint(GOut g, MapView mv) {
	JSONObject s = capture();
	JSONArray gobs = s.optJSONArray("gobs");
	int x = 10, y = 260, dy = 14;
	g.atext("warn: " + ((gobs == null) ? 0 : gobs.length()) + " candidate gob(s)", new Coord(x, y), 0, 0);
	y += dy;
	if(gobs == null)
	    return;
	for(int i = 0; (i < gobs.length()) && (i < 8); i++) {
	    JSONObject j = gobs.getJSONObject(i);
	    g.atext(String.format("#%d %s -> %s", j.optLong("id"), shortRes(j.optString("res", "?")), diagnose(j)),
		    new Coord(x, y), 0, 0);
	    y += dy;
	}
    }

    private static String shortRes(String res) {
	int idx = res.lastIndexOf('/');
	return (idx < 0) ? res : res.substring(idx + 1);
    }

    /* Pure per-gob diagnosis over the captured entry: names the first
     * stage of the detection chain that explains the gob's state. */
    static String diagnose(JSONObject j) {
	List<String> tags = new ArrayList<>();
	JSONArray ta = j.optJSONArray("tags");
	if(ta != null) {
	    for(int i = 0; i < ta.length(); i++) {tags.add(ta.getString(i));}
	}
	String cat = j.optString("categorize", "null");
	String attrib = j.optString("warning_attrib", "absent");
	if(tags.contains("ME"))
	    return "own character; never warned";
	if(tags.contains("PLAYER")) {
	    if("unresolved".equals(j.optString("isMe")))
		return "BLOCKED: isMe unresolved, FOE/FRIEND tag not assigned yet";
	    if(tags.contains("FRIEND"))
		return "kinned/villager (" + j.optString("kin", "?") + "); not a foe by design";
	    if(!tags.contains("FOE"))
		return "BLOCKED: player has neither FOE nor FRIEND tag";
	    if(tags.contains("DEAD") || tags.contains("KO"))
		return "foe but dead/KO; not warned by design";
	    if("YES".equals(j.optString("mannequin")))
		return "mannequin stand detected; suppressed by design";
	    if("PENDING".equals(j.optString("mannequin")))
		return "WAITING: equipment still loading, detection retries next tick";
	    if("null".equals(cat))
		return "BUG: foe player but categorize() returned null";
	    if("absent".equals(attrib))
		return "BUG: categorized " + cat + " but no warning attrib";
	    if("null".equals(attrib))
		return "BUG: husk warning attrib (no target)";
	    return "OK: warned as " + attrib;
	}
	if("null".equals(cat) && !"absent".equals(attrib))
	    return "BUG: warning attrib present but categorize() is null";
	if(!"null".equals(cat) && "absent".equals(attrib))
	    return "BUG: categorized " + cat + " but no warning attrib";
	if("null".equals(attrib))
	    return "BUG: husk warning attrib (no target)";
	return ("absent".equals(attrib)) ? "not warnable" : ("OK: warned as " + attrib);
    }

    public void replay(JSONObject body, PrintStream out) {
	JSONObject cfg = body.optJSONObject("cfg");
	if(cfg != null)
	    out.println("cfg: " + cfg);
	JSONArray gobs = body.optJSONArray("gobs");
	if(gobs == null || gobs.length() == 0) {
	    out.println("no candidate gobs captured" + (body.has("note") ? (" (" + body.optString("note") + ")") : ""));
	    return;
	}
	for(int i = 0; i < gobs.length(); i++) {
	    JSONObject j = gobs.getJSONObject(i);
	    out.printf("#%d %s tags=%s kin=%s mannequin=%s categorize=%s attrib=%s%n   -> %s%n",
		       j.optLong("id"), j.optString("res", "?"), j.optJSONArray("tags"),
		       j.optString("kin", "?"), j.optString("mannequin", "?"),
		       j.optString("categorize", "?"), j.optString("warning_attrib", "?"),
		       diagnose(j));
	}
    }
}
