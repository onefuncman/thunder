package thunder.clearcut;

import haven.Area;

import java.util.ArrayList;
import java.util.List;

/** Immutable, session-scoped settings for one clear-cut run. */
public final class ClearCutConfig {
    public static final double LOW_ENERGY = 0.40;
    public static final int EAT_UNTIL_PERCENT = 80;
    public static final int TREES_PER_HAUL = 4;
    public static final int SURVEY_LANE_TILES = 20;
    public static final int MAX_ATTEMPTS = 3;
    public static final int MAX_PRODUCT_ACTIONS_PER_SOURCE = 100;
    public static final int PRODUCT_MENU_ATTEMPTS = 1;
    public static final long PRODUCT_MENU_TIMEOUT_MS = 3000L;
    public static final long PRODUCT_ACTION_TIMEOUT_MS = 10000L;
    public static final int MAX_LOG_PLACEMENT_ANCHORS = 4;

    public final Area clearCut;
    public final Area water;
    public final Area food;
    public final Area logDropOff;
    public final Area productDropOff;
    public final boolean collectTreeProducts;

    public ClearCutConfig(Area clearCut, Area water, Area food, Area logDropOff,
                          Area productDropOff, boolean collectTreeProducts) {
        this.clearCut = clearCut;
        this.water = water;
        this.food = food;
        this.logDropOff = logDropOff;
        this.productDropOff = productDropOff;
        this.collectTreeProducts = collectTreeProducts;
    }

    public String validationError() {
        if(!positive(clearCut)) return "select a valid clear-cut area";
        if(!positive(logDropOff)) return "select a valid log drop-off area";
        if(clearCut.isects(logDropOff)) return "clear-cut and log drop-off areas must not overlap";
        if(collectTreeProducts && !positive(productDropOff)) return "select a tree-product drop-off area";
        return null;
    }

    public boolean hasWaterArea() {return positive(water);}
    public boolean hasFoodArea() {return positive(food);}

    /** Non-fatal overlaps are surfaced at start because they can make routing less predictable. */
    public List<String> overlapWarnings() {
        List<String> warnings = new ArrayList<>();
        warn(warnings, "clear-cut", clearCut, "water", water);
        warn(warnings, "clear-cut", clearCut, "energy-food", food);
        warn(warnings, "water", water, "energy-food", food);
        warn(warnings, "water", water, "log drop-off", logDropOff);
        warn(warnings, "energy-food", food, "log drop-off", logDropOff);
        if(collectTreeProducts && positive(productDropOff)) {
            warn(warnings, "clear-cut", clearCut, "tree-product drop-off", productDropOff);
            warn(warnings, "water", water, "tree-product drop-off", productDropOff);
            warn(warnings, "energy-food", food, "tree-product drop-off", productDropOff);
            warn(warnings, "log drop-off", logDropOff, "tree-product drop-off", productDropOff);
        }
        return warnings;
    }

    private static void warn(List<String> warnings, String firstName, Area first,
                             String secondName, Area second) {
        if(positive(first) && positive(second) && first.isects(second))
            warnings.add(firstName + " and " + secondName + " areas overlap");
    }

    private static boolean positive(Area area) {
        return area != null && area.positive();
    }
}
