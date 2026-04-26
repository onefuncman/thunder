package haven.dev;

import haven.Utils;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

/**
 * One-shot JSONL writer for feature state snapshots. Layout:
 * <pre>
 *   &lt;cwd&gt;/dev-snapshots/&lt;feature&gt;/&lt;timestamp&gt;.jsonl
 * </pre>
 * The first line is a header (type, feature, generated_at_ms); the second
 * line is the feature-specific body. Mirrors the
 * {@code haven.proto.RetroCapture} convention so the same tooling
 * (grep, jq, the editor) works on both.
 *
 * <p>The client's working directory is {@code play/} when launched normally,
 * so files end up under {@code play/dev-snapshots/&lt;feature&gt;/}. The
 * {@code play/} directory is already gitignored so no exclusion is needed.
 */
public final class DebugSnapshot {
    /**
     * Write {@code body} to a fresh per-feature snapshot file.
     *
     * @return the path written
     */
    public static Path write(String feature, JSONObject body) throws IOException {
	if(feature == null || body == null)
	    throw new IllegalArgumentException("feature and body must be non-null");
	Path dir = featureDir(feature);
	String name = String.format("%tY%<tm%<td-%<tH%<tM%<tS-%<tL.jsonl", new Date());
	Path file = dir.resolve(name);
	try(Writer w = Files.newBufferedWriter(file, CREATE, TRUNCATE_EXISTING)) {
	    JSONObject header = new JSONObject();
	    header.put("type", "header");
	    header.put("feature", feature);
	    header.put("generated_at_ms", System.currentTimeMillis());
	    w.write(header.toString()); w.write('\n');
	    w.write(body.toString());   w.write('\n');
	}
	return file;
    }

    /**
     * Write a fresh per-dump file containing the pretty-printed JSON body.
     * Used by {@code dev.<feature>.dump} so each invocation lands in its own
     * timestamped file instead of dumping to the chat console.
     */
    public static Path writeDump(String feature, JSONObject body) throws IOException {
	if(feature == null || body == null)
	    throw new IllegalArgumentException("feature and body must be non-null");
	String name = String.format("dump-%tY%<tm%<td-%<tH%<tM%<tS-%<tL.json", new Date());
	Path file = featureDir(feature).resolve(name);
	try(Writer w = Files.newBufferedWriter(file, CREATE, TRUNCATE_EXISTING)) {
	    w.write(body.toString(2));
	    w.write('\n');
	}
	return file;
    }

    private static Path featureDir(String feature) throws IOException {
	Path dir = Utils.path(System.getProperty("user.dir", "."))
	    .resolve("dev-snapshots").resolve(feature);
	Files.createDirectories(dir);
	return dir;
    }

    private DebugSnapshot() {}
}
