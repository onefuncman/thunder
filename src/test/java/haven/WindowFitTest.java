package haven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A window restored from windows.json at a position outside the current
 *  client area must be pulled back on screen instead of opening invisibly. */
public class WindowFitTest {
    private static class ProbeWindow extends Window {
	ProbeWindow() {
	    super(new Coord(240, 264), "Window fit probe", false);
	    skipInitPos = true;
	    skipSavePos = true;
	}
	Coord fit(Coord c) {return(fitToParent(c));}
    }

    /** Run enough ticks for any show/hide animation to complete. */
    private static void settle(Window w) {
	for(int i = 0; i < 50; i++) w.tick(0.05);
    }

    private static ProbeWindow attach(Coord at) {
	Widget parent = new Widget(new Coord(1604, 858));
	parent.setfocusctl(true);
	ProbeWindow w = new ProbeWindow();
	parent.add(w, at);
	return(w);
    }

    @Test
    public void positionPastTheRightEdgeIsPulledBack() {
	ProbeWindow w = attach(new Coord(10, 10));
	Coord c = w.fit(new Coord(1810, 232));
	assertTrue(c.x + Math.min(UI.scale(100), w.sz.x) <= 1604, "window must keep a grab margin inside the parent, got x=" + c.x);
	assertEquals(232, c.y);
    }

    @Test
    public void negativePositionIsPulledBack() {
	ProbeWindow w = attach(new Coord(10, 10));
	Coord c = w.fit(new Coord(-900, -700));
	assertTrue(c.x + w.sz.x >= UI.scale(100), "got x=" + c.x);
	assertTrue(c.y + w.sz.y >= UI.scale(100), "got y=" + c.y);
    }

    @Test
    public void positionInsideTheParentIsUntouched() {
	ProbeWindow w = attach(new Coord(10, 10));
	assertEquals(new Coord(500, 300), w.fit(new Coord(500, 300)));
    }

    @Test
    public void showingAHiddenWindowRefitsIt() {
	ProbeWindow w = attach(new Coord(1810, 232));
	w.hide();
	settle(w);
	assertFalse(w.visible, "hide animation should have completed");
	w.c = new Coord(1810, 232);
	w.show();
	assertTrue(w.c.x + Math.min(UI.scale(100), w.sz.x) <= 1604, "window must be pulled back on screen, got x=" + w.c.x);
    }
}
