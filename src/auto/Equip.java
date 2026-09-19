package auto;

import haven.*;
import haven.Equipory.SLOTS;

import java.util.Optional;
import java.util.function.BooleanSupplier;

import static haven.Equipory.SLOTS.*;

public class Equip {
    public static final Item BOW = new Equip.Item("Bow", "/huntersbow", "/rangersbow");
    public static final Item SWORD = new Equip.Item("Sword", "/bronzesword", "/fyrdsword", "/hirdsword");
    public static final Item SHIELD = new Equip.Item("Shield", "/roundshield");
    public static final Item SPEAR = new Equip.Item("Spear", "/boarspear");
    public static final Item TRAVELERS_SACK = new Equip.Item("Traveller's Sack", "/travellerssack");
    public static final Item WANDERERS_BINDLE = new Equip.Item("Wanderer's Bindle", "/wanderersbindle");
    public static final Item B12 = new Equip.Item("B12 Axe", "/b12axe");
    public static final Item WOODCUT_AXE = new Equip.Item("Woodcutting axe",
	"/woodsmansaxe", "/stoneaxe", "/butcherscleaver", "/axe-m", "/tinkersthrowingaxe", "/b12axe");
    public static final Item CUTBLADE = new Equip.Item("Cutblade", "/cutblade");
    public static final Item GIANT_NEEDLE = new Equip.Item("Giant Needle", "/giantneedle");
    public static final Item PICKAXE = new Equip.Item("Pickaxe", "/pickaxe");
    public static final Item SLEDGEHAMMER = new Equip.Item("Sledgehammer", "/sledgehammer");
    public static final Item SCYTHE = new Equip.Item("Scythe", "/scythe");
    public static final Item SHOVEL = new Equip.Item("Shovel", "/shovel-m", "/shovel-t", "/shovel-w");

    //These items can't be placed into a belt
    private static final String[] FORBIDDEN = {
	"/bucket",
	"/pickingbasket",
	"/splint",
    };

    public static class Item {
	private final String name;
	private final String[] types;

	public Item(String name, String... types) {
	    this.name = name;
	    this.types = types;
	}

	public boolean matches(String resourceName) {
	    return GobTag.ofType(resourceName, types);
	}

	public String name() {return name;}
    }

    /** True when a compatible item is already equipped or is available in the belt. */
    public static boolean hasInHandsOrBelt(GameUI gui, Item target) {
	if(gui == null || target == null) return false;
	for(WItem item : InvHelper.HANDS(gui).get()) {
	    if(matches(item, target)) return true;
	}
	for(WItem item : InvHelper.BELT(gui).get()) {
	    if(matches(item, target)) return true;
	}
	return false;
    }

    /**
     * Checks the extra constraint used by {@link #ensureTwoHanded}: when both
     * hands contain distinct items, one of them needs a free belt slot before a
     * two-handed tool can be swapped in.
     */
    public static boolean canEnsureTwoHanded(GameUI gui, Item target) {
	if(gui == null || gui.equipory == null || target == null) return false;
	Equipory equipory = gui.equipory;
	WItem left = equipory.slot(HAND_LEFT);
	WItem right = equipory.slot(HAND_RIGHT);
	if(matches(left, target) || matches(right, target)) return true;
	if(!InvHelper.findFirstContained(item -> matches(item, target), InvHelper.BELT_CONTAINED(gui)).isPresent())
	    return false;
	if(left == null || right == null || left.item == right.item) return true;
	WItem belt = equipory.slot(BELT);
	return belt != null && hasEmptySlot(belt);
    }

