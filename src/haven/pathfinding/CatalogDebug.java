package haven.pathfinding;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.GOut;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.MapView;
import haven.Moving;
import haven.dev.DevFeature;
import haven.dev.Feature;
import java.awt.Color;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;

public final class CatalogDebug implements Feature {
   private static final Color HEAD = new Color(255, 220, 80);
   private static final Color ID_WALK = new Color(120, 255, 140);
   private static final Color ID_VIA = new Color(255, 220, 80);
   private static final Color ID_OPEN = new Color(160, 160, 160);
   private static final Color ID_FAIL = new Color(255, 90, 90);
   private static final Color ID_NOW = new Color(255, 255, 255);
   private static final Color RANGE = new Color(80, 220, 255, 160);
   private static final Color RANGE_EXT = new Color(255, 180, 80, 120);
   private static final Color WAIT = new Color(255, 180, 90);
   private static final Color FAIL = new Color(255, 90, 90);
   private static final int KEEP = 40;
   private static volatile boolean running;
   private static volatile GameUI gui;
   private static volatile CupboardBot.CatalogWnd wnd;
   private static volatile String phase = "";
   private static volatile String detail = "";
   private static volatile int step;
   private static volatile int total;
   private static volatile long targetId;
   private static volatile long viaId;
   private static volatile String targetName = "";
   private static volatile boolean walkable;
   private static volatile boolean corner;
   private static volatile boolean extender;
   private static volatile long waitStart;
   private static volatile long waitLimit;
   private static volatile String lastFail = "";
   private static volatile Coord2d standN;
   private static volatile Coord2d standE;
   private static volatile Coord2d standPicked;
   private static Boolean restorePfDebug;
   private static final List<String> events = new CopyOnWriteArrayList<>();
   private static final List<CatalogDebug.CupMark> cups = new CopyOnWriteArrayList<>();
   private static final Set<Long> openedIds = ConcurrentHashMap.newKeySet();
   private static final Set<Long> failedIds = ConcurrentHashMap.newKeySet();

   public String name() {
      return "catalog";
   }

   public CFG<Boolean> toggle() {
      return CFG.DEBUG_CATALOG;
   }

   public JSONObject capture() {
      JSONObject o = new JSONObject();
      o.put("running", running);
      o.put("phase", phase);
      o.put("detail", detail);
      o.put("step", step);
      o.put("total", total);
      o.put("target", targetId);
      o.put("via", viaId);
      o.put("walkable", walkable);
      o.put("corner", corner);
      o.put("extender", extender);
      o.put("last_fail", lastFail);
      return o;
   }

   public void replay(JSONObject body, PrintStream out) {
      if (body == null) {
         out.println("no catalog capture");
      } else {
         out.println(body.toString(2));
      }
   }

   public void paint(GOut g, MapView mv) {
      paintOverlay(g, mv);
   }

   private CatalogDebug() {
   }

   static void start(GameUI gameui, CupboardBot.CatalogWnd catalog, int n) {
      gui = gameui;
      wnd = catalog;
      running = true;
      total = n;
      step = 0;
      phase = "start";
      detail = n + " cupboards";
      viaId = 0L;
      targetId = 0L;
      targetName = "";
      extender = false;
      corner = false;
      walkable = false;
      waitLimit = 0L;
      waitStart = 0L;
      lastFail = "";
      standPicked = null;
      standE = null;
      standN = null;
      events.clear();
      cups.clear();
      openedIds.clear();
      failedIds.clear();
      event("start " + n + " cupboards");
      if (!Boolean.TRUE.equals(CFG.DEBUG_PATHFIND.get())) {
         restorePfDebug = Boolean.FALSE;
         CFG.DEBUG_PATHFIND.set(true);
      } else {
         restorePfDebug = null;
      }
   }

   static void stop() {
      phase = "idle";
      detail = lastFail.isEmpty() ? "done" : "last fail: " + lastFail;
      waitStart = 0L;
      running = false;
      event("stop " + detail);
      wnd = null;
      if (restorePfDebug != null) {
         CFG.DEBUG_PATHFIND.set(restorePfDebug);
         restorePfDebug = null;
      }
   }

   static void step(int i, int n, Gob gob, boolean canWalk, boolean isCorner, long via, boolean hasExtender) {
      step = i;
      total = n;
      targetId = gob == null ? 0L : gob.id;
      targetName = gob == null ? "" : PrototypePathfinder.displayName(gob);
      walkable = canWalk;
      corner = isCorner;
      viaId = via;
      extender = hasExtender;
      phase = "step";
      detail = String.format("%s #%d %s%s", targetName, targetId, canWalk ? "walk-up" : "corner", via != 0L ? " via #" + via : "");
      event(
         String.format(
            "[%d/%d] %s  dist=%.1ft  walkable=%s  corner=%s  via=%s  extender=%s",
            i,
            n,
            detail,
            distTiles(),
            canWalk,
            isCorner,
            via == 0L ? "-" : "#" + via,
            hasExtender
         )
      );
   }

