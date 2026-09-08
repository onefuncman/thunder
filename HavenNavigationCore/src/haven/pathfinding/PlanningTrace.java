package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.util.Collections;
import java.util.List;

/** Mutable planning evidence bag. Does not record UI or renderer state. */
public class PlanningTrace {
   public String reason = "ok";
   public String clip = "";
   public double sx;
   public double sy;
   public double dx;
   public double dy;
   public double radius;
   public int dilation;
   public int expanded;
   public int obstacles;
   public int gridW;
   public int gridH;
   public boolean startBlocked;
   public boolean startBlockedAfter;
   public boolean startInSolid;
   public boolean goalBlocked;
   public boolean complete;
   public Coord startCell;
   public Coord goalCell;
   public Coord freeGoal;
   public List<Coord2d> waypoints = Collections.emptyList();
   public List<Coord2d> astar = Collections.emptyList();
   public List<String> near = Collections.emptyList();
   public List<Coord2d[]> polys = Collections.emptyList();
   public List<Coord2d> hazards = Collections.emptyList();
   public OccupancyGrid occupancy;
}
