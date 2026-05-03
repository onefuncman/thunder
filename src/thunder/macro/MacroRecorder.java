package thunder.macro;

import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Inventory;
import haven.UI;
import haven.WItem;
import haven.rx.Reactor;
import rx.Subscription;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures player intents (item/gob clicks, inventory drops, flower-menu choices)
 * into a {@link Macro}. Only one recorder is active at a time; the call sites in
 * {@link haven.WItem}, {@link haven.Inventory}, {@link haven.MapView}, and the
 * FLOWER_CHOICE reactive stream check {@link #current()} and tee off when set.
 */
public class MacroRecorder {
    /** If a player action triggers gui.prog within this window, append a Wait{PROGRESS}. */
    private static final long PROG_DETECT_WINDOW_MS = 500;

    private static volatile MacroRecorder current;

    private final UI ui;
    private final List<MacroStep> steps = new ArrayList<>();
    private final List<Subscription> subs = new ArrayList<>();
    private long lastActionMs = 0;
    private long lastActionStepIndex = -1;
    private boolean stopped = false;

    private MacroRecorder(UI ui) {
	this.ui = ui;
    }

    public static MacroRecorder current() {return current;}

    public static synchronized MacroRecorder start(UI ui) {
	if(current != null) current.stop();
	MacroRecorder r = new MacroRecorder(ui);
	r.subs.add(Reactor.FLOWER_CHOICE.subscribe(r::onFlowerChoice));
	current = r;
	return r;
    }

    public synchronized void stop() {
	if(stopped) return;
	stopped = true;
	for(Subscription s : subs) {
	    if(s != null && !s.isUnsubscribed()) s.unsubscribe();
	}
	subs.clear();
	if(current == this) current = null;
    }

    public synchronized List<MacroStep> steps() {
	return new ArrayList<>(steps);
    }

    public synchronized int stepCount() {return steps.size();}

    public synchronized Macro build(String name) {
	Macro m = new Macro(name);
	m.steps.addAll(steps);
	return m;
    }

    /** Hook called from {@link haven.WItem#mousedown}. */
    public synchronized void onItemClick(WItem item, int button, int modflags) {
	if(stopped || item == null || item.item == null) return;
	// Cursor-following item (ItemDrag) intercepts mousedown before it
	// reaches the actual click target -- skip it so we record the click
	// the user meant, not the item they happen to be holding.
	if(item instanceof haven.ItemDrag) return;
	String resid = item.item.resname();
	if(resid == null) return;
	MacroStep.ItemAct step = new MacroStep.ItemAct(resid, button, modflags);
	// Capture sdt so layered items (lib/layspr mug, etc) can be matched specifically.
	// Cloning avoids consuming the original's read pointer.
	if(item.item.sdt != null) {
	    byte[] s = item.item.sdt.clone().bytes();
	    if(s.length > 0) step.sdt = s;
	}
	addStep(step);
	maybeScheduleProgressWait();
    }

    /** Hook called from {@link haven.Inventory#drop}. */
    public synchronized void onInvDrop(Inventory inv, Coord slot) {
	if(stopped) return;
	GameUI gui = ui != null ? ui.gui : null;
	if(gui == null || inv != gui.maininv) return;
	addStep(new MacroStep.InvDrop(slot));
	// Race against the server's inv update is handled by ItemAct.execute's
	// polling -- no auto-injected ITEM_APPEARS wait, which can land in awkward
	// positions and make later steps wait for items that don't exist yet.
    }

    /** Hook called from {@link haven.MapView}'s click resolver. */
    public synchronized void onGobClick(Gob gob, int button, int modflags) {
	if(stopped || gob == null) return;
	String resid;
	try {resid = gob.resid();} catch(Exception e) {return;}
	if(resid == null) return;
	Coord2d pos = gob.rc;
	addStep(new MacroStep.GobAct(resid, pos, button, modflags));
	maybeScheduleProgressWait();
    }

    /** Hook called from {@link haven.MapView#iteminteract} (right-click on world while holding cursor item). */
    public synchronized void onGobItemAct(Gob gob, int modflags) {
	if(stopped || gob == null) return;
	String resid;
	try {resid = gob.resid();} catch(Exception e) {return;}
	if(resid == null) return;
	MacroStep.GobAct step = new MacroStep.GobAct(resid, gob.rc, 3, modflags);
	step.useHand = true;
	addStep(step);
	maybeScheduleProgressWait();
    }

    private void onFlowerChoice(haven.FlowerMenu.Choice choice) {
	synchronized(this) {
	    if(stopped) return;
	    if(choice == null || choice.opt == null) return;
	    addStep(new MacroStep.FlowerChoice(choice.opt));
	    maybeScheduleProgressWait();
	}
    }

    private void addStep(MacroStep step) {
	steps.add(step);
	lastActionMs = System.currentTimeMillis();
	lastActionStepIndex = steps.size() - 1;
    }

    /**
     * After every captured action, watch for {@code gui.prog} to appear within
     * {@link #PROG_DETECT_WINDOW_MS}. If it does, append a Wait{PROGRESS} step.
     * Runs on a short-lived background thread per action; it cheaply self-aborts
     * if the window passes without progress.
     */
    private void maybeScheduleProgressWait() {
	final long actionMs = lastActionMs;
	final long actionIdx = lastActionStepIndex;
	final GameUI gui = ui != null ? ui.gui : null;
	if(gui == null) return;
	Thread t = new Thread(() -> {
	    long deadline = actionMs + PROG_DETECT_WINDOW_MS;
	    while(System.currentTimeMillis() < deadline) {
		try {Thread.sleep(20);} catch(InterruptedException ie) {return;}
		if(gui.prog != null) {
		    synchronized(this) {
			if(stopped) return;
			// Don't double-append if the user did another action in the meantime
			if(lastActionStepIndex != actionIdx) return;
			steps.add(new MacroStep.Wait(MacroStep.Wait.Kind.PROGRESS, 30000));
		    }
		    return;
		}
	    }
	}, "macro-progress-watch");
	t.setDaemon(true);
	t.start();
    }
}
