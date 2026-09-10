package haven.pathfinding;

import auto.Bot;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Moving;
import haven.UI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * Allowlisted surface-travel scenarios. Missing fixtures fail closed with
 * NO_FIXTURE rather than inventing a scenario-specific planner exception.
 */
final class SurfaceTravelScenario implements PfTestRunner.Scenario {
   enum Kind {
      LONG_OPEN_GROUND("surface_long_open_ground", NavigationTestSpotSelector.Profile.OPEN_GROUND),
      SINGLE_TREE("surface_single_tree_detour", NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR),
      DENSE_FOREST("surface_dense_forest", NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR),
      CLUSTERED("surface_clustered_obstacles", NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR),
      ONE_TILE("surface_one_tile_corridor", NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR),
      DIAGONAL("surface_diagonal_corridor", NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR),
      BUILDINGS("surface_buildings_fences", NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR),
      WATER("surface_water_boundary", null),
      CLIFF("surface_cliff_boundary", null),
      MOVING("surface_moving_neutral", null),
      HOSTILE("surface_hostile_exclusion", null),
      UNKNOWN("surface_unknown_geometry", null),
      FRONTIER("surface_explore_frontier", NavigationTestSpotSelector.Profile.KNOWN_MAP_LONG_LEG);

      final String name;
      final NavigationTestSpotSelector.Profile profile;

      Kind(String name, NavigationTestSpotSelector.Profile profile) {
         this.name = name;
         this.profile = profile;
      }
   }

   private final Kind kind;

   SurfaceTravelScenario(Kind kind) {
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
         JSONObject facts = new JSONObject().put("refusal", "NO_GAME").put("selected", false).put("kind", this.kind.name());
         return PfTestHarness.body(checks, "not in game", facts);
      }
      if (this.kind.profile == null || this.kind.profile == NavigationTestSpotSelector.Profile.KNOWN_MAP_LONG_LEG) {
         return failClosed(checks, "NO_FIXTURE", "no automatic fixture for " + this.kind.name);
      }
      PrototypePathfinder.Scene scene;
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
      }
      NavigationTestSpotSelector.Selection sel = this.kind.profile == NavigationTestSpotSelector.Profile.OPEN_GROUND
         ? NavigationTestSpotSelector.openGround(scene)
         : NavigationTestSpotSelector.localObstacleOrCorridor(scene);
      if (sel == null || !sel.selected() || sel.targetWorld == null) {
         return failClosed(checks, "NO_FIXTURE", sel == null ? "no selection" : sel.evidence);
      }
      checks.add(PfTestRunner.check("fixture", true, this.kind.name + " target " + PfTestHarness.pt(sel.targetWorld)));
      PrototypePathfinder.Plan plan = PrototypePathfinder.planAny(gui, Collections.singletonList(sel.targetWorld), true);
      if (plan == null || plan.status != PrototypePathfinder.Plan.Status.REACHED) {
         return failClosed(checks, "NO_FIXTURE", plan == null ? "no local plan" : "plan " + plan.status + " is not a complete local route");
      }
      Bot bot = Bot.execute(new Bot.BotAction[0]);
      MoveToAutoOpenGroundScenario.MoveResult mv = MoveToAutoOpenGroundScenario.walkPlan(gui, plan, sel.targetWorld, bot, 60000L, 60000L);
      checks.add(MoveToAutoOpenGroundScenario.routeCheck(mv));
      checks.add(MoveToAutoOpenGroundScenario.walkCheck(mv));
      checks.add(MoveToAutoOpenGroundScenario.finalArrival(gui, ui, mv, sel.targetWorld));
      JSONObject facts = new JSONObject()
         .put("kind", this.kind.name())
         .put("selected", true)
         .put("refusal", JSONObject.NULL)
         .put("arrived", mv != null && mv.walk == WaypointWalker.Result.ARRIVED);
      return PfTestHarness.body(checks, null, facts);
   }

   private JSONObject failClosed(List<JSONObject> checks, String refusal, String why) {
      checks.add(PfTestRunner.check("fixture", false, why));
      JSONObject facts = new JSONObject().put("refusal", refusal).put("selected", false).put("kind", this.kind.name());
      return PfTestHarness.body(checks, why, facts);
   }
}
