package haven.pathfinding;

import auto.Bot;
import haven.Coord;
import haven.Coord2d;
import haven.nav.GraphNode;
import haven.pathfinding.TransitionScenario.PendingInteraction;
import haven.pathfinding.WaypointGate.Observation;
import haven.pathfinding.WaypointWalker.Env;
import haven.pathfinding.WaypointWalker.Listener;
import haven.pathfinding.WaypointWalker.Params;
import haven.pathfinding.WaypointWalker.Result;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the cellar→upstairs transition reliability fixes:
 * only authoritative signals (segment identity change or teleport) complete a
 * TOPOLOGY/STATE transition wait, occupancy is invalidated on landing so the
 * next leg rebuilds geometry, and the interaction click is issued at most once
 * while a transition is pending on the same fixture.
 */
public class TransitionAuthTest {
   private static final GraphNode CELLAR = GraphNode.of("haven", "aa", "cellar", "5,5");
   private static final GraphNode UPSTAIRS = GraphNode.of("haven", "bb", "house", "5,5");
   private static final List<Coord2d> THREE_WP = List.of(
      Coord2d.of(0.0, 0.0), Coord2d.of(10.0, 0.0), Coord2d.of(20.0, 0.0)
   );

   @BeforeEach
   void resetLog() {
      PathfinderLog.resetRun();
      PathfinderLog.invalidateOccupancy();
   }

   // --- FIX 1: authoritative-only relocation ---------------------------------

   @Test
   void topologyAuthRequiresSegmentChangeOrTeleport() {
      Assertions.assertTrue(TransitionScenario.authoritativeRelocated(true, false, false, TransitionMachine.Auth.TOPOLOGY));
      Assertions.assertTrue(TransitionScenario.authoritativeRelocated(false, true, false, TransitionMachine.Auth.TOPOLOGY));
      // High goneTicks (fixture gob unobserved) alone must never succeed.
      Assertions.assertFalse(TransitionScenario.authoritativeRelocated(false, false, true, TransitionMachine.Auth.TOPOLOGY));
   }

   @Test
   void stateAuthAlsoRefusesGoneTicksAlone() {
      for (TransitionMachine.Auth auth : TransitionMachine.Auth.values()) {
         Assertions.assertFalse(
            TransitionScenario.authoritativeRelocated(false, false, true, auth),
            "goneTicks alone must not relocate for " + auth
         );
      }
      Assertions.assertTrue(TransitionScenario.authoritativeRelocated(true, false, true, TransitionMachine.Auth.STATE));
      Assertions.assertTrue(TransitionScenario.authoritativeRelocated(false, true, true, TransitionMachine.Auth.STATE));
   }

   /** Delayed surface update: segment stays equal to source across many ticks while goneTicks grows. */
   @Test
   void delayedSurfaceUpdateNeverSucceedsUntilSegmentChanges() {
      GraphNode source = CELLAR;
      for (int goneTicks = 0; goneTicks < 50; goneTicks++) {
         boolean floorChanged = TransitionAdapter.topologyChanged(source, CELLAR);
         boolean resolved = TransitionScenario.authoritativeRelocated(
            floorChanged, false, goneTicks >= 5, TransitionMachine.Auth.TOPOLOGY
         );
         Assertions.assertFalse(resolved, "tick " + goneTicks + ": segment unchanged must not resolve");
      }
      // Once the new segment is reported, the very next tick resolves.
      boolean resolvedAfterSegment = TransitionScenario.authoritativeRelocated(
         TransitionAdapter.topologyChanged(source, UPSTAIRS), false, false, TransitionMachine.Auth.TOPOLOGY
      );
      Assertions.assertTrue(resolvedAfterSegment, "segment change resolves authoritatively");
   }

   @Test
   void teleportBeyondFiftyFiveTilesResolvesEvenWithoutSegmentChange() {
      Coord2d origin = Coord2d.of(100.0, 100.0);
      Coord2d near = Coord2d.of(130.0, 100.0);
      Coord2d far = Coord2d.of(160.0, 100.0);
      Assertions.assertFalse(TransitionScenario.authoritativeRelocated(
         false, origin.dist(near) > 55.0, false, TransitionMachine.Auth.TOPOLOGY
      ));
      Assertions.assertTrue(TransitionScenario.authoritativeRelocated(
         false, origin.dist(far) > 55.0, false, TransitionMachine.Auth.TOPOLOGY
      ));
   }

   /** Fail-closed timeout: no authoritative change within the window. */
   @Test
   void failClosedTimeoutReportsNoRelocation() {
      Assertions.assertFalse(TransitionScenario.authoritativeRelocated(false, false, false, TransitionMachine.Auth.TOPOLOGY));
      Assertions.assertFalse(TransitionScenario.authoritativeRelocated(false, false, true, TransitionMachine.Auth.TOPOLOGY));
      Assertions.assertFalse(TransitionScenario.authoritativeRelocated(false, false, false, TransitionMachine.Auth.STATE));
   }

