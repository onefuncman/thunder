package haven.proto;

import haven.*;
import java.awt.Color;
import java.util.*;

public class ProtoDecoder {
    private ProtoDecoder() {}

    public static String rmsgTypeName(int type) {
	switch(type) {
	case RMessage.RMSG_NEWWDG:   return "RMSG_NEWWDG";
	case RMessage.RMSG_WDGMSG:   return "RMSG_WDGMSG";
	case RMessage.RMSG_DSTWDG:   return "RMSG_DSTWDG";
	case RMessage.RMSG_MAPIV:    return "RMSG_MAPIV";
	case RMessage.RMSG_GLOBLOB:  return "RMSG_GLOBLOB";
	case RMessage.RMSG_RESID:    return "RMSG_RESID";
	case RMessage.RMSG_SESSKEY:  return "RMSG_SESSKEY";
	case RMessage.RMSG_FRAGMENT: return "RMSG_FRAGMENT";
	case RMessage.RMSG_ADDWDG:   return "RMSG_ADDWDG";
	case RMessage.RMSG_WDGBAR:   return "RMSG_WDGBAR";
	case RMessage.RMSG_USERAGENT:return "RMSG_USERAGENT";
	default: return "RMSG_" + type;
	}
    }

    public static String odTypeName(int type) {
	switch(type) {
	case OCache.OD_REM:     return "OD_REM";
	case OCache.OD_MOVE:    return "OD_MOVE";
	case OCache.OD_RES:     return "OD_RES";
	case OCache.OD_LINBEG:  return "OD_LINBEG";
	case OCache.OD_LINSTEP: return "OD_LINSTEP";
	case OCache.OD_SPEECH:  return "OD_SPEECH";
	case OCache.OD_COMPOSE: return "OD_COMPOSE";
	case OCache.OD_ZOFF:    return "OD_ZOFF";
	case OCache.OD_LUMIN:   return "OD_LUMIN";
	case OCache.OD_AVATAR:  return "OD_AVATAR";
	case OCache.OD_FOLLOW:  return "OD_FOLLOW";
	case OCache.OD_HOMING:  return "OD_HOMING";
	case OCache.OD_OVERLAY: return "OD_OVERLAY";
	case OCache.OD_HEALTH:  return "OD_HEALTH";
	case OCache.OD_CMPPOSE: return "OD_CMPPOSE";
	case OCache.OD_CMPMOD:  return "OD_CMPMOD";
	case OCache.OD_CMPEQU:  return "OD_CMPEQU";
	case OCache.OD_ICON:    return "OD_ICON";
	case OCache.OD_RESATTR: return "OD_RESATTR";
	case OCache.OD_END:     return "OD_END";
	default: return "OD_" + type;
	}
    }

    public static String resName(Session sess, int resId) {
	if(sess == null) return "res:" + resId;
	try {
	    Indir<Resource> ir = sess.getres2(resId);
	    if(ir == null) return "res:" + resId;
	    Resource r = ir.get();
	    return r.name;
	} catch(Exception e) {
	    return "res:" + resId;
	}
    }

    public static String describeArgs(Object[] args) {
	if(args == null || args.length == 0) return "[]";
	StringBuilder sb = new StringBuilder("[");
	for(int i = 0; i < args.length; i++) {
	    if(i > 0) sb.append(", ");
	    sb.append(describeArg(args[i]));
	}
	sb.append("]");
	return sb.toString();
    }

