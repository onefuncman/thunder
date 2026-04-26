package haven.dev;

import haven.CFG;
import haven.Debug;
import haven.GOut;
import haven.Loading;
import haven.MapView;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pluggable screen-space debug overlay registered against {@link MapView}.
 * Features register a painter once at startup; {@code MapView.draw} calls
 * {@link Registry#paintAll} once per frame after the existing per-frame
 * overlays. Painters are gated by a feature {@link CFG} flag and any
 * exception or {@link Loading} is swallowed so a debug painter can never
 * crash the draw loop.
 *
 * <p>For 3D world-space debug visualization (per-gob outlines like
 * {@code Hitbox}), keep using the existing per-gob {@code Rendered} /
 * {@code SlottedNode} pattern. This registry is for features that read
 * state and draw via {@link GOut} in screen space.
 */
public interface DebugDraw {
    /** CFG toggle that gates this overlay; paintAll skips when false. */
    CFG<Boolean> toggle();

    /** Paint into the MapView's screen-space {@link GOut}. Must not mutate game state. */
    void paint(GOut g, MapView mv);

    final class Registry {
	private static final List<DebugDraw> painters = new CopyOnWriteArrayList<>();

	// Force-load feature debug classes the first time the registry is
	// touched. Each *Debug class registers itself from its static block.
	static {
	    DebugBoot.init();
	}

	public static void register(DebugDraw d)   { painters.add(d); }
	public static void unregister(DebugDraw d) { painters.remove(d); }

	/** Called once per frame from {@code MapView.draw} after partydraw. */
	public static void paintAll(GOut g, MapView mv) {
	    for(DebugDraw d : painters) {
		boolean on;
		try {
		    Boolean v = d.toggle().get();
		    on = (v != null) && v.booleanValue();
		} catch(RuntimeException re) {
		    on = false;
		}
		if(!on) continue;
		try {
		    d.paint(g, mv);
		} catch(Loading l) {
		    // Resource not ready yet; skip this frame.
		} catch(RuntimeException re) {
		    Debug.log.printf("[debugdraw] %s threw: %s%n", d.getClass().getName(), re);
		}
	    }
	}

	private Registry() {}
    }
}
