package haven.pathfinding;

import auto.Bot;
import auto.Bot.BotAction;
import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GOut;
import haven.GameUI;
import haven.Gob;
import haven.Hitbox;
import haven.Inventory;
import haven.Loading;
import haven.MCache;
import haven.MapView;
import haven.Moving;
import haven.OCache;
import haven.Window;
import haven.Console.Command;
import haven.GameUI.MsgType;
import haven.dev.DevFeature;
import haven.dev.Feature;
import haven.rx.Reactor;
import java.awt.Color;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import rx.Subscription;

public final class MechanicsProbe implements Feature {
   private static final Color TEXT = new Color(220, 255, 180);
   private static final Color FAIL = new Color(255, 90, 90);
   private static final long CLICK_MS = 5000L;
   private static final long STEP_MS = 8000L;
   private static volatile PrototypePathfinder.Scene last;
   private static volatile String lastKind = "";
   private static volatile String lastDetail = "";

   public String name() {
      return "probe";
   }

   public CFG<Boolean> toggle() {
      return CFG.DEBUG_PF_PROBE;
   }

   public JSONObject capture() {
      return lastJson();
   }

   public void replay(JSONObject body, PrintStream out) {
      if (body == null) {
         out.println("no probe capture");
      } else {
         out.println(body.toString(2));
      }
   }

   public void paint(GOut g, MapView mv) {
      paintHud(g, mv);
   }

   public Map<String, Command> extraVerbs() {
      Map<String, Command> verbs = new LinkedHashMap<>();
      verbs.put("room", (cons, args) -> {
      });
      return verbs;
   }

   static void console(GameUI gui, String[] args) throws Exception {
      if (args.length != 2 && (args.length < 3 || !"room".equals(args[2]))) {
         if (args.length >= 3 && "click".equals(args[2])) {
            Long id = args.length >= 4 ? Long.valueOf(args[3]) : null;
            click(gui, id);
         } else if (args.length >= 3 && "step".equals(args[2])) {
            if (args.length == 6 && "rel".equals(args[3])) {
               stepRel(gui, Double.parseDouble(args[4]), Double.parseDouble(args[5]));
            } else {
               throw new Exception("Usage: pf probe step rel <x> <y>");
            }
         } else if (args.length >= 3 && "last".equals(args[2])) {
            printLast(gui.ui.cons.out);
         } else {
            throw new Exception("Usage: pf probe [room] | pf probe click [id] | pf probe step rel <x> <y> | pf probe last");
         }
      } else {
         room(gui);
      }
   }

   static PrototypePathfinder.Scene lastScene() {
      return last;
   }

   static void room(GameUI gui) {
      PrototypePathfinder.Scene scene = PrototypePathfinder.observe(gui);
      last = scene;
      lastKind = "occupancy";
      if (scene.occupancy != null) {
         PathfinderLog.recordOccupancy(scene.occupancy);
      }

      CFG.DEBUG_PATHFIND.set(true);
      JSONObject o = sceneJson(gui, scene);
      o.put("kind", "occupancy");
      append(o);
      lastDetail = String.format(
         "idle=%s  solid=%s  inflated=%s  cups=%d  %s",
         !scene.moving,
         scene.playerInSolid,
         scene.playerInDilated,
         cupCount(scene),
         scene.playerInSolid && !scene.moving ? "LEGAL POS MARKED SOLID" : "ok"
      );
      gui.msg("PF probe: " + lastDetail, scene.playerInSolid && !scene.moving ? MsgType.ERROR : MsgType.INFO);
      gui.msg("wrote " + logFile(), MsgType.INFO);
   }

   static void click(GameUI gui, Long gobId) {
      Bot.execute(new BotAction[]{(t, b) -> runClick(gui, b, gobId)}).start(gui.ui, true);
   }

   static void stepRel(GameUI gui, double dx, double dy) {
      Bot.execute(new BotAction[]{(t, b) -> runStep(gui, b, dx, dy)}).start(gui.ui, true);
   }

