package haven.pathfinding;

import haven.nav.GraphEdge;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Strategic graph. Transition edges exist only after both endpoints have
 * been observed; layer/world numbers never create topology.
 */
public final class WorldGraph {
   private final LinkedHashSet<GraphNode> observed = new LinkedHashSet<GraphNode>();
   private final List<GraphEdge> edges = new ArrayList<GraphEdge>();

   public void observe(GraphNode node) {
      if (node != null) {
         this.observed.add(node);
      }
   }

   public boolean isObserved(GraphNode node) {
      return node != null && this.observed.contains(node);
   }

   public Set<GraphNode> nodes() {
      return Collections.unmodifiableSet(this.observed);
   }

   public List<GraphEdge> edges() {
      return Collections.unmodifiableList(this.edges);
   }

   /**
    * Persistent transition/walk edge. Returns null unless both endpoints
    * were already observed. Never fabricates a destination node.
    */
   public GraphEdge learn(GraphNode from, GraphNode to, GraphEdge.Kind kind, String fixtureId) {
      if (from == null || to == null || kind == null) {
         return null;
      }
      if (!this.observed.contains(from) || !this.observed.contains(to)) {
         return null;
      }
      GraphEdge e = GraphEdge.of(from, to, kind, fixtureId);
      this.edges.add(e);
      return e;
   }

   public GraphEdge learnWalk(GraphNode from, GraphNode to, boolean water) {
      if (from == null || to == null || !this.observed.contains(from) || !this.observed.contains(to)) {
         return null;
      }
      GraphEdge e = GraphEdge.walk(from, to, water);
      this.edges.add(e);
      return e;
   }

   public void invalidate(GraphEdge edge) {
      if (edge != null) {
         edge.invalidate();
      }
   }

   public Path route(GraphNode from, GraphNode dest, MobilityProfile mobility) {
      if (from == null || dest == null) {
         return Path.fail("landing_unknown");
      }
      if (from.equals(dest)) {
         return Path.ok(Collections.<GraphEdge>emptyList());
      }
      if (!this.observed.contains(from)) {
         return Path.fail("stale_graph_edge");
      }
      if (!this.observed.contains(dest)) {
         return Path.fail("landing_unknown");
      }
      ArrayDeque<GraphNode> q = new ArrayDeque<GraphNode>();
      LinkedHashMap<GraphNode, GraphEdge> via = new LinkedHashMap<GraphNode, GraphEdge>();
      HashSet<GraphNode> seen = new HashSet<GraphNode>();
      q.add(from);
      seen.add(from);
      boolean skippedMobility = false;
      boolean skippedStale = false;
      while (!q.isEmpty()) {
         GraphNode cur = q.remove();
         for (int i = 0; i < this.edges.size(); i++) {
            GraphEdge e = this.edges.get(i);
            if (!e.from.equals(cur)) {
               continue;
            }
            if (e.stale()) {
               skippedStale = true;
               continue;
            }
            if (!e.allowed(mobility)) {
               skippedMobility = true;
               continue;
            }
            if (seen.contains(e.to)) {
               continue;
            }
            seen.add(e.to);
            via.put(e.to, e);
            if (e.to.equals(dest)) {
               return Path.ok(reconstruct(via, from, dest));
            }
            q.add(e.to);
         }
      }
      if (skippedMobility) {
         return Path.fail("mobility_unavailable");
      }
      if (skippedStale) {
         return Path.fail("stale_graph_edge");
      }
      return Path.fail("stale_graph_edge");
   }

   private static List<GraphEdge> reconstruct(LinkedHashMap<GraphNode, GraphEdge> via, GraphNode from, GraphNode dest) {
      ArrayList<GraphEdge> rev = new ArrayList<GraphEdge>();
      GraphNode at = dest;
      while (!at.equals(from)) {
         GraphEdge e = via.get(at);
         if (e == null) {
            return Collections.emptyList();
         }
         rev.add(e);
         at = e.from;
      }
      Collections.reverse(rev);
      return rev;
   }

   public static final class Path {
      public final List<GraphEdge> edges;
      public final String reason;

      Path(List<GraphEdge> edges, String reason) {
         this.edges = edges;
         this.reason = reason;
      }

      public boolean ok() {
         return this.reason == null;
      }

      static Path ok(List<GraphEdge> edges) {
         return new Path(edges, null);
      }

      static Path fail(String reason) {
         return new Path(Collections.<GraphEdge>emptyList(), reason);
      }
   }
}
