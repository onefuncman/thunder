package haven.pathfinding;

import haven.nav.GraphEdge;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;
import haven.nav.NavOutcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Strategic sequence of local walks and transitions. Does not replace local A*.
 */
public final class WorldRouter {
   private WorldRouter() {
   }

   public static final class Hop {
      public enum Kind {
         WALK,
         TRANSITION,
         EXPLORE;
      }

      public final Kind kind;
      public final GraphNode from;
      public final GraphNode to;
      public final GraphEdge edge;

      Hop(Kind kind, GraphNode from, GraphNode to, GraphEdge edge) {
         this.kind = kind;
         this.from = from;
         this.to = to;
         this.edge = edge;
      }

      public static Hop walk(GraphNode from, GraphNode to) {
         return new Hop(Kind.WALK, from, to, null);
      }

      public static Hop transition(GraphEdge edge) {
         return new Hop(Kind.TRANSITION, edge.from, edge.to, edge);
      }

      public static Hop explore(GraphNode from) {
         return new Hop(Kind.EXPLORE, from, null, null);
      }
   }

   public static final class Result {
      public final List<Hop> hops;
      public final String reason;
      public final NavOutcome outcome;

      Result(List<Hop> hops, String reason, NavOutcome outcome) {
         this.hops = hops;
         this.reason = reason;
         this.outcome = outcome;
      }

      public boolean ok() {
         return this.outcome == null;
      }
   }

   public static Result route(WorldGraph graph, GraphNode from, GraphNode dest, MobilityProfile mobility, boolean allowExplore) {
      if (from == null) {
         return fail("landing_unknown", NavOutcome.TRANSITION_FAILED);
      }
      if (dest != null && from.sameWalkRegion(dest)) {
         if (from.equals(dest)) {
            return new Result(Collections.<Hop>emptyList(), null, null);
         }
         List<Hop> hops = new ArrayList<Hop>();
         hops.add(Hop.walk(from, dest));
         return new Result(hops, null, null);
      }
      if (dest == null || graph == null || !graph.isObserved(dest)) {
         if (allowExplore) {
            List<Hop> hops = new ArrayList<Hop>();
            hops.add(Hop.explore(from));
            return new Result(hops, "partial", NavOutcome.PARTIAL);
         }
         return fail("landing_unknown", NavOutcome.UNKNOWN_TERRAIN);
      }
      WorldGraph.Path path = graph.route(from, dest, mobility);
      if (!path.ok()) {
         NavOutcome out = "mobility_unavailable".equals(path.reason) ? NavOutcome.UNAVAILABLE : NavOutcome.TRANSITION_FAILED;
         if ("landing_unknown".equals(path.reason)) {
            out = NavOutcome.UNKNOWN_TERRAIN;
         }
         return fail(path.reason, out);
      }
      List<Hop> hops = new ArrayList<Hop>();
      GraphNode at = from;
      for (int i = 0; i < path.edges.size(); i++) {
         GraphEdge e = path.edges.get(i);
         if (!at.equals(e.from)) {
            hops.add(Hop.walk(at, e.from));
         }
         hops.add(Hop.transition(e));
         at = e.to;
      }
      if (!at.equals(dest)) {
         hops.add(Hop.walk(at, dest));
      }
      return new Result(hops, null, null);
   }

   private static Result fail(String reason, NavOutcome outcome) {
      return new Result(Collections.<Hop>emptyList(), reason, outcome);
   }
}
