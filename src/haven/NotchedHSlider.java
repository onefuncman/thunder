package haven;

/* HSlider with marked values: notches are drawn on the track and the
 * knob snaps onto one when dragged within a few pixels of it. */
public class NotchedHSlider extends HSlider {
    private final int[] notches;
    private final int snappx = UI.scale(6);

    public NotchedHSlider(int w, int min, int max, int val, int... notches) {
	super(w, min, max, val);
	this.notches = notches;
    }

    /* Pixel of a value's knob center; inverse of update()'s mapping. */
    private int valpx(int v) {
	return(((sz.x - sflarp.sz().x) * (v - min)) / (max - min) + (sflarp.sz().x / 2));
    }

    @Override
    public void draw(GOut g) {
	super.draw(g);
	g.chcolor(255, 227, 168, 160);
	for(int nv : notches)
	    g.frect(Coord.of(valpx(nv) - UI.scale(1), 0), Coord.of(UI.scale(2), sz.y));
	g.chcolor();
    }

    @Override
    protected void update(Coord c) {
	for(int nv : notches) {
	    if(Math.abs(c.x - valpx(nv)) <= snappx) {
		c = Coord.of(valpx(nv), c.y);
		break;
	    }
	}
	super.update(c);
    }
}
