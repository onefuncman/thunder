package haven.pathfinding;

import haven.nav.GraphEdge;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;
import haven.nav.NavGoal;
import haven.nav.NavOutcome;

/**
 * Shared transition lifecycle. Arrival and the interaction click are not
 * success; an observed landing (or vehicle state) is.
 */
public final class TransitionMachine {
   public static final int MAX_RETRY = 2;

   public static final String FIXTURE_UNAVAILABLE = "fixture_unavailable";
   public static final String FIXTURE_CHANGED = "fixture_changed";
   public static final String APPROACH_UNAVAILABLE = "approach_unavailable";
   public static final String INTERACTION_FAILED = "interaction_failed";
   public static final String TRANSITION_TIMEOUT = "transition_timeout";
   public static final String LANDING_UNKNOWN = "landing_unknown";
   public static final String LANDING_AMBIGUOUS = "landing_ambiguous";
   public static final String MOBILITY_UNAVAILABLE = "mobility_unavailable";
   public static final String TERRAIN_UNAVAILABLE = "terrain_unavailable";
   public static final String STALE_GRAPH_EDGE = "stale_graph_edge";
   public static final String INTERACT_BEFORE_ARRIVAL = "interact_before_arrival";

   public enum Phase {
      IDLE,
      SELECT_APPROACH,
      NAVIGATE,
      REVALIDATE,
      INTERACT,
      WAIT_AUTH,
      CAPTURE_LANDING,
      RESUME,
      SUCCEEDED,
      FAILED;
   }

   public enum Auth {
      STATE,
      TOPOLOGY,
      VEHICLE_ENTER,
      VEHICLE_EXIT;
   }

   public static Auth authFor(GraphEdge.Kind kind, boolean exit) {
      if (kind == GraphEdge.Kind.DOOR_GATE) {
         return Auth.STATE;
      }
      if (kind == GraphEdge.Kind.BOAT || kind == GraphEdge.Kind.VEHICLE) {
         return exit ? Auth.VEHICLE_EXIT : Auth.VEHICLE_ENTER;
      }
      return Auth.TOPOLOGY;
   }

   public static final class State {
      public final Phase phase;
      public final NavGoal originalGoal;
      public final GraphNode source;
      public final GraphNode landing;
      public final GraphEdge edge;
      public final GraphEdge.Kind kind;
      public final String fixtureId;
      public final Auth auth;
      public final String reason;
      public final NavOutcome outcome;
      public final int retries;
      public final MobilityProfile mobilityAfter;

      State(
         Phase phase,
         NavGoal originalGoal,
         GraphNode source,
         GraphNode landing,
         GraphEdge edge,
         GraphEdge.Kind kind,
         String fixtureId,
         Auth auth,
         String reason,
         NavOutcome outcome,
         int retries,
         MobilityProfile mobilityAfter
      ) {
         this.phase = phase;
         this.originalGoal = originalGoal;
         this.source = source;
         this.landing = landing;
         this.edge = edge;
         this.kind = kind;
         this.fixtureId = fixtureId;
         this.auth = auth;
         this.reason = reason;
         this.outcome = outcome;
         this.retries = retries;
         this.mobilityAfter = mobilityAfter;
      }

      public boolean terminal() {
         return this.phase == Phase.SUCCEEDED || this.phase == Phase.FAILED;
      }
   }

   private State state;

   public TransitionMachine(NavGoal originalGoal, GraphNode source, String fixtureId, GraphEdge.Kind kind, GraphEdge candidate) {
      this(originalGoal, source, fixtureId, kind, candidate, authFor(kind, false));
   }

   public TransitionMachine(
      NavGoal originalGoal, GraphNode source, String fixtureId, GraphEdge.Kind kind, GraphEdge candidate, boolean vehicleExit
   ) {
      this(originalGoal, source, fixtureId, kind, candidate, authFor(kind, vehicleExit));
   }

   public TransitionMachine(
      NavGoal originalGoal, GraphNode source, String fixtureId, GraphEdge.Kind kind, GraphEdge candidate, Auth auth
   ) {
      Auth resolved = auth == null ? authFor(kind, false) : auth;
      if (candidate != null && candidate.stale()) {
         this.state = fail(originalGoal, source, null, candidate, kind, fixtureId, resolved, STALE_GRAPH_EDGE, 0, null);
      } else {
         this.state = new State(
            Phase.SELECT_APPROACH, originalGoal, source, null, candidate, kind, fixtureId, resolved, "", null, 0, null
         );
      }
   }

   public State state() {
      return this.state;
   }

   public NavGoal originalGoal() {
      return this.state.originalGoal;
   }

   public State selectApproach(boolean available) {
      if (this.state.terminal()) {
         return this.state;
      }
      if (this.state.phase != Phase.SELECT_APPROACH) {
         return failNow(APPROACH_UNAVAILABLE);
      }
      if (!available) {
         return failNow(APPROACH_UNAVAILABLE);
      }
      return go(Phase.NAVIGATE, "");
   }

   public State arrived() {
      if (this.state.terminal()) {
         return this.state;
      }
      if (this.state.phase != Phase.NAVIGATE) {
         return failNow(APPROACH_UNAVAILABLE);
      }
      return go(Phase.REVALIDATE, "");
   }

