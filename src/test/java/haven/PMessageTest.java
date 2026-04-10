package haven;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PMessageTest {

    @Test
    void constructWithType() {
        PMessage msg = new PMessage(5);
        assertEquals(5, msg.type);
        assertTrue(msg.eom());
    }

    @Test
    void constructWithBlob() {
        PMessage msg = new PMessage(3, new byte[]{1, 2, 3});
        assertEquals(3, msg.type);
        assertEquals(3, msg.rem());
    }

    @Test
    void constructWithBlobOffsetLength() {
        byte[] blob = {0, 0, 10, 20, 30, 0, 0};
        PMessage msg = new PMessage(7, blob, 2, 3);
        assertEquals(7, msg.type);
        assertEquals(3, msg.rem());
        assertEquals(10, msg.uint8());
        assertEquals(20, msg.uint8());
        assertEquals(30, msg.uint8());
    }

    @Test
    void constructFromMessage() {
        MessageBuf buf = new MessageBuf();
        buf.adduint8(42);
        buf.addstring("hello");
        PMessage original = new PMessage(1, buf.fin());
        PMessage copy = new PMessage(original);
        assertEquals(1, copy.type);
        assertEquals(42, copy.uint8());
        assertEquals("hello", copy.string());
    }

    @Test
    void cloneCreatesIndependentCopy() {
        PMessage msg = new PMessage(5, new byte[]{1, 2, 3});
        PMessage cloned = msg.clone();
        assertEquals(5, cloned.type);
        assertEquals(3, cloned.rem());
        // Reading from clone doesn't affect original
        cloned.uint8();
        assertEquals(3, msg.rem());
        assertEquals(2, cloned.rem());
    }

    @Test
    void writeAndRead() {
        PMessage msg = new PMessage(10);
        msg.addint32(12345);
        msg.addstring("test");
        byte[] data = msg.fin();

        PMessage read = new PMessage(10, data);
        assertEquals(12345, read.int32());
        assertEquals("test", read.string());
    }

    @Test
    void typePreservedThroughCopy() {
        for (int t = 0; t < 20; t++) {
            PMessage msg = new PMessage(t);
            PMessage copy = new PMessage(msg);
            assertEquals(t, copy.type);
        }
    }
}
