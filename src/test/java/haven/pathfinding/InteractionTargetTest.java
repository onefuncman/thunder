package haven.pathfinding;

import haven.Coord2d;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Target identity must survive staging: ID, resource, and stable re-resolution. */
class InteractionTargetTest {
   private static PrototypePathfinder.GobGeom gob(long id, String resid, Coord2d rc, boolean caveTransition) {
      PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
      g.id = id;
      g.resid = resid;
      g.rc = rc;
      g.caveTransition = caveTransition;
      return g;
   }

   private static PrototypePathfinder.Scene scene(PrototypePathfinder.GobGeom... gobs) {
      PrototypePathfinder.Scene s = new PrototypePathfinder.Scene();
      s.gobs.addAll(Arrays.asList(gobs));
      return s;
   }

   private static InteractionTarget cellarDoor(long id, Coord2d rc) {
      return InteractionTarget.of(
         gob(id, "gfx/terobjs/arch/cellardoor", rc, true), "transition:CELLAR_STAIRS", "state_changed", ""
      );
   }

   @Test
   void identityCapturesIdResourceAndPosition() {
      InteractionTarget t = cellarDoor(1844735939L, Coord2d.of(-10466.5, -10411.5));
      Assertions.assertEquals(1844735939L, t.gobId);
      Assertions.assertEquals("gfx/terobjs/arch/cellardoor", t.resid);
      Assertions.assertEquals(Coord2d.of(-10466.5, -10411.5), t.lastRc);
      Assertions.assertTrue(t.label().contains("cellardoor") && t.label().contains("#1844735939"));
   }

   @Test
   void resolvesSameGobById() {
      PrototypePathfinder.GobGeom live = gob(1844735939L, "gfx/terobjs/arch/cellardoor", Coord2d.of(-10466.5, -10411.5), true);
      InteractionTarget t = InteractionTarget.of(live, "transition:CELLAR_STAIRS", "state_changed", "");
      PrototypePathfinder.GobGeom found = t.resolveIn(scene(gob(1L, "gfx/terobjs/studydesk", Coord2d.of(0.0, 0.0), false), live));
      Assertions.assertNotNull(found);
      Assertions.assertEquals(1844735939L, found.id);
      Assertions.assertTrue(t.resolvedById);
   }

   @Test
   void idChangeAfterReloadResolvesByStableIdentity() {
      InteractionTarget t = cellarDoor(1844735939L, Coord2d.of(-10466.5, -10411.5));
      PrototypePathfinder.GobGeom reloaded = gob(990001L, "gfx/terobjs/arch/cellardoor", Coord2d.of(-10466.0, -10411.0), true);
      PrototypePathfinder.GobGeom found = t.resolveIn(scene(reloaded));
      Assertions.assertNotNull(found, "stable identity survives surface reload");
      Assertions.assertEquals(990001L, found.id);
      Assertions.assertTrue(t.sameIdentity(reloaded));
      Assertions.assertTrue(t.compatibleWith(reloaded));
   }

   @Test
   void differentResourceAtSameLocationIsRejected() {
      InteractionTarget t = cellarDoor(1844735939L, Coord2d.of(-10466.5, -10411.5));
      PrototypePathfinder.GobGeom other = gob(990002L, "gfx/terobjs/arch/polegate", Coord2d.of(-10466.0, -10411.0), true);
      Assertions.assertNull(t.resolveIn(scene(other)), "never interact with an arbitrary nearby object");
      Assertions.assertFalse(t.compatibleWith(other));
   }

   @Test
   void sameResourceFarAwayIsRejected() {
      InteractionTarget t = cellarDoor(1844735939L, Coord2d.of(-10466.5, -10411.5));
      PrototypePathfinder.GobGeom distant = gob(990003L, "gfx/terobjs/arch/cellardoor", Coord2d.of(-10000.0, -10000.0), false);
      Assertions.assertNull(t.resolveIn(scene(distant)), "fallback is location-stable, not global");
   }

   @Test
   void classificationMismatchFailsCompatibility() {
      InteractionTarget t = cellarDoor(1844735939L, Coord2d.of(-10466.5, -10411.5));
      PrototypePathfinder.GobGeom unclassified = gob(990004L, "gfx/terobjs/arch/cellardoor", Coord2d.of(-10466.0, -10411.0), false);
      // Same resid but no cave-transition classification: expected TARGET_CHANGED.
      Assertions.assertFalse(t.sameIdentity(unclassified));
   }

   @Test
   void emptySceneYieldsTargetDisappeared() {
      InteractionTarget t = cellarDoor(1844735939L, Coord2d.of(-10466.5, -10411.5));
      Assertions.assertNull(t.resolveIn(scene()), "clean TARGET_DISAPPEARED path");
      List<PrototypePathfinder.GobGeom> empty = new ArrayList<PrototypePathfinder.GobGeom>();
      Assertions.assertNull(t.resolveIn(empty));
   }

   @Test
   void movedDetectionUsesLastObservedPosition() {
      InteractionTarget t = cellarDoor(1844735939L, Coord2d.of(-10466.5, -10411.5));
      Assertions.assertFalse(t.moved(gob(1844735939L, "gfx/terobjs/arch/cellardoor", Coord2d.of(-10466.0, -10411.0), false)));
      Assertions.assertTrue(t.moved(gob(1844735939L, "gfx/terobjs/arch/cellardoor", Coord2d.of(-10420.0, -10411.5), false)));
      Assertions.assertTrue(t.moved(null));
   }
}
