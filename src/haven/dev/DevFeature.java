package haven.dev;

import haven.CFG;
import haven.Console;
import haven.GOut;
import haven.MapView;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * One-call registration helper for {@link Feature} implementations. Wires
 * the painter into {@link DebugDraw.Registry}, registers
 * {@code dev.<name>.dump}, {@code dev.<name>.snapshot}, and
 * {@code dev.<name>.debug} via {@link DevCmd}, registers any
 * {@link Feature#extraVerbs() extra verbs}, and registers the replay handler
 * with {@link DebugReplay}. Also installs the global {@code dev.debug}
 * lister that shows every feature's debug-toggle state.
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
    private static final Map<String, Feature> features = new TreeMap<>();

    static {
	Console.setscmd("dev.debug", (cons, args) -> {
		synchronized(features) {
		    if(features.isEmpty()) {
			cons.out.println("(no dev features registered)");
			return;
		    }
		    for(Map.Entry<String, Feature> e : features.entrySet()) {
			cons.out.println("  " + stateOf(e.getValue()) + "  " + e.getKey());
		    }
		    cons.out.println("set with: dev.<name>.debug [true|false|toggle]");
		}
	    });
    }

    public static void register(Feature f) {
	final String name = f.name();
	if(name == null || name.isEmpty())
	    throw new IllegalArgumentException("Feature.name() must be non-empty");

	synchronized(features) { features.put(name, f); }

	DebugDraw.Registry.register(new DebugDraw() {
		public CFG<Boolean> toggle() { return f.toggle(); }
		public void paint(GOut g, MapView mv) { f.paint(g, mv); }
	    });

	DevCmd.register(name, "dump", (cons, args) -> {
		Path p = DebugSnapshot.writeDump(name, f.capture());
		cons.out.println(name + ": dumped to " + p);
	    });

	DevCmd.register(name, "snapshot", (cons, args) -> {
		Path p = DebugSnapshot.write(name, f.capture());
		cons.out.println(name + ": wrote " + p);
	    });

	DevCmd.register(name, "debug", (cons, args) -> {
		CFG<Boolean> t = f.toggle();
		if(args.length < 2) {
		    cons.out.println("dev." + name + ".debug = " + boolStr(t.get()));
		    return;
		}
		Boolean v = parseBool(args[1], t.get());
		if(v == null) {
		    cons.out.println("usage: dev." + name + ".debug [true|false|on|off|1|0|toggle]");
		    return;
		}
		t.set(v);
		cons.out.println("dev." + name + ".debug = " + boolStr(v));
	    });

	FeatureCapture cap = f.protoCapture();
	if(cap != null) {
	    DevCmd.register(name, "capture", (cons, args) -> {
		    cap.arm();
		    cons.out.println(name + ": capture armed -- next feature invocation will record protocol traffic and auto-dump on completion");
		});
	}

	for(Map.Entry<String, Console.Command> e : f.extraVerbs().entrySet()) {
	    DevCmd.register(name, e.getKey(), e.getValue());
	}

	DebugReplay.register(name, f::replay);
    }

    private static String stateOf(Feature f) {
	try {
	    Boolean v = f.toggle().get();
	    return (v != null && v) ? "ON " : "off";
	} catch(RuntimeException re) {
	    return "ERR";
	}
    }

    private static String boolStr(Boolean v) {
	return (v != null && v) ? "true" : "false";
    }

    private static Boolean parseBool(String s, Boolean current) {
	String t = s.trim().toLowerCase();
	switch(t) {
	    case "true": case "on":  case "1": case "yes": return Boolean.TRUE;
	    case "false": case "off": case "0": case "no": return Boolean.FALSE;
	    case "toggle": case "t": return !(current != null && current);
	    default: return null;
	}
    }

    private DevFeature() {}
}
