package thunder;

import haven.Indir;
import haven.Loading;
import haven.Resource;
import haven.UID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the intermittent milking-assist deselect failure.
 *
 * The resolve signal is the {@code sfx/fx/water} uimsg, and
 * {@code MilkingAssist.onSfx} runs synchronously at uimsg time -- before the
 * client has necessarily loaded that sound resource. When the sfx arrives
 * together with a first-time RMSG_RESID binding (fresh session, first milk,
 * relog), {@code resid.get()} throws {@link Loading} and onSfx dropped the
 * one-shot signal on the floor, so the pending silently expired and the
 * animal stayed selected. When the resource was already cached, get()
 * succeeded and the deselect worked -- hence "sometimes works".
 *
 * Captured on the wire: capture-expired-20260426-163026-292.jsonl shows the
 * sfx + RESID arriving at +0.373s, well inside the 1218ms TTL, yet expiring;
 * capture-resolved-20260426-163048-731.jsonl (22s later, resource now
 * cached) has no RESID line and resolves.
 *
 * Contract pinned here: an sfx resid that is still Loading must be retried
 * until it can be read (GItem.tick drives the feature's timers in-game),
 * not dropped after a single attempt.
 */
public class MilkingAssistSfxLoadingTest {
    /** Indir that throws Loading for the first N gets, then yields the resource. */
    private static class SlowIndir implements Indir<Resource> {
	final Resource res;
	int loadingLeft;
	int gets = 0;
	SlowIndir(Resource res, int loadingLeft) { this.res = res; this.loadingLeft = loadingLeft; }
	public Resource get() {
	    gets++;
	    if(loadingLeft > 0) { loadingLeft--; throw(new Loading("not yet loaded")); }
	    return(res);
	}
    }

    private static Resource sfxRes(String name) {
	return(new Resource.Virtual(null, name, 1));
    }

    @AfterEach
    void clear() { MilkingAssist.debugClearPending(); }

    @Test
    void loadingMilkSfxIsRetriedNotDropped() {
	MilkingAssist.debugSetPending(UID.of(0x1234L));
	SlowIndir sfx = new SlowIndir(sfxRes("sfx/fx/water"), 1);
	MilkingAssist.onSfx(null, sfx);
	assertNotNull(MilkingAssist.debugPeekPending(), "pending must survive a still-loading sfx");
	// GItem.tick drives the feature's timers every frame in-game; the
	// stashed sfx must be re-checked once the resource can load. (A real
	// GItem needs remote resources to construct; the timer path never
	// touches the item for an adjacent pending, so null suffices here.)
	for(int i = 0; i < 10; i++) MilkingAssist.onItemTick(null);
	assertTrue(sfx.gets >= 2, "milk sfx resid seen while Loading must be re-checked, not dropped after one attempt");
    }

    @Test
    void loadedMilkSfxStillReadsImmediately() {
	MilkingAssist.debugSetPending(UID.of(0x1234L));
	SlowIndir sfx = new SlowIndir(sfxRes("sfx/fx/water"), 0);
	MilkingAssist.onSfx(null, sfx);
	assertEquals(1, sfx.gets, "an already-loaded sfx must be read at uimsg time");
    }

    @Test
    void nonMilkSfxDoesNotDisturbPending() {
	MilkingAssist.debugSetPending(UID.of(0x1234L));
	SlowIndir sfx = new SlowIndir(sfxRes("sfx/fx/squelch"), 0);
	MilkingAssist.onSfx(null, sfx);
	for(int i = 0; i < 10; i++) MilkingAssist.onItemTick(null);
	assertNotNull(MilkingAssist.debugPeekPending(), "unrelated sfx must not consume the pending");
    }
}
