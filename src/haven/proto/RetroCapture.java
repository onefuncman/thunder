package haven.proto;

import haven.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public class RetroCapture {
    private final Session sess;
    private final ArrayDeque<ProtoEvent> buf = new ArrayDeque<>();
    private final double windowSec;
    private final int maxEvents;
    private boolean enabled = false;
    private boolean captureHeld = false;
    private Consumer<ProtoEvent> listener;
    private boolean autoDump = false;
    private double lastDumpTime = 0;
    private Path lastDumpPath;
    private int lastDumpSize = 0;

    public RetroCapture(Session sess) {
	this.sess = sess;
	this.windowSec = Math.max(30, CFG.PROTO_RETRO_WINDOW_SEC.get());
	this.maxEvents = Math.max(1000, CFG.PROTO_RETRO_MAX_EVENTS.get());
    }

    public double window() { return windowSec; }

    public synchronized boolean isEnabled() { return enabled; }
    public synchronized boolean isAutoDump() { return autoDump; }
    public synchronized int size() { return buf.size(); }
    public synchronized Path lastDumpPath() { return lastDumpPath; }
    public synchronized int lastDumpSize() { return lastDumpSize; }

    public synchronized void setEnabled(boolean on) {
	if(on == enabled) return;
	enabled = on;
	if(on) {
	    if(sess.protoBus != null) {
		sess.protoBus.acquireCapture();
		captureHeld = true;
		listener = this::onEvent;
		sess.protoBus.addListener(listener);
	    }
	    lastDumpTime = Utils.rtime();
	} else {
	    if(sess.protoBus != null) {
		if(listener != null) {
		    sess.protoBus.removeListener(listener);
		    listener = null;
		}
		if(captureHeld) {
		    sess.protoBus.releaseCapture();
		    captureHeld = false;
		}
	    }
	    buf.clear();
	    autoDump = false;
	}
    }

    public synchronized void setAutoDump(boolean on) {
	if(on && !enabled) return;
	autoDump = on;
	if(on) lastDumpTime = Utils.rtime();
    }

    private void onEvent(ProtoEvent evt) {
	Path toWrite = null;
	List<ProtoEvent> snapshot = null;
	double dumpedAt = 0;
	synchronized(this) {
	    double now = Utils.rtime();
	    buf.addLast(evt);
	    prune(now);
	    if(autoDump && (now - lastDumpTime) >= windowSec) {
		lastDumpTime = now;
		snapshot = new ArrayList<>(buf);
		dumpedAt = now;
		toWrite = dumpTargetPath();
	    }
	}
	if(toWrite != null && snapshot != null) {
	    try {
		writeDump(toWrite, snapshot, dumpedAt);
		synchronized(this) {
		    lastDumpPath = toWrite;
		    lastDumpSize = snapshot.size();
		}
	    } catch(IOException e) {
		/* swallow; auto-dump must not kill the event pipeline */
	    }
	}
    }

    private void prune(double now) {
	double cutoff = now - windowSec;
	while(!buf.isEmpty() && buf.peekFirst().timestamp < cutoff)
	    buf.pollFirst();
	while(buf.size() > maxEvents)
	    buf.pollFirst();
    }

    public Path dumpNow() throws IOException {
	List<ProtoEvent> snapshot;
	double now;
	Path target;
	synchronized(this) {
	    if(!enabled) throw new IOException("retro capture not enabled");
	    now = Utils.rtime();
	    snapshot = new ArrayList<>(buf);
	    target = dumpTargetPath();
	}
	writeDump(target, snapshot, now);
	synchronized(this) {
	    lastDumpPath = target;
	    lastDumpSize = snapshot.size();
	}
	return target;
    }

    private Path dumpTargetPath() {
	Path dir = Utils.path(System.getProperty("user.dir", ".")).resolve("proto-recordings");
	java.io.File dirFile = dir.toFile();
	if(!dirFile.exists()) dirFile.mkdirs();
	String filename = String.format("retro-%tY%<tm%<td-%<tH%<tM%<tS.jsonl", new java.util.Date());
	return dir.resolve(filename);
    }

    private void writeDump(Path path, List<ProtoEvent> events, double nowTs) throws IOException {
	try(Writer w = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
	    JSONObject header = new JSONObject();
	    header.put("type", "header");
	    header.put("generated_at_ms", System.currentTimeMillis());
	    header.put("dump_ts", nowTs);
	    header.put("window_sec", windowSec);
	    header.put("max_events", maxEvents);
	    header.put("event_count", events.size());
	    w.write(header.toString());
	    w.write('\n');
	    for(ProtoEvent e : events) {
		JSONObject o = new JSONObject();
		o.put("t", e.timestamp);
		o.put("rel", e.timestamp - nowTs);
		o.put("dir", e.dir == ProtoEvent.Direction.IN ? "in" : "out");
		o.put("cat", e.category.name());
		o.put("type", e.typeName);
		o.put("tid", e.typeId);
		o.put("summary", e.summary == null ? "" : e.summary);
		if(e.detail != null && !e.detail.isEmpty() && !e.detail.equals(e.summary))
		    o.put("detail", e.detail);
		o.put("size", e.sizeBytes);
		if(e.gobId != 0) o.put("gob", e.gobId);
		if(e.widgetId != 0) o.put("wid", e.widgetId);
		w.write(o.toString());
		w.write('\n');
	    }
	}
    }
}
