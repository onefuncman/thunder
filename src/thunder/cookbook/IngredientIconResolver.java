package thunder.cookbook;

import haven.Config;
import haven.Defer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort ingredient-name -> game resource path lookup, via Ring of
 * Brodgar's public resource browser (https://brodgar.io/res/) -- a
 * community site, not an official H&H service, so treat this as
 * opportunistic: it can go away or change shape at any time. We only ever
 * use it to find a resource PATH; the actual icon image always comes
 * through our own game session (Resource.remote()), same as every other
 * icon in this client -- brodgar.io's own served bytes are never used
 * directly. See docs/cookbook-integration.md.
 *
 * This exists because plain path-guessing (e.g. "gfx/invobjs/" + slug)
 * turned out too unreliable to trust (tested directly, ~2/9 hit rate) --
 * showing a wrong icon is worse than no icon. Brodgar's search is a real
 * index of actual resource filenames, so a filename match is far more
 * trustworthy than a blind guess, though still not a certainty (a search
 * hit's icon may not visually match if two items share a base name).
 */
public class IngredientIconResolver {
    private static final String SEARCH_URL = "https://brodgar.io/res/?search=";
    private static final String CACHE_FILE = "cookbook-ingredient-paths.json";
    // Empty string means "looked it up, found nothing" -- cached so we don't requery every draw.
    private static final Map<String, String> cache = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private static synchronized void ensureLoaded() {
        if(loaded) {return;}
        loaded = true;
        try {
            String data = Config.loadFile(CACHE_FILE);
            if((data != null) && !data.isEmpty()) {
                JSONObject o = new JSONObject(data);
                for(String k : o.keySet()) {cache.put(k, o.getString(k));}
            }
        } catch(Exception ignored) {}
    }

    /** Cached resource path for this ingredient name, or null if unresolved (never blocks/never null-checks the network). */
    public static String getCached(String name) {
        ensureLoaded();
        String v = cache.get(name);
        return ((v != null) && !v.isEmpty()) ? v : null;
    }

    /** Fire-and-forget: looks the name up on brodgar.io in the background, once, if we haven't already tried it. */
    public static void resolveAsync(String name) {
        ensureLoaded();
        if(cache.containsKey(name)) {return;}
        cache.put(name, ""); // claim it immediately so concurrent draw() calls don't fire duplicate lookups
        Defer.later(() -> {
            String path = query(name);
            cache.put(name, (path != null) ? path : "");
            save();
        }, null);
    }

    private static synchronized void save() {
        JSONObject o = new JSONObject();
        for(Map.Entry<String, String> e : cache.entrySet()) {o.put(e.getKey(), e.getValue());}
        Config.saveFile(CACHE_FILE, o.toString());
    }

    /**
     * Confirmed name -> resource path pairs for ingredients the automatic
     * rules below can't derive -- irregular plurals ("Chantrelles" ->
     * "chantrelle"), word order flips ("Green Apple" -> "applegreen"), a
     * leading rather than trailing descriptor ("Wild Beef" -> "meat-beef"),
     * or a name substitution with no string relationship at all ("Wildkale
     * Leaf" -> "leaf-brassica", "Wild Onion" -> "preonion"). Supplied by the
     * user directly from known-good in-game item names; checked first,
     * before any automatic search. Two are flagged as the user's best guess
     * rather than a confirmed exact item, still worth using over nothing.
     */
    private static final Map<String, String> MANUAL_OVERRIDES = new LinkedHashMap<>();

    static {
        MANUAL_OVERRIDES.put("Horse bollock", "gfx/invobjs/meat-testis");
        MANUAL_OVERRIDES.put("Pork bollock", "gfx/invobjs/meat-testis");
        MANUAL_OVERRIDES.put("Wild Beef", "gfx/invobjs/meat-beef");
        MANUAL_OVERRIDES.put("Wild Mutton", "gfx/invobjs/meat-mutton");
        MANUAL_OVERRIDES.put("Roe Venison", "gfx/invobjs/meat-roedeer");
        // Bare "Venison" (no qualifier) is Red Deer specifically -- confirmed by the user:
        // Red Deer was the first deer species in the game, so its raw meat kept the
        // unqualified name "Venison" while every later deer species got an explicit
        // qualifier ("Reindeer Venison", "Roe Venison"). No string relationship to search
        // for, has to be a manual mapping.
        MANUAL_OVERRIDES.put("Venison", "gfx/invobjs/meat-reddeer");
        MANUAL_OVERRIDES.put("Chantrelles", "gfx/invobjs/herbs/chantrelle"); // ground truth from ItemResourceCapture (real inventory item, not a guess) -- gfx/terobjs/items/chantrelle is mat2-only, and NOT gfx/terobjs/mm/trees/trombonechantrelle (a different, underground mushroom "tree")
        MANUAL_OVERRIDES.put("Liberty Caps", "gfx/invobjs/herbs/libertycap");
        MANUAL_OVERRIDES.put("Portobello Mushroom", "gfx/invobjs/champignon-large"); // best guess: largest champignon size
        // Blueberries/Blackberry/Raspberry/Radish/Lingonberries previously pointed at
        // gfx/terobjs/items/<name> or gfx/invobjs/radish-tex -- decompiled directly and
        // confirmed those are 3D-model *material* resources (a "mat2" layer only, no
        // "image" layer at all: this game renders their inventory icon procedurally
        // from the mesh, there's no static PNG to fetch). Loading one threw a
        // NullPointerException from Resource.layer(Resource.imgc) inside ItemIconUtil,
        // silently swallowed by the caller's generic catch -> permanently blank icon.
        MANUAL_OVERRIDES.put("Blueberries", "gfx/invobjs/herbs/blueberry"); // ground truth from ItemResourceCapture -- not found via brodgar.io search (index/search must have missed or truncated it)
        MANUAL_OVERRIDES.put("Blackberry", "gfx/invobjs/seed-blackberrybush"); // despite the "bush"/"seed-" name, tooltip is plain "Blackberry" -- the real fruit icon
        MANUAL_OVERRIDES.put("Raspberry", "gfx/invobjs/seed-raspberrybush"); // same pattern, tooltip "Raspberry"
        MANUAL_OVERRIDES.put("Lingonberries", "gfx/invobjs/herbs/lingon"); // tooltip "Lingonberries", real icon (old override had no image layer)
        MANUAL_OVERRIDES.put("Seaberries", "gfx/invobjs/seed-sandthorn"); // ground truth from ItemResourceCapture -- confirms Seaberries and Sandthorn are the same plant after all, just not under gfx/terobjs/mm/bushes/sandthorn (that's a different resource)
        MANUAL_OVERRIDES.put("Green Apple", "gfx/invobjs/applegreen");
        MANUAL_OVERRIDES.put("Red Apple", "gfx/invobjs/apple");
        MANUAL_OVERRIDES.put("River Pearl Mussel", "gfx/invobjs/herbs/mussels"); // old override (gfx/terobjs/items/mussels) was mat2-only, no image layer
        MANUAL_OVERRIDES.put("Yellowfeet", "gfx/invobjs/herbs/yellowfoot"); // old override (gfx/terobjs/items/yellowfoot) was mat2-only, no image layer
        MANUAL_OVERRIDES.put("White Onion", "gfx/invobjs/whiteonion"); // ground truth from ItemResourceCapture -- a real invobjs/whiteonion.res exists after all, brodgar.io search just never surfaced it (only its mat2-only terobjs/items namesake came up)
        MANUAL_OVERRIDES.put("Green apple tree", "gfx/invobjs/wblock-appletreegreen"); // tooltip "Block of Greenapplewood" -- same wblock- pattern as Persimmon/Pear/Strawberry tree above. NOTE: lowercase "apple" -- the real ingredient key, unlike every other fruit ("Green Apple") -- confirmed via the live cache, first attempt used the wrong case and silently never matched
        MANUAL_OVERRIDES.put("Corn Grass Flour", "gfx/invobjs/flour-cerealflour");
        MANUAL_OVERRIDES.put("Radish", "gfx/invobjs/seed-radish"); // radish-tex (old override) is a mat2-only 3D material, no image layer
        MANUAL_OVERRIDES.put("Sheepsmilk", "gfx/invobjs/milk");
        MANUAL_OVERRIDES.put("Wild Onion", "gfx/invobjs/preonion");
        MANUAL_OVERRIDES.put("Wild Tuber", "gfx/invobjs/pretuber");
        MANUAL_OVERRIDES.put("Wildkale Leaf", "gfx/invobjs/leaf-brassica");
        MANUAL_OVERRIDES.put("Juniper Berries", "gfx/invobjs/seed-juniper");
        MANUAL_OVERRIDES.put("Dog Rose Hips", "gfx/invobjs/seed-dogrose");
        // These animals have BOTH a full-creature icon (bare filename, e.g. "rabbit.res")
        // and a mini raw-meat badge ("meat-rabbit.res") under gfx/invobjs -- both are
        // valid itemDir matches, so the auto search (which tries the bare "" prefix
        // before "meat-") kept finding the full-size creature icon first. Forced to the
        // mini badge directly so these get the same "small badge + generic raw-meat
        // backdrop" treatment as every other raw-meat ingredient.
        MANUAL_OVERRIDES.put("Bog turtle", "gfx/invobjs/meat-bogturtle");
        MANUAL_OVERRIDES.put("Squirrel", "gfx/invobjs/meat-squirrel");
        MANUAL_OVERRIDES.put("Rabbit", "gfx/invobjs/meat-rabbit");
        MANUAL_OVERRIDES.put("Ant", "gfx/invobjs/meat-ant");
        MANUAL_OVERRIDES.put("Magpie", "gfx/invobjs/meat-magpie");
        MANUAL_OVERRIDES.put("Mole", "gfx/invobjs/meat-mole");
        MANUAL_OVERRIDES.put("Swan", "gfx/invobjs/meat-swan");
        MANUAL_OVERRIDES.put("Rock Dove", "gfx/invobjs/meat-rockdove");
        // Same issue but the auto search landed on the whole-creature "fish-<name>"
        // illustration instead of the mini "meat-<name>" badge.
        MANUAL_OVERRIDES.put("Cod", "gfx/invobjs/meat-cod");
        MANUAL_OVERRIDES.put("Abyss Gazer", "gfx/invobjs/meat-abyssgazer");
        MANUAL_OVERRIDES.put("Cavelacanth", "gfx/invobjs/meat-cavelacanth");
    }

    private static String query(String name) {
        String override = MANUAL_OVERRIDES.get(name);
        if(override != null) {return override;}
        for(Candidate c : candidates(name)) {
            try {
                String path = pickBest(c, fetch(c.searchTerm));
                if(path != null) {return path;}
            } catch(Exception ignored) {}
        }
        return null;
    }

    private static JSONArray fetch(String term) throws Exception {
        // The search endpoint does a plain substring match against filenames,
        // which never contain spaces -- a query with a space in it (e.g. a
        // two-word ingredient name passed as-is) matches nothing at all.
        // Callers always pass an already-slugged term (verified directly:
        // searching "Stinging Nettle" returns zero results, "stingingnettle" finds it).
        String q = URLEncoder.encode(term, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(SEARCH_URL + q).openConnection();
        conn.setRequestProperty("User-Agent", "Thunder Client");
        StringBuilder sb = new StringBuilder();
        try(BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while((line = r.readLine()) != null) {sb.append(line);}
        } finally {
            conn.disconnect();
        }
        return new JSONArray(sb.toString());
    }

    // Known H&H filename conventions where the item you'd actually feed into a
    // recipe isn't literally named after the raw ingredient word: a smoking
    // wood type "Osier" is held/used as a "block of osier" (wblock-osier), and
    // a raw meat "Chicken" as "meat-chicken" -- verified directly against the
    // real search results for several wood and animal ingredient names before
    // adding this (see docs/cookbook-integration.md). Checked in this priority
    // order: bare name first (already correct for e.g. "Salt", "Intestines"),
    // then these role prefixes.
    private static final String[] ROLE_PREFIXES = {"", "meat-", "wblock-", "seed-", "flour-", "fish-"};

    /**
     * Ingredient names with a generic descriptor word tacked on the end
     * ("Reindeer Venison", "Chicken Egg") don't match any filename as a whole
     * -- the descriptor isn't part of it, it's implied by a role prefix on the
     * *other* word instead ("meat-reindeer", "egg-chicken"). Verified directly
     * before adding each of these. Only the forced prefix is tried for these,
     * not the full ROLE_PREFIXES list -- trying "meat-" for an Egg descriptor
     * would find the wrong item (raw meat instead of an egg).
     */
    private static final Map<String, String> DESCRIPTOR_PREFIX = new LinkedHashMap<>();

    static {
        DESCRIPTOR_PREFIX.put("egg", "egg-");
        DESCRIPTOR_PREFIX.put("venison", "meat-");
        DESCRIPTOR_PREFIX.put("leaf", "leaf-");
        DESCRIPTOR_PREFIX.put("leaves", "leaf-");
    }

    private static class Candidate {
        final String searchTerm, wantSlug;
        final String[] prefixes;

        Candidate(String searchTerm, String wantSlug, String[] prefixes) {
            this.searchTerm = searchTerm;
            this.wantSlug = wantSlug;
            this.prefixes = prefixes;
        }
    }

    private static List<Candidate> candidates(String name) {
        List<Candidate> out = new ArrayList<>();
        String full = slug(name);
        if(!full.isEmpty()) {out.add(new Candidate(full, full, ROLE_PREFIXES));}

        String[] words = name.trim().split("\\s+");
        if(words.length > 1) {
            String forced = DESCRIPTOR_PREFIX.get(slug(words[words.length - 1]));
            if(forced != null) {
                String rest = slug(String.join("", java.util.Arrays.copyOf(words, words.length - 1)));
                if(!rest.isEmpty()) {out.add(new Candidate(rest, rest, new String[]{forced}));}
            }
            // "Clove of Garlic" etc: try whatever comes after "of" on its own.
            for(int i = 0; i < words.length - 1; i++) {
                if(words[i].equalsIgnoreCase("of")) {
                    String rest = slug(String.join("", java.util.Arrays.copyOfRange(words, i + 1, words.length)));
                    if(!rest.isEmpty()) {out.add(new Candidate(rest, rest, ROLE_PREFIXES));}
                    break;
                }
            }
        }
        return out;
    }

    private static String pickBest(Candidate c, JSONArray arr) {
        for(String prefix : c.prefixes) {
            String target = slug(prefix) + c.wantSlug;
            for(int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.getJSONObject(i);
                String fname = entry.optString("name", "");
                String relDir = entry.optString("relDir", "");
                if(!fname.endsWith(".res")) {continue;}
                String baseSlug = slug(fname.substring(0, fname.length() - 4));
                if(!baseSlug.equals(target)) {continue;}
                // Only trust a match inside a real inventory-item-icon directory
                // (invobjs, including its subfolders, or terobjs/items). Other
                // directories match by filename too (e.g. "chicken.res" under
                // gfx/kritter is the live animal, gfx/terobjs/mm/trees/osier.res
                // is a minimap tree marker) and would show a flatly wrong icon --
                // verified by testing several real ingredient names directly.
                boolean itemDir = relDir.equals("gfx/invobjs") || relDir.startsWith("gfx/invobjs/")
                    || relDir.startsWith("gfx/terobjs/items");
                if(itemDir) {return relDir + "/" + fname.substring(0, fname.length() - 4);}
            }
        }
        return null;
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
