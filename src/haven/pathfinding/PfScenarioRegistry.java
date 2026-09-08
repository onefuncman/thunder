package haven.pathfinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Allowlisted pf-test scenarios. The localhost runner may start only these
 * named scenarios; arbitrary remote movement commands are not registered.
 */
final class PfScenarioRegistry {
   private static final Map<String, PfTestRunner.Scenario> SCENARIOS = new LinkedHashMap<>();

   static {
      register(new ObserveScenario());
      register(new BasementCabinetIdentifyScenario());
      register(new MoveToMarkerScenario());
      register(new MoveToAutoOpenGroundScenario());
      register(new MoveToAutoObstacleCorridorScenario());
      register(new MoveToAutoKnownLongLegScenario());
      register(new MoveToAutoCaveTransitionApproachScenario());
      register(new CrossCellarDoorScenario());
      register(new CrossCellarStairsScenario());
      register(new CrossMineholeScenario());
      register(new NavigationTestSpotScenario(NavigationTestSpotSelector.Profile.OPEN_GROUND));
      register(new NavigationTestSpotScenario(NavigationTestSpotSelector.Profile.LOCAL_OBSTACLE_OR_CORRIDOR));
      register(new NavigationTestSpotScenario(NavigationTestSpotSelector.Profile.KNOWN_MAP_LONG_LEG));
      register(new SelectBoulderApproachScenario());
      register(new SelectCaveTransitionApproachScenario());
      register(new SelectDoorGateApproachScenario());
      register(new SelectWaterlineApproachScenario());
      for (SurfaceTravelScenario.Kind k : SurfaceTravelScenario.Kind.values()) {
         register(new SurfaceTravelScenario(k));
      }
      for (InteractScenario.Kind k : InteractScenario.Kind.values()) {
         register(new InteractScenario(k));
      }
      for (TransitionScenario.Kind k : TransitionScenario.Kind.values()) {
         register(new TransitionScenario(k));
      }
      for (CriticalRouteScenario.Kind k : CriticalRouteScenario.Kind.values()) {
         register(new CriticalRouteScenario(k));
      }
      register(new RecordedRouteScenario());
   }

   private PfScenarioRegistry() {
   }

   private static void register(PfTestRunner.Scenario scenario) {
      String name = scenario.name();
      if (name == null || name.trim().isEmpty()) {
         throw new IllegalStateException("scenario with empty name");
      }
      if (SCENARIOS.put(name, scenario) != null) {
         throw new IllegalStateException("duplicate scenario name: " + name);
      }
   }

   static PfTestRunner.Scenario get(String name) {
      return name == null ? null : SCENARIOS.get(name);
   }

   static List<String> names() {
      return Collections.unmodifiableList(new ArrayList<>(SCENARIOS.keySet()));
   }

   static boolean known(String name) {
      return name != null && SCENARIOS.containsKey(name);
   }
}
