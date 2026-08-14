package thunder;

import haven.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * In-game documentation window for Thunder-specific features. Content is
 * plain-text files with RichText markup bundled into hafen.jar under
 * /docs/, one file per topic (see docs/ingame/ in the source tree).
 */
public class DocWnd extends WindowX {
    private static final int MAX_LINES = 300;
    public final String topic;

    private DocWnd(String topic, String title) {
	super(Coord.z, title);
	this.topic = topic;
	justclose = true;
	Textlog txt = add(new Textlog(UI.scale(450, 400)));
	txt.quote = false;
	txt.maxLines = MAX_LINES;
	try(InputStream in = DocWnd.class.getResourceAsStream("/docs/" + topic + ".txt")) {
	    if(in == null) {
		txt.append("Documentation for '" + topic + "' is missing from this build.");
	    } else {
		BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		String line;
		int count = 0;
		while((count < MAX_LINES) && ((line = br.readLine()) != null)) {
		    txt.append(line.isEmpty() ? " " : line);
		    count++;
		}
	    }
	} catch(Exception e) {
	    txt.append("Could not load documentation: " + e.getMessage());
	}
	txt.setprog(0);
	pack();
    }

    public static void toggle(UI ui, String topic, String title) {
	if(ui == null || ui.gui == null) {return;}
	for(Widget w = ui.gui.child; w != null; w = w.next) {
	    if((w instanceof DocWnd) && ((DocWnd) w).topic.equals(topic)) {
		w.destroy();
		return;
	    }
	}
	ui.gui.add(new DocWnd(topic, title), UI.scale(new Coord(200, 100)));
    }
}
