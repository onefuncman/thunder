package haven.nav;

import haven.Coord;
import haven.Coord2d;
import haven.pathfinding.OccupancyGrid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NavSnapshot {
   public final long timestampMs;
   public final String coordinateFrame;
   public final OccupancyGrid occupancy;
   public final String[] terrainCells;
   public final List<Coord2d[]> obstacles;
   public final List<Coord2d> hazards;
   public final Coord2d player;
   public final Coord playerCell;
   public final double agentRadius;
   public final boolean moving;
   public final MobilityProfile mobility;

   public NavSnapshot(
      long timestampMs,
      String coordinateFrame,
      OccupancyGrid occupancy,
      String[] terrainCells,
      List<Coord2d[]> obstacles,
      List<Coord2d> hazards,
      Coord2d player,
      Coord playerCell,
      double agentRadius,
      boolean moving,
      MobilityProfile mobility
   ) {
      this.timestampMs = timestampMs;
      this.coordinateFrame = coordinateFrame == null ? "world" : coordinateFrame;
      this.occupancy = occupancy;
      this.terrainCells = terrainCells;
      this.obstacles = obstacles == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<Coord2d[]>(obstacles));
      this.hazards = hazards == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<Coord2d>(hazards));
      this.player = player;
      this.playerCell = playerCell;
      this.agentRadius = agentRadius;
      this.moving = moving;
      this.mobility = mobility == null ? MobilityProfile.land() : mobility;
   }
}