   static void phase(String name, String why) {
      phase = name == null ? "" : name;
      detail = why == null ? "" : why;
      waitStart = 0L;
   }

   static void beginWait(String name, long timeoutMs, String why) {
      phase = name;
      detail = why == null ? "" : why;
      waitStart = System.currentTimeMillis();
      waitLimit = timeoutMs;
   }

   static void plan(List<CupboardCatalog.Node> nodes, Set<Long> walkable, List<CupboardCatalog.Step> steps) {
      cups.clear();
      if (nodes != null) {
         Map<Long, Integer> order = new HashMap<>();
         Map<Long, Long> viaOf = new HashMap<>();
         if (steps != null) {
            for (int i = 0; i < steps.size(); i++) {
               CupboardCatalog.Step s = steps.get(i);
               if (s != null) {
                  order.put(s.id, i + 1);
                  if (s.via != 0L) {
                     viaOf.put(s.id, s.via);
                  }
               }
            }
         }

         for (CupboardCatalog.Node n : nodes) {
            if (n != null) {
               Integer stepNo = order.get(n.id);
               Long via = viaOf.get(n.id);
               cups.add(
                  new CatalogDebug.CupMark(
                     n.id, Coord2d.of(n.x, n.y), stepNo == null ? 0 : stepNo, walkable != null && walkable.contains(n.id), via == null ? 0L : via
                  )
               );
            }
         }
      }
   }

   static void markOpened(long id) {
      openedIds.add(id);
      failedIds.remove(id);
   }

   static void markFailed(long id) {
      if (id != 0L) {
         failedIds.add(id);
      }
   }

   static void fail(String reason) {
      fail(reason, null, null);
   }

   static void fail(String reason, WaypointGate.Outcome outcome, String obs) {
      lastFail = reason == null ? "" : reason;
      if (targetId != 0L
         && !openedIds.contains(targetId)
         && outcome == null
         && reason != null
         && !reason.startsWith("short stop")
         && !reason.startsWith("movement rejected")
         && !reason.startsWith("waypoint")
         && !reason.startsWith("reached wp")
         && !reason.startsWith("issue wp")
         && !reason.startsWith("player gone")) {
         failedIds.add(targetId);
      }

      String label = WaypointWalker.gateLabel(outcome, lastFail, obs);
      event("FAIL " + label + "  dist=" + String.format("%.1ft", distTiles()) + "  extender=" + extender);
      CupboardBot.CatalogWnd log = wnd;
      if (log != null) {
         log.dbg("FAIL " + label, true);
      }

      appendFile("fail", label, outcome, obs);
   }

   static void event(String line) {
      if (line != null && !line.isEmpty()) {
         String stamped = String.format("%s  %s", phaseLabel(), line);
         events.add(stamped);

         while (events.size() > 40) {
            events.remove(0);
         }

         CupboardBot.CatalogWnd log = wnd;
         if (log != null) {
            log.dbg(line, false);
         }

         appendFile("event", line, null, null);
      }
   }

   static void extender(boolean on) {
      extender = on;
   }

   static boolean overlayActive() {
      return running || !lastFail.isEmpty();
   }

   static boolean waitingWalk() {
      return running && waitStart > 0L && "walk".equals(phase) && System.currentTimeMillis() - waitStart > 1500L;
   }

   static void stands(Coord2d north, Coord2d east, Coord2d picked) {
      standN = north;
      standE = east;
      standPicked = picked;
   }

   static String standHint() {
      Gob me = player();
      if (me != null && me.rc != null) {
         StringBuilder sb = new StringBuilder();
         if (standN != null) {
            sb.append(String.format("N %.1ft  ", me.rc.dist(standN) / MCache.tilesz.x));
         }

         if (standE != null) {
            sb.append(String.format("E %.1ft  ", me.rc.dist(standE) / MCache.tilesz.x));
         }

         if (standPicked != null) {
            String side = "?";
            if (standN != null && standPicked.dist(standN) < 0.5) {
               side = "N";
            } else if (standE != null && standPicked.dist(standE) < 0.5) {
               side = "E";
            }

            sb.append("picked ").append(side);
         }

         return sb.toString();
      } else {
         return "";
      }
   }

