/* Preprocessed source code */
/* $use: ui/tt/alch/effect */
package haven.res.ui.tt.alch.ingr_heal;

import haven.*;
import haven.res.ui.tt.alch.effect.*;
import java.awt.image.BufferedImage;

/* >tt: HealWound */
@haven.FromResource(name = "ui/tt/alch/ingr-heal", version = 2)
public class HealWound extends Effect {
    public final Indir<Resource> res, repl;

    public HealWound(Owner owner, Indir<Resource> res, Indir<Resource> repl) {
	super(owner);
	this.res = res;
	this.repl = repl;
    }

    public static ItemInfo mkinfo(Owner owner, Raw raw, Object... args) {
	Indir<Resource> res = owner.context(Resource.Resolver.class).getresv(args[1]);
	Indir<Resource> repl = null;
	if(args.length > 2)
	    repl = owner.context(Resource.Resolver.class).getresv(args[2]);
	return(new HealWound(owner, res, repl));
    }

    public BufferedImage alchtip() {
	BufferedImage t1 = Text.render("Heal ").img;
	BufferedImage t2 = Text.render(res.get().layer(Resource.tooltip).t).img;
	int h = t1.getHeight();
	BufferedImage icon = PUtils.convolvedown(res.get().layer(Resource.imgc).img, new Coord(h, h), CharWnd.iconfilter);
	BufferedImage ret = catimgsh(0, t1, icon, t2);
	if(repl != null) {
	    ret = catimgsh(0, ret,
			   Text.render(" into ").img,
			   PUtils.convolvedown(repl.get().layer(Resource.imgc).img, new Coord(h, h), CharWnd.iconfilter),
			   Text.render(repl.get().layer(Resource.tooltip).t).img);
	}
	return(ret);
    }
}
