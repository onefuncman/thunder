package thunder.cellar;

import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.UI;
import haven.Widget;
import haven.WindowX;

/** Compact controls for the player-started Cellar Digger. */
public final class CellarDiggerWnd extends WindowX {
    private static CellarDiggerWnd instance;
    private final WrappedLabel runStatus;
    private final Button start;
    private final Button stop;
    private String shownStatus;

    private CellarDiggerWnd() {
        super(Coord.z, "Cellar Digger");
        justclose = false;
        int y = 0;
        add(new Label("Stand inside a building with its cellar door visible."), 0, y);
        y += UI.scale(20);
        add(new Label("Carry a pickaxe and water; enable Auto-drink."), 0, y);
        y += UI.scale(25);
        shownStatus = CellarDigger.status();
        runStatus = add(new WrappedLabel(shownStatus, UI.scale(430)), 0, y);
        y += runStatus.sz.y + UI.scale(7);
        start = add(new Button(UI.scale(90), "Start") {
            public void click() {startRun();}
        }, 0, y);
        stop = add(new Button(UI.scale(90), "Stop") {
            public void click() {CellarDigger.stop();}
        }, UI.scale(98), y);
        pack();
        updateButtons();
    }

    private void startRun() {
        GameUI gui = ui == null ? null : ui.gui;
        CellarDigger.start(gui);
        updateButtons();
    }

    private void updateButtons() {
        if(start != null) start.disable(CellarDigger.isRunning());
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        String current = CellarDigger.status();
        if(!current.equals(shownStatus)) {
            shownStatus = current;
            runStatus.settext(current);
            int y = runStatus.c.y + runStatus.sz.y + UI.scale(7);
            start.move(Coord.of(0, y));
            stop.move(Coord.of(UI.scale(98), y));
            pack();
        }
        updateButtons();
    }

    private static final class WrappedLabel extends Label {
        private final int width;

        WrappedLabel(String text, int width) {
            super(text, width);
            this.width = width;
        }

        @Override
        public void settext(String text) {
            if(text.equals(this.texts)) return;
            this.text.dispose();
            this.text = f.renderwrap(texts = text, col, width);
            resize(this.text.sz());
        }
    }

    public static void toggle(Widget parent) {
        if(instance == null) instance = parent.add(new CellarDiggerWnd());
        else {
            instance.reqdestroy();
            instance = null;
        }
    }

    @Override
    public void destroy() {
        CellarDigger.stop();
        super.destroy();
        instance = null;
    }
}
