package haven.pathfinding;

import haven.Coord2d;
import haven.nav.NavPlan;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class NavReplayCoreTest {
   @Test
   void v1ParseRejectsOtherFormats() {
      JSONObject bad = new JSONObject().put("format", "nope").put("version", 1);
      Assertions.assertThrows(IllegalArgumentException.class, () -> NavReplay.parseObject(bad));
   }

   @Test
   void v1RoundTripPoints() {
      JSONObject doc = new JSONObject()
         .put("format", NavReplay.FORMAT)
         .put("version", 1)
         .put("raw_route", NavReplay.points(Collections.singletonList(Coord2d.of(1.25, 2.5))));
      JSONObject parsed = NavReplay.parseObject(doc);
      Assertions.assertEquals(1, parsed.getInt("version"));
      Assertions.assertEquals(1.25, NavReplay.coord2d(parsed.getJSONArray("raw_route").getJSONArray(0)).x, 1.0E-9);
   }

   @Test
   void planFromOccupancyUsesSharedPlanner() {
      Coord2d start = Coord2d.of(0.0, 0.0);
      Coord2d dest = Coord2d.of(0.0, 40.0);
      LocalPlanner.ClipResult clip = LocalPlanner.clipToHorizon(start, Collections.singletonList(dest));
      NavGrid grid = LocalPlanner.planGrid(start, clip.targets);
      boolean[] solid = new boolean[grid.w * grid.h];
      boolean[] dilated = new boolean[grid.w * grid.h];
      PlanningTrace live = new PlanningTrace();
      NavPlan livePlan = LocalPlanner.planCore(start, Collections.singletonList(dest), clip.targets, clip.clipped, true, 4.5, grid, solid, dilated, 0, live);
      OccupancyGrid occ = OccupancyGrid.capture(grid.origin, grid.w, grid.h, 2.75, solid, dilated, dilated, live.startCell, live.goalCell, live.freeGoal, Collections.emptyList());
      PlanningTrace replay = new PlanningTrace();
      NavPlan replayed = LocalPlanner.planFromOccupancy(start, dest, true, 4.5, occ, 0, replay);
      Assertions.assertEquals(livePlan.status, replayed.status);
      Assertions.assertEquals(livePlan.smoothedRoute.size(), replayed.smoothedRoute.size());
   }
}
