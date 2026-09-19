package haven.pathfinding;

import haven.Coord2d;

public final class WaypointGate {
   public static final double DEFAULT_EPS = 2.475;
   public static final double DEFAULT_MOVE_PROGRESS = 0.6875;
   public static final long DEFAULT_START_TIMEOUT_MS = 800L;
   public static final long DEFAULT_WALK_TIMEOUT_MS = 20000L;
   public static final long DEFAULT_JIGGLE_WINDOW_MS = 3000L;
   private final WaypointGate.Request req;
   private WaypointGate.State state = WaypointGate.State.NOT_STARTED;
   private WaypointGate.Outcome outcome;
   private long t0;
   private Coord2d pos0;
   private boolean moving0;
   private long vehicle0;
   private boolean passenger0;
   private long lastT;
   private Coord2d lastPos;
   private long lastProgressT;
   private Coord2d lastProgressPos;

   public WaypointGate(WaypointGate.Request req) {
      if (req == null) {
         throw new IllegalArgumentException("req is null");
      } else {
         this.req = req;
      }
   }

   public WaypointGate.Outcome start(WaypointGate.Observation base) {
      if (this.state != WaypointGate.State.NOT_STARTED) {
         throw new IllegalStateException("already started");
      } else {
         this.t0 = base.tMs;
         this.lastT = base.tMs;
         this.pos0 = base.pos;
         this.lastPos = base.pos;
         this.moving0 = base.moving;
         this.vehicle0 = base.vehicleId;
         this.passenger0 = base.passenger;
         this.lastProgressT = base.tMs;
         this.lastProgressPos = base.pos;
         this.state = WaypointGate.State.WAITING_START;
         return this.step(base);
      }
   }

   public WaypointGate.Outcome observe(WaypointGate.Observation o) {
      if (this.state == WaypointGate.State.NOT_STARTED) {
         throw new IllegalStateException("start() first");
      } else if (this.state == WaypointGate.State.DONE) {
         return this.outcome;
      } else {
         if (o.tMs < this.lastT) {
            o = new WaypointGate.Observation(this.lastT, o.pos, o.moving, o.vehicleId, o.passenger, o.cancelled);
         }

         this.lastT = o.tMs;
         this.lastPos = o.pos;
         return this.step(o);
      }
   }

   public boolean isDone() {
      return this.state == WaypointGate.State.DONE;
   }

   public WaypointGate.State state() {
      return this.state;
   }

   public WaypointGate.Outcome outcome() {
      return this.outcome;
   }

   public double lastDistance() {
      return this.lastPos == null ? Double.POSITIVE_INFINITY : this.lastPos.dist(this.req.target);
   }

   public long elapsedMs() {
      return this.state == WaypointGate.State.NOT_STARTED ? 0L : this.lastT - this.t0;
   }

   public Coord2d target() {
      return this.req.target;
   }

   private WaypointGate.Outcome step(WaypointGate.Observation o) {
      if (o.cancelled) {
         return this.done(WaypointGate.Outcome.CANCELLED);
      } else if (o.vehicleId == this.vehicle0 && o.passenger == this.passenger0) {
         switch (this.state) {
            case WAITING_START:
               return this.waitingStep(o);
            case PROGRESSING:
               return this.progressingStep(o);
            default:
               throw new IllegalStateException("unexpected state " + this.state);
         }
      } else {
         return this.done(WaypointGate.Outcome.VEHICLE_STATE_CHANGED);
      }
   }

   private WaypointGate.Outcome done(WaypointGate.Outcome terminal) {
      this.state = WaypointGate.State.DONE;
      this.outcome = terminal;
      return terminal;
   }

   private WaypointGate.Outcome waitingStep(WaypointGate.Observation o) {
      double d = o.pos.dist(this.req.target);
      double moved = o.pos.dist(this.pos0);
      if (!o.moving) {
         if (d <= this.req.eps) {
            return this.done(WaypointGate.Outcome.WAYPOINT_COMPLETE);
         }

         if (moved >= this.req.moveProgress) {
            return this.done(WaypointGate.Outcome.STOPPED_SHORT);
         }
      }

      boolean startSignal = d <= this.req.eps || moved >= this.req.moveProgress || o.moving && !this.moving0;
      if (!startSignal) {
         return o.tMs - this.t0 >= this.req.startTimeoutMs
            ? this.done(WaypointGate.Outcome.START_TIMEOUT)
            : (this.outcome = WaypointGate.Outcome.WAITING_START);
      } else {
         this.state = WaypointGate.State.PROGRESSING;
         this.lastProgressT = o.tMs;
         this.lastProgressPos = o.pos;
         return this.progressingStep(o);
      }
   }

