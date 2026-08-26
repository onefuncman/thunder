package haven;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Objects;

import static haven.PUtils.*;

public class DecoX extends Window.DefaultDeco {
    private final CFG.Observer<Theme> updateDecorator = this::updateDecorator;
    protected final Collection<Widget> twdgs = new LinkedList<>();
    private DecoTheme theme;
    
    public DecoX(boolean large) {
	super(large);
	dragsize(true);
    }
    
    @Override
    protected void added() {
	super.added();
	initTheme();
    }
    
    @Override
    public void destroy() {
	CFG.THEME.unobserve(updateDecorator);
	super.destroy();
    }
    
    private Window wnd() {
	return (Window) parent;
    }
    
    private void initTheme() {
	setTheme(CFG.THEME.get().deco);
	CFG.THEME.observe(updateDecorator);
    }
    
    private void updateDecorator(CFG<Theme> theme) {
	setTheme(theme.get().deco);
    }
    
    private void setTheme(DecoThemeType type) {
	this.theme = DecoTheme.fromType(type);
	Window wnd = wnd();
	if(theme != null) {
	    theme.apply(wnd, this);
	} else {
	    wnd.resize(wnd.contentsz());
	}
    }
    
    public void addtwdg(Widget wdg) {
	twdgs.add(add(wdg));
	placetwdgs();
    }
    
    public void remtwdg(Widget wdg) {
	twdgs.remove(wdg);
	placetwdgs();
    }
    
    protected void placetwdgs() {
	int x = cbtn.c.x;
	int y = cbtn.c.y + cbtn.sz.y / 2;
	for (Widget ch : twdgs) {
	    if(ch.visible) {
		x -= ch.sz.x + UI.scale(3);
		ch.c = new Coord(x, y - ch.sz.y / 2);
	    }
	}
    }
    
    @Override
    public void iresize(Coord isz) {
	if(theme == null) {
	    super.iresize(isz);
	} else {
	    theme.iresize(isz, this);
	}
	placetwdgs();
    }
    
    @Override
    protected void drawbg(GOut g) {
	if(theme == null) {
	    super.drawbg(g);
	} else {
	    theme.drawbg(g, this);
	}
    }
    
    @Override
    protected void drawframe(GOut g) {
	if(theme == null) {
	    super.drawframe(g);
	} else {
	    theme.drawframe(g, this);
	}
	Window wnd = (Window) parent;
	wnd.CheckForDinnerTable();
    }

    @Override
    public void draw(GOut g) {
	Window wnd = wnd();
	if(wnd.frameHidden() && !wnd.minimized()) {
	    wnd.cdraw(g.reclip(aa.ul, aa.sz()));
	    return;
	}
	super.draw(g);
    }
    
    @Override
    public boolean checkhit(Coord c) {
	if(theme == null) {
	    return super.checkhit(c);
	}
	
	return theme.checkhit(c, this);
    }
    
    @Override
    protected boolean hitSizer(Coord c) {
	if(theme == null) {
	    return super.hitSizer(c);
	}
	
	return theme.hitSizer(c, this);
    }
    
    public void siresize(Coord isz) {
	super.iresize(isz);
    }
    
    public void sdrawbg(GOut g) {
	super.drawbg(g);
    }
    
    public void sdrawframe(GOut g) {
	super.drawframe(g);
    }
    
    public boolean scheckhit(Coord c) {
	return super.checkhit(c);
    }
    
    public boolean shitSizer(Coord c) {
	return super.hitSizer(c);
    }
    
    public enum DecoThemeType {
	Big, Small, Ard
    }
    
    public interface DecoTheme {
	DecoTheme BIG = new Pretty();
	DecoTheme SMALL = new Slim();
	DecoTheme ARD = new Ard();
	
	static DecoTheme fromType(DecoThemeType type) {
	    switch (type) {
		case Big:
		    return BIG;
		case Small:
		    return SMALL;
		case Ard:
		    return ARD;
		default:
		    throw new IllegalArgumentException(String.format("Unknown theme type: '%s'", type));
	    }
	}
	
	default void apply(Window wnd, DecoX decoX) {
	    wnd.resize2(wnd.contentsz());
	}
	
	void iresize(Coord isz, DecoX decoX);
	
	void drawbg(GOut g, DecoX decoX);
	
