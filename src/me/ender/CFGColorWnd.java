package me.ender;

import haven.*;
import haven.Button;
import haven.Label;

import java.awt.*;

public class CFGColorWnd extends WindowX {
    private static final Coord BOX = UI.scale(Coord.of(45));
    private static final Coord HALF = Coord.of(BOX.x, BOX.y / 2);
    private static final int PAD = UI.scale(5);
    private static final int BTN_PAD = UI.scale(15);
    private static final int BTN_W = UI.scale(55);
    private static final int HEX_W = UI.scale(70);
    private static final int TEXT_W = UI.scale(30);
    
    private final TextEntry txtHex, txtR, txtG, txtB, txtA;
    private final CFG<Color> cfg;
    private Color col;
    private final boolean hasAlpha;
    
    private static CFGColorWnd open;
    
    public static CFGColorWnd open(CFG<Color> cfg, boolean hasAlpha) {
	if(open != null) {
	    CFGColorWnd tmp = open;
	    open = null;
	    tmp.close();
	}
	open = new CFGColorWnd(cfg, hasAlpha);
	return open;
    }
    
    @Override
    public void destroy() {
	if(open == this) {open = null;}
	super.destroy();
    }
    
    private CFGColorWnd(CFG<Color> cfg, boolean hasAlpha) {
	super(Coord.z, "Set Color");
	this.cfg = cfg;
	justclose = true;
	skipInitPos = skipSavePos = true;
	this.hasAlpha = hasAlpha;
	col = cfg.get();

	Composer composer = new Composer(this).hmrgn(PAD).vmrgn(PAD).hpad(BOX.x + PAD);

	txtR = new TextField(TEXT_W, Integer.toString(col.getRed()), this::rgbUpdated);
	txtG = new TextField(TEXT_W, Integer.toString(col.getGreen()), this::rgbUpdated);
	txtB = new TextField(TEXT_W, Integer.toString(col.getBlue()), this::rgbUpdated);
	composer.addr(
	    new Label("R:"), txtR,
	    new Label("G:"), txtG,
	    new Label("B:"), txtB
	);
	
	txtHex = new TextField(HEX_W, ClientUtils.color2hex(col, this.hasAlpha).toUpperCase(), this::hexUpdated);
	Label label = new Label("Hex:");
	
	if(this.hasAlpha) {
	    txtA = new TextField(TEXT_W, Integer.toString(col.getAlpha()), this::rgbUpdated);
	    composer.addr(label, txtHex, new Label("A:"), txtA);
	} else {
	    txtA = null;
	    composer.addr(label, txtHex);
	}
	composer.hpad(0).hmrgn(BTN_PAD).add(PAD);
	
	composer.addr(
	    new Button(BTN_W, "Apply", () -> update(col, false)),
	    new Button(BTN_W, "Save", () -> update(col, true)),
	    new Button(BTN_W, "Reset", () -> update(this.cfg.def, false))
	);

	composer.hpad(0).add(PAD);
	composer.addr(new Label("Apply = preview   Save = persist   Reset = default"));
	if(hasAlpha) {
	    composer.addr(new Label("Swatch: top = opaque reference, bottom = actual (with alpha)"));
	} else {
	    composer.addr(new Label("Swatch shows the configured color."));
	}

	pack();
    }
    
    private void update(Color c, boolean close) {
	if(col != c) {
	    col = c;
	    updateHEX();
	    updateRGB();
	}
	cfg.set(c);
	if(close) {close();}
    }
    
    private void rgbUpdated() {
	col = new Color(
	    ClientUtils.str2cc(txtR.text()),
	    ClientUtils.str2cc(txtG.text()),
	    ClientUtils.str2cc(txtB.text()),
	    hasAlpha ? ClientUtils.str2cc(txtA.text()) : 255
	);
	updateHEX();
    }
    
    private void hexUpdated() {
	col = ClientUtils.hex2color(txtHex.text(), col);
	updateRGB();
    }
    
    private void updateHEX() {
	txtHex.rsettext(ClientUtils.color2hex(col, hasAlpha).toUpperCase());
    }
    
    private void updateRGB() {
	txtR.rsettext(Integer.toString(col.getRed()));
	txtG.rsettext(Integer.toString(col.getGreen()));
	txtB.rsettext(Integer.toString(col.getBlue()));
	if(txtA != null) {
	    txtA.rsettext(Integer.toString(col.getAlpha()));
	}
    }
    
    @Override
    public void cdraw(GOut og) {
	super.cdraw(og);
	og.chcolor(col);
	if(hasAlpha) {
	    og.frect2(Coord.of(0, HALF.y), BOX);
	    og.chcolor(col.getRed(), col.getGreen(), col.getBlue(), 255);
	    og.frect2(Coord.z, HALF);
	} else {
	    og.frect2(Coord.z, BOX);
	}
	og.chcolor();
    }
    
    private static class TextField extends TextEntry {
	private final Runnable changed;
	
	public TextField(int w, String deftext, Runnable changed) {
	    super(w, deftext);
	    this.changed = changed;
	}
	
	@Override
	public void activate(String text) {
	    if(changed != null) {changed.run();}
	}
	
	@Override
	public void changed(ReadLine buf) {
	    super.changed(buf);
	    if(changed != null) {changed.run();}
	}
    }
}
