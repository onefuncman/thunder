package thunder;

import haven.*;
import java.util.*;

/** Compact controls for the player-started directional forager. */
public final class DirectionalForagerWnd extends WindowX {
    private static DirectionalForagerWnd instance;
    private final Set<String> selected = new HashSet<>(CFG.DIRECTIONAL_FORAGER_WHITELIST.get());
    private final List<Row> rows = new ArrayList<>();
    private final Scrollport list;
    private final TextEntry search;
    private final Label runStatus;
    private final Button start;
    private DirectionalForagerLogic.Direction direction = DirectionalForagerLogic.Direction.NORTH;
    private boolean caveMode = CFG.DIRECTIONAL_FORAGER_CAVE_MODE.get();
    private int catalogSize;

    private DirectionalForagerWnd() {
        super(Coord.z, "Directional Forager");
        justclose = false;
        int y = 0;
        add(new Label("Direction:"), 0, y + UI.scale(3));
        RadioGroup dirs = new RadioGroup(this) {
            public void changed(int btn, String label) {
                direction = DirectionalForagerLogic.Direction.values()[btn];
            }
        };
        dirs.add("North", Coord.of(UI.scale(75), y));
        dirs.add("South", Coord.of(UI.scale(145), y));
        dirs.add("West", Coord.of(UI.scale(215), y));
        dirs.add("East", Coord.of(UI.scale(275), y));
        dirs.check(0);
        y += UI.scale(28);

        CheckBox cave = add(new CheckBox("Use saved-map cave coverage route (ignores direction)"), 0, y);
        cave.a = caveMode;
        cave.changed(on -> {
            caveMode = on;
            CFG.DIRECTIONAL_FORAGER_CAVE_MODE.set(on);
        });
        y += UI.scale(25);

        add(new Label("Forageables:"), 0, y + UI.scale(3));
        search = add(new TextEntry(UI.scale(235), "") {
            protected void changed() {super.changed(); filterRows();}
        }, UI.scale(95), y);
        y += search.sz.y + UI.scale(5);
        list = add(new Scrollport(Coord.of(UI.scale(330), UI.scale(220))), 0, y);
        rebuildRows();
        y += list.sz.y + UI.scale(6);

        add(new Button(UI.scale(90), "Select All") {public void click() {selectAll(true);}}, 0, y);
        add(new Button(UI.scale(75), "Clear") {public void click() {selectAll(false);}}, UI.scale(98), y);
        y += UI.scale(28);
        runStatus = add(new Label(statusText()), 0, y);
        y += UI.scale(23);
        start = add(new Button(UI.scale(90), "Start") {public void click() {startRun();}}, 0, y);
        add(new Button(UI.scale(90), "Stop") {public void click() {DirectionalForager.stop();}}, UI.scale(98), y);
        pack();
        updateButtons();
    }

    private void rebuildRows() {
        for(Row row : rows) row.box.reqdestroy();
        rows.clear();
        int y = 0;
        for(ForageCatalog.Entry entry : ForageCatalog.entries()) {
            CheckBox box = list.cont.add(new CheckBox.Untranslated(entry.name));
            box.a = selected.contains(entry.key);
            box.changed(on -> {
                if(on) selected.add(entry.key); else selected.remove(entry.key);
                save(); updateButtons();
            });
            box.move(Coord.of(0, y));
            rows.add(new Row(entry, box));
            y += UI.scale(22);
        }
        catalogSize = rows.size();
        filterRows();
    }

    private void filterRows() {
        if(search == null || list == null) return;
        String q = search.text().trim().toLowerCase(Locale.ROOT);
        int y = 0;
        for(Row row : rows) {
            boolean show = q.isEmpty() || row.entry.name.toLowerCase(Locale.ROOT).contains(q);
            row.box.show(show);
            if(show) {row.box.move(Coord.of(0, y)); y += UI.scale(22);}
        }
        list.bar.max(Math.max(0, y + UI.scale(10) - list.cont.sz.y));
    }

    private void selectAll(boolean on) {
        selected.clear();
        if(on) for(Row row : rows) selected.add(row.entry.key);
        for(Row row : rows) row.box.a = selected.contains(row.entry.key);
        save(); updateButtons();
    }

    private void save() {CFG.DIRECTIONAL_FORAGER_WHITELIST.set(new HashSet<>(selected));}

    private void startRun() {
        if(selected.isEmpty()) {ui.gui.error("Directional Forager: select at least one forageable."); return;}
        DirectionalForager.start(ui.gui, direction, selected, caveMode);
        updateButtons();
    }

    private String statusText() {
        return DirectionalForager.status() + "  |  Collected: " + DirectionalForager.collected();
    }

    private void updateButtons() {if(start != null) start.disable(selected.isEmpty() || DirectionalForager.isRunning());}

    public void tick(double dt) {
        super.tick(dt);
        observeNearby();
        if(ForageCatalog.entries().size() != catalogSize) rebuildRows();
        runStatus.settext(statusText());
        updateButtons();
    }

    private void observeNearby() {
        if(ui == null || ui.sess == null || ui.sess.glob == null) return;
        synchronized(ui.sess.glob.oc) {
            for(Gob gob : ui.sess.glob.oc) ForageCatalog.observe(gob);
        }
    }

    public static void toggle(Widget parent) {
        if(instance == null) instance = parent.add(new DirectionalForagerWnd());
        else {instance.reqdestroy(); instance = null;}
    }

    public void destroy() {
        DirectionalForager.stop();
        super.destroy();
        instance = null;
    }

    private static final class Row {
        final ForageCatalog.Entry entry;
        final CheckBox box;
        Row(ForageCatalog.Entry entry, CheckBox box) {this.entry = entry; this.box = box;}
    }
}
