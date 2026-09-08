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

final class MoveToMarkerScenario implements PfTestRunner.Scenario {
   static final int MOVE_MAX_LEGS = 40;
   static final int MOVE_MAX_REPLANS = 5;
   static final long MOVE_WAYPOINT_TIMEOUT_MS = 90000L;
   static final long MOVE_LEG_BUDGET_MS = 120000L;
   static final long MOVE_TOTAL_TIMEOUT_MS = 240000L;
   static final int MOVE_BOUNDS_HALF = 256;
   static final double ARRIVAL_EPS = 3.0;
   static final RecedingHorizonNavigator.Bounds NAV_BOUNDS = new RecedingHorizonNavigator.Bounds(40, 5, 1000000);
   private final MoveToMarkerScenario.Navigation nav;

   MoveToMarkerScenario() {
      this.nav = MoveToMarkerScenario.Navigation.LIVE;
   }

   MoveToMarkerScenario(MoveToMarkerScenario.Navigation nav) {
      this.nav = nav;
   }

   @Override
   public String name() {
      return "move_to_marker";
   }

   @Override
   public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
      if (run.cancelled) {
         throw new PfTestRunner.Cancelled();
      } else {
         GameUI gui = ui == null ? null : ui.gui;
         String marker = markerName();
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

         List<JSONObject> checks = preflightChecks(inGame, marker, playerPresent, mapfileAvailable, playerIdle, Bot.hasCurrent());
         if (PfTestRunner.verdictOf(checks).equals("FAIL")) {
            return PfTestHarness.body(checks, "no movement performed: " + PfTestHarness.firstFailDetail(checks), factsJson(null, marker, null, null, null));
         } else if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         } else {
            NamedPlaceNavigator.Location start = NamedPlaceNavigator.liveState(gui).current();
            if (start == null) {
               checks.add(PfTestRunner.check("session_state", false, "session location unavailable (no map file/segment/player)"));
               return PfTestHarness.body(checks, "no movement performed: session location unavailable", factsJson(null, marker, null, null, null));
            } else {
               Area bounds = moveBounds(start.tile);
               Bot bot = Bot.execute(new BotAction[0]);
               AtomicBoolean timedOut = new AtomicBoolean(false);
               NamedPlaceNavigator.Run navRun = PfTestHarness.moveWatch(run, timedOut, 240000L, () -> this.nav.run(gui, marker, bounds, bot, run));
               Place place = navRun.place;
               Long goalSeg = place == null ? null : place.seg;
               Coord goalTile = place == null ? null : place.tc;
               if (run.cancelled) {
                  throw new PfTestRunner.Cancelled(cancelledBody(navRun, marker, bounds, goalSeg, goalTile));
               } else {
                  return completedBody(navRun, marker, bounds, goalSeg, goalTile, timedOut.get(), finalArrival(gui, ui, navRun, goalTile));
               }
            }
         }
      }
   }

   static NamedPlaceNavigator.Run liveNavigation(GameUI gui, String marker, Area bounds, Bot bot) {
      WaypointWalker.Params params = new WaypointWalker.Params(2.475, 0.6875, 800L, 90000L, 3000L);
      NamedPlaceNavigator nav = NamedPlaceNavigator.live(gui, gui.mapfile.file, bot, NAV_BOUNDS, 120000L, params, NamedPlaceNavigator.NOOP);
      return nav.navigate(marker, bounds);
   }

   private static JSONObject finalArrival(GameUI gui, UI ui, NamedPlaceNavigator.Run navRun, Coord goalTile) {
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

      Coord2d markerWorld = null;
      if (navRun.start != null && goalTile != null) {
         markerWorld = NamedPlaceNavigator.worldPos(goalTile, NamedPlaceNavigator.translation(navRun.start));
      }

      return arrivalCheck(navRun.reached(), present, idle, at, markerWorld);
   }

   static String markerName() {
      String v = System.getProperty("haven.pf.move.marker");
      return v == null ? "" : v.trim();
   }

   static List<JSONObject> preflightChecks(
      boolean inGame, String marker, boolean playerPresent, boolean mapfileAvailable, boolean playerIdle, boolean botBusy
   ) {
      List<JSONObject> checks = new ArrayList<>();
      if (!inGame) {
         checks.add(PfTestRunner.check("in_game", false, "client is not in game (move_to_marker requires in-game state)"));
         return checks;
      } else {
         checks.add(PfTestRunner.check("in_game", true, "in game"));
         if (marker != null && !marker.trim().isEmpty()) {
            checks.add(PfTestRunner.check("marker_configured", true, "marker \"" + marker.trim() + "\" (from launch-time haven.pf.move.marker)"));
            if (!playerPresent) {
               checks.add(PfTestRunner.check("player_present", false, "no player gob in game"));
               return checks;
            } else {
               checks.add(PfTestRunner.check("player_present", true, "player gob present"));
               if (!mapfileAvailable) {
                  checks.add(PfTestRunner.check("mapfile_available", false, "no map file in game state (named places unavailable)"));
                  return checks;
               } else {
                  checks.add(PfTestRunner.check("mapfile_available", true, "map file present"));
                  if (!playerIdle) {
                     checks.add(PfTestRunner.check("player_idle", false, "player is moving at scenario start; refusing to move (no-move)"));
                     return checks;
                  } else {
                     checks.add(PfTestRunner.check("player_idle", true, "player is idle"));
                     if (botBusy) {
                        checks.add(PfTestRunner.check("bot_available", false, "another auto.Bot is currently running; refusing to navigate (no-move)"));
                        return checks;
                     } else {
                        checks.add(PfTestRunner.check("bot_available", true, "no current auto.Bot"));
                        return checks;
                     }
                  }
               }
            }
         } else {
            checks.add(
               PfTestRunner.check(
                  "marker_configured", false, "system property haven.pf.move.marker is unset or blank (set it at launch, e.g. -Dhaven.pf.move.marker=Home)"
               )
            );
            return checks;
         }
      }
   }

   static Area moveBounds(Coord startTile) {
      if (startTile == null) {
         throw new IllegalArgumentException("startTile must not be null");
      } else {
         return Area.corn(startTile.sub(256, 256), startTile.add(256, 256));
      }
   }

   static List<JSONObject> outcomeChecks(NamedPlaceNavigator.Run navRun, String marker, boolean timedOut) {
      List<JSONObject> checks = new ArrayList<>();
      switch (navRun.status) {
         case UNAVAILABLE:
            checks.add(PfTestRunner.check("session_state", false, "session location unavailable (no map file/segment/player)"));
            return checks;
         case ROUTE_REJECTED:
            checks.add(PfTestRunner.check("marker_resolved", false, routeRefusal(navRun, marker)));
            return checks;
         case NAVIGATED:
            if (timedOut) {
               checks.add(PfTestRunner.check("move_timeout", false, "hard wall-clock deadline exceeded (240000ms); navigation interrupted"));
            }

            switch (navRun.navOutcome) {
               case REACHED_DESTINATION:
                  checks.add(
                     PfTestRunner.check(
                        "navigation_completed", true, "verified arrival at the marker (legs=" + navRun.legs + ", replans=" + navRun.replans + ")"
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

   private static String routeRefusal(NamedPlaceNavigator.Run navRun, String marker) {
      if (navRun.routeKind == null) {
         return "route refused (unknown kind)";
      } else {
         switch (navRun.routeKind) {
            case DEST_MISSING:
               return "unknown marker \"" + marker + "\" (DEST_MISSING)";
            case DEST_AMBIGUOUS:
               return "duplicate marker \"" + marker + "\" (DEST_AMBIGUOUS: more than one marker matches; never guessed)";
            case CROSS_SEGMENT:
               return "marker \"" + marker + "\" is in another segment (CROSS_SEGMENT)";
            case NO_KNOWN_ROUTE:
               return "no known route to marker \"" + marker + "\" (NO_KNOWN_ROUTE)";
            case INVALID_START:
               return "start tile outside the planning bounds or unknown/blocked (INVALID_START)";
            case INVALID_GOAL:
               return "marker tile outside the planning bounds or unknown/blocked (INVALID_GOAL)";
            case EXHAUSTED:
               return "route search exhausted its expansion budget (EXHAUSTED)";
            default:
               return "route refused (" + navRun.routeKind + ")";
         }
      }
   }

   static JSONObject arrivalCheck(boolean navigationVerified, boolean playerPresent, boolean playerIdle, Coord2d at, Coord2d markerWorld) {
      if (!navigationVerified) {
         return PfTestRunner.check("arrived_idle_at_marker", false, "navigation did not verify arrival at the marker");
      } else if (!playerPresent) {
         return PfTestRunner.check("arrived_idle_at_marker", false, "player gob not observable at the final check");
      } else if (!playerIdle) {
         return PfTestRunner.check("arrived_idle_at_marker", false, "player is still moving after navigation (server-confirmed idle arrival required)");
      } else if (at != null && markerWorld != null) {
         double d = at.dist(markerWorld);
         return PfTestRunner.check(
            "arrived_idle_at_marker", d <= 3.0, String.format("idle at (%.1f, %.1f), %.2f units from marker tile center (eps %.2f)", at.x, at.y, d, 3.0)
         );
      } else {
         return PfTestRunner.check("arrived_idle_at_marker", false, "final position or marker position unavailable");
      }
   }

   static JSONObject cancelledBody(NamedPlaceNavigator.Run navRun, String marker, Area bounds, Long goalSeg, Coord goalTile) {
      List<JSONObject> checks = outcomeChecks(navRun, marker, false);
      checks.add(PfTestRunner.check("run_cancelled", false, "the run was cancelled (POST /pf/cancel); terminal navigation facts above are partial"));
      return PfTestHarness.body(
         checks, "cancelled: " + (navRun.detail == null ? "run cancelled" : navRun.detail), factsJson(navRun, marker, bounds, goalSeg, goalTile)
      );
   }

   static JSONObject completedBody(
      NamedPlaceNavigator.Run navRun, String marker, Area bounds, Long goalSeg, Coord goalTile, boolean timedOut, JSONObject arrival
   ) {
      List<JSONObject> checks = outcomeChecks(navRun, marker, timedOut);
      if (arrival != null) {
         checks.add(arrival);
      }

      String note = null;
      if (timedOut) {
         note = "hard wall-clock deadline exceeded (240000ms); navigation interrupted";
      } else if (navRun.cancelled()) {
         note = "navigation cancelled (detail: " + navRun.detail + ")";
      } else if (!navRun.reached()) {
         note = "navigation did not verify arrival at the marker";
      }

      return PfTestHarness.body(checks, note, factsJson(navRun, marker, bounds, goalSeg, goalTile));
   }

   static JSONObject factsJson(NamedPlaceNavigator.Run navRun, String marker, Area bounds, Long goalSeg, Coord goalTile) {
      JSONObject f = new JSONObject();
      f.put("moved", navRun != null && navRun.status == NamedPlaceNavigator.RunStatus.NAVIGATED);
      f.put("arrived", navRun != null && navRun.reached());
      if (marker != null) {
         f.put("marker", marker);
      }

      if (bounds != null) {
         JSONObject b = new JSONObject();
         b.put("ul", new JSONArray().put(bounds.ul.x).put(bounds.ul.y));
         b.put("br", new JSONArray().put(bounds.br.x).put(bounds.br.y));
         f.put("bounds", b);
      }

      if (navRun == null) {
         f.put("status", "UNAVAILABLE");
         f.put("note", "navigation not started (preflight refused)");
         return f;
      } else {
         f.put("status", navRun.status.toString());
         if (navRun.routeKind != null) {
            f.put("route_kind", navRun.routeKind.toString());
         }

         f.put("markerseq", navRun.markerseq);
         if (navRun.start != null) {
            f.put("segment", String.format("%x", navRun.start.seg));
            f.put("start_tile", new JSONArray().put(navRun.start.tile.x).put(navRun.start.tile.y));
            f.put("start_world", new JSONArray().put(PfTestRunner.round2(navRun.start.world.x)).put(PfTestRunner.round2(navRun.start.world.y)));
         }

         if (goalSeg != null) {
            f.put("goal_segment", String.format("%x", goalSeg));
         }

         if (goalTile != null) {
            f.put("goal_tile", new JSONArray().put(goalTile.x).put(goalTile.y));
         }

         if (navRun.routeKind == NamedPlaceRouteService.Kind.REACHED) {
            f.put("waypoint_count", navRun.waypointCount);
         }

         f.put("expanded", navRun.expanded);
         if (navRun.navOutcome != null) {
            f.put("nav_outcome", navRun.navOutcome.toString());
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

         for (NamedPlaceNavigator.ReplanFact r : navRun.replanFacts) {
            JSONObject rf = new JSONObject();
            rf.put("index", r.index);
            rf.put("kind", r.kind.toString());
            rf.put("markerseq", r.markerseq);
            rf.put("expanded", r.expanded);
            replans.put(rf);
         }

         f.put("replan_facts", replans);
         return f;
      }
   }

   interface Navigation {
      MoveToMarkerScenario.Navigation LIVE = (gui, marker, bounds, bot, run) -> MoveToMarkerScenario.liveNavigation(
            gui, marker, bounds, bot
         );

      NamedPlaceNavigator.Run run(GameUI var1, String var2, Area var3, Bot var4, PfTestRunner.Run var5) throws Exception;
   }
}
