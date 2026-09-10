package haven.pathfinding;

import auto.Bot;
import auto.Bot.BotAction;
import haven.Coord;
import haven.Coord2d;
import haven.FlowerMenu;
import haven.GItem;
import haven.GameUI;
import haven.Gob;
import haven.GobHighlight;
import haven.Inventory;
import haven.ItemInfo;
import haven.Loading;
import haven.MCache;
import haven.Moving;
import haven.Textlog;
import haven.UI;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.GItem.Amount;
import haven.GameUI.Hidewnd;
import haven.GameUI.MsgType;
import haven.resutil.Curiosity;
import haven.rx.Reactor;
import java.awt.Color;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.ender.ClientUtils;
import me.ender.Reflect;
import rx.Subscription;

public final class CupboardBot {
   private static final long WALK_MS = 20000L;
   private static final long OPEN_MS = 5000L;
   private static final long INFO_MS = 2500L;
   private static final double WAYPOINT_EPS = 2.475;
   private static final int MAX_REPLANS = 8;

   private CupboardBot() {
   }

   public static void start(GameUI gui) {
      if (gui != null && gui.ui != null) {
         Bot.execute(new BotAction[]{(t, b) -> run(gui, b)}).start(gui.ui);
      }
   }

   static void run(GameUI gui, Bot bot) throws InterruptedException {
      Gob player = gui.map == null ? null : gui.map.player();
      if (player != null && player.rc != null) {
         List<CupboardCatalog.Node> cups = findCupboards(gui, player);
         if (cups.isEmpty()) {
            gui.msg("Catalog: no cupboards nearby", MsgType.ERROR);
         } else {
            Set<Long> walkable = new HashSet<>();
            Map<Long, Coord2d> stands = new HashMap<>();
            Map<Long, Integer> axes = new HashMap<>();
            Map<Long, Integer> faces = new HashMap<>();

            for (CupboardCatalog.Node n : cups) {
               Gob g = gob(gui, n.id);
               Coord2d stand = g != null && g.rc != null ? CupboardCatalog.aisleStand(g.rc, player.rc, cups, n.id) : null;
               if (stand != null) {
                  walkable.add(n.id);
                  stands.put(n.id, stand);
                  faces.put(n.id, CupboardCatalog.faceIndex(g.rc, stand));
                  axes.put(n.id, CupboardCatalog.extendAxis(g.rc, stand));
               }
            }

            List<CupboardCatalog.Step> steps = Collections.unmodifiableList(
               new ArrayList<>(CupboardCatalog.tour(player.rc.x, player.rc.y, cups, walkable, axes, faces, stands))
            );
            CupboardBot.CatalogWnd wnd = CupboardBot.CatalogWnd.show(gui);
            CatalogDebug.start(gui, wnd, cups.size());
            CatalogDebug.plan(cups, walkable, steps);
            wnd.line(
               String.format(
                  "Scanning %d cupboard%s (%d walk-up, %d via neighbour)...",
                  cups.size(),
                  cups.size() == 1 ? "" : "s",
                  walkable.size(),
                  cups.size() - walkable.size()
               )
            );
            wnd.line("Plan: " + itinerary(steps));
            CatalogDebug.event("frozen plan " + itinerary(steps));
            gui.msg(String.format("Catalog: %d cupboards", cups.size()), MsgType.INFO);
            CupboardBot.Run run = new CupboardBot.Run(gui, bot, wnd);
            int opened = 0;
            int failed = 0;
            Set<Long> done = new HashSet<>();

            try {
               int i = 0;

               for (CupboardCatalog.Step step : steps) {
                  i++;
                  bot.checkCancelled();
                  if (done.contains(step.id)) {
                     CatalogDebug.event("already opened as corner #" + step.id);
                  } else {
                     Gob gob = gob(gui, step.id);
                     if (gob == null) {
                        CatalogDebug.fail("#" + step.id + " gone");
                        wnd.line("#" + step.id + " gone");
                        failed++;
                     } else {
                        boolean canWalk = walkable.contains(gob.id);
                        boolean corner = step.via != 0L || !canWalk;
                        CatalogDebug.step(i, steps.size(), gob, canWalk, corner, step.via, run.hasExtender());
                        highlight(gob, true);

                        try {
                           Gob via = step.via != 0L ? gob(gui, step.via) : null;
                           boolean ok;
                           if (via != null && via != gob) {
                              if (walkable.contains(via.id) || stands.containsKey(via.id)) {
                                 CatalogDebug.phase("walk-via", "stand at aisle extender #" + via.id);
                                 Coord2d viaStand = CupboardCatalog.lateralStand(
                                    via.rc, gob.rc, stands.get(via.id), cups, via.id, gui.map.player() == null ? null : gui.map.player().rc
                                 );
                                 if (!walkTo(gui, bot, via, viaStand, run.banned)) {
                                    CatalogDebug.fail("could not stand at extender #" + via.id + " for " + label(gob));
                                    failed++;
                                    wnd.line("  could not stand at extender for " + label(gob));
                                    continue;
                                 }

                                 waitIdle(gui, bot);
                              }

                              if (run.windowFor(via) == null) {
                                 CatalogDebug.phase("open-via", "extender #" + via.id);
                                 if (run.openContainer(via) == null) {
                                    CatalogDebug.fail("extender window missing #" + via.id);
                                    failed++;
                                    wnd.line("  extender did not open for " + label(gob));
                                    continue;
                                 }
                              }

                              if (!inCornerRange(gui.map.player(), gob, via)) {
                                 CatalogDebug.event("not on lateral face of #" + via.id + " for " + label(gob));
                                 boolean got = false;

                                 for (Gob ext : neighbourVias(gui, gob, walkable, done)) {
                                    if (openThroughNeighbour(gui, bot, run, gob, ext, stands, cups, walkable)) {
                                       got = true;
                                       break;
                                    }
                                 }

                                 if (!got) {
                                    failed++;
                                    CatalogDebug.markFailed(gob.id);
                                    wnd.line("  could not stand beside " + label(gob));
                                    continue;
                                 }

                                 ok = true;
                              } else {
                                 ok = run.openAndRead(gob, true);
                                 if (!ok) {
                                    CatalogDebug.phase("retry-click", "extender up, click corner again");
                                    waitIdle(gui, bot);
                                    ok = run.openAndRead(gob, true);
                                 }
                              }
                           } else {
                              ok = false;
                              if (!inClickRange(gui.map.player(), gob, false, cups)) {
                                 CatalogDebug.phase("walk", "in front of " + label(gob));
                                 if (walkTo(gui, bot, gob, stands.get(gob.id), run.banned)) {
                                    waitIdle(gui, bot);
                                 }
                              }

                              if (inClickRange(gui.map.player(), gob, false, cups)) {
                                 ok = run.openAndRead(gob, corner);
                                 if (!ok && !run.hasExtender()) {
                                    CatalogDebug.phase("retry-planned", "retry current " + label(gob));
                                    waitIdle(gui, bot);
                                    ok = run.openAndRead(gob, corner);
                                 } else if (!ok) {
                                    CatalogDebug.phase("retry-click", "extender up, click again");
                                    waitIdle(gui, bot);
                                    ok = run.openAndRead(gob, true);
                                 }
                              }

                              if (!ok) {
                                 boolean got = false;

                                 for (Gob extx : neighbourVias(gui, gob, walkable, done)) {
                                    if (openThroughNeighbour(gui, bot, run, gob, extx, stands, cups, walkable)) {
                                       got = true;
                                       break;
                                    }
                                 }

                                 if (!got) {
                                    failed++;
                                    CatalogDebug.markFailed(gob.id);
                                    wnd.line("  could not open " + label(gob));
                                    continue;
                                 }

                                 ok = true;
                              }
                           }

                           if (ok) {
                              opened++;
                              done.add(gob.id);
                              CatalogDebug.extender(true);
                              CatalogDebug.markOpened(gob.id);
                              if (step.via == 0L) {
                                 opened += openPlannedCorners(gui, bot, run, gob, steps, done, stands, cups);
                              }

                              run.keepExtender(gob);
                           } else {
                              failed++;
                              Coord2d stand = stands.get(gob.id);
                              if (stand != null) {
                                 run.banned.add(standKey(stand));
                              }

                              String why = String.format(
                                 "could not open %s  walkable=%s  corner=%s  via=%s  extender=%s  %s",
                                 label(gob),
                                 canWalk,
                                 corner,
                                 step.via == 0L ? "-" : "#" + step.via,
                                 run.hasExtender(),
                                 movingHint(gui)
                              );
                              CatalogDebug.fail(why);
                              wnd.line("  " + why);
                           }
                        } finally {
                           highlight(gob, false);
                        }
                     }
                  }
               }
            } finally {
               run.keep.clear();
               run.closeExceptKeep();
               CatalogDebug.stop();
            }

            String var37 = String.format("Done: %d opened, %d failed", opened, failed);
            wnd.line(var37);
            gui.msg("Catalog: " + var37, failed > 0 ? MsgType.ERROR : MsgType.INFO);
         }
      } else {
         gui.msg("Catalog: no player", MsgType.ERROR);
      }
   }