   static void paintOverlay(GOut g, MapView mv) {
      if (running || !lastFail.isEmpty()) {
         g.chcolor(HEAD);
         int y = 12;
         g.atext(String.format("catalog %d/%d  %s", step, total, running ? "RUNNING" : "stopped"), new Coord(12, y), 0.0, 0.0);
         y += 14;
         g.atext(
            String.format(
               "target %s #%d  %.1ft  %s%s  extender=%s",
               targetName.isEmpty() ? "-" : targetName,
               targetId,
               distTiles(),
               walkable ? "walk-up" : "blocked",
               corner ? "  (corner)" : "",
               extender ? "yes" : "NO"
            ),
            new Coord(12, y),
            0.0,
            0.0
         );
         y += 14;
         if (viaId != 0L) {
            g.atext("via #" + viaId, new Coord(12, y), 0.0, 0.0);
            y += 14;
         }

         if (waitStart > 0L) {
            g.chcolor(WAIT);
            double elapsed = (double)(System.currentTimeMillis() - waitStart) / 1000.0;
            g.atext(
               String.format("WAIT %s  %.1f/%.0fs  %s  %s", phase, elapsed, (double)waitLimit / 1000.0, moving() ? "MOVING" : "idle", detail),
               new Coord(12, y),
               0.0,
               0.0
            );
            y += 14;
         } else {
            g.atext(phase + (detail.isEmpty() ? "" : "  " + detail), new Coord(12, y), 0.0, 0.0);
            y += 14;
         }

         if (!lastFail.isEmpty()) {
            g.chcolor(FAIL);
            g.atext("last fail: " + lastFail, new Coord(12, y), 0.0, 0.0);
            y += 14;
         }

         String hint = standHint();
         if (!hint.isEmpty()) {
            g.chcolor(HEAD);
            g.atext("stand " + hint, new Coord(12, y), 0.0, 0.0);
            y += 14;
         }

         g.chcolor(HEAD);
         g.atext("ids: green=walk-up  yellow=via  white=current  gray=opened  red=fail   cyan=click  orange=corner along aisle", new Coord(12, y), 0.0, 0.0);
         g.chcolor();
         drawStand(g, mv, standN, "N", standPicked != null && standN != null && standPicked.dist(standN) < 0.5);
         drawStand(g, mv, standE, "E", standPicked != null && standE != null && standPicked.dist(standE) < 0.5);
         paintRange(g, mv);
         paintCupboardIds(g, mv);
      }
   }

   static void paintCupboardIds(GOut g, MapView mv) {
      if (mv != null) {
         if (overlayActive() || Boolean.TRUE.equals(CFG.DEBUG_PATHFIND.get())) {
            if (!cups.isEmpty()) {
               for (CatalogDebug.CupMark m : cups) {
                  if (m != null && m.rc != null) {
                     boolean now = m.id == targetId;
                     boolean opened = openedIds.contains(m.id);
                     boolean failed = failedIds.contains(m.id);
                     Color col = ID_VIA;
                     if (opened) {
                        col = ID_OPEN;
                     } else if (failed) {
                        col = ID_FAIL;
                     } else if (now) {
                        col = ID_NOW;
                     } else if (m.walkable) {
                        col = ID_WALK;
                     }

                     String tag = m.walkable ? "W" : "V";
                     if (opened) {
                        tag = "ok";
                     } else if (failed) {
                        tag = "fail";
                     }

                     String label = m.step > 0 ? String.format("%d  #%d  %s", m.step, m.id, tag) : String.format("#%d  %s", m.id, tag);
                     if (m.via != 0L && !opened) {
                        label = label + " via #" + m.via;
                     }

                     drawId(g, mv, m.rc, label, col, now);
                  }
               }
            } else {
               Gob me = mv.player();
               if (me != null && me.rc != null && mv.glob != null) {
                  synchronized (mv.glob.oc) {
                     Iterator nowx = mv.glob.oc.iterator();

                     while (true) {
                        Gob gob;
                        while (true) {
                           if (!nowx.hasNext()) {
                              return;
                           }

                           gob = (Gob)nowx.next();
                           if (gob != null && gob != me && !gob.virtual && gob.id >= 0L && gob.rc != null && !(gob.rc.dist(me.rc) > 220.0)) {
                              try {
                                 if (!CupboardCatalog.isCupboardResid(gob.resid())) {
                                    continue;
                                 }
                                 break;
                              } catch (Loading var11) {
                              }
                           }
                        }

                        drawId(g, mv, gob.rc, "#" + gob.id, ID_WALK, false);
                     }
                  }
               }
            }
         }
      }
   }

   private static void paintRange(GOut g, MapView mv) {
      Gob me = mv == null ? null : mv.player();
      if (me != null && me.rc != null) {
         ring(g, mv, me.rc, 14.850000000000001, RANGE);
         ring(g, mv, me.rc, 22.0, RANGE_EXT);
      }
   }

