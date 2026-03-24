package haven.proto;

import java.util.*;

public class ProtoStats {
    private final Map<String, long[]> currentCounts = new LinkedHashMap<>();
    private final Map<String, long[]> prevCounts = new LinkedHashMap<>();
    private long totalMessages = 0;
    private long totalBytes = 0;
    private double windowStart = 0;
    private int windowMessages = 0;
    private long windowBytes = 0;
    private final double[] rateHistory = new double[60];
    private int rateHistoryHead = 0;
    private double prevRate = 0;
    private double prevBandwidth = 0;

    public void record(ProtoEvent evt) {
	totalMessages++;
	totalBytes += evt.sizeBytes;
	windowMessages++;
	windowBytes += evt.sizeBytes;
	long[] counts = currentCounts.computeIfAbsent(evt.typeName, k -> new long[2]);
	counts[0]++;
	counts[1] += evt.sizeBytes;
    }

    public void tick(double now) {
	if(windowStart == 0) windowStart = now;
	double elapsed = now - windowStart;
	if(elapsed >= 1.0) {
	    prevRate = windowMessages / elapsed;
	    prevBandwidth = windowBytes / elapsed;
	    rateHistory[rateHistoryHead] = prevRate;
	    rateHistoryHead = (rateHistoryHead + 1) % rateHistory.length;
	    synchronized(prevCounts) {
		prevCounts.clear();
		for(Map.Entry<String, long[]> e : currentCounts.entrySet())
		    prevCounts.put(e.getKey(), e.getValue().clone());
	    }
	    currentCounts.clear();
	    windowMessages = 0;
	    windowBytes = 0;
	    windowStart = now;
	}
    }

    public double getRate() { return prevRate; }
    public double getBandwidth() { return prevBandwidth; }
    public long getTotalMessages() { return totalMessages; }
    public long getTotalBytes() { return totalBytes; }

    public Map<String, Double> getTypeRates() {
	Map<String, Double> result = new LinkedHashMap<>();
	synchronized(prevCounts) {
	    double total = prevCounts.values().stream().mapToLong(c -> c[0]).sum();
	    if(total == 0) total = 1;
	    for(Map.Entry<String, long[]> e : prevCounts.entrySet())
		result.put(e.getKey(), (double)e.getValue()[0]);
	}
	return result;
    }

    public double[] getRateHistory() {
	double[] result = new double[rateHistory.length];
	for(int i = 0; i < result.length; i++) {
	    result[i] = rateHistory[(rateHistoryHead + i) % rateHistory.length];
	}
	return result;
    }

    public void reset() {
	totalMessages = 0;
	totalBytes = 0;
	windowMessages = 0;
	windowBytes = 0;
	currentCounts.clear();
	synchronized(prevCounts) {
	    prevCounts.clear();
	}
	Arrays.fill(rateHistory, 0);
	prevRate = 0;
	prevBandwidth = 0;
    }
}
