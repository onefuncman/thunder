/* Preprocessed source code */
/* $use: ui/tt/alch/effect */
package haven.res.ui.tt.alch.ingr_time_less;

import haven.*;
import haven.res.ui.tt.alch.effect.*;
import java.awt.image.BufferedImage;

/* >tt: LessTime */
@haven.FromResource(name = "ui/tt/alch/ingr-time-less", version = 2)
public class LessTime extends Effect {
    public LessTime(Owner owner) {super(owner);}

    public static ItemInfo mkinfo(Owner owner, Raw raw, Object... args) {
	return(new LessTime(owner));
    }

    public String alchtips() {
	return("Decrease elixir duration");
    }
}