   public State revalidate(boolean fixturePresent, boolean fixtureSame, boolean approachOk) {
      if (this.state.terminal()) {
         return this.state;
      }
      if (this.state.phase != Phase.REVALIDATE) {
         return failNow(FIXTURE_CHANGED);
      }
      if (!fixturePresent) {
         return failNow(FIXTURE_UNAVAILABLE);
      }
      if (!fixtureSame) {
         return failNow(FIXTURE_CHANGED);
      }
      if (!approachOk) {
         return failNow(APPROACH_UNAVAILABLE);
      }
      return go(Phase.INTERACT, "");
   }

   public State interact() {
      if (this.state.terminal()) {
         return this.state;
      }
      if (this.state.phase != Phase.INTERACT && this.state.phase != Phase.REVALIDATE) {
         if (this.state.phase == Phase.SELECT_APPROACH || this.state.phase == Phase.NAVIGATE || this.state.phase == Phase.IDLE) {
            return failNow(INTERACT_BEFORE_ARRIVAL);
         }
         return failNow(INTERACTION_FAILED);
      }
      if (this.state.phase == Phase.REVALIDATE) {
         return failNow(INTERACT_BEFORE_ARRIVAL);
      }
      if (this.state.retries >= MAX_RETRY) {
         return failNow(INTERACTION_FAILED);
      }
      this.state = new State(
         Phase.WAIT_AUTH,
         this.state.originalGoal,
         this.state.source,
         this.state.landing,
         this.state.edge,
         this.state.kind,
         this.state.fixtureId,
         this.state.auth,
         "",
         null,
         this.state.retries + 1,
         this.state.mobilityAfter
      );
      return this.state;
   }

   public State timeout() {
      return failNow(TRANSITION_TIMEOUT);
   }

   public State capture(GraphNode landing, MobilityProfile after, boolean ambiguous) {
      return capture(landing, after, ambiguous, false);
   }

   public State capture(GraphNode landing, MobilityProfile after, boolean ambiguous, boolean relocated) {
      if (this.state.terminal()) {
         return this.state;
      }
      if (this.state.phase != Phase.WAIT_AUTH) {
         return failNow(LANDING_UNKNOWN);
      }
      if (ambiguous) {
         return failNow(LANDING_AMBIGUOUS);
      }
      if (landing == null) {
         return failNow(LANDING_UNKNOWN);
      }
      if (this.state.auth == Auth.TOPOLOGY && landing.equals(this.state.source) && !relocated) {
         return failNow(LANDING_UNKNOWN);
      }
      if (this.state.auth == Auth.VEHICLE_ENTER && (after == null || !after.vehicle())) {
         return failNow(INTERACTION_FAILED);
      }
      if (this.state.auth == Auth.VEHICLE_EXIT && (after == null || after.vehicle())) {
         return failNow(INTERACTION_FAILED);
      }
      this.state = new State(
         Phase.CAPTURE_LANDING,
         this.state.originalGoal,
         this.state.source,
         landing,
         this.state.edge,
         this.state.kind,
         this.state.fixtureId,
         this.state.auth,
         "",
         null,
         this.state.retries,
         after
      );
      this.state = new State(
         Phase.RESUME,
         this.state.originalGoal,
         this.state.source,
         landing,
         this.state.edge,
         this.state.kind,
         this.state.fixtureId,
         this.state.auth,
         "",
         null,
         this.state.retries,
         after
      );
      this.state = new State(
         Phase.SUCCEEDED,
         this.state.originalGoal,
         this.state.source,
         landing,
         this.state.edge,
         this.state.kind,
         this.state.fixtureId,
         this.state.auth,
         "",
         null,
         this.state.retries,
         after
      );
      return this.state;
   }

   public State stale() {
      if (this.state.edge != null) {
         this.state.edge.invalidate();
      }
      return failNow(STALE_GRAPH_EDGE);
   }

   public State mobilityUnavailable() {
      return failNow(MOBILITY_UNAVAILABLE);
   }

   public State terrainUnavailable() {
      return failNow(TERRAIN_UNAVAILABLE);
   }

   private State go(Phase phase, String reason) {
      this.state = new State(
         phase,
         this.state.originalGoal,
         this.state.source,
         this.state.landing,
         this.state.edge,
         this.state.kind,
         this.state.fixtureId,
         this.state.auth,
         reason,
         null,
         this.state.retries,
         this.state.mobilityAfter
      );
      return this.state;
   }

   private State failNow(String reason) {
      this.state = fail(
         this.state.originalGoal,
         this.state.source,
         this.state.landing,
         this.state.edge,
         this.state.kind,
         this.state.fixtureId,
         this.state.auth,
         reason,
         this.state.retries,
         this.state.mobilityAfter
      );
      return this.state;
   }

   private static State fail(
      NavGoal goal,
      GraphNode source,
      GraphNode landing,
      GraphEdge edge,
      GraphEdge.Kind kind,
      String fixtureId,
      Auth auth,
      String reason,
      int retries,
      MobilityProfile after
   ) {
      NavOutcome out = NavOutcome.TRANSITION_FAILED;
      if (MOBILITY_UNAVAILABLE.equals(reason)) {
         out = NavOutcome.UNAVAILABLE;
      } else if (TERRAIN_UNAVAILABLE.equals(reason)) {
         out = NavOutcome.UNKNOWN_TERRAIN;
      }
      return new State(Phase.FAILED, goal, source, landing, edge, kind, fixtureId, auth, reason, out, retries, after);
   }
}
