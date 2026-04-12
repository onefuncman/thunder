package haven.proto;

import haven.*;
import java.awt.Color;
import java.util.function.Supplier;

public class ClickAreaOverlay extends Widget {
    private static final Color OUTLINE = new Color(80, 200, 255, 90);
    private static final Color SELECTED = new Color(255, 200, 0, 220);
    private static final Color SELECTED_FILL = new Color(255, 200, 0, 40);
    private final Supplier<Widget> selectedSupplier;

    public ClickAreaOverlay(Coord sz, Supplier<Widget> selectedSupplier) {
	super(sz);
	this.selectedSupplier = selectedSupplier;
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) { return false; }

    @Override
    public boolean mouseup(MouseUpEvent ev) { return false; }

    @Override
    public boolean checkhit(Coord c) { return false; }

    @Override
    public void presize() {
	if(parent != null) resize(parent.sz);
    }

    @Override
    public void draw(GOut g) {
	if(ui == null || ui.root == null) return;
	Widget sel = selectedSupplier != null ? selectedSupplier.get() : null;
	Coord origin = rootpos();
	drawOutlines(g, ui.root, sel, origin);
    }

    private void drawOutlines(GOut g, Widget w, Widget sel, Coord origin) {
	if(!w.visible) return;
	if(w != this && w.sz.x > 0 && w.sz.y > 0) {
	    Coord wp = w.rootpos().sub(origin);
	    if(w == sel) {
		g.chcolor(SELECTED_FILL);
		g.frect(wp, w.sz);
		g.chcolor(SELECTED);
	    } else {
		g.chcolor(OUTLINE);
	    }
	    g.rect(wp, w.sz);
	}
	for(Widget ch = w.child; ch != null; ch = ch.next)
	    drawOutlines(g, ch, sel, origin);
	g.chcolor();
    }
}
