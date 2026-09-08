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

final class CrossMineholeScenario implements PfTestRunner.Scenario {
   static final double MAX_TARGET_DIST = 52.25;
   static final double MAX_INTERACT_DIST = 35.0;
   static final long CROSS_WAIT_MS = 30000L;
   static final long CROSS_POLL_MS = 100L;
   private final CrossMineholeScenario.Navigation nav;
   private final CrossMineholeScenario.Interaction interaction;

   CrossMineholeScenario() {
      this.nav = (g, t, b, r) -> MoveToAutoCaveTransitionApproachScenario.liveMove(g, t, b);
      this.interaction = gob -> gob.rclick(0);
   }

   CrossMineholeScenario(CrossMineholeScenario.Navigation nav, CrossMineholeScenario.Interaction interaction) {
      this.nav = nav;
      this.interaction = interaction;
   }

   @Override
   public String name() {
      return "cross_minehole";
   }

   @Override
   public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
      if (run.cancelled) {
         throw new PfTestRunner.Cancelled();
      } else {
         List<JSONObject> checks = new ArrayList<>();
         boolean confirmed = confirmed();
         checks.add(confirmationCheck(confirmed));
         if (!confirmed) {
            return PfTestHarness.body(
               checks, "no interaction performed: minehole descent not manually confirmed", facts(null, null, false, "NOT_CONFIRMED", "NOT_CONFIRMED", null)
            );
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

            checks.addAll(PfTestHarness.autoMovePreflightChecks("cross_minehole", inGame, present, mapfile, idle, Bot.hasCurrent()));
            if ("FAIL".equals(PfTestRunner.verdictOf(checks))) {
               return PfTestHarness.body(
                  checks, "no interaction performed: " + PfTestHarness.firstFailDetail(checks), facts(null, null, false, "NOT_STARTED", "PREFLIGHT", null)
               );
            } else {
               PrototypePathfinder.Scene scene;
               synchronized (ui) {
                  scene = PrototypePathfinder.observe(gui);
               }

               TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(scene);
               checks.add(SelectCaveTransitionApproachScenario.selectionCheck(sel));
               if (sel.refused()) {
                  return PfTestHarness.body(
                     checks, "no interaction performed: " + sel.refusal, facts(sel, null, false, "SELECTION_REFUSED", sel.refusal.name(), null)
                  );
               } else {
                  boolean kind = supportedKind(sel);
                  checks.add(kindCheck(kind));
                  if (!kind) {
                     return PfTestHarness.body(
                        checks,
                        "no interaction performed: unsupported transition kind",
                        facts(sel, null, false, "SELECTION_REFUSED", "UNSUPPORTED_KIND", null)
                     );
                  } else {
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
                              Gob hole;
                              synchronized (ui) {
                                 hole = resolveMinehole(gui, PrototypePathfinder.observe(gui));
                              }

                              boolean resolved = hole != null;
                              checks.add(
                                 PfTestRunner.check(
                                    "fixture_resolved",
                                    resolved,
                                    resolved ? "unique fresh minehole resolved" : "unique minehole unavailable (no interaction)"
                                 )
                              );
                              if (!resolved) {
                                 return PfTestHarness.body(
                                    checks,
                                    "no interaction performed: minehole unavailable",
                                    facts(sel, mv, false, "REVALIDATION_REFUSED", "FIXTURE_UNAVAILABLE", null)
                                 );
                              } else {
                                 boolean range;
                                 synchronized (ui) {
                                    Gob p = gui.map.player();
                                    range = p != null && p.rc != null && hole.rc != null && p.rc.dist(hole.rc) <= 35.0;
                                 }

                                 checks.add(rangeCheck(range));
                                 if (!range) {
                                    return PfTestHarness.body(
                                       checks,
                                       "no interaction performed: minehole out of range",
                                       facts(sel, mv, false, "REVALIDATION_REFUSED", "OUT_OF_RANGE", null)
                                    );
                                 } else if (run.cancelled) {
                                    throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, false));
                                 } else {
                                    MineholeDescent.Observation before;
                                    synchronized (ui) {
                                       before = observeLocation(ui, gui);
                                    }

                                    checks.add(observationCheck(before != null));
                                    if (before == null) {
                                       return PfTestHarness.body(
                                          checks,
                                          "no interaction performed: pre-descent location unavailable",
                                          facts(sel, mv, false, "REVALIDATION_REFUSED", "LOCATION_UNAVAILABLE", null)
                                       );
                                    } else {
                                       issueOnce(this.interaction, hole);
                                       checks.add(interactionCheck());
                                       MineholeDescent.Completion completion = awaitDescent(
                                          run, ui, gui, before, System.currentTimeMillis() + 30000L
                                       );
                                       checks.add(completionCheck(completion));
                                       if (run.cancelled) {
                                          throw new PfTestRunner.Cancelled(cancelledBody(sel, mv, true));
                                       } else {
                                          boolean descended = completion == MineholeDescent.Completion.DESCENDED;
                                          return PfTestHarness.body(
                                             checks,
                                             descended ? null : "one interaction issued; descent not confirmed",
                                             facts(
                                                sel, mv, true, descended ? "DESCENDED" : "NO_RESPONSE", descended ? null : "NO_RESPONSE", completion.name()
                                             )
                                          );
                                       }
                                    }
                                 }
                              }
                           } else {
                              return PfTestHarness.body(
                                 checks, "no interaction performed: approach walk failed", facts(sel, mv, false, "WALK_REFUSED", "WALK_REFUSED", null)
                              );
                           }
                        }
                     } else {
                        return PfTestHarness.body(
                           checks,
                           "no interaction performed: revalidation refused",
                           facts(sel, null, false, "REVALIDATION_REFUSED", "REVALIDATION_REFUSED", null)
                        );
                     }
                  }
               }
            }
         }
      }
   }

   static boolean confirmed() {
      return "true".equalsIgnoreCase(System.getProperty("haven.pf.minehole.confirm", "false").trim());
   }

   static JSONObject confirmationCheck(boolean confirmed) {
      return PfTestRunner.check(
         "minehole_confirmed",
         confirmed,
         confirmed
            ? "launch-time manual confirmation present (haven.pf.minehole.confirm=true)"
            : "minehole descent requires manual confirmation at launch (-Dhaven.pf.minehole.confirm=true); no interaction"
      );
   }

   static boolean supportedKind(TransitionApproachSelector.Selection sel) {
      return sel != null && sel.kind == TransitionApproachSelector.TransitionKind.MINEHOLE;
   }

   static JSONObject kindCheck(boolean supported) {
      return PfTestRunner.check(
         "fixture_kind_supported", supported, supported ? "selected fixture is a minehole" : "only MINEHOLE is supported (no interaction)"
      );
   }

   static PrototypePathfinder.GobGeom uniqueMineholeGeom(PrototypePathfinder.Scene scene) {
      PrototypePathfinder.GobGeom found = null;

      for (PrototypePathfinder.GobGeom g : TransitionApproachSelector.caveTransitionGobs(scene)) {
         if (TransitionApproachSelector.caveTransitionKind(g.resid) == TransitionApproachSelector.TransitionKind.MINEHOLE) {
            if (found != null) {
               return null;
            }

            found = g;
         }
      }

      return found;
   }

   static Gob resolveMinehole(GameUI gui, PrototypePathfinder.Scene scene) {
      PrototypePathfinder.GobGeom found = uniqueMineholeGeom(scene);
      return found == null ? null : gui.ui.sess.glob.oc.getgob(found.id);
   }

   static JSONObject rangeCheck(boolean range) {
      return PfTestRunner.check(
         "in_interaction_range", range, range ? "minehole is within bounded interaction range" : "minehole out of interaction range (no interaction)"
      );
   }

   static void issueOnce(CrossMineholeScenario.Interaction interaction, Gob hole) {
      interaction.rightClick(hole);
   }

   static JSONObject interactionCheck() {
      return PfTestRunner.check("interaction_issued", true, "one minehole right-click issued");
   }

   static MineholeDescent.Observation observeLocation(UI ui, GameUI gui) {
      synchronized (ui) {
         NamedPlaceNavigator.Location loc = NamedPlaceNavigator.liveState(gui).current();
         if (loc == null) {
            return null;
         } else {
            PrototypePathfinder.Scene scene = PrototypePathfinder.observe(gui);
            if (scene == null) {
               return null;
            } else {
               Gob p = gui.map.player();
               boolean idle = p == null || p.getattr(Moving.class) == null;
               return new MineholeDescent.Observation(loc.seg, loc.world, idle, !scene.playerInSolid);
            }
         }
      }
   }

   static JSONObject observationCheck(boolean available) {
      return PfTestRunner.check(
         "location_observed",
         available,
         available ? "pre-descent segment/world/idle/body-free observation captured" : "pre-descent location observation unavailable (no interaction)"
      );
   }

   static MineholeDescent.Completion awaitDescent(
      PfTestRunner.Run run, UI ui, GameUI gui, MineholeDescent.Observation before, long deadline
   ) throws InterruptedException {
      MineholeDescent.Completion last = MineholeDescent.Completion.LOCATION_UNAVAILABLE;

      while (System.currentTimeMillis() < deadline) {
         if (run.cancelled) {
            return last;
         }

         MineholeDescent.Observation after = observeLocation(ui, gui);
         last = MineholeDescent.completion(before, after);
         if (last == MineholeDescent.Completion.DESCENDED) {
            return last;
         }

         Thread.sleep(100L);
      }

      return last;
   }

   static JSONObject completionCheck(MineholeDescent.Completion completion) {
      boolean descended = completion == MineholeDescent.Completion.DESCENDED;
      return PfTestRunner.check(
         "crossed",
         descended,
         descended
            ? "server relocated player to a new segment; idle and body-free landing confirmed"
            : "no confirmed descent after one click (" + completion + ")"
      );
   }

   static JSONObject cancelledBody(TransitionApproachSelector.Selection sel, MoveToAutoOpenGroundScenario.MoveResult mv, boolean issued) {
      List<JSONObject> checks = new ArrayList<>();
      checks.add(PfTestRunner.check("run_cancelled", false, issued ? "cancelled after one interaction; no retry" : "cancelled before interaction"));
      return PfTestHarness.body(checks, "cancelled", facts(sel, mv, issued, "CANCELLED", "CANCELLED", null));
   }

   static JSONObject facts(
      TransitionApproachSelector.Selection sel,
      MoveToAutoOpenGroundScenario.MoveResult mv,
      boolean issued,
      String status,
      String refusal,
      String completion
   ) {
      JSONObject f = MoveToAutoCaveTransitionApproachScenario.factsJson(sel, mv, status, refusal);
      f.put("interaction_issued", issued);
      f.put("crossed", "DESCENDED".equals(status));
      if (completion != null) {
         f.put("descent_completion", completion);
      }

      return f;
   }

   interface Interaction {
      void rightClick(Gob var1);
   }

   interface Navigation {
      MoveToAutoOpenGroundScenario.MoveResult run(GameUI var1, Coord2d var2, Bot var3, PfTestRunner.Run var4) throws Exception;
   }
}
