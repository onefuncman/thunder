package thunder.cellar;

import haven.Coord2d;

import java.util.Locale;

/** Pure resource and safety rules for the Cellar Digger state machine. */
public final class CellarDiggerRules {
    public static final double LOW_ENERGY = 0.25;
    public static final double LOW_STAMINA = 0.40;
    public static final double STAMINA_RECOVERY_TARGET = 0.80;
    public static final int MIN_AUTO_DRINK_THRESHOLD = 40;
    public static final int MAX_ATTEMPTS = 3;
    public static final int MAX_CHIPS_PER_BOULDER = 500;
    public static final int MAX_DOOR_CYCLES = 128;
    public static final int GROUNDED_STABLE_SAMPLES = 3;

    private static final String CELLAR_DOOR = "gfx/terobjs/arch/cellardoor";
    private static final String BUMLING_PREFIX = "gfx/terobjs/bumlings/";

    private CellarDiggerRules() {}

    public enum DoorOutcome {CONTINUE, COMPLETE, RETRY}

    public static boolean isCellarDoor(String resid) {
        return CELLAR_DOOR.equals(baseResid(resid));
    }

    public static boolean isCellarTransition(String resid) {
        String n = baseResid(resid);
        return "gfx/terobjs/arch/cellarstairs".equals(n) ||
            "gfx/terobjs/arch/downstairs".equals(n) ||
            "gfx/terobjs/arch/upstairs".equals(n);
    }

    /** Cellar excavation creates only staged bumlings; ordinary surface
     * boulders are deliberately outside this bot's scope. */
    public static boolean isBumling(String resid) {
        String n = baseResid(resid);
        return n.startsWith(BUMLING_PREFIX) && n.length() > BUMLING_PREFIX.length();
    }

    /** Drawable state can be appended as a bracketed suffix by the client. */
    static String baseResid(String resid) {
        String n = resid == null ? "" : resid.toLowerCase(Locale.ROOT);
        int bracket = n.indexOf('[');
        return bracket > 0 ? n.substring(0, bracket) : n;
    }

    public static boolean energyTooLow(double energy) {
        return energy >= 0.0 && energy < LOW_ENERGY;
    }

    public static boolean staminaNeedsRecovery(double stamina) {
        return stamina >= 0.0 && stamina < LOW_STAMINA;
    }

    public static boolean staminaRecovered(double stamina) {
        return stamina >= STAMINA_RECOVERY_TARGET;
    }

    public static boolean autoDrinkThresholdSafe(int thresholdPercent) {
        return thresholdPercent >= MIN_AUTO_DRINK_THRESHOLD;
    }

    /** A released bumling is safe to interact with only after its authoritative
     * ground position has remained unchanged across several client samples. */
    public static boolean groundedBoulderStable(int stableSamples) {
        return stableSamples >= GROUNDED_STABLE_SAMPLES;
    }

    static int exactMenuOption(String[] options, String wanted) {
        if(options == null || wanted == null) return -1;
        for(int i = 0; i < options.length; i++) if(wanted.equals(options[i])) return i;
        return -1;
    }

    static boolean withinDirectInteractionRange(double distance, double limit) {
        return Double.isFinite(distance) && Double.isFinite(limit) &&
            distance >= 0.0 && limit >= 0.0 && distance <= limit;
    }

    /** Chooses the ground-right-click used to release a cellar bumling. The
     * preferred direction points back toward the open side from which the
     * player approached the cellar door. If excavation has already pulled the
     * player exactly onto the door, the opposite of the facing direction is the
     * same fallback. Later attempts fan 30 degrees to either side. */
    static Coord2d boulderDropTarget(Coord2d door, Coord2d approach,
                                     double facing, double distance, int attempt) {
        if(door == null || !(distance > 0.0) || !Double.isFinite(distance) ||
           !Double.isFinite(facing) || attempt < 1 || attempt > MAX_ATTEMPTS) return null;
        double dx = approach == null ? 0.0 : approach.x - door.x;
        double dy = approach == null ? 0.0 : approach.y - door.y;
        double length = Math.hypot(dx, dy);
        if(length < 0.25) {
            dx = -Math.cos(facing);
            dy = -Math.sin(facing);
            length = 1.0;
        }
        dx /= length;
        dy /= length;
        double turn = attempt == 2 ? Math.PI / 6.0 : attempt == 3 ? -Math.PI / 6.0 : 0.0;
        double cs = Math.cos(turn), sn = Math.sin(turn);
        return door.add((dx * cs - dy * sn) * distance,
                        (dx * sn + dy * cs) * distance);
    }

    public static DoorOutcome doorOutcome(boolean bumlingVisible, boolean doorVisible,
                                          boolean cellarTransitionVisible) {
        if(bumlingVisible) return DoorOutcome.CONTINUE;
        if(!doorVisible && cellarTransitionVisible) return DoorOutcome.COMPLETE;
        return DoorOutcome.RETRY;
    }
}
