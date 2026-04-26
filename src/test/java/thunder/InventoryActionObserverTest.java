package thunder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InventoryActionObserverTest {

    @Test
    void pendingStartsNull() {
	InventoryActionObserver<String> obs = new InventoryActionObserver<>();
	assertNull(obs.peekPending());
    }

    @Test
    void setPendingExposesViaPeek() {
	InventoryActionObserver<String> obs = new InventoryActionObserver<>();
	obs.setPending("action-a");
	assertEquals("action-a", obs.peekPending());
    }

    @Test
    void setPendingReplacesPrior() {
	InventoryActionObserver<String> obs = new InventoryActionObserver<>();
	obs.setPending("first");
	obs.setPending("second");
	assertEquals("second", obs.peekPending());
    }

    @Test
    void clearPendingRemovesIt() {
	InventoryActionObserver<String> obs = new InventoryActionObserver<>();
	obs.setPending("x");
	obs.clearPending();
	assertNull(obs.peekPending());
    }

    @Test
    void retryStashesCurrentPending() {
	InventoryActionObserver<String> obs = new InventoryActionObserver<>();
	Object itemKey = new Object();
	obs.setPending("action-a");
	obs.retry(itemKey);
	assertEquals("action-a", obs.retryFor(itemKey));
    }

    @Test
    void retryNoopWhenPendingNull() {
	InventoryActionObserver<String> obs = new InventoryActionObserver<>();
	Object itemKey = new Object();
	obs.retry(itemKey);
	assertNull(obs.retryFor(itemKey));
    }

    @Test
    void retryForUnknownKeyReturnsNull() {
	InventoryActionObserver<String> obs = new InventoryActionObserver<>();
	obs.setPending("x");
	obs.retry(new Object());
	assertNull(obs.retryFor(new Object())); // different key
    }

    @Test
    void dropRetryRemovesStash() {
	InventoryActionObserver<String> obs = new InventoryActionObserver<>();
	Object k = new Object();
	obs.setPending("a");
	obs.retry(k);
	obs.dropRetry(k);
	assertNull(obs.retryFor(k));
    }

    @Test
    void clearingPendingDoesNotDisturbStashedRetries() {
	// Design invariant: clearing the currentPending mid-flight must not
	// invalidate entries already snapshotted into the retry map. Items
	// whose info loads after pending expires still resolve against their
	// stashed snapshot.
	InventoryActionObserver<String> obs = new InventoryActionObserver<>();
	Object k = new Object();
	obs.setPending("snapshot-me");
	obs.retry(k);
	obs.clearPending();
	assertNull(obs.peekPending());
	assertEquals("snapshot-me", obs.retryFor(k));
    }

    @Test
    void retryCountReflectsOutstandingRetries() {
	InventoryActionObserver<String> obs = new InventoryActionObserver<>();
	assertEquals(0, obs.retryCount());
	obs.setPending("a");
	Object k1 = new Object(), k2 = new Object();
	obs.retry(k1); obs.retry(k2);
	assertEquals(2, obs.retryCount());
	obs.dropRetry(k1);
	assertEquals(1, obs.retryCount());
    }

    @Test
    void laterSetPendingDoesNotRewriteOlderRetryEntry() {
	// The retry map stores the snapshot at the moment of retry(), not the
	// latest pending. This is what lets two actions in quick succession
	// each resolve to the right action for items whose info lags differently.
	InventoryActionObserver<String> obs = new InventoryActionObserver<>();
	Object k1 = new Object();
	Object k2 = new Object();
	obs.setPending("first");
	obs.retry(k1);
	obs.setPending("second");
	obs.retry(k2);
	assertEquals("first", obs.retryFor(k1));
	assertEquals("second", obs.retryFor(k2));
    }
}
