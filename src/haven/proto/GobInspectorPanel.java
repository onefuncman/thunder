package haven.proto;

import haven.*;
import haven.Composited;
import me.ender.CustomCursors;
import me.ender.CustomizeVarMat;
import java.awt.Color;
import java.awt.Font;
import java.util.*;

public class GobInspectorPanel extends Widget {
    private static final Text.Foundry fnd = new Text.Foundry(Text.mono, 10);
    private static final Text.Foundry fndb = new Text.Foundry(Text.monobold, 10);
    private long gobId = -1;
    private final TextEntry idEntry;
    private final Button pickBtn;
    private final List<String> lines = new ArrayList<>();
    private final Scrollbar sb;
    private static final int LINEH = fnd.height() + UI.scale(2);
    private static final int HEADER = UI.scale(25);
    private int selected = -1;
    private long lastClickTime = 0;
    private int lastClickIdx = -1;
    private long flashUntil = 0;

    public GobInspectorPanel(Coord sz) {
	super(sz);
	int x = 0;
	add(new Label("Gob ID:"), x, 0);
	x += UI.scale(45);
	idEntry = add(new TextEntry(UI.scale(100), "") {
	    @Override
	    public boolean keydown(KeyDownEvent ev) {
		if(ev.code == java.awt.event.KeyEvent.VK_ENTER) {
		    try {
			setGobId(Long.parseLong(buf.line().trim()));
		    } catch(NumberFormatException e) {}
		    return true;
		}
		return super.keydown(ev);
	    }
	}, x, 0);
	x += UI.scale(108);
	pickBtn = add(new Button(UI.scale(50), "Pick") {
	    public void click() {
		MapView map = ui.gui.map;
		if(map != null) {
		    CustomCursors.startPicking(map, gob -> setGobId(gob.id), true, true);
		}
	    }
	}, x, 0);
	pickBtn.tooltip = "Click a gob on the map to inspect it (right-click to cancel)";
	x += UI.scale(54);
	add(new Button(UI.scale(70), "Copy All") { public void click() { copyAll(); } }, x, 0);
	x += UI.scale(74);
	add(new Button(UI.scale(90), "Copy Selected") { public void click() { copySelected(); } }, x, 0);
	sb = adda(new Scrollbar(sz.y - HEADER, 0, 0), sz.x - UI.scale(1), HEADER, 1, 0);
    }

    private void copyAll() {
	StringBuilder sb2 = new StringBuilder();
	for(String line : lines) sb2.append(line).append('\n');
	ClipboardUtil.copy(sb2.toString());
	flashUntil = System.currentTimeMillis() + 400;
	selected = -2;
    }

    private void copySelected() {
	if(selected >= 0 && selected < lines.size()) {
	    ClipboardUtil.copy(lines.get(selected));
	    flashUntil = System.currentTimeMillis() + 400;
	}
    }

    public void setGobId(long id) {
	this.gobId = id;
	idEntry.settext(String.valueOf(id));
    }

