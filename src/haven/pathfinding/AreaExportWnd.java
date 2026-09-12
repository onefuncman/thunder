package haven.pathfinding;

import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.TextEntry;
import haven.UI;
import haven.GameUI.Hidewnd;
import haven.GameUI.MsgType;
import me.ender.CustomCursors;

/**
 * A stripped-down window for creating and exporting areas using
 * {@link OrganizerAreaSelector}.
 *
 * "Create area" starts a 2-click area marking session on the map.
 * "Export area" serialises the completed selection as JSON via
 * {@link AreaExport} to the configured export path.
 */
public class AreaExportWnd extends Hidewnd {
    private static final int WIDTH = UI.scale(300);
    private final TextEntry name;
    private final Label status;
    private final OrganizerAreaSelector area = new OrganizerAreaSelector();

    public AreaExportWnd() {
        super(Coord.z, "Area Export");

        this.name = new TextEntry(WIDTH - UI.scale(80), "my-area");
        add(this.name, Coord.z);
        add(new Button(UI.scale(70), "Save name") {
            public void click() {
                // just acknowledges; the name is read on export
                AreaExportWnd.this.msg("Name set: " + AreaExportWnd.this.name.text(), MsgType.INFO);
            }
        }, this.name.pos("ur").adds(4, 0));

        int y = this.name.sz.y + UI.scale(8);

        add(new Button(UI.scale(120), "Create area") {
            public void click() {
                AreaExportWnd.this.createArea();
            }
        }, 0, y);

        add(new Button(UI.scale(120), "Export area") {
            public void click() {
                AreaExportWnd.this.exportArea();
            }
        }, UI.scale(128), y);

        y += UI.scale(30);
        this.status = add(new Label("Click 'Create area' to start", 0), 0, y);

        this.pack();
        this.hide();
    }

    public void show() {
        super.show();
        this.raise();
    }

    private GameUI gui() {
        return this.ui == null ? null : this.ui.gui;
    }

    private void msg(String t, MsgType type) {
        GameUI gui = gui();
        if (gui != null) {
            gui.msg(t, type);
        }
        this.status.settext(t);
    }

    private void createArea() {
        GameUI gui = gui();
        if (gui == null || gui.map == null) {
            return;
        }
        this.area.cancel();
        CustomCursors.startMarkingArea(gui.map, mc -> {
            int before = this.area.vertexCount();
            OrganizerAreaSelector.Selection sel = this.area.click(mc, gui.map.glob.map);
            if (sel != null) {
                this.msg("Area " + sel + " — ready to export", MsgType.INFO);
            } else if (this.area.vertexCount() == before) {
                this.msg("Grid not loaded — move closer and click again", MsgType.ERROR);
            } else {
                this.msg("Corner " + this.area.vertexCount() + " of "
                    + OrganizerAreaSelector.CLICK_COUNT + " set — walk to the opposite corner",
                    MsgType.INFO);
            }
        }, () -> {
            this.area.cancel();
            this.msg("Area selection cancelled", MsgType.INFO);
        }, OrganizerAreaSelector.CLICK_COUNT);
        this.msg("Click two opposite corners (walk between them) — right-click cancels",
            MsgType.INFO);
    }

    private void exportArea() {
        GameUI gui = gui();
        if (gui == null) {
            return;
        }
        OrganizerAreaSelector.Selection sel = this.area.selection();
        if (sel == null) {
            this.msg("Create an area first", MsgType.ERROR);
            return;
        }
        String name = this.name.text();
        if (name == null || name.trim().isEmpty()) {
            this.msg("Type a name first", MsgType.ERROR);
            return;
        }
        try {
            java.nio.file.Path outFile = AreaExport.exportPath();
            java.nio.file.Files.createDirectories(outFile.getParent());
            com.google.gson.JsonObject json = AreaExport.toJson(sel.gridVertices, name);
            AreaExport.upsertToRegistry(json, outFile);
            this.msg("Exported area \"" + name + "\" to "
                + outFile.toAbsolutePath(), MsgType.INFO);
        } catch (java.io.IOException e) {
            this.msg("Export failed: " + e.getMessage(), MsgType.ERROR);
        }
    }
}