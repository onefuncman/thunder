package haven;

import java.util.Map;

/**
 * Per-source volume control for specific game sounds (cauldrons, querns,
 * noisy hats, ...), on top of the master/ambient channel volumes.
 *
 * Inspired by Hurricane's per-sound sliders, but data-driven: one Group row
 * here becomes a slider in Options -> Audio settings and a multiplier in the
 * audio pipeline. The pipeline hooks are AudioSprite (one-shot "cl" clips and
 * "rep" loops, applied when the sound starts) and ActAudio.Ambience.Glob
 * ("amb" loops, re-read continuously so the slider works on a sound that is
 * already playing). Res names taken from Hurricane's matchers.
 */
public class SfxVol {
    public enum Group {
	CAULDRON("Cauldrons", "sfx/terobjs/cauldron"),
	QUERN("Querns", "sfx/terobjs/quern"),
	GRINDER("Grinders & squeaks", "sfx/terobjs/grinder", "sfx/squeak"),
	HATS("Noisy hats", "sfx/items/hats/", "sfx/items/bells"),
	CLAP("Clapping", "sfx/borka/clap"),
	BUTCHER("Butchering", "sfx/borka/butcher"),
	MINING("Mining & chipping", "sfx/items/pickaxe", "sfx/mineout", "sfx/chip"),
	SWOOSH("Weapon swooshes", "sfx/swoosh"),
	CREAK("Structure creaks", "sfx/creak"),
	KNARR("Knarrs", "sfx/terobjs/knarr");

	public final String label;
	/* Exact res name, or a prefix when it ends with '/'. */
	private final String[] names;

	Group(String label, String... names) {
	    this.label = label;
	    this.names = names;
	}

	private boolean matches(String resname) {
	    for(String n : names) {
		if(n.endsWith("/") ? resname.startsWith(n) : resname.equals(n))
		    return true;
	    }
	    return false;
	}
    }

    private SfxVol() {}

    public static Group group(String resname) {
	if(resname == null) return null;
	for(Group g : Group.values()) {
	    if(g.matches(resname)) return g;
	}
	return null;
    }

    public static int percent(Group g) {
	Integer v = CFG.SFX_VOLUMES.get().get(g.name());
	return (v == null) ? 100 : Math.max(0, Math.min(100, v));
    }

    public static void set(Group g, int percent) {
	Map<String, Integer> m = CFG.SFX_VOLUMES.get();
	m.put(g.name(), percent);
	CFG.SFX_VOLUMES.set(m);
    }

    /** Volume multiplier for a resource's sounds; 1.0 when it belongs to no group. */
    public static double mult(Resource res) {
	if(res == null) return 1.0;
	Group g = group(res.name);
	return (g == null) ? 1.0 : percent(g) / 100.0;
    }

    /** Wrap a clip stream in a volume adjustment when its resource has one. */
    public static Audio.CS adjust(Resource res, Audio.CS stream) {
	double m = mult(res);
	return (m == 1.0) ? stream : new Audio.VolAdjust(stream, m);
    }
}
