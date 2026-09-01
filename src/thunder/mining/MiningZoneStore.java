package thunder.mining;

import haven.Area;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds MiningBot's designated zones (stockpile/water/food) as tile-space
 * Areas, one per role. In-memory / session-scoped only, on purpose --
 * a zone from a previous run should never silently reappear and overlap a
 * freshly-drawn one. Cleared explicitly when the setup window closes
 * (see MiningBotSetupWnd.destroy()).
 */
public class MiningZoneStore {
    public static final String ROLE_STOCKPILE = "stockpile";
    public static final String ROLE_WATER = "water";
    public static final String ROLE_FOOD = "food";

    private static final MiningZoneStore instance = new MiningZoneStore();
    private final Map<String, Area> zones = new HashMap<>();

    private MiningZoneStore() {}

    public static MiningZoneStore get() {
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
