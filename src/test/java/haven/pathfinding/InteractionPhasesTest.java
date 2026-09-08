package haven.pathfinding;

import haven.Coord2d;
import haven.nav.InteractionSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Interaction telemetry must always carry target identity, and the bounded
 * staging failure paths must be clean (no interaction, no stale clicks).
 */
class InteractionPhasesTest {
   private static final long DOOR_ID = 1844735939L;
   private static final Coord2d DOOR_RC = Coord2d.of(-10466.5, -10411.5);

   private static PrototypePathfinder.GobGeom doorGob(long id, Coord2d rc) {
      PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
      g.id = id;
      g.resid = "gfx/terobjs/arch/cellardoor";
      g.rc = rc;
      g.caveTransition = true;
      return g;
   }

   private static InteractionTarget door() {
      return InteractionTarget.of(doorGob(DOOR_ID, DOOR_RC), "transition:CELLAR_STAIRS", "state_changed", "cellar");
   }

   @Test
   void phaseRecordAlwaysCarriesTargetIdAndResource() {
      InteractionTarget t = door();
      List<Coord2d> staging = Collections.singletonList(Coord2d.of(-10480.0, -10420.0));
      JSONObject o = InteractionStaging.phaseRecord(
         InteractionStaging.PHASE_STAGING_POSE_SELECTED, t, Coord2d.of(-10490.0, -10425.0), staging,
         doorGob(DOOR_ID, DOOR_RC), 0, ""
      );
      Assertions.assertEquals(DOOR_ID, o.getLong("target_id"));
      Assertions.assertEquals("gfx/terobjs/arch/cellardoor", o.getString("target_resource"));
      Assertions.assertFalse(o.optString("target_resource", "").isEmpty(), "target never empty");
      Assertions.assertTrue(o.has("target_coord"));
      Assertions.assertTrue(o.has("staging_coord"));
      Assertions.assertTrue(o.has("player"));
      Assertions.assertEquals(0, o.getInt("retry"));
      Assertions.assertEquals("STAGING_POSE_SELECTED", o.getString("phase"));
   }

   @Test
   void phaseEvidenceKeepsIdentityEvenWithoutPose() {
      InteractionTarget t = door();
      JSONObject o = InteractScenario.phaseEvidence(
         "NO_INTERACTION_POSE", t, null, null, Coord2d.of(-10490.0, -10425.0),
         Collections.singletonList(Coord2d.of(-10480.0, -10420.0)), "", "", "0", "NO_INTERACTION_POSE"
      );
      Assertions.assertEquals(DOOR_ID, o.getLong("target_id"));
      Assertions.assertEquals("gfx/terobjs/arch/cellardoor", o.getString("target_resource"));
      Assertions.assertEquals("NO_INTERACTION_POSE", o.getString("phase"));
      Assertions.assertTrue(o.has("reject_counts"), "rejection reasons recorded");
      Assertions.assertTrue(o.has("target_coord"));
      Assertions.assertTrue(o.has("staging_path"));
   }

   @Test
   void boundedFailureReasonsExist() {
      Assertions.assertEquals("STAGING_UNREACHABLE", InteractionStaging.STAGING_UNREACHABLE);
      Assertions.assertEquals("TARGET_DISAPPEARED", InteractionStaging.TARGET_DISAPPEARED);
      Assertions.assertEquals("TARGET_CHANGED", InteractionStaging.TARGET_CHANGED);
      Assertions.assertEquals("LOCAL_GEOMETRY_UNAVAILABLE", InteractionStaging.LOCAL_GEOMETRY_UNAVAILABLE);
      Assertions.assertEquals("STAGING_UNREACHABLE", StagingPlanner.STAGING_UNREACHABLE);
   }

   @Test
   void stagingFailsCleanlyWhenUnobservedTargetHasNoWalkableProgress() throws Exception {
      final PrototypePathfinder.Scene scene = new PrototypePathfinder.Scene();
      scene.player = Coord2d.of(0.0, 0.0);
      scene.origin = Coord2d.of(-99.0, -99.0);
      scene.w = 72;
      scene.h = 72;
      byte[] occ = new byte[72 * 72];
      for (int i = 0; i < occ.length; i++) {
         occ[i] = OccupancyGrid.SOLID;
      }
      scene.occupancy = new PathfinderLog.Occupancy(
         scene.origin, 72, 72, 2.75, occ, null, null, null, null
      );
      // No gobs in the scene and the target is unobserved: staging hops toward
      // its last position via selectToward, but with all-SOLID occupancy there
      // is no walkable progress, so staging fails cleanly.
      InteractionStaging.Result r = InteractionStaging.stage(
         null, null, null, new InteractionStaging.SceneSupplier() {
            @Override
            public PrototypePathfinder.Scene get() {
               return scene;
            }
         }, door(), new InteractionStaging.SpecMaker() {
            @Override
            public InteractionSpec spec(PrototypePathfinder.GobGeom g, Coord2d near) {
               return InteractionAdapter.fromFootprint(
                  "door", g.rc, Coord2d.of(2.0, 2.0), InteractionSpec.ALL_SIDES, 1.0, 35.0, 1, null,
                  InteractionVerifier.STATE_CHANGED
               );
            }
         }, null
      );
      Assertions.assertFalse(r.staged());
      Assertions.assertEquals(InteractionStaging.STAGING_UNREACHABLE, r.outcome);
   }

