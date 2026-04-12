/* Preprocessed source code */
package haven.res.ui.inspect;

import haven.*;
import java.util.*;
import java.awt.Color;

/* >wdg: LocalInspect */
@haven.FromResource(name = "ui/inspect", version = 5)
public class Info implements UI.Notice {
    public final long gobid;
    public final boolean syn;
    public final String text;

    public Info(long gobid, boolean syn, String text) {
	this.gobid = gobid;
	this.syn = syn;
	this.text = text;
    }

    public static UI.Notice mkmessage(OwnerContext owner, Object... args) {
	long gobid = Utils.uiv(args[0]);
	String text = (String)args[1];
	boolean syn = (args.length > 2) ? Utils.bv(args[2]) : false;
	return(new Info(gobid, syn, text));
    }

    public String message() {return(text);}
    public Color color() {return(Color.WHITE);}
    public Audio.Clip sfx() {return(UI.InfoMessage.sfx);}

    private void save(Glob glob) {
	Gob gob = glob.oc.getgob(gobid);
	if(gob != null) {
	    SavedInfo cell = gob.getattr(SavedInfo.class);
	    if(syn || (cell == null))
		gob.setattr(cell = new SavedInfo(gob));
	    List<String> lines = new ArrayList<>(cell.lines.size() + 1);
	    lines.addAll(cell.lines);
	    lines.add(text);
	    cell.lines = lines;
	}
    }

    public boolean handle(Widget w) {
	if(w instanceof GameUI)
	    save(w.ui.sess.glob);
	return(false);
    }
}
