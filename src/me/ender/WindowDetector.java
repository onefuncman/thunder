package me.ender;

import auto.Actions;
import auto.InventorySorter;
import haven.*;
import haven.res.ui.tt.drinkbuff.Drinkbuff;
import haven.rx.CharterBook;
import haven.rx.Reactor;
import me.ender.ui.CFGBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static me.ender.ItemHelpers.*;

public class WindowDetector {
    public static final String WND_STUDY = "Study";
    public static final String WND_TABLE = "Table";
    public static final String WND_CHARACTER_SHEET = "Character Sheet";
    public static final String WND_SMELTER = "Ore Smelter";
    public static final String WND_FINERY_FORGE = "Finery Forge";
    public static final String WND_STACK_FURNACE = "Stack furnace";
    
    private static final Object lock = new Object();
    private static final Set<Window> toDetect = new HashSet<>();
    private static final Set<Window> detected = new HashSet<>();
    
    static {
	Reactor.WINDOW.subscribe(WindowDetector::onWindowEvent);
    }
    
    public static void process(Widget wdg, Widget parent) {
	if(wdg instanceof Window) {
	    detect((Window) wdg);
	}
	untranslate(wdg, parent);
    }
    
    public static void detect(Window window) {
	synchronized (toDetect) {
	    toDetect.add(window);
	}
    }
    
    private static void onWindowEvent(Pair<Window, String> event) {
	synchronized (lock) {
	    Window window = event.a;
	    if(toDetect.contains(window)) {
		String eventName = event.b;
		switch (eventName) {
		    case Window.ON_DESTROY:
			toDetect.remove(window);
			detected.remove(window);
			break;
		    //Detect window on 'pack' message - this is last message server sends after constructing a window
		    case Window.ON_PACK:
			if(!detected.contains(window)) {
			    detected.add(window);
			    recognize(window);
			}
			break;
		}
	    }
	}
    }
    
    private static void recognize(Window window) {
	if(isWindowType(window, WND_TABLE)) {
	    extendTableWindow(window);
	} else {
	    AnimalFarm.processCattleInfo(window);
	}
    }
    
    private static void untranslate(Widget wdg, Widget parent) {
	Label lbl;
	if(parent instanceof Window) {
	    Window window = (Window) parent;
	    String caption = window.caption();
	    if("Milestone".equals(caption) && wdg instanceof Label) {
		lbl  = (Label) wdg;
		if(!lbl.original.equals("Make new trail:")) {
		    lbl.i10n(false);
		}
	    } else if(isProspecting(caption)) {
		if(wdg instanceof Label) {
		    lbl = (Label) wdg;
		    ((ProspectingWnd) parent).text(lbl.original);
		} else if(wdg instanceof Button) {
		    ((Button) wdg).large(false);
		}
	    }
	}
    }
    
    public static Widget newWindow(Coord sz, String title, boolean lg) {
	if(isPortal(title)) {
	    return new CharterBook(sz, title, lg);
	} else if(isProspecting(title)) {
	    return new ProspectingWnd(sz, title);
	}
	return (new WindowX(sz, title, lg));
    }
    
    public static String getWindowName(Widget wdg) {
	Window wnd;
	if(wdg == null) {return null;}
	if(wdg instanceof Window) {
	    wnd = (Window) wdg;
	} else {
	    wnd = wdg.getparent(Window.class);
	}
	return wnd == null ? null : wnd.caption();
    }
    
    public static boolean isWindowType(Widget wdg, String... types) {
	if(types == null || types.length == 0) {return false;}
	String wnd = getWindowName(wdg);
	if(wnd == null) {return false;}
	for (String type : types) {
	    if(Objects.equals(type, wnd)) {return true;}
	}
	
	return false;
    }
    
    public static boolean isPortal(String title) {
	return "Sublime Portico".equals(title) || "Charter Stone".equals(title);
    }
    
    public static boolean isBelt(String title) {
	return "Belt".equals(title);
    }
    
    public static boolean isProspecting(String title) {
	return "Prospecting".equals(title);
    }
    
