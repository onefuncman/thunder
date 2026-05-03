package thunder.macro;

import auto.Bot;
import haven.GameUI;
import haven.UI;

public class MacroRunner {
    private final Macro macro;
    private final int repeat;
    private Bot bot;
    private UI ui;
    private volatile int iteration;
    private volatile int stepIndex;

    public MacroRunner(Macro macro, int repeat) {
	this.macro = macro;
	this.repeat = Math.max(1, repeat);
    }

    public Macro macro() {return macro;}
    public int repeat() {return repeat;}
    public int iteration() {return iteration;}
    public int stepIndex() {return stepIndex;}
    public Bot bot() {return bot;}
    public GameUI gui() {return ui != null ? ui.gui : null;}

    public void start(UI ui) {
	this.ui = ui;
	this.bot = Bot.execute((target, b) -> {
		for(int i = 0; i < repeat; i++) {
		    iteration = i + 1;
		    for(int s = 0; s < macro.steps.size(); s++) {
			b.checkCancelled();
			stepIndex = s;
			MacroStep step = macro.steps.get(s);
			step.execute(this);
		    }
		}
	    });
	bot.highlight(false).start(ui);
    }

    public void cancel() {
	if(bot != null) bot.cancel("Macro cancelled");
    }

    public static MacroRunner run(UI ui, Macro macro, int repeat) {
	MacroRunner r = new MacroRunner(macro, repeat);
	r.start(ui);
	return r;
    }
}
