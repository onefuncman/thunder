/*
 * Decompiled with CFR 0.152.
 */
package haven;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import haven.Coord;
import haven.Coord2d;
import haven.Message;
import haven.MessageBuf;
import haven.MessageInputStream;
import haven.Utils;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.imageio.ImageIO;

public class Resource {
    static final String SIG = "Haven Resource 1";
    static final byte[] BSIG = new byte[]{72, 97, 118, 101, 110, 32, 82, 101, 115, 111, 117, 114, 99, 101, 32, 49};
    public static String OUT = "dout/";
    private static final String END = "\r\n";
    private static Map<String, Class<? extends Layer>> ltypes = new TreeMap<String, Class<? extends Layer>>();
    public static Class<Image> imgc = Image.class;
    public static Class<Tile> tile = Tile.class;
    public static Class<Neg> negc = Neg.class;
    public static Class<Anim> animc = Anim.class;
    public static Class<Tileset> tileset = Tileset.class;
    public static Class<Pagina> pagina = Pagina.class;
    public static Class<AButton> action = AButton.class;
    public static Class<Audio> audio = Audio.class;
    public static Class<Tooltip> tooltip = Tooltip.class;
    static int TYPES = 0;
    static final int IMAGE = TYPES++;
    static final int TILE = TYPES++;
    static final int NEG = TYPES++;
    static final int OBST = TYPES++;
    static final int ANIM = TYPES++;
    static final int TILESET = TYPES++;
    static final int PAGINA = TYPES++;
    static final int ABUTTON = TYPES++;
    static final int AUDIO = TYPES++;
    static final int TOOLTIP = TYPES++;
    static final int MUSIC = TYPES++;
    static final int CODE = TYPES++;
    static final int CODEENTRY = TYPES++;
    static final int SOURCES = TYPES++;
    static final int OVERLAY = TYPES++;
    static final int MAT2 = TYPES++;
    private Collection<Layer> layers = new LinkedList<Layer>();
    public final String out;
    public final String name;
    public int ver;

    public static Coord cdec(Message buf) {
        return new Coord(buf.int16(), buf.int16());
    }

    public static Coord cdec(byte[] buf, int off) {
        return new Coord(Utils.int16d(buf, off), Utils.int16d(buf, off + 2));
    }

