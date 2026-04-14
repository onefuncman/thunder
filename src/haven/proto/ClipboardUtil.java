package haven.proto;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;

public class ClipboardUtil {
    public static void copy(String s) {
	if(s == null) return;
	try {
	    Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
	    cb.setContents(new StringSelection(s), null);
	} catch(Exception e) {
	}
    }
}
