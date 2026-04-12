/* Preprocessed source code */
package haven.res.ui.tt.alch.effect;

import haven.*;
import java.util.*;
import java.awt.image.BufferedImage;

@haven.FromResource(name = "ui/tt/alch/effect", version = 2)
public abstract class Effect extends ItemInfo.Tip {
    public Effect(Owner owner) {
	super(owner);
    }

    public static class Subtip extends Tip {
	final List<Effect> ls = new ArrayList<>();

	Subtip(Owner owner) {super(owner);}

	public void layout(Layout l) {
	    Collections.sort(ls, Comparator.comparing(Effect::order));
	    CompImage img = new CompImage();
	    for(Effect inf : ls)
		inf.add(img);
	    l.cmp.add(Text.render("Known alchemical effects:").img, Coord.of(0, l.cmp.sz.y));
	    l.cmp.add(img, Coord.of(UI.scale(10), l.cmp.sz.y));
	}

	public int order() {return(1000);}
    }

    public static final Layout.TipID<Subtip> sid = Subtip::new;

    public void add(CompImage img) {
	img.add(CompImage.mk(alchtip()), new Coord(0, img.sz.y));
    }

    public BufferedImage alchtip() {
	return(RichText.render(alchtips()).img);
    }

    public String alchtips() {
	throw(new UnsupportedOperationException());
    }

    public void prepare(Layout l) {
	l.intern(sid).ls.add(this);
    }
}