   private static void runClick(GameUI gui, Bot bot, Long gobId) throws InterruptedException {
      PrototypePathfinder.Scene before = PrototypePathfinder.observe(gui);
      last = before;
      Gob player = gui.map.player();
      Gob gob = pickCupboard(gui, player, gobId);
      if (gob == null) {
         lastKind = "click";
         lastDetail = "no cupboard";
         gui.msg("PF probe click: no cupboard", MsgType.ERROR);
      } else {
         Coord2d start = player.rc;
         boolean moving0 = player.getattr(Moving.class) != null;
         Coord2d clickAt = gob.rc;
         if (start.dist(gob.rc) > 1.0) {
            clickAt = gob.rc.add(start.sub(gob.rc).norm().mul(3.8499999999999996));
         }

         List<Long> extenders = openContainers(gui);
         JSONObject o = sceneJson(gui, before);
         o.put("kind", "click");
         o.put("target", gob.id);
         o.put("target_resid", gob.resid());
         o.put("target_a", round(gob.a));
         o.put("click", arr(clickAt));
         o.put("poly_dist", round(PrototypePathfinder.minPolyDist(start, Hitbox.worldPolygons(gob, false)) / MCache.tilesz.x));
         o.put("extender_before", extenders);
         o.put("moving_before", moving0);
         Set<Integer> windowsBefore = windowIds(gui);
         FlowerMenu.lastGob(gob);
         Subscription sub = Reactor.FLOWER.first().subscribe(m -> m.forceChoose(new String[]{"Open"}));

         try {
            gui.map.click(clickAt, 3, new Object[]{Coord.z, clickAt.floor(OCache.posres), 3, 0, 0, (int)gob.id, clickAt.floor(OCache.posres), 0, -1});
            long t0 = System.currentTimeMillis();
            boolean moved = false;
            boolean opened = false;

            while (System.currentTimeMillis() - t0 < 5000L) {
               bot.checkCancelled();
               Gob me = gui.map.player();
               if (me != null && me.rc != null && me.rc.dist(start) > 0.6875) {
                  moved = true;
               }

               if (windowFor(gui, gob) != null || extraWindow(gui, windowsBefore) != null) {
                  opened = true;
               }

               if (opened) {
                  break;
               }

               Thread.sleep(50L);
            }

            Gob mex = gui.map.player();
            Coord2d stop = mex == null ? start : mex.rc;
            o.put("moved", moved);
            o.put("opened", opened);
            o.put("stop", arr(stop));
            o.put("stop_dist_gob", round(stop.dist(gob.rc) / MCache.tilesz.x));
            o.put("extender_after", openContainers(gui));
            o.put("outcome", opened ? "interaction_success" : "interaction_rejected");
            lastKind = "click";
            lastDetail = String.format(
               "#%d %s  moved=%s  poly=%.2ft  opened=%s",
               gob.id,
               opened ? "OPEN" : "NO WINDOW",
               moved,
               PrototypePathfinder.minPolyDist(start, Hitbox.worldPolygons(gob, false)) / MCache.tilesz.x,
               opened
            );
            gui.msg("PF probe click: " + lastDetail, opened ? MsgType.INFO : MsgType.ERROR);
         } finally {
            if (sub != null && !sub.isUnsubscribed()) {
               sub.unsubscribe();
            }
         }

         append(o);
      }
   }

   private static void runStep(GameUI gui, Bot bot, double dx, double dy) throws InterruptedException {
      PrototypePathfinder.Scene before = PrototypePathfinder.observe(gui);
      last = before;
      if (before.occupancy != null) {
         PathfinderLog.recordOccupancy(before.occupancy);
      }

      Gob player = gui.map.player();
      if (player != null && player.rc != null) {
         Coord2d start = player.rc;
         Coord2d dest = start.add(dx, dy);
         JSONObject o = sceneJson(gui, before);
         o.put("kind", "step");
         o.put("dest", arr(dest));
         o.put("start_in_solid", before.playerInSolid);
         o.put("start_in_inflated", before.playerInDilated);
         o.put("moving_before", before.moving);
         gui.map.wdgmsg("click", new Object[]{Coord.z, dest.floor(OCache.posres), 1, 0});
         long t0 = System.currentTimeMillis();
         boolean moved = false;

         while (System.currentTimeMillis() - t0 < 8000L) {
            bot.checkCancelled();
            Gob me = gui.map.player();
            if (me != null && me.rc != null && me.rc.dist(start) > 0.6875) {
               moved = true;
            }

            if (moved && me != null && me.getattr(Moving.class) == null) {
               break;
            }

            Thread.sleep(50L);
         }

         PrototypePathfinder.Scene after = PrototypePathfinder.observe(gui);
         last = after;
         if (after.occupancy != null) {
            PathfinderLog.recordOccupancy(after.occupancy);
         }

         Gob mex = gui.map.player();
         Coord2d stop = mex != null && mex.rc != null ? mex.rc : start;
         o.put("moved", moved);
         o.put("stop", arr(stop));
         o.put("travelled", round(stop.dist(start) / MCache.tilesz.x));
         o.put("stop_in_solid", after.playerInSolid);
         o.put("stop_in_inflated", after.playerInDilated);
         o.put("idle_after", mex == null || mex.getattr(Moving.class) == null);
         String outcome = "step_done";
         if (!before.moving && before.playerInSolid) {
            outcome = "start_in_solid";
         }

         if (after.playerInSolid && (mex == null || mex.getattr(Moving.class) == null)) {
            outcome = "stop_in_solid";
         }

         o.put("outcome", outcome);
         lastKind = "step";
         lastDetail = String.format(
            "moved=%s  %.2ft  start_solid=%s  stop_solid=%s", moved, stop.dist(start) / MCache.tilesz.x, before.playerInSolid, after.playerInSolid
         );
         append(o);
         gui.msg("PF probe step: " + lastDetail, after.playerInSolid ? MsgType.ERROR : MsgType.INFO);
      }
   }

