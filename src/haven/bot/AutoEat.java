package haven.bot;

import auto.InvHelper;
import haven.*;
import haven.rx.Reactor;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Auto eat bot. Has to be initialized with UI object to work.
 * Is a singleton, use getInstance() to access it. Mirrors AutoDrink's
 * structure, but eating a specific food item, so the found item is cached
 * and reused instead of a plain have/don't-have flag.
 *
 * Eating goes through a real right-click + flower-menu "Eat" selection, not
 * WItem.itemact(0) -- confirmed live (auto.MiningMaterials' own energy-level
 * logging during testing) that itemact(0) silently does nothing: the energy
 * meter sat at the exact same value across 15+ consecutive calls. Uses the
 * same Reactor.FLOWER subscribe-then-forceChoose pattern BotUtil.selectFlower
 * already established, rather than a blocking poll loop -- tick() runs on the
 * main game loop, not a Bot thread, so it can't block waiting for the menu.
 */
public class AutoEat {
    private static AutoEat instance;

    GameUI gui = null;
    long lastEatTime = 0;
    private WItem cachedFood = null;
    private long foodTime = 0;

    private AutoEat(){}

    public static AutoEat getInstance() {
        if (instance == null) {
            instance = new AutoEat();
        }
        return instance;
    }

    /**
     * Initizalize the bot. Not thread safe, but there's no need to make it so, because it's only called once
     * @param gui GameUI object to work with
     */
    public void init(GameUI gui) {
        this.gui = gui;
    }

    public void tick(Gob gob) {
        if (gui == null)
            return;
        if (!CFG.AUTO_EAT_ENABLED.get())
            return;
        int autoEatThreshold = CFG.AUTO_EAT_THRESHOLD.get();
        // ignore if threshold is unreachable
        if (autoEatThreshold == 0)
            return;
        long currentTime = System.currentTimeMillis();
        // ignore if the action was triggered recently, to address the network delay and avoid spamming eat actions
        if (currentTime - lastEatTime > CFG.AUTO_EAT_DELAY.get()) {
            IMeter meter = gui.getIMeter("nrj");
            if (meter != null) {
                double currentEnergy = meter.meter(0);
                if (currentEnergy >= 0 && currentEnergy < (autoEatThreshold / 100f)) {
                    WItem item = findFood(currentTime);
                    if (item != null) {
                        lastEatTime = currentTime;
                        Reactor.FLOWER.first().subscribe(flowerMenu -> flowerMenu.forceChoose("Eat"));
                        item.rclick();
                    } else if (currentTime - lastEatTime > CFG.AUTO_EAT_FORCED_INTERVAL.get()) {
                        // with nothing to eat, back off to the forced interval instead of
                        // re-sweeping the inventory every tick for nothing
                        lastEatTime = currentTime;
                    }
                }
            }
        }
    }

    /**
     * First food item found in hands, pouches, inventory or belt. Cached for up to a
     * second (and re-resolved if the cached item was consumed/moved in the meantime),
     * so the inventory sweep stays cheap. Only ever called while energy is already
     * below the threshold.
     */
    private WItem findFood(long currentTime) {
        if (cachedFood != null && !cachedFood.disposed() && currentTime - foodTime < 1000) {
            return cachedFood;
        }
        foodTime = currentTime;
        cachedFood = locateFood();
        return cachedFood;
    }

    private WItem locateFood() {
        try {
            List<WItem> items = Stream.of(
                    InvHelper.HANDS(gui).get().stream(),
                    InvHelper.POUCHES(gui).get().stream(),
                    InvHelper.INVENTORY(gui).get().stream(),
                    InvHelper.BELT(gui).get().stream()
                ).flatMap(x -> x)
                .filter(w -> ItemData.hasFoodInfo(w.item))
                .collect(Collectors.toList());
            return items.isEmpty() ? null : items.get(0);
        } catch (Loading l) {
            // items not resolved yet - try again next tick rather than caching a miss
            return null;
        }
    }
}
