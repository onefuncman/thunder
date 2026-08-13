package haven;

import org.junit.jupiter.api.Test;

import java.awt.Container;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ReadLine.Base assumes a non-null owner: setline(), key() and the clipboard
 * paths all call into it unconditionally. Constructing one with a null owner
 * used to NPE the first time the line changed (e.g. FilteredListBox.filter()
 * via the alchemy window's tab strip).
 */
public class ReadLineTest {
    private static class RecordingOwner implements ReadLine.Owner {
	int changed = 0;
	int done = 0;

	public UI ui() {return null;}
	public void changed(ReadLine buf) {changed++;}
	public void done(ReadLine buf) {done++;}
    }

    private static KeyEvent typed(char c, int code) {
	return new KeyEvent(new Container(), KeyEvent.KEY_PRESSED, 0, 0, code, c);
    }

    @Test
    void setlineNotifiesOwner() {
	RecordingOwner owner = new RecordingOwner();
	ReadLine buf = new ReadLine.PCLine(owner, "");
	buf.setline("carrot");
	assertEquals("carrot", buf.line());
	assertEquals(1, owner.changed);
    }

    @Test
    void setlineWithUnchangedContentDoesNotNotify() {
	RecordingOwner owner = new RecordingOwner();
	ReadLine buf = new ReadLine.PCLine(owner, "carrot");
	buf.setline("carrot");
	assertEquals(0, owner.changed);
    }

    @Test
    void setlineWorksBeforeOwnerIsAttachedToUi() {
	//FilteredListBox creates its filter buffer in a field initializer,
	//long before the widget is attached, so the owner's ui() is still
	//null when tab selection drives the first setline.
	ReadLine.Owner detached = () -> null;
	ReadLine buf = new ReadLine.PCLine(detached, "");
	assertDoesNotThrow(() -> buf.setline("Tested"));
	assertEquals("Tested", buf.line());
	assertDoesNotThrow(() -> buf.setline(""));
	assertEquals("", buf.line());
    }

    @Test
    void typingNotifiesOwner() {
	RecordingOwner owner = new RecordingOwner();
	ReadLine buf = new ReadLine.PCLine(owner, "");
	assertTrue(buf.key(typed('a', KeyEvent.VK_A)));
	assertEquals("a", buf.line());
	assertEquals(1, owner.changed);
    }

    @Test
    void unhandledKeyDoesNotNotify() {
	RecordingOwner owner = new RecordingOwner();
	ReadLine buf = new ReadLine.PCLine(owner, "abc");
	assertFalse(buf.key(typed(KeyEvent.CHAR_UNDEFINED, KeyEvent.VK_F1)));
	assertEquals("abc", buf.line());
	assertEquals(0, owner.changed);
    }

    @Test
    void enterReportsDone() {
	RecordingOwner owner = new RecordingOwner();
	ReadLine buf = new ReadLine.PCLine(owner, "abc");
	assertTrue(buf.key(typed('\n', KeyEvent.VK_ENTER)));
	assertEquals(1, owner.done);
    }

    @Test
    void setlineClampsPointToNewLength() {
	ReadLine buf = new ReadLine.PCLine(new RecordingOwner(), "longer line");
	buf.point(buf.length());
	buf.setline("ab");
	assertTrue(buf.point() <= buf.length());
    }

    @Test
    void pclineSetlineClearsMark() {
	ReadLine buf = new ReadLine.PCLine(new RecordingOwner(), "abcdef");
	buf.mark(2);
	buf.setline("other");
	assertEquals(-1, buf.mark());
    }

    /**
     * Guard against reintroducing ownerless ReadLines: every construction
     * site must pass a real owner, since ReadLine.Base dereferences it
     * without null checks.
     */
    @Test
    void noNullOwnerReadLinesInSource() throws IOException {
	Path src = Path.of("src");
	assumeTrue(Files.isDirectory(src), "source tree not available");
	try(Stream<Path> files = Files.walk(src)) {
	    List<String> offenders = files
		.filter(p -> p.toString().endsWith(".java"))
		.filter(p -> !p.endsWith("ReadLineTest.java"))
		.filter(p -> {
		    try {
			String text = Files.readString(p);
			return text.matches("(?s).*(ReadLine\\.make|new\\s+ReadLine\\.\\w+Line)\\(\\s*null.*");
		    } catch(IOException e) {
			return false;
		    }
		})
		.map(Path::toString)
		.collect(Collectors.toList());
	    assertTrue(offenders.isEmpty(),
		"ReadLine constructed with null owner (NPEs on first change) in: " + offenders);
	}
    }
}
