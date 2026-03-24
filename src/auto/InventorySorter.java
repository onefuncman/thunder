package auto;

import haven.*;
import me.ender.WindowDetector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class InventorySorter implements Defer.Callable<Void> {
    public static final String[] EXCLUDE = new String[]{
	"Character Sheet",
	"Study",
	
	"Chicken Coop",
	"Belt",
	"Pouch",
	"Purse",
	
	"Cauldron",
	"Finery Forge",
	"Fireplace",
	"Frame",
	"Herbalist Table",
	"Kiln",
	"Ore Smelter",
	"Smith's Smelter",
	"Oven",
	"Pane mold",
	"Rack",
	"Smoke shed",
	"Stack Furnace",
	"Steelbox",
	"Tub"
    };
    
    private static final Object lock = new Object();
    public static final Comparator<WItem> ITEM_COMPARATOR = Comparator.comparing(WItem::sortName)
	.thenComparing(w -> w.item.resname())
	.thenComparing(WItem::sortValue)
	.thenComparing(WItem::quality, Comparator.reverseOrder());
    private static InventorySorter current;
    private Defer.Future<Void> task;
    
    private final List<Inventory> inventories;
    
    private InventorySorter(List<Inventory> inv) {
	this.inventories = inv;
    }
    
    public static void sort(Inventory inv) {
	if(invalidCursor(inv.ui)) {return;}
	start(new InventorySorter(Collections.singletonList(inv)), inv.ui.gui);
    }
    
    public static void sortAll(GameUI gui) {
	if(invalidCursor(gui.ui)) {return;}
	List<Inventory> targets = new ArrayList<>();
	for (ExtInventory w : gui.ui.root.children(ExtInventory.class)) {
	    if(w == null) {continue;}
	    WindowX window = w.getparent(WindowX.class);
	    if(window == null || WindowDetector.isWindowType(window, EXCLUDE)) {continue;}
	    if(w.inv != null) {
		targets.add(w.inv);
	    }
	}
	if(!targets.isEmpty()) {
	    start(new InventorySorter(targets), gui);
	}
    }
    
    private static boolean invalidCursor(UI ui) {
	if(ui.isDefaultCursor()) {
	    return false;
	}
	ui.message("Need to have default cursor active to sort inventory!", GameUI.MsgType.ERROR);
	return true;
    }
    
    @Override
    public Void call() throws InterruptedException {
	for (Inventory inv : inventories) {
	    if(inv.disposed()) {
		cancel();
		break;
	    }
	    doSort(inv);
	}
	synchronized (lock) {
	    if(current == this) {current = null;}
	}
	return null;
    }
    
    private static class Entry {
	final WItem w;
	final Coord slots;
	Coord current;
	Coord target;

	Entry(WItem w, Coord slots, Coord current) {
	    this.w = w;
	    this.slots = slots;
	    this.current = current;
	    this.target = current;
	}
    }

    private void doSort(Inventory inv) throws InterruptedException {
	// Build mask grid (permanently blocked cells)
	boolean[][] maskGrid = new boolean[inv.isz.x][inv.isz.y];
	if(inv.sqmask != null) {
	    int mo = 0;
	    for (int y = 0; y < inv.isz.y; y++) {
		for (int x = 0; x < inv.isz.x; x++) {
		    maskGrid[x][y] = inv.sqmask[mo++];
		}
	    }
	}

	// Collect all items, skip those with unloaded sprites
	List<Entry> entries = new ArrayList<>();
	for (Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!wdg.visible || !(wdg instanceof WItem)) continue;
	    WItem w = (WItem) wdg;
	    if(w.item.spr() == null) continue;
	    Coord slots = w.lsz;
	    Coord current = w.c.sub(1, 1).div(Inventory.sqsz);
	    entries.add(new Entry(w, slots, current));
	}

	// Sort all items together
	entries.sort(Comparator.comparing(e -> e.w, ITEM_COMPARATOR));

	// Assign target positions in scan order, respecting each item's size
	boolean[][] assignGrid = copyGrid(maskGrid, inv.isz);
	for (Entry e : entries) {
	    Coord pos = findFit(assignGrid, inv.isz, e.slots);
	    if(pos == null) break;
	    e.target = pos;
	    markGrid(assignGrid, pos, e.slots, true);
	}

	List<Entry> singles = entries.stream().filter(e -> e.slots.x * e.slots.y == 1).collect(Collectors.toList());
	List<Entry> multis  = entries.stream().filter(e -> e.slots.x * e.slots.y > 1).collect(Collectors.toList());

	// Phase 1: place multi-tile items
	// For each, first evict any 1x1 items from its target cells, then take+drop it
	boolean anyMultiSkipped = false;
	for (Entry me : multis) {
	    if(me.current.equals(me.target)) continue;
	    boolean blocked = false;
	    for (int tx = me.target.x; tx < me.target.x + me.slots.x && !blocked; tx++) {
		for (int ty = me.target.y; ty < me.target.y + me.slots.y && !blocked; ty++) {
		    Coord cell = new Coord(tx, ty);
		    for (Entry se : singles) {
			if(se.current.equals(cell)) {
			    Coord free = findFreeCell(inv.isz, maskGrid, entries);
			    if(free == null) { blocked = true; break; }
			    se.w.take();
			    Thread.sleep(10);
			    inv.wdgmsg("drop", free);
			    Thread.sleep(10);
			    se.current = free;
			    break;
			}
		    }
		}
	    }
	    if(blocked) { anyMultiSkipped = true; continue; }
	    me.w.take();
	    Thread.sleep(10);
	    inv.wdgmsg("drop", me.target);
	    Thread.sleep(10);
	    me.current = me.target;
	}
	if(anyMultiSkipped) {
	    inv.ui.gui.error("Could not move all large items — inventory too full");
	}

	// Phase 2: sort 1x1 items using chain/swap algorithm
	for (Entry se : singles) {
	    if(se.current.equals(se.target)) continue;
	    se.w.take();
	    Entry handu = se;
	    while (handu != null) {
		inv.wdgmsg("drop", handu.target);
		Entry next = null;
		for (Entry x : singles) {
		    if(x != handu && x.current.equals(handu.target)) { next = x; break; }
		}
		handu.current = handu.target;
		handu = next;
	    }
	    Thread.sleep(10);
	}
    }

    private static Coord findFit(boolean[][] grid, Coord isz, Coord slots) {
	for (int y = 0; y <= isz.y - slots.y; y++) {
	    for (int x = 0; x <= isz.x - slots.x; x++) {
		if(fits(grid, x, y, slots)) return new Coord(x, y);
	    }
	}
	return null;
    }

    private static boolean fits(boolean[][] grid, int ox, int oy, Coord slots) {
	for (int x = 0; x < slots.x; x++)
	    for (int y = 0; y < slots.y; y++)
		if(grid[ox + x][oy + y]) return false;
	return true;
    }

    private static Coord findFreeCell(Coord isz, boolean[][] maskGrid, List<Entry> entries) {
	outer:
	for (int y = 0; y < isz.y; y++) {
	    for (int x = 0; x < isz.x; x++) {
		if(maskGrid[x][y]) continue;
		for (Entry e : entries) {
		    for (int ex = e.current.x; ex < e.current.x + e.slots.x; ex++)
			for (int ey = e.current.y; ey < e.current.y + e.slots.y; ey++)
			    if(ex == x && ey == y) continue outer;
		}
		return new Coord(x, y);
	    }
	}
	return null;
    }

    private static boolean[][] copyGrid(boolean[][] src, Coord sz) {
	boolean[][] copy = new boolean[sz.x][sz.y];
	for (int x = 0; x < sz.x; x++)
	    copy[x] = Arrays.copyOf(src[x], sz.y);
	return copy;
    }

    private static void markGrid(boolean[][] grid, Coord pos, Coord slots, boolean val) {
	for (int x = 0; x < slots.x; x++)
	    for (int y = 0; y < slots.y; y++)
		grid[pos.x + x][pos.y + y] = val;
    }
    
    private void run(Consumer<String> callback) {
	task = Defer.later(this);
	task.callback(() -> callback.accept(task.cancelled() ? "cancelled" : "complete"));
    }
    
    public static void cancel() {
	synchronized (lock) {
	    if(current != null) {
		current.task.cancel();
		current = null;
	    }
	}
    }
    
    private static final Audio.Clip sfx_done = Audio.resclip(Resource.remote().loadwait("sfx/hud/on"));
    
    private static void start(InventorySorter inventorySorter, GameUI gui) {
	cancel();
	synchronized (lock) {current = inventorySorter;}
	inventorySorter.run((result) -> {
	    if(result.equals("complete")) {
		gui.ui.sfxrl(sfx_done);
	    } else {
		gui.ui.message(String.format("Sort is %s.", result), GameUI.MsgType.INFO);
	    }
	});
    }
}