    /**
     * Synchronous variant for a larger running bot. Unlike {@link #twoHanded}, this
     * does not start a second global Bot (which would cancel the caller).
     */
    public static boolean ensureTwoHanded(GameUI gui, Bot bot, Item target) throws InterruptedException {
	if(gui == null || gui.equipory == null || target == null) return false;
	if(gui.hand() != null) return false;
	Equipory equipory = gui.equipory;
	WItem leftHand = equipory.slot(HAND_LEFT);
	WItem rightHand = equipory.slot(HAND_RIGHT);
	String leftName = leftHand == null ? null : leftHand.item.resname();
	String rightName = rightHand == null ? null : rightHand.item.resname();
	if(target.matches(leftName) || target.matches(rightName)) return true;
	if(checkForbidden(gui, leftName, HAND_LEFT) || checkForbidden(gui, rightName, HAND_RIGHT)) return false;

	if(leftHand != null && rightHand != null && leftHand.item != rightHand.item) {
	    WItem belt = equipory.slot(BELT);
	    if(belt == null || !hasEmptySlot(belt)) return false;
	    leftHand.take();
	    if(!waitFor(gui, bot, () -> gui.hand() != null, 5000L)) return false;
	    if(!dropHeldIntoFreeBeltSlot(gui, bot, belt)) {
		equipory.sendDrop(HAND_LEFT);
		waitFor(gui, bot, () -> gui.hand() == null, 1000L);
		return false;
	    }
	}

	// Re-resolve after clearing a hand: belt widgets can be replaced when the
	// server acknowledges the preceding drop.
	Optional<InvHelper.ContainedItem> found = InvHelper.findFirstContained(
	    item -> matches(item.item, target), InvHelper.BELT_CONTAINED(gui));
	if(!found.isPresent()) return false;
	InvHelper.ContainedItem item = found.get();
	leftHand = equipory.slot(HAND_LEFT);
	rightHand = equipory.slot(HAND_RIGHT);
	GItem displacedLeft = leftHand == null ? null : leftHand.item;
	GItem displacedRight = rightHand == null ? null : rightHand.item;

	item.take();
	if(!waitFor(gui, bot, () -> heldMatches(gui, target), 5000L)) {
	    returnHeldToSource(gui, bot, item);
	    return false;
	}
	equipory.sendDrop(HAND_LEFT);
	if(!waitFor(gui, bot, () -> equipped(gui, target), 5000L)) {
	    returnHeldToSource(gui, bot, item);
	    return false;
	}

	// Equipping and placing the displaced tool on the cursor are two separate
	// server updates. Never interpret the temporary empty-cursor frame between
	// them as a completed swap.
	boolean displacedExpected = displacedLeft != null;
	if(!displacedExpected && displacedRight != null)
	    displacedExpected = !equippedContains(equipory, displacedRight);
	if(displacedExpected && !waitFor(gui, bot, () -> gui.hand() != null, 5000L)) return false;
	if(gui.hand() != null) {
	    if(!returnHeldToSource(gui, bot, item)) return false;
	}
	return waitEquipped(gui, bot, target, 5000L);
    }

    public static void twoHanded(GameUI gui, Item target) {
	Bot bot = Bot.execute((t, b) -> {
	    Equipory equipory = gui.equipory;

	    WItem leftHand = equipory.slot(HAND_LEFT);
	    String leftName = leftHand != null ? leftHand.item.resname() : null;

	    if(GobTag.ofType(leftName, target.types)) {
		b.cancel();
		return;
	    }

	    if(checkForbidden(gui, leftName, HAND_LEFT)) {
		b.cancel();
		return;
	    }

	    WItem rightHand = equipory.slot(HAND_RIGHT);
	    String rightName = rightHand != null ? rightHand.item.resname() : null;

	    if(checkForbidden(gui, rightName, HAND_RIGHT)) {
		b.cancel();
		return;
	    }

	    Optional<InvHelper.ContainedItem> opt = InvHelper.findFirstContained(InvHelper.ofType(target.types), InvHelper.BELT_CONTAINED(gui));
	    if(!opt.isPresent()) {
		b.cancel(target.name + " not found in belt.");
		return;
	    }

	    InvHelper.ContainedItem item = opt.get();

	    if(leftHand != null && rightHand != null && leftHand.item != rightHand.item) {
		if(!hasEmptySlot(equipory.slot(BELT))) {
		    b.cancel("You need an empty slot in your belt to swap to a " + target.name + ".");
		    return;
		}
		leftHand.take();
		BotUtil.waitHeldChanged(gui);
		item.putBack();
		BotUtil.waitHeldChanged(gui);
	    }

	    item.take();
	    equipory.sendDrop(HAND_LEFT);
	    BotUtil.waitHeldChanged(gui);

	    if(leftHand != null || rightHand != null) {
		item.putBack();
		BotUtil.waitHeldChanged(gui);
	    }
	});
	bot.start(gui.ui, true);
    }