   private static void paintHud(GOut g, MapView mv) {
      PrototypePathfinder.Scene scene = last;
      if (scene != null || !lastKind.isEmpty()) {
         g.chcolor(scene != null && scene.playerInSolid && !scene.moving ? FAIL : TEXT);
         int y = 12;
         g.atext("probe  " + lastKind + (lastDetail.isEmpty() ? "" : "  " + lastDetail), new Coord(12, y), 0.0, 0.0);
         if (scene != null) {
            y += 14;
            g.atext(
               String.format(
                  "player (%.1f,%.1f)  r=%.1f  %s  cell %s/%s",
                  scene.player == null ? 0.0 : scene.player.x,
                  scene.player == null ? 0.0 : scene.player.y,
                  scene.radius,
                  scene.terrain,
                  scene.playerInSolid ? "SOLID" : "free",
                  scene.playerInDilated ? "INFLATED" : "open"
               ),
               new Coord(12, y),
               0.0,
               0.0
            );
            y += 14;
            int shown = 0;

            for (PrototypePathfinder.GobGeom gob : scene.gobs) {
               if (gob.cupboard || !(gob.polyDist > 12.0)) {
                  g.atext(
                     String.format(
                        "%s #%d  gob=%.2ft  poly=%.2ft  a=%.2f",
                        gob.cupboard ? "cup" : gob.name,
                        gob.id,
                        gob.gobDist / MCache.tilesz.x,
                        gob.polyDist / MCache.tilesz.x,
                        gob.a
                     ),
                     new Coord(12, y),
                     0.0,
                     0.0
                  );
                  y += 14;
                  if (++shown >= 8) {
                     break;
                  }
               }
            }
         }

         g.chcolor();
      }
   }

   static void printLast(PrintWriter out) {
      out.println("Probe log: " + logFile());
      if (last == null) {
         out.println("(empty — :pf probe room)");
      } else {
         out.println(lastDetail);
         if (last.occupancy != null) {
            out.println(last.occupancy.ascii(last.playerCell, 10));
         }
      }
   }

   static Path logFile() {
      return PathfinderLog.logFile().getParent().resolve("probe.jsonl");
   }

   private static JSONObject lastJson() {
      JSONObject o = new JSONObject();
      o.put("kind", lastKind);
      o.put("detail", lastDetail);
      o.put("log_file", logFile().toString());
      if (last != null) {
         o.put("scene", sceneJson(null, last));
      }

      return o;
   }

   private static JSONObject sceneJson(GameUI gui, PrototypePathfinder.Scene scene) {
      JSONObject o = new JSONObject();
      if (scene == null) {
         return o;
      } else {
         o.put("origin", arr(scene.origin));
         o.put("grid", new JSONArray().put(scene.w).put(scene.h));
         o.put("cell", scene.cell);
         o.put("radius", round(scene.radius));
         o.put("player", arr(scene.player));
         o.put("player_cell", scene.playerCell == null ? JSONObject.NULL : new JSONArray().put(scene.playerCell.x).put(scene.playerCell.y));
         o.put("moving", scene.moving);
         o.put("player_in_solid", scene.playerInSolid);
         o.put("player_in_inflated", scene.playerInDilated);
         o.put("terrain", scene.terrain);
         o.put("solid_count", scene.solidCount);
         o.put("inflated_count", scene.dilatedCount);
         o.put("obstacles", scene.obstacles);
         if (scene.occupancy != null && scene.playerCell != null) {
            o.put("ascii", scene.occupancy.ascii(scene.playerCell, 8));
         }

         JSONArray gobs = new JSONArray();

         for (PrototypePathfinder.GobGeom g : scene.gobs) {
            JSONObject j = new JSONObject();
            j.put("id", g.id);
            j.put("name", g.name);
            j.put("resid", g.resid);
            j.put("rc", arr(g.rc));
            j.put("a", round(g.a));
            j.put("cupboard", g.cupboard);
            j.put("passable", g.passable);
            j.put("gob_dist", round(g.gobDist / MCache.tilesz.x));
            j.put("poly_dist", finite(g.polyDist));
            j.put("hitbox_dist", finite(g.hitboxDist));
            j.put("movement", polys(g.movement));
            j.put("hitbox", polys(g.hitbox));
            gobs.put(j);
         }

         o.put("gobs", gobs);
         o.put("open_containers", openContainers(gui));
         return o;
      }
   }

