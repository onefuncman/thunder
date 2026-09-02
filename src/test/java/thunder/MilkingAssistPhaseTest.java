package thunder;

import haven.UID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pending's phase machine, pinned against the three captured scenarios
 * that broke the old distance-estimated TTL:
 *
 * - capture-expired-20260902-114026: a wandering sheep 4.26 tiles out; the
 *   player was still OD_HOMING at +3.565s when the walk-budget deadline hit
 *   at +3.6s. EN_ROUTE must therefore be tracked, not estimated -- the
 *   walk phase gets a sanity cap, never a distance-derived deadline.
 * - capture-expired-20260426-144457: click +0.000s, sfx +1.034s on an
 *   adjacent attempt -- the post-arrival ACTION window must comfortably
 *   cover ~1.0-1.5s.
 * - capture-resolved-20260426-163048: own-sfx can arrive as early as
 *   +0.392s (22s isolated from any other milking), so fast resolves are
 *   genuine; sfx heard before arrival is held for the arrival race rather
 *   than trusted or dropped (see MilkingAssistSfxLoadingTest).
 */
public class MilkingAssistPhaseTest {
    private static final double TILE = 11.0;

    private static MilkingAssist.Pending pending(double tiles) {
	return(new MilkingAssist.Pending(UID.of(1), -1, null, tiles * TILE, 4242, null));
    }

    @Test
    void adjacentStartsActingWithFullActionWindow() {
	MilkingAssist.Pending p = pending(1.31);
	assertEquals(MilkingAssist.Phase.ACTING, p.phase, "no walk needed -- the action starts with the click");
	long window = p.deadline - p.armMs;
	assertTrue(window >= 2500,
	    "action window must cover the observed ~1.0-1.5s click-to-sfx latency with headroom, got " + window + "ms");
    }

    @Test
    void farTargetAwaitsWalkAcceptance() {
	MilkingAssist.Pending p = pending(4.26);
	assertEquals(MilkingAssist.Phase.ACCEPT, p.phase);
	long window = p.deadline - p.armMs;
	assertTrue(window >= 500 && window <= 3000,
	    "accept window is a short server-latency budget (homing observed at +84ms), got " + window + "ms");
    }

    @Test
    void enRouteHasNoDistanceDerivedDeadline() {
	// The 20260902 chase: still homing at +3.5s. The walk must be
	// tracked by the Moving attr, bounded only by a sanity cap far
	// beyond any real chase.
	MilkingAssist.Pending p = pending(4.26);
	long now = p.armMs + 90;
	p.beginEnRoute(now);
	assertEquals(MilkingAssist.Phase.EN_ROUTE, p.phase);
	assertTrue(p.deadline - now >= 30000,
	    "en-route is event-bounded; only a generous sanity cap applies, got " + (p.deadline - now) + "ms");
    }

    @Test
    void arrivalGrantsTheActionWindowRegardlessOfChaseLength() {
	MilkingAssist.Pending p = pending(4.26);
	p.beginEnRoute(p.armMs + 90);
	long arrival = p.armMs + 12000;   // long chase, well past any old TTL
	p.beginActing(arrival);
	assertEquals(MilkingAssist.Phase.ACTING, p.phase);
	long window = p.deadline - arrival;
	assertTrue(window >= 2500,
	    "the action window anchors at arrival, not at the click, got " + window + "ms");
    }
}
