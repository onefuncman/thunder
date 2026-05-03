package thunder.macro;

import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.OCache;
import haven.WItem;
import haven.rx.Reactor;
import rx.Subscription;

import java.util.ArrayList;
import java.util.List;

public abstract class MacroStep {
    public enum Type {
	ITEM_ACT,
	GOB_ACT,
	INV_DROP,
	FLOWER_CHOICE,
	WAIT,
	SLEEP,
	CMD,
    }

    public abstract Type type();

    /** Execute the step. Throws InterruptedException for cancellation, MacroException for hard failures. */
    public abstract void execute(MacroRunner ctx) throws InterruptedException;

    /** Short human-readable label for editor/UI. */
    public abstract String label();

    /** Click on the first inventory item matching {@code resid} (and {@code sdt} if set).
     *  For layered-sprite items (mug, etc.) the resname is generic ({@code lib/layspr})
     *  and the actual item identity is in the sdt bytes -- record both, match on both. */
    public static class ItemAct extends MacroStep {
	public String resid;
	/** Optional sdt bytes to disambiguate layered-sprite items. Null/empty = match resname only. */
	public byte[] sdt;
	public int button = 1;
	public int modflags = 0;

	public ItemAct() {}

	public ItemAct(String resid, int button, int modflags) {
	    this.resid = resid;
	    this.button = button;
	    this.modflags = modflags;
	}

	public Type type() {return Type.ITEM_ACT;}

	public String label() {
	    return String.format("Item %s on %s%s", buttonName(button), resid,
				 (sdt != null && sdt.length > 0) ? " sdt[" + sdt.length + "]" : "");
	}

	public void execute(MacroRunner ctx) throws InterruptedException {
	    GameUI gui = ctx.gui();
	    if(gui == null || gui.maininv == null) {
		throw new MacroException("No inventory available");
	    }
	    WItem found = null;
	    for(WItem w : gui.maininv.children(WItem.class)) {
		String rn = w.item.resname();
		if(rn != null && rn.equals(resid) && sdtMatches(w.item)) {
		    found = w;
		    break;
		}
	    }
	    if(found == null) {
		throw new MacroException("Item not found in inventory: " + resid
					 + ((sdt != null && sdt.length > 0) ? " (with sdt)" : ""));
	    }
	    if(button == 1 && (modflags & haven.UI.MOD_SHIFT) != 0) {
		int initial = countMatching(gui, resid);
		if((modflags & haven.UI.MOD_META) != 0) {
		    found.wdgmsg("transfer-same", found.item, false);
		} else {
		    found.item.wdgmsg("transfer", haven.Coord.z);
		}
		if(!waitUntil(ctx, () -> countMatching(gui, resid) < initial, EFFECT_WAIT_MS)) {
		    throw new MacroException("Transfer had no effect: " + resid);
		}
	    } else if(button == 3) {
		found.item.wdgmsg("iact", haven.Coord.z, modflags);
		// iact result is type-dependent (menu, progress, instant) -- best-effort wait, no error.
		waitMenuOrProg(ctx, EFFECT_WAIT_MS);
	    } else {
		found.item.wdgmsg("take", haven.Coord.z);
		if(!waitUntil(ctx, () -> cursorHas(gui, resid), EFFECT_WAIT_MS)) {
		    throw new MacroException("Take had no effect on " + resid + " (cursor full? item gone?)");
		}
	    }
	}

	private boolean sdtMatches(haven.GItem gi) {
	    if(sdt == null || sdt.length == 0) return true;
	    if(gi.sdt == null) return false;
	    byte[] their = gi.sdt.clone().bytes();
	    return java.util.Arrays.equals(sdt, their);
	}
    }

    /** Find a gob matching {@code resid} near {@code lastPos} and click it. */
    public static class GobAct extends MacroStep {
	public static final double DEFAULT_RADIUS = 200.0;

	public String resid;
	public Coord2d lastPos;
	public int button = 3;
	public int modflags = 0;
	public double radius = DEFAULT_RADIUS;
	/** If true, send {@code wdgmsg("itemact", ...)} (use held item on gob) instead of {@code wdgmsg("click", ...)}. */
	public boolean useHand = false;

	public GobAct() {}

	public GobAct(String resid, Coord2d lastPos, int button, int modflags) {
	    this.resid = resid;
	    this.lastPos = lastPos;
	    this.button = button;
	    this.modflags = modflags;
	}

	public Type type() {return Type.GOB_ACT;}

