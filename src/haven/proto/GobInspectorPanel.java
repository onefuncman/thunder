package haven.proto;

import haven.*;
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
		/* pick-from-map would require MapView interaction hook - for now just use the text entry */
	    }
	}, x, 0);
	sb = adda(new Scrollbar(sz.y - UI.scale(25), 0, 0), sz.x - UI.scale(1), UI.scale(25), 1, 0);
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
	    if(hp != null) lines.add("  Health: " + hp);
	    if(gob.drawable != null) lines.add("  Drawable: " + gob.drawable.getClass().getSimpleName());
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
	    if(ui.sess.protoBus != null && ui.sess.protoBus.capturing) {
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
	return (sz.y - UI.scale(25)) / LINEH;
    }

    @Override
    public void draw(GOut g) {
	g.chcolor(new Color(15, 15, 15, 220));
	g.frect(new Coord(0, UI.scale(25)), new Coord(sz.x, sz.y - UI.scale(25)));
	g.chcolor();

	int y = UI.scale(27);
	int vis = visibleLines();
	for(int i = 0; i < vis && (i + sb.val) < lines.size(); i++) {
	    String line = lines.get(i + sb.val);
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
    public boolean mousewheel(MouseWheelEvent ev) {
	sb.ch(ev.a);
	return true;
    }

    @Override
    public void resize(Coord sz) {
	super.resize(sz);
	sb.resize(sz.y - UI.scale(25));
	sb.c = new Coord(sz.x - sb.sz.x, UI.scale(25));
    }
}
