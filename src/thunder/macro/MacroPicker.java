package thunder.macro;

import haven.Gob;
import rx.functions.Action1;

/**
 * One-shot mode for "click a gob in the world to capture it as a step target".
 * The MacroEditorWnd's Pick button calls {@link #start(Action1)}; the next
 * gob click in MapView calls {@link #consume(Gob)} which fires the callback
 * exactly once and clears the mode.
 */
public class MacroPicker {
    private static volatile Action1<Gob> pending;

    public static boolean active() {return pending != null;}

    public static synchronized void start(Action1<Gob> onPicked) {
	pending = onPicked;
    }

    public static synchronized void cancel() {
	pending = null;
    }

    /** Returns true if the click was consumed by the picker (caller should not run its normal handling). */
    public static boolean consume(Gob gob) {
	Action1<Gob> cb;
	synchronized(MacroPicker.class) {
	    cb = pending;
	    pending = null;
	}
	if(cb == null) return false;
	cb.call(gob);
	return true;
    }
}
