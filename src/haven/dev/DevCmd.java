package haven.dev;

import haven.Console;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Registry and namespacing helper for {@code dev.<feature>.<verb>} console
 * commands. Each feature registers its dev commands here from its
 * {@code *Debug} class's static block; the helper installs each one as a
 * global {@link Console} command (via {@link Console#setscmd}) and tracks
 * them by feature so the {@code dev} meta-command can list them.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@code feature} is a single short token (e.g. {@code milk},
 *       {@code snap}, {@code path}).</li>
 *   <li>{@code verb} describes the action: {@code dump}, {@code fire},
 *       {@code clear}, {@code snapshot}.</li>
 *   <li>{@code dev <feature>} (no dot) lists registered verbs for a feature.
 *       {@code dev} alone lists registered features.</li>
 * </ul>
 */
public final class DevCmd {
    private static final Map<String, Map<String, Console.Command>> byFeature = new TreeMap<>();

    static {
	Console.setscmd("dev", new Console.Command() {
		public void run(Console cons, String[] args) {
		    if(args.length < 2) {
			synchronized(byFeature) {
			    if(byFeature.isEmpty()) {
				cons.out.println("(no dev features registered)");
				return;
			    }
			    cons.out.println("dev features: " + String.join(", ", byFeature.keySet()));
			}
			cons.out.println("usage: dev <feature>             -- list verbs for <feature>");
			cons.out.println("       dev.<feature>.<verb> [..] -- run a registered command");
			return;
		    }
		    String feature = args[1];
		    Map<String, Console.Command> verbs;
		    synchronized(byFeature) { verbs = byFeature.get(feature); }
		    if(verbs == null) {
			cons.out.println("no dev." + feature + ".* commands registered");
			return;
		    }
		    cons.out.println("dev." + feature + ".*: " + String.join(", ", verbs.keySet()));
		}
	    });
    }

    /** Register a {@code dev.<feature>.<verb>} command. */
    public static void register(String feature, String verb, Console.Command cmd) {
	if(feature == null || verb == null || cmd == null)
	    throw new IllegalArgumentException("feature, verb, cmd must be non-null");
	Console.setscmd("dev." + feature + "." + verb, cmd);
	synchronized(byFeature) {
	    byFeature.computeIfAbsent(feature, f -> new LinkedHashMap<>()).put(verb, cmd);
	}
    }

    private DevCmd() {}
}
