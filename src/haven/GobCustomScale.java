package haven;

import haven.render.Location;
import haven.render.Pipe;
import me.ender.ResName;

public class GobCustomScale implements Gob.SetupMod {
    
    private Pipe.Op op = null;
    private int scale = 100;
    
    public boolean update(Gob gob) {
	Pipe.Op prev = op;
	String res = gob.resid();
	if(res == null) {
	    op = null;
	} else if(res.equals(ResName.CUPBOARD)) {
	    update(CFG.DISPLAY_SCALE_CUPBOARDS.get());
	} else if(Utils.WALLS_TO_RESIZE.contains(res)) {
	    update(CFG.DISPLAY_SCALE_WALLS.get());
	} else if(res.startsWith("gfx/terobjs/trees/") && !res.endsWith("log")) {
	    update2(CFG.DISPLAY_SCALE_TREES.get());
	} else if(res.startsWith("gfx/terobjs/bushes/")) {
	    update2(CFG.DISPLAY_SCALE_BUSHES.get());
	}
	return op != prev;
    }
    
    private void update(int percent) {
	if(percent != scale) {
	    scale = percent;
	    op = makeScale(percent);
	}
    }
    
    private Pipe.Op makeScale(int percent) {
	if(percent == 100) {
	    return null;
	}
	
	float scale = percent / 100f;
	return new Location(new Matrix4f(
	    1, 0, 0, 0,
	    0, 1, 0, 0,
	    0, 0, scale, 0,
	    0, 0, 0, 1));
    }
    
    private void update2(int percent) {
	if(percent != scale) {
	    scale = percent;
	    op = makeScale2(percent);
	}
    }
    
    private Pipe.Op makeScale2(int percent) {
	if(percent == 100) {
	    return null;
	}
	
	float scale = percent / 100f;
	return new Location(new Matrix4f(
	    scale, 0, 0, 0,
	    0, scale, 0, 0,
	    0, 0, scale, 0,
	    0, 0, 0, 1));
    }
    
    @Override
    public Pipe.Op gobstate() {
	return op;
    }
}
