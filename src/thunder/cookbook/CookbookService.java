package thunder.cookbook;

import haven.Defer;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Fetches the food dataset from civ.hearthworld.com/food-info.json. */
public class CookbookService {
    private static final String DATA_URL = CookbookAuth.BASE + "/food-info.json";

    private static volatile List<CookbookItem> lastGood = Collections.emptyList();

    public static List<CookbookItem> lastGood() {return lastGood;}

    public interface RefreshCallback {
        void done(List<CookbookItem> items, String error);
    }

    public static void refreshAsync(RefreshCallback cb) {
        Defer.later(() -> {
            List<CookbookItem> items = null;
            String error = null;
            try {
                items = fetch();
                lastGood = items;
            } catch(Exception e) {
                error = (e.getMessage() != null) ? e.getMessage() : e.toString();
            }
            cb.done(items, error);
        }, null);
    }

    private static List<CookbookItem> fetch() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(DATA_URL).openConnection();
        conn.setRequestProperty("User-Agent", "Thunder Client");
        String cookie = CookbookAuth.cookieHeader();
        if(cookie != null) {conn.setRequestProperty("Cookie", cookie);}
        try {
            StringBuilder sb = new StringBuilder();
            try(BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while((line = r.readLine()) != null) {sb.append(line);}
            }
            return CookbookItem.parseAll(new JSONArray(sb.toString()));
        } finally {
            conn.disconnect();
        }
    }
}
