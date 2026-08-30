package haven;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ArdHudTest {
    private static final Path WINDOW_RES = Path.of("resources/pre-compiled/res/custom/hud/ardclient/window.res");

    @Test
    void vendoredWindowHasArdLayout() throws Exception {
	assertTrue(Files.isRegularFile(WINDOW_RES), "vendored Ard window.res");
	byte[] data = Files.readAllBytes(WINDOW_RES);
	assertArrayEquals("Haven Resource 1".getBytes(StandardCharsets.US_ASCII),
	    java.util.Arrays.copyOf(data, 16));
	int images = 0;
	boolean foundCfg = false;
	int off = 18;
	while(off < data.length) {
	    int z = indexOf(data, (byte)0, off);
	    String name = new String(data, off, z - off, StandardCharsets.US_ASCII);
	    off = z + 1;
	    int len = ByteBuffer.wrap(data, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
	    off += 4;
	    if(name.equals("image"))
		images++;
	    if(name.equals("windowconfig")) {
		foundCfg = true;
		assertEquals(16, len);
		ByteBuffer buf = ByteBuffer.wrap(data, off, len).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(ArdHud.FALLBACK_TLC, Coord.of(buf.getShort(), buf.getShort()));
		assertEquals(ArdHud.FALLBACK_BRC, Coord.of(buf.getShort(), buf.getShort()));
		assertEquals(ArdHud.FALLBACK_CAPC, Coord.of(buf.getShort(), buf.getShort()));
		assertEquals(ArdHud.FALLBACK_BTNC, Coord.of(buf.getShort(), buf.getShort()));
	    }
	    off += len;
	}
	assertTrue(foundCfg);
	assertEquals(11, images);
    }

    @Test
    void themeUsesVendoredArdHud() {
	assertTrue(Theme.Ard.usesArdHud());
	assertTrue(Theme.Ard.usesFloatingHud());
	assertFalse(Theme.Pretty.usesArdHud());
    }

    @Test
    void windowTintIsBlackAndTranslucent() {
	assertTrue(ArdHud.FILL.getRed() < 32);
	assertTrue(ArdHud.FILL.getAlpha() < 255);
	assertTrue(ArdHud.BTNCOL.getRed() < 32);
	assertTrue(ArdHud.TXBCOL.getRed() < 32);
    }

    private static int indexOf(byte[] data, byte b, int from) {
	for(int i = from; i < data.length; i++) {
	    if(data[i] == b)
		return i;
	}
	throw new IllegalArgumentException("unterminated string at " + from);
    }
}
