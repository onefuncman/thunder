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

final class MoveToAutoCaveTransitionApproachScenario implements PfTestRunner.Scenario {
   static final double MAX_TARGET_DIST = 52.25;
   static final double SAME_APPROACH_EPS = 0.01;
   private final MoveToAutoCaveTransitionApproachScenario.Navigation nav;

   MoveToAutoCaveTransitionApproachScenario() {
      this.nav = MoveToAutoCaveTransitionApproachScenario.Navigation.LIVE;
   }

   MoveToAutoCaveTransitionApproachScenario(MoveToAutoCaveTransitionApproachScenario.Navigation nav) {
      this.nav = nav;
   }

   @Override
   public String name() {
      return "move_to_auto_cave_transition_approach";
   }

   @Override
   public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
      if (run.cancelled) {
         throw new PfTestRunner.Cancelled();
      } else {
         GameUI gui = ui == null ? null : ui.gui;
         boolean inGame = gui != null && gui.map != null;
         boolean playerPresent = false;
         boolean mapfileAvailable = false;
         boolean playerIdle = false;
         if (inGame) {
            synchronized (ui) {
               Gob me = gui.map.player();
               playerPresent = me != null && me.rc != null;
               playerIdle = me == null || me.getattr(Moving.class) == null;
            }

            mapfileAvailable = gui.mapfile != null && gui.mapfile.file != null;
         }

         List<JSONObject> checks = preflightChecks(inGame, playerPresent, mapfileAvailable, playerIdle, Bot.hasCurrent());
         if (PfTestRunner.verdictOf(checks).equals("FAIL")) {
            String why = PfTestHarness.firstFailDetail(checks);
            return PfTestHarness.body(checks, "no movement performed: " + why, factsJson(null, null, "NOT_STARTED", why));
         } else if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            PrototypePathfinder.Scene scene;
            synchronized (ui) {
               scene = PrototypePathfinder.observe(gui);
            }

            TransitionApproachSelector.Selection sel = TransitionApproachSelector.caveTransitionApproach(scene);
            checks.add(SelectCaveTransitionApproachScenario.selectionCheck(sel));
            if (sel.refused()) {
               return PfTestHarness.body(checks, "no movement performed: " + sel.refusal, factsJson(sel, null, "SELECTION_REFUSED", null));
            } else if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            } else {
               PrototypePathfinder.Scene fresh;
               synchronized (ui) {
                  fresh = PrototypePathfinder.observe(gui);
               }

               JSONObject approach = approachRevalidationCheck(sel, fresh);
               checks.add(approach);
               if ("fail".equals(approach.getString("status"))) {
                  return PfTestHarness.body(
                     checks, "no movement performed: " + approach.getString("detail"), factsJson(sel, null, "REVALIDATION_REFUSED", null)
                  );
               } else {
                  JSONObject target = MoveToAutoOpenGroundScenario.revalidationCheck(fresh, sel.approachWorld, 52.25);
                  checks.add(target);
                  if ("fail".equals(target.getString("status"))) {
                     return PfTestHarness.body(
                        checks, "no movement performed: " + target.getString("detail"), factsJson(sel, null, "REVALIDATION_REFUSED", null)
                     );
                  } else if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  } else {
                     Bot bot = Bot.execute(new BotAction[0]);
                     AtomicBoolean timedOut = new AtomicBoolean(false);
                     MoveToAutoOpenGroundScenario.MoveResult mv = PfTestHarness.moveWatch(
                        run, timedOut, 120000L, () -> this.nav.run(gui, sel.approachWorld, bot, run)
                     );
                     if (run.cancelled) {
                        throw new PfTestRunner.Cancelled(cancelledBody(sel, mv));
                     } else {
                        return completedBody(sel, mv, timedOut.get(), MoveToAutoOpenGroundScenario.finalArrival(gui, ui, mv, sel.approachWorld));
                     }
                  }
               }
            }
         }
      }
   }

   static MoveToAutoOpenGroundScenario.MoveResult liveMove(GameUI gui, Coord2d target, Bot bot) {
      PrototypePathfinder.Plan plan = PrototypePathfinder.planAny(gui, Collections.singletonList(target), true);
      return MoveToAutoOpenGroundScenario.walkPlan(gui, plan, target, bot, 60000L, 60000L);
   }

   static List<JSONObject> preflightChecks(boolean inGame, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy) {
      return PfTestHarness.autoMovePreflightChecks("move_to_auto_cave_transition_approach", inGame, playerPresent, mapfileAvailable, playerIdle, botBusy);
   }

   static JSONObject approachRevalidationCheck(TransitionApproachSelector.Selection selected, PrototypePathfinder.Scene fresh) {
      if (selected != null && !selected.refused() && selected.approachWorld != null) {
         TransitionApproachSelector.Selection now = TransitionApproachSelector.caveTransitionApproach(fresh);
         if (now.refused()) {
            return PfTestRunner.check("approach_revalidated", false, "fresh cave approach refused " + now.refusal + ": " + now.evidence + " (no-move)");
         } else {
            return now.kind == selected.kind && now.approachWorld != null && !(now.approachWorld.dist(selected.approachWorld) > 0.01)
               ? PfTestRunner.check("approach_revalidated", true, "same " + selected.kind + " approach remains selected in the fresh observation")
               : PfTestRunner.check("approach_revalidated", false, "fresh cave approach changed (no-move)");
         }
      } else {
         return PfTestRunner.check("approach_revalidated", false, "no selected cave approach to revalidate (no-move)");
      }
   }

   static JSONObject cancelledBody(TransitionApproachSelector.Selection sel, MoveToAutoOpenGroundScenario.MoveResult mv) {
      List<JSONObject> checks = new ArrayList<>();
      checks.add(MoveToAutoOpenGroundScenario.routeCheck(mv));
      checks.add(MoveToAutoOpenGroundScenario.walkCheck(mv));
      checks.add(PfTestRunner.check("run_cancelled", false, "the run was cancelled; no transition was clicked or crossed"));
      return PfTestHarness.body(checks, "cancelled", factsJson(sel, mv, "WALKED", null));
   }

   static JSONObject completedBody(
      TransitionApproachSelector.Selection sel, MoveToAutoOpenGroundScenario.MoveResult mv, boolean timedOut, JSONObject arrival
   ) {
      List<JSONObject> checks = new ArrayList<>();
      if (timedOut) {
         checks.add(PfTestRunner.check("move_timeout", false, "hard wall-clock deadline exceeded; movement interrupted"));
      }

      checks.add(MoveToAutoOpenGroundScenario.routeCheck(mv));
      checks.add(MoveToAutoOpenGroundScenario.walkCheck(mv));
      if (arrival != null) {
         checks.add(arrival);
      }

      boolean walked = mv != null && (mv.walk != null || mv.cancelledDetail != null);
      return PfTestHarness.body(
         checks, timedOut ? "hard wall-clock deadline exceeded; movement interrupted" : null, factsJson(sel, mv, walked ? "WALKED" : "ROUTE_REFUSED", null)
      );
   }

   static JSONObject factsJson(
      TransitionApproachSelector.Selection sel, MoveToAutoOpenGroundScenario.MoveResult mv, String status, String reason
   ) {
      JSONObject f = sel == null
         ? SelectCaveTransitionApproachScenario.refusalFacts("PREFLIGHT", reason == null ? "preflight refused" : reason)
         : SelectCaveTransitionApproachScenario.factsJson(sel);
      f.put("moved", mv != null && (mv.walk != null || mv.cancelledDetail != null));
      f.put("arrived", mv != null && mv.walk == WaypointWalker.Result.ARRIVED);
      f.put("status", status == null ? "NOT_STARTED" : status);
      if (mv != null) {
         f.put("plan_status", mv.planStatus.name());
         f.put("expanded", mv.expanded);
         f.put("obstacles", mv.obstacles);
         if (mv.cancelledDetail != null) {
            f.put("walk_outcome", "CANCELLED");
         } else if (mv.walk != null) {
            f.put("walk_outcome", mv.walk.name());
         }

         if (mv.endPos != null) {
            f.put("end_pos", new JSONArray().put(PfTestRunner.round2(mv.endPos.x)).put(PfTestRunner.round2(mv.endPos.y)));
         }

         f.put("elapsed_ms", mv.elapsedMs);
      }

      return f;
   }

   interface Navigation {
      MoveToAutoCaveTransitionApproachScenario.Navigation LIVE = (gui, target, bot, run) -> MoveToAutoCaveTransitionApproachScenario.liveMove(
            gui, target, bot
         );

      MoveToAutoOpenGroundScenario.MoveResult run(GameUI var1, Coord2d var2, Bot var3, PfTestRunner.Run var4) throws Exception;
   }
}
