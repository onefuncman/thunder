package haven.proto;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class ProtoStatsTest {

    private ProtoEvent makeEvent(String typeName, int sizeBytes) {
        return new ProtoEvent.Builder()
            .typeName(typeName)
            .sizeBytes(sizeBytes)
            .build();
    }

    @Test
    void initialState() {
        ProtoStats stats = new ProtoStats();
        assertEquals(0, stats.getTotalMessages());
        assertEquals(0, stats.getTotalBytes());
        assertEquals(0.0, stats.getRate(), 1e-10);
        assertEquals(0.0, stats.getBandwidth(), 1e-10);
    }

    @Test
    void recordIncrementsTotals() {
        ProtoStats stats = new ProtoStats();
        stats.record(makeEvent("TEST", 100));
        stats.record(makeEvent("TEST", 200));
        assertEquals(2, stats.getTotalMessages());
        assertEquals(300, stats.getTotalBytes());
    }

    @Test
    void recordDifferentTypes() {
        ProtoStats stats = new ProtoStats();
        stats.record(makeEvent("TYPE_A", 10));
        stats.record(makeEvent("TYPE_B", 20));
        stats.record(makeEvent("TYPE_A", 30));
        assertEquals(3, stats.getTotalMessages());
        assertEquals(60, stats.getTotalBytes());
    }

    @Test
    void tickComputesRate() {
        ProtoStats stats = new ProtoStats();
        // First tick initializes the window
        stats.tick(100.0);
        stats.record(makeEvent("TEST", 50));
        stats.record(makeEvent("TEST", 50));
        // Second tick 1 second later triggers rate computation
        stats.tick(101.0);
        assertEquals(2.0, stats.getRate(), 0.1);
        assertEquals(100.0, stats.getBandwidth(), 0.1);
    }

    @Test
    void tickResetsWindow() {
        ProtoStats stats = new ProtoStats();
        stats.tick(100.0);
        stats.record(makeEvent("TEST", 50));
        stats.tick(101.0);
        // After tick, window is reset
        stats.record(makeEvent("TEST", 25));
        stats.tick(102.0);
        assertEquals(1.0, stats.getRate(), 0.1);
        assertEquals(25.0, stats.getBandwidth(), 0.1);
    }

    @Test
    void getTypeRatesAfterTick() {
        ProtoStats stats = new ProtoStats();
        stats.tick(100.0);
        stats.record(makeEvent("ALPHA", 10));
        stats.record(makeEvent("ALPHA", 10));
        stats.record(makeEvent("BETA", 20));
        stats.tick(101.0);
        Map<String, Double> rates = stats.getTypeRates();
        assertEquals(2.0, rates.get("ALPHA"), 0.01);
        assertEquals(1.0, rates.get("BETA"), 0.01);
    }

    @Test
    void getTypeRatesEmptyBeforeTick() {
        ProtoStats stats = new ProtoStats();
        stats.record(makeEvent("TEST", 10));
        Map<String, Double> rates = stats.getTypeRates();
        assertTrue(rates.isEmpty());
    }

    @Test
    void getRateHistory() {
        ProtoStats stats = new ProtoStats();
        double[] history = stats.getRateHistory();
        assertEquals(60, history.length);
        for (double v : history) {
            assertEquals(0.0, v, 1e-10);
        }
    }

    @Test
    void getRateHistoryAccumulatesOverTicks() {
        ProtoStats stats = new ProtoStats();
        stats.tick(100.0);
        stats.record(makeEvent("TEST", 10));
        stats.tick(101.0);
        double[] history = stats.getRateHistory();
        // At least one non-zero entry
        boolean hasNonZero = false;
        for (double v : history) {
            if (v > 0) hasNonZero = true;
        }
        assertTrue(hasNonZero);
    }

    @Test
    void reset() {
        ProtoStats stats = new ProtoStats();
        stats.tick(100.0);
        stats.record(makeEvent("TEST", 50));
        stats.tick(101.0);
        stats.reset();
        assertEquals(0, stats.getTotalMessages());
        assertEquals(0, stats.getTotalBytes());
        assertEquals(0.0, stats.getRate(), 1e-10);
        assertEquals(0.0, stats.getBandwidth(), 1e-10);
        assertTrue(stats.getTypeRates().isEmpty());
    }
}