   @Test
   void stagingFailsCleanlyWhenUnobservedTargetHasNoLastPosition() throws Exception {
      final PrototypePathfinder.Scene scene = new PrototypePathfinder.Scene();
      scene.player = Coord2d.of(0.0, 0.0);
      scene.origin = Coord2d.of(-99.0, -99.0);
      scene.w = 72;
      scene.h = 72;
      scene.occupancy = new PathfinderLog.Occupancy(
         scene.origin, 72, 72, 2.75, new byte[72 * 72], null, null, null, null
      );
      // No gobs in the scene and the identity has no last position: staging
      // cannot even pick a hop direction, so it fails as TARGET_DISAPPEARED.
      InteractionTarget identity = InteractionTarget.of(doorGob(DOOR_ID, null), "transition:CELLAR_STAIRS", "state_changed", "cellar");
      Assertions.assertNull(identity.lastRc);
      InteractionStaging.Result r = InteractionStaging.stage(
         null, null, null, new InteractionStaging.SceneSupplier() {
            @Override
            public PrototypePathfinder.Scene get() {
               return scene;
            }
         }, identity, new InteractionStaging.SpecMaker() {
            @Override
            public InteractionSpec spec(PrototypePathfinder.GobGeom g, Coord2d near) {
               return InteractionAdapter.fromFootprint(
                  "door", g.rc, Coord2d.of(2.0, 2.0), InteractionSpec.ALL_SIDES, 1.0, 35.0, 1, null,
                  InteractionVerifier.STATE_CHANGED
               );
            }
         }, null
      );
      Assertions.assertFalse(r.staged());
      Assertions.assertEquals(InteractionStaging.TARGET_DISAPPEARED, r.outcome);
   }

   @Test
   void stagingFailsCleanlyWhenNoWalkableStagingPointExists() throws Exception {
      Coord2d player = Coord2d.of(0.0, 0.0);
      Coord2d doorRc = Coord2d.of(80.0, 0.0);
      PrototypePathfinder.GobGeom g = doorGob(DOOR_ID, doorRc);
      final PrototypePathfinder.Scene scene = new PrototypePathfinder.Scene();
      scene.player = player;
      scene.origin = player.sub(99.0, 99.0);
      scene.w = 72;
      scene.h = 72;
      byte[] occ = new byte[72 * 72];
      // Seal a region wide enough to cover every staging probe radius
      // (standoff .. maxDist+slack) around the target.
      int cx = (int) Math.floor((doorRc.x - scene.origin.x) / 2.75);
      int cy = (int) Math.floor((doorRc.y - scene.origin.y) / 2.75);
      for (int x = cx - 18; x <= cx + 18; x++) {
         for (int y = cy - 18; y <= cy + 18; y++) {
            occ[y * 72 + x] = OccupancyGrid.SOLID;
         }
      }
      scene.occupancy = new PathfinderLog.Occupancy(scene.origin, 72, 72, 2.75, occ, null, null, null, null);
      scene.gobs = new ArrayList<PrototypePathfinder.GobGeom>();
      scene.gobs.add(g);
      final PrototypePathfinder.GobGeom live = g;
      InteractionStaging.Result r = InteractionStaging.stage(
         null, null, null, new InteractionStaging.SceneSupplier() {
            @Override
            public PrototypePathfinder.Scene get() {
               return scene;
            }
         }, door(), new InteractionStaging.SpecMaker() {
            @Override
            public InteractionSpec spec(PrototypePathfinder.GobGeom gg, Coord2d near) {
               return InteractionAdapter.fromFootprint(
                  "door", live.rc, Coord2d.of(2.0, 2.0), InteractionSpec.ALL_SIDES, 1.0, 35.0, 1, null,
                  InteractionVerifier.STATE_CHANGED
               );
            }
         }, null
      );
      Assertions.assertFalse(r.staged());
      Assertions.assertEquals(InteractionStaging.STAGING_UNREACHABLE, r.outcome);
   }
}
