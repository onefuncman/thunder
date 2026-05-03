package thunder.macro;

import haven.Button;
import haven.Coord;
import haven.GOut;
import haven.GameUI;
import haven.Listbox;
import haven.TextEntry;
import haven.UI;
import haven.WindowX;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MacroListWnd extends WindowX {
    private static final int LIST_W = 220;
    private static final int LIST_ROWS = 14;
    private static final int ROW_H = UI.scale(20);
    private static final Color ROW_EVEN = new Color(255, 255, 255, 16);
    private static final Color ROW_ODD = new Color(255, 255, 255, 32);

    private final TextEntry nameField;
    private final MList list;
    private List<Macro> entries = Collections.emptyList();

    public MacroListWnd() {
	super(Coord.z, "Macros");
	justclose = true;

	nameField = add(new TextEntry(UI.scale(LIST_W - 80), ""), Coord.z);
	add(new Button(UI.scale(75), "Record") {
	    public void click() {startRecording();}
	}, UI.scale(LIST_W - 75), 0);

	int y = nameField.sz.y + UI.scale(5);
	add(new Button(UI.scale(75), "Save Rec") {
	    public void click() {saveRecording();}
	}, 0, y);
	add(new Button(UI.scale(75), "Discard") {
	    public void click() {discardRecording();}
	}, UI.scale(80), y);
	add(new Button(UI.scale(60), "Refresh") {
	    public void click() {refresh();}
	}, UI.scale(LIST_W - 60), y);
	y += UI.scale(22);

	list = add(new MList(UI.scale(LIST_W), LIST_ROWS), 0, y);
	y += list.sz.y + UI.scale(5);

	add(new Button(UI.scale(60), "Edit") {
	    public void click() {editSelected();}
	}, 0, y);
	add(new Button(UI.scale(60), "Play") {
	    public void click() {playSelected();}
	}, UI.scale(65), y);
	add(new Button(UI.scale(60), "Delete") {
	    public void click() {deleteSelected();}
	}, UI.scale(LIST_W - 60), y);

	pack();
	refresh();
    }

    public static void toggle(UI ui) {
	if(ui == null || ui.gui == null) return;
	if(ui.gui.macroListWnd == null) {
	    ui.gui.macroListWnd = ui.gui.add(new MacroListWnd(), 100, 100);
	} else {
	    ui.gui.macroListWnd.destroy();
	}
    }

    @Override
    public void destroy() {
	super.destroy();
	if(ui != null && ui.gui != null) ui.gui.macroListWnd = null;
    }

    private void startRecording() {
	if(MacroRecorder.current() != null) {
	    msg("A recording is already in progress.");
	    return;
	}
	MacroRecorder.start(ui);
	msg("Recording. Click 'Save Rec' when done.");
    }

    private void saveRecording() {
	MacroRecorder rec = MacroRecorder.current();
	if(rec == null) {msg("No recording in progress."); return;}
	String name = nameField.text();
	if(name == null || name.trim().isEmpty()) {msg("Enter a name first."); return;}
	Macro m = rec.build(name.trim());
	rec.stop();
	MacroStore.get().put(m);
	nameField.settext("");
	refresh();
	msg("Saved '" + m.name + "' (" + m.steps.size() + " steps).");
    }

    private void discardRecording() {
	MacroRecorder rec = MacroRecorder.current();
	if(rec == null) {msg("No recording in progress."); return;}
	rec.stop();
	msg("Recording discarded.");
    }

    private void editSelected() {
	Macro sel = list.sel;
	if(sel == null) {msg("Select a macro first."); return;}
	MacroEditorWnd.open(ui, sel);
    }

    private void playSelected() {
	Macro sel = list.sel;
	if(sel == null) {msg("Select a macro first."); return;}
	MacroRunner.run(ui, sel, sel.defaultRepeat);
    }

    private void deleteSelected() {
	Macro sel = list.sel;
	if(sel == null) {msg("Select a macro first."); return;}
	MacroStore.get().remove(sel.name);
	refresh();
    }

    public void refresh() {
	entries = new ArrayList<>(MacroStore.get().list());
	list.sel = null;
    }

    private void msg(String s) {
	if(ui != null && ui.gui != null) ui.gui.msg(s, GameUI.MsgType.INFO);
    }

    private class MList extends Listbox<Macro> {
	MList(int w, int rows) {
	    super(w, rows, ROW_H);
	    bgcolor = new Color(0, 0, 0, 84);
	}

	@Override
	protected Macro listitem(int idx) {return entries.get(idx);}

	@Override
	protected int listitems() {return entries.size();}

	@Override
	protected void drawitem(GOut g, Macro m, int idx) {
	    g.chcolor((idx % 2 == 0) ? ROW_EVEN : ROW_ODD);
	    g.frect(Coord.z, g.sz());
	    g.chcolor();
	    g.atext(String.format("%s  (%d, x%d)", m.name, m.steps.size(), m.defaultRepeat),
		    new Coord(UI.scale(4), ROW_H / 2), 0, 0.5);
	}
    }
}
