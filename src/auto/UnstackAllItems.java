package auto;

import haven.Coord;
import haven.Defer;
import haven.GameUI;
import haven.Inventory;
import haven.Loading;
import haven.WItem;
import haven.Widget;
import haven.WindowX;
import me.ender.WindowDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Shift+ctrl right-click every "stack of" pile so it unpacks into free squares. */
public class UnstackAllItems implements Defer.Callable<Void> {
    private static final Object lock = new Object();
    private static UnstackAllItems current;
    private Defer.Future<Void> task;

    private final List<Inventory> inventories;

    private UnstackAllItems(List<Inventory> inventories) {
	this.inventories = inventories;
    }

    public static void unstack(Inventory inv) {
	if(inv == null || inv.ui == null || inv.ui.gui == null)
	    return;
	if(InventorySorter.invalidCursor(inv.ui))
	    return;
	start(new UnstackAllItems(Collections.singletonList(inv)), inv.ui.gui);
    }

    public static void unstackOpened(GameUI gui) {
	if(InventorySorter.invalidCursor(gui.ui))
	    return;
	List<Inventory> targets = new ArrayList<>();
	for(haven.ExtInventory w : gui.ui.root.children(haven.ExtInventory.class)) {
	    if(w == null || w.inv == null)
		continue;
	    WindowX window = w.getparent(WindowX.class);
	    if(window == null || WindowDetector.isWindowType(window, InventorySorter.EXCLUDE))
		continue;
	    targets.add(w.inv);
	}
	if(!targets.isEmpty())
	    start(new UnstackAllItems(targets), gui);
    }

    @Override
    public Void call() throws InterruptedException {
	try {
	    for(Inventory inv : inventories) {
		if(inv.disposed())
		    continue;
		unstackInv(inv);
	    }
	} finally {
	    synchronized(lock) {
		if(current == this)
		    current = null;
	    }
	}
	return null;
    }

    static boolean unstackInv(Inventory inv) throws InterruptedException {
	return unstackInv(inv, null);
    }

    static boolean unstackInv(Inventory inv, Set<Integer> onlyIds) throws InterruptedException {
	GameUI gui = inv.ui.gui;
	if(gui == null)
	    return false;
	if(gui.vhand != null) {
	    gui.error("Can't unstack items with an occupied cursor!");
	    return false;
	}
	List<WItem> stacks = new ArrayList<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!wdg.visible || !(wdg instanceof WItem))
		continue;
	    WItem w = (WItem) wdg;
	    String name;
	    try {
		name = w.item.name.get("");
	    } catch(Loading ignored) {
		continue;
	    }
	    if(ItemStacking.isStackName(name) &&
	       (onlyIds == null || onlyIds.contains(w.item.wdgid())))
		stacks.add(w);
	}
	boolean changed = false;
	for(WItem w : stacks) {
	    if(w.disposed() || inv.disposed())
		return changed;
	    String before = state(inv);
	    w.item.wdgmsg("iact", Coord.z, 3);
	    changed |= waitUntil(() -> w.disposed() || !before.equals(state(inv)), 20, 25);
	}
	return changed;
    }

    private static String state(Inventory inv) {
	List<String> items = new ArrayList<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!(wdg instanceof WItem))
		continue;
	    WItem w = (WItem) wdg;
	    String name;
	    try {
		name = w.item.name.get("");
	    } catch(Loading ignored) {
		name = "";
	    }
	    items.add(w.item.wdgid() + ":" + name);
	}
	Collections.sort(items);
	return items.toString();
    }

    private static boolean waitUntil(BooleanSupplier cond, int tries, int sleepMs) throws InterruptedException {
	for(int i = 0; i < tries; i++) {
	    if(cond.getAsBoolean())
		return true;
	    Thread.sleep(sleepMs);
	}
	return cond.getAsBoolean();
    }

    private void run(java.util.function.Consumer<String> callback) {
	task = Defer.later(this);
	task.callback(() -> callback.accept(task.cancelled() ? "cancelled" : "complete"));
    }

    public static void cancel() {
	synchronized(lock) {
	    if(current != null && current.task != null) {
		current.task.cancel();
		current = null;
	    }
	}
    }

    private static void start(UnstackAllItems job, GameUI gui) {
	StackAllItems.cancel();
	cancel();
	synchronized(lock) {current = job;}
	job.run((result) -> {
	    if(!"complete".equals(result))
		gui.ui.message(String.format("Unstack is %s.", result), GameUI.MsgType.INFO);
	});
    }
}
