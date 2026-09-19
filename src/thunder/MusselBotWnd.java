package thunder;

import haven.*;

/** Compact controls for the player-started musseling bot. */
public final class MusselBotWnd extends WindowX {
    private static MusselBotWnd instance;
    private final Label runStatus;
    private final Button start;

    private MusselBotWnd() {
        super(Coord.z, "River Musseler");
        justclose = false;
        int y = 0;
        add(new Label("Automatically follows your saved waterways."), 0, y);
        y += UI.scale(20);
        add(new Label("Take the boat's driver seat, then press Start."), 0, y);
        y += UI.scale(25);
        runStatus = add(new Label(statusText()), 0, y);
        y += UI.scale(25);
        start = add(new Button(UI.scale(90), "Start") {public void click() {startRun();}}, 0, y);
        add(new Button(UI.scale(90), "Stop") {public void click() {MusselBot.stop();}}, UI.scale(98), y);
        y += UI.scale(28);
        add(new Button(UI.scale(188), "Save Debug Snapshot") {
            public void click() {MusselBot.saveDebugSnapshot(ui == null ? null : ui.gui);}
        }, 0, y);
        y += UI.scale(26);
        add(new Label("Route debug: cyan=plan, magenta=live"), 0, y);
        y += UI.scale(18);
        add(new Label("white=actual, yellow=request, red=stop"), 0, y);
        pack();
        updateButtons();
    }

    private void startRun() {
        MusselBot.start(ui.gui);
        updateButtons();
    }

    private String statusText() {return MusselBot.status() + "  |  Collected: " + MusselBot.collected();}
    private void updateButtons() {if(start != null) start.disable(MusselBot.isRunning());}

    public void tick(double dt) {
        super.tick(dt);
        runStatus.settext(statusText());
        updateButtons();
    }

    public static void toggle(Widget parent) {
        if(instance == null) instance = parent.add(new MusselBotWnd());
        else {instance.reqdestroy(); instance = null;}
    }

    public void destroy() {
        MusselBot.stop();
        super.destroy();
        instance = null;
    }
}