	void drawframe(GOut g, DecoX decoX);
	boolean checkhit(Coord c, DecoX decoX);
	
	boolean hitSizer(Coord c, DecoX decoX);
    }
    
    private static class Slim implements DecoTheme {
	private static final Tex cl = Resource.loadtex("gfx/hud/wnd/cleft");
	private static final TexI cm = new TexI(Resource.loadsimg("gfx/hud/wnd/cmain"));
	private static final Tex cr = Resource.loadtex("gfx/hud/wnd/cright");
	private static final int capo = UI.scale(2), capio = UI.scale(1);
	private static final Coord mrgn = UI.scale(1, 1);
	private static final double cay = 0.5;
	public static final Text.Forge cf = new PUtils.BlurFurn(new PUtils.TexFurn(new Text.Foundry(Text.serif, 14).aa(true), WindowX.ctex),
	    UI.rscale(0.75), UI.rscale(1.0), new Color(96, 96, 0));
	public static final Text.Forge ncf = new PUtils.BlurFurn(new PUtils.TexFurn(new Text.Foundry(Text.serif, 14).aa(true), WindowX.ctex),
	    UI.rscale(0.75), UI.rscale(1.0), Color.BLACK);

	public static final BufferedImage[] cbtni = new BufferedImage[]{
	    Resource.loadsimg("gfx/hud/btn-close"),
	    Resource.loadsimg("gfx/hud/btn-close-d"),
	    Resource.loadsimg("gfx/hud/btn-close-h")
	};

	private static final IBox wbox = new IBox.Scaled("gfx/hud/wnd", "tl", "tr", "bl", "br", "extvl", "extvr", "extht", "exthb");
	private boolean cfocus;
	
	@Override
	public void apply(Window wnd, DecoX decoX) {
	    decoX.cbtn.recthit = false;
	    decoX.cbtn.images(cbtni[0], cbtni[1], cbtni[2]);
	    DecoTheme.super.apply(wnd, decoX);
	}
	
	@Override
	public void iresize(Coord isz, DecoX decoX) {
	    Coord asz = isz;
	    Coord csz = asz.add(mrgn.mul(2));
	    
	    decoX.cptl = Coord.of(0, wbox.ctloff().y);
	    Coord wsz = csz.add(wbox.bisz()).addy(cm.sz().y / 2).add(decoX.cptl);
	    decoX.resize(wsz);
	    
	    decoX.ca = Area.sized(decoX.cptl.add(wbox.btloff()).add(0, cm.sz().y / 2), wsz);
	    decoX.aa = Area.sized(decoX.ca.ul.add(mrgn), asz);
	    
	    decoX.cbtn.c = Coord.of(wsz.x, decoX.aa.ul.y).sub(decoX.cbtn.sz);
	}
	
	@Override
	public void drawbg(GOut g, DecoX decoX) {
	    g.chcolor(new Color(55, 64, 32, 200));
	    g.frect(decoX.cptl.add(mrgn.mul(2)), decoX.sz.sub(mrgn.mul(2)));
	    g.chcolor();
	}
	
	@Override
	public void drawframe(GOut g, DecoX decoX) {
	    Window wnd = decoX.wnd();
	    Text cap = decoX.cap;
	    if((cap == null) || (!Objects.equals(cap.text, wnd.cap)) || (cfocus != wnd.hasfocus)) {
		if(cap != null) cap.dispose();
		cap = (wnd.cap == null) ? null : ((cfocus = wnd.hasfocus) ? cf : ncf).render(wnd.cap);
		decoX.cap = cap;
		decoX.cmw = (cap == null) ? 0 : cap.sz().x;
		decoX.cpsz = Coord.of(cl.sz().x + decoX.cmw + cr.sz().x, cm.sz.y);
		decoX.cmw = decoX.cmw - (cl.sz().x) - UI.scale(5);
	    }
	    
	    wbox.draw(g, decoX.cptl, decoX.sz.sub(decoX.cptl));
	    
	    if(decoX.dragsize) {
		Coord sub = decoX.sz.sub(Window.sizer_sz);
		g.image(Window.sizer, sub);
	    }
	    
	    if(cap != null) {
		int w = cap.sz().x;
		int y = decoX.cptl.y + capo;
		g.aimage(cl, new Coord(decoX.cptl.x, y), 0, cay);
		g.aimage(cm, new Coord(decoX.cptl.x + cl.sz().x, y), 0, cay, new Coord(w, cm.sz().y));
		g.aimage(cr, new Coord(decoX.cptl.x + w + cl.sz().x, y), 0, cay);
		g.aimage(cap.tex(), new Coord(decoX.cptl.x + cl.sz().x, y - capo - capio), 0, cay);
	    }
	}
	
