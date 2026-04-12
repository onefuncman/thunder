/* Preprocessed source code */
/* $use: ui/tt/alch/effect */
package haven.res.ui.tt.alch.ingr_buff;

import haven.*;
import haven.res.ui.tt.alch.effect.*;
import java.awt.image.BufferedImage;

/* >tt: BuffAttr */
@haven.FromResource(name = "ui/tt/alch/ingr-buff", version = 2)
public class BuffAttr extends Effect {
    public final Indir<Resource> res;

    public BuffAttr(Owner owner, Indir<Resource> res) {
	super(owner);
	this.res = res;
    }

    public static ItemInfo mkinfo(Owner owner, Raw raw, Object... args) {
	Indir<Resource> res = owner.context(Resource.Resolver.class).getresv(args[1]);
	return(new BuffAttr(owner, res));
    }

    public BufferedImage alchtip() {
	BufferedImage t1 = Text.render("Increase ").img;
	BufferedImage t2 = Text.render(res.get().layer(Resource.tooltip).t).img;
	int h = t1.getHeight();
	BufferedImage icon = PUtils.convolvedown(res.get().layer(Resource.imgc).img, new Coord(h, h), CharWnd.iconfilter);
	return(catimgsh(0, t1, icon, t2));
    }
}
