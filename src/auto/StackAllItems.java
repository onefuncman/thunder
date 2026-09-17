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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Merge stacks in inventory windows, then organize existing stack contents in
 * place by quality. Full cupboards use one free inventory square as a swap
 * buffer rather than unpacking every stack into the cupboard.
 */
public class StackAllItems implements Defer.Callable<Void> {
    private static final int MAX_PASSES = 512;
    private static final int MAX_QUALITY_CYCLES = 256;
    private static final int ACTION_TIMEOUT_MS = 1200;
    private static final int POLL_MS = 10;
    private static final Object lock = new Object();
    private static final Map<String, Integer> fullCapacities = new ConcurrentHashMap<>();
    private static StackAllItems current;
    private Defer.Future<Void> task;

    private final List<Inventory> inventories;
    private final boolean rebuildStacks;
    private boolean completed = true;

    private StackAllItems(List<Inventory> inventories, boolean rebuildStacks) {
	this.inventories = inventories;
	this.rebuildStacks = rebuildStacks;
    }

    public static void stack(Inventory inv) {
	stack(inv, true);
    }

    public static void stack(Inventory inv, boolean organize) {
	if(inv == null || inv.ui == null || inv.ui.gui == null)
	    return;
	if(InventorySorter.invalidCursor(inv.ui))
	    return;
	start(new StackAllItems(Collections.singletonList(inv), organize), inv.ui.gui,
	      organize ? () -> InventorySorter.sort(inv) : null);
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
	    start(new StackAllItems(targets, true), gui, () -> InventorySorter.sortAll(gui));
    }

    @Override
    public Void call() throws InterruptedException {
	try {
	    for(Inventory inv : inventories) {
		if(inv.disposed())
		    continue;
		boolean ok = rebuildStacks ? rebuild(inv) : stackInv(inv, Collections.emptySet());
		if(!ok) {
		    completed = false;
		    break;
		}
	    }
	} finally {
	    synchronized(lock) {
		if(current == this)
		    current = null;
	    }
	}
	return null;
    }

    private boolean rebuild(Inventory inv) throws InterruptedException {
	if(!stackInv(inv, Collections.emptySet()))
	    return false;
	return organizeStackQualities(inv);
    }

    private boolean stackInv(Inventory inv, Set<Integer> excludedIds) throws InterruptedException {
	GameUI gui = inv.ui.gui;
	if(gui == null)
	    return false;
	if(gui.vhand != null) {
	    gui.error("Can't stack items with an occupied cursor!");
	    return false;
	}
	Set<String> stuck = new HashSet<>();
	Set<String> rejectedGroups = new HashSet<>();
	Set<Integer> fullStacks = new HashSet<>();
	for(int pass = 0; pass < MAX_PASSES; pass++) {
	    if(inv.disposed() || Thread.currentThread().isInterrupted())
		return true;
	    int result = mergeOnePass(gui, inv, stuck, rejectedGroups, fullStacks, excludedIds);
	    if(result < 0)
		return false;
	    if(result == 0)
		return true;
	}
	gui.error("Stack items stopped at its safety limit.");
	return false;
    }

