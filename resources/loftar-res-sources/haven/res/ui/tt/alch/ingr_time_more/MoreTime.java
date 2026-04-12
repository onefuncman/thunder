/* Preprocessed source code */
/* $use: ui/tt/alch/effect */
package haven.res.ui.tt.alch.ingr_time_more;

import haven.*;
import haven.res.ui.tt.alch.effect.*;
import java.awt.image.BufferedImage;

/* >tt: MoreTime */
@haven.FromResource(name = "ui/tt/alch/ingr-time-more", version = 2)
public class MoreTime extends Effect {
    public MoreTime(Owner owner) {super(owner);}

    public static ItemInfo mkinfo(Owner owner, Raw raw, Object... args) {
	return(new MoreTime(owner));
    }

    public String alchtips() {
	return("Increase elixir duration");
    }
}
