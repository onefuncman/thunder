package thunder.cookbook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row of the automap site's food-info dataset (see CookbookService), plus
 * the derived fields the site's own cookbook page computes client-side
 * (see docs/cookbook-integration.md for how these were reverse-engineered
 * from the site's main-es2015 bundle).
 */
public class CookbookItem {
    /** JSON fep name -> short attribute key, exactly as the website's `fepmap`. */
    private static final Map<String, String> FEP_MAP = new LinkedHashMap<>();
    /** Display order of the base (non-tier-2) attribute keys, matching the site's `fepAttrs`. */
    public static final String[] ATTR_ORDER = {"str", "agi", "int", "con", "per", "cha", "dex", "wil", "psy"};

    static {
        for(String base : new String[]{"Strength", "Agility", "Intelligence", "Constitution",
            "Perception", "Charisma", "Dexterity", "Will", "Psyche"}) {
            String key = base.substring(0, 3).toLowerCase();
            FEP_MAP.put(base + " +1", key);
            FEP_MAP.put(base + " +2", key + "2");
        }
    }

    public final String name;
    public final String resourceName;
    public final int energy;
    public final double hunger;
    public final List<Ingredient> ingredients;
    /** attr key ("str", "str2", ...) -> raw FEP value, as shown by e.g. "str>50". */
    public final Map<String, Double> feps;
    /** attr key -> percentage of totalFep, as shown by e.g. "str>50%". */
    public final Map<String, Double> fepPercent;
    public final double totalFep;
    /** Website's "FEP/Hunger" column: totalFep / hunger * 10. */
    public final double fepPerHunger;

    public static class Ingredient {
        public final String name;
        public final int percentage;
        public Ingredient(String name, int percentage) {
            this.name = name;
            this.percentage = percentage;
        }
    }

    private CookbookItem(String name, String resourceName, int energy, double hunger,
                          List<Ingredient> ingredients, Map<String, Double> feps) {
        this.name = name;
        this.resourceName = resourceName;
        this.energy = energy;
        this.hunger = hunger;
        this.ingredients = ingredients;
        this.feps = feps;
        double total = 0;
        for(double v : feps.values()) {total += v;}
        this.totalFep = total;
        Map<String, Double> pct = new LinkedHashMap<>();
        if(total != 0) {
            for(Map.Entry<String, Double> e : feps.entrySet()) {
                pct.put(e.getKey(), e.getValue() / total * 100.0);
            }
        }
        this.fepPercent = pct;
        this.fepPerHunger = (hunger != 0) ? (total / hunger * 10.0) : 0;
    }

    /**
     * Parses the hash-keyed /data/food-info.json object that FoodService caches.
     * The uploader stores one record per (item, ingredients, quality-sample)
     * hash, so the same dish+ingredient variant can appear several times with
     * rounding-level FEP differences. The Cookbook wants one row per variant
     * (what the site's own deduplicated array shows), so collapse on key(),
     * keeping the lowest hash so the pick is stable run to run.
     */
    public static List<CookbookItem> parseAll(JSONObject byHash) {
        List<String> hashes = new ArrayList<>(byHash.keySet());
        Collections.sort(hashes);
        Map<String, CookbookItem> byKey = new LinkedHashMap<>();
        for(String h : hashes) {
            JSONObject o = byHash.optJSONObject(h);
            if(o == null) {continue;}
            CookbookItem item = parse(o);
            byKey.putIfAbsent(item.key(), item);
        }
        return new ArrayList<>(byKey.values());
    }

    /** Parses a plain array of records (the site's /food-info.json shape). */
    public static List<CookbookItem> parseAll(JSONArray arr) {
        List<CookbookItem> out = new ArrayList<>(arr.length());
        for(int i = 0; i < arr.length(); i++) {
            out.add(parse(arr.getJSONObject(i)));
        }
        return out;
    }

    /** One record, in the shape FoodService.ParsedFoodInfo is uploaded as. */
    public static CookbookItem parse(JSONObject o) {
        String name = o.optString("itemName", "");
        String resourceName = o.optString("resourceName", "");
        int energy = o.optInt("energy", 0);
        double hunger = o.optDouble("hunger", 0);

        List<Ingredient> ingredients = new ArrayList<>();
        JSONArray ja = o.optJSONArray("ingredients");
        if(ja != null) {
            for(int j = 0; j < ja.length(); j++) {
                JSONObject io = ja.getJSONObject(j);
                ingredients.add(new Ingredient(io.optString("name", ""), io.optInt("percentage", 0)));
            }
        }

        Map<String, Double> feps = new LinkedHashMap<>();
        JSONArray fa = o.optJSONArray("feps");
        if(fa != null) {
            for(int j = 0; j < fa.length(); j++) {
                JSONObject fo = fa.getJSONObject(j);
                String key = FEP_MAP.get(fo.optString("name", ""));
                if(key != null) {
                    feps.merge(key, fo.optDouble("value", 0), Double::sum);
                }
            }
        }

        return new CookbookItem(name, resourceName, energy, hunger, ingredients, feps);
    }

    public String ingredientSummary() {
        StringBuilder sb = new StringBuilder();
        for(Ingredient ig : ingredients) {
            if(sb.length() > 0) {sb.append(", ");}
            sb.append(ig.name).append(": ").append(ig.percentage).append('%');
        }
        return sb.toString();
    }

    /**
     * Stable identifier for this exact dish+ingredient-variant, for saving
     * things (like a meal plan) across sessions -- object identity isn't
     * stable since the dataset is re-fetched and re-parsed fresh every time.
     * The item name is part of it: every roast/spitroast/dried meat shares
     * the "gfx/invobjs/meat" resource, so resource+ingredients alone would
     * make "Roast Bear" and "Sizzling Spitroast Pike" the same key.
     */
    public String key() {
        List<String> parts = new ArrayList<>();
        for(Ingredient ig : ingredients) {parts.add(ig.name + ":" + ig.percentage);}
        Collections.sort(parts);
        return name + "|" + resourceName + "|" + String.join(",", parts);
    }
}
