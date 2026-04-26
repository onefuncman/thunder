package haven.dev;

import haven.CFG;
import haven.Console;
import haven.GOut;
import haven.MapView;
import org.json.JSONObject;

import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;

/**
 * One-stop interface a feature implements to plug into the dev iteration
 * toolkit. Register an implementation via {@link DevFeature#register} from
 * the feature's {@code *Debug} class's static block; the helper wires the
 * {@link DebugDraw} painter, the {@code dev.<name>.dump|snapshot} console
 * commands, the {@link DebugReplay} handler, and any {@link #extraVerbs()}
 * the feature contributes.
 *
 * <p>The shared {@link #capture()} method backs both the {@code dump} verb
 * and the snapshot writer, so what you see at runtime and what gets saved
 * to disk cannot drift.
 */
public interface Feature {
    /** Short token used as the feature key (e.g. {@code "milk"}, {@code "snap"}). */
    String name();

    /** CFG toggle that gates the screen-space overlay. */
    CFG<Boolean> toggle();

    /**
     * Snapshot of the feature's debug-relevant state, as a JSON object.
     * Called per-frame by the painter (when toggle is on), on demand by
     * {@code dev.<name>.dump}, and once by {@code dev.<name>.snapshot}.
     * Implementations should be cheap enough to call every frame; treat
     * this as the read-side projection of feature state.
     */
    JSONObject capture();

    /** Screen-space overlay; runs every frame when {@link #toggle()} is true. */
    void paint(GOut g, MapView mv);

    /**
     * Pure replay of the feature's decision logic against a captured body.
     * Called from {@link DebugReplay#main(String[])} after loading the
     * snapshot file. Operate on the JSON only; do not touch live game state.
     */
    void replay(JSONObject body, PrintStream out) throws Exception;

    /**
     * Optional additional console commands registered as
     * {@code dev.<name>.<verb>}. Use this for actions beyond the standard
     * {@code dump}/{@code snapshot} pair (e.g. {@code fire}, {@code clear}).
     */
    default Map<String, Console.Command> extraVerbs() { return Collections.emptyMap(); }
}
