package me.ender;

import haven.*;
import haven.res.ui.tt.wear.Wear;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static me.ender.ResName.*;

public class ItemHelpers {
    public static final Coord DISHES_SZ = Coord.of(3, 3);
    public static final Coord TABLECLOTH_SZ = Coord.of(1, 2);
    public static final Coord ALCHEMY_SZ = Coord.of(4, 2);
    private static final int DISH_HP_WARNING = 1;
    
    
    public static boolean canTake(WItem item) {
	UI ui = item.ui;
	if(ui == null) {return true;}
	String msg;
	if(CFG.PRESERVE_SYMBEL.get() && (msg = preserveDishes(item, ui)) != null) {
	    ui.message(msg, GameUI.MsgType.ERROR);
	    return false;
	}
	return true;
    }
    
    public static void invalidateFoodItemTooltips(UI ui) {
	Set<WItem> children = ui.root.children(WItem.class);
	children.forEach(w -> {if(ItemData.hasFoodInfo(w.item)) {w.clearLongTip();}});
    }
    
    public static void invalidateIngredientTooltips(UI ui) {
	Set<WItem> children = ui.root.children(WItem.class);
	children.forEach(w -> {if(ItemData.hasIngredientInfo(w.item)) {w.clearLongTip();}});
    }
    
    public static Stream<WItem> findAll(UI ui, Predicate<WItem> filter) {
	return ui.root.children(WItem.class).stream().filter(filter);
    }
    
    public static Optional<WItem> find(UI ui, Predicate<WItem> filter) {
	return ui.root.children(WItem.class).stream().filter(filter).findFirst();
    }
    
    private static String preserveDishes(WItem item, UI ui) {
	if(!ItemData.hasFoodInfo(item.item)) {return null;}

	if(!eatCursorActive(ui)) {return null;}

	Window wnd = item.getparent(Window.class);
	if(wnd == null) {return null;}

	try {
	    Optional<WItem> atRisk = wnd.children(Inventory.class).stream()
		.filter(i -> i.isz.equals(DISHES_SZ) || i.isz.equals(TABLECLOTH_SZ))
		.flatMap(i -> i.children(WItem.class).stream())
		.filter(w -> {
		    Wear wear = ItemInfo.getWear(w.item.info());
		    if(wear == null) {return true;}
		    return wear.m - wear.d <= DISH_HP_WARNING;
		})
		.findFirst();

	    return atRisk.map(wItem -> {
		try {
		    return String.format("Cannot eat from this table: %s is almost broken!", wItem.name.get());
		} catch (Loading l) {
		    return "Cannot eat from this table: some cutlery is almost broken!";
		}
	    }).orElse(null);
	} catch (Loading l) {
	    return "Cannot eat from this table: cutlery info still loading.";
	}
    }

    private static boolean eatCursorActive(UI ui) {
	try {
	    Indir<Resource> cur = ui.root.cursor;
	    if(cur == null) {return false;}
	    return CURSOR_EAT.equals(cur.get().name);
	} catch (Loading l) {
	    return true;
	}
    }
}