	public String label() {
	    return String.format("Gob %s%s on %s @ (%.0f, %.0f)", useHand ? "use-hand " : "", buttonName(button), resid,
				 lastPos != null ? lastPos.x : 0, lastPos != null ? lastPos.y : 0);
	}

	public Gob findTarget(GameUI gui) {
	    if(gui == null || gui.ui.sess == null || gui.ui.sess.glob == null) return null;
	    OCache oc = gui.ui.sess.glob.oc;
	    Gob best = null;
	    double bestDist = Double.POSITIVE_INFINITY;
	    synchronized(oc) {
		for(Gob g : oc) {
		    String rn;
		    try {rn = g.resid();} catch(Exception e) {continue;}
		    if(rn == null || !rn.equals(resid)) continue;
		    if(lastPos == null) {best = g; break;}
		    double d = g.rc.dist(lastPos);
		    if(d <= radius && d < bestDist) {
			best = g;
			bestDist = d;
		    }
		}
	    }
	    return best;
	}

	public void execute(MacroRunner ctx) throws InterruptedException {
	    Gob g = findTarget(ctx.gui());
	    if(g == null) {
		throw new MacroException("Gob not found near (" + (lastPos != null ? lastPos.x : 0)
					 + ", " + (lastPos != null ? lastPos.y : 0) + "): " + resid);
	    }
	    GameUI gui = ctx.gui();
	    haven.Coord wc = g.rc.floor(OCache.posres);
	    if(useHand) {
		final String beforeId = cursorIdentity(gui);
		final boolean[] menuOpened = {false};
		Subscription sub = Reactor.FLOWER.first().subscribe(m -> menuOpened[0] = true);
		try {
		    gui.map.wdgmsg("itemact", haven.Coord.z, wc, modflags, 0, (int) g.id, wc, 0, -1);
		    boolean ok = waitUntil(ctx, () -> {
			if(menuOpened[0]) return true;
			if(gui.prog != null) return true;
			// Compare full (resname, sdt) so layered-sprite items (lib/layspr mug)
			// still register as "changed" when only sdt mutates (mug → mug-of-tea).
			String now = cursorIdentity(gui);
			return !java.util.Objects.equals(beforeId, now);
		    }, EFFECT_WAIT_MS);
		    if(!ok) {
			String afterId = cursorIdentity(gui);
			throw new MacroException("useHand had no observable effect on " + resid
						 + " (source empty? wrong target?). before=" + beforeId
						 + " after=" + afterId
						 + " menuOpened=" + menuOpened[0] + " prog=" + (gui.prog != null));
		    }
		} finally {
		    if(!sub.isUnsubscribed()) sub.unsubscribe();
		}
	    } else {
		gui.map.wdgmsg("click", haven.Coord.z, wc, button, modflags, 0, (int) g.id, wc, 0, -1);
		// rclick can be a no-op (just walks toward gob) -- best-effort wait, no error.
		waitMenuOrProg(ctx, EFFECT_WAIT_MS);
	    }
	}
    }

    /** Drop the held cursor item into the player's main inventory at a slot coord. */
    public static class InvDrop extends MacroStep {
	public haven.Coord slot;

	public InvDrop() {}

	public InvDrop(haven.Coord slot) {
	    this.slot = slot;
	}

	public Type type() {return Type.INV_DROP;}

	public String label() {
	    return "Drop in inv at " + (slot != null ? slot : "?");
	}

	public void execute(MacroRunner ctx) throws InterruptedException {
	    GameUI gui = ctx.gui();
	    if(gui == null || gui.maininv == null) {
		throw new MacroException("No inventory available");
	    }
	    haven.Coord s = (slot != null) ? slot : haven.Coord.z;
	    gui.maininv.wdgmsg("drop", s);
	    if(!waitUntil(ctx, () -> gui.hand() == null, EFFECT_WAIT_MS)) {
		throw new MacroException("Drop had no effect (slot occupied or cursor empty?)");
	    }
	}
    }

    /** Wait for the next FlowerMenu and choose the option whose name matches. */
    public static class FlowerChoice extends MacroStep {
	public String optionName;
	public long timeoutMs = 5000;

	public FlowerChoice() {}

	public FlowerChoice(String optionName) {
	    this.optionName = optionName;
	}

	public Type type() {return Type.FLOWER_CHOICE;}

	public String label() {return "Flower choice: " + optionName;}

