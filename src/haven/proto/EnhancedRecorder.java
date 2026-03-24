package haven.proto;

import haven.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class EnhancedRecorder implements Transport.Callback {
    private volatile Writer out;
    private volatile double epoch;
    private volatile boolean recording = false;
    private final Set<ProtoEvent.Category> enabledCategories = EnumSet.allOf(ProtoEvent.Category.class);
    private Path filePath;
    private final Session sess;

    public EnhancedRecorder(Session sess) {
	this.sess = sess;
    }

    public boolean isRecording() { return recording; }
    public Path getFilePath() { return filePath; }

    public synchronized void start(Path path) throws IOException {
	if(recording) stop();
	this.filePath = path;
	this.out = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	this.epoch = Utils.rtime();
	recording = true;
	printf("# Enhanced protocol recording\n");
	printf("# Started: %s\n", new java.util.Date().toString());
	printf("# Format: binary-compatible lines + # annotation comments\n");
	printf("# Compatible with Transport.Playback (# lines are ignored)\n");
    }

    public synchronized void stop() {
	if(!recording) return;
	recording = false;
	try {
	    printf("# Recording stopped: %s\n", new java.util.Date().toString());
	    printf("%4.6f close\n", Utils.rtime() - epoch);
	    out.flush();
	    out.close();
	} catch(IOException e) {
	    /* swallow on stop */
	}
	out = null;
    }

    public synchronized void bookmark(String label) {
	if(!recording) return;
	double ts = Utils.rtime() - epoch;
	printf("# BOOKMARK [%4.6f]: %s\n", ts, label);
    }

    public void setCategoryEnabled(ProtoEvent.Category cat, boolean enabled) {
	if(enabled)
	    enabledCategories.add(cat);
	else
	    enabledCategories.remove(cat);
    }

    @Override
    public synchronized void closed() {
	if(!recording) return;
	printf("%4.6f close\n", Utils.rtime() - epoch);
    }

    @Override
    public synchronized void handle(PMessage msg) {
	if(!recording) return;
	// Decode for annotation
	ProtoEvent evt = null;
	try {
	    evt = ProtoDecoder.decodeRel(msg, sess);
	} catch(Exception e) {}

	if(evt != null && !enabledCategories.contains(evt.category)) return;

	// Write annotation comment
	if(evt != null)
	    printf("# %s %s %s\n", evt.dir == ProtoEvent.Direction.IN ? "<<" : ">>", evt.typeName, evt.summary);

	// Write binary-compatible line (same format as Transport.Callback.Recorder)
	printf("%4.6f rmsg %d %s\n", Utils.rtime() - epoch, msg.type, Utils.bprint.enc(msg.bytes()));
    }

    @Override
    public synchronized void handle(OCache.ObjDelta delta) {
	if(!recording) return;
	// Decode for annotation
	ProtoEvent evt = null;
	try {
	    evt = ProtoDecoder.decodeObjDelta(delta, sess);
	} catch(Exception e) {}

	if(evt != null && !enabledCategories.contains(evt.category)) return;

	// Write annotation comment
	if(evt != null)
	    printf("# << %s\n", evt.summary);

	// Write binary-compatible line (same format as Transport.Callback.Recorder)
	double ts = Utils.rtime() - epoch;
	StringBuilder sb = new StringBuilder();
	sb.append(String.format("%4.6f objd", ts));
	String fl = "";
	if(delta.initframe > 0) fl += "i";
	if((delta.fl & 2) != 0) fl += "v";
	if((delta.fl & 4) != 0) fl += "o";
	if(delta.rem) fl += "d";
	sb.append(String.format(" %s %d %d", fl.isEmpty() ? "n" : fl, delta.id, delta.frame));
	if(delta.initframe > 0) sb.append(String.format(" %d", delta.initframe));
	for(OCache.AttrDelta attr : delta.attrs)
	    sb.append(String.format(" %d:%s", attr.type, Utils.bprint.enc(attr.bytes())));
	sb.append("\n");
	printRaw(sb.toString());
    }

    @Override
    public synchronized void mapdata(Message msg) {
	if(!recording) return;
	if(!enabledCategories.contains(ProtoEvent.Category.MAP)) return;

	printf("# << MAPDATA (%d bytes)\n", msg.rt - msg.rh);
	printf("%4.6f map %s\n", Utils.rtime() - epoch, Utils.b64.enc(msg.bytes()));
    }

    private void printf(String format, Object... args) {
	try {
	    if(out != null) out.write(String.format(format, args));
	} catch(IOException e) {
	    recording = false;
	}
    }

    private void printRaw(String s) {
	try {
	    if(out != null) out.write(s);
	} catch(IOException e) {
	    recording = false;
	}
    }
}
