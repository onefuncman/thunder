package haven.proto;

import haven.*;
import org.junit.jupiter.api.Test;
import java.awt.Color;
import static org.junit.jupiter.api.Assertions.*;

public class ProtoDecoderTest {

    // --- rmsgTypeName ---

    @Test
    void rmsgTypeNameKnownTypes() {
        assertEquals("RMSG_NEWWDG", ProtoDecoder.rmsgTypeName(RMessage.RMSG_NEWWDG));
        assertEquals("RMSG_WDGMSG", ProtoDecoder.rmsgTypeName(RMessage.RMSG_WDGMSG));
        assertEquals("RMSG_DSTWDG", ProtoDecoder.rmsgTypeName(RMessage.RMSG_DSTWDG));
        assertEquals("RMSG_MAPIV", ProtoDecoder.rmsgTypeName(RMessage.RMSG_MAPIV));
        assertEquals("RMSG_GLOBLOB", ProtoDecoder.rmsgTypeName(RMessage.RMSG_GLOBLOB));
        assertEquals("RMSG_RESID", ProtoDecoder.rmsgTypeName(RMessage.RMSG_RESID));
        assertEquals("RMSG_SESSKEY", ProtoDecoder.rmsgTypeName(RMessage.RMSG_SESSKEY));
        assertEquals("RMSG_FRAGMENT", ProtoDecoder.rmsgTypeName(RMessage.RMSG_FRAGMENT));
        assertEquals("RMSG_ADDWDG", ProtoDecoder.rmsgTypeName(RMessage.RMSG_ADDWDG));
        assertEquals("RMSG_WDGBAR", ProtoDecoder.rmsgTypeName(RMessage.RMSG_WDGBAR));
        assertEquals("RMSG_USERAGENT", ProtoDecoder.rmsgTypeName(RMessage.RMSG_USERAGENT));
    }

    @Test
    void rmsgTypeNameUnknown() {
        assertEquals("RMSG_99", ProtoDecoder.rmsgTypeName(99));
    }

    // --- odTypeName ---

    @Test
    void odTypeNameKnownTypes() {
        assertEquals("OD_REM", ProtoDecoder.odTypeName(OCache.OD_REM));
        assertEquals("OD_MOVE", ProtoDecoder.odTypeName(OCache.OD_MOVE));
        assertEquals("OD_RES", ProtoDecoder.odTypeName(OCache.OD_RES));
        assertEquals("OD_LINBEG", ProtoDecoder.odTypeName(OCache.OD_LINBEG));
        assertEquals("OD_LINSTEP", ProtoDecoder.odTypeName(OCache.OD_LINSTEP));
        assertEquals("OD_SPEECH", ProtoDecoder.odTypeName(OCache.OD_SPEECH));
        assertEquals("OD_COMPOSE", ProtoDecoder.odTypeName(OCache.OD_COMPOSE));
        assertEquals("OD_ZOFF", ProtoDecoder.odTypeName(OCache.OD_ZOFF));
        assertEquals("OD_LUMIN", ProtoDecoder.odTypeName(OCache.OD_LUMIN));
        assertEquals("OD_AVATAR", ProtoDecoder.odTypeName(OCache.OD_AVATAR));
        assertEquals("OD_FOLLOW", ProtoDecoder.odTypeName(OCache.OD_FOLLOW));
        assertEquals("OD_HOMING", ProtoDecoder.odTypeName(OCache.OD_HOMING));
        assertEquals("OD_OVERLAY", ProtoDecoder.odTypeName(OCache.OD_OVERLAY));
        assertEquals("OD_HEALTH", ProtoDecoder.odTypeName(OCache.OD_HEALTH));
        assertEquals("OD_CMPPOSE", ProtoDecoder.odTypeName(OCache.OD_CMPPOSE));
        assertEquals("OD_CMPMOD", ProtoDecoder.odTypeName(OCache.OD_CMPMOD));
        assertEquals("OD_CMPEQU", ProtoDecoder.odTypeName(OCache.OD_CMPEQU));
        assertEquals("OD_ICON", ProtoDecoder.odTypeName(OCache.OD_ICON));
        assertEquals("OD_RESATTR", ProtoDecoder.odTypeName(OCache.OD_RESATTR));
        assertEquals("OD_END", ProtoDecoder.odTypeName(OCache.OD_END));
    }

    @Test
    void odTypeNameUnknown() {
        assertEquals("OD_99", ProtoDecoder.odTypeName(99));
    }

    // --- resName ---

    @Test
    void resNameNullSession() {
        assertEquals("res:42", ProtoDecoder.resName(null, 42));
    }

    // --- describeArg ---

    @Test
    void describeArgNull() {
        assertEquals("nil", ProtoDecoder.describeArg(null));
    }

    @Test
    void describeArgString() {
        assertEquals("\"hello\"", ProtoDecoder.describeArg("hello"));
    }

    @Test
    void describeArgCoord() {
        String result = ProtoDecoder.describeArg(Coord.of(3, 4));
        assertEquals("(3, 4)", result);
    }

