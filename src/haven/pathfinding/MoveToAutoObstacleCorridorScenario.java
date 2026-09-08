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

final class MoveToAutoObstacleCorridorScenario implements PfTestRunner.Scenario {
   static final long MOVE_WAYPOINT_TIMEOUT_MS = 60000L;
   static final long MOVE_WALK_BUDGET_MS = 60000L;
   static final long MOVE_TOTAL_TIMEOUT_MS = 120000L;
   static final double MAX_TARGET_DIST = 44.0;
   static final double ARRIVAL_EPS = 3.0;
   static final int MIN_NONTRIVIAL_WAYPOINTS = 3;
   static final double MIN_DETOUR_RATIO = 1.05;
   private final MoveToAutoObstacleCorridorScenario.Navigation nav;

   MoveToAutoObstacleCorridorScenario() {
      this.nav = MoveToAutoObstacleCorridorScenario.Navigation.LIVE;
   }

   MoveToAutoObstacleCorridorScenario(MoveToAutoObstacleCorridorScenario.Navigation nav) {
      this.nav = nav;
   }

   @Override
   public String name() {
      return "move_to_auto_obstacle_corridor";
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
            return PfTestHarness.body(checks, "no movement performed: " + why, factsJson(null, null, null, "NOT_STARTED", why));
         } else if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            PrototypePathfinder.Scene scene;
            synchronized (ui) {
               scene = PrototypePathfinder.observe(gui);
            }

