package haven.proto;

import haven.*;
import java.awt.Color;
import java.awt.Font;
import java.util.*;

public class ProtoEventList extends Listbox<ProtoEvent> {
    private static final Text.Foundry fnd = new Text.Foundry(Text.mono, 10);
    private static final Text.Foundry fndb = new Text.Foundry(Text.monobold, 10);
    private static final int ITEMH = fnd.height() + UI.scale(4);
    private static final Color BG_EVEN = new Color(20, 20, 20, 200);
    private static final Color BG_ODD = new Color(30, 30, 30, 200);
    private static final Color DIR_IN = new Color(100, 200, 100);
    private static final Color DIR_OUT = new Color(200, 100, 100);
    private static final Color TS_COLOR = new Color(140, 140, 140);

    private final List<ProtoEvent> events = new ArrayList<>();
    private boolean autoScroll = true;

    public ProtoEventList(int w, int h) {
	super(w, h, ITEMH);
	bgcolor = new Color(15, 15, 15, 220);
    }

    public void addEvent(ProtoEvent evt) {
	events.add(evt);
	if(autoScroll) {
	    int n = events.size();
	    if(n > h)
		sb.val = n - h;
	}
    }

    public void clear() {
	events.clear();
	change(null);
	sb.val = 0;
    }

    public List<ProtoEvent> getEvents() {
	return events;
    }

    @Override
    protected ProtoEvent listitem(int i) {
	return events.get(i);
    }

    @Override
    protected int listitems() {
	return events.size();
    }

    @Override
    protected void drawitem(GOut g, ProtoEvent evt, int idx) {
	g.chcolor((idx % 2 == 0) ? BG_EVEN : BG_ODD);
	g.frect(Coord.z, g.sz());

	int catBarW = UI.scale(4);
	g.chcolor(evt.category.color);
	g.frect(Coord.z, new Coord(catBarW, g.sz().y));

	int x = catBarW + UI.scale(4);
	int cy = g.sz().y / 2;

	String ts = String.format("%6.2f", evt.timestamp % 1000);
	g.chcolor(TS_COLOR);
	g.atext(ts, new Coord(x, cy), 0, 0.5);
	x += fnd.strsize(ts).x + UI.scale(6);

	String dir = (evt.dir == ProtoEvent.Direction.IN) ? "<<" : ">>";
	g.chcolor((evt.dir == ProtoEvent.Direction.IN) ? DIR_IN : DIR_OUT);
	g.atext(dir, new Coord(x, cy), 0, 0.5);
	x += fnd.strsize(dir).x + UI.scale(6);

	g.chcolor(Color.WHITE);
	String tn = evt.typeName;
	Text tnt = fndb.render(tn);
	g.aimage(tnt.tex(), new Coord(x, cy), 0, 0.5);
	x += tnt.img.getWidth() + UI.scale(6);
	tnt.tex().dispose();

	int remainW = g.sz().x - x - UI.scale(4);
	if(remainW > 0) {
	    g.chcolor(new Color(200, 200, 200));
	    String sum = evt.summary;
	    Text st = fnd.render(sum);
	    if(st.img.getWidth() > remainW) {
		String ellipsized = fnd.ellipsize(sum, remainW, "...").text;
		st.tex().dispose();
		st = fnd.render(ellipsized);
	    }
	    g.aimage(st.tex(), new Coord(x, cy), 0, 0.5);
	    st.tex().dispose();
	}
	g.chcolor();
    }

    @Override
    protected Object itemtip(ProtoEvent item) {
	return item.detail;
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
	boolean ret = super.mousewheel(ev);
	int n = events.size();
	autoScroll = (sb.val >= n - h - 1);
	return ret;
    }
}
