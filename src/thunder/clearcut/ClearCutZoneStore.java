package thunder.clearcut;

import haven.Area;

import java.util.HashMap;
import java.util.Map;

/** Session-only areas selected by the Clear-Cut setup window. */
public final class ClearCutZoneStore {
    public static final String ROLE_CLEAR_CUT = "clearcut-work";
    public static final String ROLE_WATER = "clearcut-water";
    public static final String ROLE_FOOD = "clearcut-food";
    public static final String ROLE_LOG_DROP = "clearcut-log-drop";
    public static final String ROLE_PRODUCT_DROP = "clearcut-product-drop";

    private static final ClearCutZoneStore INSTANCE = new ClearCutZoneStore();
    private final Map<String, Area> zones = new HashMap<>();

    private ClearCutZoneStore() {}
    public static ClearCutZoneStore get() {return INSTANCE;}
    public synchronized Area get(String role) {return zones.get(role);}
    public synchronized void put(String role, Area area) {zones.put(role, area);}
    public synchronized void clear(String role) {zones.remove(role);}
    public synchronized void clearAll() {zones.clear();}
}