   private WaypointGate.Outcome progressingStep(WaypointGate.Observation o) {
      double d = o.pos.dist(this.req.target);
      if (!o.moving) {
         return d <= this.req.eps ? this.done(WaypointGate.Outcome.WAYPOINT_COMPLETE) : this.done(WaypointGate.Outcome.STOPPED_SHORT);
      } else if (o.tMs - this.t0 >= this.req.walkTimeoutMs) {
         return this.done(WaypointGate.Outcome.WALK_TIMEOUT);
      } else {
         double moved = o.pos.dist(this.lastProgressPos);
         if (moved >= this.req.moveProgress) {
            this.lastProgressT = o.tMs;
            this.lastProgressPos = o.pos;
            return this.outcome = WaypointGate.Outcome.PROGRESSING;
         } else {
            return o.tMs - this.lastProgressT >= this.req.jiggleWindowMs
               ? this.done(WaypointGate.Outcome.NO_PROGRESS)
               : (this.outcome = WaypointGate.Outcome.PROGRESSING);
         }
      }
   }

   public static final class Observation {
      public final long tMs;
      public final Coord2d pos;
      public final boolean moving;
      public final long vehicleId;
      public final boolean passenger;
      public final boolean cancelled;
      public Observation(long tMs, Coord2d pos, boolean moving, long vehicleId, boolean passenger, boolean cancelled) {
         if (pos == null) {
            throw new IllegalArgumentException("pos is null");
         } else {
            this.tMs = tMs;
            this.pos = pos;
            this.moving = moving;
            this.vehicleId = vehicleId;
            this.passenger = passenger;
            this.cancelled = cancelled;
         }
      }

      public Observation(long tMs, Coord2d pos, boolean moving) {
         this(tMs, pos, moving, 0L, false, false);
      }
   }

   public static enum Outcome {
      WAITING_START,
      PROGRESSING,
      WAYPOINT_COMPLETE,
      STOPPED_SHORT,
      NO_PROGRESS,
      START_TIMEOUT,
      WALK_TIMEOUT,
      CANCELLED,
      VEHICLE_STATE_CHANGED;

      public boolean isTerminal() {
         return this != WAITING_START && this != PROGRESSING;
      }

      public boolean isSuccess() {
         return this == WAYPOINT_COMPLETE;
      }
   }

   public static final class Request {
      public final Coord2d target;
      public final double eps;
      public final double moveProgress;
      public final long startTimeoutMs;
      public final long walkTimeoutMs;
      public final long jiggleWindowMs;

      public Request(Coord2d target, double eps, double moveProgress, long startTimeoutMs, long walkTimeoutMs, long jiggleWindowMs) {
         if (target == null) {
            throw new IllegalArgumentException("target is null");
         } else if (!(eps > 0.0)) {
            throw new IllegalArgumentException("eps must be > 0: " + eps);
         } else if (!(moveProgress > 0.0)) {
            throw new IllegalArgumentException("moveProgress must be > 0: " + moveProgress);
         } else if (startTimeoutMs < 0L) {
            throw new IllegalArgumentException("startTimeoutMs must be >= 0: " + startTimeoutMs);
         } else if (walkTimeoutMs < startTimeoutMs) {
            throw new IllegalArgumentException("walkTimeoutMs must be >= startTimeoutMs: " + walkTimeoutMs + " < " + startTimeoutMs);
         } else if (jiggleWindowMs <= 0L) {
            throw new IllegalArgumentException("jiggleWindowMs must be > 0: " + jiggleWindowMs);
         } else {
            this.target = target;
            this.eps = eps;
            this.moveProgress = moveProgress;
            this.startTimeoutMs = startTimeoutMs;
            this.walkTimeoutMs = walkTimeoutMs;
            this.jiggleWindowMs = jiggleWindowMs;
         }
      }

      public Request(Coord2d target) {
         this(target, 2.475, 0.6875, 800L, 20000L, 3000L);
      }
   }

   public static enum State {
      NOT_STARTED,
      WAITING_START,
      PROGRESSING,
      DONE;
   }
}