   private static void ring(GOut g, MapView mv, Coord2d center, double radius, Color col) {
      g.chcolor(col);
      Coord prev = null;
      Coord first = null;
      int n = 24;

      for (int i = 0; i <= n; i++) {
         double a = (Math.PI * 2) * (double)i / (double)n;
         Coord s = PathfinderDebug.screen(mv, center.add(Math.cos(a) * radius, Math.sin(a) * radius));
         if (s == null) {
            prev = null;
         } else {
            if (first == null) {
               first = s;
            }

            if (prev != null) {
               g.line(prev, s, 1.0);
            }

            prev = s;
         }
      }

      if (prev != null && first != null) {
         g.line(prev, first, 1.0);
      }

      g.chcolor();
   }

   private static void drawId(GOut g, MapView mv, Coord2d world, String label, Color col, boolean now) {
      Coord c = PathfinderDebug.screen(mv, world);
      if (c != null) {
         g.chcolor(col);
         int r = now ? 8 : 4;
         g.fellipse(c, new Coord(r, r));
         g.atext(label, c.add(6, now ? -10 : -8), 0.0, 0.0);
         g.chcolor();
      }
   }

   private static void drawStand(GOut g, MapView mv, Coord2d world, String label, boolean picked) {
      Coord c = PathfinderDebug.screen(mv, world);
      if (c != null) {
         g.chcolor(picked ? new Color(80, 255, 120) : new Color(255, 230, 80));
         int r = picked ? 7 : 5;
         g.line(c.add(-r, 0), c.add(r, 0), picked ? 2.0 : 1.0);
         g.line(c.add(0, -r), c.add(0, r), picked ? 2.0 : 1.0);
         g.atext(label, c.add(8, -6), 0.0, 0.0);
         g.chcolor();
      }
   }

   static void printLog(PrintWriter out) {
      out.println("Catalog debug: " + logFile());
      if (events.isEmpty()) {
         out.println("(empty)");
      } else {
         for (String e : events) {
            out.println("  " + e);
         }

         if (!lastFail.isEmpty()) {
            out.println("last fail: " + lastFail);
         }
      }
   }

   static Path logFile() {
      return PathfinderLog.logFile().getParent().resolve("catalog-debug.jsonl");
   }

   private static String phaseLabel() {
      if (waitStart > 0L) {
         double elapsed = (double)(System.currentTimeMillis() - waitStart) / 1000.0;
         return String.format("%s@%.1fs", phase, elapsed);
      } else {
         return phase;
      }
   }

   private static double distTiles() {
      Gob gob = targetGob();
      Gob me = player();
      return gob != null && gob.rc != null && me != null && me.rc != null ? gob.rc.dist(me.rc) / MCache.tilesz.x : 0.0;
   }

   private static boolean moving() {
      Gob me = player();
      return me != null && me.getattr(Moving.class) != null;
   }

   private static Gob player() {
      GameUI g = gui;
      return g != null && g.map != null ? g.map.player() : null;
   }

   private static Gob targetGob() {
      GameUI g = gui;
      return g != null && g.ui != null && g.ui.sess != null && targetId != 0L ? g.ui.sess.glob.oc.getgob(targetId) : null;
   }

   private static void appendFile(String kind, String text, WaypointGate.Outcome outcome, String obs) {
      try {
         Path file = logFile();
         Files.createDirectories(file.getParent());
         JSONObject o = new JSONObject();
         o.put("t", System.currentTimeMillis());
         o.put("kind", kind);
         o.put("phase", phase);
         o.put("step", step);
         o.put("total", total);
         o.put("target", targetId);
         o.put("via", viaId);
         o.put("dist", (double)Math.round(distTiles() * 10.0) / 10.0);
         o.put("walkable", walkable);
         o.put("corner", corner);
         o.put("extender", extender);
         o.put("moving", moving());
         o.put("text", text == null ? "" : text);
         if (outcome != null) {
            o.put("outcome", outcome.name());
         }

         if (obs != null && !obs.isEmpty()) {
            o.put("obs", obs);
         }

         Writer w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

         try {
            w.write(o.toString());
            w.write(10);
         } catch (Throwable var10) {
            if (w != null) {
               try {
                  w.close();
               } catch (Throwable var9) {
                  var10.addSuppressed(var9);
               }
            }

            throw var10;
         }

         if (w != null) {
            w.close();
         }
      } catch (IOException var11) {
      }
   }

   static {
      DevFeature.register(new CatalogDebug());
   }

   private static final class CupMark {
      final long id;
      final Coord2d rc;
      final int step;
      final boolean walkable;
      final long via;

      CupMark(long id, Coord2d rc, int step, boolean walkable, long via) {
         this.id = id;
         this.rc = rc;
         this.step = step;
         this.walkable = walkable;
         this.via = via;
      }
   }
}