    @Override
    public void tick(double dt) {
	super.tick(dt);
	if(gobId < 0) return;
	lines.clear();
	try {
	    Glob glob = ui.sess.glob;
	    Gob gob = glob.oc.getgob(gobId);
	    if(gob == null) {
		lines.add("Gob " + gobId + " not found");
		return;
	    }
	    lines.add("ID: " + gob.id);
	    lines.add("Position: " + (gob.rc != null ? String.format("(%.1f, %.1f)", gob.rc.x, gob.rc.y) : "null"));
	    lines.add("Angle: " + String.format("%.2f", gob.a));
	    lines.add("Virtual: " + gob.virtual);
	    try {
		Resource res = gob.getres();
		lines.add("Resource: " + (res != null ? res.name : "null"));
	    } catch(Loading l) {
		lines.add("Resource: (loading...)");
	    }
	    if(gob.moving != null)
		lines.add("Moving: " + gob.moving.getClass().getSimpleName());
	    lines.add("--- Attributes ---");
	    try {
		me.ender.gob.KinInfo kin = gob.kin();
		if(kin != null) lines.add("  Kin: " + kin);
	    } catch(Exception e) {}
	    GobHealth hp = gob.getattr(GobHealth.class);
	    if(hp != null) lines.add("  Health: " + String.format("%.0f%% (raw: %.4f)", hp.hp * 100, hp.hp));
	    Speaking speaking = gob.getattr(Speaking.class);
	    if(speaking != null) lines.add("  Speaking: " + (speaking.text != null ? speaking.text.text : ""));
	    DrawOffset doff = gob.getattr(DrawOffset.class);
	    if(doff != null) lines.add("  DrawOffset: " + String.format("(%.1f, %.1f, %.1f)", doff.off.x, doff.off.y, doff.off.z));
	    if(gob.getattr(Lumin.class) != null) lines.add("  Lumin: yes");
	    GobIcon icon = gob.getattr(GobIcon.class);
	    if(icon != null) {
		try {
		    lines.add("  Icon: " + icon.res.get().name);
		} catch(Loading l) {
		    lines.add("  Icon: (loading...)");
		}
	    }
	    String varMats = CustomizeVarMat.formatMaterials(gob);
	    if(varMats != null) {
		for(String line : varMats.split("\n"))
		    lines.add("  " + line);
	    }
	    if(gob.drawable != null) {
		lines.add("  Drawable: " + gob.drawable.getClass().getSimpleName());
		if(gob.drawable instanceof Composite) {
		    Composite comp = (Composite) gob.drawable;
		    String resId = comp.resId();
		    if(resId != null) lines.add("    ResID: " + resId);
		    try {
			lines.add("--- Materials ---");
			for(Composited.MD md : comp.comp.cmod) {
			    try {
				lines.add("  Model: " + md.mod.get().name);
			    } catch(Loading l) {
				lines.add("  Model: (loading...)");
			    }
			    for(ResData tex : md.tex) {
				try {
				    lines.add("    Tex: " + tex.res.get().name);
				} catch(Loading l) {
				    lines.add("    Tex: (loading...)");
				}
			    }
			}
			lines.add("--- Equipment ---");
			for(Composited.ED eq : comp.comp.cequ) {
			    try {
				lines.add("  " + eq.at + ": " + eq.res.res.get().name);
			    } catch(Loading l) {
				lines.add("  " + eq.at + ": (loading...)");
			    }
			}
		    } catch(Exception e) {
			lines.add("  (composite error: " + e.getMessage() + ")");
		    }
		}
	    }
	    lines.add("--- Overlays ---");
	    synchronized(gob.ols) {
		for(Gob.Overlay ol : gob.ols) {
		    String sprInfo = "?";
		    try {
			if(ol.spr != null)
			    sprInfo = ol.spr.getClass().getSimpleName();
		    } catch(Exception e) {
			sprInfo = "(error)";
		    }
		    lines.add("  OL " + ol.id + ": " + sprInfo);
		}
	    }
	    if(ui.sess.protoBus != null && ui.sess.protoBus.isCapturing()) {
		List<ProtoEvent> gobEvents = ui.sess.protoBus.getHistoryForGob(gobId);
		if(!gobEvents.isEmpty()) {
		    lines.add("--- Recent Events (last 20) ---");
		    int start = Math.max(0, gobEvents.size() - 20);
		    for(int i = start; i < gobEvents.size(); i++) {
			ProtoEvent evt = gobEvents.get(i);
			lines.add(String.format("  [%.2f] %s %s", evt.timestamp % 1000, evt.typeName, evt.summary));
		    }
		}
	    }
	} catch(Exception e) {
	    lines.add("Error: " + e.getMessage());
	}
	sb.max(Math.max(0, lines.size() - visibleLines()));
    }

    private int visibleLines() {
	return (sz.y - HEADER) / LINEH;
    }

    @Override
    public void draw(GOut g) {
	g.chcolor(new Color(15, 15, 15, 220));
	g.frect(new Coord(0, HEADER), new Coord(sz.x, sz.y - HEADER));
	g.chcolor();

	boolean flashing = System.currentTimeMillis() < flashUntil;
	int y = HEADER + UI.scale(2);
	int vis = visibleLines();
	for(int i = 0; i < vis && (i + sb.val) < lines.size(); i++) {
	    int idx = i + sb.val;
	    String line = lines.get(idx);
	    if(flashing && selected == -2) {
		g.chcolor(new Color(80, 120, 80, 120));
		g.frect(new Coord(0, y), new Coord(sz.x, LINEH));
	    } else if(idx == selected) {
		g.chcolor(flashing ? new Color(80, 120, 80, 160) : new Color(60, 80, 110, 160));
		g.frect(new Coord(0, y), new Coord(sz.x, LINEH));
	    }
	    if(line.startsWith("---")) {
		g.chcolor(new Color(100, 180, 255));
	    } else if(line.startsWith("  ")) {
		g.chcolor(new Color(200, 200, 200));
	    } else {
		g.chcolor(Color.WHITE);
	    }
	    g.text(line, new Coord(UI.scale(4), y));
	    y += LINEH;
	}
	g.chcolor();
	super.draw(g);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
	if(ev.propagate(this)) return true;
	if(ev.c.y >= HEADER && ev.b == 1) {
	    int idx = (ev.c.y - HEADER - UI.scale(2)) / LINEH + sb.val;
	    if(idx >= 0 && idx < lines.size()) {
		long now = System.currentTimeMillis();
		if(idx == lastClickIdx && (now - lastClickTime) < 400) {
		    selected = idx;
		    copySelected();
		    lastClickTime = 0;
		    lastClickIdx = -1;
		} else {
		    selected = idx;
		    lastClickTime = now;
		    lastClickIdx = idx;
		}
		return true;
	    }
	}
	return false;
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
	sb.ch(ev.a);
	return true;
    }

    @Override
    public void resize(Coord sz) {
	super.resize(sz);
	sb.resize(sz.y - HEADER);
	sb.c = new Coord(sz.x - sb.sz.x, HEADER);
    }
}
