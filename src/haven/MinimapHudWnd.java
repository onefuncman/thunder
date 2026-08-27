package haven;

public class MinimapHudWnd extends Window {
    private final GameUI.CornerMap map;
    private final Widget toolbar;

    public MinimapHudWnd(GameUI gui, GameUI.CornerMap map) {
	super(UI.scale(260, 220), "Minimap");
	this.map = add(map, Coord.z);
	this.toolbar = add(makeToolbar(gui), Coord.z);
	resize(UI.scale(260, 220));
    }

    private Widget makeToolbar(GameUI gui) {
	Widget bar = new Widget();
	int x = 0;
	CheckBox claims = bar.add(new CheckBox("P"), x, 0);
	claims.tooltip = "Display personal claims";
	claims.changed(a -> gui.setMapOverlay("cplot", a));
	x += claims.sz.x + UI.scale(5);
	CheckBox village = bar.add(new CheckBox("V"), x, 0);
	village.tooltip = "Display village claims";
	village.changed(a -> gui.setMapOverlay("vlg", a));
	x += village.sz.x + UI.scale(5);
	CheckBox province = bar.add(new CheckBox("R"), x, 0);
	province.tooltip = "Display provinces";
	province.changed(a -> gui.setMapOverlay("prov", a));
	x += province.sz.x + UI.scale(8);
	Button mapbtn = bar.add(new Button(UI.scale(55), "Map", () -> {
	    gui.toggleMap();
	    if(gui.mapfile != null)
		Utils.setprefb("wndvis-map", gui.mapfile.visible());
	}), x, 0);
	x += mapbtn.sz.x + UI.scale(5);
	bar.add(new Button(UI.scale(65), "Icons", gui::toggleIconSettings), x, 0);
	bar.pack();
	return bar;
    }

    private void layout(Coord sz) {
	if((map == null) || (toolbar == null))
	    return;
	toolbar.move(Coord.z);
	toolbar.resize(new Coord(sz.x, toolbar.sz.y));
	map.move(new Coord(0, toolbar.sz.y));
	map.resize(new Coord(sz.x, Math.max(UI.scale(80), sz.y - toolbar.sz.y)));
    }

    @Override
    protected void added() {
	super.added();
	layout(csz());
    }

    @Override
    public void resize(Coord sz) {
	super.resize(sz);
	layout(sz);
    }

    @Override
    public void close() {
	Utils.setprefb("wndvis-minimap", false);
	hide();
    }
}
