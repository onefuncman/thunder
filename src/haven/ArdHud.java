package haven;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * ArdClient HUD pack loaded from {@code custom/hud/ardclient}.
 * Ard's git ships that look as {@code custom/hud/sloth} (thin dark chrome).
 * The ornate {@code default} pack is vanilla Haven, not the Ard theme.
 * Sloth textures are mid-grey; Ard multiplies them by a window color.
 */
public class ArdHud {
    public static final String PREFIX = "custom/hud/ardclient/";
    public static final Coord FALLBACK_TLC = new Coord(2, 24);
    public static final Coord FALLBACK_BRC = new Coord(2, 2);
    public static final Coord FALLBACK_CAPC = new Coord(5, 0);
    public static final Coord FALLBACK_BTNC = new Coord(5, 3);
    /** Body fill: near-black with the world showing through. */
    public static final Color FILL = new Color(8, 9, 10, 200);
    /** Frame / chrome multiply (Ard {@code WNDCOL}). */
    public static final Color WNDCOL = new Color(8, 9, 10, 230);
    public static final Color BTNCOL = new Color(8, 9, 10, 230);
    public static final Color TXBCOL = new Color(8, 9, 10, 230);

    public static void tint(BufferedImage img, Color c) {
	WritableRaster ras = img.getRaster();
	int bands = ras.getNumBands();
	if(bands < 3)
	    return;
	float sr = c.getRed() / 255f, sg = c.getGreen() / 255f, sb = c.getBlue() / 255f;
	float sa = (bands > 3) ? (c.getAlpha() / 255f) : 1f;
	int[] px = new int[bands];
	for(int y = 0; y < img.getHeight(); y++) {
	    for(int x = 0; x < img.getWidth(); x++) {
		ras.getPixel(x, y, px);
		if((bands > 3) && (px[3] == 0))
		    continue;
		px[0] = Math.min(255, Math.round(px[0] * sr));
		px[1] = Math.min(255, Math.round(px[1] * sg));
		px[2] = Math.min(255, Math.round(px[2] * sb));
		if(bands > 3)
		    px[3] = Math.min(255, Math.round(px[3] * sa));
		ras.setPixel(x, y, px);
	    }
	}
    }

    public static class WindowConfig extends Resource.Layer {
	public final Coord tlc, brc, capc, btnc;

	public WindowConfig(Resource res, Message buf) {
	    res.super();
	    tlc = Resource.cdec(buf);
	    brc = Resource.cdec(buf);
	    capc = Resource.cdec(buf);
	    btnc = Resource.cdec(buf);
	}

	public void init() {}
    }

    static {
	Resource.addltype("windowconfig", WindowConfig.class);
    }

    public static TexI bg, bgl, bgr, cl, cm, cr, bl, br, lm, rm, bm;
    public static Coord tlc, brc, capc, btnc;
    public static BufferedImage[] close;
    public static BufferedImage[] textbtn;
    private static Tex[][] ctrl;
    private static Tex[] textedit;
    private static Tex[][] chkbox;
    private static boolean loaded;

    public static String path(String name) {
	return PREFIX + name;
    }

    public static Resource res(String name) {
	return Resource.local().loadwait(path(name));
    }

    public static Tex tex(String name, int id) {
	return res(name).flayer(Resource.imgc, id).tex();
    }

    public static BufferedImage img(String name, int id) {
	return res(name).flayer(Resource.imgc, id).scaled();
    }

    public static synchronized void ensure() {
	if(loaded)
	    return;
	Resource wnd = res("window");
	bg = texi(wnd, 0);
	bgl = texi(wnd, 1);
	bgr = texi(wnd, 2);
	cl = texi(wnd, 3);
	cm = texi(wnd, 4);
	cr = texi(wnd, 5);
	bl = texi(wnd, 6);
	br = texi(wnd, 7);
	lm = texi(wnd, 8);
	rm = texi(wnd, 9);
	bm = texi(wnd, 10);
	WindowConfig cfg = wnd.layer(WindowConfig.class);
	if(cfg != null) {
	    tlc = cfg.tlc;
	    brc = cfg.brc;
	    capc = cfg.capc;
	    btnc = cfg.btnc;
	} else {
	    tlc = FALLBACK_TLC;
	    brc = FALLBACK_BRC;
	    capc = FALLBACK_CAPC;
	    btnc = FALLBACK_BTNC;
	}
	close = new BufferedImage[]{
	    img("buttons/close", 0),
	    img("buttons/close", 1),
	    img("buttons/close", 2)
	};
	loaded = true;
    }

    public static BufferedImage[] textbtn() {
	ensure();
	if(textbtn == null) {
	    textbtn = new BufferedImage[]{
		img("buttons/textbtn", 0),
		img("buttons/textbtn", 1),
		img("buttons/textbtn", 2),
		img("buttons/textbtn", 3),
		img("buttons/textbtn", 4),
		img("buttons/textbtn", 5)
	    };
	}
	return(textbtn);
    }

    public static Tex textedit(int id) {
	ensure();
	if(textedit == null)
	    textedit = new Tex[3];
	if(textedit[id] == null)
	    textedit[id] = tex("textedit", id);
	return(textedit[id]);
    }

    public static Tex chkbox(boolean large, boolean marked) {
	ensure();
	if(chkbox == null)
	    chkbox = new Tex[2][2];
	int kind = large ? 1 : 0;
	int id = marked ? 1 : 0;
	if(chkbox[kind][id] == null)
	    chkbox[kind][id] = tex(large ? "chkbox/large" : "chkbox/small", id);
	return(chkbox[kind][id]);
    }

    public static Tex ctrl(int icon, boolean hover, boolean active) {
	ensure();
	if(ctrl == null)
	    ctrl = new Tex[3][3];
	int st = active ? 1 : hover ? 2 : 0;
	if(ctrl[icon][st] == null) {
	    String name = (icon == 0) ? "buttons/lock" : (icon == 1) ? "buttons/minimize" : "buttons/hide";
	    ctrl[icon][st] = tex(name, st);
	}
	return(ctrl[icon][st]);
    }

    private static TexI texi(Resource res, int id) {
	return((TexI)res.flayer(Resource.imgc, id).tex());
    }
}
