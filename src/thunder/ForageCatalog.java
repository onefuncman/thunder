package thunder;

import haven.Gob;
import haven.GobTag;
import haven.Loading;
import haven.CFG;
import java.util.*;

/** Human-facing forageable names mapped to stable resource basenames. */
public final class ForageCatalog {
    public static final class Entry {
        public final String key;
        public final String name;
        Entry(String key, String name) {this.key = key; this.name = name;}
    }

    private static final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    static {
        add("baybolete", "Bay Bolete"); add("blacktrumpet", "Black Trumpets");
        add("bloodstern", "Blood Stern"); add("bloatedbolete", "Bloated Bolete");
        add("blueberry", "Blueberries"); add("candleberry", "Candleberry");
        add("cavebulb", "Cavebulb"); add("chantrelle", "Chantrelles");
        add("chives", "Chives"); add("clover", "Clover"); add("coltsfoot", "Coltsfoot");
        add("dandelion", "Dandelion"); add("duskfern", "Dusk Fern"); add("edelweiss", "Edelweiss");
        add("fairymushroom", "Fairy Mushroom"); add("giantpuffball", "Giant Puffball");
        add("glimmermoss", "Glimmermoss"); add("greenkelp", "Green Kelp"); add("heartsease", "Heartsease");
        add("kvann", "Kvann"); add("ladysmantle", "Lady's Mantle"); add("libertycap", "Liberty Caps");
        add("lingon", "Lingonberries"); add("morel", "Morels"); add("oystermushroom", "Oyster Mushroom");
        add("parasolshroom", "Parasol Mushroom"); add("perfectautumnleaf", "Perfect Autumn Leaf");
        add("rubybolete", "Ruby Bolete"); add("rustroot", "Rustroot");
        add("snowtop", "Snowtop"); add("spindlytaproot", "Spindly Taproot");
        add("stingingnettle", "Stinging Nettle"); add("strawberry", "Strawberries");
        add("tansy", "Tansy"); add("uncommonsnapdragon", "Uncommon Snapdragon");
        add("windweed", "Wild Windsown Weed");
        add("yellowfoot", "Yellowfeet"); add("yarrow", "Yarrow");
        add("champignon", "Champignon"); add("clay-gray", "Gray Clay"); add("lakesnail", "Lake Snail");
        add("mussels", "River Pearl Mussel"); add("oyster", "Oyster"); add("pearloyster", "Pearl Oyster");
        add("razorclams", "Razor Clam"); add("roundclam", "Round Clam");
        add("goosebarnacle", "Gooseneck Barnacle"); add("brownkelp", "Brown Kelp"); add("driftkelp", "Driftkelp");
        for(String key : CFG.DIRECTIONAL_FORAGER_DISCOVERED.get()) {
            if(key != null && !key.isEmpty() && !entries.containsKey(key)) add(key, pretty(key));
        }
    }

    private ForageCatalog() {}
    private static void add(String key, String name) {entries.put(key, new Entry(key, name));}

    public static synchronized List<Entry> entries() {
        List<Entry> out = new ArrayList<>(entries.values());
        out.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Learn exact herb basenames from live resources without selecting them. */
    public static synchronized void observe(Gob gob) {
        if(gob == null || !gob.is(GobTag.HERB)) return;
        try {
            String key = key(gob.resid());
            if(key != null && !entries.containsKey(key)) {
                add(key, pretty(key));
                Set<String> discovered = new HashSet<>(CFG.DIRECTIONAL_FORAGER_DISCOVERED.get());
                if(discovered.add(key)) CFG.DIRECTIONAL_FORAGER_DISCOVERED.set(discovered);
            }
        } catch(Loading ignored) {}
    }

    public static synchronized boolean isSelected(Gob gob, Set<String> selected) {
        if(gob == null || selected == null || selected.isEmpty()) return false;
        try {
            String key = key(gob.resid());
            return key != null && selected.contains(key) && (gob.is(GobTag.HERB) || entries.containsKey(key));
        } catch(Loading e) {return false;}
    }

    public static boolean isSelectedInventoryResource(String resid, Set<String> selected) {
        String key = key(resid);
        return key != null && selected != null && selected.contains(key);
    }

    public static String key(String resid) {
        if(resid == null || resid.isEmpty()) return null;
        int slash = resid.lastIndexOf('/');
        String key = (slash >= 0 ? resid.substring(slash + 1) : resid).toLowerCase(Locale.ROOT);
        int bracket = key.indexOf('[');
        return bracket > 0 ? key.substring(0, bracket) : key;
    }

    private static String pretty(String key) {
        return key == null || key.isEmpty() ? "Unknown forageable" : Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }
}
