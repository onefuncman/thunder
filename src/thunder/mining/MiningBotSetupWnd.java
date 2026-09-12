package thunder.mining;

import auto.MiningBot;
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
 * Setup window for MiningBot: direction/radius/material-threshold fields, three
 * "designate zone" buttons (stockpile/water/food) that arm a drag-rectangle
 * picker on the game map, and Start/Stop. Zones are session-only -- cleared
 * on window close, never persisted -- so they're re-designated each time the
 * window is reopened. Toggle-singleton per GobWarning.WarnCFGWnd.
 */
public class MiningBotSetupWnd extends WindowX {
    private static MiningBotSetupWnd instance;
    private static final int W = 260;

    private final TextEntry dirField;
    private final TextEntry radiusField;
    private final TextEntry stoneField;
    private final TextEntry eatUntilField;
    private final TextEntry capField;
    private final TextEntry barsTargetField;

    private MiningBotSetupWnd() {
        super(Coord.z, "MiningBot Setup");
        justclose = true;
        int y = 0;

        add(new Label("Direction (n/s/e/w):"), 0, y);
        dirField = add(new TextEntry(UI.scale(40), "n"), UI.scale(180), y);
        y += dirField.sz.y + UI.scale(6);

        add(new Label("Support radius (tiles):"), 0, y);
        radiusField = add(new TextEntry(UI.scale(40), "9"), UI.scale(180), y);
        y += radiusField.sz.y + UI.scale(6);

        add(new Label("Minimum stone:"), 0, y);
        stoneField = add(new TextEntry(UI.scale(40), "30"), UI.scale(180), y);
        y += stoneField.sz.y + UI.scale(6);

        add(new Label("Bars to carry (stockpile trips):"), 0, y);
        barsTargetField = add(new TextEntry(UI.scale(40), "10"), UI.scale(230), y);
        y += barsTargetField.sz.y + UI.scale(6);

        add(new Label("Eat-until energy % (food-zone trips):"), 0, y);
        eatUntilField = add(new TextEntry(UI.scale(40), "95"), UI.scale(230), y);
        y += eatUntilField.sz.y + UI.scale(6);

        add(new Label("Safety cap (segments, 0 = unlimited):"), 0, y);
        capField = add(new TextEntry(UI.scale(40), "0"), UI.scale(230), y);
        y += capField.sz.y + UI.scale(12);

        y = zoneRow(y, "Stockpile zone (stone/bars)", MiningZoneStore.ROLE_STOCKPILE);
        y = zoneRow(y, "Water zone (barrel)", MiningZoneStore.ROLE_WATER);
        y = zoneRow(y, "Food zone (containers)", MiningZoneStore.ROLE_FOOD);
        y += UI.scale(8);

        add(new Button(UI.scale(100), "Start") {
            public void click() {start();}
        }, 0, y);
        add(new Button(UI.scale(100), "Stop") {
            public void click() {MiningBot.stop();}
        }, UI.scale(110), y);
        y += UI.scale(30);

        pack();
        Coord asz = ca().sz();
        if(asz.x < UI.scale(W)) {
            resize(new Coord(UI.scale(W), asz.y));
        }
    }

    @Override
    protected void added() {
        super.added();
        showSavedZones();
    }

    private void showSavedZones() {
        if(ui == null || ui.gui == null) {return;}
        MapView map = ui.gui.map;
        if(map == null) {return;}
        for(String role : new String[]{MiningZoneStore.ROLE_STOCKPILE, MiningZoneStore.ROLE_WATER, MiningZoneStore.ROLE_FOOD}) {
            Area a = MiningZoneStore.get().get(role);
            if(a != null) {ZonePicker.showZone(map, role, a);}
        }
    }

    private int zoneRow(int y, String label, String role) {
        add(new Label(label + ":"), 0, y);
        y += UI.scale(16);
        Label status = add(new Label(zoneStatusText(role)), 0, y);
        add(new Button(UI.scale(60), "Set") {
            public void click() {pickZone(role, status);}
        }, UI.scale(170), y - UI.scale(2));
        return y + UI.scale(22);
    }

    private void pickZone(String role, Label status) {
        MapView map = ui.gui.map;
        if(map == null) {return;}
        msg("Drag a rectangle on the map to designate the " + role + " zone.");
        ZonePicker.start(map, role, area -> {
            MiningZoneStore.get().put(role, area);
            ZonePicker.showZone(map, role, area);
            status.settext(zoneStatusText(role));
            msg(role + " zone set.");
        });
    }

    private String zoneStatusText(String role) {
        Area a = MiningZoneStore.get().get(role);
        if(a == null) {return "not set";}
        Coord sz = a.sz();
        return "set (" + sz.x + "x" + sz.y + " tiles)";
    }

    private void start() {
        GameUI gui = ui.gui;
        MiningBot.Direction dir;
        try {
            dir = MiningBot.Direction.parse(dirField.text().trim());
        } catch(IllegalArgumentException e) {
            gui.error(e.getMessage());
            return;
        }
        int radius, stone, eatUntil, cap, barsTarget;
        try {
            radius = Integer.parseInt(radiusField.text().trim());
            stone = Integer.parseInt(stoneField.text().trim());
            eatUntil = Integer.parseInt(eatUntilField.text().trim());
            cap = Integer.parseInt(capField.text().trim());
            barsTarget = Integer.parseInt(barsTargetField.text().trim());
        } catch(NumberFormatException e) {
            gui.error("Radius/stone/eat%/cap/bars must be valid whole numbers.");
            return;
        }
        MiningBot.start(gui, dir, radius, stone, eatUntil, cap, barsTarget);
    }

    public static void toggle(Widget parent) {
        if(instance == null) {
            instance = parent.add(new MiningBotSetupWnd());
        } else {
            doClose();
        }
    }

    private static void doClose() {
        if(instance != null) {
            instance.reqdestroy();
            instance = null;
        }
    }

    @Override
    public void destroy() {
        ZonePicker.cancel();
        ZonePicker.hideAllZones();
        MiningZoneStore.get().clearAll();
        super.destroy();
        instance = null;
    }

    private void msg(String s) {
        if(ui != null && ui.gui != null) {ui.gui.msg(s, GameUI.MsgType.INFO);}
    }
}
