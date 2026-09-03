package auto;

import haven.Coord;
import haven.Defer;
import haven.GItem;
import haven.GameUI;
import haven.Inventory;
import haven.ItemInfo;
import haven.Loading;
import haven.WItem;
import haven.Widget;
import haven.WindowX;
import me.ender.WindowDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Merge stacks in inventory windows, Hurricane-style: pick the two smallest
 * piles of each name, take the smaller onto the next with shift+ctrl itemact,
 * drop leftovers, repeat until each name is one pile.
 */
public class StackAllItems implements Defer.Callable<Void> {
    private static final Object lock = new Object();
    private static StackAllItems current;
    private Defer.Future<Void> task;

    private final List<Inventory> inventories;

    private StackAllItems(List<Inventory> inventories) {
	this.inventories = inventories;
    }

    public static void stack(Inventory inv) {
	if(inv == null || inv.ui == null || inv.ui.gui == null)
	    return;
	if(InventorySorter.invalidCursor(inv.ui))
	    return;
	start(new StackAllItems(Collections.singletonList(inv)), inv.ui.gui);
    }

    public static void stackOpened(GameUI gui) {
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
	    start(new StackAllItems(targets), gui);
    }

    @Override
    public Void call() throws InterruptedException {
	try {
	    for(Inventory inv : inventories) {
		if(inv.disposed())
		    continue;
		stackInv(inv);
	    }
	} finally {
	    synchronized(lock) {
		if(current == this)
		    current = null;
	    }
	}
	return null;
    }

    private void stackInv(Inventory inv) throws InterruptedException {
	GameUI gui = inv.ui.gui;
	if(gui == null)
	    return;
	if(gui.vhand != null) {
	    gui.error("Can't stack items with an occupied cursor!");
	    return;
	}
	Set<String> stuck = new HashSet<>();
	for(int pass = 0; pass < 80; pass++) {
	    if(inv.disposed() || Thread.currentThread().isInterrupted())
		return;
	    String before = state(inv);
	    mergeOnePass(gui, inv, stuck);
	    Thread.sleep(80);
	    if(before.equals(state(inv)))
		return;
	}
    }

    private static void mergeOnePass(GameUI gui, Inventory inv, Set<String> stuck) throws InterruptedException {
	Map<String, List<WItem>> groups = new LinkedHashMap<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!wdg.visible || !(wdg instanceof WItem))
		continue;
	    WItem w = (WItem) wdg;
	    String key = ItemStacking.stackKey(itemName(w));
	    if(key == null)
		continue;
	    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
	}
	for(List<WItem> similar : groups.values()) {
	    if(similar.size() < 2)
		continue;
	    List<WItem> bySize = new ArrayList<>(similar);
	    Collections.sort(bySize, new Comparator<WItem>() {
		@Override
		public int compare(WItem a, WItem b) {
		    return Integer.compare(amount(a), amount(b));
		}
	    });
	    boolean did = false;
	    for(int i = 0; i < bySize.size() && !did; i++) {
		for(int j = i + 1; j < bySize.size(); j++) {
		    WItem lowest = bySize.get(i);
		    WItem next = bySize.get(j);
		    if(lowest.disposed() || next.disposed())
			continue;
		    String pair = pairKey(lowest, next);
		    if(stuck.contains(pair))
			continue;
		    Coord dropSlot = lowest.c.sub(1, 1).div(Inventory.sqsz);
		    int destBefore = amount(next);
		    lowest.take();
		    if(!waitUntil(() -> gui.vhand != null, 40, 25)) {
			gui.error("Stack items: could not pick up an item.");
			return;
		    }
		    next.itemact(3);
		    waitUntil(() -> gui.vhand == null, 12, 25);
		    if(gui.vhand != null) {
			inv.wdgmsg("drop", dropSlot);
			waitUntil(() -> gui.vhand == null, 40, 25);
		    }
		    Thread.sleep(50);
		    if(!ItemStacking.stacked(lowest.disposed(), destBefore, amount(next)))
			stuck.add(pair);
		    did = true;
		    break;
		}
	    }
	}
    }

    private static String pairKey(WItem a, WItem b) {
	int ia = a.item.wdgid();
	int ib = b.item.wdgid();
	if(ia > ib) {
	    int t = ia;
	    ia = ib;
	    ib = t;
	}
	return ia + ":" + ib;
    }

    private static String state(Inventory inv) {
	Map<String, List<Integer>> groups = new LinkedHashMap<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!(wdg instanceof WItem))
		continue;
	    WItem w = (WItem) wdg;
	    String key = ItemStacking.stackKey(itemName(w));
	    if(key == null)
		continue;
	    List<Integer> amts = groups.get(key);
	    if(amts == null) {
		amts = new ArrayList<>();
		groups.put(key, amts);
	    }
	    amts.add(Integer.valueOf(amount(w)));
	}
	List<String> names = new ArrayList<>(groups.keySet());
	Collections.sort(names);
	StringBuilder sb = new StringBuilder();
	for(int n = 0; n < names.size(); n++) {
	    String name = names.get(n);
	    List<Integer> amts = groups.get(name);
	    Collections.sort(amts);
	    if(n > 0)
		sb.append(';');
	    sb.append(name).append(':');
	    for(int i = 0; i < amts.size(); i++) {
		if(i > 0)
		    sb.append(',');
		sb.append(amts.get(i));
	    }
	}
	return sb.toString();
    }

    static String itemName(WItem w) {
	try {
	    return w.item.name.get("");
	} catch(Loading ignored) {
	    return "";
	}
    }

    static int amount(WItem w) {
	try {
	    for(ItemInfo info : w.item.info()) {
		if(info instanceof GItem.Amount)
		    return Math.max(1, ((GItem.Amount) info).itemnum());
	    }
	} catch(Loading ignored) {}
	Float q = w.item.quantity.get(1f);
	if(q != null && q > 1)
	    return q.intValue();
	if(w.item.contents != null) {
	    int n = 0;
	    for(WItem ignored : w.item.contents.children(WItem.class))
		n++;
	    if(n > 0)
		return n;
	}
	return 1;
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

    private static void start(StackAllItems job, GameUI gui) {
	cancel();
	synchronized(lock) {current = job;}
	job.run((result) -> {
	    if(!"complete".equals(result))
		gui.ui.message(String.format("Stack is %s.", result), GameUI.MsgType.INFO);
	});
    }
}