            NavigationTestSpotSelector.Selection sel = NavigationTestSpotSelector.localObstacleOrCorridor(scene);
            checks.add(NavigationTestSpotScenario.selectionCheck(sel));
            if (sel.refused()) {
               return PfTestHarness.body(checks, "no movement performed: " + sel.refusal, factsJson(sel, null, null, "SELECTION_REFUSED", null));
            } else if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            } else {
               Coord2d target = sel.targetWorld;
               PrototypePathfinder.Scene fresh;
               synchronized (ui) {
                  fresh = PrototypePathfinder.observe(gui);
               }

               JSONObject reval = MoveToAutoOpenGroundScenario.revalidationCheck(fresh, target, 44.0);
               checks.add(reval);
               if ("fail".equals(reval.getString("status"))) {
                  return PfTestHarness.body(
                     checks, "no movement performed: " + reval.getString("detail"), factsJson(sel, null, null, "REVALIDATION_REFUSED", null)
                  );
               } else if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  PrototypePathfinder.Plan plan = PrototypePathfinder.planAny(gui, Collections.singletonList(target), true);
                  JSONObject routeReval = routeRevalidationCheck(fresh, plan, target);
                  checks.add(routeReval);
                  if ("fail".equals(routeReval.getString("status"))) {
                     return PfTestHarness.body(
                        checks, "no movement performed: " + routeReval.getString("detail"), factsJson(sel, plan, null, "REVALIDATION_REFUSED", null)
                     );
                  } else if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  } else {
                     Bot bot = Bot.execute(new BotAction[0]);
                     AtomicBoolean timedOut = new AtomicBoolean(false);
                     MoveToAutoOpenGroundScenario.MoveResult mv = PfTestHarness.moveWatch(
                        run, timedOut, 120000L, () -> this.nav.run(gui, plan, target, bot, run)
                     );
                     if (run.cancelled) {
                        throw new PfTestRunner.Cancelled(cancelledBody(sel, plan, routeReval, mv));
                     } else {
                        return completedBody(sel, plan, mv, timedOut.get(), MoveToAutoOpenGroundScenario.finalArrival(gui, ui, mv, target));
                     }
                  }
               }
            }
         }
      }
   }

   static MoveToAutoOpenGroundScenario.MoveResult liveMove(GameUI gui, PrototypePathfinder.Plan plan, Coord2d target, Bot bot) {
      return MoveToAutoOpenGroundScenario.walkPlan(gui, plan, target, bot, 60000L, 60000L);
   }

   static List<JSONObject> preflightChecks(boolean inGame, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy) {
      return PfTestHarness.autoMovePreflightChecks("move_to_auto_obstacle_corridor", inGame, playerPresent, mapfileAvailable, playerIdle, botBusy);
   }

   static JSONObject routeRevalidationCheck(PrototypePathfinder.Scene fresh, PrototypePathfinder.Plan plan, Coord2d target) {
      if (fresh == null || fresh.player == null) {
         return PfTestRunner.check("route_revalidated", false, "player gob not observable at route revalidation (no-move)");
      } else if (target == null || plan == null) {
         return PfTestRunner.check("route_revalidated", false, "no live local plan to revalidate (no-move)");
      } else if (plan.status != PrototypePathfinder.Plan.Status.REACHED) {
         return PfTestRunner.check("route_revalidated", false, "live local plan " + plan.status + " to the selected target; refusing to move (no-move)");
      } else {
         Coord start = fresh.cellOf(fresh.player);
         Coord goal = fresh.cellOf(target);
         if (start != null && goal != null && NavigationTestSpotSelector.lineOccluded(fresh, start, goal)) {
            double direct = fresh.player.dist(target);
            double route = routeLength(plan.waypoints);
            if (plan.waypoints.size() < 3) {
               return PfTestRunner.check(
                  "route_revalidated",
                  false,
                  "live local plan is trivial (" + plan.waypoints.size() + " waypoints); refusing to degrade to a plain open-ground route (no-move)"
               );
            } else {
               return route <= direct * 1.05
                  ? PfTestRunner.check(
                     "route_revalidated",
                     false,
                     String.format(
                        "live local plan is not a material detour (route %.2fu vs direct %.2fu); refusing to degrade to a plain open-ground route (no-move)",
                        route,
                        direct
                     )
                  )
                  : PfTestRunner.check(
                     "route_revalidated",
                     true,
                     String.format(
                        "direct line occluded; plan REACHED with %d waypoints, route %.2fu vs direct %.2fu (detour ratio %.2f)",
                        plan.waypoints.size(),
                        route,
                        direct,
                        route / direct
                     )
                  );
            }
         } else {
            return PfTestRunner.check(
               "route_revalidated",
               false,
               "the direct line to the target is no longer occluded in the fresh observation; the fixture degraded to open ground (no-move)"
            );
         }
      }
   }

   static double routeLength(List<Coord2d> waypoints) {
      double len = 0.0;
      if (waypoints == null) {
         return 0.0;
      } else {
         for (int i = 1; i < waypoints.size(); i++) {
            Coord2d a = waypoints.get(i - 1);
            Coord2d b = waypoints.get(i);
            if (a != null && b != null) {
               len += a.dist(b);
            }
         }

         return len;
      }
   }

   static JSONObject cancelledBody(
      NavigationTestSpotSelector.Selection sel,
      PrototypePathfinder.Plan plan,
      JSONObject routeReval,
      MoveToAutoOpenGroundScenario.MoveResult mv
   ) {
      List<JSONObject> checks = new ArrayList<>();
      if (routeReval != null) {
         checks.add(routeReval);
      }

      checks.add(MoveToAutoOpenGroundScenario.walkCheck(mv));
      checks.add(PfTestRunner.check("run_cancelled", false, "the run was cancelled (POST /pf/cancel); terminal movement facts above are partial"));
      return PfTestHarness.body(
         checks, "cancelled: " + (mv != null && mv.cancelledDetail != null ? mv.cancelledDetail : "run cancelled"), factsJson(sel, plan, mv, "WALKED", null)
      );
   }

   static JSONObject completedBody(
      NavigationTestSpotSelector.Selection sel,
      PrototypePathfinder.Plan plan,
      MoveToAutoOpenGroundScenario.MoveResult mv,
      boolean timedOut,
      JSONObject arrival
   ) {
      List<JSONObject> checks = new ArrayList<>();
      if (timedOut) {
         checks.add(PfTestRunner.check("move_timeout", false, "hard wall-clock deadline exceeded (120000ms); movement interrupted"));
      }

      checks.add(MoveToAutoOpenGroundScenario.walkCheck(mv));
      if (arrival != null) {
         checks.add(arrival);
      }

      String note = null;
      if (timedOut) {
         note = "hard wall-clock deadline exceeded (120000ms); movement interrupted";
      } else if (mv != null && mv.cancelledDetail != null) {
         note = "movement cancelled (detail: " + mv.cancelledDetail + ")";
      } else if (mv != null && mv.walk != WaypointWalker.Result.ARRIVED) {
         note = "movement did not verify arrival at the selected target";
      }

      boolean walked = mv != null && (mv.walk != null || mv.cancelledDetail != null);
      return PfTestHarness.body(checks, note, factsJson(sel, plan, mv, walked ? "WALKED" : "ROUTE_REFUSED", null));
   }

   static JSONObject factsJson(
      NavigationTestSpotSelector.Selection sel,
      PrototypePathfinder.Plan plan,
      MoveToAutoOpenGroundScenario.MoveResult mv,
      String status,
      String reason
   ) {
      JSONObject f = new JSONObject();
      f.put("moved", mv != null && (mv.walk != null || mv.cancelledDetail != null));
      f.put("arrived", mv != null && mv.walk == WaypointWalker.Result.ARRIVED);
      f.put("profile", NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR.name());
      f.put("status", status == null ? "NOT_STARTED" : status);
      if (sel != null) {
         f.put("selected", sel.selected());
         f.put("refusal", sel.refused() ? sel.refusal.name() : JSONObject.NULL);
         f.put("reason", sel.evidence);
         f.put("candidates", sel.candidates.size());
         if (!sel.candidates.isEmpty()) {
            NavigationTestSpotSelector.Candidate best = sel.candidates.get(0);
            f.put("best_score", PfTestRunner.round2(best.score));
            f.put("route_cells", best.route.size());
            f.put("route_expanded", best.routeExpanded);
         }

         if (sel.targetTile != null) {
            f.put("target_tile", new JSONArray().put(sel.targetTile.x).put(sel.targetTile.y));
         }

         if (sel.targetWorld != null) {
            f.put("target_world", new JSONArray().put(PfTestRunner.round2(sel.targetWorld.x)).put(PfTestRunner.round2(sel.targetWorld.y)));
         }
      } else {
         f.put("selected", false);
         f.put("refusal", "PREFLIGHT");
         f.put("reason", reason == null ? "preflight refused" : reason);
         f.put("candidates", 0);
      }

      if (plan != null) {
         f.put("plan_status", plan.status.name());
         f.put("expanded", plan.expanded);
         f.put("obstacles", plan.obstacles);
         f.put("plan_waypoints", plan.waypoints.size());
         double direct = sel != null && sel.targetWorld != null && !plan.waypoints.isEmpty() && plan.waypoints.get(0) != null
            ? plan.waypoints.get(0).dist(sel.targetWorld)
            : 0.0;
         f.put("route_dist", PfTestRunner.round2(routeLength(plan.waypoints)));
         f.put("direct_dist", PfTestRunner.round2(direct));
      }

      if (mv != null) {
         if (mv.cancelledDetail != null) {
            f.put("walk_outcome", "CANCELLED");
            f.put("detail", mv.cancelledDetail);
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
      MoveToAutoObstacleCorridorScenario.Navigation LIVE = (gui, plan, target, bot, run) -> MoveToAutoObstacleCorridorScenario.liveMove(
            gui, plan, target, bot
         );

      MoveToAutoOpenGroundScenario.MoveResult run(GameUI var1, PrototypePathfinder.Plan var2, Coord2d var3, Bot var4, PfTestRunner.Run var5) throws Exception;
   }
}
