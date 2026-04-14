/* Preprocessed source code */
package haven.res.ui.tt.drink;

import haven.*;
import java.awt.image.BufferedImage;

@haven.FromResource(name = "ui/tt/drink", version = 6)
public class Type {
    public final BufferedImage img;
    public final String nm;
    public final double m;

    public Type(BufferedImage img, String nm, double m) {
	this.img = img;
	this.nm = nm;
	this.m = m;
    }

    public static Type make(ItemInfo.Owner owner, ResData sdt, double m) {
	ItemSpec spec = new ItemSpec(owner, sdt, null);
	GSprite spr = spec.spr();
	BufferedImage img;
	if(spr instanceof GSprite.ImageSprite)
	    img = ((GSprite.ImageSprite)spr).image();
	else
	    img = spec.res.res.get().layer(Resource.imgc).img;
	return(new Type(img, spec.name(), m));
    }
}

/* >tt: Drink */