    @Test
    void describeArgCoord2d() {
        String result = ProtoDecoder.describeArg(Coord2d.of(1.5, 2.5));
        assertTrue(result.contains("1.5"));
        assertTrue(result.contains("2.5"));
    }

    @Test
    void describeArgColor() {
        Color c = new Color(255, 128, 0, 200);
        String result = ProtoDecoder.describeArg(c);
        assertEquals("Color(255,128,0,200)", result);
    }

    @Test
    void describeArgSmallByteArray() {
        byte[] data = {0x01, 0x02, (byte) 0xFF};
        String result = ProtoDecoder.describeArg(data);
        assertTrue(result.startsWith("bytes:"));
        assertTrue(result.contains("0102FF"));
    }

    @Test
    void describeArgLargeByteArray() {
        byte[] data = new byte[32];
        String result = ProtoDecoder.describeArg(data);
        assertTrue(result.contains("..."));
        assertTrue(result.contains("bytes[32]"));
    }

    @Test
    void describeArgInteger() {
        assertEquals("42", ProtoDecoder.describeArg(42));
    }

    @Test
    void describeArgNestedArray() {
        Object[] nested = {1, "two"};
        String result = ProtoDecoder.describeArg(nested);
        assertTrue(result.contains("1"));
        assertTrue(result.contains("\"two\""));
    }

    // --- describeArgs ---

    @Test
    void describeArgsEmpty() {
        assertEquals("[]", ProtoDecoder.describeArgs(new Object[0]));
    }

    @Test
    void describeArgsNull() {
        assertEquals("[]", ProtoDecoder.describeArgs(null));
    }

    @Test
    void describeArgsMultiple() {
        Object[] args = {42, "hello", null};
        String result = ProtoDecoder.describeArgs(args);
        assertTrue(result.startsWith("["));
        assertTrue(result.endsWith("]"));
        assertTrue(result.contains("42"));
        assertTrue(result.contains("\"hello\""));
        assertTrue(result.contains("nil"));
    }

    // --- decodeRel: RMSG_DSTWDG ---

    @Test
    void decodeRelDstWdg() {
        // Build a DSTWDG message: type=2, payload=int32(widgetId)
        MessageBuf buf = new MessageBuf();
        buf.addint32(42); // widget id
        PMessage msg = new PMessage(RMessage.RMSG_DSTWDG, buf.fin());
        ProtoEvent evt = ProtoDecoder.decodeRel(msg, null);
        assertEquals(ProtoEvent.Direction.IN, evt.dir);
        assertEquals(ProtoEvent.Category.WIDGET, evt.category);
        assertEquals("RMSG_DSTWDG", evt.typeName);
        assertEquals(42, evt.widgetId);
        assertTrue(evt.summary.contains("Destroy widget #42"));
    }

    // --- decodeRel: RMSG_NEWWDG ---

    @Test
    void decodeRelNewWdg() {
        MessageBuf buf = new MessageBuf();
        buf.addint32(10);          // widget id
        buf.addstring("Button");   // type
        buf.addint32(1);           // parent id
        buf.adduint8(Message.T_END); // empty pargs
        buf.adduint8(Message.T_END); // empty cargs
        PMessage msg = new PMessage(RMessage.RMSG_NEWWDG, buf.fin());
        ProtoEvent evt = ProtoDecoder.decodeRel(msg, null);
        assertEquals(ProtoEvent.Category.WIDGET, evt.category);
        assertEquals(10, evt.widgetId);
        assertTrue(evt.summary.contains("New widget #10"));
        assertTrue(evt.summary.contains("Button"));
        assertTrue(evt.summary.contains("parent=#1"));
    }

    // --- decodeRel: RMSG_WDGMSG ---

    @Test
    void decodeRelWdgMsg() {
        MessageBuf buf = new MessageBuf();
        buf.addint32(5);           // widget id
        buf.addstring("click");    // message name
        buf.adduint8(Message.T_END); // empty args
        PMessage msg = new PMessage(RMessage.RMSG_WDGMSG, buf.fin());
        ProtoEvent evt = ProtoDecoder.decodeRel(msg, null);
        assertEquals(ProtoEvent.Category.WIDGET, evt.category);
        assertEquals(5, evt.widgetId);
        assertTrue(evt.summary.contains("click"));
    }

    // --- decodeRel: RMSG_ADDWDG ---

    @Test
    void decodeRelAddWdg() {
        MessageBuf buf = new MessageBuf();
        buf.addint32(20);          // widget id
        buf.addint32(1);           // parent id
        buf.adduint8(Message.T_END); // empty pargs
        PMessage msg = new PMessage(RMessage.RMSG_ADDWDG, buf.fin());
        ProtoEvent evt = ProtoDecoder.decodeRel(msg, null);
        assertEquals(ProtoEvent.Category.WIDGET, evt.category);
        assertEquals(20, evt.widgetId);
        assertTrue(evt.summary.contains("Add widget #20 to parent #1"));
    }

    // --- decodeRel: RMSG_RESID ---

