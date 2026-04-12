package haven;

import haven.render.MixColor;
import haven.render.Pipe;

import java.awt.*;

public class GobHighlight extends GAttrib implements Gob.SetupMod {
    private static final Color COLOR = new Color(64, 255, 64, 255);
    private static final long cycle = 1200;
    private static final long duration = 7200;
    private long start = 0;
    private boolean persistent = false;

    public GobHighlight(Gob g) {
	super(g);
    }

    public void start() {
	start = System.currentTimeMillis();
	persistent = false;
    }

    public void setPersistent(boolean on) {
	this.persistent = on;
	if(on) start = System.currentTimeMillis();
    }

    public boolean isPersistent() {return(persistent);}

    public boolean isActive() {
	return(persistent || (System.currentTimeMillis() - start) <= duration);
    }

    public Pipe.Op gobstate() {
	if(persistent) {
	    Color c = CFG.COLOR_CATTLE_HIGHLIGHT.get();
	    return(new MixColor(c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha()));
	}
	long active = System.currentTimeMillis() - start;
	if(active > duration) {
	    return(null);
	} else {
	    float k = (float) Math.abs(Math.sin(Math.PI * active / cycle));
	    return(new MixColor(COLOR.getRed(), COLOR.getGreen(), COLOR.getBlue(), (int) (255 * k)));
	}
    }
}