   private static String itinerary(List<CupboardCatalog.Step> steps) {
      StringBuilder out = new StringBuilder();

      for (int i = 0; i < steps.size(); i++) {
         CupboardCatalog.Step step = steps.get(i);
         if (i > 0) {
            out.append(" -> ");
         }

         out.append('#').append(step.id);
         if (step.via != 0L) {
            out.append("(via #").append(step.via).append(')');
         }
      }

      return out.toString();
   }

   private static int openPlannedCorners(
      GameUI gui,
      Bot bot,
      CupboardBot.Run run,
      Gob opened,
      List<CupboardCatalog.Step> steps,
      Set<Long> done,
      Map<Long, Coord2d> stands,
      List<CupboardCatalog.Node> cups
   ) throws InterruptedException {
      int extra = 0;
      if (opened != null && run.windowFor(opened) != null) {
         for (CupboardCatalog.Step step : steps) {
            if (step.via == opened.id && !done.contains(step.id)) {
               Gob gob = gob(gui, step.id);
               if (gob != null) {
                  Coord2d viaStand = CupboardCatalog.lateralStand(
                     opened.rc, gob.rc, stands.get(opened.id), cups, opened.id, gui.map.player() == null ? null : gui.map.player().rc
                  );
                  if (!reachedStand(gui.map.player(), viaStand)) {
                     CatalogDebug.phase("corner-walk", "lateral stand at #" + opened.id + " for " + label(gob));
                     if (!walkTo(gui, bot, opened, viaStand, run.banned)) {
                        CatalogDebug.event("corner walk failed #" + gob.id + " with #" + opened.id + " open");
                        continue;
                     }

                     waitIdle(gui, bot);
                  }

                  if (run.windowFor(opened) == null && run.openContainer(opened) == null) {
                     break;
                  }

                  if (!inCornerRange(gui.map.player(), gob, opened)) {
                     CatalogDebug.event("skip through-furniture click #" + gob.id + " from #" + opened.id);
                  } else {
                     CatalogDebug.phase("corner", "click " + label(gob) + " with #" + opened.id + " open");
                     boolean ok = run.openAndRead(gob, true);
                     if (!ok) {
                        waitIdle(gui, bot);
                        ok = run.openAndRead(gob, true);
                     }

                     if (ok) {
                        extra++;
                        done.add(gob.id);
                        CatalogDebug.markOpened(gob.id);
                     } else {
                        CatalogDebug.event("corner click failed #" + gob.id + " with #" + opened.id + " open");
                     }
                  }
               }
            }
         }

         return extra;
      } else {
         return 0;
      }
   }

