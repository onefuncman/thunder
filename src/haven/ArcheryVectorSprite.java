package haven;

import haven.render.*;
import haven.resutil.WaterTile;

import java.awt.*;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/* Direction cone for ArcheryIndicator: a translucent wedge from the archer to
 * their weapon's reach, opening 20 degrees to each side of their facing.
 * Ported from Hurricane's ArcheryVectorSprite, but rebuilt as a world-aligned
 * terrain-following mesh: the wedge is regenerated whenever the archer moves
 * or turns, so slopes are followed correctly under rotation (Hurricane's
 * version sampled terrain in the unrotated frame). */
public class ArcheryVectorSprite extends Sprite {
    private static final Color col = new Color(192, 0, 0, 96);
    private static final double HALF_ARC = Math.toRadians(20);
    private static final float R0 = 3f, H = 0.6f;
    private static final Pipe.Op state = Pipe.Op.compose(MapMesh.postmap,
	new States.Facecull(States.Facecull.Mode.NONE), Location.nullrot, Clickable.No, new BaseColor(col));

    public final int range;
    private final Gob gob;
    private final int rows, cols;
    private final VertexBuf vbuf;
    private final FastMesh mesh;
    private Coord2d lc = null;
    private double la = 0;

    public ArcheryVectorSprite(Gob gob, int range) {
	super(gob, null);
	this.gob = gob;
	this.range = range;
	/* Radial rings about every half tile, angular columns every 2 degrees. */
	this.rows = Math.max(8, (int) Math.ceil((range - R0) / (MCache.tilesz2.x / 2f)));
	this.cols = 20;
	int verts = (rows + 1) * (cols + 1);
	FloatBuffer pos = Utils.mkfbuf(verts * 3);
	ShortBuffer ind = Utils.mksbuf(rows * cols * 6);
	for (int k = 0; k < rows; k++) {
	    for (int j = 0; j < cols; j++) {
		short a = (short) (k * (cols + 1) + j);
		short b = (short) ((k + 1) * (cols + 1) + j);
		short c = (short) (k * (cols + 1) + j + 1);
		short d = (short) ((k + 1) * (cols + 1) + j + 1);
		ind.put(a).put(b).put(c);
		ind.put(b).put(d).put(c);
	    }
	}
	this.vbuf = new VertexBuf(new VertexBuf.VertexData(pos), new VertexBuf.NormalData(pos));
	this.mesh = new FastMesh(vbuf, ind);
    }

    @Override
    public void added(RenderTree.Slot slot) {
	slot.add(mesh, state);
    }

    @Override
    public void dispose() {
	mesh.dispose();
	super.dispose();
    }

    @Override
    public void gtick(Render g) {
	Coord2d cc = gob.rc;
	double a = gob.a;
	if((lc != null) && lc.equals(cc) && (la == a)) {return;}
	if(update(g, cc, a)) {
	    lc = cc;
	    la = a;
	}
    }

    private boolean update(Render g, Coord2d c, double a) {
	MCache map = gob.glob.map;
	FloatBuffer points = ((VertexBuf.VertexData) vbuf.bufs[0]).data;
	try {
	    /* Same height handling as MeshUtils.HeightFastMesh. */
	    float extra = H;
	    DrawOffset dro = gob.getattr(DrawOffset.class);
	    if(dro != null) {
		extra -= dro.off.z;
	    } else {
		Tiler t = map.tiler(map.gettile(c.floor(MCache.tilesz)));
		if(t instanceof WaterTile)
		    extra += (float) (map.getzp(c).z - gob.getrc().z);
	    }
	    float bz = (float) map.getcz(c.x, c.y);
	    int i = 0;
	    for (int k = 0; k <= rows; k++) {
		float r = R0 + (range - R0) * k / (float) rows;
		for (int j = 0; j <= cols; j++) {
		    /* Gobs render rotated by -a about z, so the facing
		     * direction in mesh coordinates is angle -a; mesh y maps
		     * to -y in map coordinates when sampling heights. */
		    double ang = -a - HALF_ARC + (2 * HALF_ARC * j / cols);
		    float px = (float) (r * Math.cos(ang));
		    float py = (float) (r * Math.sin(ang));
		    float z = (float) map.getcz(c.x + px, c.y - py) - bz;
		    points.put(i * 3, px).put(i * 3 + 1, py).put(i * 3 + 2, z + extra);
		    i++;
		}
	    }
	} catch (Loading l) {
	    return false;
	}
	vbuf.update(g);
	return true;
    }

    @Override
    public String toString() {
	return "ArcheryVectorSprite(" + range + ")";
    }
}
