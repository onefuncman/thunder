package haven.dev;

import haven.CFG;
import haven.Console;
import haven.GOut;
import haven.MapView;
import org.json.JSONObject;

import java.nio.file.Path;
import java.util.Map;

/**
 * One-call registration helper for {@link Feature} implementations. Wires
 * the painter into {@link DebugDraw.Registry}, registers
 * {@code dev.<name>.dump} and {@code dev.<name>.snapshot} via {@link DevCmd},
 * registers any {@link Feature#extraVerbs() extra verbs}, and registers the
 * replay handler with {@link DebugReplay}.
 *
 * <p>Typical usage from a feature's {@code *Debug} class:
 * <pre>
 *   public final class MilkingAssistDebug implements Feature {
 *       static { DevFeature.register(new MilkingAssistDebug()); }
 *       // ... implement name/toggle/capture/paint/replay/extraVerbs ...
 *   }
 * </pre>
 */
public final class DevFeature {
    public static void register(Feature f) {
	final String name = f.name();
	if(name == null || name.isEmpty())
	    throw new IllegalArgumentException("Feature.name() must be non-empty");

	DebugDraw.Registry.register(new DebugDraw() {
		public CFG<Boolean> toggle() { return f.toggle(); }
		public void paint(GOut g, MapView mv) { f.paint(g, mv); }
	    });

	DevCmd.register(name, "dump", (cons, args) ->
		cons.out.println(f.capture().toString(2)));

	DevCmd.register(name, "snapshot", (cons, args) -> {
		Path p = DebugSnapshot.write(name, f.capture());
		cons.out.println(name + ": wrote " + p);
	    });

	for(Map.Entry<String, Console.Command> e : f.extraVerbs().entrySet()) {
	    DevCmd.register(name, e.getKey(), e.getValue());
	}

	DebugReplay.register(name, f::replay);
    }

    private DevFeature() {}
}