   @Test
   void topologyChangedDetectsSegmentIdentityOnly() {
      Assertions.assertFalse(TransitionAdapter.topologyChanged(CELLAR, CELLAR), "same segment is not a floor change");
      Assertions.assertTrue(TransitionAdapter.topologyChanged(CELLAR, UPSTAIRS), "segment change is authoritative");
      GraphNode sameSegOtherArea = GraphNode.of("haven", "aa", "cellar", "9,9");
      Assertions.assertFalse(TransitionAdapter.topologyChanged(CELLAR, sameSegOtherArea), "area drift on one floor is not a transition");
   }

   // --- FIX 2: occupancy invalidation ----------------------------------------

   @Test
   void invalidateOccupancyClearsCacheAndExactGeometry() {
      PathfinderLog.Occupancy occ = PathfinderLog.Occupancy.capture(
         Coord2d.of(0.0, 0.0), 4, 3, 2.75, new boolean[12], new boolean[12], new boolean[12],
         Coord.of(0, 0), Coord.of(3, 2), Coord.of(3, 2), List.of()
      );
      PathfinderLog.recordOccupancy(occ);
      List<Coord2d[]> solids = new ArrayList<>();
      solids.add(new Coord2d[] {Coord2d.of(1.0, 1.0)});
      List<Coord2d[]> body = new ArrayList<>();
      body.add(new Coord2d[] {Coord2d.of(2.0, 2.0)});
      PathfinderLog.recordExactGeometry(solids, body);
      Assertions.assertNotNull(PathfinderLog.lastOccupancy());
      Assertions.assertFalse(PathfinderLog.lastSolids().isEmpty());
      Assertions.assertFalse(PathfinderLog.lastPlayerBody().isEmpty());

      PathfinderLog.invalidateOccupancy();

      Assertions.assertNull(PathfinderLog.lastOccupancy(), "occupancy cache must be null after invalidation");
      Assertions.assertTrue(PathfinderLog.lastSolids().isEmpty(), "exact solids must be cleared");
      Assertions.assertTrue(PathfinderLog.lastPlayerBody().isEmpty(), "exact player body must be cleared");
   }

   /**
    * After invalidation the walker must rebuild occupancy via
    * Env.refreshOccupancy() rather than handing a null grid to the
    * SurfaceController (the mock records the rebuilt grid so the next read of
    * PathfinderLog.lastOccupancy() sees it, mirroring liveEnv).
    */
   @Test
   void walkerRebuildsOccupancyAfterInvalidation() throws InterruptedException {
      PathfinderLog.Occupancy rebuilt = PathfinderLog.Occupancy.capture(
         Coord2d.of(0.0, 0.0), 4, 4, 2.75, new boolean[16], new boolean[16], new boolean[16],
         Coord.of(0, 0), Coord.of(3, 3), Coord.of(3, 3), List.of()
      );
      CacheEnv env = new CacheEnv();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 2.0, 0.0, true);
      env.obs(800L, 10.0, 0.0, true);
      env.obs(1500L, 16.0, 0.0, true);
      env.obs(2000L, 20.0, 0.0, true);
      env.obs(2500L, 20.0, 0.0, false);
      env.rebuilt = rebuilt;