    @Test
    void decodeRelResId() {
        MessageBuf buf = new MessageBuf();
        buf.adduint16(100);               // resource id
        buf.addstring("gfx/terobjs/tree"); // resource name
        buf.adduint16(5);                  // resource version
        PMessage msg = new PMessage(RMessage.RMSG_RESID, buf.fin());
        ProtoEvent evt = ProtoDecoder.decodeRel(msg, null);
        assertEquals(ProtoEvent.Category.RESOURCE, evt.category);
        assertTrue(evt.summary.contains("gfx/terobjs/tree"));
        assertTrue(evt.summary.contains("v5"));
    }

    // --- decodeRel: RMSG_MAPIV ---

    @Test
    void decodeRelMapIv() {
        PMessage msg = new PMessage(RMessage.RMSG_MAPIV, new byte[]{2});
        ProtoEvent evt = ProtoDecoder.decodeRel(msg, null);
        assertEquals(ProtoEvent.Category.MAP, evt.category);
        assertTrue(evt.summary.contains("Map trim all"));
    }

    // --- decodeRel: RMSG_GLOBLOB ---

    @Test
    void decodeRelGloblob() {
        PMessage msg = new PMessage(RMessage.RMSG_GLOBLOB, new byte[]{1, 2, 3, 4});
        ProtoEvent evt = ProtoDecoder.decodeRel(msg, null);
        assertEquals(ProtoEvent.Category.GLOB, evt.category);
        assertTrue(evt.summary.contains("Global state blob"));
    }

    // --- decodeRel: RMSG_SESSKEY ---

    @Test
    void decodeRelSessKey() {
        PMessage msg = new PMessage(RMessage.RMSG_SESSKEY, new byte[16]);
        ProtoEvent evt = ProtoDecoder.decodeRel(msg, null);
        assertEquals(ProtoEvent.Category.SESSION, evt.category);
        assertTrue(evt.summary.contains("Session key"));
    }

    // --- decodeRel: RMSG_FRAGMENT ---

    @Test
    void decodeRelFragment() {
        PMessage msg = new PMessage(RMessage.RMSG_FRAGMENT, new byte[64]);
        ProtoEvent evt = ProtoDecoder.decodeRel(msg, null);
        assertEquals(ProtoEvent.Category.SESSION, evt.category);
        assertTrue(evt.summary.contains("fragment"));
    }

    // --- decodeRel: unknown type ---

    @Test
    void decodeRelUnknown() {
        PMessage msg = new PMessage(99, new byte[]{0x42});
        ProtoEvent evt = ProtoDecoder.decodeRel(msg, null);
        assertEquals(ProtoEvent.Category.SESSION, evt.category);
        assertTrue(evt.summary.contains("Unknown RMSG type 99"));
    }

    // --- decodeRel preserves read head ---

    @Test
    void decodeRelPreservesReadHead() {
        MessageBuf buf = new MessageBuf();
        buf.addint32(42);
        PMessage msg = new PMessage(RMessage.RMSG_DSTWDG, buf.fin());
        int rhBefore = msg.rh;
        ProtoDecoder.decodeRel(msg, null);
        assertEquals(rhBefore, msg.rh);
    }

    // --- decodeMapData ---

    @Test
    void decodeMapData() {
        // 8-byte header (pktid int32, off uint16, len uint16) + payload
        MessageBuf buf = new MessageBuf();
        buf.addint32(42);     // pktid
        buf.adduint16(0);     // off
        buf.adduint16(120);   // total len
        for(int i = 0; i < 120; i++) buf.addint8((byte) 0);
        MessageBuf msg = new MessageBuf(buf.fin());
        ProtoEvent evt = ProtoDecoder.decodeMapData(msg);
        assertEquals(ProtoEvent.Direction.IN, evt.dir);
        assertEquals(ProtoEvent.Category.MAP, evt.category);
        assertEquals("MAPDATA", evt.typeName);
        assertTrue(evt.summary.contains("pkt=42"));
        assertTrue(evt.summary.contains("frag=120B"));
    }

    // --- decodeOutgoing ---

    @Test
    void decodeOutgoing() {
        ProtoEvent evt = ProtoDecoder.decodeOutgoing(5, "click", new Object[]{10, 20});
        assertEquals(ProtoEvent.Direction.OUT, evt.dir);
        assertEquals(ProtoEvent.Category.WIDGET, evt.category);
        assertEquals("WDGMSG_OUT", evt.typeName);
        assertEquals(5, evt.widgetId);
        assertTrue(evt.summary.contains("click"));
    }

    // --- RMSG_WDGBAR ---

    @Test
    void decodeRelWdgBar() {
        MessageBuf buf = new MessageBuf();
        buf.addint32(1);
        buf.addint32(2);
        buf.addint32(3);
        buf.addint32(-1); // terminator
        PMessage msg = new PMessage(RMessage.RMSG_WDGBAR, buf.fin());
        ProtoEvent evt = ProtoDecoder.decodeRel(msg, null);
        assertEquals(ProtoEvent.Category.WIDGET, evt.category);
        assertTrue(evt.summary.contains("Widget barrier"));
    }
}
