package haven.proto;

import haven.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ProtoBus implements Transport.Callback {
    private final AtomicInteger captureRefs = new AtomicInteger(0);
    private final ConcurrentLinkedQueue<ProtoEvent> pending = new ConcurrentLinkedQueue<>();
    private final ProtoEvent[] history;
    private int historyHead = 0;
    private int historyCount = 0;
    private final int maxHistory;
    private final List<Consumer<ProtoEvent>> listeners = new ArrayList<>();
    public final ProtoStats stats = new ProtoStats();
    public final EnhancedRecorder recorder;
    private final Session sess;

    public ProtoBus(Session sess) {
	this.sess = sess;
	this.maxHistory = CFG.PROTO_INSPECTOR_MAX_EVENTS.get();
	this.history = new ProtoEvent[maxHistory];
	this.recorder = new EnhancedRecorder(sess);
    }

    public boolean isCapturing() { return captureRefs.get() > 0; }

    public void acquireCapture() { captureRefs.incrementAndGet(); }

    public void releaseCapture() {
	int n = captureRefs.updateAndGet(v -> Math.max(0, v - 1));
	if(n == 0) pending.clear();
    }

    public void handle(PMessage msg) {
	if(!isCapturing()) return;
	try {
	    ProtoEvent evt = ProtoDecoder.decodeRel(msg, sess);
	    pending.add(evt);
	} catch(Exception e) {
	    /* don't let decode errors disrupt the protocol */
	}
    }

    public void handle(OCache.ObjDelta delta) {
	if(!isCapturing()) return;
	try {
	    ProtoEvent evt = ProtoDecoder.decodeObjDelta(delta, sess);
	    pending.add(evt);
	} catch(Exception e) {
	}
    }

    public void mapdata(Message msg) {
	if(!isCapturing()) return;
	try {
	    ProtoEvent evt = ProtoDecoder.decodeMapData(msg);
	    pending.add(evt);
	} catch(Exception e) {
	}
    }

    public void mapgrid(Coord gc, int sizeBytes, boolean applied) {
	if(!isCapturing()) return;
	try {
	    ProtoEvent evt = ProtoDecoder.decodeMapGrid(gc, sizeBytes, applied);
	    pending.add(evt);
	} catch(Exception e) {
	}
    }

    public void recordOutgoing(int widgetId, String name, Object[] args) {
	if(!isCapturing()) return;
	try {
	    ProtoEvent evt = ProtoDecoder.decodeOutgoing(widgetId, name, args);
	    pending.add(evt);
	} catch(Exception e) {
	}
    }

    public void closed() {}

    public void drainToUI() {
	ProtoEvent evt;
	while((evt = pending.poll()) != null) {
	    history[historyHead] = evt;
	    historyHead = (historyHead + 1) % maxHistory;
	    if(historyCount < maxHistory) historyCount++;
	    stats.record(evt);
	    for(Consumer<ProtoEvent> listener : listeners) {
		try {
		    listener.accept(evt);
		} catch(Exception e) {
		}
	    }
	}
	stats.tick(Utils.rtime());
    }

    public void addListener(Consumer<ProtoEvent> listener) {
	listeners.add(listener);
    }

    public void removeListener(Consumer<ProtoEvent> listener) {
	listeners.remove(listener);
    }

    public List<ProtoEvent> getHistory() {
	List<ProtoEvent> result = new ArrayList<>(historyCount);
	if(historyCount < maxHistory) {
	    for(int i = 0; i < historyCount; i++) {
		if(history[i] != null) result.add(history[i]);
	    }
	} else {
	    for(int i = 0; i < maxHistory; i++) {
		int idx = (historyHead + i) % maxHistory;
		if(history[idx] != null) result.add(history[idx]);
	    }
	}
	return result;
    }

    public List<ProtoEvent> getHistoryForGob(long gobId) {
	List<ProtoEvent> result = new ArrayList<>();
	for(ProtoEvent evt : getHistory()) {
	    if(evt.gobId == gobId) result.add(evt);
	}
	return result;
    }

    public void clearHistory() {
	pending.clear();
	Arrays.fill(history, null);
	historyHead = 0;
	historyCount = 0;
    }
}
