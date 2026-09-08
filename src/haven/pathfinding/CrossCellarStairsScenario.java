package haven.pathfinding;

import auto.Bot;
import auto.Bot.BotAction;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.HackThread;
import haven.MapFile;
import haven.Moving;
import haven.UI;
import haven.Utils;
import haven.NamedPlaceResolver.Place;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

final class CrossCellarStairsScenario implements PfTestRunner.Scenario {
   static final double MAX_TARGET_DIST = 52.25;
   static final double MAX_INTERACT_DIST = 35.0;
   static final long CROSS_WAIT_MS = 30000L;
   private final CrossCellarStairsScenario.Navigation nav;
   private final CrossCellarStairsScenario.Interaction interaction;

   CrossCellarStairsScenario() {
      this.nav = (g, t, b, r) -> MoveToAutoCaveTransitionApproachScenario.liveMove(g, t, b);
      this.interaction = gob -> gob.rclick(0);
   }

   CrossCellarStairsScenario(CrossCellarStairsScenario.Navigation nav, CrossCellarStairsScenario.Interaction interaction) {
      this.nav = nav;
      this.interaction = interaction;
   }

   @Override
   public String name() {
      return "cross_cellar_stairs";
   }

   @Override
   public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
      if (run.cancelled) {
         throw new PfTestRunner.Cancelled();
      } else {
         GameUI gui = ui == null ? null : ui.gui;
         boolean inGame = gui != null && gui.map != null;
         boolean present = false;
         boolean idle = false;
         boolean mapfile = inGame && gui.mapfile != null && gui.mapfile.file != null;
         if (inGame) {
            synchronized (ui) {
               Gob p = gui.map.player();
               present = p != null && p.rc != null;
               idle = p == null || p.getattr(Moving.class) == null;
            }
         }

         List<JSONObject> checks = PfTestHarness.autoMovePreflightChecks("cross_cellar_stairs", inGame, present, mapfile, idle, Bot.hasCurrent());
         if ("FAIL".equals(PfTestRunner.verdictOf(checks))) {
            return PfTestHarness.body(
               checks, "no interaction performed: " + PfTestHarness.firstFailDetail(checks), facts(null, null, false, "NOT_STARTED", "PREFLIGHT")
            );
         } else {
            PrototypePathfinder.Scene scene;
            synchronized (ui) {
               scene = PrototypePathfinder.observe(gui);
            }

            TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(scene);
            checks.add(SelectCaveTransitionApproachScenario.selectionCheck(sel));
            boolean kind = supportedKind(sel);
            checks.add(kindCheck(kind));
            if (!sel.refused() && kind) {
               PrototypePathfinder.Scene fresh;
               synchronized (ui) {
                  fresh = PrototypePathfinder.observe(gui);
               }

               JSONObject ar = MoveToAutoCaveTransitionApproachScenario.approachRevalidationCheck(sel, fresh);
               checks.add(ar);
               JSONObject tr = MoveToAutoOpenGroundScenario.revalidationCheck(fresh, sel.approachWorld, 52.25);
               checks.add(tr);
               if (!"fail".equals(ar.getString("status")) && !"fail".equals(tr.getString("status"))) {
                  Bot bot = Bot.execute(new BotAction[0]);
                  AtomicBoolean timedOut = new AtomicBoolean(false);
                  MoveToAutoOpenGroundScenario.MoveResult mv = PfTestHarness.moveWatch(
                     run, timedOut, 120000L, () -> this.nav.run(gui, sel.approachWorld, bot, run)
                  );
                  if (run.cancelled) {
                     throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, false));
                  } else {
                     JSONObject arrival = MoveToAutoOpenGroundScenario.finalArrival(gui, ui, mv, sel.approachWorld);
                     checks.add(MoveToAutoOpenGroundScenario.routeCheck(mv));
                     checks.add(MoveToAutoOpenGroundScenario.walkCheck(mv));
                     checks.add(arrival);
                     if (!timedOut.get() && "pass".equals(arrival.getString("status"))) {
                        Gob stairs;
                        synchronized (ui) {
                           stairs = resolveStairs(gui, PrototypePathfinder.observe(gui));
                        }

                        boolean resolved = stairs != null;
                        checks.add(
                           PfTestRunner.check(
                              "fixture_resolved",
                              resolved,
                              resolved ? "unique fresh cellar stairs resolved" : "unique cellar stairs unavailable (no interaction)"
                           )
                        );
                        if (!resolved) {
                           return PfTestHarness.body(
                              checks,
                              "no interaction performed: cellar stairs unavailable",
                              facts(sel, mv, false, "REVALIDATION_REFUSED", "FIXTURE_UNAVAILABLE")
                           );
                        } else {
                           boolean range;
                           synchronized (ui) {
                              Gob p = gui.map.player();
                              range = p != null && p.rc != null && stairs.rc != null && p.rc.dist(stairs.rc) <= 35.0;
                           }

                           checks.add(rangeCheck(range));
                           if (!range) {
                              return PfTestHarness.body(
                                 checks,
                                 "no interaction performed: cellar stairs out of range",
                                 facts(sel, mv, false, "REVALIDATION_REFUSED", "OUT_OF_RANGE")
                              );
                           } else if (run.cancelled) {
                              throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, false));
                           } else {
                              issueOnce(this.interaction, stairs);
                              checks.add(interactionCheck());
                              boolean crossed = CrossCellarDoorScenario.awaitCross(run, ui, gui, stairs.id, System.currentTimeMillis() + 30000L);
                              checks.add(completionCheck(crossed));
                              if (run.cancelled) {
                                 throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, true));
                              } else {
                                 return PfTestHarness.body(
                                    checks,
                                    crossed ? null : "one interaction issued; crossing not confirmed",
                                    facts(sel, mv, true, crossed ? "CROSSED" : "NO_RESPONSE", crossed ? null : "NO_RESPONSE")
                                 );
                              }
                           }
                        }
                     } else {
                        return PfTestHarness.body(
                           checks, "no interaction performed: approach walk failed", facts(sel, mv, false, "WALK_REFUSED", "WALK_REFUSED")
                        );
                     }
                  }
               } else {
                  return PfTestHarness.body(
                     checks, "no interaction performed: revalidation refused", facts(sel, null, false, "REVALIDATION_REFUSED", "REVALIDATION_REFUSED")
                  );
               }
            } else {
               return PfTestHarness.body(
                  checks,
                  "no interaction performed: unsupported transition kind",
                  facts(sel, null, false, "SELECTION_REFUSED", sel.refused() ? sel.refusal.name() : "UNSUPPORTED_KIND")
               );
            }
         }
      }
   }

   static boolean supportedKind(TransitionApproachSelector.Selection sel) {
      return sel != null && sel.kind == TransitionApproachSelector.TransitionKind.CELLAR_STAIRS;
   }

   static JSONObject kindCheck(boolean supported) {
      return PfTestRunner.check(
         "fixture_kind_supported", supported, supported ? "selected fixture is cellar stairs" : "only CELLAR_STAIRS is supported (no interaction)"
      );
   }

   static PrototypePathfinder.GobGeom uniqueStairsGeom(PrototypePathfinder.Scene scene) {
      PrototypePathfinder.GobGeom found = null;

      for (PrototypePathfinder.GobGeom g : TransitionApproachSelector.caveTransitionGobs(scene)) {
         if (TransitionApproachSelector.caveTransitionKind(g.resid) == TransitionApproachSelector.TransitionKind.CELLAR_STAIRS) {
            if (found != null) {
               return null;
            }

            found = g;
         }
      }

      return found;
   }

   static Gob resolveStairs(GameUI gui, PrototypePathfinder.Scene scene) {
      PrototypePathfinder.GobGeom found = uniqueStairsGeom(scene);
      return found == null ? null : gui.ui.sess.glob.oc.getgob(found.id);
   }

   static JSONObject rangeCheck(boolean range) {
      return PfTestRunner.check(
         "in_interaction_range",
         range,
         range ? "cellar stairs are within bounded interaction range" : "cellar stairs out of interaction range (no interaction)"
      );
   }

   static void issueOnce(CrossCellarStairsScenario.Interaction interaction, Gob stairs) {
      interaction.rightClick(stairs);
   }

   static JSONObject interactionCheck() {
      return PfTestRunner.check("interaction_issued", true, "one cellar-stairs right-click issued");
   }

   static JSONObject completionCheck(boolean crossed) {
      return PfTestRunner.check(
         "crossed", crossed, crossed ? "server relocated player; source cellar stairs absent" : "no confirmed relocation after one click"
      );
   }

   static JSONObject cancelledBody(TransitionApproachSelector.Selection sel, MoveToAutoOpenGroundScenario.MoveResult mv, boolean issued) {
      List<JSONObject> checks = new ArrayList<>();
      checks.add(PfTestRunner.check("run_cancelled", false, issued ? "cancelled after one interaction; no retry" : "cancelled before interaction"));
      return PfTestHarness.body(checks, "cancelled", facts(sel, mv, issued, "CANCELLED", "CANCELLED"));
   }

   static JSONObject facts(
      TransitionApproachSelector.Selection sel, MoveToAutoOpenGroundScenario.MoveResult mv, boolean issued, String status, String refusal
   ) {
      JSONObject f = MoveToAutoCaveTransitionApproachScenario.factsJson(sel, mv, status, refusal);
      f.put("interaction_issued", issued);
      f.put("crossed", "CROSSED".equals(status));
      return f;
   }

   interface Interaction {
      void rightClick(Gob var1);
   }

   interface Navigation {
      MoveToAutoOpenGroundScenario.MoveResult run(GameUI var1, Coord2d var2, Bot var3, PfTestRunner.Run var4) throws Exception;
   }
}