      // Cache is null (invalidated after landing); refreshOccupancy() records the rebuilt grid.
      Result r = WaypointWalker.execute(env, bot(), THREE_WP, 3, 20000L, Params.DEFAULT, noop());
      Assertions.assertEquals(Result.ARRIVED, r);
      Assertions.assertTrue(env.refreshCalls >= 1, "walker must rebuild occupancy when the cache is invalidated");
      Assertions.assertSame(rebuilt, PathfinderLog.lastOccupancy(), "rebuilt grid is cached for the next leg");
   }

   @Test
   void walkerUsesValidCacheWithoutRebuild() throws InterruptedException {
      PathfinderLog.Occupancy cached = PathfinderLog.Occupancy.capture(
         Coord2d.of(0.0, 0.0), 4, 4, 2.75, new boolean[16], new boolean[16], new boolean[16],
         Coord.of(0, 0), Coord.of(3, 3), Coord.of(3, 3), List.of()
      );
      PathfinderLog.recordOccupancy(cached);
      CacheEnv env = new CacheEnv();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 2.0, 0.0, true);
      env.obs(800L, 10.0, 0.0, true);
      env.obs(1500L, 16.0, 0.0, true);
      env.obs(2000L, 20.0, 0.0, true);
      env.obs(2500L, 20.0, 0.0, false);

      Result r = WaypointWalker.execute(env, bot(), THREE_WP, 3, 20000L, Params.DEFAULT, noop());
      Assertions.assertEquals(Result.ARRIVED, r);
      Assertions.assertEquals(0, env.refreshCalls, "a valid cache must not force a rebuild");
   }

   // --- FIX 3: duplicate interaction click guard ------------------------------

   @Test
   void interactionClickIssuedAtMostOnceWhilePending() {
      PendingInteraction pending = new PendingInteraction();
      long t0 = 1000L;
      Assertions.assertTrue(pending.begin(42L, t0), "first execute clicks");
      Assertions.assertTrue(pending.pending(42L));
      // Re-entered execute for the same fixture while the transition is pending.
      Assertions.assertFalse(pending.begin(42L, t0 + 100L), "second execute must not click while pending");
      Assertions.assertFalse(pending.begin(42L, t0 + 30000L), "still inside the pending window");
      // Authoritative resolution clears the marker so a later interaction clicks again.
      pending.resolve(42L);
      Assertions.assertFalse(pending.pending(42L));
      Assertions.assertTrue(pending.begin(42L, t0 + 31000L), "resolved transition allows a fresh click");
   }

   @Test
   void stalePendingMarkerExpiresForLostClicks() {
      PendingInteraction pending = new PendingInteraction();
      Assertions.assertTrue(pending.begin(7L, 0L));
      // Beyond the auth deadline + slack the marker is stale: a re-entry may
      // re-click (the click was genuinely lost), preventing a permanent lock.
      Assertions.assertTrue(pending.begin(7L, 45000L));
   }

   @Test
   void differentFixtureSupersedesPendingMarker() {
      PendingInteraction pending = new PendingInteraction();
      Assertions.assertTrue(pending.begin(1L, 0L));
      Assertions.assertTrue(pending.begin(2L, 10L), "a different fixture replaces the pending marker");
      Assertions.assertFalse(pending.pending(1L));
      Assertions.assertTrue(pending.pending(2L));
      pending.resolve(1L);
      Assertions.assertTrue(pending.pending(2L), "resolve only clears the matching fixture");
   }

   /**
    * Drive WaypointWalker.execute twice for the same goal/route with a mock Env
    * while mirroring TransitionScenario's guard: the fixture interaction click
    * is issued at most once while the transition is pending; walker re-entry
    * itself only issues movement clicks, never the interaction click.
    */
   @Test
   void repeatedWalkerRunsClickFixtureAtMostOnceWhilePending() throws InterruptedException {
      PendingInteraction pending = new PendingInteraction();
      CacheEnv env = new CacheEnv();
      env.obs(0L, 0.0, 0.0, false);
      env.obs(100L, 2.0, 0.0, true);
      env.obs(800L, 10.0, 0.0, true);
      env.obs(1500L, 16.0, 0.0, true);
      env.obs(2000L, 20.0, 0.0, true);
      env.obs(2500L, 20.0, 0.0, false);
      Listener rec = noop();
      long t0 = 10_000L;

      // Attempt 1: walk to the fixture and click it (transition now pending).
      Assertions.assertEquals(Result.ARRIVED, WaypointWalker.execute(env, bot(), THREE_WP, 3, 20000L, Params.DEFAULT, rec));
      Assertions.assertTrue(pending.begin(42L, t0), "first execute issues the interaction click");
      int movementClicksAfterFirst = env.clicks.size();

      // Attempt 2 (spurious-timeout retry) re-observes the same scene and re-walks,
      // but must NOT re-click the fixture.
      env.resetWalk();
      Assertions.assertEquals(Result.ARRIVED, WaypointWalker.execute(env, bot(), THREE_WP, 3, 20000L, Params.DEFAULT, rec));
      Assertions.assertFalse(pending.begin(42L, t0 + 100L), "second execute must not re-click while the transition is pending");
      Assertions.assertTrue(env.clicks.size() > movementClicksAfterFirst, "walker still issues its own movement clicks");
   }

   private static Bot bot() {
      return Bot.execute(new Bot.BotAction[0]);
   }

   private static Listener noop() {
      return new Listener() {
         public void event(String msg) {
         }

         public void fail(String msg) {
         }

         public void fail(String msg, WaypointGate.Outcome oc, String obs) {
         }

         public void beginWait(String what, long ms, String detail) {
         }

         public void dumpStuck() {
         }
      };
   }

   /** Env whose occupancy() mirrors liveEnv's cache read and rebuilds on demand. */
   private static final class CacheEnv implements Env {
      final List<Observation> script = new ArrayList<>();
      final List<Coord2d> clicks = new ArrayList<>();
      PathfinderLog.Occupancy rebuilt;
      long nowMs;
      int at;
      int refreshCalls;

      void obs(long t, double x, double y, boolean moving) {
         this.script.add(new Observation(t, Coord2d.of(x, y), moving));
      }

      /** A retry execute() re-observes the same scene from the start. */
      void resetWalk() {
         this.at = 0;
      }

      public Observation observe(boolean cancelled) {
         if (this.at >= this.script.size()) {
            return null;
         }
         Observation o = this.script.get(this.at++);
         this.nowMs = Math.max(this.nowMs, o.tMs);
         return o;
      }

      public void click(Coord2d waypoint) {
         this.clicks.add(waypoint);
      }

      public long now() {
         return this.nowMs;
      }

      public PathfinderLog.Occupancy occupancy() {
         return PathfinderLog.lastOccupancy();
      }

      public PathfinderLog.Occupancy refreshOccupancy() {
         this.refreshCalls++;
         if (this.rebuilt != null) {
            PathfinderLog.recordOccupancy(this.rebuilt);
            return this.rebuilt;
         }
         return null;
      }
   }
}
