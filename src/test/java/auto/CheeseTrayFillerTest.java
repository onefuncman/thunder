package auto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CheeseTrayFillerTest {

    /**
     * In-memory Env. Models a set of trays (each with a fill count and max
     * capacity), a curd inventory, and a single hand slot.
     *
     * placeIntoTray increments the tray's fill if it has room and reports
     * true; else it leaves state unchanged and reports false — mirroring
     * the real signal (tray's content count changed after server round-trip).
     */
    static class FakeEnv implements CheeseTrayFiller.Env {
	final int[] trays;
	final int[] capacity;
	int curdsAvailable;
	boolean holdingCurd = false;

	int cancelAfterActions = -1;
	int actions = 0;
	int pickupFailsAfter = -1;
	int pickupsDone = 0;

	/** If set, trayFill returns -1 (unknown) for the given index. */
	int hideFillForTray = -1;

	FakeEnv(int trayCount, int curds) {
	    this(trayCount, CheeseTrayFiller.TRAY_CAPACITY, curds);
	}

	FakeEnv(int trayCount, int trayCap, int curds) {
	    this.trays = new int[trayCount];
	    this.capacity = new int[trayCount];
	    for (int i = 0; i < trayCount; i++) { this.capacity[i] = trayCap; }
	    this.curdsAvailable = curds;
	}

	FakeEnv withInitialFill(int trayIndex, int fill) {
	    trays[trayIndex] = fill;
	    return this;
	}

	FakeEnv withCapacity(int trayIndex, int cap) {
	    capacity[trayIndex] = cap;
	    return this;
	}

	private void tick() { actions++; }

	public boolean isCancelled() {
	    return cancelAfterActions >= 0 && actions >= cancelAfterActions;
	}

	public boolean handEmpty() { return !holdingCurd; }

	public int trayCount() { return trays.length; }

	public int trayFill(int trayIndex) {
	    if(trayIndex == hideFillForTray) { return -1; }
	    return trays[trayIndex];
	}

	public boolean pickupCurd() {
	    tick();
	    if(pickupFailsAfter >= 0 && pickupsDone >= pickupFailsAfter) { return false; }
	    if(curdsAvailable <= 0) { return false; }
	    if(holdingCurd) { throw new IllegalStateException("pickup while holding"); }
	    curdsAvailable--;
	    holdingCurd = true;
	    pickupsDone++;
	    return true;
	}

	public boolean placeIntoTray(int trayIndex) {
	    tick();
	    if(!holdingCurd) { throw new IllegalStateException("place with empty hand"); }
	    if(trays[trayIndex] >= capacity[trayIndex]) { return false; }
	    trays[trayIndex]++;
	    holdingCurd = false;
	    return true;
	}

	public void dropHeld() {
	    tick();
	    if(holdingCurd) {
		holdingCurd = false;
		curdsAvailable++;
	    }
	}
    }

    @Test
    void fillsSingleTrayCompletely() {
	FakeEnv env = new FakeEnv(1, 4);
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	assertArrayEquals(new int[]{4}, r.perTray);
	assertEquals(4, r.placed);
	assertNull(r.cancelReason);
	assertEquals(0, env.curdsAvailable);
	assertFalse(env.holdingCurd);
    }

    @Test
    void stopsWhenCurdsRunOut() {
	FakeEnv env = new FakeEnv(1, 2);
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	assertArrayEquals(new int[]{2}, r.perTray);
	assertEquals(0, env.curdsAvailable);
	assertFalse(env.holdingCurd);
    }

    @Test
    void fillsAllTraysWhenCurdsPlentiful() {
	FakeEnv env = new FakeEnv(5, 100);
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	assertArrayEquals(new int[]{4, 4, 4, 4, 4}, r.perTray);
	assertEquals(20, r.placed);
	assertEquals(80, env.curdsAvailable);
    }

    /** Regression for reported bug: 10 trays + limited curds should fill in order, not randomly. */
    @Test
    void fillsTraysInOrderWithLimitedCurds() {
	FakeEnv env = new FakeEnv(10, 25);
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	assertArrayEquals(new int[]{4, 4, 4, 4, 4, 4, 1, 0, 0, 0}, r.perTray);
	assertEquals(25, r.placed);
	assertEquals(0, env.curdsAvailable);
	assertFalse(env.holdingCurd);
    }

    @Test
    void skipsFullTraysAndContinues() {
	FakeEnv env = new FakeEnv(3, 100).withInitialFill(0, 4).withInitialFill(1, 2);
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	assertArrayEquals(new int[]{0, 2, 4}, r.perTray);
	assertEquals(6, r.placed);
    }

    @Test
    void handlesZeroTrays() {
	FakeEnv env = new FakeEnv(0, 5);
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	assertEquals(0, r.perTray.length);
	assertEquals(0, r.placed);
	assertEquals(5, env.curdsAvailable);
    }

    @Test
    void dropsHeldCurdIfPickupFailsMidRun() {
	FakeEnv env = new FakeEnv(3, 100);
	env.pickupFailsAfter = 5;
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	assertEquals(5, r.placed);
	assertArrayEquals(new int[]{4, 1, 0}, r.perTray);
	assertFalse(env.holdingCurd);
    }

    @Test
    void cancellationStopsAndDropsHeld() {
	FakeEnv env = new FakeEnv(3, 100);
	env.cancelAfterActions = 5;
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	assertNotNull(r.cancelReason);
	assertFalse(env.holdingCurd);
	assertTrue(r.placed < 12);
    }

    @Test
    void respectsNonDefaultTrayCapacities() {
	FakeEnv env = new FakeEnv(3, 4, 100)
	    .withCapacity(0, 2)
	    .withCapacity(1, 3)
	    .withCapacity(2, 4);
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	assertArrayEquals(new int[]{2, 3, 4}, r.perTray);
    }

    @Test
    void resumesPartialTrayFromInitialFill() {
	FakeEnv env = new FakeEnv(2, 100).withInitialFill(0, 2);
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	assertArrayEquals(new int[]{2, 4}, r.perTray);
	assertEquals(6, r.placed);
    }

    /**
     * When the tray reports its fill as >= capacity, skip it immediately
     * (no placement attempt). This is the short-circuit that avoids the
     * old bug where we attempted placements we couldn't actually make.
     */
    @Test
    void skipsTrayImmediatelyWhenFillAtCapacity() {
	FakeEnv env = new FakeEnv(2, 100).withInitialFill(0, 4);
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	// Tray 0 already full — no place attempt. Tray 1 fills normally.
	assertArrayEquals(new int[]{0, 4}, r.perTray);
    }

    /**
     * When trayFill is unavailable (info not loaded), the algorithm should
     * still place correctly, falling back on placeIntoTray's return value.
     */
    @Test
    void fillsCorrectlyWhenFillUnknown() {
	FakeEnv env = new FakeEnv(2, 100);
	env.hideFillForTray = 0;
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	assertArrayEquals(new int[]{4, 4}, r.perTray);
    }

    /**
     * Regression test for the observed bug: random-looking partial fills
     * (2,2,4,3,1,2,...) caused by treating a place-attempt timeout as
     * "tray full". With the Env semantics (placeIntoTray reflects real
     * count transition), every non-full tray fills deterministically.
     */
    @Test
    void eachTrayFillsToCapacityDeterministic() {
	FakeEnv env = new FakeEnv(10, 100);
	CheeseTrayFiller.Result r = CheeseTrayFiller.run(env);
	for (int i = 0; i < 10; i++) {
	    assertEquals(4, r.perTray[i], "tray " + i + " should be full");
	}
    }
}
