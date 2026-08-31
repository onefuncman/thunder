package haven.res.ui.croster;

import haven.Coord;
import haven.Widget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for "Hide when closed" leaving the cattle glow on.
 *
 * Window hide is animated (fade transition) since the 2026-08 loftar merge:
 * while the fade runs the {@code visible} field stays true, and when it
 * completes {@code Window.tick} calls {@code Widget.hide} directly, bypassing
 * {@code RosterWindow.hide()}'s highlight clearing. {@code RosterWindow.tick}
 * gates its highlight re-sync on the raw {@code visible} field, so during the
 * fade it re-adds the glow that {@code hide()} just cleared, and nothing
 * clears it again afterwards.
 *
 * Needs builtin-res.jar/hafen-res.jar on the classpath (window deco, cursor,
 * checkbox art); the ant test target provides them from lib/ext.
 */
public class RosterCloseHighlightTest {
    /** Observes the highlight fan-out instead of touching OCache/gobs. */
    private static class ProbeWindow extends RosterWindow {
	boolean glowing = false;
	int syncs = 0, clears = 0;
	@Override public void syncAllHighlights()  { syncs++;  glowing = true; }
	@Override public void clearAllHighlights() { clears++; glowing = false; }
    }

    private static ProbeWindow openRoster() {
	ProbeWindow w = new ProbeWindow();
	Widget parent = new Widget(new Coord(1000, 1000));
	parent.setfocusctl(true);
	parent.add(w, new Coord(10, 10));
	w.highlighting = true;
	w.hideWhenClosed = true;
	settle(w);
	return(w);
    }

    /** Run enough ticks for any show/hide animation to complete. */
    private static void settle(ProbeWindow w) {
	for(int i = 0; i < 50; i++) w.tick(0.05);
    }

    @Test
    void closeButtonClearsHighlightsOnceSettled() {
	ProbeWindow w = openRoster();
	assertTrue(w.glowing, "sanity: highlights synced while the roster is open");
	w.reqclose();
	settle(w);
	assertFalse(w.visible, "sanity: window is closed after the hide animation settles");
	assertFalse(w.glowing, "highlights must stay cleared after the roster is closed");
    }

    @Test
    void noHighlightSyncRunsAfterClose() {
	ProbeWindow w = openRoster();
	w.reqclose();
	int syncsAtClose = w.syncs;
	settle(w);
	assertEquals(syncsAtClose, w.syncs, "no highlight re-sync may run after the roster is closed");
    }

    @Test
    void menuToggleHideClearsHighlightsOnceSettled() {
	ProbeWindow w = openRoster();
	w.show(false);
	settle(w);
	assertFalse(w.visible, "sanity: window is closed after the hide animation settles");
	assertFalse(w.glowing, "highlights must stay cleared after the roster is toggled closed");
    }
}
