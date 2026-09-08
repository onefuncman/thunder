package thunder.fish;

import haven.Area;

import java.util.HashMap;
import java.util.Map;

/**
 * Session-only storage for the Fish Spit-Roast Bot's three designated map
 * areas (raw-fish input, fire/roasting-spit, cooked-fish output). Mirrors
 * Keeps its own roles so fish-zone cleanup
 * never touches mining-zone overlays.
 */
public class FishSpitRoastZoneStore {
    public static final String ROLE_INPUT = "fish-input";
    public static final String ROLE_FIRE = "fish-fire";
    public static final String ROLE_OUTPUT = "fish-output";

    private static final FishSpitRoastZoneStore instance = new FishSpitRoastZoneStore();
    private final Map<String, Area> zones = new HashMap<>();

    private FishSpitRoastZoneStore() {}

    public static FishSpitRoastZoneStore get() {
        return instance;
    }

    public synchronized Area get(String role) {
        return zones.get(role);
    }

    public synchronized void put(String role, Area area) {
        zones.put(role, area);
    }

    public synchronized void clear(String role) {
        zones.remove(role);
    }

    public synchronized void clearAll() {
        zones.clear();
    }
}
