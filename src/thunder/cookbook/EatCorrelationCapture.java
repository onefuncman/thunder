package thunder.cookbook;

import haven.GItem;
import haven.ItemInfo;
import haven.Resource;
import haven.resutil.FoodInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correlates satiation changes (SatiationCapture's CONST log lines) with the
 * SPECIFIC item that caused them -- its exact quality-scaled hunger/FEP
 * values, not just "some Meat-category food." SatiationCapture alone showed
 * the per-bite satiation increase differs between eating sessions in a way
 * that doesn't fit a single constant (0.046 vs 0.032 in the "odds" transform
 * across two different pork-eating runs), and the leading hypothesis is that
 * the increment scales with the specific piece's own stats (quality varies
 * piece to piece, visibly, in the user's own inventory screenshots) -- but
 * eating consumes the item before its stats can be read directly. So this
 * takes a snapshot of every visible food item's real stats the first time
 * it's drawn, and logs it as gone once it stops being drawn for over half a
 * second (a background sweeper thread, since nothing calls us again once an
 * item is destroyed to tell us it's gone) -- the resulting ITEM-GONE log
 * line should land within ~1s of the CONST line it actually caused, letting
 * the two be matched up by eye afterward. Hooked from WItem.draw() alongside
 * ItemResourceCapture. See docs/eating-helper.md.
 */
public class EatCorrelationCapture {
    private static class Snapshot {
        final String name;
        final double hunger;
        final double energy;
        final String feps;
        volatile long lastSeen;

        Snapshot(String name, double hunger, double energy, String feps) {
            this.name = name;
            this.hunger = hunger;
            this.energy = energy;
            this.feps = feps;
        }
    }

    private static final Map<GItem, Snapshot> tracked = new ConcurrentHashMap<>();
    private static volatile boolean sweeperStarted = false;
    private static final long GONE_AFTER_MS = 600;
    private static final long SWEEP_INTERVAL_MS = 250;

    public static void consider(GItem item) {
        FoodInfo finf;
        try {
            finf = ItemInfo.find(FoodInfo.class, item.info());
        } catch(Exception e) {
            return;
        }
        if(finf == null) {return;}

        Snapshot snap = tracked.get(item);
        if(snap == null) {
            String name = "?";
            try {
                Resource.Tooltip tt = item.getres().layer(Resource.tooltip);
                if(tt != null) {name = tt.t;}
            } catch(Exception ignored) {}
            StringBuilder feps = new StringBuilder();
            for(FoodInfo.Event ev : finf.evs) {
                if(feps.length() > 0) {feps.append(",");}
                feps.append(ev.ev.nm).append("=").append(String.format("%.5f", ev.a));
            }
            snap = new Snapshot(name, finf.glut, finf.end, feps.toString());
            tracked.put(item, snap);
            SatiationCapture.log(String.format("ITEM-SEEN %s hunger=%.5f energy=%.5f feps=[%s]", name, finf.glut, finf.end, feps));
        }
        snap.lastSeen = System.currentTimeMillis();
        ensureSweeper();
    }

    private static void ensureSweeper() {
        if(sweeperStarted) {return;}
        synchronized(EatCorrelationCapture.class) {
            if(sweeperStarted) {return;}
            sweeperStarted = true;
            Thread t = new Thread(() -> {
                while(true) {
                    try {
                        Thread.sleep(SWEEP_INTERVAL_MS);
                    } catch(InterruptedException e) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    for(Map.Entry<GItem, Snapshot> e : tracked.entrySet()) {
                        Snapshot s = e.getValue();
                        if((now - s.lastSeen) > GONE_AFTER_MS) {
                            SatiationCapture.log(String.format("ITEM-GONE %s hunger=%.5f energy=%.5f feps=[%s]", s.name, s.hunger, s.energy, s.feps));
                            tracked.remove(e.getKey());
                        }
                    }
                }
            }, "EatCorrelationCapture-sweeper");
            t.setDaemon(true);
            t.start();
        }
    }
}
