package auto;

import haven.*;
import haven.pathfinding.BotMovement;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Supply and inventory helpers used only by the Clear-Cut bot. */
public final class ClearCutSupplies {
    private static final Set<String> STONE_NAMES = new HashSet<>(Arrays.asList(
        "Alabaster", "Apatite", "Arkose", "Basalt", "Bat Rock", "Black Coal", "Breccia",
        "Cat Gold", "Chert", "Diabase", "Diorite", "Dolomite", "Dross", "Eclogite",
        "Feldspar", "Flint", "Fluorospar", "Gabbro", "Gneiss", "Granite", "Graywacke",
        "Greenschist", "Hornblende", "Jasper", "Korund", "Kyanite", "Lava Rock",
        "Limestone", "Marble", "Mica", "Microlite", "Obsidian", "Olivine", "Orthoclase",
        "Pegmatite", "Porphyry", "Pumice", "Quarryartz", "Quartz", "Rhyolite",
        "Rock Crystal", "Rock Salt", "Sandstone", "Schist", "Serpentine",
        "Shard of Conch", "Slag", "Slate", "Soapstone", "Sodalite", "Sunstone", "Zincspar"
    ));
    private static final Set<String> ORE_NAMES = new HashSet<>(Arrays.asList(
        "Black Ore", "Bloodstone", "Cassiterite", "Chalcopyrite", "Cinnabar", "Direvein",
        "Galena", "Heavy Earth", "Horn Silver", "Iron Ochre", "Lead Glance", "Leaf Ore",
        "Malachite", "Meteorite", "Peacock Ore", "Schrifterz", "Silvershine", "Wine Glance"
    ));
    private static final String STACK_SUFFIX = ", stack of";

    private ClearCutSupplies() {}

    public static boolean isInventoryBasketResid(String resid) {
        String name = baseResid(resid);
        return name != null && name.startsWith("gfx/terobjs/") && name.endsWith("basket");
    }

    private static boolean isFoodInventorySource(Gob gob) {
        if(gob == null || gob.disposed()) return false;
        if(gob.is(GobTag.CONTAINER)) return true;
        String name;
        try {name = baseResid(gob.resid());}
        catch(RuntimeException loading) {return false;}
        return "gfx/terobjs/htable".equals(name) || "gfx/terobjs/table".equals(name) ||
            (name != null && (name.startsWith("gfx/terobjs/furn/table-") ||
                isInventoryBasketResid(name)));
    }

    public static boolean isRockMaterial(WItem item) {
        return item != null && isRockMaterial(item.item);
    }

    public static boolean isRockMaterial(GItem item) {
        String name = itemName(item);
        return name != null && (STONE_NAMES.contains(name) || ORE_NAMES.contains(name));
    }

    public static boolean refillWaterFromZone(GameUI gui, Bot bot, Area zone)
        throws InterruptedException {
        if(gui == null || gui.ui == null || gui.ui.sess == null || zone == null) return false;
        Gob barrel = gui.ui.sess.glob.oc.stream()
            .filter(g -> g != null && !g.disposed() && g.is(GobTag.HAS_WATER))
            .filter(g -> g.rc != null && zone.contains(g.rc.floor(MCache.tilesz)))
            .min(PositionHelper.byDistanceToPlayer)
            .orElse(null);
        if(barrel == null || !BotMovement.approach(gui, bot, barrel, BotMovement.Mode.LAND).ok())
            return false;

        List<InvHelper.ContainedItem> targets = Stream.of(
                InvHelper.POUCHES_CONTAINED(gui).get().stream().filter(InvHelper::isDrinkContainer),
                InvHelper.INVENTORY_CONTAINED(gui).get().stream().filter(InvHelper::isDrinkContainer),
                InvHelper.BELT_CONTAINED(gui).get().stream().filter(InvHelper::isDrinkContainer),
                InvHelper.HANDS_CONTAINED(gui).get().stream()
                    .filter(ci -> InvHelper.isDrinkContainer(ci) || InvHelper.isBucket(ci)))
            .flatMap(stream -> stream)
            .filter(InvHelper::isNotFull)
            .collect(Collectors.toList());
        Set<GItem> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        targets.removeIf(ci -> !seen.add(ci.item.item));

        for(InvHelper.ContainedItem item : targets) {
            bot.checkCancelled();
            ContainedTarget target = new ContainedTarget(item);
            target.take();
            BotUtil.waitHeldChanged(gui, 2000);
            barrel.itemact(UI.MOD_META);
            BotUtil.pause(200);
            target.putBack();
            BotUtil.waitHeldChanged(gui, 2000);
        }
        if(targets.isEmpty()) return false;
        BotUtil.pause(200);
        for(InvHelper.ContainedItem target : targets) {
            try {
                if(InvHelper.isNotFull(target) || !InvHelper.HAS_WATER.test(target.item)) return false;
            } catch(Loading loading) {
                return false;
            }
        }
        return true;
    }

