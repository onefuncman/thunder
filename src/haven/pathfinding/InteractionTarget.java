package haven.pathfinding;

import haven.Coord2d;
import java.util.List;

/**
 * Typed interaction-target identity. The gob ID, resource name and last
 * observed position survive every stage of a distant-object interaction; a
 * plain coordinate is only ever a temporary movement destination.
 */
public final class InteractionTarget {
   /** Max distance a fallback (id-changed) match may sit from the last known position. */
   public static final double RESOLVE_RADIUS = 11.0;

   public final long gobId;
   /** Base (unbracketed) resource name, e.g. gfx/terobjs/arch/cellardoor. */
   public final String resid;
   public final Coord2d lastRc;
   /** Descriptive interaction label, e.g. interact_door_gate or transition:CELLAR_STAIRS. */
   public final String kind;
   /** Stable classification used for re-resolution, e.g. door_gate or cupboard. */
   public final String kindClass;
   public final String expectedResult;
   /** Surface / layer identity where available (terrain name). */
   public final String surface;
   /** True when the live target was resolved by ID on the last refresh. */
   public final boolean resolvedById;

   private InteractionTarget(long gobId, String resid, Coord2d lastRc, String kind, String kindClass, String expectedResult, String surface, boolean resolvedById) {
      this.gobId = gobId;
      this.resid = resid;
      this.lastRc = lastRc;
      this.kind = kind;
      this.kindClass = kindClass;
      this.expectedResult = expectedResult;
      this.surface = surface;
      this.resolvedById = resolvedById;
   }

   public static InteractionTarget of(PrototypePathfinder.GobGeom g, String kind, String expectedResult, String surface) {
      if (g == null) {
         return null;
      }
      return new InteractionTarget(
         g.id, PrototypePathfinder.baseResid(g.resid), g.rc, kind, kindClass(g), expectedResult, surface == null ? "" : surface, true
      );
   }

   /** Refresh the observed position and surface without changing identity. */
   public InteractionTarget refreshed(PrototypePathfinder.GobGeom live, String surface, boolean byId) {
      return new InteractionTarget(this.gobId, this.resid, live != null ? live.rc : this.lastRc, this.kind,
         this.kindClass, this.expectedResult, surface == null ? this.surface : surface, byId);
   }

   /**
    * Resolve the same intended object in a fresh scene: by gob ID first; if
    * the ID disappeared after a map/surface reload, fall back to the stable
    * identity (same resource, same interaction classification, approximate
    * location). Never returns an arbitrary nearby object of another kind.
    */
   public PrototypePathfinder.GobGeom resolveIn(PrototypePathfinder.Scene scene) {
      if (scene == null || scene.gobs == null) {
         return null;
      }
      PrototypePathfinder.GobGeom byId = null;
      PrototypePathfinder.GobGeom byFallback = null;
      for (int i = 0; i < scene.gobs.size(); i++) {
         PrototypePathfinder.GobGeom g = scene.gobs.get(i);
         if (g == null || g.rc == null) {
            continue;
         }
         if (g.id == this.gobId) {
            byId = g;
            break;
         }
         if (byFallback == null && sameIdentity(g) && g.rc.dist(this.lastRc) <= RESOLVE_RADIUS) {
            byFallback = g;
         }
      }
      if (byId != null) {
         return byId;
      }
      return byFallback;
   }

   /**
    * Re-resolve in a broader scan (targets outside the observed scene list).
    * Same rules as {@link #resolveIn}: ID first, then stable fallback.
    */
   public PrototypePathfinder.GobGeom resolveIn(List<PrototypePathfinder.GobGeom> gobs) {
      if (gobs == null) {
         return null;
      }
      PrototypePathfinder.GobGeom byFallback = null;
      for (int i = 0; i < gobs.size(); i++) {
         PrototypePathfinder.GobGeom g = gobs.get(i);
         if (g == null || g.rc == null) {
            continue;
         }
         if (g.id == this.gobId) {
            return g;
         }
         if (byFallback == null && sameIdentity(g) && g.rc.dist(this.lastRc) <= RESOLVE_RADIUS) {
            byFallback = g;
         }
      }
      return byFallback;
   }

   /** Same resource and interaction classification — reloads keep this stable. */
   public boolean sameIdentity(PrototypePathfinder.GobGeom g) {
      if (g == null) {
         return false;
      }
      if (this.resid == null || !this.resid.equals(PrototypePathfinder.baseResid(g.resid))) {
         return false;
      }
      return this.kindClass.equals(kindClass(g));
   }

   /**
    * Compatible for continued interaction: resource and classification are
    * unchanged. A changed gob ID alone is allowed (map reload) as long as the
    * stable identity matches at the same location.
    */
   public boolean compatibleWith(PrototypePathfinder.GobGeom g) {
      return g != null && sameIdentity(g);
   }

   public boolean moved(PrototypePathfinder.GobGeom live) {
      return live == null || live.rc == null || this.lastRc == null || live.rc.dist(this.lastRc) > RESOLVE_RADIUS;
   }

   static String kindClass(PrototypePathfinder.GobGeom g) {
      if (g == null) {
         return "object";
      }
      if (g.cupboard) {
         return "cupboard";
      }
      if (g.caveTransition) {
         return "cave_transition";
      }
      if (g.doorGate) {
         return "door_gate";
      }
      if (g.boulder) {
         return "boulder";
      }
      return "object";
   }

   /** Short telemetry label that always carries ID and resource. */
   public String label() {
      String res = this.resid == null ? "?" : this.resid.substring(this.resid.lastIndexOf('/') + 1);
      return res + " #" + this.gobId;
   }

   public String toString() {
      return this.label() + " (" + this.resid + ") @" + this.lastRc;
   }
}
