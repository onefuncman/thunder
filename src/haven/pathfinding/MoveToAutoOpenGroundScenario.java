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

final class MoveToAutoOpenGroundScenario implements PfTestRunner.Scenario {
   static final long MOVE_WAYPOINT_TIMEOUT_MS = 60000L;
   static final long MOVE_WALK_BUDGET_MS = 60000L;
   static final long MOVE_TOTAL_TIMEOUT_MS = 120000L;
   static final double MAX_TARGET_DIST = 44.0;
   static final double ARRIVAL_EPS = 3.0;
   private final MoveToAutoOpenGroundScenario.Navigation nav;

   MoveToAutoOpenGroundScenario() {
      this.nav = MoveToAutoOpenGroundScenario.Navigation.LIVE;
   }

   MoveToAutoOpenGroundScenario(MoveToAutoOpenGroundScenario.Navigation nav) {
      this.nav = nav;
   }

   @Override
   public String name() {
      return "move_to_auto_open_ground";
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

            NavigationTestSpotSelector.Selection sel = NavigationTestSpotSelector.openGround(scene);
            checks.add(NavigationTestSpotScenario.selectionCheck(sel));
            if (sel.refused()) {
               return PfTestHarness.body(checks, "no movement performed: " + sel.refusal, factsJson(sel, null, "SELECTION_REFUSED", null));
            } else if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            } else {
               Coord2d target = sel.targetWorld;
               PrototypePathfinder.Scene fresh;
               synchronized (ui) {
                  fresh = PrototypePathfinder.observe(gui);
               }

               JSONObject reval = revalidationCheck(fresh, target, 44.0);
               checks.add(reval);
               if ("fail".equals(reval.getString("status"))) {
                  return PfTestHarness.body(checks, "no movement performed: " + reval.getString("detail"), factsJson(sel, null, "REVALIDATION_REFUSED", null));
               } else if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  Bot bot = Bot.execute(new BotAction[0]);
                  AtomicBoolean timedOut = new AtomicBoolean(false);
                  MoveToAutoOpenGroundScenario.MoveResult mv = PfTestHarness.moveWatch(
                     run, timedOut, 120000L, () -> this.nav.run(gui, target, bot, run)
                  );
                  if (run.cancelled) {
                     throw new PfTestRunner.Cancelled(cancelledBody(sel, mv));
                  } else {
                     return completedBody(sel, mv, timedOut.get(), finalArrival(gui, ui, mv, target));
                  }
               }
            }
         }
      }
   }

   static MoveToAutoOpenGroundScenario.MoveResult liveMove(GameUI gui, Coord2d target, Bot bot) {
      PrototypePathfinder.Plan plan = PrototypePathfinder.planAny(gui, Collections.singletonList(target), true);
      return walkPlan(gui, plan, target, bot, 60000L, 60000L);
   }

   /**
    * Follow a stand even when the first local plan is PARTIAL/CLIPPED/SNAPPED
    * or a hop walks into a pinch. Crowded yards need a detour plus a replan;
    * aborting on the first walker STUCK was stopping Walk reverse next to crates.
    */
   static String followTo(GameUI gui, Coord2d dest, Bot bot, long totalBudgetMs) throws InterruptedException {
      long deadline = System.currentTimeMillis() + totalBudgetMs;
      int hops = 0;
      double best = Double.POSITIVE_INFINITY;
      int stalled = 0;
      while (System.currentTimeMillis() < deadline && hops < 24) {
         Coord2d at = PfTestHarness.observePos(gui);
         if (at != null && dest != null && at.dist(dest) <= 3.0) {
            return null;
         }
         PrototypePathfinder.Plan plan = PrototypePathfinder.planAny(gui, Collections.singletonList(dest), true);
         if (plan == null || plan.waypoints == null || plan.waypoints.size() < 2 || plan.status == PrototypePathfinder.Plan.Status.FAILED) {
            Thread.sleep(200L);
            plan = PrototypePathfinder.planAny(gui, Collections.singletonList(dest), true);
         }
         if (plan == null || plan.waypoints == null || plan.waypoints.size() < 2 || plan.status == PrototypePathfinder.Plan.Status.FAILED) {
            return "no route around obstacles" + (plan == null ? "" : " (" + plan.status + ")");
         }
         long hopBudget = Math.min(25000L, deadline - System.currentTimeMillis());
         if (hopBudget < 1000L) {
            break;
         }
         MoveToAutoOpenGroundScenario.MoveResult mv = walkPlan(gui, plan, dest, bot, hopBudget, hopBudget);
         hops++;
         Coord2d now = PfTestHarness.observePos(gui);
         if (closeEnough(plan, now, dest)) {
            return null;
         }
         if (retryAfterWalk(mv == null ? null : mv.walk)) {
            Thread.sleep(200L);
         }
         double d = now == null || dest == null ? best : now.dist(dest);
         if (d < best - 2.0) {
            best = d;
            stalled = 0;
         } else {
            stalled++;
            if (stalled >= 3) {
               return "stuck: no progress around obstacles";
            }
         }
      }
      Coord2d at = PfTestHarness.observePos(gui);
      if (at != null && dest != null && at.dist(dest) <= 3.0) {
         return null;
      }
      return "did not reach stand";
   }

   /** One jammed polyline is a replan, not a route abort. */
   static boolean retryAfterWalk(WaypointWalker.Result walk) {
      return walk == WaypointWalker.Result.STUCK
         || walk == WaypointWalker.Result.TIMEOUT
         || walk == WaypointWalker.Result.SHORT_STOP;
   }

   /**
    * A stand behind a desk is often occupancy-SNAPPED to the nearest free cell.
    * Arriving there (within a tile and a half) is reaching the stand, not a stall.
    */
   static boolean closeEnough(PrototypePathfinder.Plan plan, Coord2d now, Coord2d dest) {
      if (now == null || dest == null) {
         return false;
      }
      if (now.dist(dest) <= 8.0) {
         return true;
      }
      if (plan == null || plan.status != PrototypePathfinder.Plan.Status.SNAPPED || plan.waypoints == null || plan.waypoints.isEmpty()) {
         return false;
      }
      Coord2d end = plan.waypoints.get(plan.waypoints.size() - 1);
      return end != null && now.dist(end) <= 3.0 && now.dist(dest) <= 16.5;
   }

   static MoveToAutoOpenGroundScenario.MoveResult walkPlan(
      GameUI gui, PrototypePathfinder.Plan plan, Coord2d fallbackPos, Bot bot, long waypointTimeoutMs, long walkBudgetMs
   ) {
      long t0 = System.currentTimeMillis();
      Coord2d before = PfTestHarness.observePos(gui);
      if (plan != null && plan.waypoints.size() >= 2) {
         WaypointWalker.Params params = new WaypointWalker.Params(2.475, 0.6875, 800L, waypointTimeoutMs, 3000L);

         try {
            WaypointWalker.Result r;
            List<GatePassage.Crossing> crossings = GatePassage.observedCrossings(gui, plan.waypoints);
            if (crossings.isEmpty()) {
               r = WaypointWalker.execute(
                  WaypointWalker.liveEnv(gui), bot, plan.waypoints, 0, walkBudgetMs, params, NamedPlaceNavigator.NOOP,
                  haven.nav.NavPlanStatus.valueOf(plan.status.name()), fallbackPos
               );
            } else {
               // The route crosses a pass-through gate: walk with open/pass/close.
               r = GatePassage.resultFor(GatePassage.walk(gui, bot, plan.waypoints, walkBudgetMs, NamedPlaceNavigator.NOOP));
            }
            Coord2d after = PfTestHarness.observePos(gui);
            return new MoveToAutoOpenGroundScenario.MoveResult(
               plan.status,
               plan.expanded,
               plan.obstacles,
               r,
               null,
               after != null ? after : (before != null ? before : fallbackPos),
               PfTestHarness.elapsed(t0)
            );
         } catch (InterruptedException var15) {
            Coord2d afterx = PfTestHarness.observePos(gui);
            String detail = var15.getMessage() != null && !var15.getMessage().isEmpty() ? var15.getMessage() : "bot cancelled";
            return new MoveToAutoOpenGroundScenario.MoveResult(
               plan.status,
               plan.expanded,
               plan.obstacles,
               null,
               detail,
               afterx != null ? afterx : (before != null ? before : fallbackPos),
               PfTestHarness.elapsed(t0)
            );
         }
      } else {
         PrototypePathfinder.Plan.Status st = plan == null ? PrototypePathfinder.Plan.Status.FAILED : plan.status;
         int expanded = plan == null ? 0 : plan.expanded;
         int obstacles = plan == null ? 0 : plan.obstacles;
         return new MoveToAutoOpenGroundScenario.MoveResult(
            st, expanded, obstacles, null, null, before != null ? before : fallbackPos, PfTestHarness.elapsed(t0)
         );
      }
   }

   static List<JSONObject> preflightChecks(boolean inGame, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy) {
      return PfTestHarness.autoMovePreflightChecks("move_to_auto_open_ground", inGame, playerPresent, mapfileAvailable, playerIdle, botBusy);
   }

   static JSONObject revalidationCheck(PrototypePathfinder.Scene fresh, Coord2d target, double maxDist) {
      if (fresh == null || fresh.player == null) {
         return PfTestRunner.check("target_revalidated", false, "player gob not observable at revalidation (no-move)");
      } else if (fresh.moving) {
         return PfTestRunner.check("target_revalidated", false, "player is moving at revalidation; refusing to move (no-move)");
      } else if (target == null) {
         return PfTestRunner.check("target_revalidated", false, "no selected target to revalidate (no-move)");
      } else {
         double d = fresh.player.dist(target);
         if (d > maxDist) {
            return PfTestRunner.check(
               "target_revalidated", false, String.format("selected target %.2fu from the live player exceeds the hard bound %.2fu (no-move)", d, maxDist)
            );
         } else {
            return !fresh.bodyFree(target)
               ? PfTestRunner.check("target_revalidated", false, "selected target is occupancy-blocked in the fresh observation (no-move)")
               : PfTestRunner.check("target_revalidated", true, String.format("target body-free, %.2fu from the idle player (bound %.2fu)", d, maxDist));
         }
      }
   }

   static JSONObject routeCheck(MoveToAutoOpenGroundScenario.MoveResult mv) {
      if (mv == null) {
         return PfTestRunner.check("route_planned", false, "no movement attempt (no-move)");
      } else {
         switch (mv.planStatus) {
            case REACHED:
               return PfTestRunner.check("route_planned", true, "plan REACHED (" + mv.expanded + " expanded, " + mv.obstacles + " obstacles)");
            case CLIPPED:
               return PfTestRunner.check("route_planned", false, "plan CLIPPED: selected target beyond the local planning horizon (no-move)");
            case SNAPPED:
               return PfTestRunner.check("route_planned", false, "plan SNAPPED: selected target became blocked at plan time (no-move)");
            case PARTIAL:
               return PfTestRunner.check("route_planned", false, "plan PARTIAL: best-effort route only (no-move)");
            case FAILED:
               return PfTestRunner.check("route_planned", false, "plan FAILED: no route to the selected target (no-move)");
            default:
               throw new AssertionError(mv.planStatus);
         }
      }
   }

   static JSONObject walkCheck(MoveToAutoOpenGroundScenario.MoveResult mv) {
      if (mv == null) {
         return PfTestRunner.check("walk_completed", false, "no walk was executed (no-move)");
      } else if (mv.cancelledDetail != null) {
         return PfTestRunner.check("walk_completed", false, "walk cancelled: " + mv.cancelledDetail);
      } else if (mv.walk == null) {
         return PfTestRunner.check("walk_completed", false, "no walk was executed (no-move)");
      } else {
         switch (mv.walk) {
            case ARRIVED:
               return PfTestRunner.check("walk_completed", true, "walker verified arrival at every waypoint (" + mv.elapsedMs + "ms)");
            case REJECTED:
               return PfTestRunner.check("walk_completed", false, "walker REJECTED: click never accepted / vehicle state changed");
            case SHORT_STOP:
               return PfTestRunner.check("walk_completed", false, "walker SHORT_STOP: stopped short of a waypoint");
            case STUCK:
               return PfTestRunner.check("walk_completed", false, "walker STUCK: recovery exhausted");
            case TIMEOUT:
               return PfTestRunner.check("walk_completed", false, "walker TIMEOUT: walk budget exhausted (60000ms)");
            default:
               throw new AssertionError(mv.walk);
         }
      }
   }

   static JSONObject finalArrival(GameUI gui, UI ui, MoveToAutoOpenGroundScenario.MoveResult mv, Coord2d target) {
      boolean present = false;
      boolean idle = false;
      Coord2d at = null;
      if (gui != null && gui.map != null && ui != null) {
         synchronized (ui) {
            Gob me = gui.map.player();
            present = me != null && me.rc != null;
            idle = me == null || me.getattr(Moving.class) == null;
            at = present ? me.rc : null;
         }
      }

      boolean walkVerified = mv != null && mv.walk == WaypointWalker.Result.ARRIVED;
      return arrivalCheck(walkVerified, present, idle, at, target);
   }

   static JSONObject arrivalCheck(boolean walkVerified, boolean playerPresent, boolean playerIdle, Coord2d at, Coord2d target) {
      if (!walkVerified) {
         return PfTestRunner.check("arrived_idle_at_target", false, "the walk did not verify arrival at the selected target");
      } else if (!playerPresent) {
         return PfTestRunner.check("arrived_idle_at_target", false, "player gob not observable at the final check");
      } else if (!playerIdle) {
         return PfTestRunner.check("arrived_idle_at_target", false, "player is still moving after the walk (server-confirmed idle arrival required)");
      } else if (at != null && target != null) {
         double d = at.dist(target);
         return PfTestRunner.check(
            "arrived_idle_at_target", d <= 3.0, String.format("idle at (%.1f, %.1f), %.2f units from the selected target (eps %.2f)", at.x, at.y, d, 3.0)
         );
      } else {
         return PfTestRunner.check("arrived_idle_at_target", false, "final position or target position unavailable");
      }
   }

   static JSONObject cancelledBody(NavigationTestSpotSelector.Selection sel, MoveToAutoOpenGroundScenario.MoveResult mv) {
      List<JSONObject> checks = new ArrayList<>();
      checks.add(routeCheck(mv));
      checks.add(walkCheck(mv));
      checks.add(PfTestRunner.check("run_cancelled", false, "the run was cancelled (POST /pf/cancel); terminal movement facts above are partial"));
      return PfTestHarness.body(
         checks, "cancelled: " + (mv != null && mv.cancelledDetail != null ? mv.cancelledDetail : "run cancelled"), factsJson(sel, mv, "WALKED", null)
      );
   }

   static JSONObject completedBody(
      NavigationTestSpotSelector.Selection sel, MoveToAutoOpenGroundScenario.MoveResult mv, boolean timedOut, JSONObject arrival
   ) {
      List<JSONObject> checks = new ArrayList<>();
      if (timedOut) {
         checks.add(PfTestRunner.check("move_timeout", false, "hard wall-clock deadline exceeded (120000ms); movement interrupted"));
      }

      checks.add(routeCheck(mv));
      checks.add(walkCheck(mv));
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
      return PfTestHarness.body(checks, note, factsJson(sel, mv, walked ? "WALKED" : "ROUTE_REFUSED", null));
   }

   static JSONObject factsJson(
      NavigationTestSpotSelector.Selection sel, MoveToAutoOpenGroundScenario.MoveResult mv, String status, String reason
   ) {
      JSONObject f = new JSONObject();
      f.put("moved", mv != null && (mv.walk != null || mv.cancelledDetail != null));
      f.put("arrived", mv != null && mv.walk == WaypointWalker.Result.ARRIVED);
      f.put("profile", NavigationTestSpotSelector.Profile.OPEN_GROUND.name());
      f.put("status", status == null ? "NOT_STARTED" : status);
      if (sel != null) {
         f.put("selected", sel.selected());
         f.put("refusal", sel.refused() ? sel.refusal.name() : JSONObject.NULL);
         f.put("reason", sel.evidence);
         f.put("candidates", sel.candidates.size());
         if (!sel.candidates.isEmpty()) {
            f.put("best_score", PfTestRunner.round2(sel.candidates.get(0).score));
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

      if (mv != null) {
         f.put("plan_status", mv.planStatus.name());
         f.put("expanded", mv.expanded);
         f.put("obstacles", mv.obstacles);
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

   static final class MoveResult {
      final PrototypePathfinder.Plan.Status planStatus;
      final int expanded;
      final int obstacles;
      final WaypointWalker.Result walk;
      final String cancelledDetail;
      final Coord2d endPos;
      final long elapsedMs;

      MoveResult(
         PrototypePathfinder.Plan.Status planStatus,
         int expanded,
         int obstacles,
         WaypointWalker.Result walk,
         String cancelledDetail,
         Coord2d endPos,
         long elapsedMs
      ) {
         this.planStatus = planStatus;
         this.expanded = expanded;
         this.obstacles = obstacles;
         this.walk = walk;
         this.cancelledDetail = cancelledDetail;
         this.endPos = endPos;
         this.elapsedMs = elapsedMs;
      }
   }

   interface Navigation {
      MoveToAutoOpenGroundScenario.Navigation LIVE = (gui, target, bot, run) -> MoveToAutoOpenGroundScenario.liveMove(
            gui, target, bot
         );

      MoveToAutoOpenGroundScenario.MoveResult run(GameUI var1, Coord2d var2, Bot var3, PfTestRunner.Run var4) throws Exception;
   }
}
