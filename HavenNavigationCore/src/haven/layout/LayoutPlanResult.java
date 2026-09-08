package haven.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a layout plan. {@code PLANNED} means the requested count was
 * reached; {@code PARTIAL} means some placements were accepted but not enough;
 * {@code FAILED} means none could be accepted. {@code reason} is empty for
 * {@code PLANNED} and otherwise names the dominant rejection cause (see the
 * reason constants on {@link LayoutPlanner}).
 */
public final class LayoutPlanResult {
   public enum Status {
      PLANNED,
      PARTIAL,
      FAILED
   }

   public final Status status;
   /** Accepted placements in acceptance order (row-major enumeration). */
   public final List<LayoutPlacement> placements;
   /** Empty when PLANNED; dominant rejection reason otherwise. */
   public final String reason;
   /** Per-candidate rejection reason counts during enumeration. */
   public final Map<String, Integer> rejectCounts;

   public LayoutPlanResult(Status status, List<LayoutPlacement> placements, String reason, Map<String, Integer> rejectCounts) {
      this.status = status;
      this.placements = placements == null
         ? Collections.<LayoutPlacement>emptyList()
         : Collections.unmodifiableList(new ArrayList<LayoutPlacement>(placements));
      this.reason = reason == null ? "" : reason;
      this.rejectCounts = rejectCounts == null
         ? Collections.<String, Integer>emptyMap()
         : Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(rejectCounts));
   }

   @Override
   public String toString() {
      return "LayoutPlanResult{" + this.status + " placements=" + this.placements.size() + " reason='" + this.reason + "'}";
   }
}
