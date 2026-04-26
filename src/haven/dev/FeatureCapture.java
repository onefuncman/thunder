package haven.dev;

import haven.Session;
import haven.Utils;
import haven.proto.ProtoBus;
import haven.proto.ProtoEvent;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.function.Consumer;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

/**
 * Per-feature one-shot protocol capture. The user arms it via
 * {@code dev.<feature>.capture}; on the next call to
 * {@link #beginIfArmed} the feature begins recording all
 * {@link ProtoEvent} traffic via {@link ProtoBus} into a bounded ring
 * buffer. {@link #endIfActive} writes a JSONL file to
 * {@code play/dev-snapshots/&lt;feature&gt;/capture-&lt;outcome&gt;-&lt;ts&gt;.jsonl}
 * and detaches.
 *
 * <p>Distinct from the long-running {@link haven.proto.RetroCapture}: a
 * feature capture is bounded to one feature invocation (arm to
 * resolve/expire), giving the user a focused forensic artifact instead
 * of a rolling protocol log.
 *
 * <p>Threading: callers may invoke {@link #beginIfArmed} and
 * {@link #endIfActive} from the UI/event thread; protocol events come in
 * via {@link ProtoBus#addListener}, which is drained on
 * {@link ProtoBus#drainToUI}. Internal state is synchronized.
 */
public final class FeatureCapture {
    public static final int MAX_EVENTS = 5000;

    private final String feature;
    private boolean armed = false;
    private boolean active = false;
    private boolean held = false;
    private Session sess;
    private Consumer<ProtoEvent> listener;
    private final ArrayDeque<ProtoEvent> buf = new ArrayDeque<>();
    private long beginMs;
    private double beginRtime;
    private JSONObject beginMeta;

    public FeatureCapture(String feature) {
	if(feature == null || feature.isEmpty())
	    throw new IllegalArgumentException("feature must be non-empty");
	this.feature = feature;
    }

    /** Arm a one-shot capture; the next {@link #beginIfArmed} starts recording. */
    public synchronized void arm() { armed = true; }

    /** Drop the armed flag without starting a capture. */
    public synchronized void disarm() { armed = false; }

    public synchronized boolean isArmed()  { return armed; }
    public synchronized boolean isActive() { return active; }
    public synchronized int     bufferSize() { return buf.size(); }

    /**
     * If armed, begin capturing protocol events from {@code sess.protoBus}.
     * Idempotent: a no-op if not armed or already active. Stores
     * {@code meta} verbatim into the dump header.
     */
    public synchronized void beginIfArmed(Session sess, JSONObject meta) {
	if(!armed || active) return;
	if(sess == null || sess.protoBus == null) return;
	armed = false;
	active = true;
	this.sess = sess;
	this.beginMs = System.currentTimeMillis();
	this.beginRtime = Utils.rtime();
	this.beginMeta = meta;
	sess.protoBus.acquireCapture();
	held = true;
	listener = this::onEvent;
	sess.protoBus.addListener(listener);
    }

    /**
     * If a capture is active, detach, write the dump file, and reset.
     * Returns the written path or {@code null} if no capture was active.
     */
    public Path endIfActive(String outcome, JSONObject endMeta) {
	ArrayDeque<ProtoEvent> snapshot;
	long beginMs;
	double beginRtime;
	JSONObject beginMeta;
	synchronized(this) {
	    if(!active) return null;
	    if(listener != null && sess != null && sess.protoBus != null)
		sess.protoBus.removeListener(listener);
	    if(held && sess != null && sess.protoBus != null)
		sess.protoBus.releaseCapture();
	    held = false;
	    listener = null;
	    active = false;
	    snapshot = new ArrayDeque<>(buf);
	    beginMs = this.beginMs;
	    beginRtime = this.beginRtime;
	    beginMeta = this.beginMeta;
	    buf.clear();
	    sess = null;
	}
	try {
	    return writeDump(outcome, beginMs, beginRtime, beginMeta, endMeta, snapshot);
	} catch(IOException e) {
	    return null;
	}
    }

    private void onEvent(ProtoEvent e) {
	synchronized(this) {
	    if(!active) return;
	    buf.addLast(e);
	    while(buf.size() > MAX_EVENTS) buf.pollFirst();
	}
    }

    private Path writeDump(String outcome, long beginMs, double beginRtime,
			   JSONObject beginMeta, JSONObject endMeta,
			   ArrayDeque<ProtoEvent> events) throws IOException {
	Path dir = Utils.path(System.getProperty("user.dir", "."))
	    .resolve("dev-snapshots").resolve(feature);
	Files.createDirectories(dir);
	String safeOutcome = (outcome == null || outcome.isEmpty()) ? "unknown" : outcome.replaceAll("[^a-zA-Z0-9_-]", "_");
	String name = String.format("capture-%s-%tY%<tm%<td-%<tH%<tM%<tS-%<tL.jsonl",
				    safeOutcome, new Date());
	Path path = dir.resolve(name);
	long endMs = System.currentTimeMillis();
	try(Writer w = Files.newBufferedWriter(path, CREATE, TRUNCATE_EXISTING)) {
	    JSONObject header = new JSONObject();
	    header.put("type", "header");
	    header.put("feature", feature);
	    header.put("outcome", outcome);
	    header.put("begin_ms", beginMs);
	    header.put("end_ms", endMs);
	    header.put("duration_ms", endMs - beginMs);
	    header.put("event_count", events.size());
	    if(beginMeta != null) header.put("begin_meta", beginMeta);
	    if(endMeta != null)   header.put("end_meta",   endMeta);
	    w.write(header.toString()); w.write('\n');
	    for(ProtoEvent e : events) {
		JSONObject o = new JSONObject();
		o.put("t", e.timestamp);
		o.put("rel", e.timestamp - beginRtime);
		o.put("dir", e.dir == ProtoEvent.Direction.IN ? "in" : "out");
		o.put("cat", e.category.name());
		o.put("type", e.typeName);
		o.put("tid", e.typeId);
		o.put("summary", e.summary == null ? "" : e.summary);
		if(e.detail != null && !e.detail.isEmpty() && !e.detail.equals(e.summary))
		    o.put("detail", e.detail);
		o.put("size", e.sizeBytes);
		if(e.gobId != 0)    o.put("gob", e.gobId);
		if(e.widgetId != 0) o.put("wid", e.widgetId);
		w.write(o.toString()); w.write('\n');
	    }
	}
	return path;
    }
}
