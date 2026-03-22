package auto;

import haven.*;
import haven.rx.Reactor;

import java.util.function.Supplier;

public class BotUtil {
    private static final Object waiter = new Object();
    
    static boolean isHeld(GameUI gui, String what) throws Loading {
	GameUI.DraggedItem drag = gui.hand();
	if(drag == null && what == null) {
	    return true;
	}
	if(drag != null && what != null) {
	    return drag.item.is2(what);
	}
	return false;
    }
    
    static boolean waitHeld(GameUI gui, String what) {
	return waitHeld(gui, what, 5000);
    }

    static boolean waitHeld(GameUI gui, String what, long timeout) {
	if(Boolean.TRUE.equals(doWaitLoad(() -> isHeld(gui, what)))) {
	    return true;
	}
	if(waitHeldChanged(gui, timeout)) {
	    return Boolean.TRUE.equals(doWaitLoad(() -> isHeld(gui, what)));
	}
	return false;
    }
    
    static final Bot.BotAction WaitHeldChanged = (t, b) -> {
	boolean result = waitHeldChanged(b.gui());
	if(!result) {b.cancel();}
    };
    
    static boolean waitHeldChanged(GameUI gui) {
	return waitHeldChanged(gui, 5000);
    }

    static boolean waitHeldChanged(GameUI gui, long timeout) {
	boolean result = true;
	try {
	    synchronized (gui.heldNotifier) {
		gui.heldNotifier.wait(timeout);
	    }
	} catch (InterruptedException e) {
	    result = false;
	}
	return result;
    }
    
    /**returns true if wait was successful*/
    static boolean waitProgress(Bot bot, long startTimeout, long finishTimeout) throws InterruptedException {
	GameUI gui = bot.ui.gui;
	long wait = startTimeout;
	while (gui.prog == null) {
	    wait -= 10;
	    pause(10);
	    if(wait < 0) {return false;}
	    bot.checkCancelled();
	}
	wait = finishTimeout;
	while (gui.prog != null) {
	    wait -= 10;
	    pause(10);
	    if(wait < 0) {return false;}
	    bot.checkCancelled();
	}
	return true;
    }
    
    private static <T> T doWaitLoad(Supplier<T> action) {
	T result = null;
	boolean ready = false;
	while (!ready) {
	    try {
		result = action.get();
		ready = true;
	    } catch (Loading e) {
		pause(100);
	    }
	}
	return result;
    }
    
    static Bot.BotAction doWait(long ms) {
	return (t, b) -> pause(ms);
    }
    
    static void pause(long ms) {
	synchronized (waiter) {
	    try {
		waiter.wait(ms);
	    } catch (InterruptedException ignore) {
	    }
	}
    }
    
    private static void unpause() {
	synchronized (waiter) { waiter.notifyAll(); }
    }
    
    public static boolean isOnRadar(Gob gob) {
	if(!CFG.AUTO_PICK_ONLY_RADAR.get()) {return true;}
	Boolean onRadar = gob.isOnRadar();
	return onRadar == null || onRadar;
    }
    
    public static void rclick(GameUI gui) {
	click(3, gui);
    }
    
    public static void click(int btn, GameUI gui) {
	gui.map.wdgmsg("click", Coord.z, gui.map.player().rc.floor(OCache.posres), btn, 0);
    }
    
    static Bot.BotAction selectFlower(String... options) {
	return (target, bot) -> {
	    if(target.hasMenu()) {
		FlowerMenu.lastTarget(target);
		Reactor.FLOWER.first().subscribe(flowerMenu -> {
		    Reactor.FLOWER_CHOICE.first().subscribe(choice -> unpause());
		    flowerMenu.forceChoose(options);
		});
		pause(5000);
	    }
	};
    }
    
}
