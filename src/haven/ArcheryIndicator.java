package haven;

import haven.render.RenderTree;

import java.awt.*;

/* Ported from Hurricane's "Archery Vector and Radius" (b030f8ad3, ranges from
 * 6ce96bd45), rebuilt on Thunder's ColoredRadius/GAttrib machinery: while a
 * gob holds an aiming pose, show a circle of its weapon's reach. */
public class ArcheryIndicator extends GAttrib implements RenderTree.Node {
    private static final Color scol = new Color(192, 0, 0, 96);
    private static final Color ecol = new Color(255, 64, 64, 192);
    public final int range;
    private final ColoredRadius radius;

    public ArcheryIndicator(Gob gob, int range) {
	super(gob);
	this.range = range;
	this.radius = new ColoredRadius(gob, range, scol, ecol);
    }

    @Override
    public void added(RenderTree.Slot slot) {
	super.added(slot);
	slot.add(radius);
    }

    /* Weapon reach in map units, or 0 when the gob is not aiming. Bow reach
     * depends on which bow is equipped, so peek at the composite equipment. */
    public static int aimRange(Gob gob) {
	Drawable d = gob.drawable;
	if(d == null) {return 0;}
	if(d.hasPose("spear-ready") || d.hasPose("sling-aim")) {return 160;}
	if(d.hasPose("drawbow") && (d instanceof Composite)) {
	    Composited comp = ((Composite) d).comp;
	    if(comp == null) {return 0;}
	    try {
		for (Composited.ED item : comp.cequ) {
		    String nm = item.res.res.get().basename();
		    if("huntersbow".equals(nm)) {return 200;}
		    if("rangersbow".equals(nm)) {return 257;}
		}
	    } catch (Loading l) {
		/* Equipment not resolved yet; re-run on the next tick. */
		gob.tagsUpdated();
	    }
	}
	return 0;
    }
}