    public static void twoSame(GameUI gui, Item target) {
	Bot bot = Bot.execute((t, b) -> {
	    Equipory equipory = gui.equipory;

	    WItem leftHand = equipory.slot(HAND_LEFT);
	    WItem rightHand = equipory.slot(HAND_RIGHT);
	    String leftName = leftHand != null ? leftHand.item.resname() : null;
	    String rightName = rightHand != null ? rightHand.item.resname() : null;

	    boolean leftOk = GobTag.ofType(leftName, target.types);
	    boolean rightOk = GobTag.ofType(rightName, target.types);
	    if(leftOk && rightOk) {
		b.cancel();
		return;
	    }

	    boolean handleLeft = !leftOk && !checkForbidden(gui, leftName, HAND_LEFT);
	    boolean handleRight = !rightOk && !checkForbidden(gui, rightName, HAND_RIGHT);

	    if(!handleLeft && !handleRight) {
		b.cancel();
		return;
	    }

	    int needed = (handleLeft ? 1 : 0) + (handleRight ? 1 : 0);
	    long available = InvHelper.BELT_CONTAINED(gui).get().stream()
		.filter(c -> InvHelper.ofType(target.types).test(c.item))
		.count();
	    if(available < needed) {
		b.cancel("Need " + needed + " " + target.name + " in belt, found " + available + ".");
		return;
	    }

	    if(handleLeft) {
		swapHandFromBelt(gui, equipory, target, HAND_LEFT, leftHand != null);
	    }
	    if(handleRight) {
		swapHandFromBelt(gui, equipory, target, HAND_RIGHT, rightHand != null);
	    }
	});
	bot.start(gui.ui, true);
    }

    private static void swapHandFromBelt(GameUI gui, Equipory equipory, Item target, SLOTS hand, boolean handOccupied) {
	Optional<InvHelper.ContainedItem> opt = InvHelper.findFirstContained(InvHelper.ofType(target.types), InvHelper.BELT_CONTAINED(gui));
	if(!opt.isPresent()) {return;}
	InvHelper.ContainedItem item = opt.get();
	item.take();
	BotUtil.waitHeldChanged(gui);
	equipory.sendDrop(hand);
	BotUtil.waitHeldChanged(gui);
	if(handOccupied) {
	    item.putBack();
	    BotUtil.waitHeldChanged(gui);
	}
    }

    public static void twoItems(GameUI gui, Item first, Item second) {
	Bot bot = Bot.execute((t, b) -> {
	    Equipory equipory = gui.equipory;

	    WItem leftHand = equipory.slot(HAND_LEFT);
	    String leftName = leftHand != null ? leftHand.item.resname() : null;

	    if(checkForbidden(gui, leftName, HAND_LEFT)) {
		b.cancel();
		return;
	    }

	    WItem rightHand = equipory.slot(HAND_RIGHT);
	    String rightName = rightHand != null ? rightHand.item.resname() : null;

	    if(checkForbidden(gui, rightName, HAND_RIGHT)) {
		b.cancel();
		return;
	    }

	    SLOTS firstEquipped = GobTag.ofType(leftName, first.types) ? HAND_LEFT : GobTag.ofType(rightName, first.types) ? HAND_RIGHT : INVALID;
	    SLOTS secondEquipped = GobTag.ofType(leftName, second.types) ? HAND_LEFT : GobTag.ofType(rightName, second.types) ? HAND_RIGHT : INVALID;

	    if(firstEquipped != INVALID && secondEquipped != INVALID) {
		//both already equipped, nothing to do
		b.cancel();
		return;
	    }

	    Optional<InvHelper.ContainedItem> optFirst = InvHelper.findFirstContained(InvHelper.ofType(first.types), InvHelper.BELT_CONTAINED(gui));
	    if(firstEquipped == INVALID && !optFirst.isPresent()) {
		b.cancel(first.name + " not found in belt.");
		return;
	    }

	    Optional<InvHelper.ContainedItem> optSecond = InvHelper.findFirstContained(InvHelper.ofType(second.types), InvHelper.BELT_CONTAINED(gui));
	    if(secondEquipped == INVALID && !optSecond.isPresent()) {
		b.cancel(second.name + " not found in belt.");
		return;
	    }

	    SLOTS firstSlot = firstEquipped == INVALID
		? secondEquipped == HAND_LEFT ? HAND_RIGHT : HAND_LEFT
		: INVALID;

	    SLOTS secondSlot = secondEquipped == INVALID
		? firstEquipped == HAND_RIGHT ? HAND_LEFT : HAND_RIGHT
		: INVALID;

	    InvHelper.ContainedItem item;
	    if(firstSlot != INVALID) {
		item = optFirst.get();
		item.take();
		BotUtil.waitHeldChanged(gui);
		equipory.sendDrop(firstSlot);
		BotUtil.waitHeldChanged(gui);
		item.putBack();
		BotUtil.pause(5);
	    }

	    if(secondSlot != INVALID) {
		item = optSecond.get();
		item.take();
		BotUtil.waitHeldChanged(gui);
		equipory.sendDrop(secondSlot);
		BotUtil.waitHeldChanged(gui);
		item.putBack();
	    }
	});

	bot.start(gui.ui, true);
    }

