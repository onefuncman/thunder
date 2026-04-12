package haven.proto;

import haven.*;
import java.awt.Color;
import java.nio.file.Path;

public class StateInspector extends GameUI.Hidewnd {
    private static final int W = UI.scale(600);
    private static final int H = UI.scale(450);
    private final Tabs tabs;
    private final Tabs.Tab gobTab, widgetTab, resTab;
    private final GobInspectorPanel gobPanel;
    private final WidgetTreePanel widgetPanel;
    private final ResourceMapPanel resPanel;

    public StateInspector(UI ui) {
	super(new Coord(W, H), "State Inspector");

	int btnY = 0;
	int btnX = 0;
	int tabY = UI.scale(28);
	Coord tabSz = new Coord(W, H - tabY);

	tabs = new Tabs(new Coord(0, tabY), tabSz, this);

	gobTab = tabs.add();
	gobPanel = gobTab.add(new GobInspectorPanel(tabSz), 0, 0);

	widgetTab = tabs.add();
	widgetPanel = widgetTab.add(new WidgetTreePanel(tabSz), 0, 0);

	resTab = tabs.add();
	resPanel = resTab.add(new ResourceMapPanel(tabSz), 0, 0);

	int btnW = UI.scale(80);
	add(new Button(btnW, "Gobs") { public void click() { tabs.showtab(gobTab); } }, btnX, btnY);
	btnX += btnW + UI.scale(4);
	add(new Button(btnW, "Widgets") { public void click() { tabs.showtab(widgetTab); } }, btnX, btnY);
	btnX += btnW + UI.scale(4);
	add(new Button(btnW, "Resources") { public void click() { tabs.showtab(resTab); } }, btnX, btnY);
	btnX += btnW + UI.scale(4);
	add(new Button(UI.scale(100), "Snapshot Scene") { public void click() { doSnapshot(); } }, btnX, btnY);
    }

    private void doSnapshot() {
	GameUI gui = ui.gui;
	if(gui == null) { return; }
	try {
	    Path p = SceneSnapshotter.snapshot(gui);
	    gui.msg("Scene snapshot: " + p.toAbsolutePath(), GameUI.MsgType.INFO);
	} catch (Exception e) {
	    gui.error("Snapshot failed: " + e.getClass().getSimpleName()
		+ (e.getMessage() != null ? ": " + e.getMessage() : ""));
	}
    }

    public void inspectGob(long id) {
	tabs.showtab(gobTab);
	gobPanel.setGobId(id);
    }

    public void showWidgetTree() {
	tabs.showtab(widgetTab);
	widgetPanel.refresh();
    }

    @Override
    public void resize(Coord sz) {
	super.resize(sz);
	if(tabs != null) {
	    int tabY = UI.scale(28);
	    Coord tabSz = new Coord(sz.x, sz.y - tabY);
	    tabs.c = new Coord(0, tabY);
	    tabs.resize(tabSz);
	    gobPanel.resize(tabSz);
	    widgetPanel.resize(tabSz);
	    resPanel.resize(tabSz);
	}
    }
}
