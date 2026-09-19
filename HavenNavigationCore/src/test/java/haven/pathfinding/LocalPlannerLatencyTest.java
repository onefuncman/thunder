package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.NavPlan;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Phase 6: local planCore latency on this machine, excluding network/ticks.
 */
public class LocalPlannerLatencyTest {
   private static final int WARM = 8;
   private static final int N = 40;

   @Test
   void openGroundAndDetourP95Below100ms() {
      long[] samples = new long[N];
      OccupancyGrid open = grid(false);
      OccupancyGrid wall = grid(true);
      for (int i = 0; i < WARM; i++) {
         plan(open);
         plan(wall);
      }
      for (int i = 0; i < N; i++) {
         OccupancyGrid g = (i & 1) == 0 ? open : wall;
         long t0 = System.nanoTime();
         NavPlan p = plan(g);
         samples[i] = (System.nanoTime() - t0) / 1_000_000L;
         Assertions.assertNotNull(p);
      }
      Arrays.sort(samples);
      long p50 = samples[N / 2];
      long p95 = samples[(int) Math.ceil(0.95 * N) - 1];
      long max = samples[N - 1];
      Assertions.assertTrue(p95 < 100L, "p50=" + p50 + " p95=" + p95 + " max=" + max);
      System.out.println("planCore_ms p50=" + p50 + " p95=" + p95 + " max=" + max);
   }

   private static NavPlan plan(OccupancyGrid occ) {
      return LocalPlanner.planFromOccupancy(
         occ.origin.add(5.5, 5.5), occ.origin.add(occ.w * 2.75 - 8.0, occ.h * 2.75 * 0.5), true, 4.5, occ, 0, new PlanningTrace()
      );
   }

   private static OccupancyGrid grid(boolean wall) {
      int w = 72;
      int h = 72;
      boolean[] solid = new boolean[w * h];
      boolean[] dilated = new boolean[w * h];
      if (wall) {
         for (int y = 8; y < h - 8; y++) {
            solid[y * w + 36] = true;
            dilated[y * w + 36] = true;
            dilated[y * w + 35] = true;
            dilated[y * w + 37] = true;
         }
         solid[40 * w + 36] = false;
         dilated[40 * w + 36] = false;
      }
      return OccupancyGrid.capture(
         Coord2d.of(0.0, 0.0), w, h, 2.75, solid, dilated, dilated, Coord.of(2, 36), Coord.of(68, 36), Coord.of(68, 36), Collections.emptyList()
      );
   }
}
