package thunder.cookbook;

import haven.Config;
import haven.Defer;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time crawl of every item resource under gfx/invobjs on Ring of
 * Brodgar's resource browser (https://brodgar.io/res/gfx/invobjs) -- for
 * ":restest"'s "All invobjs" mode, so the user can visually browse the
 * game's whole item-icon set and hover to read each one's real resource
 * path, to help identify/confirm entries for IngredientIconResolver's
 * MANUAL_OVERRIDES. Same community-site caveats as IngredientIconResolver:
 * best-effort, not an official H&H service. See docs/cookbook-integration.md.
 *
 * Crawled once and cached to disk (cookbook-invobjs-index.json, ~2000
 * entries) -- courteous to brodgar.io not to re-crawl every time the
 * window opens, and so the list is still there next session even if
 * brodgar.io is unreachable then.
 */
public class BrodgarInvobjsIndex {
    private static final String BASE = "https://brodgar.io/res";
    private static final String ROOT_DIR = "gfx/invobjs";
    private static final String CACHE_FILE = "cookbook-invobjs-index.json";

    private static volatile List<String> cached = null;
    private static volatile boolean loading = false;

    /** Null until loaded -- call loadAsync() and wait for its callback. */
    public static List<String> getCached() {return cached;}

    public static void loadAsync(Runnable onDone) {
        if(cached != null) {onDone.run(); return;}
        synchronized(BrodgarInvobjsIndex.class) {
            if(loading) {return;}
            loading = true;
        }
        Defer.later(() -> {
            List<String> list = loadFromDisk();
            if(list == null) {
                list = crawl();
                saveToDisk(list);
            }
            cached = list;
            loading = false;
            onDone.run();
        }, null);
    }

    private static List<String> loadFromDisk() {
        try {
            String data = Config.loadFile(CACHE_FILE);
            if((data == null) || data.isEmpty()) {return null;}
            JSONArray arr = new JSONArray(data);
            List<String> out = new ArrayList<>(arr.length());
            for(int i = 0; i < arr.length(); i++) {out.add(arr.getString(i));}
            return out.isEmpty() ? null : out;
        } catch(Exception e) {
            return null;
        }
    }

    private static void saveToDisk(List<String> list) {
        try {
            JSONArray arr = new JSONArray();
            for(String s : list) {arr.put(s);}
            Config.saveFile(CACHE_FILE, arr.toString());
        } catch(Exception ignored) {}
    }

    private static List<String> crawl() {
        List<String> out = new ArrayList<>();
        crawlDir(ROOT_DIR, out);
        return out;
    }

    private static void crawlDir(String dir, List<String> out) {
        try {
            String html = fetchHtml(BASE + "/" + dir);
            List<String> subdirs = new ArrayList<>();
            Matcher m = Pattern.compile("href=\"/res/(" + Pattern.quote(dir) + "/[^\"]+)\"").matcher(html);
            while(m.find()) {
                String rel = m.group(1);
                if(rel.endsWith(".res")) {
                    out.add(rel.substring(0, rel.length() - 4));
                } else if(!subdirs.contains(rel)) {
                    subdirs.add(rel);
                }
            }
            for(String sub : subdirs) {crawlDir(sub, out);}
        } catch(Exception ignored) {}
    }

    private static String fetchHtml(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Thunder Client");
        StringBuilder sb = new StringBuilder();
        try(BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while((line = r.readLine()) != null) {sb.append(line);}
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }
}
