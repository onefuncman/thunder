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

final class MoveToAutoKnownLongLegScenario implements PfTestRunner.Scenario {
   static final int MOVE_MAX_LEGS = 40;
   static final int MOVE_MAX_REPLANS = 5;
   static final long MOVE_WAYPOINT_TIMEOUT_MS = 90000L;
   static final long MOVE_LEG_BUDGET_MS = 120000L;
   static final long MOVE_TOTAL_TIMEOUT_MS = 240000L;
   static final double ARRIVAL_EPS = 3.0;
   static final RecedingHorizonNavigator.Bounds NAV_BOUNDS = new RecedingHorizonNavigator.Bounds(40, 5, 1000000);
   private final MoveToAutoKnownLongLegScenario.Navigation nav;

   MoveToAutoKnownLongLegScenario() {
      this.nav = MoveToAutoKnownLongLegScenario.Navigation.LIVE;
   }

   MoveToAutoKnownLongLegScenario(MoveToAutoKnownLongLegScenario.Navigation nav) {
      this.nav = nav;
   }

   @Override
   public String name() {
      return "move_to_auto_known_long_leg";
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
            NamedPlaceNavigator.Location start;
            MapFile file;
            synchronized (ui) {
               file = gui.mapfile == null ? null : gui.mapfile.file;
               start = NamedPlaceNavigator.liveState(gui).current();
            }

            if (file != null && start != null) {
               checks.add(PfTestRunner.check("session_state", true, String.format("segment %x, start tile %s", start.seg, start.tile)));
               if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  Area bounds = selectBounds(start.tile);
                  MapFileTileSource src = MapFileTileSource.of(file, start.seg, bounds);
                  NavigationTestSpotSelector.Selection sel = NavigationTestSpotSelector.knownMapLongLeg(
                     src, start.seg, start.tile.sub(bounds.ul), 12.0, 24.0, 40000, 2000000
                  );
                  checks.add(NavigationTestSpotScenario.selectionCheck(sel));
                  if (sel.refused()) {
                     return PfTestHarness.body(checks, "no movement performed: " + sel.refusal, factsJson(null, sel, bounds, "SELECTION_REFUSED", null));
                  } else if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  } else {
                     Coord goalTile = bounds.ul.add(sel.targetTile);
                     boolean freshPresent = false;
                     boolean freshIdle = false;
                     NamedPlaceNavigator.Location fresh;
                     synchronized (ui) {
                        Gob me = gui.map.player();
                        freshPresent = me != null && me.rc != null;
                        freshIdle = me == null || me.getattr(Moving.class) == null;
                        fresh = NamedPlaceNavigator.liveState(gui).current();
                     }

                     CoarseRoutePlanner.Route freshRoute = null;
                     if (fresh != null) {
                        freshRoute = CoarseTileNavigator.planRoute(file, fresh.seg, fresh.tile, goalTile, bounds, NAV_BOUNDS.coarseExpanded);
                     }

                     JSONObject reval = goalRevalidationCheck(fresh, sel.segment, freshPresent, freshIdle, freshRoute);
                     checks.add(reval);
                     if ("fail".equals(reval.getString("status"))) {
                        return PfTestHarness.body(
                           checks, "no movement performed: " + reval.getString("detail"), factsJson(null, sel, bounds, "REVALIDATION_REFUSED", null)
                        );
                     } else if (run.cancelled) {
                        throw new PfTestRunner.Cancelled();
                     } else {
                        Bot bot = Bot.execute(new BotAction[0]);
                        AtomicBoolean timedOut = new AtomicBoolean(false);
                        CoarseTileNavigator.Run navRun = PfTestHarness.moveWatch(run, timedOut, 240000L, () -> this.nav.run(gui, goalTile, bounds, bot, run));
                        if (run.cancelled) {
                           throw new PfTestRunner.Cancelled(cancelledBody(navRun, sel, bounds, timedOut.get()));
                        } else {
                           return completedBody(navRun, sel, bounds, timedOut.get(), finalArrival(gui, ui, navRun, goalTile));
                        }
                     }
                  }
               }
            } else {
               checks.add(PfTestRunner.check("session_state", false, "map file or session location unavailable (no-move)"));
               return PfTestHarness.body(
                  checks, "no movement performed: session state unavailable", factsJson(null, null, null, "NOT_STARTED", "session state unavailable")
               );
            }
         }
      }
   }

   static CoarseTileNavigator.Run liveNavigation(GameUI gui, Coord goalTile, Area bounds, Bot bot) {
      WaypointWalker.Params params = new WaypointWalker.Params(2.475, 0.6875, 800L, 90000L, 3000L);
      CoarseTileNavigator nav = CoarseTileNavigator.live(gui, gui.mapfile.file, bot, NAV_BOUNDS, 120000L, params, NamedPlaceNavigator.NOOP);
      return nav.navigate(goalTile, bounds);
   }

   private static JSONObject finalArrival(GameUI gui, UI ui, CoarseTileNavigator.Run navRun, Coord goalTile) {
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

      Coord2d goalWorld = null;
      if (navRun.start != null && goalTile != null) {
         goalWorld = NamedPlaceNavigator.worldPos(goalTile, NamedPlaceNavigator.translation(navRun.start));
      }

      return arrivalCheck(navRun.reached(), present, idle, at, goalWorld);
   }

   static List<JSONObject> preflightChecks(boolean inGame, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy) {
      return PfTestHarness.autoMovePreflightChecks("move_to_auto_known_long_leg", inGame, playerPresent, mapfileAvailable, playerIdle, botBusy);
   }

   static Area selectBounds(Coord startTile) {
      return NavigationTestSpotScenario.selectBounds(startTile);
   }

   static JSONObject goalRevalidationCheck(
      NamedPlaceNavigator.Location fresh, long selectedSeg, boolean playerPresent, boolean playerIdle, CoarseRoutePlanner.Route freshRoute
   ) {
      if (fresh == null) {
         return PfTestRunner.check("goal_revalidated", false, "session location unavailable at revalidation (no-move)");
      } else if (fresh.seg != selectedSeg) {
         return PfTestRunner.check(
            "goal_revalidated", false, String.format("player is in segment %x but the frozen goal is in segment %x (no-move)", fresh.seg, selectedSeg)
         );
      } else if (!playerPresent) {
         return PfTestRunner.check("goal_revalidated", false, "player gob not observable at revalidation (no-move)");
      } else if (!playerIdle) {
         return PfTestRunner.check("goal_revalidated", false, "player is moving at revalidation; refusing to move (no-move)");
      } else {
         return freshRoute != null && freshRoute.reached()
            ? PfTestRunner.check(
               "goal_revalidated",
               true,
               String.format(
                  "same segment %x, player idle; fresh coarse route REACHED (%d waypoints, %d expanded)",
                  fresh.seg,
                  freshRoute.waypoints.size(),
                  freshRoute.expanded
               )
            )
            : PfTestRunner.check(
               "goal_revalidated",
               false,
               "fresh coarse route to the frozen goal is not REACHED (" + (freshRoute == null ? "no plan" : freshRoute.status) + "); refusing (no-move)"
            );
      }
   }

   static List<JSONObject> outcomeChecks(CoarseTileNavigator.Run navRun, boolean timedOut) {
      List<JSONObject> checks = new ArrayList<>();
      switch (navRun.status) {
         case UNAVAILABLE:
            checks.add(PfTestRunner.check("session_state", false, "session location unavailable (no map file/segment/player)"));
            return checks;
         case ROUTE_REJECTED:
            checks.add(PfTestRunner.check("coarse_route", false, routeRefusal(navRun)));
            return checks;
         case NAVIGATED:
            if (timedOut) {
               checks.add(PfTestRunner.check("move_timeout", false, "hard wall-clock deadline exceeded (240000ms); navigation interrupted"));
            }

            switch (navRun.navOutcome) {
               case REACHED_DESTINATION:
                  checks.add(
                     PfTestRunner.check(
                        "navigation_completed",
                        true,
                        "verified arrival at the goal tile " + navRun.goalTile + " (legs=" + navRun.legs + ", replans=" + navRun.replans + ")"
                     )
                  );
                  break;
               case CANCELLED:
                  checks.add(PfTestRunner.check("navigation_completed", false, "cancelled: " + (navRun.detail == null ? "bot cancelled" : navRun.detail)));
                  break;
               case TERMINAL_FAILURE:
                  checks.add(PfTestRunner.check("navigation_completed", false, "walker failure: " + (navRun.detail == null ? "unknown" : navRun.detail)));
                  break;
               case LEG_LIMIT_EXHAUSTED:
                  checks.add(PfTestRunner.check("navigation_completed", false, "leg limit exhausted (40 legs)"));
                  break;
               case REPLAN_LIMIT_EXHAUSTED:
                  checks.add(PfTestRunner.check("navigation_completed", false, "replan limit exhausted (5 replans)"));
                  break;
               case COARSE_PLAN_FAILED:
                  checks.add(PfTestRunner.check("navigation_completed", false, "coarse plan failed: " + (navRun.detail == null ? "unknown" : navRun.detail)));
                  break;
               case COARSE_PLAN_INVALID:
                  checks.add(
                     PfTestRunner.check("navigation_completed", false, "coarse plan invalid: " + (navRun.detail == null ? "unknown" : navRun.detail))
                  );
                  break;
               case STUCK:
                  checks.add(PfTestRunner.check("navigation_completed", false, "stuck: " + (navRun.detail == null ? "recovery exhausted" : navRun.detail)));
                  break;
               default:
                  throw new AssertionError(navRun.navOutcome);
            }

            return checks;
         default:
            throw new AssertionError(navRun.status);
      }
   }

   private static String routeRefusal(CoarseTileNavigator.Run navRun) {
      if (navRun.routeStatus == null) {
         return "route refused (unknown status)";
      } else {
         switch (navRun.routeStatus) {
            case INVALID_START:
               return "start tile outside the planning bounds or unknown/blocked (INVALID_START)";
            case INVALID_GOAL:
               return "goal tile outside the planning bounds or unknown/blocked (INVALID_GOAL)";
            case NO_KNOWN_ROUTE:
               return "no known coarse route to the goal tile "
                  + navRun.goalTile
                  + " (NO_KNOWN_ROUTE"
                  + (navRun.routeCause == null ? "" : ":" + navRun.routeCause)
                  + ")";
            case EXHAUSTED:
               return "route search exhausted its expansion budget (EXHAUSTED)";
            default:
               return "route refused (" + navRun.routeStatus + ")";
         }
      }
   }

   static JSONObject arrivalCheck(boolean navigationVerified, boolean playerPresent, boolean playerIdle, Coord2d at, Coord2d goalWorld) {
      if (!navigationVerified) {
         return PfTestRunner.check("arrived_idle_at_goal", false, "navigation did not verify arrival at the goal tile");
      } else if (!playerPresent) {
         return PfTestRunner.check("arrived_idle_at_goal", false, "player gob not observable at the final check");
      } else if (!playerIdle) {
         return PfTestRunner.check("arrived_idle_at_goal", false, "player is still moving after navigation (server-confirmed idle arrival required)");
      } else if (at != null && goalWorld != null) {
         double d = at.dist(goalWorld);
         return PfTestRunner.check(
            "arrived_idle_at_goal", d <= 3.0, String.format("idle at (%.1f, %.1f), %.2f units from the goal tile center (eps %.2f)", at.x, at.y, d, 3.0)
         );
      } else {
         return PfTestRunner.check("arrived_idle_at_goal", false, "final position or goal position unavailable");
      }
   }

   static JSONObject cancelledBody(CoarseTileNavigator.Run navRun, NavigationTestSpotSelector.Selection sel, Area bounds, boolean timedOut) {
      List<JSONObject> checks = outcomeChecks(navRun, timedOut);
      checks.add(PfTestRunner.check("run_cancelled", false, "the run was cancelled (POST /pf/cancel); terminal navigation facts above are partial"));
      return PfTestHarness.body(
         checks,
         "cancelled: " + (navRun.detail == null ? "run cancelled" : navRun.detail),
         factsJson(navRun, sel, bounds, navRun.status == CoarseTileNavigator.RunStatus.NAVIGATED ? "NAVIGATED" : navRun.status.name(), null)
      );
   }

   static JSONObject completedBody(
      CoarseTileNavigator.Run navRun, NavigationTestSpotSelector.Selection sel, Area bounds, boolean timedOut, JSONObject arrival
   ) {
      List<JSONObject> checks = outcomeChecks(navRun, timedOut);
      if (arrival != null) {
         checks.add(arrival);
      }

      String note = null;
      if (timedOut) {
         note = "hard wall-clock deadline exceeded (240000ms); navigation interrupted";
      } else if (navRun.cancelled()) {
         note = "navigation cancelled (detail: " + navRun.detail + ")";
      } else if (!navRun.reached()) {
         note = "navigation did not verify arrival at the goal tile";
      }

      return PfTestHarness.body(
         checks, note, factsJson(navRun, sel, bounds, navRun.status == CoarseTileNavigator.RunStatus.NAVIGATED ? "NAVIGATED" : navRun.status.name(), null)
      );
   }

   static JSONObject factsJson(CoarseTileNavigator.Run navRun, NavigationTestSpotSelector.Selection sel, Area bounds, String status, String reason) {
      JSONObject f = new JSONObject();
      f.put("moved", navRun != null && navRun.status == CoarseTileNavigator.RunStatus.NAVIGATED);
      f.put("arrived", navRun != null && navRun.reached());
      f.put("profile", NavigationTestSpotSelector.Profile.KNOWN_MAP_LONG_LEG.name());
      f.put("status", status == null ? "NOT_STARTED" : status);
      if (sel != null) {
         f.put("selected", sel.selected());
         f.put("refusal", sel.refused() ? sel.refusal.name() : JSONObject.NULL);
         f.put("reason", sel.evidence);
         f.put("candidates", sel.candidates.size());
         if (!sel.candidates.isEmpty()) {
            f.put("best_score", PfTestRunner.round2(sel.candidates.get(0).score));
         }
      } else {
         f.put("selected", false);
         f.put("refusal", "PREFLIGHT");
         f.put("reason", reason == null ? "preflight refused" : reason);
         f.put("candidates", 0);
      }

      if (navRun != null) {
         f.put("goal_tile", new JSONArray().put(navRun.goalTile.x).put(navRun.goalTile.y));
         if (navRun.start != null) {
            f.put("segment", String.format("%x", navRun.start.seg));
         }
      } else if (sel != null && sel.targetTile != null && bounds != null) {
         f.put("goal_tile", new JSONArray().put(bounds.ul.x + sel.targetTile.x).put(bounds.ul.y + sel.targetTile.y));
         f.put("segment", String.format("%x", sel.segment));
      }

      if (navRun != null) {
         if (navRun.start != null) {
            f.put("start_tile", new JSONArray().put(navRun.start.tile.x).put(navRun.start.tile.y));
         }

         if (navRun.routeStatus != null) {
            f.put("route_kind", navRun.routeStatus.name());
         }

         if (navRun.routeStatus == CoarseRoutePlanner.Status.REACHED) {
            f.put("waypoint_count", navRun.waypointCount);
         }

         f.put("expanded", navRun.expanded);
         if (navRun.navOutcome != null) {
            f.put("nav_outcome", navRun.navOutcome.name());
            f.put("legs", navRun.legs);
            f.put("replans", navRun.replans);
            f.put("route_index", navRun.routeIndex);
            if (navRun.detail != null) {
               f.put("detail", navRun.detail);
            }

            if (navRun.endPos != null) {
               f.put("end_pos", new JSONArray().put(PfTestRunner.round2(navRun.endPos.x)).put(PfTestRunner.round2(navRun.endPos.y)));
            }
         }

         f.put("elapsed_ms", navRun.elapsedMs);
         JSONArray replans = new JSONArray();

         for (CoarseTileNavigator.ReplanFact r : navRun.replanFacts) {
            JSONObject rf = new JSONObject();
            rf.put("index", r.index);
            rf.put("status", r.status.name());
            if (r.cause != null) {
               rf.put("cause", r.cause.name());
            }

            rf.put("expanded", r.expanded);
            replans.put(rf);
         }

         f.put("replan_facts", replans);
      }

      return f;
   }

   interface Navigation {
      MoveToAutoKnownLongLegScenario.Navigation LIVE = (gui, goalTile, bounds, bot, run) -> MoveToAutoKnownLongLegScenario.liveNavigation(
            gui, goalTile, bounds, bot
         );

      CoarseTileNavigator.Run run(GameUI var1, Coord var2, Area var3, Bot var4, PfTestRunner.Run var5) throws Exception;
   }
}
