package haven.pathfinding;

import auto.Bot;
import haven.Coord2d;
import haven.GameUI;
import haven.GItem;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.WItem;
import haven.layout.LayoutFootprint;
import haven.layout.LayoutPlanResult;
import haven.layout.LayoutPlacement;
import haven.layout.LayoutRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Opt-in v1 organizer: plans and creates stockpiles in a selected area, never fills or moves them. */
public final class StockpileOrganizer {
   private static final long STEP_TIMEOUT_MS = 2500L;
   private static final long WALK_TIMEOUT_MS = 20000L;
   private static final int MAX_PILES = 128;

   private StockpileOrganizer() {}

   public static void start(final GameUI gui, final OrganizerAreaSelector.Selection area) {
      if (gui == null || gui.map == null || area == null) {
         if (gui != null) gui.msg("Stockpile: choose a complete area first", GameUI.MsgType.ERROR);
         return;
      }
      if (Bot.hasCurrent()) {
         gui.msg("Stockpile: another bot is running", GameUI.MsgType.ERROR);
         return;
      }
      final Bot bot = Bot.execute((unused, b) -> run(gui, area, b));
      bot.start(gui.ui);
   }

   private static void run(GameUI gui, OrganizerAreaSelector.Selection area, Bot bot) throws InterruptedException {
      if (gui.map.player() == null || gui.map.player().rc == null) fail(bot, "player position unavailable");
      GameUI.DraggedItem held = gui.hand();
      if (held == null || held.item == null) fail(bot, "hold the item to stockpile first");
      String itemRes = held.item.resname();
      String stockpile = stockpileResource(itemRes);
      if (stockpile == null) fail(bot, "held item is not a supported stockpile item");
      String kind = stockpile.substring(stockpile.lastIndexOf('-') + 1);
      LayoutFootprint footprint = stockpileFootprint(stockpile);
      final double fpCx = footprint.bboxW() * MCache.tilesz.x / 2.0;
      final double fpCy = footprint.bboxH() * MCache.tilesz.y / 2.0;

      gui.msg("Stockpile: planning " + kind + " piles (footprint " + footprint.bboxW() + "x" + footprint.bboxH() + " tiles)…", GameUI.MsgType.INFO);
      PrototypePathfinder.Scene scene = PrototypePathfinder.observe(gui, false);
      if (scene == null || scene.occupancy == null) fail(bot, "local occupancy unavailable; nothing placed");
      LayoutRequest request = LayoutRequest.builder(scene.occupancy, footprint, gui.map.player().rc, MAX_PILES)
         .area(area.min, area.max).pitch(MCache.tilesz.x).build();
      LayoutPlanResult plan = haven.layout.LayoutPlanner.plan(request);
      if (plan.placements == null || plan.placements.isEmpty())
         fail(bot, "area is not plannable (" + reason(plan) + "); nothing placed");
      gui.msg("Stockpile: planned " + plan.placements.size() + " " + kind + " piles", GameUI.MsgType.INFO);

      int created = 0;
      for (LayoutPlacement placement : plan.placements) {
         bot.checkCancelled();
         if (gui.hand() == null || gui.hand().item == null) {
            if (!takeItem(gui, bot, itemRes)) fail(bot, "ran out of matching items after " + created + " piles");
         }

         // Refresh occupancy so already-created piles count as solid before walking.
         PrototypePathfinder.observe(gui, false);
         gui.msg("Stockpile: pile " + (placement.index + 1) + "/" + plan.placements.size() + " walking…", GameUI.MsgType.INFO);
         List<Coord2d> route = new ArrayList<Coord2d>(2);
         route.add(gui.map.player().rc);
         route.add(placement.stand);
         WaypointWalker.Result walked = WaypointWalker.execute(gui, bot, route, 2, WALK_TIMEOUT_MS, listener(gui));
         if (walked != WaypointWalker.Result.ARRIVED && walked != WaypointWalker.Result.READY_TO_INTERACT)
            fail(bot, "could not reach pile " + placement.index + " stand (" + walked + "); created " + created + " piles");

         if (gui.hand() == null || gui.hand().item == null) fail(bot, "held item lost before placing pile " + placement.index);
         Coord2d center = placement.world.add(fpCx, fpCy);
         Set<Long> before = pileIds(gui, stockpile, center);
         if (!gui.map.itemactAt(center, gui.ui.modflags())) fail(bot, "itemact could not be sent for pile " + placement.index);
         if (!waitPlacer(gui, bot)) fail(bot, "server did not enter the placer for pile " + placement.index + "; created " + created + " piles");
         if (!gui.map.placeAt(center, 1, gui.ui.modflags())) fail(bot, "place could not be sent for pile " + placement.index);
         if (!waitNewPile(gui, bot, stockpile, center, before)) fail(bot, "pile " + placement.index + " placement was not acknowledged; created " + created + " piles");
         created++;
      }
      gui.msg("Stockpile: created " + created + " " + kind + " piles (not filled)", GameUI.MsgType.INFO);
   }

   private static void fail(Bot bot, String reason) throws InterruptedException {
      bot.cancel("Stockpile: " + reason);
      throw new InterruptedException(reason);
   }