   private static JSONArray polys(List<Coord2d[]> polygons) {
      JSONArray all = new JSONArray();
      if (polygons == null) {
         return all;
      } else {
         for (Coord2d[] poly : polygons) {
            if (poly != null) {
               JSONArray one = new JSONArray();

               for (Coord2d p : poly) {
                  one.put(arr(p));
               }

               all.put(one);
            }
         }

         return all;
      }
   }

   private static JSONArray arr(Coord2d p) {
      JSONArray a = new JSONArray();
      if (p != null) {
         a.put(round(p.x)).put(round(p.y));
      }

      return a;
   }

   private static double finite(double v) {
      return !Double.isInfinite(v) && !Double.isNaN(v) ? round(v / MCache.tilesz.x) : -1.0;
   }

   private static double round(double v) {
      return (double)Math.round(v * 100.0) / 100.0;
   }

   private static int cupCount(PrototypePathfinder.Scene scene) {
      int n = 0;
      if (scene == null) {
         return 0;
      } else {
         for (PrototypePathfinder.GobGeom g : scene.gobs) {
            if (g.cupboard) {
               n++;
            }
         }

         return n;
      }
   }

   private static Gob pickCupboard(GameUI gui, Gob player, Long id) {
      if (gui.ui != null && gui.ui.sess != null) {
         if (id != null) {
            return gui.ui.sess.glob.oc.getgob(id);
         } else {
            Gob best = null;
            double bestD = Double.POSITIVE_INFINITY;
            synchronized (gui.ui.sess.glob.oc) {
               Iterator var7 = gui.ui.sess.glob.oc.iterator();

               while (true) {
                  Gob gob;
                  while (true) {
                     if (!var7.hasNext()) {
                        return best;
                     }

                     gob = (Gob)var7.next();
                     if (gob != null && gob != player && gob.rc != null) {
                        try {
                           if (!CupboardCatalog.isCupboardResid(gob.resid())) {
                              continue;
                           }
                           break;
                        } catch (Loading var12) {
                        }
                     }
                  }

                  double d = PrototypePathfinder.minPolyDist(player.rc, Hitbox.worldPolygons(gob, false));
                  if (d < bestD) {
                     bestD = d;
                     best = gob;
                  }
               }
            }
         }
      } else {
         return null;
      }
   }

   private static List<Long> openContainers(GameUI gui) {
      List<Long> ids = new ArrayList<>();
      if (gui == null) {
         return ids;
      } else {
         for (Window w : gui.children(Window.class)) {
            if (w != null && !w.disposed() && w.gobId() >= 0L && !w.children(Inventory.class).isEmpty()) {
               ids.add(w.gobId());
            }
         }

         return ids;
      }
   }

   private static Set<Integer> windowIds(GameUI gui) {
      Set<Integer> ids = new HashSet<>();

      for (Window w : gui.children(Window.class)) {
         ids.add(w.wdgid());
      }

      return ids;
   }

   private static Window windowFor(GameUI gui, Gob gob) {
      if (gob == null) {
         return null;
      } else {
         for (Window w : gui.children(Window.class)) {
            if (w != null && !w.disposed() && w.gobId() == gob.id) {
               return w;
            }
         }

         return null;
      }
   }

   private static Window extraWindow(GameUI gui, Set<Integer> before) {
      for (Window w : gui.children(Window.class)) {
         if (!before.contains(w.wdgid()) && !w.children(Inventory.class).isEmpty()) {
            return w;
         }
      }

      return null;
   }

   private static void append(JSONObject o) {
      try {
         o.put("t", System.currentTimeMillis());
         Path file = logFile();
         Files.createDirectories(file.getParent());
         Writer w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

         try {
            w.write(o.toString());
            w.write(10);
         } catch (Throwable var6) {
            if (w != null) {
               try {
                  w.close();
               } catch (Throwable var5) {
                  var6.addSuppressed(var5);
               }
            }

            throw var6;
         }

         if (w != null) {
            w.close();
         }
      } catch (IOException var7) {
      }
   }

   static {
      DevFeature.register(new MechanicsProbe());
   }
}
