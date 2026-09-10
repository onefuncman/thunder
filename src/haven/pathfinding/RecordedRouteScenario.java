package haven.pathfinding;

import auto.Bot;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Moving;
import haven.UI;
import haven.nav.NavGoal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * Plays a user-saved critical route from {@link CriticalRouteBook}.
 * The active route id is {@code routes/current.txt}, set by the in-game
 * editor or by {@code pf_matrix.py} — not by raw coordinates on HTTP.
 */
final class RecordedRouteScenario implements PfTestRunner.Scenario {
   @Override
   public String name() {
      return "campaign_recorded";
   }

   @Override
   public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
      GameUI gui = ui == null ? null : ui.gui;
      boolean inGame = gui != null && gui.map != null;
      boolean playerPresent = false;
      boolean idle = true;
      if (inGame) {
         synchronized (ui) {
            Gob me = gui.map.player();
            playerPresent = me != null && me.rc != null;
            idle = me == null || me.getattr(Moving.class) == null;
         }
      }
      List<JSONObject> checks = new ArrayList<JSONObject>(
         PfTestHarness.autoMovePreflightChecks(this.name(), inGame, playerPresent, true, idle, Bot.hasCurrent())
      );
      if (!inGame || !playerPresent) {
         return fail(checks, "NO_GAME", "not in game", null);
      }
      String id = CriticalRouteBook.currentId();
      if (id == null || id.isEmpty()) {
         return fail(checks, "NO_FIXTURE", "no current recorded route", null);
      }
      CriticalRouteBook book;
      try {
         book = CriticalRouteBook.load(id);
      } catch (Exception e) {
         return fail(checks, "NO_FIXTURE", "recorded route missing: " + id, null);
      }
      if (book.legs.isEmpty()) {
         return fail(checks, "NO_FIXTURE", "recorded route has no legs", null);
      }
      Coord2d dest = book.originalDest();
      NavGoal original = dest == null ? null : NavGoal.point(dest);
      checks.add(PfTestRunner.check("fixture", true, "recorded " + book.id + " " + book.legs.size() + " legs"));
      String err = play(run, ui, gui, checks, book, false, original, false);
      if (err != null) {
         return finish(checks, original, false, false, err.contains("STUCK"), err, refusalOf(err));
      }
      err = play(run, ui, gui, checks, book, true, original, false);
      if (err != null) {
         return finish(checks, original, true, false, err.contains("STUCK"), err, refusalOf(err));
      }
      boolean held = original != null && original.position != null;
      checks.add(PfTestRunner.check("original_goal", held, held ? "held " + PfTestHarness.pt(original.position) : "no dest"));
      checks.add(PfTestRunner.check("forward", true, "forward complete"));
      checks.add(PfTestRunner.check("reverse", true, "reverse complete"));
      checks.add(PfTestRunner.check("unrecovered_stall", true, "no unrecovered stall"));
      JSONObject facts = new JSONObject()
         .put("kind", "RECORDED")
         .put("route", book.id)
         .put("bidirectional", true)
         .put("forward", true)
         .put("reverse", true)
         .put("original_goal", dest == null ? JSONObject.NULL : PfTestHarness.pt(dest))
         .put("original_goal_preserved", held)
         .put("unrecovered_stall", false)
         .put("refusal", JSONObject.NULL);
      return PfTestHarness.body(checks, "recorded route both directions", facts);
   }

   static String walkLive(GameUI gui, CriticalRouteBook book, boolean reverse) throws Exception {
      if (gui == null || gui.ui == null) {
         return "no game";
      }
      List<JSONObject> checks = new ArrayList<JSONObject>();
      NavGoal original = book.originalDest() == null ? null : NavGoal.point(book.originalDest());
      PfTestRunner.Run run = new PfTestRunner.Run("campaign_recorded");
      return play(run, gui.ui, gui, checks, book, reverse, original, true);
   }

   private static String play(
      PfTestRunner.Run run,
      UI ui,
      GameUI gui,
      List<JSONObject> checks,
      CriticalRouteBook book,
      boolean reverse,
      NavGoal original,
      boolean resumeHere
   ) throws Exception {
      List<CriticalRouteBook.Leg> order = new ArrayList<CriticalRouteBook.Leg>(book.legs);
      if (reverse) {
         Collections.reverse(order);
      }
      int ran = 0;
      long pendingClose = -1L;
      for (int i = 0; i < order.size(); i++) {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         }
         CriticalRouteBook.Leg leg = order.get(i);
         String tag = (reverse ? "rev" : "fwd") + "_" + (i + 1);
         String live = CriticalRouteBook.segmentId(gui);
         Gob liveGob = ("gob".equals(leg.kind) || CriticalRouteBook.isApproach(leg))
            ? CriticalRouteBook.resolveGob(gui, leg.gobId, leg.resid, CriticalRouteBook.sessionWorld(gui, leg))
            : null;
         if (liveGob != null && !CriticalRouteBook.isApproach(leg)) {
            liveGob = BuildingDoor.preferDoorGob(gui, liveGob);
         }
         boolean fixtureHere = liveGob != null;
         if (skipOtherFloor(leg, live, fixtureHere, resumeHere)) {
            continue;
         }
         if (CriticalRouteBook.isApproach(leg)) {
            if (liveGob == null) {
               return ApproachOnly.Outcome.TARGET_DISAPPEARED.name() + ": no live object on " + tag;
            }
            ApproachOnly.Result ap = ApproachOnly.run(run, ui, gui, liveGob);
            checks.add(ApproachOnly.check(ap));
            if (ap.outcome != ApproachOnly.Outcome.APPROACH_READY) {
               return ap.outcome.name() + ": " + (ap.detail.isEmpty() ? tag : ap.detail);
            }
            ran++;
            continue;
         }
         TransitionScenario.Kind tk = transitionKind(leg);
         if (tk == null && liveGob != null) {
            haven.nav.GraphEdge.Kind liveKind = TransitionAdapter.kind(CriticalRouteBook.gobResid(liveGob));
            if (liveKind == haven.nav.GraphEdge.Kind.DOOR_GATE) {
               tk = TransitionScenario.Kind.DOOR_GATE;
            }
         }
         if (tk == null && BuildingDoor.isHouseHull(leg.resid)) {
            tk = TransitionScenario.Kind.DOOR_GATE;
         }
         if ("gob".equals(leg.kind) && tk != null) {
            Coord2d dest = CriticalRouteBook.sessionWorld(gui, leg);
            if (BuildingDoor.isHouseHull(leg.resid)) {
               // Walk to the doorway, never the blocked hull centre: the hull
               // centre is a solid cell and planAny snaps it to a far corner,
               // which followTo reports as "stuck: no progress around obstacles".
               // The doorway sits on the wall, so planAny snaps it to the free
               // cell immediately in front of the door.
               dest = BuildingDoor.walkWorld(gui, leg, dest);
            } else if (TransitionApproachSelector.isCaveTransitionResid(leg.resid)) {
               // Cave-transition fixtures (minehole/ladder/cellar-stairs) have a
               // solid centre. Walking to liveGob.rc targets a solid cell and
               // planAny snaps it to a far free cell, stalling the walker before
               // the transition runs ("walk stalled: aba_oscillation"). When the
               // gob is already resolvable, skip this pre-walk entirely: the
               // transition's own staging walks to a stand-off pose beside the
               // fixture and never targets its centre. When it is not yet
               // resolvable, keep the recorded-position hop below to bring the
               // fixture into observation range.
               if (liveGob != null) {
                  dest = null;
               }
            } else if (liveGob != null && liveGob.rc != null) {
               dest = liveGob.rc;
            }
            Coord2d at = PfTestHarness.observePos(gui);
            if (dest != null && (at == null || at.dist(dest) > 35.0)) {
               String walkErr = walkTo(run, ui, gui, checks, dest, tag);
               if (walkErr != null) {
                  return walkErr;
               }
               if (liveGob == null) {
                  liveGob = CriticalRouteBook.resolveGob(gui, leg.gobId, leg.resid, dest);
               }
            }
            if ("boat".equals(leg.role) || "vehicle".equals(leg.role)) {
               Gob me;
               synchronized (ui) {
                  me = gui.map.player();
               }
               boolean aboard = me != null && me.vehicleId() != 0L;
               if ("boat".equals(leg.role)) {
                  tk = aboard ? TransitionScenario.Kind.BOAT_DISEMBARK : TransitionScenario.Kind.BOAT_BOARD;
               } else {
                  tk = aboard ? TransitionScenario.Kind.VEHICLE_EXIT : TransitionScenario.Kind.VEHICLE_ENTER;
               }
            }
            long gobId = liveGob == null ? -1L : liveGob.id;
            JSONObject inner = new TransitionScenario(tk).execute(run, ui, original, gobId, true);
            merge(checks, inner);
            String refusal = refusal(inner);
            if (resumeHere && "NO_FIXTURE".equals(refusal) && !leg.onFloor(live)) {
               continue;
            }
            if ("NO_GAME".equals(refusal) || "NO_FIXTURE".equals(refusal) || "NO_POSE".equals(refusal)) {
               return refusal + ": " + inner.optString("note", tag);
            }
            if (!"PASS".equals(inner.optString("verdict"))) {
               return inner.optString("note", tag + " failed");
            }
            if (closeBehind(leg) && gobId >= 0L) {
               pendingClose = gobId;
            }
            ran++;
            continue;
         }
         live = CriticalRouteBook.segmentId(gui);
         if (!leg.onFloor(live)) {
            if (resumeHere) {
               continue;
            }
            return "NO_FIXTURE: start on the first floor of the route (now on another floor than " + tag + ")";
         }
         Coord2d dest = CriticalRouteBook.sessionWorld(gui, leg);
         if ("gob".equals(leg.kind) && liveGob != null && liveGob.rc != null) {
            dest = liveGob.rc;
         }
         if (dest == null) {
            return "NO_FIXTURE: missing dest on " + tag;
         }
         String walkErr = walkTo(run, ui, gui, checks, dest, tag);
         if (walkErr != null) {
            return walkErr;
         }
         if (pendingClose >= 0L) {
            String closeErr = TransitionScenario.closeGate(run, ui, gui, pendingClose);
            checks.add(
               closeErr == null
                  ? PfTestRunner.check("gate_closed", true, "closed behind after " + tag)
                  : PfTestHarness.skip("gate_closed", closeErr)
            );
            pendingClose = -1L;
         }
         ran++;
      }
      if (pendingClose >= 0L) {
         String closeErr = TransitionScenario.passThroughAndClose(run, ui, gui, pendingClose, PfTestHarness.observePos(gui));
         checks.add(
            closeErr == null
               ? PfTestRunner.check("gate_closed", true, "closed behind")
               : PfTestHarness.skip("gate_closed", closeErr)
         );
      }
      if (resumeHere && ran == 0) {
         return "NO_FIXTURE: no legs on this floor — go to the basement to walk the full route";
      }
      return null;
   }

   static boolean closeBehind(CriticalRouteBook.Leg leg) {
      return leg != null && "door_gate".equals(leg.role) && TransitionAdapter.isPassThroughGate(leg.resid);
   }

   static boolean skipOtherFloor(CriticalRouteBook.Leg leg, String liveSeg, boolean fixtureHere, boolean resumeHere) {
      if (!resumeHere || leg == null) {
         return false;
      }
      if (leg.onFloor(liveSeg)) {
         return false;
      }
      return !fixtureHere;
   }

   private static String walkTo(PfTestRunner.Run run, UI ui, GameUI gui, List<JSONObject> checks, Coord2d dest, String leg)
      throws Exception {
      waitIdle(run, ui, gui);
      Coord2d at = PfTestHarness.observePos(gui);
      if (at != null && dest != null && at.dist(dest) <= 3.0) {
         checks.add(PfTestRunner.check(leg, true, "already at dest"));
         return null;
      }
      Bot bot = Bot.execute(new Bot.BotAction[0]);
      String err = MoveToAutoOpenGroundScenario.followTo(gui, dest, bot, 120000L);
      if (err != null) {
         return err.contains("STUCK") || err.contains("stuck") ? "STUCK on " + leg + ": " + err : err + " on " + leg;
      }
      checks.add(PfTestRunner.check(leg, true, "server-confirmed idle arrival"));
      return null;
   }

   static TransitionScenario.Kind transitionKind(CriticalRouteBook.Leg leg) {
      if (leg == null || CriticalRouteBook.isApproach(leg)) {
         return null;
      }
      TransitionScenario.Kind fromRole = transitionKind(leg.role);
      if (fromRole != null) {
         return fromRole;
      }
      haven.nav.GraphEdge.Kind k = TransitionAdapter.kind(leg.resid);
      if (k == haven.nav.GraphEdge.Kind.DOOR_GATE) {
         return TransitionScenario.Kind.DOOR_GATE;
      }
      if (k == haven.nav.GraphEdge.Kind.CELLAR_STAIRS) {
         return TransitionScenario.Kind.CELLAR_STAIRS;
      }
      if (k == haven.nav.GraphEdge.Kind.CAVE) {
         return TransitionScenario.Kind.CAVE;
      }
      if (k == haven.nav.GraphEdge.Kind.LADDER) {
         return TransitionScenario.Kind.LADDER;
      }
      if (k == haven.nav.GraphEdge.Kind.MINEHOLE) {
         return TransitionScenario.Kind.MINEHOLE;
      }
      if (k == haven.nav.GraphEdge.Kind.BOAT) {
         return TransitionScenario.Kind.BOAT_BOARD;
      }
      if (k == haven.nav.GraphEdge.Kind.VEHICLE) {
         return TransitionScenario.Kind.VEHICLE_ENTER;
      }
      if (k == haven.nav.GraphEdge.Kind.HEARTH) {
         return TransitionScenario.Kind.HEARTH;
      }
      return null;
   }

   private static TransitionScenario.Kind transitionKind(String role) {
      if (role == null) {
         return null;
      }
      String r = role.toLowerCase();
      if ("door_gate".equals(r)) {
         return TransitionScenario.Kind.DOOR_GATE;
      }
      if ("cellar_stairs".equals(r)) {
         return TransitionScenario.Kind.CELLAR_STAIRS;
      }
      if ("cave".equals(r)) {
         return TransitionScenario.Kind.CAVE;
      }
      if ("ladder".equals(r)) {
         return TransitionScenario.Kind.LADDER;
      }
      if ("minehole".equals(r)) {
         return TransitionScenario.Kind.MINEHOLE;
      }
      if ("boat".equals(r)) {
         return TransitionScenario.Kind.BOAT_BOARD;
      }
      if ("vehicle".equals(r)) {
         return TransitionScenario.Kind.VEHICLE_ENTER;
      }
      if ("hearth".equals(r)) {
         return TransitionScenario.Kind.HEARTH;
      }
      return null;
   }

   private static void waitIdle(PfTestRunner.Run run, UI ui, GameUI gui) throws InterruptedException, PfTestRunner.Cancelled {
      TransitionScenario.waitIdle(run, ui, gui);
   }

   private static void merge(List<JSONObject> checks, JSONObject inner) {
      if (inner == null || inner.optJSONArray("checks") == null) {
         return;
      }
      org.json.JSONArray arr = inner.getJSONArray("checks");
      for (int i = 0; i < arr.length(); i++) {
         checks.add(arr.getJSONObject(i));
      }
   }

   private static String refusal(JSONObject inner) {
      JSONObject f = inner == null ? null : inner.optJSONObject("facts");
      if (f == null) {
         return "";
      }
      Object r = f.opt("refusal");
      return r == null || r == JSONObject.NULL ? "" : r.toString();
   }

   private static String refusalOf(String err) {
      if (err != null && err.startsWith("NO_")) {
         int c = err.indexOf(':');
         return c < 0 ? err : err.substring(0, c);
      }
      return null;
   }

   private static JSONObject fail(List<JSONObject> checks, String refusal, String why, NavGoal original) {
      checks.add(PfTestRunner.check("fixture", false, why));
      JSONObject facts = new JSONObject()
         .put("kind", "RECORDED")
         .put("forward", false)
         .put("reverse", false)
         .put("original_goal_preserved", false)
         .put("unrecovered_stall", false)
         .put("refusal", refusal);
      return PfTestHarness.body(checks, why, facts);
   }

   private static JSONObject finish(
      List<JSONObject> checks, NavGoal original, boolean forward, boolean reverse, boolean stall, String note, String refusal
   ) {
      boolean held = original != null && original.position != null;
      checks.add(PfTestRunner.check("original_goal", held && !stall, held ? PfTestHarness.pt(original.position) : "no dest"));
      checks.add(PfTestRunner.check("forward", forward, forward ? "ok" : "incomplete"));
      checks.add(PfTestRunner.check("reverse", reverse, reverse ? "ok" : "incomplete"));
      JSONObject facts = new JSONObject()
         .put("kind", "RECORDED")
         .put("forward", forward)
         .put("reverse", reverse)
         .put("original_goal", original == null || original.position == null ? JSONObject.NULL : PfTestHarness.pt(original.position))
         .put("original_goal_preserved", false)
         .put("unrecovered_stall", stall)
         .put("refusal", refusal == null ? JSONObject.NULL : refusal);
      return PfTestHarness.body(checks, note, facts);
   }
}
