package auto;

import haven.*;
import rx.functions.Action2;

import java.util.*;


public class Bot implements Defer.Callable<Void> {
    private static final Object lock = new Object();
    private static Bot current;
    private final List<ITarget> targets;
    private BotAction[] actions;
    private Defer.Future<Void> task;
    private boolean highlight = true;
    private boolean cancelled = false;
    private String message = null;
    BotAction[] setup = null;
    BotAction[] cleanup = null;
    UI ui;

    private Bot(List<ITarget> targets) {
	this.targets = targets;
    }

    public UI ui() {return ui;}

    public GameUI gui() {return ui != null ? ui.gui : null;}

    public Bot actions(BotAction... actions) {
	this.actions = actions;
	return this;
    }

    public Bot setup(BotAction... actions) {
	setup = actions;
	return this;
    }

    public Bot cleanup(BotAction... actions) {
	cleanup = actions;
	return this;
    }

    public Bot highlight(boolean value) {
	highlight = value;
	return this;
    }

    @Override
    public Void call() throws InterruptedException {
	try {
	    if(setup != null) {
		for (BotAction action : setup) {
		    action.call(null, this);
		}
	    }
	    if(actions != null) {
		if(highlight) {targets.forEach(ITarget::highlight);}
		for (ITarget target : targets) {
		    for (BotAction action : actions) {
			if(target.disposed()) {break;}
			action.call(target, this);
			checkCancelled();
		    }
		}
	    }
	    if(cleanup != null) {
		for (BotAction action : cleanup) {
		    action.call(null, this);
		}
	    }
	} catch (InterruptedException e) {
	    if(message == null) { message = "Task interrupted"; }
	    throw e;
	} catch (Throwable e) {
	    if(message == null) {
		message = "Task error: " + e.getClass().getSimpleName()
		    + (e.getMessage() != null ? ": " + e.getMessage() : "");
	    }
	    throw new RuntimeException(e);
	} finally {
	    synchronized (lock) {
		if(current == this) {current = null;}
	    }
	}
	return null;
    }

    private void run(Action2<Boolean, String> callback) {
	task = Defer.later(this);
	task.callback(() -> callback.call(task.cancelled(), message));
    }

    void checkCancelled() throws InterruptedException {
	if(cancelled) {
	    throw new InterruptedException();
	}
    }

    private void markCancelled() {
	cancelled = true;
	task.cancel();
    }

    public void cancel(String message) {
	this.message = message;
	markCancelled();
    }

    public void cancel() {
	cancel(null);
    }

    public static void cancelCurrent() {
	cancelCurrent("Cancelled by user");
    }

    public static boolean hasCurrent() {
	synchronized (lock) { return current != null; }
    }

    public static void cancelCurrent(String reason) {
	synchronized (lock) {
	    if(current != null) {
		current.cancel(current.message == null ? reason : current.message);
	    }
	    current = null;
	}
    }

    private static void setCurrent(Bot bot) {
	synchronized (lock) {
	    if(current != null) {
		current.cancel(current.message == null ? "Replaced by another task" : current.message);
	    }
	    current = bot;
	}
    }

    public static Bot process(List<ITarget> targets) {
	return new Bot(targets);
    }

    public static Bot execute(BotAction... actions) {
	return new Bot(Targets.EMPTY).actions(actions);
    }

    public void start(UI ui) {start(ui, false);}

    public void start(UI ui, boolean silent) {
	this.ui = ui;
	doStart(this, ui, silent);
    }

    private static void doStart(Bot bot, UI ui, boolean silent) {
	setCurrent(bot);
	bot.run((cancelled, message) -> {
	    if(!silent && CFG.SHOW_BOT_MESSAGES.get() || message != null) {
		GameUI.MsgType type = cancelled ? GameUI.MsgType.ERROR : GameUI.MsgType.INFO;
		if(message == null) {
		    message = cancelled
			? "Task is cancelled."
			: "Task is completed.";
		    type = GameUI.MsgType.INFO;
		}
		ui.message(message, type);
	    }
	});
    }

    public interface BotAction {
	void call(ITarget target, Bot bot) throws InterruptedException;
    }

}