	public void execute(MacroRunner ctx) throws InterruptedException {
	    final Object lock = new Object();
	    final boolean[] chosen = {false};
	    final List<Subscription> subs = new ArrayList<>();
	    // Subscribe to the choice event up-front; works for both already-open menus
	    // (we'll force-choose immediately below) and menus that arrive during the wait.
	    subs.add(Reactor.FLOWER_CHOICE.first().subscribe(c -> {
		synchronized(lock) {chosen[0] = true; lock.notifyAll();}
	    }));
	    // If a menu is already open from the previous step's wdgmsg, choose it now.
	    // The previous step's trailing wait (waitMenuOrProg) often consumes the FLOWER
	    // event, so subscribing to FLOWER.first() here would never fire.
	    // Note: forceChoose() only takes effect at attach time; for already-open
	    // menus we call choose(petal) directly via the matching Petal lookup.
	    haven.FlowerMenu existing = findOpenMenu(ctx.gui());
	    if(existing != null) {
		chooseByName(existing, optionName);
	    } else {
		// No menu yet -- wait for the next one. forceChoose() works here because
		// the menu is freshly created and attach() hasn't run yet.
		subs.add(Reactor.FLOWER.first().subscribe(menu -> menu.forceChoose(optionName)));
	    }
	    try {
		long deadline = System.currentTimeMillis() + timeoutMs;
		synchronized(lock) {
		    while(!chosen[0]) {
			long left = deadline - System.currentTimeMillis();
			if(left <= 0) break;
			lock.wait(Math.min(left, 100));
			ctx.bot().checkCancelled();
		    }
		}
		if(!chosen[0]) {
		    throw new MacroException("Flower menu choice timed out: " + optionName);
		}
	    } finally {
		for(Subscription s : subs) {
		    if(s != null && !s.isUnsubscribed()) s.unsubscribe();
		}
	    }
	}

	private static haven.FlowerMenu findOpenMenu(GameUI gui) {
	    if(gui == null || gui.ui == null || gui.ui.root == null) return null;
	    for(haven.Widget w = gui.ui.root.lchild; w != null; w = w.prev) {
		if(w instanceof haven.FlowerMenu) return (haven.FlowerMenu) w;
	    }
	    return null;
	}

	private static void chooseByName(haven.FlowerMenu menu, String name) {
	    if(menu.opts == null || menu.options == null) return;
	    for(int i = 0; i < menu.options.length && i < menu.opts.length; i++) {
		if(name != null && name.equals(menu.options[i]) && menu.opts[i] != null) {
		    menu.choose(menu.opts[i]);
		    return;
		}
	    }
	}
    }

    /** Wait for a game-state condition. */
    public static class Wait extends MacroStep {
	public enum Kind {
	    PROGRESS,
	    ITEM_APPEARS,
	    ITEM_GONE,
	    INV_HAS,
	    INV_FREE_SLOTS,
	    WINDOW_APPEARS,
	    WINDOW_GONE,
	    MESSAGE,
	    GOB_NEAR,
	    BUFF_APPEARS,
	    BUFF_GONE,
	}

	public Kind kind;
	public String resid;
	public long timeoutMs = 30000;
	public long startTimeoutMs = 1000;
	public int minCount = 1;
	public String windowTitle;
	public String pattern;
	public Coord2d gobNear;
	public double gobRadius = 200.0;
	public String buffName;

	public Wait() {}

	public Wait(Kind kind, long timeoutMs) {
	    this.kind = kind;
	    this.timeoutMs = timeoutMs;
	}

	public Type type() {return Type.WAIT;}

	public String label() {
	    return "Wait: " + kind + (resid != null ? " " + resid
				     : windowTitle != null ? " '" + windowTitle + "'"
				     : pattern != null ? " /" + pattern + "/"
				     : buffName != null ? " " + buffName
				     : "");
	}

	public void execute(MacroRunner ctx) throws InterruptedException {
	    GameUI gui = ctx.gui();
	    if(kind == Kind.PROGRESS) {waitProgress(ctx, gui); return;}
	    if(kind == Kind.MESSAGE)  {waitMessage(ctx); return;}
	    pollUntil(ctx, () -> conditionTrue(gui), describe());
	}

	private void waitProgress(MacroRunner ctx, GameUI gui) throws InterruptedException {
	    long startDeadline = System.currentTimeMillis() + startTimeoutMs;
	    while(gui.prog == null) {
		if(System.currentTimeMillis() > startDeadline) return;
		Thread.sleep(10);
		ctx.bot().checkCancelled();
	    }
	    long deadline = System.currentTimeMillis() + timeoutMs;
	    while(gui.prog != null) {
		if(System.currentTimeMillis() > deadline) throw new MacroException("Progress wait timed out");
		Thread.sleep(10);
		ctx.bot().checkCancelled();
	    }
	}

