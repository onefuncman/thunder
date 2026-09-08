package thunder.fish;

import java.util.Locale;

/**
 * Pure, side-effect-free classification helpers for the Fish Spit-Roast Bot.
 * Kept deliberately free of GameUI / session state so the rules can be unit
 * tested headlessly.
 */
public final class FishRecognition {
    private FishRecognition() {}

    /** Whole raw fish are recognised by inventory resource name, per the runtime
     *  resource patterns confirmed in-game. */
    public static boolean isRawFishResname(String resname) {
        if (resname == null || resname.isEmpty()) {
            return false;
        }
        // Exact generic resource.
        if ("gfx/invobjs/fish".equals(resname)) {
            return true;
        }
        // Prefixed variants under gfx/invobjs and gfx/invobjs/small.
        if (!resname.startsWith("gfx/invobjs/fish-") && !resname.startsWith("gfx/invobjs/small/fish-")) {
            return false;
        }
        // Exclude fish-derived products (filets, meat, cooked/roasted fish, etc.).
        String lower = resname.toLowerCase(Locale.ROOT);
        for (String derived : DERIVED_FISH_TERMS) {
            if (lower.contains(derived)) {
                return false;
            }
        }
        return true;
    }

    private static final String[] DERIVED_FISH_TERMS = {
        "filet", "fillet", "meat", "roast", "cook", "smoked", "dried", "cured", "salted", "roe", "oil", "glue",
    };

    /** Stacked item display names carry a ", stack of" suffix; strip it before
     *  matching so stacked and unstacked items classify identically. */
    public static String stripStackSuffix(String name) {
        if (name == null) {
            return null;
        }
        String suffix = ", stack of";
        if (name.endsWith(suffix)) {
            return name.substring(0, name.length() - suffix.length());
        }
        return name;
    }

    /** Spit-roasted fish use generic meat resources, so the loaded display/tooltip
     *  name is the only reliable signal. Case-insensitive. */
    public static boolean isCookedDisplayName(String name) {
        String n = stripStackSuffix(name);
        if (n == null) {
            return false;
        }
        String lower = n.toLowerCase(Locale.ROOT);
        return lower.contains("spitroast") || lower.contains("spit roast");
    }

    /** Classification of a roasting-spit's reflected content resource name. */
    public enum SpitState {
        EMPTY,
        RAW,
        COOKED,
        UNKNOWN,
    }

    public static SpitState spitState(String contentResname) {
        if (contentResname == null) {
            return SpitState.EMPTY;
        }
        if (contentResname.toLowerCase(Locale.ROOT).contains("raw")) {
            return SpitState.RAW;
        }
        return SpitState.COOKED;
    }

    public static final int PRIORITY_LARGE_CHEST = 0;
    public static final int PRIORITY_CONTAINER = 1;
    public static final int PRIORITY_TABLE = 2;
    public static final int NOT_OUTPUT = -1;

    /** Output-container priority from a gob resource name alone. Lower is better.
     *  Generic GobTag.CONTAINER gobs whose resid is not on the known list are
     *  handled separately by the bot (they get PRIORITY_CONTAINER). */
    public static int outputPriority(String resid) {
        if (resid == null) {
            return NOT_OUTPUT;
        }
        if ("gfx/terobjs/largechest".equals(resid)) {
            return PRIORITY_LARGE_CHEST;
        }
        if (isTableResid(resid)) {
            return PRIORITY_TABLE;
        }
        if (isKnownContainerResid(resid)) {
            return PRIORITY_CONTAINER;
        }
        return NOT_OUTPUT;
    }

    public static boolean isOutputCandidate(String resid) {
        return outputPriority(resid) != NOT_OUTPUT;
    }

    private static boolean isTableResid(String resid) {
        return "gfx/terobjs/furn/table-rustic".equals(resid)
            || "gfx/terobjs/furn/table-elegant".equals(resid)
            || "gfx/terobjs/htable".equals(resid);
    }

    private static boolean isKnownContainerResid(String resid) {
        return "gfx/terobjs/metalcabinet".equals(resid)
            || "gfx/terobjs/cupboard".equals(resid)
            || "gfx/terobjs/chest".equals(resid)
            || "gfx/terobjs/crate".equals(resid)
            || "gfx/terobjs/coffer".equals(resid)
            || "gfx/terobjs/bonechest".equals(resid)
            || "gfx/terobjs/stonecasket".equals(resid);
    }
}