    public static boolean eatFromZone(GameUI gui, Bot bot, Area zone, int untilPercent)
        throws InterruptedException {
        if(gui == null || gui.ui == null || gui.ui.sess == null || zone == null) return false;
        double target = Math.max(0, Math.min(100, untilPercent)) / 100.0;
        List<Gob> containers = gui.ui.sess.glob.oc.stream()
            .filter(ClearCutSupplies::isFoodInventorySource)
            .filter(g -> g.rc != null && zone.contains(g.rc.floor(MCache.tilesz)))
            .sorted(PositionHelper.byDistanceToPlayer)
            .collect(Collectors.toList());

        for(Gob container : containers) {
            IMeter meter = gui.getIMeter("nrj");
            if(meter != null && meter.meter(0) >= target) break;
            bot.checkCancelled();
            if(container.disposed() ||
                !BotMovement.approach(gui, bot, container, BotMovement.Mode.LAND).ok()) continue;
            Window window = openContainerWindow(gui, bot, container, 3000L);
            if(window == null) continue;
            Inventory inventory = findInventory(window);
            if(inventory != null) {
                while(true) {
                    bot.checkCancelled();
                    meter = gui.getIMeter("nrj");
                    if(meter == null || meter.meter(0) < 0 || meter.meter(0) >= target) break;
                    WItem food = inventory.children(WItem.class).stream()
                        .filter(w -> ItemData.hasFoodInfo(w.item)).findFirst().orElse(null);
                    if(food == null || !eatViaFlowerMenu(gui, bot, food)) break;
                    BotUtil.pause(1200);
                }
            }
            window.reqdestroy();
            BotUtil.pause(200);
        }
        IMeter meter = gui.getIMeter("nrj");
        return meter != null && meter.meter(0) >= target;
    }

    private static Window openContainerWindow(GameUI gui, Bot bot, Gob container, long timeoutMs)
        throws InterruptedException {
        Set<Widget> before = new HashSet<>();
        for(Widget widget = gui.lchild; widget != null; widget = widget.prev) before.add(widget);
        FlowerMenu.lastGob(container);
        Coord mc = container.rc.floor(OCache.posres);
        gui.map.wdgmsg("click", Coord.z, mc, 3, gui.ui.modflags(), 0,
            (int)container.id, mc, 0, -1);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while(System.currentTimeMillis() < deadline) {
            bot.checkCancelled();
            for(Widget widget = gui.lchild; widget != null; widget = widget.prev)
                if(widget instanceof Window && !before.contains(widget)) return (Window)widget;
            BotUtil.pause(100);
        }
        return null;
    }

    private static Inventory findInventory(Window window) {
        for(Widget widget = window.lchild; widget != null; widget = widget.prev) {
            Inventory inventory = ExtInventory.inventory(widget);
            if(inventory != null) return inventory;
        }
        return null;
    }

    private static boolean eatViaFlowerMenu(GameUI gui, Bot bot, WItem food)
        throws InterruptedException {
        Widget root = gui.ui.root;
        Set<Widget> before = new HashSet<>();
        collectWidgets(root, before);
        food.rclick();
        long deadline = System.currentTimeMillis() + 3000L;
        FlowerMenu menu = null;
        while(System.currentTimeMillis() < deadline) {
            bot.checkCancelled();
            menu = findNewWidget(root, FlowerMenu.class, before);
            if(menu != null) break;
            BotUtil.pause(100);
        }
        if(menu == null) return false;
        for(FlowerMenu.Petal petal : menu.opts) {
            if("Eat".equals(petal.name)) {
                menu.choose(petal);
                return true;
            }
        }
        menu.choose(null);
        return false;
    }

    private static void collectWidgets(Widget parent, Set<Widget> out) {
        for(Widget widget = parent.lchild; widget != null; widget = widget.prev) {
            out.add(widget);
            collectWidgets(widget, out);
        }
    }

    private static <T extends Widget> T findNewWidget(Widget root, Class<T> type,
                                                       Set<Widget> before) {
        for(Widget widget = root.lchild; widget != null; widget = widget.prev) {
            if(type.isInstance(widget) && !before.contains(widget)) return type.cast(widget);
            T nested = findNewWidget(widget, type, before);
            if(nested != null) return nested;
        }
        return null;
    }

    private static String itemName(GItem item) {
        if(item == null) return null;
        try {
            ItemInfo.Name info = ItemInfo.find(ItemInfo.Name.class, item.info());
            if(info == null || info.original == null) return null;
            return info.original.endsWith(STACK_SUFFIX)
                ? info.original.substring(0, info.original.length() - STACK_SUFFIX.length())
                : info.original;
        } catch(Loading loading) {
            return null;
        }
    }

    private static String baseResid(String resid) {
        if(resid == null || resid.isEmpty()) return null;
        int variant = resid.indexOf('[');
        return variant > 0 ? resid.substring(0, variant) : resid;
    }
}