	private void waitMessage(MacroRunner ctx) throws InterruptedException {
	    final boolean[] matched = {false};
	    final Object lock = new Object();
	    final java.util.regex.Pattern p;
	    try {p = (pattern != null) ? java.util.regex.Pattern.compile(pattern) : null;}
	    catch(Exception e) {throw new MacroException("Bad regex: " + pattern);}
	    rx.Subscription sub = haven.rx.Reactor.IMSG.subscribe(text -> {
		if(p == null || p.matcher(text).find()) {
		    synchronized(lock) {matched[0] = true; lock.notifyAll();}
		}
	    });
	    try {
		long deadline = System.currentTimeMillis() + timeoutMs;
		synchronized(lock) {
		    while(!matched[0]) {
			long left = deadline - System.currentTimeMillis();
			if(left <= 0) throw new MacroException("Message wait timed out: " + pattern);
			lock.wait(Math.min(left, 100));
			ctx.bot().checkCancelled();
		    }
		}
	    } finally {
		if(!sub.isUnsubscribed()) sub.unsubscribe();
	    }
	}

	private void pollUntil(MacroRunner ctx, java.util.function.BooleanSupplier cond, String desc) throws InterruptedException {
	    long deadline = System.currentTimeMillis() + timeoutMs;
	    while(!cond.getAsBoolean()) {
		if(System.currentTimeMillis() > deadline) throw new MacroException("Wait timed out: " + desc);
		Thread.sleep(50);
		ctx.bot().checkCancelled();
	    }
	}

	private boolean conditionTrue(GameUI gui) {
	    switch(kind) {
	    case ITEM_APPEARS:    return hasItem(gui, resid);
	    case ITEM_GONE:       return !hasItem(gui, resid);
	    case INV_HAS:         return invCount(gui, resid) >= minCount;
	    case INV_FREE_SLOTS:  return freeSlots(gui) >= minCount;
	    case WINDOW_APPEARS:  return findWindow(gui, windowTitle) != null;
	    case WINDOW_GONE:     return findWindow(gui, windowTitle) == null;
	    case GOB_NEAR:        return findGobNear(gui, resid, gobNear, gobRadius) != null;
	    case BUFF_APPEARS:    return hasBuff(gui, buffName);
	    case BUFF_GONE:       return !hasBuff(gui, buffName);
	    default: return true;
	    }
	}

	private String describe() {
	    return kind + " " + (resid != null ? resid : windowTitle != null ? windowTitle : buffName != null ? buffName : "");
	}

	private static boolean hasItem(GameUI gui, String resid) {
	    return invCount(gui, resid) > 0;
	}

	private static int invCount(GameUI gui, String resid) {
	    if(gui == null || gui.maininv == null || resid == null) return 0;
	    int n = 0;
	    for(WItem w : gui.maininv.children(WItem.class)) {
		String rn = w.item.resname();
		if(rn != null && rn.equals(resid)) n++;
	    }
	    return n;
	}

	private static int freeSlots(GameUI gui) {
	    if(gui == null || gui.maininv == null) return 0;
	    return gui.maininv.size() - gui.maininv.filled();
	}

	private static haven.Window findWindow(GameUI gui, String title) {
	    if(gui == null || title == null) return null;
	    for(haven.Window w : gui.children(haven.Window.class)) {
		String c = w.caption();
		if(c != null && c.equals(title)) return w;
	    }
	    return null;
	}

	private static haven.Gob findGobNear(GameUI gui, String resid, Coord2d near, double radius) {
	    if(gui == null || resid == null || gui.ui.sess == null || gui.ui.sess.glob == null) return null;
	    haven.OCache oc = gui.ui.sess.glob.oc;
	    synchronized(oc) {
		for(haven.Gob g : oc) {
		    String rn;
		    try {rn = g.resid();} catch(Exception e) {continue;}
		    if(rn == null || !rn.equals(resid)) continue;
		    if(near == null || g.rc.dist(near) <= radius) return g;
		}
	    }
	    return null;
	}

	private static boolean hasBuff(GameUI gui, String name) {
	    if(gui == null || gui.buffs == null || name == null) return false;
	    for(haven.Buff b : gui.buffs.children(haven.Buff.class)) {
		try {
		    String tt = b.res.get().flayer(haven.Resource.tooltip).t;
		    if(tt != null && tt.contains(name)) return true;
		} catch(Exception ignored) {}
	    }
	    return false;
	}
    }

