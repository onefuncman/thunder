package haven.nav;

/**
 * Authoritative strategic location. Identifiers are opaque strings; numeric
 * layer or world values never imply adjacency.
 */
public final class GraphNode {
   public final String worldId;
   public final String segmentId;
   public final String layerId;
   public final String areaId;

   public GraphNode(String worldId, String segmentId, String layerId, String areaId) {
      this.worldId = worldId == null ? "unknown" : worldId;
      this.segmentId = segmentId == null ? "unknown" : segmentId;
      this.layerId = layerId == null ? "unknown" : layerId;
      this.areaId = areaId == null ? "unknown" : areaId;
   }

   public static GraphNode of(String worldId, String segmentId, String layerId, String areaId) {
      return new GraphNode(worldId, segmentId, layerId, areaId);
   }

   public boolean sameWalkRegion(GraphNode other) {
      return other != null
         && this.worldId.equals(other.worldId)
         && this.segmentId.equals(other.segmentId)
         && this.layerId.equals(other.layerId);
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      }
      if (!(o instanceof GraphNode)) {
         return false;
      }
      GraphNode n = (GraphNode) o;
      return this.worldId.equals(n.worldId)
         && this.segmentId.equals(n.segmentId)
         && this.layerId.equals(n.layerId)
         && this.areaId.equals(n.areaId);
   }

   @Override
   public int hashCode() {
      int h = this.worldId.hashCode();
      h = 31 * h + this.segmentId.hashCode();
      h = 31 * h + this.layerId.hashCode();
      h = 31 * h + this.areaId.hashCode();
      return h;
   }

   @Override
   public String toString() {
      return this.worldId + "/" + this.segmentId + "/" + this.layerId + "/" + this.areaId;
   }
}
