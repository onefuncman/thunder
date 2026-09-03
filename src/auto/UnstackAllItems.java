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

    private static void unstackInv(Inventory inv) throws InterruptedException {
	GameUI gui = inv.ui.gui;
	if(gui == null)
	    return;
	if(gui.vhand != null) {
	    gui.error("Can't unstack items with an occupied cursor!");
	    return;
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
	    if(ItemStacking.isStackName(name))
		stacks.add(w);
	}
	for(WItem w : stacks) {
	    if(w.disposed() || inv.disposed())
		return;
	    w.item.wdgmsg("iact", Coord.z, 3);
	    Thread.sleep(40);
	}
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
	cancel();
	synchronized(lock) {current = job;}
	job.run((result) -> {
	    if(!"complete".equals(result))
		gui.ui.message(String.format("Unstack is %s.", result), GameUI.MsgType.INFO);
	});
    }
}
