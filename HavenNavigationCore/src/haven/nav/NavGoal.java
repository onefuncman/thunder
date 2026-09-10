package haven.nav;

import haven.Coord2d;
import java.util.Collections;
import java.util.List;

public final class NavGoal {
   public enum Kind {
      POINT,
      AREA,
      INTERACTION,
      TRANSITION,
      EXPLORE_FRONTIER;
   }

   public final Kind kind;
   public final Coord2d position;
   public final List<Coord2d> region;
   public final String targetId;
   public final InteractionSpec interaction;

   public NavGoal(Kind kind, Coord2d position, List<Coord2d> region, String targetId) {
      this(kind, position, region, targetId, null);
   }

   public NavGoal(Kind kind, Coord2d position, List<Coord2d> region, String targetId, InteractionSpec interaction) {
      if (kind == null) {
         throw new IllegalArgumentException("kind");
      }
      this.kind = kind;
      this.position = position;
      this.region = region == null ? Collections.emptyList() : Collections.unmodifiableList(region);
      this.targetId = targetId;
      this.interaction = interaction;
   }

   public static NavGoal point(Coord2d position) {
      return new NavGoal(Kind.POINT, position, Collections.emptyList(), null);
   }

   public static NavGoal area(List<Coord2d> region) {
      return new NavGoal(Kind.AREA, region == null || region.isEmpty() ? null : (Coord2d) region.get(0), region, null);
   }

   public static NavGoal interaction(InteractionSpec spec) {
      if (spec == null) {
         throw new IllegalArgumentException("interaction");
      }
      return new NavGoal(Kind.INTERACTION, spec.origin, Collections.emptyList(), spec.targetId, spec);
   }

   public static NavGoal transition(String targetId) {
      return new NavGoal(Kind.TRANSITION, null, Collections.emptyList(), targetId);
   }

   public static NavGoal exploreFrontier(Coord2d hint) {
      return new NavGoal(Kind.EXPLORE_FRONTIER, hint, Collections.emptyList(), null);
   }
}
