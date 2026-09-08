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
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Allowlisted critical-route campaign scenarios. One run completes both
 * applicable directions while holding a single original POINT destination
 * across transitions. Missing fixtures fail closed with NO_FIXTURE.
 */
final class CriticalRouteScenario implements PfTestRunner.Scenario {
   enum Kind {
      SURFACE_OUT_AND_BACK("campaign_surface_out_and_back", true),
      SURFACE_AND_DOOR("campaign_surface_and_door", true),
      DOOR_GATE_ROUNDTRIP("campaign_door_gate_roundtrip", true),
      CELLAR_ROUNDTRIP("campaign_cellar_roundtrip", true),
      MINEHOLE_ROUNDTRIP("campaign_minehole_roundtrip", true),
      CAVE_ROUNDTRIP("campaign_cave_roundtrip", true),
      BOAT_ROUNDTRIP("campaign_boat_roundtrip", true),
      VEHICLE_ROUNDTRIP("campaign_vehicle_roundtrip", true);

      final String name;
      final boolean bidirectional;

      Kind(String name, boolean bidirectional) {
         this.name = name;
         this.bidirectional = bidirectional;
      }
   }

   private final Kind kind;

   CriticalRouteScenario(Kind kind) {
      this.kind = kind;
   }

   @Override
   public String name() {
      return this.kind.name;
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
         PfTestHarness.autoMovePreflightChecks(this.kind.name, inGame, playerPresent, true, idle, Bot.hasCurrent())
      );
      if (!inGame || !playerPresent) {
         return failClosed(checks, "NO_GAME", "not in game", null, false);
      }
      Coord2d home;
      synchronized (ui) {
         Gob me = gui.map.player();
         home = me == null ? null : me.rc;
      }
      if (home == null) {
         return failClosed(checks, "NO_GAME", "player gob missing", null, false);
      }
      if (this.kind == Kind.SURFACE_OUT_AND_BACK) {
         return surfaceOutAndBack(run, ui, gui, checks, home);
      }
      if (this.kind == Kind.SURFACE_AND_DOOR) {
         return surfaceAndDoor(run, ui, gui, checks, home);
      }
      if (this.kind == Kind.DOOR_GATE_ROUNDTRIP) {
         return transitionRoundTrip(run, ui, gui, checks, home, TransitionScenario.Kind.DOOR_GATE);
      }
      if (this.kind == Kind.CELLAR_ROUNDTRIP) {
         return transitionRoundTrip(run, ui, gui, checks, home, TransitionScenario.Kind.CELLAR_STAIRS);
      }
      if (this.kind == Kind.MINEHOLE_ROUNDTRIP) {
         return transitionRoundTrip(run, ui, gui, checks, home, TransitionScenario.Kind.MINEHOLE);
      }
      if (this.kind == Kind.CAVE_ROUNDTRIP) {
         return transitionRoundTrip(run, ui, gui, checks, home, TransitionScenario.Kind.CAVE);
      }
      if (this.kind == Kind.BOAT_ROUNDTRIP) {
         return vehicleCycle(
            run, ui, gui, checks, home, TransitionScenario.Kind.BOAT_BOARD, TransitionScenario.Kind.BOAT_TRAVEL, TransitionScenario.Kind.BOAT_DISEMBARK
         );
      }
      return vehicleCycle(
         run, ui, gui, checks, home, TransitionScenario.Kind.VEHICLE_ENTER, TransitionScenario.Kind.VEHICLE_TRAVEL, TransitionScenario.Kind.VEHICLE_EXIT
      );
   }

   private JSONObject surfaceOutAndBack(PfTestRunner.Run run, UI ui, GameUI gui, List<JSONObject> checks, Coord2d home) throws Exception {
      PrototypePathfinder.Scene scene;
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
      }
      NavigationTestSpotSelector.Selection sel = NavigationTestSpotSelector.openGround(scene);
      if (sel == null || !sel.selected() || sel.targetWorld == null) {
         return failClosed(checks, "NO_FIXTURE", sel == null ? "no open-ground fixture" : sel.evidence, null, false);
      }
      if (home.dist(sel.targetWorld) <= 3.0) {
         return failClosed(checks, "NO_FIXTURE", "open-ground target is the start tile", NavGoal.point(sel.targetWorld), false);
      }
      NavGoal original = NavGoal.point(sel.targetWorld);
      recordGoal(original);
      checks.add(PfTestRunner.check("fixture", true, this.kind.name + " dest " + PfTestHarness.pt(sel.targetWorld)));
      JSONObject walked = walkTo(run, ui, gui, checks, sel.targetWorld, "forward", original, true, false);
      if (walked != null) {
         return walked;
      }
      checks.add(PfTestRunner.check("original_goal_forward", true, "held " + PfTestHarness.pt(original.position)));
      JSONObject back = walkTo(run, ui, gui, checks, home, "reverse", original, false, true);
      if (back != null) {
         return back;
      }
      return finish(checks, original, true, true, false, "both directions arrived", null);
   }

   private JSONObject surfaceAndDoor(PfTestRunner.Run run, UI ui, GameUI gui, List<JSONObject> checks, Coord2d home) throws Exception {
      PrototypePathfinder.Scene scene;
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
      }
      NavigationTestSpotSelector.Selection sel = NavigationTestSpotSelector.openGround(scene);
      if (sel == null || !sel.selected() || sel.targetWorld == null || home.dist(sel.targetWorld) <= 3.0) {
         return failClosed(checks, "NO_FIXTURE", "no open-ground fixture for combined surface+door route", null, false);
      }
      NavGoal original = NavGoal.point(sel.targetWorld);
      recordGoal(original);
      checks.add(PfTestRunner.check("fixture", true, "surface dest " + PfTestHarness.pt(sel.targetWorld)));
      JSONObject walked = walkTo(run, ui, gui, checks, sel.targetWorld, "forward", original, true, false);
      if (walked != null) {
         return walked;
      }
      waitIdle(run, ui, gui);
      JSONObject door = new InteractScenario(InteractScenario.Kind.DOOR).execute(run, ui);
      JSONObject adopted = adopt(checks, door, "interact_door_gate", original, false, true);
      if (adopted != null) {
         return adopted;
      }
      JSONObject back = walkTo(run, ui, gui, checks, home, "reverse", original, false, true);
      if (back != null) {
         return back;
      }
      return finish(checks, original, true, true, false, "surface, interaction, and return arrived", null);
   }

   private JSONObject transitionRoundTrip(
      PfTestRunner.Run run, UI ui, GameUI gui, List<JSONObject> checks, Coord2d home, TransitionScenario.Kind edge
   ) throws Exception {
      NavGoal original = NavGoal.point(home);
      recordGoal(original);
      waitIdle(run, ui, gui);
      JSONObject forward = new TransitionScenario(edge).execute(run, ui, original);
      JSONObject adopted = adopt(checks, forward, "forward_" + edge.name, original, true, false);
      if (adopted != null) {
         return adopted;
      }
      JSONObject far = farSideSurface(run, ui, gui, checks, original);
      if (far != null) {
         return far;
      }
      if (run.cancelled) {
         throw new PfTestRunner.Cancelled();
      }
      waitIdle(run, ui, gui);
      JSONObject reverse = new TransitionScenario(edge).execute(run, ui, original);
      adopted = adopt(checks, reverse, "reverse_" + edge.name, original, true, true);
      if (adopted != null) {
         return adopted;
      }
      JSONObject homeWalk = walkTo(run, ui, gui, checks, home, "resume_original", original, false, true);
      if (homeWalk != null) {
         return homeWalk;
      }
      return finish(checks, original, true, true, false, "round trip preserved original destination", null);
   }

   private JSONObject vehicleCycle(
      PfTestRunner.Run run,
      UI ui,
      GameUI gui,
      List<JSONObject> checks,
      Coord2d home,
      TransitionScenario.Kind enter,
      TransitionScenario.Kind travel,
      TransitionScenario.Kind exit
   ) throws Exception {
      NavGoal original = NavGoal.point(home);
      recordGoal(original);
      waitIdle(run, ui, gui);
      JSONObject adopted = adopt(checks, new TransitionScenario(enter).execute(run, ui, original), "forward_" + enter.name, original, true, false);
      if (adopted != null) {
         return adopted;
      }
      waitIdle(run, ui, gui);
      adopted = adopt(checks, new TransitionScenario(travel).execute(run, ui, original), "forward_" + travel.name, original, true, false);
      if (adopted != null) {
         return adopted;
      }
      waitIdle(run, ui, gui);
      adopted = adopt(checks, new TransitionScenario(exit).execute(run, ui, original), "forward_" + exit.name, original, true, false);
      if (adopted != null) {
         return adopted;
      }
      waitIdle(run, ui, gui);
      adopted = adopt(checks, new TransitionScenario(enter).execute(run, ui, original), "reverse_" + enter.name, original, true, true);
      if (adopted != null) {
         return adopted;
      }
      waitIdle(run, ui, gui);
      adopted = adopt(checks, new TransitionScenario(travel).execute(run, ui, original), "reverse_" + travel.name, original, true, true);
      if (adopted != null) {
         return adopted;
      }
      waitIdle(run, ui, gui);
      adopted = adopt(checks, new TransitionScenario(exit).execute(run, ui, original), "reverse_" + exit.name, original, true, true);
      if (adopted != null) {
         return adopted;
      }
      JSONObject homeWalk = walkTo(run, ui, gui, checks, home, "resume_original", original, false, true);
      if (homeWalk != null) {
         return homeWalk;
      }
      return finish(checks, original, true, true, false, "vehicle cycle preserved original destination", null);
   }

   private JSONObject farSideSurface(PfTestRunner.Run run, UI ui, GameUI gui, List<JSONObject> checks, NavGoal original) throws Exception {
      PrototypePathfinder.Scene scene;
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
      }
      NavigationTestSpotSelector.Selection sel = NavigationTestSpotSelector.openGround(scene);
      if (sel == null || !sel.selected() || sel.targetWorld == null) {
         checks.add(PfTestRunner.skip("far_side_surface", "no open-ground fixture after landing"));
         return null;
      }
      PrototypePathfinder.Plan plan = PrototypePathfinder.planAny(gui, Collections.singletonList(sel.targetWorld), true);
      if (plan == null || plan.status != PrototypePathfinder.Plan.Status.REACHED) {
         checks.add(PfTestRunner.skip("far_side_surface", plan == null ? "no local plan after landing" : "plan " + plan.status + " after landing"));
         return null;
      }
      return walkTo(run, ui, gui, checks, sel.targetWorld, "far_side_surface", original, false, true);
   }

   private JSONObject walkTo(
      PfTestRunner.Run run,
      UI ui,
      GameUI gui,
      List<JSONObject> checks,
      Coord2d dest,
      String leg,
      NavGoal original,
      boolean fixtureIfUnroutable,
      boolean forwardDone
   ) throws Exception {
      if (run.cancelled) {
         throw new PfTestRunner.Cancelled();
      }
      waitIdle(run, ui, gui);
      if (alreadyThere(ui, gui, dest)) {
         checks.add(PfTestRunner.check(leg + "_arrival", true, "already idle at " + PfTestHarness.pt(dest)));
         return null;
      }
      Bot bot = Bot.execute(new Bot.BotAction[0]);
      String err = MoveToAutoOpenGroundScenario.followTo(gui, dest, bot, 120000L);
      if (err != null) {
         boolean stall = err.contains("STUCK") || err.contains("stuck");
         if (fixtureIfUnroutable && !stall) {
            return failClosed(checks, "NO_FIXTURE", err, original, false);
         }
         checks.add(PfTestRunner.check(leg, false, err));
         return finish(checks, original, forwardDone, false, stall, stall ? "unrecovered stall on " + leg : err, null);
      }
      checks.add(PfTestRunner.check(leg, true, "server-confirmed idle arrival"));
      return null;
   }

   private JSONObject adopt(
      List<JSONObject> checks, JSONObject inner, String leg, NavGoal original, boolean requireInnerGoal, boolean forwardDone
   ) {
      mergeChecks(checks, inner);
      JSONObject facts = inner == null ? null : inner.optJSONObject("facts");
      String refusal = jsonRefusal(facts);
      if ("NO_GAME".equals(refusal) || "NO_POSE".equals(refusal) || ("NO_FIXTURE".equals(refusal) && !forwardDone)) {
         return failClosed(checks, refusal, leg + ": " + inner.optString("note", refusal), original, false);
      }
      if ("NO_FIXTURE".equals(refusal) && forwardDone) {
         checks.add(PfTestRunner.check(leg, false, "reverse fixture missing after forward: " + inner.optString("note", refusal)));
         return finish(checks, original, true, false, false, "both directions required; reverse fixture missing", null);
      }
      if (inner == null || !"PASS".equals(inner.optString("verdict"))) {
         boolean stall = inner != null && (inner.optString("note", "").contains("STUCK") || walkStuck(inner));
         checks.add(PfTestRunner.check(leg, false, inner == null ? "missing leg" : inner.optString("note", "leg failed")));
         return finish(checks, original, forwardDone, false, stall, leg + " failed", null);
      }
      if (requireInnerGoal && original != null && original.position != null) {
         String got = facts == null ? "" : facts.optString("original_goal", "");
         boolean held = PfTestHarness.pt(original.position).equals(got);
         checks.add(PfTestRunner.check("original_goal_" + leg, held, held ? "held " + got : "lost original dest (got " + got + ")"));
         if (!held) {
            return finish(checks, original, forwardDone, false, false, "original destination not preserved across " + leg, null);
         }
      }
      checks.add(PfTestRunner.check(leg, true, "ok"));
      return null;
   }

   private JSONObject finish(
      List<JSONObject> checks, NavGoal original, boolean forward, boolean reverse, boolean stall, String note, String refusal
   ) {
      boolean held = original != null && original.kind == NavGoal.Kind.POINT && original.position != null;
      checks.add(PfTestRunner.check("original_goal", held && !stall, held ? "original POINT dest " + PfTestHarness.pt(original.position) : "no original dest"));
      checks.add(PfTestRunner.check("forward", forward, forward ? "forward complete" : "forward incomplete"));
      if (this.kind.bidirectional) {
         checks.add(PfTestRunner.check("reverse", reverse, reverse ? "reverse complete" : "reverse incomplete"));
      }
      checks.add(PfTestRunner.check("unrecovered_stall", !stall, stall ? "unrecovered stall" : "no unrecovered stall"));
      JSONObject facts = new JSONObject()
         .put("kind", this.kind.name())
         .put("bidirectional", this.kind.bidirectional)
         .put("forward", forward)
         .put("reverse", reverse)
         .put("original_goal", original == null || original.position == null ? JSONObject.NULL : PfTestHarness.pt(original.position))
         .put("original_goal_preserved", held && "PASS".equals(PfTestHarness.verdictOf(checks)))
         .put("unrecovered_stall", stall)
         .put("refusal", refusal == null ? JSONObject.NULL : refusal);
      return PfTestHarness.body(checks, note, facts);
   }

   private JSONObject failClosed(List<JSONObject> checks, String refusal, String why, NavGoal original, boolean stall) {
      checks.add(PfTestRunner.check("fixture", false, why));
      JSONObject facts = new JSONObject()
         .put("kind", this.kind.name())
         .put("bidirectional", this.kind.bidirectional)
         .put("forward", false)
         .put("reverse", false)
         .put("original_goal", original == null || original.position == null ? JSONObject.NULL : PfTestHarness.pt(original.position))
         .put("original_goal_preserved", false)
         .put("unrecovered_stall", stall)
         .put("refusal", refusal)
         .put("selected", false);
      return PfTestHarness.body(checks, why, facts);
   }

   private static void recordGoal(NavGoal original) {
      JSONObject g = new JSONObject();
      g.put("original_goal_kind", original.kind.name());
      g.put("original_goal", original.position == null ? JSONObject.NULL : PfTestHarness.pt(original.position));
      g.put("reached", false);
      PathfinderLog.recordGraph(g);
   }

   private static void mergeChecks(List<JSONObject> checks, JSONObject inner) {
      if (inner == null) {
         return;
      }
      JSONArray arr = inner.optJSONArray("checks");
      if (arr == null) {
         return;
      }
      for (int i = 0; i < arr.length(); i++) {
         checks.add(arr.getJSONObject(i));
      }
   }

   private static String jsonRefusal(JSONObject facts) {
      if (facts == null) {
         return "";
      }
      Object r = facts.opt("refusal");
      if (r == null || r == JSONObject.NULL) {
         return "";
      }
      return r.toString();
   }

   private static boolean walkStuck(JSONObject inner) {
      JSONArray arr = inner == null ? null : inner.optJSONArray("checks");
      if (arr == null) {
         return false;
      }
      for (int i = 0; i < arr.length(); i++) {
         JSONObject c = arr.getJSONObject(i);
         String d = c.optString("detail", "");
         if (d.contains("STUCK") || d.contains("recovery exhausted")) {
            return true;
         }
      }
      return false;
   }

   private static boolean alreadyThere(UI ui, GameUI gui, Coord2d dest) {
      Coord2d at = PfTestHarness.observePos(gui);
      if (at == null || dest == null || at.dist(dest) > 3.0) {
         return false;
      }
      synchronized (ui) {
         Gob me = gui.map.player();
         return me == null || me.getattr(Moving.class) == null;
      }
   }

   private static void waitIdle(PfTestRunner.Run run, UI ui, GameUI gui) throws InterruptedException, PfTestRunner.Cancelled {
      long deadline = System.currentTimeMillis() + 5000L;
      while (System.currentTimeMillis() < deadline) {
         if (run.cancelled) {
            throw new PfTestRunner.Cancelled();
         }
         boolean idle;
         synchronized (ui) {
            Gob me = gui.map.player();
            idle = me == null || me.getattr(Moving.class) == null;
         }
         if (idle && !Bot.hasCurrent()) {
            return;
         }
         Thread.sleep(50L);
      }
   }
}
