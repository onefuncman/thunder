package thunder.cookbook;

import haven.Defer;
import integrations.food.FoodService;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * Supplies the food dataset to the Cookbook windows. Nothing is fetched
 * here: {@link FoodService} (Kami's food uploader) already downloads
 * {@code <Mapping URL>/data/food-info.json} and caches it on disk for its
 * own dedup keys, so a Cookbook refresh just asks it for a fresh copy and
 * parses that. One endpoint setting, one download path, one cache file.
 */
public class CookbookService {
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
        return CookbookItem.parseAll(new JSONObject(FoodService.foodDataJson(true)));
    }
}