	@Override
	public boolean checkhit(Coord c, DecoX decoX) {
	    return c.isect(decoX.cptl, decoX.sz)
		|| c.isect(decoX.cptl.addy(-cm.sz.y), decoX.cpsz);
	}
	
	@Override
	public boolean hitSizer(Coord c, DecoX decoX) {
	    return c.x > decoX.sz.x - Window.sizer_sz.x
		&& c.y > decoX.sz.y - Window.sizer_sz.y;
//	    return c.isect(decoX.sz.sub(Window.sizer_sz), Window.sizer_sz);
	}
    }

    private static class Ard implements DecoTheme {
	private static final int border = UI.scale(1);
	private static final int pad = UI.scale(6);
	private static final int titleh = UI.scale(22);
	private static final Color body = new Color(8, 9, 10, 238);
	private static final Color panel = new Color(17, 19, 21, 245);
	private static final Text.Forge cf = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), UI.scale(13),
	    new Color(225, 227, 228)).aa(true);
	private static final Text.Forge ncf = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), UI.scale(13),
	    new Color(135, 138, 140)).aa(true);
	private static final BufferedImage[] cleanClose = {
	    ardCtrlImg(3, ARD_IDLE_BG, ARD_IDLE_FG),
	    ardCtrlImg(3, ARD_HOVER_BG, ARD_HOVER_FG),
	    ardCtrlImg(3, ARD_DOWN_BG, ARD_DOWN_FG)
	};
	private boolean cfocus;

	@Override
	public void apply(Window wnd, DecoX decoX) {
	    decoX.cbtn.recthit = true;
	    decoX.cbtn.images(cleanClose[0], cleanClose[2], cleanClose[1]);
	    DecoTheme.super.apply(wnd, decoX);
	}

	@Override
	public void iresize(Coord isz, DecoX decoX) {
	    Coord csz = Coord.of(Math.max(0, isz.x), Math.max(0, isz.y));
	    Coord wsz = Coord.of(csz.x + (pad * 2) + (border * 2),
		csz.y + titleh + (pad * 2) + (border * 2));
	    decoX.resize(wsz);
	    decoX.cptl = Coord.z;
	    decoX.ca = Area.sized(Coord.z, wsz);
	    decoX.aa = Area.sized(Coord.of(border + pad, border + titleh + pad), csz);
	    decoX.cbtn.c = Coord.of(wsz.x - border - decoX.cbtn.sz.x - UI.scale(3),
		border + ((titleh - decoX.cbtn.sz.y) / 2));
	    decoX.cpsz = Coord.of(wsz.x, titleh + border);
	}

	@Override
	public void drawbg(GOut g, DecoX decoX) {
	    g.chcolor(body);
	    g.frect(Coord.z, decoX.sz);
	    g.chcolor(panel);
	    g.frect(decoX.aa.ul.sub(pad, pad), decoX.aa.sz().add(pad * 2, pad * 2));
	    g.chcolor();
	}

	@Override
	public void drawframe(GOut g, DecoX decoX) {
	    Window wnd = decoX.wnd();
	    boolean focused = wnd.hasfocus;
	    Text cap = decoX.cap;
	    if((cap == null) || (!Objects.equals(cap.text, wnd.cap)) || (cfocus != focused)) {
		if(cap != null)
		    cap.dispose();
		cap = (wnd.cap == null) ? null : (focused ? cf : ncf).render(wnd.cap);
		decoX.cap = cap;
		cfocus = focused;
	    }

	    if(cap != null)
		g.aimage(cap.tex(), Coord.of(border + UI.scale(7), border + (titleh / 2)), 0, 0.5);
	    if(decoX.dragsize && !wnd.minimized()) {
		g.chcolor(focused ? new Color(150, 153, 155) : new Color(85, 88, 90));
		int d = UI.scale(3);
		for(int i = 1; i <= 3; i++)
		    g.frect(Coord.of(decoX.sz.x - border - (i * d), decoX.sz.y - border - d),
			Coord.of(d - 1, d - 1));
	    }
	    g.chcolor();
	}

	@Override
	public boolean checkhit(Coord c, DecoX decoX) {
	    return c.isect(Coord.z, decoX.sz);
	}

	@Override
	public boolean hitSizer(Coord c, DecoX decoX) {
	    return c.x >= decoX.sz.x - UI.scale(18) && c.y >= decoX.sz.y - UI.scale(18);
	}
    }
    
    private static class Pretty implements DecoTheme {
	@Override
	public void apply(Window wnd, DecoX decoX) {
	    decoX.cbtn.recthit = false;
	    decoX.cbtn.images(Window.cbtni[0], Window.cbtni[1], Window.cbtni[2]);
	    DecoTheme.super.apply(wnd, decoX);
	}
	
	@Override
	public void iresize(Coord isz, DecoX decoX) {
	    decoX.siresize(isz);
	}
	
	@Override
	public void drawbg(GOut g, DecoX decoX) {
	    decoX.sdrawbg(g);
	}
	
	@Override
	public void drawframe(GOut g, DecoX decoX) {
	    decoX.sdrawframe(g);
	}
	
	@Override
	public boolean checkhit(Coord c, DecoX decoX) {
	    return decoX.scheckhit(c);
	}
	
	@Override
	public boolean hitSizer(Coord c, DecoX decoX) {
	    return decoX.shitSizer(c);
	}
    }

    private static final Color ARD_IDLE_BG = new Color(28, 30, 31, 220);
    private static final Color ARD_HOVER_BG = new Color(70, 72, 73, 230);
    private static final Color ARD_DOWN_BG = new Color(72, 76, 78, 230);
    private static final Color ARD_IDLE_FG = new Color(164, 165, 160);
    private static final Color ARD_HOVER_FG = new Color(225, 227, 228);
    private static final Color ARD_DOWN_FG = new Color(205, 208, 210);
    private static final Tex[][] ardCtrl = new Tex[3][3];

    static Tex ardCtrl(int icon, boolean hover, boolean active) {
	int st = active ? 2 : hover ? 1 : 0;
	Tex t = ardCtrl[icon][st];
	if(t == null) {
	    Color bg = (st == 2) ? ARD_DOWN_BG : (st == 1) ? ARD_HOVER_BG : ARD_IDLE_BG;
	    Color fg = (st == 2) ? ARD_DOWN_FG : (st == 1) ? ARD_HOVER_FG : ARD_IDLE_FG;
	    ardCtrl[icon][st] = t = new TexI(ardCtrlImg(icon, bg, fg));
	}
	return(t);
    }

    private static BufferedImage ardCtrlImg(int glyph, Color bg, Color fg) {
	int s = UI.scale(16);
	BufferedImage img = TexI.mkbuf(Coord.of(s, s));
	Graphics2D g = img.createGraphics();
	g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
	g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
	g.setColor(bg);
	g.fillRect(0, 0, s, s);
	g.setColor(fg);
	float sw = Math.max(1.25f, UI.scale(1.25f));
	g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
	int m = UI.scale(4);
	if(glyph == 0) {
	    int bodyX = UI.scale(4);
	    int bodyY = UI.scale(7);
	    int bodyW = s - (bodyX * 2);
	    int bodyH = s - bodyY - UI.scale(3);
	    g.draw(new java.awt.geom.RoundRectangle2D.Float(bodyX, bodyY, bodyW, bodyH, UI.scale(2), UI.scale(2)));
	    int shW = UI.scale(6);
	    int shX = (s - shW) / 2;
	    g.draw(new java.awt.geom.Arc2D.Float(shX, m, shW, UI.scale(8), 0, 180, java.awt.geom.Arc2D.OPEN));
	} else if(glyph == 1) {
	    int y = s / 2;
	    g.drawLine(m, y, s - m - 1, y);
	} else if(glyph == 2) {
	    java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
	    p.moveTo(m, s * 0.38f);
	    p.lineTo(s / 2f, s * 0.62f);
	    p.lineTo(s - m, s * 0.38f);
	    g.draw(p);
	} else {
	    g.drawLine(m, m, s - m - 1, s - m - 1);
	    g.drawLine(s - m - 1, m, m, s - m - 1);
	}
	g.dispose();
	return(img);
    }
}
