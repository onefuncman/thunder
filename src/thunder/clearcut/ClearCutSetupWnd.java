package thunder.clearcut;

import auto.Bot;
import haven.*;
import thunder.mining.ZonePicker;

import java.util.LinkedHashMap;
import java.util.Map;

/** Session-only setup window for the Clear-Cut bot. */
public final class ClearCutSetupWnd extends WindowX {
    private static ClearCutSetupWnd instance;
    private static final int WIDTH = 330;
    private static final String[] ROLES = {
        ClearCutZoneStore.ROLE_CLEAR_CUT, ClearCutZoneStore.ROLE_WATER,
        ClearCutZoneStore.ROLE_FOOD, ClearCutZoneStore.ROLE_LOG_DROP,
        ClearCutZoneStore.ROLE_PRODUCT_DROP
    };

    private final Map<String, Label> zoneLabels = new LinkedHashMap<>();
    private final CheckBox collectProducts;
    private final Widget productRow;
    private final Label readiness;
    private final Label runStatus;

    static {
        ZonePicker.registerRoleColor(ClearCutZoneStore.ROLE_CLEAR_CUT, new Integer[]{220, 65, 65});
        ZonePicker.registerRoleColor(ClearCutZoneStore.ROLE_WATER, new Integer[]{40, 150, 255});
        ZonePicker.registerRoleColor(ClearCutZoneStore.ROLE_FOOD, new Integer[]{80, 220, 90});
        ZonePicker.registerRoleColor(ClearCutZoneStore.ROLE_LOG_DROP, new Integer[]{210, 145, 55});
        ZonePicker.registerRoleColor(ClearCutZoneStore.ROLE_PRODUCT_DROP, new Integer[]{185, 90, 235});
    }

    private ClearCutSetupWnd() {
        super(Coord.z, "Clear-Cut Setup");
        justclose = false;
        int y = 0;
        add(new Label("Clear trees, bushes, boulders, stumps, and logs in an area."), 0, y);
        y += UI.scale(22);
        y = addZoneRow(y, "Clear-cut work area", ClearCutZoneStore.ROLE_CLEAR_CUT, this);
        y = addZoneRow(y, "Water containers (optional)", ClearCutZoneStore.ROLE_WATER, this);
        y = addZoneRow(y, "Energy food (optional)", ClearCutZoneStore.ROLE_FOOD, this);
        y = addZoneRow(y, "Log drop-off / carts", ClearCutZoneStore.ROLE_LOG_DROP, this);

        collectProducts = add(new CheckBox("Collect tree products") {
            public void set(boolean value) {
                super.set(value);
                if(productRow != null) productRow.show(value);
                if(readiness != null) readiness.settext(readinessText());
            }
        }, 0, y);
        y += UI.scale(24);

        productRow = add(new Widget(Coord.of(UI.scale(WIDTH), UI.scale(42))), 0, y);
        addZoneRow(0, "Tree-product containers", ClearCutZoneStore.ROLE_PRODUCT_DROP, productRow);
        productRow.hide();
        y += UI.scale(44);

        readiness = add(new Label(readinessText()), 0, y);
        y += UI.scale(20);
        add(new Label("No supply area: stop below 4,000% energy or when water runs out."), 0, y);
        y += UI.scale(20);
        runStatus = add(new Label(ClearCutBot.status()), 0, y);
        y += UI.scale(25);
        add(new Button(UI.scale(95), "Start") {public void click() {startBot();}}, 0, y);
        add(new Button(UI.scale(95), "Stop") {public void click() {ClearCutBot.stop();}}, UI.scale(105), y);
        y += UI.scale(30);

        pack();
        Coord content = ca().sz();
        if(content.x < UI.scale(WIDTH)) resize(Coord.of(UI.scale(WIDTH), content.y));
    }

    private int addZoneRow(int y, String title, String role, Widget parent) {
        parent.add(new Label(title + ":"), 0, y);
        Label state = parent.add(new Label(zoneText(role)), UI.scale(190), y);
        zoneLabels.put(role, state);
        parent.add(new Button(UI.scale(55), "Set") {
            public void click() {pickZone(title, role);}
        }, UI.scale(270), y - UI.scale(2));
        return y + UI.scale(25);
    }

    private void pickZone(String title, String role) {
        if(ui == null || ui.gui == null || ui.gui.map == null) return;
        ui.gui.msg("Drag the " + title.toLowerCase() + " on the map.", GameUI.MsgType.INFO);
        ZonePicker.start(ui.gui.map, role, area -> {
            ClearCutZoneStore.get().put(role, area);
            ZonePicker.showZone(ui.gui.map, role, area);
            Label label = zoneLabels.get(role);
            if(label != null) label.settext(zoneText(role));
            readiness.settext(readinessText());
        });
    }

    private String zoneText(String role) {
        Area area = ClearCutZoneStore.get().get(role);
        return area == null ? "not set" : area.sz().x + "x" + area.sz().y + " tiles";
    }

    private String readinessText() {
        ClearCutConfig cfg = config();
        String error = cfg.validationError();
        return error == null ? "Setup: ready" : "Setup: " + error;
    }

    private ClearCutConfig config() {
        ClearCutZoneStore store = ClearCutZoneStore.get();
        return new ClearCutConfig(
            store.get(ClearCutZoneStore.ROLE_CLEAR_CUT),
            store.get(ClearCutZoneStore.ROLE_WATER),
            store.get(ClearCutZoneStore.ROLE_FOOD),
            store.get(ClearCutZoneStore.ROLE_LOG_DROP),
            store.get(ClearCutZoneStore.ROLE_PRODUCT_DROP),
            collectProducts != null && collectProducts.a);
    }

    private void startBot() {
        if(Bot.hasCurrent() && !ClearCutBot.isRunning()) {
            ui.gui.error("Clear-Cut: another bot is already running.");
            return;
        }
        ClearCutBot.start(ui.gui, config());
    }

    public void tick(double dt) {
        super.tick(dt);
        runStatus.settext(ClearCutBot.status());
    }

    protected void added() {
        super.added();
        for(String role : ROLES) {
            Area area = ClearCutZoneStore.get().get(role);
            if(area != null) ZonePicker.showZone(ui.gui.map, role, area);
        }
    }

    public static void toggle(Widget parent) {
        if(instance == null) instance = parent.add(new ClearCutSetupWnd());
        else {instance.reqdestroy(); instance = null;}
    }

    public void destroy() {
        ZonePicker.cancel();
        for(String role : ROLES) ZonePicker.hideZone(role);
        ClearCutZoneStore.get().clearAll();
        super.destroy();
        instance = null;
    }
}