    private static boolean hasEmptySlot(WItem belt) {
	return belt != null && belt.item != null && belt.item.contentswnd != null &&
	    belt.item.contentswnd.children(Inventory.class).stream().anyMatch(i -> i.free() > 0);
    }

    private static boolean matches(WItem item, Item target) {
	if(item == null || item.item == null || target == null) return false;
	return matches(item.item, target);
    }

    private static boolean matches(GItem item, Item target) {
	if(item == null || target == null) return false;
	try {
	    return target.matches(item.resname());
	} catch(Loading ignored) {
	    return false;
	}
    }

    private static boolean heldMatches(GameUI gui, Item target) {
	GameUI.DraggedItem held = gui == null ? null : gui.hand();
	return held != null && matches(held.item, target);
    }

    private static boolean equipped(GameUI gui, Item target) {
	Equipory equipory = gui == null ? null : gui.equipory;
	return equipory != null &&
	    (matches(equipory.slot(HAND_LEFT), target) || matches(equipory.slot(HAND_RIGHT), target));
    }

    private static boolean equippedContains(Equipory equipory, GItem item) {
	return equipory != null && item != null &&
	    ((equipory.slot(HAND_LEFT) != null && equipory.slot(HAND_LEFT).item == item) ||
	     (equipory.slot(HAND_RIGHT) != null && equipory.slot(HAND_RIGHT).item == item));
    }

    private static boolean dropHeldIntoFreeBeltSlot(GameUI gui, Bot bot, WItem belt)
	    throws InterruptedException {
	if(belt == null || belt.item == null || belt.item.contentswnd == null) return false;
	for(Inventory inventory : belt.item.contentswnd.children(Inventory.class)) {
	    Coord slot = inventory.findPlaceFor(Coord.of(1, 1));
	    if(slot == null) continue;
	    inventory.wdgmsg("drop", slot);
	    return waitFor(gui, bot, () -> gui.hand() == null, 5000L);
	}
	return false;
    }

    private static boolean returnHeldToSource(GameUI gui, Bot bot, InvHelper.ContainedItem source)
	    throws InterruptedException {
	if(gui == null || source == null || gui.hand() == null) return true;
	for(int attempt = 0; attempt < 3; attempt++) {
	    source.putBack();
	    if(waitFor(gui, bot, () -> gui.hand() == null, 2000L)) return true;
	}
	return false;
    }

    private static boolean waitFor(GameUI gui, Bot bot, BooleanSupplier condition, long timeout)
	    throws InterruptedException {
	long deadline = System.currentTimeMillis() + Math.max(0L, timeout);
	do {
	    bot.checkCancelled();
	    if(condition.getAsBoolean()) return true;
	    if(System.currentTimeMillis() >= deadline) return false;
	    Thread.sleep(25L);
	} while(true);
    }

    private static boolean waitEquipped(GameUI gui, Bot bot, Item target, long timeout)
	    throws InterruptedException {
	long deadline = System.currentTimeMillis() + Math.max(0L, timeout);
	do {
	    bot.checkCancelled();
	    if(gui != null && gui.hand() == null && equipped(gui, target))
		return true;
	    if(System.currentTimeMillis() >= deadline) break;
	    Thread.sleep(25L);
	} while(true);
	return false;
    }

    private static boolean checkForbidden(GameUI gui, String name, SLOTS hand) {
	if(GobTag.ofType(name, FORBIDDEN)) {
	    gui.msg("Item in " + (hand == HAND_LEFT ? "left" : "right") + " hand can't be unequipped.", GameUI.MsgType.BAD);
	    return true;
	}
	return false;
    }
}
