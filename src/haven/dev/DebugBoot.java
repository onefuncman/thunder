package haven.dev;

import haven.Debug;

/**
 * Force-loads feature debug classes so their static initializers run.
 * Each feature debug class registers itself with {@link DebugDraw.Registry},
 * {@link DevCmd}, and {@link DebugReplay} from its own static block.
 *
 * <p>Adding a feature: append a {@link #touch(String)} call to {@link #init}.
 * Errors are logged, never thrown -- a missing or broken feature debug class
 * must not break the game.
 */
public final class DebugBoot {
    private static volatile boolean done = false;

    public static synchronized void init() {
	if(done) return;
	done = true;
	touch("thunder.MilkingAssistDebug");
    }

    private static void touch(String fqcn) {
	try {
	    Class.forName(fqcn);
	} catch(ClassNotFoundException e) {
	    Debug.log.printf("[debugboot] feature debug class not found: %s%n", fqcn);
	} catch(Throwable t) {
	    Debug.log.printf("[debugboot] init failed for %s: %s%n", fqcn, t);
	}
    }

    private DebugBoot() {}
}