    private static int mergeOnePass(GameUI gui, Inventory inv, Set<String> stuck,
				    Set<String> rejectedGroups, Set<Integer> fullStacks,
				    Set<Integer> excludedIds)
				    throws InterruptedException {
	Map<String, List<WItem>> groups = new LinkedHashMap<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!wdg.visible || !(wdg instanceof WItem))
		continue;
	    WItem w = (WItem) wdg;
	    if(excludedIds.contains(w.item.wdgid()) || fullStacks.contains(w.item.wdgid()))
		continue;
	    String name = itemName(w);
	    String key = ItemStacking.stackKey(name);
	    if(key == null || !ItemStacking.mayStack(name, w.item.resname(), w.lsz.x, w.lsz.y))
		continue;
	    Integer knownCapacity = fullCapacities.get(capacityKey(w));
	    if(ItemStacking.isStackName(name) && knownCapacity != null &&
	       amount(w) >= knownCapacity) {
		fullStacks.add(w.item.wdgid());
		continue;
	    }
	    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
	}
	boolean changed = false;
	for(Map.Entry<String, List<WItem>> entry : groups.entrySet()) {
	    String key = entry.getKey();
	    List<WItem> similar = entry.getValue();
	    if(similar.size() < 2 || rejectedGroups.contains(key))
		continue;
	    boolean knownStackable = false;
	    for(WItem w : similar)
		knownStackable |= ItemStacking.isStackName(itemName(w));
	    while(true) {
		int[] amounts = new int[similar.size()];
		double[] mins = new double[similar.size()];
		double[] maxs = new double[similar.size()];
		boolean[][] blocked = new boolean[similar.size()][similar.size()];
		for(int i = 0; i < similar.size(); i++) {
		    WItem w = similar.get(i);
		    amounts[i] = amount(w);
		    double[] range = qualityRange(w);
		    mins[i] = range[0];
		    maxs[i] = range[1];
		    for(int j = i + 1; j < similar.size(); j++)
			blocked[i][j] = fullStacks.contains(w.item.wdgid()) ||
			    fullStacks.contains(similar.get(j).item.wdgid()) ||
			    stuck.contains(pairKey(w, similar.get(j)));
		}
		int[] pick = ItemStacking.closestQualityPair(mins, maxs, amounts, blocked);
		if(pick == null)
		    break;
		WItem source = similar.get(pick[0]);
		WItem destination = similar.get(pick[1]);
		if(source.disposed() || destination.disposed())
		    break;
		String pair = pairKey(source, destination);
		int result = merge(gui, inv, key, source, destination);
		if(result < 0)
		    return -1;
		if(result > 0) {
		    changed = true;
		    break;
		}
		stuck.add(pair);
		if(!knownStackable) {
		    rejectedGroups.add(key);
		    break;
		}
		fullStacks.add(destination.item.wdgid());
		if(ItemStacking.isStackName(itemName(destination))) {
		    int learnedCapacity = amounts[pick[1]];
		    String capacityKey = capacityKey(destination);
		    fullCapacities.put(capacityKey, learnedCapacity);
		    for(WItem stack : similar) {
			if(ItemStacking.isStackName(itemName(stack)) &&
			   capacityKey.equals(capacityKey(stack)) &&
			   amount(stack) >= learnedCapacity)
			    fullStacks.add(stack.item.wdgid());
		    }
		}
		if(ItemStacking.failedSourceIsAlsoFull(amounts[pick[0]], amounts[pick[1]]))
		    fullStacks.add(source.item.wdgid());
	    }
	}
	return changed ? 1 : 0;
    }

    private static int merge(GameUI gui, Inventory inv, String key, WItem source,
			     WItem destination) throws InterruptedException {
	String before = groupState(inv, key);
	int totalBefore = groupAmount(inv, key);
	Coord dropSlot = source.c.sub(1, 1).div(Inventory.sqsz);
	/*
	 * These messages are processed in order by the server. Sending the whole
	 * transaction together avoids waiting for a round trip between take,
	 * itemact, and drop. The total-amount check below still waits for the final
	 * server state, so a rejected merge is not mistaken for progress while the
	 * source is temporarily on the cursor.
	 */
	source.take();
	destination.itemact(3);
	inv.wdgmsg("drop", dropSlot);
	boolean settled = waitUntil(() -> source.disposed() && gui.vhand == null &&
	    groupAmount(inv, key) == totalBefore, ACTION_TIMEOUT_MS);
	if(!settled && gui.vhand != null) {
	    inv.wdgmsg("drop", dropSlot);
	    settled = waitUntil(() -> gui.vhand == null &&
		groupAmount(inv, key) == totalBefore, ACTION_TIMEOUT_MS);
	}
	if(!settled) {
	    gui.error("Stack items: the server did not finish the stack move.");
	    return -1;
	}
	return before.equals(groupState(inv, key)) ? 0 : 1;
    }

    private static boolean organizeStackQualities(Inventory inv) throws InterruptedException {
	GameUI gui = inv.ui.gui;
	if(gui == null)
	    return false;
	if(!waitUntil(() -> stackContentsReady(inv), ACTION_TIMEOUT_MS)) {
	    gui.error("Stack quality sorting stopped because stack contents did not finish loading.");
	    return false;
	}
	Map<String, List<WItem>> groups = new LinkedHashMap<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!wdg.visible || !(wdg instanceof WItem))
		continue;
	    WItem stack = (WItem) wdg;
	    if(!ItemStacking.isStackName(itemName(stack)) || stackChildren(stack).isEmpty())
		continue;
	    groups.computeIfAbsent(capacityKey(stack), k -> new ArrayList<>()).add(stack);
	}

	int cycles = 0;
	for(List<WItem> stacks : groups.values()) {
	    if(stacks.size() < 2)
		continue;
	    stacks.sort(Comparator.comparingDouble(StackAllItems::averageStackQuality));
	    while(true) {
		if(inv.disposed() || Thread.currentThread().isInterrupted())
		    return true;
		QualitySnapshot before = qualitySnapshot(stacks);
		int misplacedBefore = ItemStacking.qualityMisplacementCount(before.qualities);
		if(misplacedBefore == 0)
		    break;
		int[][] cycle = ItemStacking.nextQualityCycle(before.qualities);
		if(cycle == null || cycle.length < 2) {
		    gui.error("Stack quality sorting could not produce a safe move plan.");
		    return false;
		}
		if(++cycles > MAX_QUALITY_CYCLES) {
		    gui.error("Stack quality sorting stopped at its safety limit.");
		    return false;
		}
		BufferSpace buffer = findBuffer(gui, inv);
		if(buffer == null) {
		    gui.error("Stack quality sorting needs one free inventory square as a swap buffer.");
		    return false;
		}
		if(!rotateQualityCycle(gui, inv, stacks, before.children, cycle, buffer)) {
		    gui.error("Stack quality sorting stopped after a server update did not complete.");
		    return false;
		}
		boolean improved = waitUntil(() -> {
		    QualitySnapshot after = qualitySnapshot(stacks);
		    return after.finiteCount >= before.finiteCount &&
			ItemStacking.qualityMisplacementCount(after.qualities) < misplacedBefore;
		}, ACTION_TIMEOUT_MS);
		if(!improved) {
		    gui.error("Stack quality sorting stopped because the last plan made no progress.");
		    return false;
		}
	    }
	}
	return true;
    }

    private static boolean stackContentsReady(Inventory inv) {
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!(wdg instanceof WItem))
		continue;
	    WItem stack = (WItem) wdg;
	    if(ItemStacking.isStackName(itemName(stack)) &&
	       stackChildren(stack).size() < amount(stack))
		return false;
	}
	return true;
    }

    private static QualitySnapshot qualitySnapshot(List<WItem> stacks) {
	List<List<WItem>> children = new ArrayList<>(stacks.size());
	double[][] qualities = new double[stacks.size()][];
	int finiteCount = 0;
	for(int i = 0; i < stacks.size(); i++) {
	    List<WItem> items = stackChildren(stacks.get(i));
	    children.add(items);
	    qualities[i] = new double[items.size()];
	    for(int j = 0; j < items.size(); j++) {
		double q = quality(items.get(j));
		qualities[i][j] = q;
		if(Double.isFinite(q))
		    finiteCount++;
	    }
	}
	return new QualitySnapshot(children, qualities, finiteCount);
    }

    private static final class QualitySnapshot {
	final List<List<WItem>> children;
	final double[][] qualities;
	final int finiteCount;

	QualitySnapshot(List<List<WItem>> children, double[][] qualities, int finiteCount) {
	    this.children = children;
	    this.qualities = qualities;
	    this.finiteCount = finiteCount;
	}
    }

    private static boolean rotateQualityCycle(GameUI gui, Inventory targetInv,
				       List<WItem> stacks, List<List<WItem>> children,
				       int[][] cycle, BufferSpace buffer)
				       throws InterruptedException {
	int[] first = cycle[0];
	WItem firstStack = stacks.get(first[0]);
	WItem firstItem = children.get(first[0]).get(first[1]);
	WItem parked = parkChild(gui, targetInv, firstStack, firstItem, buffer);
	if(parked == null)
	    return false;

	WItem vacancy = firstStack;
	for(int i = cycle.length - 1; i >= 1; i--) {
	    int[] move = cycle[i];
	    WItem source = stacks.get(move[0]);
	    if(stacks.get(move[2]) != vacancy) {
		restoreBuffered(gui, parked, vacancy, stackSize(vacancy) + 1);
		return false;
	    }
	    WItem destination = vacancy;
	    WItem item = children.get(move[0]).get(move[1]);
	    double wantedQuality = quality(item);
	    int sourceBefore = stackSize(source);
	    int vacancyBefore = stackSize(destination);
	    /* Queue take and insertion together; the size/quality postcondition is
	     * the acknowledgement for both ordered server actions. */
	    item.take();
	    destination.itemact(0);
	    boolean inserted = waitUntil(() -> gui.vhand == null &&
		stackSize(source) < sourceBefore &&
		stackSize(destination) > vacancyBefore &&
		stackContainsQuality(destination, wantedQuality), ACTION_TIMEOUT_MS);
	    if(!inserted) {
		if(gui.vhand != null) {
		    restoreHand(gui, source, sourceBefore);
		} else if(stackSize(destination) > vacancyBefore) {
		    vacancy = source;
		}
		restoreBuffered(gui, parked, vacancy, stackSize(vacancy) + 1);
		return false;
	    }
	    vacancy = source;
	}

	if(stacks.get(first[2]) != vacancy) {
	    restoreBuffered(gui, parked, vacancy, stackSize(vacancy) + 1);
	    return false;
	}
	double parkedQuality = quality(parked);
	WItem destination = vacancy;
	int vacancyBefore = stackSize(destination);
	parked.take();
	destination.itemact(0);
	if(!waitUntil(() -> gui.vhand == null && stackSize(destination) > vacancyBefore &&
		stackContainsQuality(destination, parkedQuality), ACTION_TIMEOUT_MS)) {
	    restoreHand(gui, destination, vacancyBefore + 1);
	    return false;
	}
	return true;
    }

    private static WItem parkChild(GameUI gui, Inventory targetInv, WItem sourceStack,
				   WItem child, BufferSpace buffer) throws InterruptedException {
	Set<Integer> beforeIds = topLevelIds(buffer.inv);
	String resname = child.item.resname();
	double quality = quality(child);
	int sourceBefore = stackSize(sourceStack);
	if(buffer.inv == gui.maininv && targetInv != gui.maininv) {
	    child.item.wdgmsg("transfer", child.sz.div(2));
	} else {
	    /* As above, drop is safe to queue immediately after take. */
	    child.take();
	    buffer.inv.wdgmsg("drop", buffer.slot);
	}
	WItem[] found = new WItem[1];
	boolean ready = waitUntil(() -> {
	    found[0] = findNewTopLevel(buffer.inv, beforeIds, resname, quality);
	    return gui.vhand == null && found[0] != null &&
		stackSize(sourceStack) < sourceBefore;
	}, ACTION_TIMEOUT_MS);
	if(!ready && gui.vhand != null) {
	    buffer.inv.wdgmsg("drop", buffer.slot);
	    waitUntil(() -> gui.vhand == null, ACTION_TIMEOUT_MS);
	}
	return ready ? found[0] : null;
    }

    private static void restoreHand(GameUI gui, WItem stack, int wantedSize)
				    throws InterruptedException {
	if(gui.vhand == null || stack.disposed())
	    return;
	stack.itemact(0);
	waitUntil(() -> gui.vhand == null && stackSize(stack) >= wantedSize,
	    ACTION_TIMEOUT_MS);
    }

    private static void restoreBuffered(GameUI gui, WItem buffered, WItem stack,
					int wantedSize) throws InterruptedException {
	if(gui.vhand != null || buffered == null || buffered.disposed() || stack.disposed())
	    return;
	buffered.take();
	if(waitUntil(() -> gui.vhand != null, ACTION_TIMEOUT_MS))
	    restoreHand(gui, stack, wantedSize);
    }

    private static WItem findNewTopLevel(Inventory inv, Set<Integer> beforeIds,
					 String resname, double wantedQuality) {
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!(wdg instanceof WItem))
		continue;
	    WItem w = (WItem) wdg;
	    if(beforeIds.contains(w.item.wdgid()))
		continue;
	    if(!resname.isEmpty() && !resname.equals(w.item.resname()))
		continue;
	    double q = quality(w);
	    if(Double.isFinite(wantedQuality) && Double.isFinite(q) &&
	       Math.abs(q - wantedQuality) > 0.0001)
		continue;
	    return w;
	}
	return null;
    }

    private static Set<Integer> topLevelIds(Inventory inv) {
	Set<Integer> ids = new HashSet<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(wdg instanceof WItem)
		ids.add(((WItem) wdg).item.wdgid());
	}
	return ids;
    }

    private static BufferSpace findBuffer(GameUI gui, Inventory target) {
	if(gui.maininv != null && !gui.maininv.disposed()) {
	    Coord slot = gui.maininv.findPlaceFor(Coord.of(1, 1));
	    if(slot != null)
		return new BufferSpace(gui.maininv, slot);
	}
	Coord slot = target.findPlaceFor(Coord.of(1, 1));
	return slot == null ? null : new BufferSpace(target, slot);
    }

    private static final class BufferSpace {
	final Inventory inv;
	final Coord slot;

	BufferSpace(Inventory inv, Coord slot) {
	    this.inv = inv;
	    this.slot = slot;
	}
    }

    private static List<WItem> stackChildren(WItem stack) {
	if(stack == null || stack.item.contents == null)
	    return Collections.emptyList();
	return new ArrayList<>(stack.item.contents.children(WItem.class));
    }

    private static int stackSize(WItem stack) {
	List<WItem> children = stackChildren(stack);
	return children.isEmpty() ? amount(stack) : children.size();
    }

    private static boolean stackContainsQuality(WItem stack, double wanted) {
	if(!Double.isFinite(wanted))
	    return true;
	for(WItem child : stackChildren(stack)) {
	    double q = quality(child);
	    if(Double.isFinite(q) && Math.abs(q - wanted) <= 0.0001)
		return true;
	}
	return false;
    }

    private static double averageStackQuality(WItem stack) {
	double total = 0;
	int count = 0;
	for(WItem child : stackChildren(stack)) {
	    double q = quality(child);
	    if(Double.isFinite(q)) {
		total += q;
		count++;
	    }
	}
	return count == 0 ? Double.POSITIVE_INFINITY : total / count;
    }

    private static String capacityKey(WItem item) {
	String name = ItemStacking.stackKey(itemName(item));
	String resname = item.item.resname();
	for(WItem child : stackChildren(item)) {
	    String childRes = child.item.resname();
	    if(!childRes.isEmpty()) {
		resname = childRes;
		break;
	    }
	}
	return String.valueOf(name) + "|" + resname;
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

    private static String groupState(Inventory inv, String wantedKey) {
	List<String> values = new ArrayList<>();
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!(wdg instanceof WItem))
		continue;
	    WItem w = (WItem) wdg;
	    String name = itemName(w);
	    String key = ItemStacking.stackKey(name);
	    if(!wantedKey.equals(key))
		continue;
	    values.add(amount(w) + (ItemStacking.isStackName(name) ? "s" : "i"));
	}
	Collections.sort(values);
	return values.toString();
    }

    private static int groupAmount(Inventory inv, String wantedKey) {
	int total = 0;
	for(Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
	    if(!(wdg instanceof WItem))
		continue;
	    WItem w = (WItem) wdg;
	    if(wantedKey.equals(ItemStacking.stackKey(itemName(w))))
		total += amount(w);
	}
	return total;
    }

    private static double[] qualityRange(WItem w) {
	double min = Double.POSITIVE_INFINITY;
	double max = Double.NEGATIVE_INFINITY;
	if(w.item.contents != null) {
	    for(WItem child : w.item.contents.children(WItem.class)) {
		double q = quality(child);
		if(Double.isFinite(q)) {
		    min = Math.min(min, q);
		    max = Math.max(max, q);
		}
	    }
	}
	if(min == Double.POSITIVE_INFINITY) {
	    double q = quality(w);
	    if(Double.isFinite(q))
		return new double[] {q, q};
	    return new double[] {Double.NaN, Double.NaN};
	}
	return new double[] {min, max};
    }

    private static double quality(WItem w) {
	try {
	    double q = w.quality();
	    return q > 0 ? q : Double.NaN;
	} catch(Loading ignored) {
	    return Double.NaN;
	}
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

    private static boolean waitUntil(BooleanSupplier cond, int timeoutMs) throws InterruptedException {
	long deadline = System.nanoTime() + (timeoutMs * 1_000_000L);
	while(System.nanoTime() < deadline) {
	    if(cond.getAsBoolean())
		return true;
	    Thread.sleep(POLL_MS);
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

    private static void start(StackAllItems job, GameUI gui, Runnable afterComplete) {
	UnstackAllItems.cancel();
	cancel();
	synchronized(lock) {current = job;}
	job.run((result) -> {
	    if("complete".equals(result) && job.completed) {
		if(afterComplete != null)
		    afterComplete.run();
	    } else if(!"complete".equals(result)) {
		gui.ui.message(String.format("Stack is %s.", result), GameUI.MsgType.INFO);
	    }
	});
    }
}