    /** Run a client console command line (e.g. "macro run other", "afk"). */
    public static class Cmd extends MacroStep {
	public String text;

	public Cmd() {}

	public Cmd(String text) {this.text = text;}

	public Type type() {return Type.CMD;}

	public String label() {return "Cmd: " + text;}

	public void execute(MacroRunner ctx) throws InterruptedException {
	    if(text == null || text.isEmpty()) return;
	    haven.UI ui = ctx.gui() != null ? ctx.gui().ui : null;
	    if(ui == null) throw new MacroException("UI unavailable");
	    try {
		ui.cons.run(ctx.gui(), text);
	    } catch(Exception e) {
		throw new MacroException("Cmd failed: " + e.getMessage());
	    }
	}
    }

    /** Fixed sleep. Last resort when no observable condition fits. */
    public static class Sleep extends MacroStep {
	public long ms;

	public Sleep() {}

	public Sleep(long ms) {
	    this.ms = ms;
	}

	public Type type() {return Type.SLEEP;}

	public String label() {return "Sleep " + ms + "ms";}

	public void execute(MacroRunner ctx) throws InterruptedException {
	    long deadline = System.currentTimeMillis() + ms;
	    while(System.currentTimeMillis() < deadline) {
		long left = deadline - System.currentTimeMillis();
		Thread.sleep(Math.min(left, 50));
		ctx.bot().checkCancelled();
	    }
	}
    }

    static String buttonName(int b) {
	switch(b) {
	case 1: return "lclick";
	case 2: return "mclick";
	case 3: return "rclick";
	default: return "btn" + b;
	}
    }

    /** Cap on per-step trailing waits (cursor change, menu open, etc). */
    static final long EFFECT_WAIT_MS = 1500;

    /** Polls {@code cond} until true or timeout. Returns true if cond became true, false on timeout. */
    static boolean waitUntil(MacroRunner ctx, java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
	long deadline = System.currentTimeMillis() + timeoutMs;
	while(!cond.getAsBoolean()) {
	    if(System.currentTimeMillis() >= deadline) return false;
	    Thread.sleep(20);
	    ctx.bot().checkCancelled();
	}
	return true;
    }

    /** Brief wait for a flower menu to open or {@code gui.prog} to appear. Returns true on either. */
    static boolean waitMenuOrProg(MacroRunner ctx, long timeoutMs) throws InterruptedException {
	GameUI gui = ctx.gui();
	final boolean[] menuOpened = {false};
	Subscription sub = Reactor.FLOWER.first().subscribe(m -> menuOpened[0] = true);
	try {
	    return waitUntil(ctx, () -> menuOpened[0] || (gui != null && gui.prog != null), timeoutMs);
	} finally {
	    if(!sub.isUnsubscribed()) sub.unsubscribe();
	}
    }

    static boolean cursorHas(GameUI gui, String resid) {
	if(gui == null) return false;
	GameUI.DraggedItem h = gui.hand();
	if(h == null || h.item == null) return false;
	String rn = h.item.resname();
	return rn != null && rn.equals(resid);
    }

    static String cursorResname(GameUI gui) {
	if(gui == null) return null;
	GameUI.DraggedItem h = gui.hand();
	if(h == null || h.item == null) return null;
	return h.item.resname();
    }

    /** Cursor item identity: resname + sdt bytes. Used to detect server-side
     *  state changes on the held item (e.g. mug → mug-of-tea changes only the
     *  sdt bytes; the resname stays {@code lib/layspr}). */
    static String cursorIdentity(GameUI gui) {
	if(gui == null) return null;
	GameUI.DraggedItem h = gui.hand();
	if(h == null || h.item == null) return null;
	String rn = h.item.resname();
	StringBuilder sb = new StringBuilder(rn != null ? rn : "");
	sb.append('|');
	if(h.item.sdt != null) {
	    byte[] s = h.item.sdt.clone().bytes();
	    for(byte b : s) sb.append(String.format("%02X", b));
	}
	return sb.toString();
    }

    static int countMatching(GameUI gui, String resid) {
	if(gui == null || gui.maininv == null || resid == null) return 0;
	int n = 0;
	for(WItem w : gui.maininv.children(WItem.class)) {
	    String rn = w.item.resname();
	    if(rn != null && rn.equals(resid)) n++;
	}
	return n;
    }
}
