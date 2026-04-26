package haven.dev;

import org.json.JSONObject;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless replay driver for snapshots written by {@link DebugSnapshot}.
 * Each feature registers a {@link Handler} from its {@code *Debug} class's
 * static block. {@link #main(String[])} loads a snapshot file, dispatches
 * to the handler keyed by the snapshot's {@code feature} header, and prints
 * the result to stdout.
 *
 * <p>Run from the build via:
 * <pre>
 *   java -cp hafen.jar haven.dev.DebugReplay path/to/snapshot.jsonl
 * </pre>
 */
public final class DebugReplay {
    public interface Handler {
	/**
	 * Run the feature's pure logic against the captured state.
	 *
	 * @param body  the second JSONL line, parsed as JSON
	 * @param out   stream to print human-readable replay output on
	 */
	void replay(JSONObject body, PrintStream out) throws Exception;
    }

    private static final Map<String, Handler> handlers = new HashMap<>();

    public static synchronized void register(String feature, Handler h) {
	if(feature == null || h == null) throw new IllegalArgumentException();
	handlers.put(feature, h);
    }

    private static synchronized Handler handler(String feature) {
	return handlers.get(feature);
    }

    public static void main(String[] args) throws Exception {
	if(args.length < 1) {
	    System.err.println("usage: java -cp hafen.jar haven.dev.DebugReplay <snapshot.jsonl>");
	    System.exit(2);
	    return;
	}
	DebugBoot.init();
	Path file = Paths.get(args[0]);
	List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
	if(lines.size() < 2) {
	    System.err.println("snapshot has no body line: " + file);
	    System.exit(3); return;
	}
	JSONObject header = new JSONObject(lines.get(0));
	if(!"header".equals(header.optString("type", null))) {
	    System.err.println("first line is not a header: " + file);
	    System.exit(3); return;
	}
	String feature = header.optString("feature", null);
	if(feature == null) {
	    System.err.println("header missing 'feature' field");
	    System.exit(3); return;
	}
	Handler h = handler(feature);
	if(h == null) {
	    System.err.println("no replay handler for feature: " + feature
			       + " (registered: " + registeredFeatures() + ")");
	    System.exit(3); return;
	}
	JSONObject body = new JSONObject(lines.get(1));
	System.out.println("[replay] feature=" + feature + " file=" + file);
	h.replay(body, System.out);
    }

    private static synchronized String registeredFeatures() {
	if(handlers.isEmpty()) return "none";
	return String.join(", ", handlers.keySet());
    }

    private DebugReplay() {}
}
