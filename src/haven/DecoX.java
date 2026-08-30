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
	dragsize(false);
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
	private static final Coord dlmrgn = UI.scale(23, 14);
	private static final Coord dsmrgn = UI.scale(3, 3);
	private static final Text.Forge cf = new PUtils.BlurFurn(
	    new PUtils.TexFurn(new Text.Foundry(Text.sans, UI.scale(15)).aa(true), Window.ctex),
	    1, 1, Color.BLACK);

	@Override
	public void apply(Window wnd, DecoX decoX) {
	    ArdHud.ensure();
	    decoX.cbtn.recthit = false;
	    decoX.cbtn.images(ArdHud.close[0], ArdHud.close[1], ArdHud.close[2]);
	    DecoTheme.super.apply(wnd, decoX);
	}

	@Override
	public void iresize(Coord isz, DecoX decoX) {
	    ArdHud.ensure();
	    Coord mrgn = decoX.lg ? dlmrgn : dsmrgn;
	    Coord asz = Coord.of(Math.max(0, isz.x), Math.max(0, isz.y));
	    Coord csz = asz.add(mrgn.mul(2));
	    Coord tlc = UI.scale(ArdHud.tlc);
	    Coord brc = UI.scale(ArdHud.brc);
	    Coord wsz = csz.add(tlc).add(brc);
	    decoX.resize(wsz);
	    decoX.cptl = Coord.z;
	    decoX.ca = Area.sized(tlc, csz);
	    decoX.aa = Area.sized(tlc.add(mrgn), asz);
	    decoX.cpsz = Coord.of(wsz.x, ArdHud.cl.sz().y);
	    decoX.cbtn.c = Coord.of(wsz.x - decoX.cbtn.sz.x - UI.scale(ArdHud.btnc).x,
		UI.scale(ArdHud.btnc).y);
	}

	@Override
	public void drawbg(GOut g, DecoX decoX) {
	    ArdHud.ensure();
	    g.chcolor(ArdHud.FILL);
	    g.frect(decoX.ca.ul, decoX.ca.sz());
	    g.chcolor();
	}

	@Override
	public void drawframe(GOut g, DecoX decoX) {
	    ArdHud.ensure();
	    Window wnd = decoX.wnd();
	    Coord sz = decoX.sz;
	    g.chcolor(ArdHud.WNDCOL);
	    g.image(ArdHud.cl, Coord.z);
	    g.image(ArdHud.bl, new Coord(0, sz.y - ArdHud.bl.sz().y));
	    g.image(ArdHud.br, sz.sub(ArdHud.br.sz()));
	    g.image(ArdHud.cr, new Coord(sz.x - ArdHud.cr.sz().x, 0));
	    g.rimagev(ArdHud.lm, new Coord(0, ArdHud.cl.sz().y), sz.y - ArdHud.bl.sz().y - ArdHud.cl.sz().y);
	    g.rimagev(ArdHud.rm, new Coord(sz.x - ArdHud.rm.sz().x, ArdHud.cr.sz().y), sz.y - ArdHud.br.sz().y - ArdHud.cr.sz().y);
	    g.rimageh(ArdHud.bm, new Coord(ArdHud.bl.sz().x, sz.y - ArdHud.bm.sz().y), sz.x - ArdHud.br.sz().x - ArdHud.bl.sz().x);
	    g.rimageh(ArdHud.cm, new Coord(ArdHud.cl.sz().x, 0), sz.x - ArdHud.cl.sz().x - ArdHud.cr.sz().x);
	    g.chcolor();

	    Text cap = decoX.cap;
	    if((cap == null) || (!Objects.equals(cap.text, wnd.cap))) {
		if(cap != null)
		    cap.dispose();
		cap = (wnd.cap == null) ? null : cf.render(wnd.cap);
		decoX.cap = cap;
	    }
	    if(cap != null)
		g.image(cap.tex(), UI.scale(ArdHud.capc));
	    if(decoX.dragsize && !wnd.minimized())
		g.image(Window.sizer, decoX.ca.br.sub(Window.sizer.sz()));
	}

	@Override
	public boolean checkhit(Coord c, DecoX decoX) {
	    ArdHud.ensure();
	    if(c.isect(decoX.ca.ul, decoX.ca.sz()))
		return true;
	    TexI cl = ArdHud.cl, cr = ArdHud.cr, cm = ArdHud.cm;
	    Coord cpc = c.sub(cl.sz().x, 0);
	    Coord cprc = c.sub(decoX.sz.x - cr.sz().x, 0);
	    if(c.isect(Coord.z, cl.sz()) && sample(cl.back, c.x, c.y) >= 128)
		return true;
	    if(c.isect(new Coord(decoX.sz.x - cr.sz().x, 0), cr.sz()) && sample(cr.back, cprc.x, cprc.y) >= 128)
		return true;
	    return c.isect(new Coord(cl.sz().x, 0), new Coord(decoX.sz.x - cr.sz().x - cl.sz().x, cm.sz().y))
		&& sample(cm.back, cpc.x, cpc.y) >= 128;
	}

	@Override
	public boolean hitSizer(Coord c, DecoX decoX) {
	    return (c.x < decoX.ca.br.x) && (c.y < decoX.ca.br.y)
		&& (c.y >= decoX.ca.br.y - UI.scale(25) + (decoX.ca.br.x - c.x));
	}

	private static int sample(BufferedImage img, int x, int y) {
	    x = Utils.floormod(x, img.getWidth());
	    if((y < 0) || (y >= img.getHeight()) || (img.getRaster().getNumBands() < 4))
		return 255;
	    return img.getRaster().getSample(x, y, 3);
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

}