   private static List<CupboardCatalog.Node> findCupboards(GameUI gui, Gob player) {
      List<CupboardCatalog.Node> out = new ArrayList<>();
      if (gui.ui != null && gui.ui.sess != null) {
         synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
               if (gob != null && gob != player && !gob.virtual && gob.id >= 0L && gob.rc != null) {
                  try {
                     if (!CupboardCatalog.isCupboardResid(gob.resid())) {
                        continue;
                     }
                  } catch (Loading var8) {
                     continue;
                  }

                  if (!(gob.rc.dist(player.rc) > 220.0)) {
                     out.add(new CupboardCatalog.Node(gob.id, gob.rc.x, gob.rc.y));
                  }
               }
            }
         }

         out.sort(Comparator.<CupboardCatalog.Node>comparingDouble(n -> n.y).thenComparingDouble(n -> n.x).thenComparingLong(n -> n.id));
         return out;
      } else {
         return out;
      }
   }

   private static Gob gob(GameUI gui, long id) {
      return gui.ui != null && gui.ui.sess != null ? gui.ui.sess.glob.oc.getgob(id) : null;
   }

   private static Coord2d findStand(PrototypePathfinder.Scene scene, Gob gob, Gob player, List<CupboardCatalog.Node> cups) {
      return findStand(scene, gob, player, Collections.emptySet(), cups);
   }

   private static Coord2d findStand(PrototypePathfinder.Scene scene, Gob gob, Gob player, Set<String> banned, List<CupboardCatalog.Node> cups) {
      List<Coord2d> cands = freeStands(scene, gob, banned, cups);
      if (cands.isEmpty()) {
         return null;
      } else {
         if (player != null && player.rc != null && cands.size() >= 2) {
            final Coord2d at = player.rc;
            Collections.sort(cands, new Comparator<Coord2d>() {
               public int compare(Coord2d a, Coord2d b) {
                  return Double.compare(at.dist(a), at.dist(b));
               }
            });
         }

         return cands.get(0);
      }
   }

   private static String standKey(Coord2d p) {
      return p == null ? "" : Math.round(p.x) + "," + Math.round(p.y);
   }

   private static List<Coord2d> freeStands(PrototypePathfinder.Scene scene, Gob gob, Set<String> banned, List<CupboardCatalog.Node> cups) {
      List<Coord2d> out = new ArrayList<>();
      if (gob != null && gob.rc != null) {
         Set<String> seen = new HashSet<>();
         if (scene != null) {
            for (Coord2d dest : scene.approachStands(gob.rc, 5.5)) {
               addStand(out, seen, dest, gob, banned, cups, scene);
            }
         }

         for (Coord2d dest : CupboardCatalog.standCandidates(gob.rc)) {
            addStand(out, seen, dest, gob, banned, cups, scene);
         }

         return out;
      } else {
         return out;
      }
   }

   private static void addStand(
      List<Coord2d> out, Set<String> seen, Coord2d dest, Gob gob, Set<String> banned, List<CupboardCatalog.Node> cups, PrototypePathfinder.Scene scene
   ) {
      if (dest != null) {
         Coord2d snapped = CupboardCatalog.faceSnap(gob.rc, dest);
         if (snapped != null) {
            if (banned == null || !banned.contains(standKey(dest)) && !banned.contains(standKey(snapped))) {
               if (CupboardCatalog.standClear(snapped, cups, gob.id)) {
                  if (scene == null || scene.aisleStand(snapped) || scene.aisleStand(dest)) {
                     String key = standKey(snapped);
                     if (seen.add(key)) {
                        out.add(snapped);
                     }
                  }
               }
            }
         }
      }
   }

   private static int lateralAxis(Gob gob, Coord2d stand) {
      double dx = Math.abs(stand.x - gob.rc.x);
      double dy = Math.abs(stand.y - gob.rc.y);
      return dy >= dx ? 0 : 1;
   }

   private static Coord2d pickStand(GameUI gui, Gob gob, Coord2d preferred, Set<String> banned, List<CupboardCatalog.Node> cups) {
      if (gui != null && gob != null && gob.rc != null && gui.map != null) {
         PrototypePathfinder.Scene scene = PrototypePathfinder.observe(gui);
         return preferred == null
               || !scene.aisleStand(preferred)
               || !CupboardCatalog.standClear(preferred, cups, gob.id)
               || banned != null && banned.contains(standKey(preferred))
            ? findStand(scene, gob, gui.map.player(), banned, cups)
            : preferred;
      } else {
         return preferred;
      }
   }

   static double routeLength(Coord2d start, List<Coord2d> points) {
      if (start != null && points != null && !points.isEmpty()) {
         double total = 0.0;
         Coord2d at = start;

         for (Coord2d p : points) {
            total += at.dist(p);
            at = p;
         }

         return total;
      } else {
         return Double.POSITIVE_INFINITY;
      }
   }

   private static Gob neighbourVia(GameUI gui, Gob gob, Set<Long> walkable, Set<Long> opened) {
      List<Gob> all = neighbourVias(gui, gob, walkable, opened);
      return all.isEmpty() ? null : all.get(0);
   }

   private static List<Gob> neighbourVias(GameUI gui, Gob gob, Set<Long> walkable, Set<Long> opened) {
      List<Gob> out = new ArrayList<>();
      Set<Long> seen = new HashSet<>();
      Gob player = gui != null && gui.map != null ? gui.map.player() : null;
      addNeighbourVias(out, seen, gui, gob, opened, true, player);
      addNeighbourVias(out, seen, gui, gob, walkable, true, player);
      addNeighbourVias(out, seen, gui, gob, opened, false, player);
      addNeighbourVias(out, seen, gui, gob, walkable, false, player);
      if (out.isEmpty()) {
         Gob any = neighbourViaAny(gui, gob);
         if (any != null) {
            out.add(any);
         }
      }

      return out;
   }

   private static void addNeighbourVias(List<Gob> out, Set<Long> seen, GameUI gui, Gob gob, Set<Long> ids, boolean adjacentOnly, Gob player) {
      if (ids != null && gob != null && gob.rc != null) {
         List<Gob> found = new ArrayList<>();

         for (long id : ids) {
            if (id != gob.id && seen.add(id)) {
               Gob g = gob(gui, id);
               if (g != null && g.rc != null) {
                  if (adjacentOnly && g.rc.dist(gob.rc) > 14.850000000000001) {
                     seen.remove(id);
                  } else {
                     found.add(g);
                  }
               }
            }
         }

         found.sort((a, b) -> {
            double da = player != null && player.rc != null ? player.rc.dist(a.rc) : gob.rc.dist(a.rc);
            double db = player != null && player.rc != null ? player.rc.dist(b.rc) : gob.rc.dist(b.rc);
            return Double.compare(da, db);
         });
         out.addAll(found);
      }
   }

   private static Gob neighbourViaAny(GameUI gui, Gob gob) {
      if (gui != null && gob != null && gob.rc != null) {
         Gob player = gui.map == null ? null : gui.map.player();
         List<CupboardCatalog.Node> cups = findCupboards(gui, player);
         CupboardCatalog.Node self = CupboardCatalog.node(cups, gob.id);
         Gob best = null;
         double bestD = Double.POSITIVE_INFINITY;

         for (CupboardCatalog.Node n : cups) {
            if (n != null && CupboardCatalog.adjacent(self, n)) {
               Gob g = gob(gui, n.id);
               if (g != null && g.rc != null) {
                  double d = player != null && player.rc != null ? player.rc.dist(g.rc) : gob.rc.dist(g.rc);
                  if (d < bestD) {
                     bestD = d;
                     best = g;
                  }
               }
            }
         }

         return best;
      } else {
         return null;
      }
   }

   private static boolean openThroughNeighbour(
      GameUI gui, Bot bot, CupboardBot.Run run, Gob gob, Gob via, Map<Long, Coord2d> stands, List<CupboardCatalog.Node> cups, Set<Long> walkable
   ) throws InterruptedException {
      if (gob != null && via != null && via != gob) {
         CatalogDebug.phase("nook-fallback", "stand at #" + via.id + " for " + label(gob));
         Coord2d near = gui.map != null && gui.map.player() != null ? gui.map.player().rc : null;
         Coord2d viaStand = CupboardCatalog.lateralStand(via.rc, gob.rc, stands.get(via.id), cups, via.id, near);
         if (viaStand != null && CupboardCatalog.standOnRoomWall(via.rc, viaStand, cups) && CupboardCatalog.faceIndex(via.rc, viaStand) == 1) {
            CatalogDebug.event("skip wall-side via #" + via.id);
            return false;
         } else if (!walkTo(gui, bot, via, viaStand, run.banned)) {
            return false;
         } else {
            waitIdle(gui, bot);
            if (run.windowFor(via) == null && run.openContainer(via) == null) {
               return false;
            } else if (!inCornerRange(gui.map.player(), gob, via)) {
               CatalogDebug.event("via #" + via.id + " not lateral for " + label(gob));
               return false;
            } else {
               boolean ok = run.openAndRead(gob, true);
               if (!ok) {
                  waitIdle(gui, bot);
                  ok = run.openAndRead(gob, true);
               }

               return ok;
            }
         }
      } else {
         return false;
      }
   }

   private static Gob preferredWalkable(GameUI gui, Gob from, Set<Long> walkable, long skip) {
      if (from != null && from.rc != null && walkable != null) {
         Gob bestAdj = pickWalkable(gui, from, walkable, skip, true);
         return bestAdj != null ? bestAdj : pickWalkable(gui, from, walkable, skip, false);
      } else {
         return null;
      }
   }

   private static Gob pickWalkable(GameUI gui, Gob from, Set<Long> walkable, long skip, boolean adjacentOnly) {
      Gob best = null;
      int bestRank = Integer.MAX_VALUE;
      double bestD = Double.POSITIVE_INFINITY;

      for (long id : walkable) {
         if (id != skip) {
            Gob g = gob(gui, id);
            if (g != null && g.rc != null) {
               double d = g.rc.dist(from.rc);
               if (!adjacentOnly || !(d > 14.850000000000001)) {
                  int rank = CupboardCatalog.viaRank(from.rc.x, from.rc.y, g.rc.x, g.rc.y);
                  if (rank < bestRank || rank == bestRank && d < bestD) {
                     bestRank = rank;
                     bestD = d;
                     best = g;
                  }
               }
            }
         }
      }

      return best;
   }

   private static boolean walkTo(GameUI gui, Bot bot, Gob gob, Coord2d preferredStand, Set<String> banned) throws InterruptedException {
      Gob player = gui.map.player();
      if (player != null && gob != null && gob.rc != null) {
         List<CupboardCatalog.Node> cups = findCupboards(gui, player);
         boolean atStand = preferredStand == null || player.rc.dist(preferredStand) <= 2.475;
         if (atStand && inClickRange(player, gob, false, cups)) {
            CatalogDebug.event(String.format("walk skip already in click range  %.1ft", player.rc.dist(gob.rc) / MCache.tilesz.x));
            return true;
         } else {
            Coord2d dest = catalogStand(player, gob, preferredStand, banned, cups);
            PrototypePathfinder.Scene scene = PrototypePathfinder.observe(gui);
            boolean keepPreferred = preferredStand != null && dest != null && dest.dist(preferredStand) < 1.0;
            if (dest != null && !scene.aisleStand(dest) && !keepPreferred) {
               CatalogDebug.event(String.format("frozen stand is not an aisle dest=(%.1f,%.1f), other face", dest.x, dest.y));
               Coord2d alt = pickStand(gui, gob, null, banned, cups);
               if (alt != null) {
                  dest = alt;
               } else if (CupboardCatalog.standOnRoomWall(gob.rc, dest, cups)) {
                  CatalogDebug.event("wall-side stand with no other face — treat as nook");
                  dest = null;
               }
            } else if (dest != null && !scene.aisleStand(dest)) {
               CatalogDebug.event(String.format("keep requested stand dest=(%.1f,%.1f) even if occupancy disagrees", dest.x, dest.y));
            }

            if (dest == null) {
               CatalogDebug.event("no aisle stand for " + label(gob));
               return false;
            } else {
               CatalogDebug.stands(Coord2d.of(gob.rc.x, gob.rc.y - 7.5), Coord2d.of(gob.rc.x + 7.5, gob.rc.y), dest);
               CatalogDebug.phase("walk", label(gob));
               PathfinderLog.setTarget("catalog " + label(gob));

               try {
                  Coord2d tryDest = dest;
                  List<List<Coord2d>> routes = CupboardCatalog.detourOptions(player.rc, dest, cups);
                  int routeAt = 0;

                  for (int attempt = 0; attempt <= 8; attempt++) {
                     player = gui.map.player();
                     if (player == null || player.rc == null) {
                        return false;
                     }

                     if (reachedStand(player, tryDest)) {
                        return true;
                     }

                     if (routeAt >= routes.size()) {
                        routes = CupboardCatalog.detourOptions(player.rc, tryDest, cups);
                        routeAt = 0;
                     }

                     List<Coord2d> route = routes.isEmpty() ? Arrays.asList(player.rc, tryDest) : routes.get(routeAt);
                     CatalogDebug.event(
                        String.format(
                           "floor-click stand %s  dest=(%.1f,%.1f)  %.1ft from gob  wps=%d  %s",
                           label(gob),
                           tryDest.x,
                           tryDest.y,
                           tryDest.dist(gob.rc) / MCache.tilesz.x,
                           route.size() - 1,
                           routeBrief(route)
                        )
                     );
                     PrototypePathfinder.Plan plan = PrototypePathfinder.Plan.of(route);
                     WaypointWalker.Result result = executeWaypoints(gui, bot, plan, attempt);
                     player = gui.map.player();
                     if (reachedStand(player, tryDest)) {
                        return true;
                     }

                     Coord2d far = CupboardCatalog.sameFaceFar(gob.rc, tryDest);
                     if (far != null && CupboardCatalog.standClear(far, cups, gob.id) && !far.equals(tryDest) && usableStand(far, banned)) {
                        CatalogDebug.event("replan " + (attempt + 1) + "/" + 8 + " after " + result + " → far stand");
                        tryDest = far;
                        routes = CupboardCatalog.detourOptions(player.rc, far, cups);
                        routeAt = 0;
                     } else {
                        if (result == WaypointWalker.Result.REJECTED || result == WaypointWalker.Result.SHORT_STOP) {
                           if (++routeAt < routes.size()) {
                              CatalogDebug.event("try next detour " + routeAt + " after " + result);
                              continue;
                           }
                        }

                        if (result == WaypointWalker.Result.REJECTED) {
                           CatalogDebug.fail("movement rejected for " + label(gob));
                           dumpStuck(gui);
                           return false;
                        }

                        CatalogDebug.event("replan " + (attempt + 1) + "/" + 8 + " after " + result);
                     }
                  }

                  CatalogDebug.fail("replan limit reached for " + label(gob));
                  dumpStuck(gui);
                  return false;
               } finally {
                  PathfinderLog.clearTarget();
               }
            }
         }
      } else {
         return false;
      }
   }

   private static Coord2d catalogStand(Gob player, Gob gob, Coord2d preferred, Set<String> banned, List<CupboardCatalog.Node> cups) {
      if (usableStand(preferred, banned) && CupboardCatalog.standClear(preferred, cups, gob.id)) {
         return preferred;
      } else {
         Coord2d best = CupboardCatalog.aisleStand(gob.rc, player.rc, cups, gob.id);
         if (usableStand(best, banned)) {
            return best;
         } else {
            best = CupboardCatalog.bestStand(gob.rc, player.rc, cups, gob.id);
            if (usableStand(best, banned)) {
               return best;
            } else {
               Coord2d far = CupboardCatalog.sameFaceFar(gob.rc, best != null ? best : preferred);
               return usableStand(far, banned) && CupboardCatalog.standClear(far, cups, gob.id) ? far : null;
            }
         }
      }
   }

   private static boolean usableStand(Coord2d stand, Set<String> banned) {
      return stand != null && (banned == null || !banned.contains(standKey(stand)));
   }

   private static WaypointWalker.Result executeWaypoints(final GameUI gui, Bot bot, PrototypePathfinder.Plan plan, int replan) throws InterruptedException {
      return WaypointWalker.execute(gui, bot, plan.waypoints, replan, 20000L, new WaypointWalker.Listener() {
         @Override
         public void event(String msg) {
            CatalogDebug.event(msg);
         }

         @Override
         public void fail(String msg) {
            CatalogDebug.fail(msg);
         }

         @Override
         public void fail(String msg, WaypointGate.Outcome oc, String obs) {
            CatalogDebug.fail(msg, oc, obs);
         }

         @Override
         public void beginWait(String what, long ms, String detail) {
            CatalogDebug.beginWait(what, ms, detail);
         }

         @Override
         public void dumpStuck() {
            CupboardBot.dumpStuck(gui);
         }
      });
   }

   private static boolean reachedStand(Gob player, Coord2d dest) {
      return player != null && player.rc != null && dest != null && player.rc.dist(dest) <= 2.475;
   }

   static boolean inInteractionRange(Gob player, Gob target) {
      return inClickRange(player, target, false, null);
   }

   static boolean inClickRange(Gob player, Gob target, boolean extender) {
      return inClickRange(player, target, extender, null);
   }

   static boolean inClickRange(Gob player, Gob target, boolean extender, List<CupboardCatalog.Node> cups) {
      if (player != null && player.rc != null && target != null && target.rc != null) {
         double max = extender ? 22.0 : 14.850000000000001;
         return player.rc.dist(target.rc) > max ? false : cups == null || !CupboardCatalog.throughPacked(player.rc, target.rc, cups, target.id);
      } else {
         return false;
      }
   }

   static boolean inCornerRange(Gob player, Gob target, Gob via) {
      if (player != null && player.rc != null && target != null && target.rc != null) {
         if (player.rc.dist(target.rc) > 22.0) {
            return false;
         } else if (via != null && via.rc != null) {
            if (via.rc.dist(target.rc) > 14.850000000000001) {
               return false;
            } else {
               int axis = CupboardCatalog.extendAxis(via.rc, player.rc);
               return CupboardCatalog.lateral(target.rc.x, target.rc.y, via.rc.x, via.rc.y, axis);
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static void dumpStuck(GameUI gui) {
      if (gui != null && gui.ui != null && gui.ui.cons != null) {
         PathfinderDebug.dumpStuck(gui, gui.ui.cons.out);
      }
   }

   private static void waitIdle(GameUI gui, Bot bot) throws InterruptedException {
      CatalogDebug.beginWait("idle", 2000L, movingHint(gui));
      waitUntil(bot, 2000L, () -> {
         Gob me = gui.map == null ? null : gui.map.player();
         return me == null || me.getattr(Moving.class) == null;
      });
   }

   private static String movingHint(GameUI gui) {
      Gob me = gui != null && gui.map != null ? gui.map.player() : null;
      return me != null && me.getattr(Moving.class) != null ? "MOVING" : "idle";
   }

   private static String flowerHint(GameUI gui) {
      FlowerMenu menu = findFlower(gui);
      if (menu != null && menu.options != null) {
         StringBuilder sb = new StringBuilder("flower=");

         for (int i = 0; i < menu.options.length; i++) {
            if (i > 0) {
               sb.append(',');
            }

            sb.append(menu.options[i]);
         }

         return sb.toString();
      } else {
         return "flower=none";
      }
   }

   private static void rclickGob(Gob gob) {
      if (gob != null && !gob.disposed()) {
         try {
            FlowerMenu.lastGob(gob);
            gob.rclick(0);
         } catch (RuntimeException var2) {
         }
      }
   }

   private static FlowerMenu findFlower(GameUI gui) {
      if (gui != null && gui.ui != null && gui.ui.root != null) {
         for (Widget w = gui.ui.root.lchild; w != null; w = w.prev) {
            if (w instanceof FlowerMenu) {
               return (FlowerMenu)w;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private static void chooseOpen(GameUI gui) {
      FlowerMenu menu = findFlower(gui);
      if (menu != null && menu.options != null && menu.opts != null) {
         int pick = -1;

         for (int i = 0; i < menu.options.length; i++) {
            if ("Open".equals(menu.options[i])) {
               pick = i;
               break;
            }
         }

         if (pick < 0 && menu.options.length == 1 && !"Destroy".equals(menu.options[0])) {
            pick = 0;
         }

         if (pick >= 0 && pick < menu.opts.length && menu.opts[pick] != null) {
            menu.choose(menu.opts[pick]);
         }
      }
   }

   private static boolean isCupboardWindow(GameUI gui, Window w) {
      if (!isLootWindow(gui, w)) {
         return false;
      } else {
         String cap = w.caption();
         return cap != null && cap.toLowerCase().contains("cupboard") ? true : w.gobId() >= 0L;
      }
   }

   private static boolean isLootWindow(GameUI gui, Window w) {
      return w != null && w != gui.invwnd && w != gui.equwnd && w != gui.studywnd ? !w.children(Inventory.class).isEmpty() : false;
   }

   private static List<String> readItems(Bot bot, Window wnd) throws InterruptedException {
      waitUntil(bot, 2500L, () -> leavesReady(wnd));
      List<CupboardCatalog.Entry> entries = new ArrayList<>();

      for (WItem w : collectLeaves(wnd)) {
         CupboardCatalog.Entry e = readEntry(w);
         if (e != null) {
            entries.add(e);
         }
      }

      List<String> lines = new ArrayList<>();

      for (CupboardCatalog.Entry e : CupboardCatalog.merge(entries)) {
         lines.add(CupboardCatalog.format(e));
      }

      return lines;
   }

   private static boolean leavesReady(Window wnd) {
      List<WItem> leaves = collectLeaves(wnd);
      if (leaves.isEmpty()) {
         return !hasPackedStack(wnd);
      } else {
         for (WItem w : leaves) {
            if (!itemReady(w.item)) {
               return false;
            }
         }

         return true;
      }
   }

   private static boolean hasPackedStack(Window wnd) {
      for (Inventory inv : inventories(wnd)) {
         for (Widget ch : inv.children()) {
            if (ch instanceof WItem) {
               WItem w = (WItem)ch;
               if (isItemStack(w.item.contents) && w.item.contents.children(WItem.class).isEmpty()) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   private static List<Inventory> inventories(Window wnd) {
      List<Inventory> out = new ArrayList<>();
      if (wnd == null) {
         return out;
      } else {
         for (Inventory inv : wnd.children(Inventory.class)) {
            if (!isItemStack(inv)) {
               out.add(inv);
            }
         }

         return out;
      }
   }

   private static List<WItem> collectLeaves(Window wnd) {
      List<WItem> out = new ArrayList<>();

      for (Inventory inv : inventories(wnd)) {
         for (Widget ch : inv.children()) {
            if (ch instanceof WItem) {
               unpack((WItem)ch, out);
            }
         }
      }

      return out;
   }

   private static void unpack(WItem w, List<WItem> out) {
      if (w != null && w.item != null) {
         Widget contents = w.item.contents;
         if (isItemStack(contents)) {
            List<WItem> kids = new ArrayList<>(contents.children(WItem.class));
            if (!kids.isEmpty()) {
               for (WItem k : kids) {
                  unpack(k, out);
               }

               return;
            }
         }

         out.add(w);
      }
   }

   private static boolean isItemStack(Widget w) {
      return Reflect.is(w, "haven.res.ui.stackinv.ItemStack");
   }

   private static CupboardCatalog.Entry readEntry(WItem w) {
      if (w != null && w.item != null) {
         GItem item = w.item;
         String name = (String)item.name.get();
         if (name == null || name.isEmpty() || "???".equals(name)) {
            name = ClientUtils.prettyResName(item.resname());
         }

         double quality = 0.0;

         try {
            quality = item.quality();
         } catch (Loading var12) {
         }

         double amount = amountOf(item);
         Integer lp = null;
         Integer lph = null;
         Curiosity curio = (Curiosity)w.curio.get();
         if (curio == null) {
            try {
               curio = (Curiosity)ItemInfo.find(Curiosity.class, item.info());
            } catch (Loading var11) {
            }
         }

         if (curio != null && curio.exp > 0) {
            lp = curio.exp;
            int rate = Curiosity.lph(curio.lph);
            if (rate > 0) {
               lph = rate;
            }
         }

         return new CupboardCatalog.Entry(name, quality, amount, lp, lph);
      } else {
         return null;
      }
   }

   private static double amountOf(GItem item) {
      float qty = 1.0F;

      try {
         Float v = (Float)item.quantity.get();
         if (v != null && v > 0.0F) {
            qty = v;
         }
      } catch (Loading var4) {
      }

      try {
         Amount amt = (Amount)ItemInfo.find(Amount.class, item.info());
         if (amt != null && (float)amt.itemnum() > qty) {
            qty = (float)amt.itemnum();
         }
      } catch (Loading var3) {
      }

      return (double)qty;
   }

   private static boolean itemReady(GItem item) {
      if (item == null) {
         return true;
      } else {
         try {
            String name = (String)item.name.get();
            return name != null && !name.isEmpty() && !"???".equals(name);
         } catch (Loading var2) {
            return false;
         }
      }
   }

   private static void closeWindow(Window w) {
      if (w != null && !w.disposed()) {
         try {
            w.wdgmsg("close", new Object[0]);
         } catch (RuntimeException var2) {
         }
      }
   }

   private static boolean waitUntil(Bot bot, long timeoutMs, CupboardBot.Check check) throws InterruptedException {
      for (long deadline = System.currentTimeMillis() + timeoutMs; System.currentTimeMillis() < deadline; Thread.sleep(50L)) {
         bot.checkCancelled();

         try {
            if (check.ok()) {
               return true;
            }
         } catch (Loading var8) {
         }
      }

      try {
         return check.ok();
      } catch (Loading var7) {
         return false;
      }
   }

   private static void highlight(Gob gob, boolean on) {
      if (gob != null && !gob.disposed()) {
         try {
            if (on) {
               gob.highlight();
            }

            GobHighlight h = (GobHighlight)gob.getattr(GobHighlight.class);
            if (h != null) {
               h.setFlashing(on);
            }
         } catch (RuntimeException var3) {
         }
      }
   }

   private static String routeBrief(List<Coord2d> route) {
      if (route != null && !route.isEmpty()) {
         StringBuilder sb = new StringBuilder();

         for (int i = 0; i < route.size(); i++) {
            if (i > 0) {
               sb.append(" -> ");
            }

            Coord2d p = route.get(i);
            sb.append(String.format("(%.0f,%.0f)", p.x, p.y));
         }

         return sb.toString();
      } else {
         return "[]";
      }
   }

   private static String label(Gob gob) {
      String name = PrototypePathfinder.displayName(gob);
      double tiles = 0.0;

      try {
         Gob me = gob.glob.sess.ui.gui.map.player();
         if (me != null && me.rc != null && gob.rc != null) {
            tiles = gob.rc.dist(me.rc) / MCache.tilesz.x;
         }
      } catch (RuntimeException var5) {
      }

      return String.format("%s #%d  %.1ft", name, gob.id, tiles);
   }

   private static void appendLog(Gob gob, List<String> items, boolean corner) {
      try {
         Path file = PathfinderLog.logFile().getParent().resolve("catalog.jsonl");
         Files.createDirectories(file.getParent());
         StringBuilder sb = new StringBuilder();
         sb.append("{\"id\":").append(gob.id);
         sb.append(",\"corner\":").append(corner);
         sb.append(",\"items\":[");

         for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
               sb.append(',');
            }

            sb.append('"').append(items.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
         }

         sb.append("]}\n");
         Writer w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

         try {
            w.write(sb.toString());
         } catch (Throwable var9) {
            if (w != null) {
               try {
                  w.close();
               } catch (Throwable var8) {
                  var9.addSuppressed(var8);
               }
            }

            throw var9;
         }

         if (w != null) {
            w.close();
         }
      } catch (IOException var10) {
      }
   }

   static final class CatalogWnd extends Hidewnd {
      private static CupboardBot.CatalogWnd instance;
      private final Textlog log;

      static CupboardBot.CatalogWnd show(GameUI gui) {
         if (instance == null || instance.disposed()) {
            instance = (CupboardBot.CatalogWnd)gui.add(new CupboardBot.CatalogWnd(), new Coord(UI.scale(40), UI.scale(80)));
         }

         instance.show();
         instance.raise();
         return instance;
      }

      private CatalogWnd() {
         super(Coord.z, "Cupboard catalog");
         this.justclose = true;
         this.log = (Textlog)this.add(new Textlog(UI.scale(360, 420)));
         this.log.maxLines = 500;
         this.pack();
      }

      void line(String text) {
         this.log.append(text, Color.BLACK);
      }

      void dbg(String text, boolean fail) {
         this.log.append("dbg: " + text, fail ? new Color(160, 0, 0) : new Color(40, 40, 120));
      }

      public void destroy() {
         if (instance == this) {
            instance = null;
         }

         super.destroy();
      }
   }

   private interface Check {
      boolean ok();
   }

   private static final class Run {
      final GameUI gui;
      final Bot bot;
      final CupboardBot.CatalogWnd wnd;
      final Map<Long, Window> bound = new HashMap<>();
      final Set<Integer> keep = new HashSet<>();
      final Set<String> banned = new HashSet<>();

      Run(GameUI gui, Bot bot, CupboardBot.CatalogWnd wnd) {
         this.gui = gui;
         this.bot = bot;
         this.wnd = wnd;
      }

      Window windowFor(Gob gob) {
         if (gob == null) {
            return null;
         } else {
            Window w = this.bound.get(gob.id);
            if (w != null && !w.disposed()) {
               return w;
            } else {
               this.bound.remove(gob.id);

               for (Window cand : this.gui.children(Window.class)) {
                  if (CupboardBot.isCupboardWindow(this.gui, cand) && cand.gobId() == gob.id) {
                     this.bound.put(gob.id, cand);
                     return cand;
                  }
               }

               return null;
            }
         }
      }

      Window openContainer(Gob gob) throws InterruptedException {
         Window existing = this.windowFor(gob);
         if (existing != null) {
            CatalogDebug.event("already open #" + gob.id);
            return existing;
         } else {
            Set<Integer> before = new HashSet<>();

            for (Window w : this.gui.children(Window.class)) {
               before.add(w.wdgid());
            }

            CatalogDebug.phase("open", CupboardBot.label(gob) + "  " + CupboardBot.movingHint(this.gui) + "  extender=" + this.hasExtender());
            Subscription sub = Reactor.FLOWER.first().subscribe(m -> m.forceChoose(new String[]{"Open"}));

            try {
               long slice = Math.max(400L, 1666L);

               for (int attempt = 0; attempt < 3; attempt++) {
                  CupboardBot.waitIdle(this.gui, this.bot);
                  CupboardBot.rclickGob(gob);
                  CatalogDebug.beginWait("open", slice, CupboardBot.flowerHint(this.gui) + "  try=" + (attempt + 1) + "  " + CupboardBot.movingHint(this.gui));
                  boolean got = CupboardBot.waitUntil(this.bot, slice, () -> {
                     CupboardBot.chooseOpen(this.gui);
                     return this.windowFor(gob) != null || this.newSince(before) != null;
                  });
                  if (got) {
                     break;
                  }
               }
            } finally {
               if (sub != null && !sub.isUnsubscribed()) {
                  sub.unsubscribe();
               }
            }

            Window found = this.windowFor(gob);
            if (found == null) {
               found = this.newSince(before);
            }

            if (found != null) {
               this.bound.put(gob.id, found);
               this.keep.add(found.wdgid());
               CatalogDebug.event("opened #" + gob.id + "  " + CupboardBot.flowerHint(this.gui));
            } else {
               CatalogDebug.fail(
                  String.format(
                     "rclick no window %s  %s  %s  extender=%s",
                     CupboardBot.label(gob),
                     CupboardBot.flowerHint(this.gui),
                     CupboardBot.movingHint(this.gui),
                     this.hasExtender()
                  )
               );
            }

            return found;
         }
      }

      Window newSince(Set<Integer> before) {
         Window newest = null;

         for (Window w : this.gui.children(Window.class)) {
            if (!before.contains(w.wdgid()) && CupboardBot.isLootWindow(this.gui, w)) {
               newest = w;
            }
         }

         return newest;
      }

      boolean hasExtender() {
         return this.extenderWindow() != null;
      }

      Window extenderWindow() {
         for (Window w : this.gui.children(Window.class)) {
            if (this.keep.contains(w.wdgid()) && !w.disposed() && CupboardBot.isLootWindow(this.gui, w)) {
               return w;
            }
         }

         return null;
      }

      void keepExtender(Gob justOpened) {
         Window mine = this.windowFor(justOpened);
         if (mine != null) {
            List<Window> old = new ArrayList<>();

            for (Window w : this.gui.children(Window.class)) {
               if (w != mine && this.keep.contains(w.wdgid()) && CupboardBot.isCupboardWindow(this.gui, w)) {
                  old.add(w);
               }
            }

            this.keep.clear();
            this.keep.add(mine.wdgid());

            for (Window wx : old) {
               this.bound.values().removeIf(v -> v == wx);
               CupboardBot.closeWindow(wx);
            }
         }
      }

      boolean openAndRead(Gob gob, boolean viaOpen) throws InterruptedException {
         Window open = this.windowFor(gob);
         if (open == null) {
            open = this.openContainer(gob);
         }

         if (open == null) {
            return false;
         } else {
            List<String> items = CupboardBot.readItems(this.bot, open);
            this.wnd.line(CupboardBot.label(gob) + (viaOpen ? "  (corner)" : ""));
            if (items.isEmpty()) {
               this.wnd.line("  (empty)");
            } else {
               for (String line : items) {
                  this.wnd.line("  " + line);
               }
            }

            CupboardBot.appendLog(gob, items, viaOpen);
            return true;
         }
      }

      void closeGob(Gob gob) {
         Window w = this.windowFor(gob);
         if (w != null) {
            this.keep.remove(w.wdgid());
            this.bound.remove(gob.id);
            CupboardBot.closeWindow(w);
         }
      }

      void closeExceptKeep() {
         List<Window> close = new ArrayList<>();

         for (Window w : this.gui.children(Window.class)) {
            if (CupboardBot.isCupboardWindow(this.gui, w) && !this.keep.contains(w.wdgid())) {
               close.add(w);
            }
         }

         for (Window wx : close) {
            this.bound.values().removeIf(v -> v == wx);
            CupboardBot.closeWindow(wx);
         }
      }
   }
}
