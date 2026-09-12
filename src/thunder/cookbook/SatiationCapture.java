package thunder.cookbook;

import haven.Config;

import java.io.File;
import java.io.FileWriter;

/**
 * Raw, timestamped dump of every real "food"/"glut"/"const"/"lvl"/"ftrig" server
 * message BAttrWnd receives (see the hook calls in BAttrWnd.uimsg) -- there's no
 * published formula for how much eating a food raises its category's satiation
 * penalty (confirmed: the Food_Satiations wiki page gives the EFFECT of a
 * satiation penalty on FEP gain, never the rate it accumulates at), so instead
 * of guessing this observes it directly from a real eating session, the same
 * "no offline source, capture it live" approach as RecipeCapture. The user eats
 * normally; afterward this log gets read back and correlated by hand/eye against
 * what was eaten in what order. See docs/eating-helper.md.
 */
public class SatiationCapture {
    private static final String FILE = "cookbook-satiation-debug.log";

    public static void log(String line) {
        try {
            File f = Config.getFile(FILE);
            try(FileWriter fw = new FileWriter(f, true)) {
                fw.write(System.currentTimeMillis() + " " + line + "\n");
            }
        } catch(Exception ignored) {}
    }

    // Wall-clock time of the last real "food"/"glut"/"const" server message BAttrWnd.uimsg
    // received (see markUpdated() call sites there) -- lets EatingHelperWnd's auto-eat wait
    // for CONFIRMED server state after the last bite in a round instead of a blind fixed
    // delay. Raised directly by the user after a Discord discussion about whether a fast
    // eat-then-check cadence could outrun the server/network under bad ping -- a delay long
    // enough for a good connection could still check stale data on a bad one, and vice
    // versa a delay picked safe for a bad connection wastes time on a good one. Confirming
    // an actual update arrived sidesteps guessing a "safe" number entirely.
    private static volatile long lastUpdate = 0;

    public static void markUpdated() {
        lastUpdate = System.currentTimeMillis();
    }

    public static long lastUpdate() {
        return lastUpdate;
    }
}
