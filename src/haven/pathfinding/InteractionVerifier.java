package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.nav.InteractionSpec;

/**
 * Authoritative interaction confirmation. Game/UI details are interpreted
 * here; the shared core only stores the expected-result descriptor.
 */
public final class InteractionVerifier {
   public static final String TARGET_GONE = "target_gone";
   public static final String INVENTORY_CHANGED = "inventory_changed";
   public static final String WINDOW_OPENED = "window_opened";
   public static final String STATE_CHANGED = "state_changed";
   public static final int MAX_RETRY = 2;
   public static final long WAIT_MS = 2500L;

   private InteractionVerifier() {
   }

   public static boolean mayInteract(boolean arrivedConfirmed) {
      return arrivedConfirmed;
   }

   public static boolean arrivedConfirmed(boolean idle, Coord2d pos, Coord2d pose, double eps) {
      return idle && pos != null && pose != null && pos.dist(pose) <= eps;
   }

   public static boolean poseStillValid(
      Coord2d pos,
      Coord2d pose,
      double eps,
      InteractionSpec spec,
      Coord2d targetOrigin,
      Coord2d targetHalf,
      boolean targetPresent
   ) {
      return poseStillValid(pos, pose, eps, spec, targetOrigin, targetHalf, targetPresent, null);
   }

   public static boolean poseStillValid(
      Coord2d pos,
      Coord2d pose,
      double eps,
      InteractionSpec spec,
      Coord2d targetOrigin,
      Coord2d targetHalf,
      boolean targetPresent,
      OccupancyGrid occ
   ) {
      return poseStillValid(pos, pose, eps, spec, targetOrigin, targetHalf, targetPresent, occ, null, null);
   }

   public static boolean poseStillValid(
      Coord2d pos,
      Coord2d pose,
      double eps,
      InteractionSpec spec,
      Coord2d targetOrigin,
      Coord2d targetHalf,
      boolean targetPresent,
      OccupancyGrid occ,
      java.util.List<Coord2d[]> solids,
      java.util.List<Coord2d[]> playerBody
   ) {
      if (!targetPresent || pos == null || pose == null || spec == null) {
         return false;
      }
      if (pos.dist(pose) > eps) {
         return false;
      }
      if (targetOrigin != null && spec.origin != null && targetOrigin.dist(spec.origin) > 2.75) {
         return false;
      }
      if (occ == null) {
         return true;
      }
      double dist = InteractionGoals.distanceToFootprint(pos, spec);
      if (dist + 1.0E-6 < spec.minDist || dist - 1.0E-6 > spec.maxDist) {
         return false;
      }
      InteractionGoals.Geometry geom = solids == null || solids.isEmpty()
         ? null
         : new InteractionGoals.Geometry(solids, playerBody);
      if (geom != null && geom.enabled()) {
         return InteractionGoals.poseFits(pose, spec, occ, geom);
      }
      Coord cell = occ.cellOf(pos);
      if (cell == null) {
         return false;
      }
      if (occ.at(cell.x, cell.y) == OccupancyGrid.SOLID) {
         return false;
      }
      if (InteractionGoals.overlapsFootprint(pos, spec)) {
         return false;
      }
      return InteractionGoals.losClear(pos, spec, occ, null);
   }

   public static boolean confirmed(
      String expected,
      boolean targetPresent,
      boolean inventoryChanged,
      boolean windowOpened,
      boolean stateChanged
   ) {
      if (WINDOW_OPENED.equals(expected)) {
         return windowOpened;
      }
      if (INVENTORY_CHANGED.equals(expected)) {
         return inventoryChanged;
      }
      if (TARGET_GONE.equals(expected)) {
         return !targetPresent || inventoryChanged;
      }
      if (STATE_CHANGED.equals(expected)) {
         return stateChanged || !targetPresent || inventoryChanged;
      }
      return false;
   }

   public static boolean retryAllowed(int attempt) {
      return attempt >= 0 && attempt < MAX_RETRY;
   }

   public static boolean timedOut(long elapsedMs, long budgetMs) {
      return elapsedMs >= budgetMs;
   }
}
