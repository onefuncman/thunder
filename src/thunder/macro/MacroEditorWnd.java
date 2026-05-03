package thunder.macro;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.Coord2d;
import haven.Dropbox;
import haven.GOut;
import haven.GameUI;
import haven.Gob;
import haven.GobHighlight;
import haven.Label;
import haven.Listbox;
import haven.TextEntry;
import haven.UI;
import haven.Widget;
import haven.WindowX;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MacroEditorWnd extends WindowX {
    private static final int LIST_W = 360;
    private static final int LIST_ROWS = 14;
    private static final int ROW_H = UI.scale(20);
    private static final Color ROW_EVEN = new Color(255, 255, 255, 16);
    private static final Color ROW_ODD = new Color(255, 255, 255, 32);

    private static final Map<String, MacroEditorWnd> open = new HashMap<>();

    private Macro macro;
    private final TextEntry nameField;
    private final TextEntry defaultRepeatField;
    private final TextEntry playRepeatField;
    private final StepList stepList;
    private final Widget stepEditor;
    private MacroStep stepEditorFor;

    private MacroEditorWnd(Macro macro) {
	super(Coord.z, "Macro: " + macro.name);
	justclose = true;
	this.macro = macro;

	add(new Label("Name:"), Coord.z);
	nameField = add(new TextEntry(UI.scale(LIST_W - 60), macro.name), UI.scale(60), 0);

	int y = nameField.sz.y + UI.scale(5);
	add(new Label("Default repeat:"), 0, y);
	defaultRepeatField = add(new TextEntry(UI.scale(60), Integer.toString(macro.defaultRepeat)), UI.scale(120), y);
	y += defaultRepeatField.sz.y + UI.scale(8);

	stepList = add(new StepList(UI.scale(LIST_W), LIST_ROWS), 0, y);
	y += stepList.sz.y + UI.scale(5);

	final MacroStep.Type[] addTypes = MacroStep.Type.values();
	final Dropbox<MacroStep.Type> addType = add(new Dropbox<MacroStep.Type>(UI.scale(120), addTypes.length, UI.scale(16)) {
	    protected MacroStep.Type listitem(int i) {return addTypes[i];}
	    protected int listitems() {return addTypes.length;}
	    protected void drawitem(GOut g, MacroStep.Type item, int i) {g.text(item.name(), Coord.z);}
	}, 0, y);
	addType.sel = MacroStep.Type.SLEEP;
	add(new Button(UI.scale(60), "Add") {
	    public void click() {addStep(addType.sel);}
	}, UI.scale(125), y);
	y += UI.scale(22);

	add(new Button(UI.scale(60), "Up") {
	    public void click() {moveSelected(-1);}
	}, 0, y);
	add(new Button(UI.scale(60), "Down") {
	    public void click() {moveSelected(+1);}
	}, UI.scale(65), y);
	add(new Button(UI.scale(60), "Delete") {
	    public void click() {deleteSelected();}
	}, UI.scale(LIST_W - 60), y);
	y += UI.scale(22);

	add(new Button(UI.scale(80), "Play once") {
	    public void click() {playOnce();}
	}, 0, y);
	add(new Label("x"), UI.scale(90), y + UI.scale(3));
	playRepeatField = add(new TextEntry(UI.scale(50), Integer.toString(macro.defaultRepeat)), UI.scale(105), y);
	add(new Button(UI.scale(60), "Play N") {
	    public void click() {playN();}
	}, UI.scale(160), y);
	add(new Button(UI.scale(60), "Save") {
	    public void click() {saveChanges();}
	}, UI.scale(LIST_W - 60), y);
	y += UI.scale(26);

	stepEditor = add(new Widget(new Coord(UI.scale(LIST_W), 0)), 0, y);

	pack();
    }

    public static void open(UI ui, Macro macro) {
	if(ui == null || ui.gui == null || macro == null) return;
	MacroEditorWnd existing = open.get(macro.name);
	if(existing != null && existing.parent != null) {
	    existing.parent.setfocus(existing);
	    return;
	}
	MacroEditorWnd w = new MacroEditorWnd(macro);
	ui.gui.add(w, 200, 100);
	open.put(macro.name, w);
    }

    @Override
    public void destroy() {
	if(macro != null) open.remove(macro.name);
	MacroPicker.cancel();
	clearPersistentHighlight();
	super.destroy();
    }

    @Override
    public void tick(double dt) {
	super.tick(dt);
	rebuildStepEditorIfNeeded();
	highlightSelectedGob();
    }

    private void rebuildStepEditorIfNeeded() {
	MacroStep current = stepList.sel;
	if(current == stepEditorFor) return;
	for(Widget w : new ArrayList<>(stepEditor.children())) w.destroy();
	gobActX = gobActY = null;
	gobActStatus = null;
	stepEditorFor = current;
	if(current == null) {
	    stepEditor.resize(new Coord(UI.scale(LIST_W), 0));
	} else if(current instanceof MacroStep.GobAct) {
	    buildGobActEditor((MacroStep.GobAct) current);
	} else if(current instanceof MacroStep.ItemAct) {
	    buildItemActEditor((MacroStep.ItemAct) current);
	} else if(current instanceof MacroStep.InvDrop) {
	    buildInvDropEditor((MacroStep.InvDrop) current);
	} else if(current instanceof MacroStep.FlowerChoice) {
	    buildFlowerChoiceEditor((MacroStep.FlowerChoice) current);
	} else if(current instanceof MacroStep.Wait) {
	    buildWaitEditor((MacroStep.Wait) current);
	} else if(current instanceof MacroStep.Sleep) {
	    buildSleepEditor((MacroStep.Sleep) current);
	} else if(current instanceof MacroStep.Cmd) {
	    buildCmdEditor((MacroStep.Cmd) current);
	} else {
	    Label l = stepEditor.add(new Label("(no inline editor for this step type yet)"), Coord.z);
	    stepEditor.resize(new Coord(UI.scale(LIST_W), l.sz.y));
	}
	pack();
    }

    private TextEntry gobActX;
    private TextEntry gobActY;
    private Label gobActStatus;

    private void buildGobActEditor(MacroStep.GobAct step) {
	int y = 0;
	stepEditor.add(new Label("Resid:"), 0, y);
	final TextEntry residEntry = stepEditor.add(new TextEntry(UI.scale(LIST_W - 60), step.resid != null ? step.resid : ""), UI.scale(60), y);
	y += residEntry.sz.y + UI.scale(4);

	stepEditor.add(new Label("Pos: x"), 0, y + UI.scale(3));
	gobActX = stepEditor.add(new TextEntry(UI.scale(70), step.lastPos != null ? Double.toString(step.lastPos.x) : "0"), UI.scale(45), y);
	stepEditor.add(new Label("y"), UI.scale(120), y + UI.scale(3));
	gobActY = stepEditor.add(new TextEntry(UI.scale(70), step.lastPos != null ? Double.toString(step.lastPos.y) : "0"), UI.scale(135), y);
	y += gobActX.sz.y + UI.scale(4);

	final CheckBox useHand = stepEditor.add(new CheckBox("Use held item (itemact)"), 0, y);
	useHand.a = step.useHand;
	y += useHand.sz.y + UI.scale(4);

	stepEditor.add(new Button(UI.scale(70), "Apply") {
	    public void click() {step.useHand = useHand.a; applyGobActFields(step, residEntry);}
	}, 0, y);
	stepEditor.add(new Button(UI.scale(120), "Pick from world") {
	    public void click() {startPick(step);}
	}, UI.scale(80), y);
	y += UI.scale(22);

	gobActStatus = stepEditor.add(new Label(""), 0, y);
	y += gobActStatus.sz.y + UI.scale(2);

	stepEditor.resize(new Coord(UI.scale(LIST_W), y));
	updateGobActStatus(step);
    }

    private void applyGobActFields(MacroStep.GobAct step, TextEntry residEntry) {
	String r = residEntry.text() != null ? residEntry.text().trim() : "";
	if(!r.isEmpty()) step.resid = r;
	try {
	    double x = Double.parseDouble(gobActX.text().trim());
	    double y = Double.parseDouble(gobActY.text().trim());
	    step.lastPos = Coord2d.of(x, y);
	} catch(NumberFormatException e) {
	    msg("Invalid x/y.");
	    return;
	}
	updateGobActStatus(step);
    }

    private void updateGobActStatus(MacroStep.GobAct step) {
	if(gobActStatus == null) return;
	Gob g = (ui != null && ui.gui != null) ? step.findTarget(ui.gui) : null;
	if(g != null) {
	    gobActStatus.settext(String.format("Found: %s @ (%.0f, %.0f)", g.resid(), g.rc.x, g.rc.y));
	} else {
	    gobActStatus.settext(String.format("Not found within %.0fu of (%.0f, %.0f)",
		step.radius, step.lastPos != null ? step.lastPos.x : 0, step.lastPos != null ? step.lastPos.y : 0));
	}
    }

    private void startPick(MacroStep.GobAct step) {
	msg("Right-click a gob in the world to set the target.");
	MacroPicker.start(gob -> {
	    if(gob == null) return;
	    String r;
	    try {r = gob.resid();} catch(Exception e) {return;}
	    if(r == null) return;
	    step.resid = r;
	    step.lastPos = gob.rc;
	    if(gobActX != null) gobActX.settext(Double.toString(gob.rc.x));
	    if(gobActY != null) gobActY.settext(Double.toString(gob.rc.y));
	    updateGobActStatus(step);
	    msg("Picked: " + r);
	});
    }

    private void buildItemActEditor(MacroStep.ItemAct step) {
	int y = 0;
	stepEditor.add(new Label("Resid:"), 0, y);
	final TextEntry residEntry = stepEditor.add(new TextEntry(UI.scale(LIST_W - 60), step.resid != null ? step.resid : ""), UI.scale(60), y);
	y += residEntry.sz.y + UI.scale(4);

	stepEditor.add(new Label("SDT (hex):"), 0, y);
	final TextEntry sdtEntry = stepEditor.add(new TextEntry(UI.scale(LIST_W - 130), bytesToHex(step.sdt)), UI.scale(80), y);
	stepEditor.add(new Button(UI.scale(45), "Clr") {
	    public void click() {sdtEntry.settext(""); step.sdt = null;}
	}, UI.scale(LIST_W - 45), y);
	y += sdtEntry.sz.y + UI.scale(4);

	stepEditor.add(new Label("Button:"), 0, y);
	final TextEntry btnEntry = stepEditor.add(new TextEntry(UI.scale(40), Integer.toString(step.button)), UI.scale(60), y);
	stepEditor.add(new Label("(1=lclick, 3=rclick)"), UI.scale(110), y + UI.scale(2));
	y += btnEntry.sz.y + UI.scale(4);

	final CheckBox shift = stepEditor.add(new CheckBox("Shift"), 0, y);
	final CheckBox ctrl  = stepEditor.add(new CheckBox("Ctrl"),  UI.scale(70), y);
	final CheckBox alt   = stepEditor.add(new CheckBox("Alt"),   UI.scale(140), y);
	shift.a = (step.modflags & UI.MOD_SHIFT) != 0;
	ctrl.a  = (step.modflags & UI.MOD_CTRL)  != 0;
	alt.a   = (step.modflags & UI.MOD_META)  != 0;
	y += shift.sz.y + UI.scale(4);

	stepEditor.add(new Button(UI.scale(70), "Apply") {
	    public void click() {
		String r = residEntry.text() != null ? residEntry.text().trim() : "";
		if(!r.isEmpty()) step.resid = r;
		String sh = sdtEntry.text() != null ? sdtEntry.text().trim() : "";
		step.sdt = sh.isEmpty() ? null : hexToBytes(sh);
		try {step.button = Integer.parseInt(btnEntry.text().trim());} catch(NumberFormatException e) {}
		step.modflags = (shift.a ? UI.MOD_SHIFT : 0) | (ctrl.a ? UI.MOD_CTRL : 0) | (alt.a ? UI.MOD_META : 0);
	    }
	}, 0, y);
	y += UI.scale(22);

	stepEditor.resize(new Coord(UI.scale(LIST_W), y));
    }

    private static String bytesToHex(byte[] b) {
	if(b == null || b.length == 0) return "";
	StringBuilder sb = new StringBuilder();
	for(byte v : b) sb.append(String.format("%02X", v));
	return sb.toString();
    }

    private static byte[] hexToBytes(String h) {
	String s = h.replaceAll("\\s+", "");
	if(s.length() % 2 != 0) return null;
	byte[] out = new byte[s.length() / 2];
	for(int i = 0; i < out.length; i++) {
	    try {out[i] = (byte) Integer.parseInt(s.substring(i*2, i*2+2), 16);}
	    catch(NumberFormatException e) {return null;}
	}
	return out;
    }

    private void buildInvDropEditor(MacroStep.InvDrop step) {
	int y = 0;
	stepEditor.add(new Label("Slot: x"), 0, y + UI.scale(3));
	final TextEntry sx = stepEditor.add(new TextEntry(UI.scale(50), Integer.toString(step.slot != null ? step.slot.x : 0)), UI.scale(50), y);
	stepEditor.add(new Label("y"), UI.scale(110), y + UI.scale(3));
	final TextEntry sy = stepEditor.add(new TextEntry(UI.scale(50), Integer.toString(step.slot != null ? step.slot.y : 0)), UI.scale(125), y);
	y += sx.sz.y + UI.scale(4);

	stepEditor.add(new Button(UI.scale(70), "Apply") {
	    public void click() {
		try {step.slot = new haven.Coord(Integer.parseInt(sx.text().trim()), Integer.parseInt(sy.text().trim()));}
		catch(NumberFormatException e) {msg("Invalid slot.");}
	    }
	}, 0, y);
	y += UI.scale(22);

	stepEditor.resize(new Coord(UI.scale(LIST_W), y));
    }

    private void buildFlowerChoiceEditor(MacroStep.FlowerChoice step) {
	int y = 0;
	stepEditor.add(new Label("Option:"), 0, y);
	final TextEntry name = stepEditor.add(new TextEntry(UI.scale(LIST_W - 60), step.optionName != null ? step.optionName : ""), UI.scale(60), y);
	y += name.sz.y + UI.scale(4);

	stepEditor.add(new Label("Timeout (ms):"), 0, y + UI.scale(3));
	final TextEntry to = stepEditor.add(new TextEntry(UI.scale(70), Long.toString(step.timeoutMs)), UI.scale(110), y);
	y += to.sz.y + UI.scale(4);

	stepEditor.add(new Button(UI.scale(70), "Apply") {
	    public void click() {
		String n = name.text() != null ? name.text().trim() : "";
		if(!n.isEmpty()) step.optionName = n;
		try {step.timeoutMs = Math.max(1, Long.parseLong(to.text().trim()));} catch(NumberFormatException e) {}
	    }
	}, 0, y);
	y += UI.scale(22);

	stepEditor.resize(new Coord(UI.scale(LIST_W), y));
    }

    private void buildWaitEditor(MacroStep.Wait step) {
	int y = 0;
	stepEditor.add(new Label("Kind:"), 0, y + UI.scale(3));
	final MacroStep.Wait.Kind[] kinds = MacroStep.Wait.Kind.values();
	final Dropbox<MacroStep.Wait.Kind> kind = stepEditor.add(new Dropbox<MacroStep.Wait.Kind>(UI.scale(160), kinds.length, UI.scale(16)) {
	    protected MacroStep.Wait.Kind listitem(int i) {return kinds[i];}
	    protected int listitems() {return kinds.length;}
	    protected void drawitem(GOut g, MacroStep.Wait.Kind item, int i) {g.text(item.name(), Coord.z);}
	}, UI.scale(60), y);
	kind.sel = step.kind;
	y += UI.scale(22);

	stepEditor.add(new Label("Resid:"), 0, y);
	final TextEntry resid = stepEditor.add(new TextEntry(UI.scale(LIST_W - 60), step.resid != null ? step.resid : ""), UI.scale(60), y);
	y += resid.sz.y + UI.scale(4);

	stepEditor.add(new Label("Window title:"), 0, y);
	final TextEntry wtitle = stepEditor.add(new TextEntry(UI.scale(LIST_W - 100), step.windowTitle != null ? step.windowTitle : ""), UI.scale(100), y);
	y += wtitle.sz.y + UI.scale(4);

	stepEditor.add(new Label("Pattern (regex):"), 0, y);
	final TextEntry pat = stepEditor.add(new TextEntry(UI.scale(LIST_W - 110), step.pattern != null ? step.pattern : ""), UI.scale(110), y);
	y += pat.sz.y + UI.scale(4);

	stepEditor.add(new Label("Buff name:"), 0, y);
	final TextEntry buff = stepEditor.add(new TextEntry(UI.scale(LIST_W - 80), step.buffName != null ? step.buffName : ""), UI.scale(80), y);
	y += buff.sz.y + UI.scale(4);

	stepEditor.add(new Label("Min count:"), 0, y + UI.scale(3));
	final TextEntry mc = stepEditor.add(new TextEntry(UI.scale(60), Integer.toString(step.minCount)), UI.scale(80), y);
	stepEditor.add(new Label("Gob radius:"), UI.scale(150), y + UI.scale(3));
	final TextEntry gr = stepEditor.add(new TextEntry(UI.scale(60), Double.toString(step.gobRadius)), UI.scale(225), y);
	y += mc.sz.y + UI.scale(4);

	stepEditor.add(new Label("Gob near: x"), 0, y + UI.scale(3));
	final TextEntry gnx = stepEditor.add(new TextEntry(UI.scale(70), step.gobNear != null ? Double.toString(step.gobNear.x) : "0"), UI.scale(80), y);
	stepEditor.add(new Label("y"), UI.scale(155), y + UI.scale(3));
	final TextEntry gny = stepEditor.add(new TextEntry(UI.scale(70), step.gobNear != null ? Double.toString(step.gobNear.y) : "0"), UI.scale(170), y);
	y += gnx.sz.y + UI.scale(4);

	stepEditor.add(new Label("Timeout (ms):"), 0, y + UI.scale(3));
	final TextEntry to = stepEditor.add(new TextEntry(UI.scale(70), Long.toString(step.timeoutMs)), UI.scale(110), y);
	stepEditor.add(new Label("Start (ms):"), UI.scale(190), y + UI.scale(3));
	final TextEntry sto = stepEditor.add(new TextEntry(UI.scale(70), Long.toString(step.startTimeoutMs)), UI.scale(265), y);
	y += to.sz.y + UI.scale(4);

	stepEditor.add(new Button(UI.scale(70), "Apply") {
	    public void click() {
		if(kind.sel != null) step.kind = kind.sel;
		step.resid       = nullIfEmpty(resid.text());
		step.windowTitle = nullIfEmpty(wtitle.text());
		step.pattern     = nullIfEmpty(pat.text());
		step.buffName    = nullIfEmpty(buff.text());
		try {step.minCount      = Math.max(0, Integer.parseInt(mc.text().trim()));} catch(NumberFormatException e) {}
		try {step.gobRadius     = Math.max(0, Double.parseDouble(gr.text().trim()));} catch(NumberFormatException e) {}
		try {
		    double gx = Double.parseDouble(gnx.text().trim());
		    double gy = Double.parseDouble(gny.text().trim());
		    step.gobNear = (gx == 0 && gy == 0) ? null : Coord2d.of(gx, gy);
		} catch(NumberFormatException e) {}
		try {step.timeoutMs     = Math.max(1, Long.parseLong(to.text().trim()));} catch(NumberFormatException e) {}
		try {step.startTimeoutMs = Math.max(0, Long.parseLong(sto.text().trim()));} catch(NumberFormatException e) {}
	    }
	}, 0, y);
	y += UI.scale(22);

	stepEditor.resize(new Coord(UI.scale(LIST_W), y));
    }

    private static String nullIfEmpty(String s) {
	if(s == null) return null;
	String t = s.trim();
	return t.isEmpty() ? null : t;
    }

    private void buildCmdEditor(MacroStep.Cmd step) {
	int y = 0;
	stepEditor.add(new Label("Cmd:"), 0, y);
	final TextEntry txt = stepEditor.add(new TextEntry(UI.scale(LIST_W - 60), step.text != null ? step.text : ""), UI.scale(60), y);
	y += txt.sz.y + UI.scale(4);

	stepEditor.add(new Button(UI.scale(70), "Apply") {
	    public void click() {step.text = txt.text();}
	}, 0, y);
	y += UI.scale(22);

	stepEditor.resize(new Coord(UI.scale(LIST_W), y));
    }

    private void buildSleepEditor(MacroStep.Sleep step) {
	int y = 0;
	stepEditor.add(new Label("Sleep ms:"), 0, y + UI.scale(3));
	final TextEntry ms = stepEditor.add(new TextEntry(UI.scale(80), Long.toString(step.ms)), UI.scale(80), y);
	y += ms.sz.y + UI.scale(4);

	stepEditor.add(new Button(UI.scale(70), "Apply") {
	    public void click() {
		try {step.ms = Math.max(0, Long.parseLong(ms.text().trim()));} catch(NumberFormatException e) {}
	    }
	}, 0, y);
	y += UI.scale(22);

	stepEditor.resize(new Coord(UI.scale(LIST_W), y));
    }

    private Gob persistentlyHighlighted = null;

    private void highlightSelectedGob() {
	Gob target = null;
	if((stepEditorFor instanceof MacroStep.GobAct) && ui != null && ui.gui != null) {
	    target = ((MacroStep.GobAct) stepEditorFor).findTarget(ui.gui);
	}
	if(target == persistentlyHighlighted) return;
	clearPersistentHighlight();
	if(target != null) {
	    GobHighlight h = target.getattr(GobHighlight.class);
	    if(h == null) {
		target.highlight();
		h = target.getattr(GobHighlight.class);
	    }
	    if(h != null) h.setPersistent(true);
	    persistentlyHighlighted = target;
	}
    }

    private void clearPersistentHighlight() {
	if(persistentlyHighlighted == null) return;
	if(!persistentlyHighlighted.disposed()) {
	    GobHighlight h = persistentlyHighlighted.getattr(GobHighlight.class);
	    if(h != null) h.setPersistent(false);
	}
	persistentlyHighlighted = null;
    }

    private void moveSelected(int delta) {
	int idx = stepList.selIndex();
	if(idx < 0) return;
	int next = idx + delta;
	if(next < 0 || next >= macro.steps.size()) return;
	MacroStep step = macro.steps.remove(idx);
	macro.steps.add(next, step);
	stepList.sel = step;
    }

    private void deleteSelected() {
	int idx = stepList.selIndex();
	if(idx < 0) return;
	macro.steps.remove(idx);
	stepList.sel = null;
    }

    private void addStep(MacroStep.Type type) {
	if(type == null) return;
	MacroStep step = newStep(type);
	int idx = stepList.selIndex();
	int insertAt = (idx < 0) ? macro.steps.size() : idx + 1;
	macro.steps.add(insertAt, step);
	stepList.sel = step;
    }

    private static MacroStep newStep(MacroStep.Type type) {
	switch(type) {
	case ITEM_ACT:      return new MacroStep.ItemAct("", 1, 0);
	case GOB_ACT:       return new MacroStep.GobAct("", null, 3, 0);
	case INV_DROP:      return new MacroStep.InvDrop(haven.Coord.z);
	case FLOWER_CHOICE: return new MacroStep.FlowerChoice("");
	case WAIT:          return new MacroStep.Wait(MacroStep.Wait.Kind.PROGRESS, 30000);
	case SLEEP:         return new MacroStep.Sleep(1000);
	case CMD:           return new MacroStep.Cmd("");
	default:            throw new IllegalArgumentException("Unknown step type: " + type);
	}
    }

    private void playOnce() {
	saveChanges();
	MacroRunner.run(ui, macro, 1);
    }

    private void playN() {
	saveChanges();
	int n;
	try {n = Math.max(1, Integer.parseInt(playRepeatField.text().trim()));}
	catch(NumberFormatException e) {n = macro.defaultRepeat;}
	MacroRunner.run(ui, macro, n);
    }

    private void saveChanges() {
	String newName = nameField.text() != null ? nameField.text().trim() : "";
	if(newName.isEmpty()) {msg("Name can't be empty."); return;}
	int defRep;
	try {defRep = Math.max(1, Integer.parseInt(defaultRepeatField.text().trim()));}
	catch(NumberFormatException e) {defRep = 1;}

	String oldName = macro.name;
	macro.defaultRepeat = defRep;
	if(!newName.equals(oldName)) {
	    open.remove(oldName);
	    MacroStore.get().remove(oldName);
	    macro.name = newName;
	    open.put(newName, this);
	    chcap("Macro: " + newName);
	}
	MacroStore.get().put(macro);
	if(ui != null && ui.gui != null && ui.gui.macroListWnd != null) {
	    ui.gui.macroListWnd.refresh();
	}
    }

    private void msg(String s) {
	if(ui != null && ui.gui != null) ui.gui.msg(s, GameUI.MsgType.INFO);
    }

    private class StepList extends Listbox<MacroStep> {
	StepList(int w, int rows) {
	    super(w, rows, ROW_H);
	    bgcolor = new Color(0, 0, 0, 84);
	}

	int selIndex() {
	    if(sel == null) return -1;
	    return macro.steps.indexOf(sel);
	}

	@Override
	protected MacroStep listitem(int idx) {return macro.steps.get(idx);}

	@Override
	protected int listitems() {return macro.steps.size();}

	@Override
	protected void drawitem(GOut g, MacroStep step, int idx) {
	    g.chcolor((idx % 2 == 0) ? ROW_EVEN : ROW_ODD);
	    g.frect(Coord.z, g.sz());
	    g.chcolor();
	    g.atext(String.format("%2d. %s", idx + 1, step.label()), new Coord(UI.scale(4), ROW_H / 2), 0, 0.5);
	}
    }
}
