package haven;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class PosixArgsTest {

    @Test
    void noArgs() {
        PosixArgs pa = PosixArgs.getopt(new String[]{}, "abc");
        assertNotNull(pa);
        assertEquals(0, pa.rest.length);
        assertFalse(pa.parsed().iterator().hasNext());
    }

    @Test
    void simpleFlag() {
        PosixArgs pa = PosixArgs.getopt(new String[]{"-a"}, "abc");
        List<Character> flags = new ArrayList<>();
        for (char c : pa.parsed()) flags.add(c);
        assertEquals(Collections.singletonList('a'), flags);
        assertEquals(0, pa.rest.length);
    }

    @Test
    void multipleFlags() {
        PosixArgs pa = PosixArgs.getopt(new String[]{"-abc"}, "abc");
        List<Character> flags = new ArrayList<>();
        for (char c : pa.parsed()) flags.add(c);
        assertEquals(Arrays.asList('a', 'b', 'c'), flags);
    }

    @Test
    void flagWithArgSameToken() {
        PosixArgs pa = PosixArgs.getopt(new String[]{"-fvalue"}, "f:");
        for (char c : pa.parsed()) {
            assertEquals('f', c);
            assertEquals("value", pa.arg);
        }
    }

    @Test
    void flagWithArgNextToken() {
        PosixArgs pa = PosixArgs.getopt(new String[]{"-f", "value"}, "f:");
        for (char c : pa.parsed()) {
            assertEquals('f', c);
            assertEquals("value", pa.arg);
        }
    }

    @Test
    void restArguments() {
        PosixArgs pa = PosixArgs.getopt(new String[]{"file1", "file2"}, "abc");
        assertEquals(0, new ArrayList<Character>() {{ for (char c : pa.parsed()) add(c); }}.size());
        assertArrayEquals(new String[]{"file1", "file2"}, pa.rest);
    }

    @Test
    void mixedFlagsAndRest() {
        PosixArgs pa = PosixArgs.getopt(new String[]{"-a", "file1", "-b", "file2"}, "ab");
        List<Character> flags = new ArrayList<>();
        for (char c : pa.parsed()) flags.add(c);
        assertEquals(Arrays.asList('a', 'b'), flags);
        assertArrayEquals(new String[]{"file1", "file2"}, pa.rest);
    }

    @Test
    void doubleDashStopsProcessing() {
        PosixArgs pa = PosixArgs.getopt(new String[]{"-a", "--", "-b"}, "ab");
        List<Character> flags = new ArrayList<>();
        for (char c : pa.parsed()) flags.add(c);
        assertEquals(Collections.singletonList('a'), flags);
        // Note: the source code falls through after setting acc=false,
        // so "--" itself also ends up in rest
        assertArrayEquals(new String[]{"--", "-b"}, pa.rest);
    }

    @Test
    void invalidFlagReturnsNull() {
        PosixArgs pa = PosixArgs.getopt(new String[]{"-x"}, "ab");
        assertNull(pa);
    }

    @Test
    void missingArgReturnsNull() {
        PosixArgs pa = PosixArgs.getopt(new String[]{"-f"}, "f:");
        assertNull(pa);
    }

    @Test
    void startOffset() {
        PosixArgs pa = PosixArgs.getopt(new String[]{"program", "-a", "file"}, 1, "a");
        List<Character> flags = new ArrayList<>();
        for (char c : pa.parsed()) flags.add(c);
        assertEquals(Collections.singletonList('a'), flags);
        assertArrayEquals(new String[]{"file"}, pa.rest);
    }

    @Test
    void flagNoArgNull() {
        PosixArgs pa = PosixArgs.getopt(new String[]{"-a"}, "a");
        for (char c : pa.parsed()) {
            assertEquals('a', c);
            assertNull(pa.arg);
        }
    }
}