    private static void extendTableWindow(Window wnd) {
	Inventory food = null;
	for (Inventory inventory : wnd.children(Inventory.class)) {
	    Coord isz = inventory.isz;
	    if(isz.equals(DISHES_SZ) || isz.equals(TABLECLOTH_SZ) || isz.equals(ALCHEMY_SZ)) {continue;}
	    food = inventory;
	    break;
	}
	if(food != null) {
	    Coord p = wnd.xlate(food.parentpos(wnd, food.pos("ur")), false);
	    final Inventory tmp = food;
	    wnd.adda(new IButton("gfx/hud/btn-sort", "", "-d", "-h"), p, 1, 1)
		.action(() -> InventorySorter.sort(tmp))
		.settip("Sort");
	}
	
	Button btn = wnd.getchild(Button.class);
	if(btn == null) {return;}
	
	btn.c = wnd.add(new CFGBox("Preserve cutlery", CFG.PRESERVE_SYMBEL), btn.pos("ul"))
	    .settip("Prevent eating from this table if some of the cutlery is almost broken").pos("bl");
	
	wnd.add(new Button(55, "Salt All", false, () -> Actions.saltFood(wnd.ui.gui)), btn.pos("ur").adds(-55, -20))
	    .settip("Salt all food");

	wnd.add(new DrinkBuffLabel(), btn.pos("bl").adds(0, 4));
    }

    private static class DrinkBuffLabel extends Label {
	private String sig = "init";
	private List<Buff> drinkBuffs = new ArrayList<>();
	private Tex longtip;

	DrinkBuffLabel() {
	    super("Drinks: ...");
	}

	@Override
	public void tick(double dt) {
	    super.tick(dt);
	    List<Buff> found = findDrinkBuffs();
	    String newSig = buildSig(found);
	    if(!newSig.equals(sig)) {
		sig = newSig;
		drinkBuffs = found;
		if(longtip != null) {longtip.dispose(); longtip = null;}
		settext(buildText(found));
	    }
	}

	private List<Buff> findDrinkBuffs() {
	    List<Buff> out = new ArrayList<>();
	    try {
		Bufflist bl = ui.gui.buffs;
		if(bl == null) {return out;}
		for(Buff buff : bl.children(Buff.class)) {
		    try {
			for(ItemInfo info : buff.info()) {
			    if(info instanceof Drinkbuff) {
				out.add(buff);
				break;
			    }
			}
		    } catch(Loading ignore) {}
		}
	    } catch(Exception ignore) {}
	    return out;
	}

	private static String buildSig(List<Buff> buffs) {
	    StringBuilder sb = new StringBuilder();
	    for(Buff b : buffs) {
		try {
		    for(ItemInfo i : b.info()) {
			if(i instanceof Drinkbuff) {
			    Drinkbuff db = (Drinkbuff) i;
			    sb.append(db.nm).append(':').append(db.n).append(';');
			}
		    }
		} catch(Loading ignore) {}
	    }
	    return sb.toString();
	}

	private static String buildText(List<Buff> buffs) {
	    StringBuilder sb = new StringBuilder();
	    for(Buff b : buffs) {
		try {
		    for(ItemInfo i : b.info()) {
			if(i instanceof Drinkbuff) {
			    Drinkbuff db = (Drinkbuff) i;
			    if(sb.length() > 0) {sb.append("  ");}
			    sb.append(db.nm).append(": ").append(db.n);
			}
		    }
		} catch(Loading ignore) {}
	    }
	    return sb.length() == 0 ? "Drinks: 0" : sb.toString();
	}

	@Override
	public Object tooltip(Coord c, Widget prev) {
	    if(drinkBuffs.isEmpty()) {return null;}
	    if(longtip == null) {
		List<ItemInfo> infos = new ArrayList<>();
		for(Buff b : drinkBuffs) {
		    try {infos.addAll(b.info());} catch(Loading ignore) {}
		}
		if(infos.isEmpty()) {return "...";}
		try {
		    longtip = new TexI(ItemInfo.longtip(infos));
		} catch(Loading l) {return "...";}
	    }
	    return longtip;
	}
    }
}