   private static String reason(LayoutPlanResult plan) {
      return (plan.reason == null || plan.reason.isEmpty()) ? "no free space" : plan.reason;
   }

   /** Lifts one matching item from the main inventory into hand; false if none left. */
   private static boolean takeItem(GameUI gui, Bot bot, String itemRes) throws InterruptedException {
      if (gui.maininv == null) return false;
      final WItem[] found = new WItem[1];
      gui.maininv.forEachItem((gitem, witem) -> {
         if (found[0] == null && witem != null && itemRes.equals(safeResname(gitem))) found[0] = witem;
      });
      if (found[0] == null) return false;
      found[0].take();
      long end = System.currentTimeMillis() + STEP_TIMEOUT_MS;
      while (System.currentTimeMillis() < end) {
         bot.checkCancelled();
         GameUI.DraggedItem h = gui.hand();
         if (h != null && h.item != null) return true;
         Thread.sleep(50L);
      }
      return false;
   }

   private static String safeResname(GItem g) {
      if (g == null) return null;
      try { return g.resname(); } catch (Loading e) { return null; }
   }

   static String stockpileResource(String item) {
      if (item == null || !item.startsWith("gfx/invobjs/")) return null;
      String name = item.substring("gfx/invobjs/".length());
      if (name.length() == 0 || name.indexOf('/') >= 0) return null;
      // Planks are "board-<woodtype>" (there is no bare board); metal bars are "bar-<metal>".
      // Match by prefix so every wood/metal variant works; "board-" and "bar-" avoid
      // colliding with unrelated names like bark/boardgame.
      if (name.equals("board") || name.startsWith("board-")) return "gfx/terobjs/stockpile-board";
      if (name.equals("bar") || name.startsWith("bar-")) return "gfx/terobjs/stockpile-metal";
      if (name.equals("soil") || name.equals("worm")) return "gfx/terobjs/stockpile-soil";
      if (name.equals("pumpkin")) return "gfx/terobjs/stockpile-pumpkin";
      if (name.equals("straw")) return "gfx/terobjs/stockpile-straw";
      if (name.equals("brick")) return "gfx/terobjs/stockpile-brick";
      if (name.equals("leaf") || name.equals("leaves")) return "gfx/terobjs/stockpile-leaf";
      return null;
   }

   /**
    * Reserved world footprint for a stockpile resource, derived from its real
    * collision geometry (see {@link ObjectFootprints}); falls back to the
    * per-type table in {@link #fallbackHalfExtents(String)} when unreadable.
    */
   static LayoutFootprint stockpileFootprint(String stockpileRes) {
      return ObjectFootprints.footprintFor(stockpileRes, fallbackHalfExtents(stockpileRes));
   }

   static Coord2d fallbackHalfExtents(String resname) {
      if (resname == null) return Coord2d.of(11, 11);
      if (resname.endsWith("-metal")) return Coord2d.of(5.5, 8.25);
      if (resname.endsWith("-straw") || resname.endsWith("-leaf")) return Coord2d.of(8.25, 8.25);
      if (resname.endsWith("-brick")) return Coord2d.of(11, 5.5);
      return Coord2d.of(11, 11); // board / soil / pumpkin / generic
   }

   private static boolean waitPlacer(GameUI gui, Bot bot) throws InterruptedException {
      long end = System.currentTimeMillis() + STEP_TIMEOUT_MS;
      while (System.currentTimeMillis() < end) {
         bot.checkCancelled();
         if (gui.map.isPlacing()) return true;
         Thread.sleep(50L);
      }
      return false;
   }

   private static boolean waitNewPile(GameUI gui, Bot bot, String res, Coord2d center, Set<Long> before) throws InterruptedException {
      long end = System.currentTimeMillis() + STEP_TIMEOUT_MS;
      while (System.currentTimeMillis() < end) {
         bot.checkCancelled();
         if (!pileIds(gui, res, center).equals(before)) return true;
         Thread.sleep(50L);
      }
      return false;
   }

   private static Set<Long> pileIds(GameUI gui, String res, Coord2d center) {
      Set<Long> ids = new HashSet<Long>();
      synchronized (gui.ui.sess.glob.oc) {
         for (Gob gob : gui.ui.sess.glob.oc) {
            if (gob == null || gob.rc == null || gob.id < 0L || gob.rc.dist(center) > MCache.tilesz.x * 2.5) continue;
            try { if (res.equals(gob.resid())) ids.add(Long.valueOf(gob.id)); }
            catch (Loading ignored) {}
         }
      }
      return ids;
   }

   private static WaypointWalker.Listener listener(final GameUI gui) {
      return new WaypointWalker.Listener() {
         public void event(String s) {
            gui.msg("Stockpile: " + s, GameUI.MsgType.INFO);
         }
         public void fail(String s) {
            gui.msg("Stockpile: " + s, GameUI.MsgType.ERROR);
         }
         public void fail(String s, WaypointGate.Outcome o, String brief) {
            gui.msg("Stockpile: " + s + " (" + o + ")", GameUI.MsgType.ERROR);
         }
         public void beginWait(String kind, long budget, String detail) {
         }
         public void dumpStuck() {
         }
      };
   }
}
