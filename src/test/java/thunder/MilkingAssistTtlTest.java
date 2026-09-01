package thunder;

import haven.UID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the alternating sheep-milking deselect failures.
 *
 * Observed on the wire: a milking's sfx/fx/water arrives about a second
 * after the click even for an adjacent animal --
 * capture-expired-20260426-144457-292.jsonl (flat-5s-TTL era) shows
 * click at +0.000s and the sfx at +1.034s. The adaptive TTL introduced
 * later budgets the walk (~150ms/tile) plus a 1000ms base, which is less
 * than the milking action itself takes: for the 1.31-tile attempt in
 * capture-resolved-20260901-204752-143.jsonl the deadline was 1186ms --
 * sitting directly on top of the ~1.0-1.5s sfx latency. Attempts
 * alternate between the sfx landing just inside and just outside the
 * deadline; "successes" during batch milking were often the previous
 * animal's late sfx resolving the freshly armed pending (that capture's
 * sfx came at +0.463s, under half the observed real latency).
 *
 * Contract pinned here: the TTL must budget walk time PLUS the milking
 * action and sfx delivery, with real headroom over the observed 1034ms.
 */
public class MilkingAssistTtlTest {
    private static final double TILE = 11.0;

    private static long ttlFor(double tiles) {
	MilkingAssist.Pending p = new MilkingAssist.Pending(UID.of(1), -1, null, tiles * TILE);
	return(p.deadline - p.armMs);
    }

    @Test
    void adjacentPendingBudgetsTheMilkingAction() {
	// 1.31 tiles: the Sept-1 alternating-failure geometry. The sfx was
	// observed at +1034ms on an adjacent attempt; the deadline needs
	// honest headroom over that, not a photo finish.
	long ttl = ttlFor(1.31);
	assertTrue(ttl >= 2500,
	    "adjacent TTL must cover walk + milking action + sfx delivery (observed +1034ms), got " + ttl + "ms");
    }

    @Test
    void farPendingBudgetsWalkPlusAction() {
	// 10 tiles of walk at ~150ms/tile is ~1.5s; the action budget must
	// come on top of it, not share the same second.
	long ttl = ttlFor(10);
	assertTrue(ttl >= 1500 + 2500,
	    "far TTL must budget walk AND action, got " + ttl + "ms");
    }

    @Test
    void ttlStaysCapped() {
	assertTrue(ttlFor(500) <= 15000, "TTL cap must survive the action budget");
    }
}
