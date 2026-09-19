package haven.nav;

import haven.Coord2d;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NavPlan {
   public final NavPlanStatus status;
   public final List<Coord2d> rawRoute;
   public final List<Coord2d> smoothedRoute;
   public final Coord2d selectedGoal;
   public final double cost;
   public final int expanded;
   public final int obstacles;
   public final boolean complete;
   public final boolean snapped;
   public final String reason;

   public NavPlan(
      NavPlanStatus status,
      List<Coord2d> rawRoute,
      List<Coord2d> smoothedRoute,
      Coord2d selectedGoal,
      double cost,
      int expanded,
      int obstacles,
      boolean complete,
      boolean snapped,
      String reason
   ) {
      this.status = status == null ? NavPlanStatus.FAILED : status;
      this.rawRoute = copy(rawRoute);
      this.smoothedRoute = smoothedRoute == null ? this.rawRoute : copy(smoothedRoute);
      this.selectedGoal = selectedGoal;
      this.cost = cost;
      this.expanded = expanded;
      this.obstacles = obstacles;
      this.complete = complete;
      this.snapped = snapped;
      this.reason = reason == null ? "" : reason;
   }

   public static NavPlan failed(int obstacles, String reason) {
      return create(NavPlanStatus.FAILED, Collections.emptyList(), Collections.emptyList(), false, false, 0, obstacles, reason);
   }

   public static NavPlan create(
      NavPlanStatus status,
      List<Coord2d> raw,
      List<Coord2d> smoothed,
      boolean complete,
      boolean snapped,
      int expanded,
      int obstacles,
      String reason
   ) {
      List<Coord2d> sm = smoothed == null ? Collections.emptyList() : smoothed;
      Coord2d selected = sm.isEmpty() ? null : (Coord2d) sm.get(sm.size() - 1);
      double cost = 0.0;
      for (int i = 1; i < sm.size(); i++) {
         Coord2d a = (Coord2d) sm.get(i - 1);
         Coord2d b = (Coord2d) sm.get(i);
         if (a != null && b != null) {
            cost += a.dist(b);
         }
      }
      return new NavPlan(status, raw, sm, selected, cost, expanded, obstacles, complete, snapped, reason);
   }

   private static List<Coord2d> copy(List<Coord2d> in) {
      return in == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<Coord2d>(in));
   }
}
