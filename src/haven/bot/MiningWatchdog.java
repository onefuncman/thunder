package haven.bot;

import auto.MiningBot;
import haven.*;

/**
 * Safety watchdog for MiningBot: ticks once per player logic frame (wired
 * from Gob.botActions(), same site as AutoDrink/AutoEat) and is a no-op
 * unless a mining run is currently active. Scans for nearby dangerous gobs
 * (trolls etc.) and aborts + flees the moment one comes within range.
 *
 * Stamina/hunger are handled by the already-standing AutoDrink/AutoEat
 * watchdogs, which tick independently of this one and of whether a mining
 * run is active.
 */
public class MiningWatchdog {
    private static final MiningWatchdog instance = new MiningWatchdog();
    private static final long SCAN_INTERVAL_MS = 350;

    private long lastScan = 0;

    private MiningWatchdog() {}

    public static MiningWatchdog getInstance() {
        return instance;
    }

    public void tick(Gob player) {
        if(!MiningBot.isRunning()) {return;}
        if(player.glob == null || player.glob.sess == null || player.glob.sess.ui == null) {return;}
        GameUI gui = player.glob.sess.ui.gui;
        if(gui == null || gui.map == null) {return;}

        long now = System.currentTimeMillis();
        if(now - lastScan < SCAN_INTERVAL_MS) {return;}
        lastScan = now;

        Gob threat = findNearestThreat(gui, player);
        if(threat != null) {
            String label = describe(threat);
            MiningBot.abort(label + " spotted -- fleeing!");
            flee(gui, player, threat, label);
        }
    }

    private Gob findNearestThreat(GameUI gui, Gob player) {
        double radius = CFG.MINEBOT_FLEE_RADIUS.get();
        Gob nearest = null;
        double nearestDist = Double.MAX_VALUE;
        synchronized(gui.ui.sess.glob.oc) {
            for(Gob gob : gui.ui.sess.glob.oc) {
                if(gob == player || gob.disposed()) {continue;}
                if(!isDangerous(gob)) {continue;}
                double d = gob.rc.dist(player.rc);
                if(d <= radius && d < nearestDist) {
                    nearest = gob;
                    nearestDist = d;
                }
            }
        }
        return nearest;
    }

    /* Mirrors GobWarning.categorize's "is this gob dangerous" check
     * (package-private to haven, so re-derived here rather than called
     * directly): a live aggressive animal (trolls included, GobTag.java's
     * AGGRO list) or a live hostile player. */
    static boolean isDangerous(Gob gob) {
        if(gob.anyOf(GobTag.DEAD, GobTag.KO)) {return false;}
        return gob.is(GobTag.AGGRESSIVE) || gob.is(GobTag.FOE);
    }

    /* Walk to a point past the player, along the line from the threat
     * through the player, same math shape as CombatDistanceTool.getNewCoord
     * (move-toward-target) with the anchor/distance inverted to move away. */
    private void flee(GameUI gui, Gob player, Gob threat, String label) {
        double angle = threat.rc.angle(player.rc);
        double distanceFromThreat = threat.rc.dist(player.rc) + CFG.MINEBOT_FLEE_RADIUS.get();
        Coord2d dest = new Coord2d(
            threat.rc.x + distanceFromThreat * Math.cos(angle),
            threat.rc.y + distanceFromThreat * Math.sin(angle));
        gui.map.wdgmsg("click", Coord.z, dest.floor(OCache.posres), 1, 0);
        gui.msg("Fleeing from " + label + "!", GameUI.MsgType.ERROR);
    }

    private String describe(Gob gob) {
        String id = gob.resid();
        if(id == null) {return "Something dangerous";}
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }
}
