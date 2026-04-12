package haven.proto;

import haven.*;
import haven.res.ui.croster.CattleId;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Walks every Gob with a CattleId attached (i.e. animals tracked by the
 * cattle roster) and dumps each unique resource's bindpose Z extent so
 * the cattle-roster name-offset can be tuned per-species.
 *
 * One line per resource. First sighting of a resource wins; later ones
 * are ignored (the skeleton's bindpose is static per resource).
 */
public class CattleHeightsCapture {
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");

    public static Path capture(GameUI gui) throws IOException {
	Path dir = Paths.get("snapshots");
	Files.createDirectories(dir);
	Path out = dir.resolve("cattle-heights-" + STAMP.format(new Date()) + ".txt");
	String text = render(gui);
	Files.write(out, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	return out;
    }

    public static String render(GameUI gui) {
	StringBuilder sb = new StringBuilder();
	sb.append("# Cattle heights ").append(new Date()).append('\n');
	sb.append("# Fields: resname meshMaxZ meshMinZ boneMaxZ boneMaxZnoIK topZBone topNonIKBone\n");
	sb.append("# meshMaxZ = true geometric top (bind-pose vertex positions). Prefer this.\n");

	Map<String, String> byRes = new TreeMap<>();
	int seen = 0, withSkel = 0;

	Glob glob = gui.ui.sess.glob;
	synchronized(glob.oc) {
	    for(Gob g : glob.oc) {
		CattleId cid = g.getattr(CattleId.class);
		if(cid == null) continue;
		seen++;
		Drawable d = g.drawable;
		Resource res = (d != null) ? d.getres() : null;
		if(res == null) continue;
		String key = (d instanceof Composite) ? ((Composite)d).resId() : null;
		if(key == null) key = res.name;
		if(byRes.containsKey(key)) continue;

		Skeleton skel = null;
		List<Resource> meshResources = new ArrayList<>();
		meshResources.add(res);
		if(d instanceof Composite) {
		    Composite cd = (Composite)d;
		    skel = cd.comp.skel;
		    for(Composited.MD md : cd.comp.cmod) {
			try {meshResources.add(md.mod.get());} catch(Loading l) {}
		    }
		}
		if(skel == null) {
		    Skeleton.Res sr = res.layer(Skeleton.Res.class);
		    if(sr != null) skel = sr.s;
		}

		float meshMinZ = Float.POSITIVE_INFINITY, meshMaxZ = Float.NEGATIVE_INFINITY;
		for(Resource mr : meshResources) {
		    for(VertexBuf.VertexRes vr : mr.layers(VertexBuf.VertexRes.class)) {
			for(VertexBuf.AttribData a : vr.b.bufs) {
			    if(!(a instanceof VertexBuf.VertexData)) continue;
			    FloatBuffer fb = ((VertexBuf.VertexData)a).data;
			    int n = fb.capacity();
			    for(int i = 2; i < n; i += 3) {
				float z = fb.get(i);
				if(z > meshMaxZ) meshMaxZ = z;
				if(z < meshMinZ) meshMinZ = z;
			    }
			}
		    }
		}

		String boneStats = "no-skel";
		if(skel != null) {
		    Skeleton.Pose bind = skel.bindpose;
		    float bMaxZ = Float.NEGATIVE_INFINITY, bMaxZn = Float.NEGATIVE_INFINITY;
		    String topZ = null, topZn = null;
		    for(int i = 0; i < skel.blist.length; i++) {
			String nm = skel.blist[i].name;
			float z = bind.gpos[i][2];
			if(z > bMaxZ) {bMaxZ = z; topZ = nm;}
			if(!isIKBone(nm) && z > bMaxZn) {bMaxZn = z; topZn = nm;}
		    }
		    boneStats = String.format("boneMaxZ=%6.2f  boneMaxZnoIK=%6.2f  topZBone=%-14s topNonIKBone=%s",
			bMaxZ, bMaxZn, topZ, (topZn == null) ? "(none)" : topZn);
		}

		String meshStats = (meshMaxZ == Float.NEGATIVE_INFINITY)
		    ? "meshMaxZ=   N/A  meshMinZ=   N/A"
		    : String.format("meshMaxZ=%6.2f  meshMinZ=%6.2f", meshMaxZ, meshMinZ);
		byRes.put(key, String.format("%-72s  %s  %s", key, meshStats, boneStats));
		if(skel != null) withSkel++;
	    }
	}

	sb.append("# gobs scanned: ").append(seen).append(", unique resources with skeleton: ").append(withSkel).append('\n');
	sb.append('\n');
	for(String line : byRes.values()) sb.append(line).append('\n');
	return sb.toString();
    }

    private static boolean isIKBone(String name) {
	if(name == null) return(false);
	String s = name.toLowerCase(Locale.ROOT);
	return(s.startsWith("ik") || s.contains("-ik") || s.contains("_ik") || s.contains("mover"));
    }
}