    public static String describeArg(Object arg) {
	if(arg == null) return "nil";
	if(arg instanceof String) return "\"" + arg + "\"";
	if(arg instanceof Coord) return arg.toString();
	if(arg instanceof Coord2d) return arg.toString();
	if(arg instanceof Color) {
	    Color c = (Color)arg;
	    return String.format("Color(%d,%d,%d,%d)", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
	}
	if(arg instanceof byte[]) {
	    byte[] ba = (byte[])arg;
	    if(ba.length <= 16)
		return "bytes:" + Utils.hex.enc(ba);
	    else
		return "bytes[" + ba.length + "]:" + Utils.hex.enc(java.util.Arrays.copyOf(ba, 16)) + "...";
	}
	if(arg instanceof Object[]) return describeArgs((Object[])arg);
	return String.valueOf(arg);
    }

    public static ProtoEvent decodeRel(PMessage msg, Session sess) {
	double now = Utils.rtime();
	int size = msg.rt - msg.rh;
	int savedRh = msg.rh;
	ProtoEvent.Builder b = new ProtoEvent.Builder()
	    .timestamp(now)
	    .dir(ProtoEvent.Direction.IN)
	    .typeId(msg.type)
	    .typeName(rmsgTypeName(msg.type))
	    .sizeBytes(size);

	try {
	    switch(msg.type) {
	    case RMessage.RMSG_NEWWDG: {
		b.category(ProtoEvent.Category.WIDGET);
		int id = msg.int32();
		String type = msg.string();
		int parent = msg.int32();
		Object[] pargs = msg.list();
		Object[] cargs = msg.list();
		b.widgetId(id);
		b.summary(String.format("New widget #%d type=%s parent=#%d", id, type, parent));
		b.detail(String.format("Widget #%d\n  Type: %s\n  Parent: #%d\n  PArgs: %s\n  CArgs: %s",
				       id, type, parent, describeArgs(pargs), describeArgs(cargs)));
		break;
	    }
	    case RMessage.RMSG_WDGMSG: {
		b.category(ProtoEvent.Category.WIDGET);
		int id = msg.int32();
		String name = msg.string();
		Object[] args = msg.list();
		b.widgetId(id);
		b.summary(String.format("Widget #%d msg '%s' %s", id, name, describeArgs(args)));
		b.detail(String.format("Widget #%d\n  Message: %s\n  Args: %s", id, name, describeArgs(args)));
		break;
	    }
	    case RMessage.RMSG_DSTWDG: {
		b.category(ProtoEvent.Category.WIDGET);
		int id = msg.int32();
		b.widgetId(id);
		b.summary(String.format("Destroy widget #%d", id));
		b.detail(String.format("Destroy widget #%d", id));
		break;
	    }
	    case RMessage.RMSG_ADDWDG: {
		b.category(ProtoEvent.Category.WIDGET);
		int id = msg.int32();
		int parent = msg.int32();
		Object[] pargs = msg.list();
		b.widgetId(id);
		b.summary(String.format("Add widget #%d to parent #%d", id, parent));
		b.detail(String.format("Widget #%d\n  Parent: #%d\n  PArgs: %s", id, parent, describeArgs(pargs)));
		break;
	    }
	    case RMessage.RMSG_WDGBAR: {
		b.category(ProtoEvent.Category.WIDGET);
		List<Integer> ids = new ArrayList<>();
		while(!msg.eom()) {
		    int dep = msg.int32();
		    if(dep == -1) break;
		    ids.add(dep);
		}
		b.summary("Widget barrier " + ids);
		b.detail("Widget barrier\n  IDs: " + ids);
		break;
	    }
	    case RMessage.RMSG_MAPIV: {
		b.category(ProtoEvent.Category.MAP);
		b.summary("Map invalidation (" + size + " bytes)");
		b.detail("Map invalidation blob, " + size + " bytes");
		break;
	    }
	    case RMessage.RMSG_GLOBLOB: {
		b.category(ProtoEvent.Category.GLOB);
		b.summary("Global state blob (" + size + " bytes)");
		b.detail("Global state blob, " + size + " bytes");
		break;
	    }
	    case RMessage.RMSG_RESID: {
		b.category(ProtoEvent.Category.RESOURCE);
		int resid = msg.uint16();
		String resname = msg.string();
		int resver = msg.uint16();
		b.summary(String.format("Resource #%d = %s (v%d)", resid, resname, resver));
		b.detail(String.format("Resource mapping\n  ID: %d\n  Name: %s\n  Version: %d", resid, resname, resver));
		break;
	    }
	    case RMessage.RMSG_SESSKEY: {
		b.category(ProtoEvent.Category.SESSION);
		b.summary("Session key (" + size + " bytes)");
		b.detail("Session key exchange, " + size + " bytes");
		break;
	    }
	    case RMessage.RMSG_FRAGMENT: {
		b.category(ProtoEvent.Category.SESSION);
		b.summary("Message fragment (" + size + " bytes)");
		b.detail("Message fragment, " + size + " bytes");
		break;
	    }
	    case RMessage.RMSG_USERAGENT: {
		b.category(ProtoEvent.Category.SESSION);
		b.summary("User agent info (" + size + " bytes)");
		b.detail("User agent data, " + size + " bytes");
		break;
	    }
	    default: {
		b.category(ProtoEvent.Category.SESSION);
		byte[] raw = msg.bytes();
		String hex = (raw.length <= 32) ? Utils.hex.enc(raw) : Utils.hex.enc(java.util.Arrays.copyOf(raw, 32)) + "...";
		b.summary(String.format("Unknown RMSG type %d (%d bytes)", msg.type, size));
		b.detail(String.format("Unknown RMSG type %d\n  Size: %d bytes\n  Data: %s", msg.type, size, hex));
		break;
	    }
	    }
	} catch(Exception e) {
	    b.summary(String.format("%s (decode error: %s)", rmsgTypeName(msg.type), e.getMessage()));
	    b.detail(String.format("Decode error: %s\n  Type: %s\n  Size: %d bytes", e.getMessage(), rmsgTypeName(msg.type), size));
	} finally {
	    msg.rh = savedRh;
	}
	return b.build();
    }

    public static ProtoEvent decodeObjDelta(OCache.ObjDelta delta, Session sess) {
	double now = Utils.rtime();
	StringBuilder summary = new StringBuilder();
	StringBuilder detail = new StringBuilder();
	int totalSize = 0;

	summary.append(String.format("Gob %d frame=%d", delta.id, delta.frame));
	detail.append(String.format("Gob %d\n  Frame: %d\n  Flags: %s%s%s",
				    delta.id, delta.frame,
				    ((delta.fl & 2) != 0) ? "virtual " : "",
				    ((delta.fl & 4) != 0) ? "old " : "",
				    delta.rem ? "remove " : ""));
	if(delta.initframe > 0)
	    detail.append(String.format("\n  InitFrame: %d", delta.initframe));

	if(delta.rem) {
	    summary.append(" REMOVE");
	    detail.append("\n  Action: REMOVE");
	} else {
	    List<String> attrSummaries = new ArrayList<>();
	    for(OCache.AttrDelta attr : delta.attrs) {
		int savedRh = attr.rh;
		int attrSize = attr.rt - attr.rh;
		totalSize += attrSize;
		String attrName = odTypeName(attr.type);
		String attrDetail = decodeAttrDelta(attr, sess);
		attrSummaries.add(attrName);
		detail.append(String.format("\n  [%s] %s", attrName, attrDetail));
		attr.rh = savedRh;
	    }
	    if(!attrSummaries.isEmpty())
		summary.append(" ").append(String.join(",", attrSummaries));
	}

	return new ProtoEvent.Builder()
	    .timestamp(now)
	    .dir(ProtoEvent.Direction.IN)
	    .category(ProtoEvent.Category.OBJECT)
	    .typeName("OBJDATA")
	    .typeId(-1)
	    .summary(summary.toString())
	    .detail(detail.toString())
	    .sizeBytes(totalSize)
	    .gobId(delta.id)
	    .build();
    }

    private static String decodeAttrDelta(OCache.AttrDelta attr, Session sess) {
	try {
	    switch(attr.type) {
	    case OCache.OD_MOVE: {
		int x = attr.int32();
		int y = attr.int32();
		int ia = attr.uint16();
		return String.format("pos=(%d,%d) angle=%d", x, y, ia);
	    }
	    case OCache.OD_RES: {
		int resid = attr.uint16();
		if(resid == 65535) {
		    return "res=nil";
		} else {
		    return "res=" + resName(sess, resid);
		}
	    }
	    case OCache.OD_LINBEG: {
		int sx = attr.int32();
		int sy = attr.int32();
		int tx = attr.int32();
		int ty = attr.int32();
		return String.format("from=(%d,%d) to=(%d,%d)", sx, sy, tx, ty);
	    }
	    case OCache.OD_LINSTEP: {
		int w = attr.int32();
		if(w == -1) return "done";
		return String.format("w=%d", w);
	    }
	    case OCache.OD_SPEECH: {
		float zo = attr.int16() / 100.0f;
		String text = attr.string();
		return String.format("zo=%.1f text=\"%s\"", zo, text);
	    }
	    case OCache.OD_COMPOSE: {
		int resid = attr.uint16();
		return "res=" + resName(sess, resid);
	    }
	    case OCache.OD_ZOFF: {
		int off = attr.int16();
		return "zoff=" + off;
	    }
	    case OCache.OD_LUMIN: {
		int x = attr.int32();
		int y = attr.int32();
		int sz = attr.uint16();
		int str = attr.uint8();
		return String.format("pos=(%d,%d) sz=%d str=%d", x, y, sz, str);
	    }
	    case OCache.OD_AVATAR: {
		List<String> layers = new ArrayList<>();
		while(true) {
		    int resid = attr.uint16();
		    if(resid == 65535) break;
		    layers.add(resName(sess, resid));
		}
		return "layers=" + layers;
	    }
	    case OCache.OD_FOLLOW: {
		long oid = attr.uint32();
		if(oid == 0xffffffffL)
		    return "follow=nil";
		int xoff = attr.int32();
		int yoff = attr.int32();
		return String.format("follow=%d off=(%d,%d)", oid, xoff, yoff);
	    }
	    case OCache.OD_HOMING: {
		long oid = attr.uint32();
		if(oid == 0xffffffffL)
		    return "homing=nil";
		int tx = attr.int32();
		int ty = attr.int32();
		int v = attr.int32();
		return String.format("target=%d pos=(%d,%d) v=%d", oid, tx, ty, v);
	    }
	    case OCache.OD_OVERLAY: {
		int olid = attr.int32();
		boolean prs = (olid & 1) != 0;
		olid >>= 1;
		int resid = attr.uint16();
		if(resid == 65535)
		    return String.format("ol=%d REMOVE", olid);
		return String.format("ol=%d res=%s prs=%b", olid, resName(sess, resid), prs);
	    }
	    case OCache.OD_HEALTH: {
		int hp = attr.uint8();
		return "hp=" + hp;
	    }
	    case OCache.OD_ICON: {
		int resid = attr.uint16();
		if(resid == 65535)
		    return "icon=nil";
		return "icon=" + resName(sess, resid);
	    }
	    case OCache.OD_RESATTR: {
		int resid = attr.uint16();
		int len = attr.uint8();
		if(len > 0) {
		    return String.format("resattr=%s len=%d", resName(sess, resid), len);
		} else {
		    return String.format("resattr=%s REMOVE", resName(sess, resid));
		}
	    }
	    case OCache.OD_REM:
		return "remove";
	    case OCache.OD_CMPPOSE:
	    case OCache.OD_CMPMOD:
	    case OCache.OD_CMPEQU: {
		int bytes = attr.rt - attr.rh;
		return bytes + " bytes";
	    }
	    default: {
		int bytes = attr.rt - attr.rh;
		return bytes + " bytes (raw)";
	    }
	    }
	} catch(Exception e) {
	    return "decode error: " + e.getMessage();
	}
    }

    public static ProtoEvent decodeMapData(Message msg) {
	double now = Utils.rtime();
	int size = msg.rt - msg.rh;
	return new ProtoEvent.Builder()
	    .timestamp(now)
	    .dir(ProtoEvent.Direction.IN)
	    .category(ProtoEvent.Category.MAP)
	    .typeName("MAPDATA")
	    .typeId(Session.MSG_MAPDATA)
	    .summary(String.format("Map data (%d bytes)", size))
	    .detail(String.format("Map data\n  Size: %d bytes", size))
	    .sizeBytes(size)
	    .build();
    }

    public static ProtoEvent decodeOutgoing(int widgetId, String name, Object[] args) {
	double now = Utils.rtime();
	String argStr = describeArgs(args);
	return new ProtoEvent.Builder()
	    .timestamp(now)
	    .dir(ProtoEvent.Direction.OUT)
	    .category(ProtoEvent.Category.WIDGET)
	    .typeName("WDGMSG_OUT")
	    .typeId(RMessage.RMSG_WDGMSG)
	    .summary(String.format("Widget #%d msg '%s' %s", widgetId, name, argStr))
	    .detail(String.format("Outgoing widget message\n  Widget: #%d\n  Message: %s\n  Args: %s", widgetId, name, argStr))
	    .sizeBytes(0)
	    .widgetId(widgetId)
	    .build();
    }
}
