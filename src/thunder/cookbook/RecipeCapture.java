package thunder.cookbook;

import haven.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Passively records real crafting-recipe ingredients (item + exact quantity)
 * whenever a Makewindow shows both its inputs and outputs. There is no
 * static/offline database of this data anywhere in the game -- confirmed by
 * inspecting Makewindow and CraftDBWnd: a recipe's real ingredient list is
 * server-sent only for the live crafting session you have open (the
 * "inpop"/"opop" widget messages), never cached to a resource file. See
 * docs/cookbook-integration.md.
 *
 * So this builds up coverage organically instead: a dish only shows up here
 * once someone running this client has actually opened its crafting window.
 * Hooked from Makewindow.uimsg (see the two consider() call sites there).
 * Consumed by CookbookPlanWnd's bill of materials, which prefers this real
 * data over the FEP-percentage estimate for any dish we've captured.
 */
public class RecipeCapture {
    private static final String FILE = "cookbook-recipes.json";

    public static class Ingredient {
        public final String resName;
        public final String displayName;
        public final int qty;

        public Ingredient(String resName, String displayName, int qty) {
            this.resName = resName;
            this.displayName = displayName;
            this.qty = qty;
        }
    }

    /** Called from Makewindow whenever its inputs or outputs are (re)populated. */
    public static void consider(Makewindow mw) {
        try {
            if(mw.inputs.isEmpty() || mw.outputs.isEmpty()) {return;}
            List<Ingredient> ingredients = new ArrayList<>();
            for(Makewindow.Input in : mw.inputs) {
                Resource r = in.spec.resource();
                ingredients.add(new Ingredient(r.name, displayName(r), in.spec.num));
            }
            if(ingredients.isEmpty()) {return;}
            Set<String> outNames = new LinkedHashSet<>();
            for(Makewindow.SpecWidget out : mw.outputs) {
                outNames.add(out.spec.resource().name);
            }
            for(String outName : outNames) {
                save(outName, ingredients);
            }
        } catch(Loading l) {
            /* one of the involved resources isn't cached yet -- harmless, we'll
             * just try again the next time inpop/opop fires for this window */
        } catch(Exception ignored) {}
    }

    private static String displayName(Resource r) {
        try {
            Resource.Tooltip tt = r.layer(Resource.tooltip);
            if(tt != null) {return tt.t;}
        } catch(Exception ignored) {}
        return r.name;
    }

    private static synchronized void save(String outResName, List<Ingredient> ingredients) {
        JSONObject all = load();
        JSONArray arr = new JSONArray();
        for(Ingredient ig : ingredients) {
            JSONObject o = new JSONObject();
            o.put("res", ig.resName);
            o.put("name", ig.displayName);
            o.put("qty", ig.qty);
            arr.put(o);
        }
        all.put(outResName, arr);
        Config.saveFile(FILE, all.toString());
    }

    private static JSONObject load() {
        try {
            String data = Config.loadFile(FILE);
            if((data != null) && !data.isEmpty()) {return new JSONObject(data);}
        } catch(Exception ignored) {}
        return new JSONObject();
    }

    /** Real captured recipe for this dish's resource name, or null if we haven't seen it crafted yet. */
    public static List<Ingredient> recipeFor(String resName) {
        JSONObject all = load();
        JSONArray arr = all.optJSONArray(resName);
        if(arr == null) {return null;}
        List<Ingredient> out = new ArrayList<>();
        for(int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            out.add(new Ingredient(o.getString("res"), o.getString("name"), o.getInt("qty")));
        }
        return out;
    }

    /** Resource names of every dish we have real captured ingredient data for. One-shot read, not per-frame -- callers should cache the result. */
    public static Set<String> capturedNames() {
        return new LinkedHashSet<>(load().keySet());
    }
}