    public static BufferedImage readimage(final InputStream fp) throws IOException {
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<BufferedImage>(){

                @Override
                public BufferedImage run() throws IOException {
                    BufferedImage ret = ImageIO.read(fp);
                    if (ret == null) {
                        throw new ImageReadException();
                    }
                    return ret;
                }
            });
        }
        catch (PrivilegedActionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) {
                throw (IOException)c;
            }
            throw new AssertionError((Object)c);
        }
    }

    public Resource(String full, String name, String out, boolean w) throws Exception {
        this.out = out;
        this.name = name;
        if (w) {
            this.load(new FileInputStream(new File(full)));
        } else {
            this.loadfromdecode(full);
        }
    }

    public Resource(String full, String name, boolean w) throws Exception {
        this(full, name, OUT, w);
    }

    private void readall(InputStream in, byte[] buf) throws IOException {
        int ret;
        for (int off = 0; off < buf.length; off += ret) {
            ret = in.read(buf, off, buf.length - off);
            if (ret >= 0) continue;
            throw new LoadException("Incomplete resource at " + this.name, this);
        }
    }

    private void load(InputStream in) throws Exception {
        byte[] buf = new byte[SIG.length()];
        this.readall(in, buf);
        if (!SIG.equals(new String(buf))) {
            throw new LoadException("Invalid res signature", this);
        }
        buf = new byte[2];
        this.readall(in, buf);
        this.ver = Utils.uint16d(buf, 0);
        LinkedList<Layer> layers = new LinkedList<Layer>();
        block6: while (true) {
            Layer l;
            Constructor<? extends Layer> cons;
            int n;
            StringBuilder tbuf = new StringBuilder();
            while (true) {
                int ib;
                if ((ib = in.read()) == -1) {
                    if (tbuf.length() == 0) break block6;
                    throw new LoadException("Incomplete resource at " + this.name, this);
                }
                n = ib;
                if (n == 0) break;
                tbuf.append((char)n);
            }
            buf = new byte[4];
            this.readall(in, buf);
            n = Utils.int32d(buf, 0);
            buf = new byte[n];
            this.readall(in, buf);
            String layerName = tbuf.toString();
            Class<? extends Layer> lc = ltypes.get(layerName);
            if (lc == null) {
                System.out.println(String.format("Couldn't find  layer class for '%s'", layerName));
                continue;
            }
            try {
                cons = lc.getConstructor(Resource.class, byte[].class);
            }
            catch (NoSuchMethodException e) {
                throw new LoadException(e, this);
            }
            try {
                l = cons.newInstance(this, buf);
            }
            catch (InstantiationException e) {
                throw new LoadException(e, this);
            }
            catch (InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof RuntimeException) {
                    throw (RuntimeException)c;
                }
                throw new LoadException(c, this);
            }
            catch (IllegalAccessException e) {
                throw new LoadException(e, this);
            }
            layers.add(l);
        }
        this.layers = layers;
        for (Layer layer : layers) {
            layer.init();
        }
    }

    public void decodeall() throws Exception {
        String base = this.out + this.name;
        new File(base).mkdirs();
        int[] c = new int[TYPES];
        for (int i = 0; i < TYPES; ++i) {
            c[i] = 0;
        }
        for (Layer layer : this.layers) {
            int n = layer.type();
            int n2 = c[n];
            c[n] = n2 + 1;
            layer.decode(base, n2);
        }
        BufferedWriter bw = new BufferedWriter(new FileWriter(base + "/meta"));
        bw.write("#General info for res " + base + END);
        bw.write("#int16 ver\r\n");
        bw.write(Integer.toString(this.ver) + END);
        bw.flush();
        bw.close();
    }

    private void loadfromdecode(String full) throws Exception {
        if (!full.endsWith(".res")) {
            throw new Exception("Invalid decoded res directory");
        }
        File f = new File(full);
        if (!f.isDirectory()) {
            throw new Exception("Invalid decoded res directory");
        }
        File[] l = f.listFiles();
        Arrays.sort(l, (a, b) -> a.getName().compareTo(b.getName()));
        LinkedList<Layer> layers = new LinkedList<Layer>();
        block40: for (int i = 0; i < l.length; ++i) {
            String n;
            Class<? extends Layer> lc;
            if (!l[i].isDirectory() || (lc = ltypes.get(n = l[i].getName())) == null) continue;
            File[] df = l[i].listFiles();
            Arrays.sort(df, (a, b) -> a.getName().compareTo(b.getName()));
            switch (n) {
                case "image": 
                case "tile": {
                    int j;
                    Constructor<? extends Layer> cons;
                    if (df.length % 2 != 0) {
                        throw new Exception("Invalid number of decoded files for " + n);
                    }
                    try {
                        cons = lc.getConstructor(Resource.class, File.class, File.class);
                    }
                    catch (NoSuchMethodException e) {
                        throw new LoadException(e, this);
                    }
                    for (j = 0; j < df.length - 1; ++j) {
                        if (!df[j].getName().endsWith(".data") && !df[j + 1].getName().endsWith(".png")) continue;
                        layers.add(cons.newInstance(this, df[j++], df[j]));
                    }
                    continue block40;
                }
                case "code": {
                    int j;
                    Constructor<? extends Layer> cons;
                    if (df.length % 2 != 0) {
                        throw new Exception("Invalid number of decoded files for " + n);
                    }
                    try {
                        cons = lc.getConstructor(Resource.class, File.class, File.class);
                    }
                    catch (NoSuchMethodException e) {
                        throw new LoadException(e, this);
                    }
                    for (j = 0; j < df.length - 1; j += 2) {
                        if (df[j].getName().endsWith(".data")) {
                            layers.add(cons.newInstance(this, df[j], df[j + 1]));
                            continue;
                        }
                        if (!df[j].getName().endsWith(".class")) continue;
                        layers.add(cons.newInstance(this, df[j + 1], df[j]));
                    }
                    continue block40;
                }
                case "neg": 
                case "anim": 
                case "tooltip": 
                case "tileset": 
                case "codeentry": 
                case "pagina": 
                case "overlay": 
                case "action": {
                    int j;
                    Constructor<? extends Layer> cons;
                    try {
                        cons = lc.getConstructor(Resource.class, File.class);
                    }
                    catch (NoSuchMethodException e) {
                        throw new LoadException(e, this);
                    }
                    for (j = 0; j < df.length; ++j) {
                        if (!df[j].getName().endsWith(".data")) continue;
                        layers.add(cons.newInstance(this, df[j]));
                    }
                    continue block40;
                }
                case "mat2": {
                    int j;
                    Constructor<? extends Layer> cons;
                    try {
                        cons = lc.getConstructor(Resource.class, File.class);
                    }
                    catch (NoSuchMethodException e) {
                        throw new LoadException(e, this);
                    }
                    for (j = 0; j < df.length; ++j) {
                        if (!df[j].getName().endsWith(".json")) continue;
                        layers.add(cons.newInstance(this, df[j]));
                    }
                    continue block40;
                }
                case "midi": {
                    int j;
                    Constructor<? extends Layer> cons;
                    try {
                        cons = lc.getConstructor(Resource.class, File.class);
                    }
                    catch (NoSuchMethodException e) {
                        throw new LoadException(e, this);
                    }
                    for (j = 0; j < df.length; ++j) {
                        if (!df[j].getName().endsWith(".midi")) continue;
                        layers.add(cons.newInstance(this, df[j]));
                    }
                    continue block40;
                }
                case "audio": {
                    int j;
                    Constructor<? extends Layer> cons;
                    try {
                        cons = lc.getConstructor(Resource.class, File.class);
                    }
                    catch (NoSuchMethodException e) {
                        throw new LoadException(e, this);
                    }
                    for (j = 0; j < df.length; ++j) {
                        if (!df[j].getName().endsWith(".ogg")) continue;
                        layers.add(cons.newInstance(this, df[j]));
                    }
                    continue block40;
                }
                case "src": {
                    int j;
                    Constructor<? extends Layer> cons;
                    try {
                        cons = lc.getConstructor(Resource.class, File.class);
                    }
                    catch (NoSuchMethodException e) {
                        throw new LoadException(e, this);
                    }
                    for (j = 0; j < df.length; ++j) {
                        if (!df[j].getName().endsWith(".java")) continue;
                        layers.add(cons.newInstance(this, df[j]));
                    }
                    continue block40;
                }
            }
        }
        this.layers = layers;
        BufferedReader br = new BufferedReader(new FileReader(full + "/meta"));
        this.ver = Utils.rnint(br);
        br.close();
    }

    public void encodeall() throws Exception {
        File f = new File(this.out + this.name);
        f.mkdirs();
        f.delete();
        f.createNewFile();
        FileOutputStream fos = new FileOutputStream(f);
        byte[] buf = BSIG;
        fos.write(buf);
        buf = Utils.byte_int16d(this.ver);
        fos.write(buf);
        for (Layer layer : this.layers) {
            fos.write(layer.type_buffer());
            fos.write(Utils.byte_int32d(layer.size()));
            layer.encode(fos);
        }
        fos.flush();
        fos.close();
    }

    static {
        ltypes.put("image", Image.class);
        ltypes.put("tooltip", Tooltip.class);
        ltypes.put("tile", Tile.class);
        ltypes.put("neg", Neg.class);
        ltypes.put("obst", Obst.class);
        ltypes.put("anim", Anim.class);
        ltypes.put("tileset", Tileset.class);
        ltypes.put("pagina", Pagina.class);
        ltypes.put("action", AButton.class);
        ltypes.put("code", Code.class);
        ltypes.put("codeentry", CodeEntry.class);
        ltypes.put("audio", Audio.class);
        ltypes.put("midi", Music.class);
        ltypes.put("src", Sources.class);
        ltypes.put("overlay", Overlay.class);
        ltypes.put("mat2", NewMat.class);
    }

    public class NewMat
    extends Layer {
        Data data;

        public NewMat(byte[] bytes) {
            this.data = new Data();
            MessageBuf buf = new MessageBuf(bytes);
            this.data.id = buf.uint16();
            while (!buf.eom()) {
                this.data.mats.put(buf.string(), buf.list());
            }
        }

        public NewMat(File src) throws Exception {
            this.data = new Data();
            FileInputStream fis = new FileInputStream(src);
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)fis, StandardCharsets.UTF_8));
            Gson gson = new GsonBuilder().create();
            this.data = gson.fromJson((Reader)br, Data.class);
            fis.close();
        }

        @Override
        public void init() {
        }

        @Override
        public int size() {
            return this.bytes().length;
        }

        @Override
        public int type() {
            return MAT2;
        }

        public byte[] bytes() {
            MessageBuf buf = new MessageBuf();
            buf.adduint16(this.data.id);
            byte[] bytes = buf.fin();
            return bytes;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{109, 97, 116, 50, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/mat2/mat2_" + i + ".json");
            new File(res + "/mat2/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), StandardCharsets.UTF_8));
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            bw.write(gson.toJson(this.data));
            bw.flush();
            bw.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(this.bytes());
        }

        private class Data {
            int id;
            Map<String, Object[]> mats = new HashMap<String, Object[]>();

            private Data() {
            }
        }
    }

    public class Overlay
    extends Layer {
        Collection<String> tags;
        int matid;
        int omatid;

        public Overlay(byte[] bytes) {
            this.matid = -1;
            this.omatid = -1;
            MessageBuf buf = new MessageBuf(bytes);
            int ver = buf.uint8();
            if (ver == 1) {
                Object[] data;
                int matid = 0;
                int omatid = -1;
                List tags = Collections.emptyList();
                block10: for (Object argp : data = buf.list()) {
                    Object[] arg = (Object[])argp;
                    switch ((String)arg[0]) {
                        case "tags": {
                            ArrayList tbuf = new ArrayList();
                            for (int i = 1; i < arg.length; ++i) {
                                tbuf.add(((String)arg[i]).intern());
                            }
                            tbuf.trimToSize();
                            tags = tbuf;
                            continue block10;
                        }
                        case "mat": {
                            matid = (Integer)arg[1];
                            continue block10;
                        }
                        case "omat": {
                            omatid = (Integer)arg[1];
                        }
                    }
                }
                this.matid = matid;
                this.omatid = omatid;
                this.tags = tags;
            }
        }

        public Overlay(File src) throws Exception {
            String line;
            this.matid = -1;
            this.omatid = -1;
            FileInputStream fis = new FileInputStream(src);
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)fis, StandardCharsets.UTF_8));
            this.matid = Utils.rnint(br);
            this.omatid = Utils.rnint(br);
            this.tags = new LinkedList<String>();
            while ((line = Utils.rstr(br)) != null) {
                this.tags.add(line);
            }
            fis.close();
        }

        @Override
        public void init() {
        }

        @Override
        public int size() {
            return this.bytes().length;
        }

        @Override
        public int type() {
            return OVERLAY;
        }

        public byte[] bytes() {
            MessageBuf buf = new MessageBuf();
            Object[] tags = new Object[this.tags.size() + 1];
            tags[0] = "tags";
            int i = 1;
            for (String tag : this.tags) {
                tags[i] = tag;
                ++i;
            }
            ArrayList<Object[]> data = new ArrayList<Object[]>(3);
            data.add(tags);
            if (this.matid != 0) {
                data.add(new Object[]{"mat", this.matid});
            }
            if (this.omatid != -1) {
                data.add(new Object[]{"omat", this.omatid});
            }
            Object[] args = data.toArray();
            buf.addlist(args);
            byte[] bytes = buf.fin();
            return bytes;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{111, 118, 101, 114, 108, 97, 121, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/overlay/overlay_" + i + ".data");
            new File(res + "/overlay/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), StandardCharsets.UTF_8));
            bw.write("#OVERLAY LAYER FOR RES " + res + Resource.END);
            bw.write("#Byte matid\r\n");
            bw.write(Integer.toString(this.matid) + Resource.END);
            bw.write("#Byte omatid\r\n");
            bw.write(Integer.toString(this.omatid) + Resource.END);
            bw.write("#string[] tags\r\n");
            for (String tag : this.tags) {
                bw.write(tag + Resource.END);
            }
            bw.flush();
            bw.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(this.bytes());
        }
    }

    public class Sources
    extends Layer {
        byte[] raw;

        public Sources(byte[] buf) {
            this.raw = new byte[buf.length];
            for (int i = 0; i < buf.length; ++i) {
                this.raw[i] = buf[i];
            }
        }

        public Sources(File src) throws Exception {
            FileInputStream fis = new FileInputStream(src);
            this.raw = new byte[(int)src.length()];
            fis.read(this.raw);
            fis.close();
        }

        @Override
        public void init() {
        }

        @Override
        public int size() {
            return this.raw.length;
        }

        @Override
        public int type() {
            return SOURCES;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{115, 114, 99, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/src/src_" + i + ".java");
            new File(res + "/src/").mkdirs();
            f.createNewFile();
            FileOutputStream fout = new FileOutputStream(f);
            fout.write(this.raw);
            fout.flush();
            fout.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(this.raw);
        }
    }

    public class Music
    extends Layer {
        byte[] raw;

        public Music(byte[] buf) {
            this.raw = new byte[buf.length];
            for (int i = 0; i < buf.length; ++i) {
                this.raw[i] = buf[i];
            }
        }

        public Music(File midi) throws Exception {
            FileInputStream fis = new FileInputStream(midi);
            this.raw = new byte[(int)midi.length()];
            fis.read(this.raw);
            fis.close();
        }

        @Override
        public int size() {
            return this.raw.length;
        }

        @Override
        public int type() {
            return MUSIC;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{109, 105, 100, 105, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/midi/midi_" + i + ".midi");
            new File(res + "/midi/").mkdirs();
            f.createNewFile();
            FileOutputStream fout = new FileOutputStream(f);
            fout.write(this.raw);
            fout.flush();
            fout.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(this.raw);
        }

        @Override
        public void init() {
        }
    }

    public class Audio
    extends Layer {
        byte[] raw;

        public Audio(byte[] buf) {
            this.raw = new byte[buf.length];
            for (int i = 0; i < buf.length; ++i) {
                this.raw[i] = buf[i];
            }
        }

        public Audio(File ogg) throws Exception {
            FileInputStream fis = new FileInputStream(ogg);
            this.raw = new byte[(int)ogg.length()];
            fis.read(this.raw);
            fis.close();
        }

        @Override
        public int size() {
            return this.raw.length;
        }

        @Override
        public int type() {
            return AUDIO;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{97, 117, 100, 105, 111, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/audio/audio_" + i + ".ogg");
            new File(res + "/audio/").mkdirs();
            f.createNewFile();
            FileOutputStream fout = new FileOutputStream(f);
            fout.write(this.raw);
            fout.flush();
            fout.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(this.raw);
        }

        @Override
        public void init() {
        }
    }

    public class CodeEntry
    extends Layer {
        private int size;
        private ArrayList<String> key;
        private ArrayList<String> value;
        private Map<String, Integer> requires;

        public CodeEntry(byte[] buf) {
            this.size = 0;
            this.key = new ArrayList();
            this.value = new ArrayList();
            this.requires = new HashMap<String, Integer>();
            MessageBuf msg = new MessageBuf(buf);
            block0: while (!msg.eom()) {
                int t = msg.uint8();
                if (t == 1) {
                    while (true) {
                        String en = msg.string();
                        String cn = msg.string();
                        if (en.length() == 0) continue block0;
                        this.key.add(en);
                        this.value.add(cn);
                    }
                }
                if (t == 2) {
                    String ln;
                    while ((ln = msg.string()).length() != 0) {
                        int ver = msg.uint16();
                        this.requires.put(ln, ver);
                    }
                    continue;
                }
                throw new LoadException("Unknown codeentry data type: " + t, Resource.this);
            }
        }

        public CodeEntry(File data) throws Exception {
            String len;
            String t;
            this.size = 0;
            this.key = new ArrayList();
            this.value = new ArrayList();
            this.requires = new HashMap<String, Integer>();
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(data), "UTF-8"));
            int s = Utils.rnint(br);
            if (s > 0) {
                ++this.size;
                for (int j = 0; j < s; ++j) {
                    t = Utils.rnstr(br);
                    this.key.add(t);
                    this.size += Utils.byte_strd(t).length;
                    t = Utils.rnstr(br);
                    this.value.add(t);
                    this.size += Utils.byte_strd(t).length;
                }
                this.size += 2;
            }
            if ((len = Utils.rnstr(br)) != null) {
                s = Integer.parseInt(len);
                ++this.size;
                for (int i = 0; i < s; ++i) {
                    t = Utils.rnstr(br);
                    this.size += Utils.byte_strd(t).length;
                    int v = Utils.rnint(br);
                    this.size += 2;
                    this.requires.put(t, v);
                }
                ++this.size;
            }
            br.close();
        }

        @Override
        public int size() {
            return this.size;
        }

        @Override
        public int type() {
            return CODEENTRY;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{99, 111, 100, 101, 101, 110, 116, 114, 121, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/codeentry/codeentry_" + i + ".data");
            new File(res + "/codeentry/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), "UTF-8"));
            bw.write("#CODEENTRY LAYER FOR RES " + res + Resource.END);
            bw.write("#int32 length\r\n");
            bw.write(this.key.size() + Resource.END);
            for (int j = 0; j < this.key.size(); ++j) {
                bw.write("#String key[" + j + "]" + Resource.END);
                bw.write(this.key.get(j).replace("\n", "\\n") + Resource.END);
                bw.write("#String value[" + j + "]" + Resource.END);
                bw.write(this.value.get(j).replace("\n", "\\n") + Resource.END);
            }
            if (!this.requires.isEmpty()) {
                bw.write("#start of requirements\r\n");
                bw.write("#int32 length\r\n");
                Set<Map.Entry<String, Integer>> entries = this.requires.entrySet();
                bw.write(entries.size() + Resource.END);
                for (Map.Entry<String, Integer> e : entries) {
                    bw.write("#String resource\r\n");
                    bw.write(e.getKey().replace("\n", "\\n") + Resource.END);
                    bw.write("#uint16 version\r\n");
                    bw.write(e.getValue() + Resource.END);
                }
            }
            bw.flush();
            bw.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            if (this.key.size() > 0) {
                out.write(1);
                for (int i = 0; i < this.key.size(); ++i) {
                    out.write(Utils.byte_strd(this.key.get(i)));
                    out.write(Utils.byte_strd(this.value.get(i)));
                }
                out.write(0);
                out.write(0);
            }
            if (!this.requires.isEmpty()) {
                out.write(2);
                for (Map.Entry<String, Integer> e : this.requires.entrySet()) {
                    out.write(Utils.byte_strd(e.getKey()));
                    out.write(Utils.byte_int16d(e.getValue()));
                }
                out.write(0);
            }
        }

        @Override
        public void init() {
        }
    }

    public class Code
    extends Layer {
        public final String name;
        public final transient byte[] data;
        private int size;

        public Code(byte[] buf) {
            this.size = 0;
            int[] off = new int[]{0};
            this.name = Utils.strd(buf, off);
            this.data = new byte[buf.length - off[0]];
            System.arraycopy(buf, off[0], this.data, 0, this.data.length);
        }

        public Code(File dat, File clas) throws Exception {
            this.size = 0;
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(dat), "UTF-8"));
            this.name = Utils.rnstr(br);
            this.size = Utils.byte_strd(this.name).length;
            byte[] tmp = Utils.readBytes(clas);
            if (!Utils.isJavaClass(tmp)) {
                clas = new File(clas.getParentFile() + File.separator + new String(tmp));
                tmp = Utils.readBytes(clas);
            }
            this.data = tmp;
            br.close();
        }

        @Override
        public int size() {
            return this.size + this.data.length;
        }

        @Override
        public int type() {
            return CODE;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{99, 111, 100, 101, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/code/code_" + i + ".data");
            new File(res + "/code/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), "UTF-8"));
            bw.write("#CODE LAYER FOR RES " + res + Resource.END);
            bw.write("#String class_name\r\n");
            bw.write("#Note: the .class file will have the same name as this file\r\n");
            bw.write(this.name.replace("\n", "\\n") + Resource.END);
            bw.flush();
            bw.close();
            f = new File(res + "/code/code_" + i + ".class");
            FileOutputStream fout = new FileOutputStream(f);
            fout.write(this.data);
            fout.flush();
            fout.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(Utils.byte_strd(this.name));
            out.write(this.data);
        }

        @Override
        public void init() {
        }
    }

    public class AButton
    extends Layer {
        public final String name;
        public final String preq;
        public final char hk;
        public final String[] ad;
        int adl;
        int pver;
        String pr;
        int size;

        public AButton(byte[] buf) {
            this.size = 0;
            int[] off = new int[]{0};
            this.pr = Utils.strd(buf, off);
            this.pver = Utils.uint16d(buf, off[0]);
            off[0] = off[0] + 2;
            this.name = Utils.strd(buf, off);
            this.preq = Utils.strd(buf, off);
            this.hk = (char)Utils.uint16d(buf, off[0]);
            off[0] = off[0] + 2;
            this.adl = Utils.uint16d(buf, off[0]);
            this.ad = new String[this.adl];
            off[0] = off[0] + 2;
            for (int i = 0; i < this.ad.length; ++i) {
                this.ad[i] = Utils.strd(buf, off);
            }
        }

        public AButton(File data) throws Exception {
            this.size = 0;
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(data), "UTF-8"));
            this.pr = Utils.rnstr(br);
            this.size = Utils.byte_strd(this.pr).length;
            this.pver = Utils.rnint(br);
            this.name = Utils.rnstr(br);
            this.size += Utils.byte_strd(this.name).length;
            this.preq = Utils.rnstr(br);
            this.size += Utils.byte_strd(this.preq).length;
            this.hk = (char)Utils.rnint(br);
            this.ad = new String[Utils.rnint(br)];
            for (int j = 0; j < this.ad.length; ++j) {
                this.ad[j] = Utils.rnstr(br);
                this.size += Utils.byte_strd(this.ad[j]).length;
            }
            br.close();
        }

        @Override
        public int size() {
            return this.size + 6;
        }

        @Override
        public int type() {
            return ABUTTON;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{97, 99, 116, 105, 111, 110, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/action/action_" + i + ".data");
            new File(res + "/action/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), "UTF-8"));
            bw.write("#ABUTTON LAYER FOR RES " + res + Resource.END);
            bw.write("#String pr\r\n");
            bw.write(this.pr.replace("\n", "\\n") + Resource.END);
            bw.write("#uint16 pver\r\n");
            bw.write(Integer.toString(this.pver) + Resource.END);
            bw.write("#String name\r\n");
            bw.write(this.name.replace("\n", "\\n") + Resource.END);
            bw.write("#String preq\r\n");
            bw.write(this.preq.replace("\n", "\\n") + Resource.END);
            bw.write("#uint16 hk\r\n");
            bw.write(Integer.toString(this.hk) + Resource.END);
            bw.write("#uint16 ad length\r\n");
            bw.write(Integer.toString(this.adl) + Resource.END);
            for (int j = 0; j < this.adl; ++j) {
                bw.write("#String ad[" + j + "]" + Resource.END);
                bw.write(this.ad[j].replace("\n", "\\n") + Resource.END);
            }
            bw.flush();
            bw.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(Utils.byte_strd(this.pr));
            out.write(Utils.byte_int16d(this.pver));
            out.write(Utils.byte_strd(this.name));
            out.write(Utils.byte_strd(this.preq));
            out.write(Utils.byte_int16d(this.hk));
            out.write(Utils.byte_int16d(this.ad.length));
            for (int j = 0; j < this.ad.length; ++j) {
                out.write(Utils.byte_strd(this.ad[j]));
            }
        }

        @Override
        public void init() {
        }
    }

    public class Pagina
    extends Layer {
        public final String text;
        private int size;

        public Pagina(byte[] buf) {
            this.size = 0;
            try {
                this.text = new String(buf, "UTF-8");
            }
            catch (UnsupportedEncodingException e) {
                throw new LoadException(e, Resource.this);
            }
        }

        public Pagina(File data) throws Exception {
            this.size = 0;
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(data), "UTF-8"));
            this.text = Utils.rnstr(br);
            this.size = Utils.byte_strd(this.text).length;
            br.close();
        }

        @Override
        public int size() {
            return this.size;
        }

        @Override
        public int type() {
            return PAGINA;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{112, 97, 103, 105, 110, 97, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/pagina/pagina_" + i + ".data");
            new File(res + "/pagina/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), "UTF-8"));
            bw.write("#PAGINA LAYER FOR RES " + res + Resource.END);
            bw.write("#String text\r\n");
            bw.write(this.text.replace("\n", "\\n") + Resource.END);
            bw.flush();
            bw.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(Utils.byte_strd(this.text));
        }

        @Override
        public void init() {
        }
    }

    public class Tileset
    extends Layer {
        private int fl;
        private String[] fln;
        private int[] flv;
        private int[] flw;
        int flnum;
        int flavprob;
        private int size;

        public Tileset(byte[] buf) {
            this.size = 0;
            int[] off = new int[]{0};
            int n = off[0];
            off[0] = n + 1;
            this.fl = Utils.ub(buf[n]);
            this.flnum = Utils.uint16d(buf, off[0]);
            off[0] = off[0] + 2;
            this.flavprob = Utils.uint16d(buf, off[0]);
            off[0] = off[0] + 2;
            this.fln = new String[this.flnum];
            this.flv = new int[this.flnum];
            this.flw = new int[this.flnum];
            for (int i = 0; i < this.flnum; ++i) {
                this.fln[i] = Utils.strd(buf, off);
                this.flv[i] = Utils.uint16d(buf, off[0]);
                off[0] = off[0] + 2;
                int n2 = off[0];
                off[0] = n2 + 1;
                this.flw[i] = Utils.ub(buf[n2]);
            }
        }

        public Tileset(File data) throws Exception {
            this.size = 0;
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(data), "UTF-8"));
            this.fl = Utils.rnint(br);
            this.flnum = Utils.rnint(br);
            this.flavprob = Utils.rnint(br);
            this.fln = new String[this.flnum];
            this.flv = new int[this.flnum];
            this.flw = new int[this.flnum];
            this.size = 5;
            for (int j = 0; j < this.flnum; ++j) {
                this.fln[j] = Utils.rnstr(br);
                this.size += Utils.byte_strd(this.fln[j]).length;
                this.flv[j] = Utils.rnint(br);
                this.flw[j] = Utils.rnint(br);
                this.size += 3;
            }
            br.close();
        }

        @Override
        public int size() {
            return this.size;
        }

        @Override
        public int type() {
            return TILESET;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{116, 105, 108, 101, 115, 101, 116, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/tileset/tileset_" + i + ".data");
            new File(res + "/tileset/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), "UTF-8"));
            bw.write("#TILESET LAYER FOR RES " + res + Resource.END);
            bw.write("#Byte fl\r\n");
            bw.write(Integer.toString(this.fl) + Resource.END);
            bw.write("#uint16 flnum\r\n");
            bw.write(Integer.toString(this.flnum) + Resource.END);
            bw.write("#uint16 flavprob\r\n");
            bw.write(Integer.toString(this.flavprob) + Resource.END);
            for (int j = 0; j < this.flnum; ++j) {
                bw.write("#String fln[" + j + "]" + Resource.END);
                bw.write(this.fln[j].replace("\n", "\\n") + Resource.END);
                bw.write("#uint16d flv[" + j + "]" + Resource.END);
                bw.write(Integer.toString(this.flv[j]) + Resource.END);
                bw.write("#byte flw[" + j + "]" + Resource.END);
                bw.write(Integer.toString(this.flw[j]) + Resource.END);
            }
            bw.flush();
            bw.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(new byte[]{(byte)(this.fl & 0xFF)});
            out.write(Utils.byte_int16d(this.flnum));
            out.write(Utils.byte_int16d(this.flavprob));
            for (int j = 0; j < this.flnum; ++j) {
                out.write(Utils.byte_strd(this.fln[j]));
                out.write(Utils.byte_int16d(this.flv[j]));
                out.write(new byte[]{(byte)(this.flw[j] & 0xFF)});
            }
        }

        @Override
        public void init() {
        }
    }

    public class Anim
    extends Layer {
        private int[] ids;
        public int id;
        public int d;
        public Image[][] f;

        public Anim(byte[] buf) {
            this.id = Utils.int16d(buf, 0);
            this.d = Utils.uint16d(buf, 2);
            this.ids = new int[Utils.uint16d(buf, 4)];
            if (buf.length - 6 != this.ids.length * 2) {
                throw new LoadException("Invalid anim descriptor in " + Resource.this.name, Resource.this);
            }
            for (int i = 0; i < this.ids.length; ++i) {
                this.ids[i] = Utils.int16d(buf, 6 + i * 2);
            }
        }

        public Anim(File data) throws Exception {
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(data), "UTF-8"));
            this.id = Utils.rnint(br);
            this.d = Utils.rnint(br);
            this.ids = new int[Utils.rnint(br)];
            for (int j = 0; j < this.ids.length; ++j) {
                this.ids[j] = Utils.rnint(br);
            }
            br.close();
        }

        @Override
        public int size() {
            int s = 6;
            return s += 2 * this.ids.length;
        }

        @Override
        public int type() {
            return ANIM;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{97, 110, 105, 109, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/anim/anim_" + i + ".data");
            new File(res + "/anim/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), "UTF-8"));
            bw.write("#ANIM LAYER FOR RES " + res + Resource.END);
            bw.write("#int16 id [keep -1]\r\n");
            bw.write(Integer.toString(this.id) + Resource.END);
            bw.write("#uint16 d [duration of animation]\r\n");
            bw.write(Integer.toString(this.d) + Resource.END);
            bw.write("#uint16 ids [length]\r\n");
            bw.write(Integer.toString(this.ids.length) + Resource.END);
            for (int j = 0; j < this.ids.length; ++j) {
                bw.write("#uint16 ids[" + j + "]" + Resource.END);
                bw.write(Integer.toString(this.ids[j]) + Resource.END);
            }
            bw.flush();
            bw.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(Utils.byte_int16d(this.id));
            out.write(Utils.byte_int16d(this.d));
            out.write(Utils.byte_int16d(this.ids.length));
            for (int i = 0; i < this.ids.length; ++i) {
                out.write(Utils.byte_int16d(this.ids[i]));
            }
        }

        @Override
        public void init() {
        }
    }

    public class Obst
    extends Layer {
        final int version;
        final String id;
        final List<Coord2d[]> polygons;

        public Obst(byte[] buf) {
            int i;
            MessageBuf msg = new MessageBuf(buf);
            this.version = msg.int8();
            this.id = this.version >= 2 ? msg.string() : "";
            int polygonCount = msg.int8();
            this.polygons = new LinkedList<Coord2d[]>();
            int[] polygonSizes = new int[polygonCount];
            for (i = 0; i < polygonCount; ++i) {
                polygonSizes[i] = msg.int8();
            }
            for (i = 0; i < polygonCount; ++i) {
                int points = polygonSizes[i];
                Coord2d[] polygon = new Coord2d[points];
                for (int j = 0; j < points; ++j) {
                    polygon[j] = new Coord2d(msg.float16(), msg.float16());
                }
                this.polygons.add(polygon);
            }
            System.out.println("LL");
        }

        public Obst(File data) throws Exception {
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(data), "UTF-8"));
            this.version = 1;
            this.id = "";
            this.polygons = new LinkedList<Coord2d[]>();
            br.close();
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public int type() {
            return OBST;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{111, 98, 115, 116, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/obst/obst_" + i + ".data");
            new File(res + "/obst/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), "UTF-8"));
            bw.flush();
            bw.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
        }

        @Override
        public void init() {
        }
    }

    public class Neg
    extends Layer {
        public Coord cc;
        public Coord bc;
        public Coord bs;
        public Coord sz;
        public Coord[][] ep;
        public int en;
        public ArrayList<Integer> cns;
        public ArrayList<Integer> epds;

        public Neg(byte[] buf) {
            this.cns = new ArrayList();
            this.epds = new ArrayList();
            this.cc = Resource.cdec(buf, 0);
            this.bc = Resource.cdec(buf, 4);
            this.bs = Resource.cdec(buf, 8);
            this.sz = Resource.cdec(buf, 12);
            this.ep = new Coord[8][0];
            this.en = buf[16];
            int off = 17;
            for (int i = 0; i < this.en; ++i) {
                byte epid = buf[off];
                int cn = Utils.uint16d(buf, off + 1);
                this.epds.add(Integer.valueOf(epid));
                this.cns.add(cn);
                off += 3;
                this.ep[epid] = new Coord[cn];
                for (int o = 0; o < cn; ++o) {
                    this.ep[epid][o] = Resource.cdec(buf, off);
                    off += 4;
                }
            }
        }

        public Neg(File data) throws Exception {
            this.cns = new ArrayList();
            this.epds = new ArrayList();
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(data), "UTF-8"));
            this.cc = new Coord(Utils.rnint(br), Utils.rnint(br));
            this.bc = new Coord(Utils.rnint(br), Utils.rnint(br));
            this.bs = new Coord(Utils.rnint(br), Utils.rnint(br));
            this.sz = new Coord(Utils.rnint(br), Utils.rnint(br));
            this.ep = new Coord[8][0];
            this.en = Utils.rnint(br);
            for (int i = 0; i < this.en; ++i) {
                int epid = Utils.rnint(br);
                int cn = Utils.rnint(br);
                this.epds.add(epid);
                this.cns.add(cn);
                this.ep[epid] = new Coord[cn];
                for (int o = 0; o < cn; ++o) {
                    this.ep[epid][o] = new Coord(Utils.rnint(br), Utils.rnint(br));
                }
            }
            br.close();
        }

        @Override
        public int size() {
            int s = 17;
            for (int i = 0; i < this.cns.size(); ++i) {
                s += 3;
                for (int o = 0; o < this.cns.get(i); ++o) {
                    s += 4;
                }
            }
            return s;
        }

        @Override
        public int type() {
            return NEG;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{110, 101, 103, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/neg/neg_" + i + ".data");
            new File(res + "/neg/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), "UTF-8"));
            bw.write("#NEG LAYER FOR RES: " + res + Resource.END);
            bw.write("#Coord cc\r\n");
            bw.write(Integer.toString(this.cc.x) + Resource.END);
            bw.write(Integer.toString(this.cc.y) + Resource.END);
            bw.write("#Coord bc\r\n");
            bw.write(Integer.toString(this.bc.x) + Resource.END);
            bw.write(Integer.toString(this.bc.y) + Resource.END);
            bw.write("#Coord bs\r\n");
            bw.write(Integer.toString(this.bs.x) + Resource.END);
            bw.write(Integer.toString(this.bs.y) + Resource.END);
            bw.write("#Coord sz\r\n");
            bw.write(Integer.toString(this.sz.x) + Resource.END);
            bw.write(Integer.toString(this.sz.y) + Resource.END);
            bw.write("#Byte en\r\n");
            bw.write(Integer.toString(this.en) + Resource.END);
            for (int j = 0; j < this.cns.size(); ++j) {
                bw.write("#Byte epid\r\n");
                bw.write(Integer.toString(this.epds.get(j)) + Resource.END);
                bw.write("#uint16 cn\r\n");
                bw.write(Integer.toString(this.cns.get(j)) + Resource.END);
                for (int o = 0; o < this.cns.get(j); ++o) {
                    bw.write("#Coord ep[" + this.epds.get(j) + "][" + o + "]" + Resource.END);
                    bw.write(Integer.toString(this.ep[this.epds.get((int)j).intValue()][o].x) + Resource.END);
                    bw.write(Integer.toString(this.ep[this.epds.get((int)j).intValue()][o].y) + Resource.END);
                }
            }
            bw.flush();
            bw.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(Utils.byte_int16d(this.cc.x));
            out.write(Utils.byte_int16d(this.cc.y));
            out.write(Utils.byte_int16d(this.bc.x));
            out.write(Utils.byte_int16d(this.bc.y));
            out.write(Utils.byte_int16d(this.bs.x));
            out.write(Utils.byte_int16d(this.bs.y));
            out.write(Utils.byte_int16d(this.sz.x));
            out.write(Utils.byte_int16d(this.sz.y));
            out.write(new byte[]{(byte)(this.en & 0xFF)});
            for (int j = 0; j < this.cns.size(); ++j) {
                out.write(new byte[]{(byte)(this.epds.get(j) & 0xFF)});
                out.write(Utils.byte_int16d(this.cns.get(j)));
                for (int o = 0; o < this.cns.get(j); ++o) {
                    out.write(Utils.byte_int16d(this.ep[this.epds.get((int)j).intValue()][o].x));
                    out.write(Utils.byte_int16d(this.ep[this.epds.get((int)j).intValue()][o].y));
                }
            }
        }

        @Override
        public void init() {
        }
    }

    public class Tile
    extends Layer {
        transient BufferedImage img;
        byte[] raw;
        public int id;
        int w;
        char t;

        public Tile(byte[] buf) {
            this.t = (char)Utils.ub(buf[0]);
            this.id = Utils.ub(buf[1]);
            this.w = Utils.uint16d(buf, 2);
            try {
                this.img = ImageIO.read(new ByteArrayInputStream(buf, 4, buf.length - 4));
            }
            catch (IOException e) {
                throw new LoadException(e, Resource.this);
            }
            if (this.img == null) {
                throw new LoadException("Invalid image data in " + Resource.this.name, Resource.this);
            }
        }

        public Tile(File data, File png) throws Exception {
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(data), "UTF-8"));
            this.t = (char)Utils.rnint(br);
            this.id = Utils.rnint(br);
            this.w = Utils.rnint(br);
            this.img = ImageIO.read(png);
            br.close();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write((RenderedImage)this.img, "png", baos);
            baos.flush();
            this.raw = baos.toByteArray();
            baos.close();
        }

        @Override
        public int size() {
            int s = 4;
            return s += this.raw.length;
        }

        @Override
        public int type() {
            return TILE;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{116, 105, 108, 101, 0};
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/tile/tile_" + i + ".data");
            new File(res + "/tile/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), "UTF-8"));
            bw.write("#TILE LAYER FOR RES " + res + Resource.END);
            bw.write("#Byte t\r\n");
            bw.write(Integer.toString(this.t) + Resource.END);
            bw.write("#Byte id\r\n");
            bw.write(Integer.toString(this.id) + Resource.END);
            bw.write("#uint16 w\r\n");
            bw.write(Integer.toString(this.w) + Resource.END);
            bw.flush();
            bw.close();
            ImageIO.write((RenderedImage)this.img, "png", new File(res + "/tile/tile_" + i + ".png"));
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(new byte[]{(byte)(this.t & 0xFF)});
            out.write(new byte[]{(byte)(this.id & 0xFF)});
            out.write(Utils.byte_int16d(this.w));
            out.write(this.raw);
        }

        @Override
        public void init() {
        }
    }

    public class Tooltip
    extends Layer {
        public final String t;
        private int size;

        public Tooltip(byte[] buf) {
            this.size = 0;
            try {
                this.t = new String(buf, "UTF-8");
            }
            catch (UnsupportedEncodingException e) {
                throw new LoadException(e, Resource.this);
            }
        }

        public Tooltip(File data) throws Exception {
            this.size = 0;
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(data), "UTF-8"));
            this.t = Utils.rstr(br);
            this.size = Utils.byte_str(this.t).length;
            br.close();
        }

        @Override
        public int size() {
            return this.size;
        }

        @Override
        public int type() {
            return TOOLTIP;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{116, 111, 111, 108, 116, 105, 112, 0};
        }

        @Override
        public void init() {
        }

        @Override
        public void decode(String res, int i) throws Exception {
            File f = new File(res + "/tooltip/tooltip_" + i + ".data");
            new File(res + "/tooltip/").mkdirs();
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), "UTF-8"));
            bw.write("#TOOLTIP LAYER FOR RES " + res + Resource.END);
            bw.write("#String tooltip\r\n");
            bw.write(this.t.replace("\n", "\\n") + Resource.END);
            bw.flush();
            bw.close();
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(Utils.byte_str(this.t));
        }
    }

    public class Image
    extends Layer {
        public transient BufferedImage img;
        public byte[] raw;
        public final int z;
        public final int subz;
        public final boolean nooff;
        public final boolean custom;
        public final int id;
        private float scale;
        public Coord sz;
        public Coord o;
        public Coord tsz;
        public Map<String, byte[]> kvdata;

        public Image(byte[] bytes) {
            this.scale = 1.0f;
            this.kvdata = null;
            MessageBuf buf = new MessageBuf(bytes);
            this.z = buf.int16();
            this.subz = buf.int16();
            int fl = buf.uint8();
            this.nooff = (fl & 2) != 0;
            this.id = buf.int16();
            this.o = Resource.cdec(buf);
            this.custom = (fl & 4) != 0;
            HashMap<String, byte[]> kvdata = new HashMap<String, byte[]>();
            if (this.custom) {
                String key;
                while (!(key = buf.string()).equals("")) {
                    int len = buf.uint8();
                    if ((len & 0x80) != 0) {
                        len = buf.int32();
                    }
                    byte[] data = buf.bytes(len);
                    MessageBuf val = new MessageBuf(data);
                    if (key.equals("tsz")) {
                        this.tsz = val.coord();
                        continue;
                    }
                    if (key.equals("scale")) {
                        this.scale = val.float32();
                        continue;
                    }
                    kvdata.put(key, data);
                }
            }
            this.kvdata = kvdata.isEmpty() ? Collections.emptyMap() : kvdata;
            try {
                this.img = Resource.readimage(new MessageInputStream(buf));
            }
            catch (IOException e) {
                throw new LoadException(e, Resource.this);
            }
            this.sz = Utils.imgsz(this.img);
            if (this.tsz == null) {
                this.tsz = this.sz;
            }
            if (this.img == null) {
                throw new LoadException("Invalid image data in " + Resource.this.name, Resource.this);
            }
        }

        public Image(File data, File png) throws Exception {
            String k;
            this.scale = 1.0f;
            this.kvdata = null;
            BufferedReader br = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(data), "UTF-8"));
            this.z = Utils.rnint(br);
            this.subz = Utils.rnint(br);
            int fl = Utils.rnint(br);
            this.nooff = (fl & 2) != 0;
            this.id = Utils.rnint(br);
            this.o = new Coord(Utils.rnint(br), Utils.rnint(br));
            this.tsz = this.sz;
            this.scale = 1.0f;
            boolean tmp = false;
            HashMap<String, byte[]> kvdata = new HashMap<String, byte[]>();
            while ((k = Utils.rnstr(br)) != null) {
                if ("tsz".equals(k)) {
                    this.tsz = new Coord(Utils.rnint(br), Utils.rnint(br));
                } else if ("scale".equals(k)) {
                    this.scale = Utils.rfloat(br);
                } else {
                    kvdata.put(k, Utils.rstrbytes(br));
                }
                tmp = true;
            }
            this.kvdata = kvdata.isEmpty() ? Collections.emptyMap() : kvdata;
            this.custom = tmp;
            this.img = ImageIO.read(png);
            br.close();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write((RenderedImage)this.img, "png", baos);
            baos.flush();
            this.raw = baos.toByteArray();
            baos.close();
        }

        @Override
        public int size() {
            int s = 11;
            if (this.custom) {
                if (this.scale != 1.0f) {
                    s += 6;
                    ++s;
                    s += 4;
                }
                if (this.tsz != this.sz) {
                    s += 4;
                    ++s;
                    s += 8;
                }
                if (!this.kvdata.isEmpty()) {
                    for (Map.Entry<String, byte[]> entry : this.kvdata.entrySet()) {
                        s += entry.getKey().length() + 1;
                        ++s;
                        s += entry.getValue().length;
                    }
                }
                ++s;
            }
            return s += this.raw.length;
        }

        @Override
        public int type() {
            return IMAGE;
        }

        @Override
        public byte[] type_buffer() {
            return new byte[]{105, 109, 97, 103, 101, 0};
        }

        @Override
        public void init() {
        }

        @Override
        public void decode(String res, int i) throws Exception {
            new File(res + "/image/").mkdirs();
            File f = new File(res + "/image/image_" + i + ".data");
            f.createNewFile();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(f, false), "UTF-8"));
            bw.write("#IMAGE LAYER FOR RES " + res + Resource.END);
            bw.write("#int16 z\r\n");
            bw.write(Integer.toString(this.z) + Resource.END);
            bw.write("#int16 subz\r\n");
            bw.write(Integer.toString(this.subz) + Resource.END);
            bw.write("#Byte nooff\r\n");
            bw.write(Integer.toString(this.nooff ? 1 : 0) + Resource.END);
            bw.write("#int16 id\r\n");
            bw.write(Integer.toString(this.id) + Resource.END);
            bw.write("#Coord o\r\n");
            bw.write(Integer.toString(this.o.x) + Resource.END);
            bw.write(Integer.toString(this.o.y) + Resource.END);
            if (this.tsz != this.sz) {
                bw.write("tsz\r\n");
                bw.write(Integer.toString(this.tsz.x) + Resource.END);
                bw.write(Integer.toString(this.tsz.y) + Resource.END);
            }
            if (this.scale != 1.0f) {
                bw.write("scale\r\n");
                bw.write(Float.toString(this.scale) + Resource.END);
            }
            if (!this.kvdata.isEmpty()) {
                for (Map.Entry<String, byte[]> entry : this.kvdata.entrySet()) {
                    bw.write(entry.getKey() + Resource.END);
                    String bytes = Arrays.toString(entry.getValue());
                    bytes = bytes.substring(1, bytes.length() - 1);
                    bw.write(bytes + Resource.END);
                }
            }
            bw.flush();
            bw.close();
            ImageIO.write((RenderedImage)this.img, "png", new File(res + "/image/image_" + i + ".png"));
        }

        @Override
        public void encode(OutputStream out) throws Exception {
            out.write(Utils.byte_int16d(this.z));
            out.write(Utils.byte_int16d(this.subz));
            out.write(new byte[]{(byte)((this.nooff ? 2 : 0) | (this.custom ? 4 : 0))});
            out.write(Utils.byte_int16d(this.id));
            out.write(Utils.byte_int16d(this.o.x));
            out.write(Utils.byte_int16d(this.o.y));
            if (this.scale != 1.0f) {
                out.write(Utils.byte_strd("scale"));
                out.write(4);
                out.write(Utils.byte_float32d(this.scale));
            }
            if (this.tsz != this.sz) {
                out.write(Utils.byte_strd("tsz"));
                out.write(32);
                out.write(Utils.byte_int32d(this.tsz.x));
                out.write(Utils.byte_int32d(this.tsz.y));
            }
            if (!this.kvdata.isEmpty()) {
                for (Map.Entry<String, byte[]> entry : this.kvdata.entrySet()) {
                    out.write(Utils.byte_strd(entry.getKey()));
                    out.write(entry.getValue().length);
                    out.write(entry.getValue());
                }
            }
            if (this.custom) {
                out.write(Utils.byte_strd(""));
            }
            out.write(this.raw);
        }
    }

    public static class ImageReadException
    extends IOException {
        public final String[] supported = ImageIO.getReaderMIMETypes();

        public ImageReadException() {
            super("Could not decode image data");
        }
    }

    public abstract class Layer
    implements Serializable {
        public abstract void init();

        public abstract int size();

        public abstract int type();

        public abstract byte[] type_buffer();

        public abstract void decode(String var1, int var2) throws Exception;

        public abstract void encode(OutputStream var1) throws Exception;
    }

    public static class LoadException
    extends RuntimeException {
        public Resource res;

        public LoadException(String msg, Resource res) {
            super(msg);
            this.res = res;
        }

        public LoadException(String msg, Throwable cause, Resource res) {
            super(msg, cause);
            this.res = res;
        }

        public LoadException(Throwable cause, Resource res) {
            super("Load error in resource " + res.toString() + "\n" + cause + "\n");
            this.res = res;
        }
    }
}
