package thunder.fish;

import haven.Area;
import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.MapView;
import haven.TextEntry;
import haven.UI;
import haven.Widget;
import haven.WindowX;

/**
 * Setup window for the Fish Spit-Roast Bot: three drag-rectangle map areas
 * (raw-fish input, fire/spit, cooked-fish output), a batch-size field, and
 * Start/Stop. Zones are session-only and cleared when this window closes.
 *
 * Uses a fish-specific zone picker and performs per-role cleanup only, so
 * closing this window never erases mining-zone overlays.
 */
public class FishSpitRoastSetupWnd extends WindowX {
    private static FishSpitRoastSetupWnd instance;
    private static final int W = 300;

    private static final Integer[] RGB_INPUT = {255, 90, 200};   // magenta
    private static final Integer[] RGB_FIRE = {255, 70, 60};     // red
    private static final Integer[] RGB_OUTPUT = {255, 220, 40};  // yellow

    static {
        FishZonePicker.registerRoleColor(FishSpitRoastZoneStore.ROLE_INPUT, RGB_INPUT);
        FishZonePicker.registerRoleColor(FishSpitRoastZoneStore.ROLE_FIRE, RGB_FIRE);
        FishZonePicker.registerRoleColor(FishSpitRoastZoneStore.ROLE_OUTPUT, RGB_OUTPUT);
    }

    private final TextEntry batchField;
    private final Label allSetStatus;

    private FishSpitRoastSetupWnd() {
        super(Coord.z, "Fish Spit-Roast Setup");
        justclose = true;
        int y = 0;

        add(new Label("Batch size (fish per run):"), 0, y);
        batchField = add(new TextEntry(UI.scale(40), "15"), UI.scale(190), y);
        y += batchField.sz.y + UI.scale(10);

        y = zoneRow(y, "Raw-fish input area", FishSpitRoastZoneStore.ROLE_INPUT);
        y = zoneRow(y, "Fire / roasting-spit area", FishSpitRoastZoneStore.ROLE_FIRE);
        y = zoneRow(y, "Cooked-fish output area", FishSpitRoastZoneStore.ROLE_OUTPUT);
        y += UI.scale(4);

        allSetStatus = add(new Label(allSetText()), 0, y);
        y += allSetStatus.sz.y + UI.scale(8);

        add(new Label("Fires must already be lit and fueled."), 0, y);
        y += UI.scale(16);

        add(new Button(UI.scale(110), "Start") {
            public void click() {start();}
        }, 0, y);
        add(new Button(UI.scale(110), "Stop") {
            public void click() {FishSpitRoastBot.stop();}
        }, UI.scale(120), y);
        y += UI.scale(30);

        pack();
        Coord asz = ca().sz();
        if (asz.x < UI.scale(W)) {
            resize(new Coord(UI.scale(W), asz.y));
        }
    }

    @Override
    protected void added() {
        super.added();
        showSavedZones();
    }

    private void showSavedZones() {
        if (ui == null || ui.gui == null) {return;}
        MapView map = ui.gui.map;
        if (map == null) {return;}
        for (String role : new String[]{
            FishSpitRoastZoneStore.ROLE_INPUT,
            FishSpitRoastZoneStore.ROLE_FIRE,
            FishSpitRoastZoneStore.ROLE_OUTPUT,
        }) {
            Area a = FishSpitRoastZoneStore.get().get(role);
            if (a != null) {FishZonePicker.showZone(map, role, a);}
        }
    }

    private int zoneRow(int y, String label, String role) {
        add(new Label(label + ":"), 0, y);
        y += UI.scale(16);
        Label status = add(new Label(zoneStatusText(role)), 0, y);
        add(new Button(UI.scale(60), "Set") {
            public void click() {pickZone(role, status);}
        }, UI.scale(200), y - UI.scale(2));
        return y + UI.scale(22);
    }

    private void pickZone(String role, Label status) {
        MapView map = ui.gui.map;
        if (map == null) {return;}
        msg("Drag a rectangle on the map to designate the " + role + " zone.");
        FishZonePicker.start(map, role, area -> {
            FishSpitRoastZoneStore.get().put(role, area);
            FishZonePicker.showZone(map, role, area);
            status.settext(zoneStatusText(role));
            allSetStatus.settext(allSetText());
            msg(role + " zone set.");
        });
    }

    private String zoneStatusText(String role) {
        Area a = FishSpitRoastZoneStore.get().get(role);
        if (a == null) {return "not set";}
        Coord sz = a.sz();
        return "set (" + sz.x + "x" + sz.y + " tiles)";
    }

    private String allSetText() {
        for (String role : new String[]{
            FishSpitRoastZoneStore.ROLE_INPUT,
            FishSpitRoastZoneStore.ROLE_FIRE,
            FishSpitRoastZoneStore.ROLE_OUTPUT,
        }) {
            if (FishSpitRoastZoneStore.get().get(role) == null) {
                return "Status: not all areas set yet";
            }
        }
        return "Status: all three areas set";
    }

    private void start() {
        GameUI gui = ui.gui;
        int batch;
        try {
            batch = Integer.parseInt(batchField.text().trim());
        } catch (NumberFormatException e) {
            gui.error("Batch size must be a whole number.");
            return;
        }
        Area input = FishSpitRoastZoneStore.get().get(FishSpitRoastZoneStore.ROLE_INPUT);
        Area fire = FishSpitRoastZoneStore.get().get(FishSpitRoastZoneStore.ROLE_FIRE);
        Area output = FishSpitRoastZoneStore.get().get(FishSpitRoastZoneStore.ROLE_OUTPUT);
        FishSpitRoastBot.start(gui, input, fire, output, batch);
    }

    public static void toggle(Widget parent) {
        if (instance == null) {
            instance = parent.add(new FishSpitRoastSetupWnd());
        } else {
            doClose();
        }
    }

    private static void doClose() {
        if (instance != null) {
            instance.reqdestroy();
            instance = null;
        }
    }

    @Override
    public void destroy() {
        FishZonePicker.cancel();
        // Per-role cleanup only -- mining-zone overlays must survive this window.
        FishZonePicker.hideZone(FishSpitRoastZoneStore.ROLE_INPUT);
        FishZonePicker.hideZone(FishSpitRoastZoneStore.ROLE_FIRE);
        FishZonePicker.hideZone(FishSpitRoastZoneStore.ROLE_OUTPUT);
        FishSpitRoastZoneStore.get().clearAll();
        super.destroy();
        instance = null;
    }

    private void msg(String s) {
        if (ui != null && ui.gui != null) {ui.gui.msg(s, GameUI.MsgType.INFO);}
    }
}
