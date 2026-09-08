package haven.pathfinding;

import auto.Bot;
import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.Hitbox;
import haven.Loading;
import haven.MCache;
import haven.Moving;
import haven.OCache;
import haven.Resource;
import haven.GameUI.MsgType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.ender.ClientUtils;
import me.ender.gob.KinInfo;

public final class PrototypePathfinder {
   public static final double CELL = LocalPlanner.CELL;
   public static final double PAD = LocalPlanner.PAD;
   public static final double DEFAULT_AGENT_RADIUS = LocalPlanner.DEFAULT_AGENT_RADIUS;
   public static final int MAX_SIDE = LocalPlanner.MAX_SIDE;
   public static final int MAX_EXPANDED = LocalPlanner.MAX_EXPANDED;
   public static final double OVERLAP = LocalPlanner.OVERLAP;
   public static final double HOUSE_CLEARANCE = 2.0;
   private static final Map<String, Coord2d> BUILDING_HALF = new HashMap<>();
   private static final Map<String, Coord2d> FURNITURE_HALF = new HashMap<>();
   public static final int SNAP_SIDE = 72;

   private PrototypePathfinder() {
   }

   public static PrototypePathfinder.Plan plan(GameUI gui, Coord2d destination) {
      return destination == null ? planAny(gui, Collections.emptyList(), true) : planAny(gui, Collections.singletonList(destination), true);
   }

   public static PrototypePathfinder.Plan planAny(GameUI gui, List<Coord2d> destinations, boolean snap) {
      PathfinderLog.Trace tr = new PathfinderLog.Trace();
      Gob player = gui != null && gui.map != null ? gui.map.player() : null;
      if (player != null && destinations != null && !destinations.isEmpty()) {
         Coord2d start = player.rc;
         tr.sx = start.x;
         tr.sy = start.y;
         Coord2d first = destinations.get(0);
         tr.dx = first.x;
         tr.dy = first.y;
         double radius = agentRadius(player);
         tr.radius = radius;
         PrototypePathfinder.ClipResult clip = clipToHorizon(start, destinations);
         List<Coord2d> targets = clip.targets;
         if (targets.isEmpty()) {
            tr.reason = "no_player";
            PathfinderLog.record(gui, tr);
            return new PrototypePathfinder.Plan(Collections.emptyList(), false, 0, 0, PrototypePathfinder.Plan.Status.FAILED);
         } else {
            PrototypePathfinder.Grid grid = planGrid(start, targets);
            tr.gridW = grid.w;
            tr.gridH = grid.h;
            List<Coord2d[]> debugPolys = new ArrayList<>();
            PrototypePathfinder.OccupancyBuild occ = PrototypePathfinder.OccupancyBuild.build(gui, grid, player, debugPolys);
            boolean[] solid = occ.solid;
            boolean[] dilated = occ.dilated;
            int obstacles = occ.obstacles;
            tr.obstacles = obstacles;
            tr.polys = debugPolys;
            tr.hazards = occ.hazards;
            tr.near = nearBlockers(gui, player, start);
            PrototypePathfinder.Plan plan = planCore(start, destinations, targets, clip.clipped, snap, radius, grid, solid, dilated, obstacles, tr);
            tr.clip = clipAlong(gui, player, plan.waypoints);
            PathfinderLog.record(gui, tr);
            return plan;
         }
      } else {
         tr.reason = "no_player";
         PathfinderLog.record(gui, tr);
         return new PrototypePathfinder.Plan(Collections.emptyList(), false, 0, 0, PrototypePathfinder.Plan.Status.FAILED);
      }
   }

   static PrototypePathfinder.Plan planCore(
      Coord2d start,
      List<Coord2d> destinations,
      List<Coord2d> targets,
      List<Boolean> clippedFlags,
      boolean snap,
      double radius,
      PrototypePathfinder.Grid grid,
      boolean[] solid,
      boolean[] dilated,
      int obstacles,
      PathfinderLog.Trace tr
   ) {
      if (tr == null) {
         tr = new PathfinderLog.Trace();
      }
      return Plan.from(LocalPlanner.planCore(start, destinations, targets, clippedFlags, snap, radius, grid, solid, dilated, obstacles, tr));
   }


   /**
    * Replay planning against a captured occupancy grid. Reconstructs the
    * pre-carve solid/dilated masks and calls {@link #planCore} — the same
    * planner used by live {@link #planAny}.
    */
   static PrototypePathfinder.Plan planFromOccupancy(
      Coord2d start, Coord2d dest, boolean snap, double radius, PathfinderLog.Occupancy occ, int obstacles, PathfinderLog.Trace tr
   ) {
      if (tr == null) {
         tr = new PathfinderLog.Trace();
      }
      return Plan.from(LocalPlanner.planFromOccupancy(start, dest, snap, radius, occ, obstacles, tr));
   }


   static PrototypePathfinder.ClipResult clipToHorizon(Coord2d start, List<Coord2d> destinations) {
      LocalPlanner.ClipResult c = LocalPlanner.clipToHorizon(start, destinations);
      PrototypePathfinder.ClipResult out = new PrototypePathfinder.ClipResult();
      out.targets.addAll(c.targets);
      out.clipped.addAll(c.clipped);
      return out;
   }


   static PrototypePathfinder.Grid planGrid(Coord2d start, List<Coord2d> targets) {
      return new PrototypePathfinder.Grid(LocalPlanner.planGrid(start, targets));
   }


   private static double minDist(Coord2d start, List<Coord2d> dests) {
      return LocalPlanner.minDist(start, dests);
   }


   public static void execute(GameUI gui, PrototypePathfinder.Plan plan) {
      if (gui != null && gui.map != null && plan != null && plan.waypoints.size() >= 2) {
         gui.pathQueue.repath();

         for (int i = 1; i < plan.waypoints.size(); i++) {
            gui.pathQueue.add(plan.waypoints.get(i));
         }

         Coord2d first = plan.waypoints.get(1);
         gui.map.wdgmsg("click", new Object[]{Coord.z, first.floor(OCache.posres), 1, 0});
      }
   }

   public static double maxReach() {
      return LocalPlanner.maxReach();
   }


   public static Coord2d alignedOrigin(double x, double y) {
      return LocalPlanner.alignedOrigin(x, y);
   }


   public static PrototypePathfinder.Scene observe(GameUI gui) {
      return observe(gui, true);
   }

   public static PrototypePathfinder.Scene observe(GameUI gui, boolean includeGobs) {
      PrototypePathfinder.Scene scene = new PrototypePathfinder.Scene();
      Gob player = gui != null && gui.map != null ? gui.map.player() : null;
      if (player != null && player.rc != null) {
         scene.player = player.rc;
         scene.moving = player.getattr(Moving.class) != null;
         scene.radius = agentRadius(player);
         scene.terrain = terrainName(gui, player.rc);
         int w = 72;
         int h = 72;
         scene.w = w;
         scene.h = h;
         scene.origin = alignedOrigin(player.rc.x - (double)w * 2.75 * 0.5, player.rc.y - (double)h * 2.75 * 0.5);
         PrototypePathfinder.Grid grid = new PrototypePathfinder.Grid(scene.origin, w, h);
         PrototypePathfinder.OccupancyBuild occ = PrototypePathfinder.OccupancyBuild.build(gui, grid, player, null);
         boolean[] solid = occ.solid;
         boolean[] dilated = occ.dilated;
         scene.obstacles = occ.obstacles;
         scene.terrainCells = occ.terrainNames;
         Coord sc = grid.cell(player.rc);
         scene.playerCell = sc;
         if (sc.x >= 0 && sc.y >= 0 && sc.x < w && sc.y < h) {
            scene.playerInSolid = solid[sc.y * w + sc.x];
            scene.playerInDilated = dilated[sc.y * w + sc.x];
         } else {
            scene.playerInSolid = scene.playerInDilated = true;
         }

         for (int i = 0; i < solid.length; i++) {
            if (solid[i]) {
               scene.solidCount++;
            } else if (dilated[i]) {
               scene.dilatedCount++;
            }
         }

         scene.occupancy = PathfinderLog.Occupancy.capture(scene.origin, w, h, 2.75, solid, dilated, dilated, sc, null, null, Collections.emptyList());
         scene.gobs = includeGobs ? nearbyGeometry(gui, player, 220.0) : Collections.emptyList();
         scene.solids = occ.solids;
         scene.playerBody = PrototypePathfinder.playerBodyOrigin(player);
         PathfinderLog.recordOccupancy(scene.occupancy);
         PathfinderLog.recordHazards(occ.hazards);
         PathfinderLog.recordConfirmedPos(scene.player);
         PathfinderLog.recordExactGeometry(scene.solids, scene.playerBody);
         return scene;
      } else {
         return scene;
      }
   }

   public static String terrainName(GameUI gui, Coord2d at) {
      if (gui != null && gui.ui != null && gui.ui.sess != null && at != null) {
         try {
            Resource res = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(at.floor(MCache.tilesz)));
            return res == null ? "" : res.name;
         } catch (Loading var3) {
            return "loading";
         }
      } else {
         return "";
      }
   }

   public static List<PrototypePathfinder.GobGeom> nearbyGeometry(GameUI gui, Gob player, double reach) {
      List<PrototypePathfinder.GobGeom> out = new ArrayList<>();
      if (gui != null && gui.ui != null && gui.ui.sess != null && player != null && player.rc != null) {
         synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
               if (gob != null && gob != player && !gob.virtual && gob.id >= 0L && gob.rc != null && !(gob.rc.dist(player.rc) > reach)) {
                  PrototypePathfinder.GobGeom g = gobGeom(player, gob);
                  if (includeInScene(g)) {
                     out.add(g);
                  }
               }
            }
         }

         out.sort(Comparator.comparingDouble(a -> a.polyDist));
         return out;
      } else {
         return out;
      }
   }

   public static PrototypePathfinder.GobGeom gobGeom(GameUI gui, long id) {
      if (gui == null || gui.map == null || gui.ui == null || gui.map.glob == null || id < 0L) {
         return null;
      }
      synchronized (gui.ui) {
         Gob gob = gui.map.glob.oc.getgob(id);
         if (gob == null || gob.rc == null) {
            return null;
         }
         return gobGeom(gui.map.player(), gob);
      }
   }

   public static PrototypePathfinder.GobGeom gobGeom(Gob player, Gob gob) {
      PrototypePathfinder.GobGeom g = new PrototypePathfinder.GobGeom();
      if (gob == null) {
         return g;
      }
      g.id = gob.id;
      g.rc = gob.rc;
      g.a = gob.a;
      if (player != null && player.rc != null && gob.rc != null) {
         g.gobDist = gob.rc.dist(player.rc);
      }
      try {
         g.resid = gob.resid() == null ? "" : gob.resid();
      } catch (Loading ignored) {
         g.resid = "";
      }
      g.cupboard = CupboardCatalog.isCupboardResid(g.resid);
      g.boulder = TransitionApproachSelector.isBoulderResid(g.resid);
      g.caveTransition = TransitionApproachSelector.isCaveTransitionResid(g.resid);
      g.doorGate = TransitionApproachSelector.isDoorGateResid(g.resid);
      try {
         g.name = displayName(gob);
         g.passable = Hitbox.passable(gob);
         g.gateState = gob.sdt();
         g.visitorGate = g.doorGate && gob.isVisitorGate();
         g.movement = Hitbox.movementPolygons(gob);
         g.obst = Hitbox.obstaclePolygons(gob);
         g.placement = Hitbox.placementPolygons(gob);
         g.hitbox = Hitbox.worldPolygons(gob, true);
         g.fallback = knownFurnitureFootprint(g.resid, g.rc, g.a);
         CollisionGeom chosen = furnitureFootprint(g.resid)
            ? furnitureGeometry(g.resid, g.rc, g.a, g.obst, g.movement)
            : CollisionGeom.target(g.obst, g.movement);
         g.collisionSource = chosen.source;
         g.collision = chosen.polygons;
         g.collisionReason = furnitureFootprint(g.resid)
            ? CollisionGeom.furnitureChoiceReason(g.obst, g.fallback)
            : chosen.source;
         if (player != null && player.rc != null) {
            g.polyDist = minPolyDist(player.rc, g.movement);
            g.hitboxDist = minPolyDist(player.rc, g.hitbox);
         }
      } catch (Loading ignored) {
         if (g.name == null || g.name.isEmpty()) {
            g.name = "?";
         }
      }
      return g;
   }

   /**
    * Keep ladders/doors/boats even when hitboxes are still loading or the player is
    * a courtyard away. The old 22u clutter cutoff dropped a recorded ladder from
    * stand 2 and Walk died with {@code NO_FIXTURE}.
    */
   public static boolean includeInScene(PrototypePathfinder.GobGeom g) {
      if (g == null) {
         return false;
      }
      if (g.cupboard || g.boulder || g.caveTransition || g.doorGate) {
         return true;
      }
      if (TransitionAdapter.kind(g.resid) != null) {
         return true;
      }
      return g.polyDist <= 16.0 || g.gobDist <= MCache.tilesz.x * 2.0;
   }

   public static double minPolyDist(Coord2d p, List<Coord2d[]> polygons) {
      if (p != null && polygons != null) {
         double best = Double.POSITIVE_INFINITY;

         for (Coord2d[] poly : polygons) {
            if (poly != null && poly.length >= 2) {
               if (pointInside(p, poly)) {
                  return 0.0;
               }

               best = Math.min(best, edgeDistance(p, poly));
            }
         }

         return best;
      } else {
         return Double.POSITIVE_INFINITY;
      }
   }

   public static List<PrototypePathfinder.NearbyGob> nearby(GameUI gui, String query) {
      List<PrototypePathfinder.NearbyGob> out = new ArrayList<>();
      if (gui != null && gui.map != null && gui.ui != null && gui.ui.sess != null) {
         Gob player = gui.map.player();
         if (player != null && player.rc != null) {
            double reach = maxReach();
            OCache oc = gui.ui.sess.glob.oc;
            synchronized (oc) {
               for (Gob gob : oc) {
                  if (gob != null && gob != player && !gob.virtual && gob.id >= 0L && gob.rc != null) {
                     double dist = gob.rc.dist(player.rc);
                     if (!(dist > reach)) {
                        String name;
                        String resid;
                        try {
                           name = displayName(gob);
                           resid = gob.resid();
                        } catch (Loading var16) {
                           continue;
                        }

                        if (matches(query, gob.id, name, resid)) {
                           out.add(new PrototypePathfinder.NearbyGob(gob.id, name, resid == null ? "" : resid, dist, gob.rc));
                        }
                     }
                  }
               }
            }

            out.sort(Comparator.comparingDouble(g -> g.dist));
            if (out.size() > 200) {
               out = new ArrayList<>(out.subList(0, 200));
            }

            return out;
         } else {
            return out;
         }
      } else {
         return out;
      }
   }

   public static boolean matches(String query, long id, String name, String resid) {
      if (query != null && !query.isEmpty()) {
         String q = query.trim().toLowerCase();
         if (q.isEmpty()) {
            return true;
         } else if (Long.toString(id).contains(q)) {
            return true;
         } else {
            return name != null && name.toLowerCase().contains(q) ? true : resid != null && resid.toLowerCase().contains(q);
         }
      } else {
         return true;
      }
   }

   public static String displayName(Gob gob) {
      if (gob == null) {
         return "???";
      } else {
         try {
            KinInfo kin = gob.kin();
            if (kin != null && kin.name != null && !kin.name.isEmpty()) {
               return kin.name;
            }
         } catch (Loading var4) {
         }

         try {
            String tt = gob.tooltip();
            if (tt != null && !tt.isEmpty() && !"???".equals(tt)) {
               return tt;
            }
         } catch (Loading var3) {
         }

         try {
            String rid = gob.resid();
            if (rid != null) {
               return ClientUtils.prettyResName(rid);
            }
         } catch (Loading var2) {
         }

         return "#" + gob.id;
      }
   }

   public static void go(GameUI gui, Coord2d destination) {
      PrototypePathfinder.Plan plan = plan(gui, destination);
      if (plan.waypoints.size() < 2) {
         Gob player = gui.map == null ? null : gui.map.player();
         if (player != null && player.rc != null && destination != null && player.rc.dist(destination) <= 11.0) {
            gui.msg("Pathfinder: already next to it", MsgType.INFO);
         } else {
            gui.msg("Pathfinder: no route found", MsgType.ERROR);
         }
      } else {
         execute(gui, plan);
         String suffix = plan.status == PrototypePathfinder.Plan.Status.CLIPPED ? " (clipped)" : (plan.complete ? "" : " (partial)");
         gui.msg(
            String.format("Pathfinder: %d waypoints, %d nodes, %d obstacles%s", plan.waypoints.size() - 1, plan.expanded, plan.obstacles, suffix), MsgType.INFO
         );
      }
   }

   public static void goToGob(GameUI gui, long id) {
      if (gui != null && gui.ui != null && gui.ui.sess != null) {
         Gob gob = gui.ui.sess.glob.oc.getgob(id);
         if (gob != null && gob.rc != null) {
            Gob player = gui.map == null ? null : gui.map.player();
            gui.msg("Pathfinder: going to " + displayName(gob), MsgType.INFO);
            PathfinderLog.setTarget(displayName(gob) + " #" + id);

            try {
               Coord2d dest = gob.rc;
               if (player != null && player.rc != null) {
                  dest = approach(player.rc, gob.rc, MCache.tilesz.x * 0.85);
               }

               go(gui, dest);
            } finally {
               PathfinderLog.clearTarget();
            }
         } else {
            gui.msg("Pathfinder: that object is gone", MsgType.ERROR);
         }
      }
   }

   public static void goToNearest(GameUI gui, String query) {
      List<PrototypePathfinder.NearbyGob> hits = nearby(gui, query);
      if (hits.isEmpty()) {
         gui.msg("Pathfinder: no nearby object matches \"" + query + "\"", MsgType.ERROR);
      } else {
         goToGob(gui, hits.get(0).id);
      }
   }

   public static void console(GameUI gui, String[] args) throws Exception {
      if (args.length >= 2 && "catalog".equals(args[1])) {
         if (args.length >= 3 && "log".equals(args[2])) {
            CatalogDebug.printLog(gui.ui.cons.out);
            gui.msg("Catalog debug: " + CatalogDebug.logFile(), MsgType.INFO);
         } else if (args.length >= 3 && "cancel".equals(args[2])) {
            Bot.cancelCurrent();
            gui.msg("Catalog cancelled", MsgType.INFO);
         } else {
            CupboardBot.start(gui);
         }
      } else if (args.length >= 2 && "debug".equals(args[1])) {
         boolean on = args.length >= 3 ? parseBool(args[2], (Boolean)CFG.DEBUG_PATHFIND.get()) : !Boolean.TRUE.equals(CFG.DEBUG_PATHFIND.get());
         CFG.DEBUG_PATHFIND.set(on);
         gui.msg("Pathfinder debug " + (on ? "ON (cyan route overlay)" : "off"), MsgType.INFO);
      } else if (args.length >= 2 && "log".equals(args[1])) {
         PathfinderDebug.printLog(gui.ui.cons.out);
         gui.msg("PF log: " + PathfinderLog.logFile(), MsgType.INFO);
      } else if (args.length >= 2 && "stuck".equals(args[1])) {
         PathfinderDebug.dumpStuck(gui, gui.ui.cons.out);
      } else if (args.length >= 2 && "probe".equals(args[1])) {
         MechanicsProbe.console(gui, args);
      } else if (args.length >= 2 && "gob".equals(args[1])) {
         if (args.length == 2) {
            gui.togglePathfinder();
         } else {
            String rest = join(args, 2);

            try {
               goToGob(gui, Long.parseLong(rest.trim()));
            } catch (NumberFormatException var5) {
               goToNearest(gui, rest);
            }
         }
      } else if (args.length == 1 || (args.length >= 2 && "route".equals(args[1]))) {
         gui.toggleCriticalRoutes();
      } else if (args.length != 3 && args.length != 4) {
         throw new Exception(
            "Usage: pf  |  pf route  |  pf [rel] <x> <y>  |  pf gob [id|name]  |  pf catalog [log|cancel]  |  pf probe [room|click|step|last]  |  pf debug  |  pf log  |  pf stuck"
         );
      } else {
         int off = args.length == 4 ? 2 : 1;
         Coord2d destination = Coord2d.of(Double.parseDouble(args[off]), Double.parseDouble(args[off + 1]));
         Gob player = gui.map == null ? null : gui.map.player();
         if (player == null) {
            throw new Exception("Player is not available");
         } else {
            if (args.length == 4) {
               if (!"rel".equals(args[1])) {
                  throw new Exception("Usage: pf [rel] <x> <y>  |  pf gob [id|name]");
               }

               destination = player.rc.add(destination);
            }

            go(gui, destination);
         }
      }
   }

   private static String join(String[] args, int from) {
      StringBuilder sb = new StringBuilder();

      for (int i = from; i < args.length; i++) {
         if (i > from) {
            sb.append(' ');
         }

         sb.append(args[i]);
      }

      return sb.toString();
   }

   private static Coord clamp(Coord c, int w, int h) {
      return LocalPlanner.clamp(c, w, h);
   }


   public static void openFootprint(boolean[] blocked, int w, int h, int sx, int sy, int radius, boolean[] solid) {
      LocalPlanner.openFootprint(blocked, w, h, sx, sy, radius, solid);
   }


   public static void openFootprint(boolean[] blocked, int w, int h, int sx, int sy, int radius) {
      LocalPlanner.openFootprint(blocked, w, h, sx, sy, radius);
   }


   public static void openStartPocket(boolean[] blocked, int w, int h, int sx, int sy, int radius) {
      LocalPlanner.openStartPocket(blocked, w, h, sx, sy, radius);
   }


   public static Coord nearestFree(boolean[] blocked, int w, int h, Coord goal, Coord from) {
      return LocalPlanner.nearestFree(blocked, w, h, goal, from);
   }


   private static Coord standoff(boolean[] blocked, int w, int h, Coord goal, Coord at) {
      return LocalPlanner.standoff(blocked, w, h, goal, at);
   }


   private static boolean[] rasterTerrain(GameUI gui, PrototypePathfinder.Grid grid) {
      return rasterTerrain(gui, grid, null);
   }

   private static boolean[] rasterTerrain(GameUI gui, PrototypePathfinder.Grid grid, String[] names) {
      boolean[] mask = new boolean[grid.blocked.length];

      for (int y = 0; y < grid.h; y++) {
         for (int x = 0; x < grid.w; x++) {
            Coord2d wc = grid.world(Coord.of(x, y));
            String name = null;

            try {
               Resource res = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(wc.floor(MCache.tilesz)));
               name = res == null ? "" : res.name;
               if (TerrainPolicy.terrainBlocks(name, WorldGraphAdapter.mobility(gui != null && gui.map != null ? gui.map.player() : null))) {
                  grid.block(x, y);
                  mask[y * grid.w + x] = true;
               }
            } catch (Loading var9) {
               name = "loading";
               grid.block(x, y);
               mask[y * grid.w + x] = true;
            }

            if (names != null) {
               names[y * grid.w + x] = name;
            }
         }
      }

      return mask;
   }

   private static int rasterGobs(GameUI gui, PrototypePathfinder.Grid grid, Gob player, List<Coord2d[]> debugPolys, List<Coord2d[]> body) {
      boolean inflate = body != null && !body.isEmpty();
      int count = 0;
      synchronized (gui.ui.sess.glob.oc) {
         for (Gob gob : gui.ui.sess.glob.oc) {
            if (gob != null && gob != player && gob.id >= 0L && gob.rc != null) {
               try {
                  if (!Hitbox.passable(gob)) {
                     String resid = gob.resid();
                     if (TransitionApproachSelector.isGateResid(resid)) {
                        // Pass-through gate: leave the planning grid open so
                        // routes may plan THROUGH it (the bot opens it on the
                        // way), but keep its polygons in the solids list so
                        // scene.solids still carries the gate footprint for
                        // beside-the-gate pose selection.
                        if (!inflate && debugPolys != null) {
                           List<Coord2d[]> gatePolys = collisionPolygons(gob);
                           if (gatePolys != null && !gatePolys.isEmpty()) {
                              debugPolys.addAll(gatePolys);
                           }
                        }
                        continue;
                     }
                     if (!inflate || !skipBodyInflate(resid)) {
                        List<Coord2d[]> polygons = collisionPolygons(gob);
                        double disk = obstacleDisk(resid);
                        if (rasterObstacle(grid, gob.rc, inflate, resid, polygons, disk, body)) {
                           count++;
                        }

                        if (!inflate && polygons != null && !polygons.isEmpty()) {
                           if (debugPolys != null) {
                              debugPolys.addAll(polygons);
                           }
                        }
                     }
                  }
               } catch (Loading var16) {
                  String resid = null;

                  try {
                     resid = gob.resid();
                  } catch (Loading var15) {
                  }

                  double diskx = solidFootprint(resid) ? MCache.tilesz.x * 3.0 : MCache.tilesz.x;
                  if (rasterObstacle(grid, gob.rc, inflate, resid, null, diskx, body)) {
                     count++;
                  }
               }
            }
         }

         return count;
      }
   }

   static List<Coord2d> movingHazards(GameUI gui, Gob player) {
      List<Coord2d> out = new ArrayList<>();
      if (gui != null && gui.ui != null && gui.ui.sess != null) {
         synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
               if (gob != null && gob != player && gob.id >= 0L && gob.rc != null) {
                  try {
                     if (gob.getattr(Moving.class) != null) {
                        out.add(gob.rc);
                     }
                  } catch (Loading var4) {
                  }
               }
            }
         }
      }
      return out;
   }

   public static boolean rasterObstacle(
      boolean[] blocked,
      Coord2d origin,
      int w,
      int h,
      Coord2d center,
      boolean inflate,
      String resid,
      List<Coord2d[]> polygons,
      double diskFallback,
      List<Coord2d[]> body
   ) {
      PrototypePathfinder.Grid grid = new PrototypePathfinder.Grid(origin, w, h);
      int n = Math.min(blocked.length, grid.blocked.length);
      System.arraycopy(blocked, 0, grid.blocked, 0, n);
      boolean out = rasterObstacle(grid, center, inflate, resid, polygons, diskFallback, body);
      System.arraycopy(grid.blocked, 0, blocked, 0, n);
      return out;
   }

   private static boolean rasterObstacle(
      PrototypePathfinder.Grid grid, Coord2d center, boolean inflate, String resid, List<Coord2d[]> polygons, double diskFallback, List<Coord2d[]> body
   ) {
      if (inflate && skipBodyInflate(resid)) {
         return false;
      } else if (polygons != null && !polygons.isEmpty()) {
         if (inflate) {
            if (!isHollowRing(polygons)) {
               for (Coord2d[] polygon : polygons) {
                  rasterPolygon(grid, polygon, body);
               }
            } else {
               rasterAabb(grid, polygons, body);
            }
         } else {
            if (!isHollowRing(polygons)) {
               for (Coord2d[] polygon : polygons) {
                  rasterPolygon(grid, polygon, OVERLAP);
               }
            } else {
               rasterAabb(grid, polygons, OVERLAP);
            }
         }
         if (skimpyVegetation(resid, center, polygons)) {
            double disk = diskFallback;
            if (inflate) {
               disk = diskFallback + boundingRadius(Coord2d.of(0.0, 0.0), body);
            }
            rasterDisk(grid, center, disk);
         }
         return true;
      } else if (polygons != null && polygons.isEmpty()) {
         return false;
      } else {
         double disk = diskFallback;
         if (inflate) {
            disk = diskFallback + boundingRadius(Coord2d.of(0.0, 0.0), body);
         }

         rasterDisk(grid, center, disk);
         return true;
      }
   }

   static void inflateMasked(PrototypePathfinder.Grid grid, boolean[] mask, List<Coord2d[]> body) {
      LocalPlanner.inflateMasked(grid, mask, body);
   }


   private static void rasterAabb(PrototypePathfinder.Grid grid, List<Coord2d[]> polygons, List<Coord2d[]> body) {
      LocalPlanner.rasterAabb(grid, polygons, body);
   }


   private static List<Coord2d[]> collisionPolygons(Gob gob) {
      String resid = gob.resid();
      if (furnitureFootprint(resid)) {
         return furnitureCollision(resid, gob.rc, gob.a, Hitbox.obstaclePolygons(gob), Hitbox.movementPolygons(gob));
      }
      CollisionGeom geom = CollisionGeom.target(Hitbox.obstaclePolygons(gob), Hitbox.movementPolygons(gob));
      List<Coord2d[]> polygons = new ArrayList<Coord2d[]>(geom.polygons);
      if (solidFootprint(resid)) {
         Coord2d[] known = knownBuildingFootprint(resid, gob.rc, gob.a);
         if (known != null) {
            polygons.add(known);
         }
      }
      return polygons;
   }

   /**
    * Packed cupboards stay obstacle-only (empty obst = no occupancy). Other
    * household furniture uses live obst if it is a real movement solid, else a
    * rotated known fallback — never a union AABB of both.
    */
   static List<Coord2d[]> furnitureCollision(String resid, Coord2d rc, double a, List<Coord2d[]> obst, List<Coord2d[]> movement) {
      return furnitureGeometry(resid, rc, a, obst, movement).polygons;
   }

   static CollisionGeom furnitureGeometry(String resid, Coord2d rc, double a, List<Coord2d[]> obst, List<Coord2d[]> movement) {
      if (CupboardCatalog.isCupboardResid(resid)) {
         List<Coord2d[]> used = obst == null ? Collections.<Coord2d[]>emptyList() : obst;
         return new CollisionGeom(used, CollisionGeom.OBST);
      }
      return CollisionGeom.furniture(obst, knownFurnitureFootprint(resid, rc, a));
   }

   public static String baseResid(String resid) {
      if (resid != null && !resid.isEmpty()) {
         int bracket = resid.indexOf(91);
         return bracket > 0 ? resid.substring(0, bracket) : resid;
      } else {
         return resid;
      }
   }

   public static boolean solidFootprint(String resid) {
      String name = baseResid(resid);
      return name == null ? false : BUILDING_HALF.containsKey(name);
   }

   public static boolean furnitureFootprint(String resid) {
      String name = baseResid(resid);
      if (name == null || !name.startsWith("gfx/terobjs/")) {
         return false;
      }
      if (name.startsWith("gfx/terobjs/arch/")
         || name.startsWith("gfx/terobjs/vehicle/")
         || name.startsWith("gfx/terobjs/trees/")
         || name.startsWith("gfx/terobjs/bushes/")
         || name.startsWith("gfx/terobjs/herbs/")
         || name.startsWith("gfx/terobjs/plants/")
         || name.startsWith("gfx/terobjs/bumlings/")
         || name.startsWith("gfx/terobjs/items/")
         || name.startsWith("gfx/terobjs/map/")
         || name.contains("stockpile")) {
         return false;
      }
      if (TransitionAdapter.kind(resid) != null || TransitionApproachSelector.isBoulderResid(resid)) {
         return false;
      }
      return true;
   }

   static boolean skipBodyInflate(String resid) {
      if (furnitureFootprint(resid)) {
         return true;
      }
      String name = baseResid(resid);
      return name != null && name.contains("hwall");
   }

   public static boolean vegetationResid(String resid) {
      String name = baseResid(resid);
      if (name == null) {
         return false;
      }
      return name.contains("/trees/") || name.contains("/bushes/") || name.contains("/bumlings/");
   }

   public static double obstacleDisk(String resid) {
      if (solidFootprint(resid)) {
         return MCache.tilesz.x * 3.0;
      }
      if (vegetationResid(resid)) {
         return MCache.tilesz.x;
      }
      return MCache.tilesz.x * 0.6;
   }

   static boolean skimpyVegetation(String resid, Coord2d center, List<Coord2d[]> polygons) {
      return vegetationResid(resid) && boundingRadius(center, polygons) < MCache.tilesz.x * 0.5;
   }

   public static boolean wallClearance(String resid) {
      String name = baseResid(resid);
      if (name == null) {
         return false;
      } else if (!name.startsWith("gfx/terobjs/arch/")) {
         return false;
      } else if (name.contains("palisade")) {
         return true;
      } else if (name.contains("brickwall") || name.contains("brickbig")) {
         return true;
      } else if (name.contains("drystone")) {
         return true;
      } else if (name.contains("hwall")) {
         return true;
      } else {
         return !name.contains("poleseg") && !name.contains("polecp") && !name.contains("polegate") && !name.contains("polebig")
            ? name.endsWith("seg") || name.endsWith("cp")
            : true;
      }
   }

   public static Coord2d[] knownBuildingFootprint(String resid, Coord2d rc, double a) {
      if (rc == null) {
         return null;
      } else {
         Coord2d half = BUILDING_HALF.get(baseResid(resid));
         if (half == null && solidFootprint(resid)) {
            half = Coord2d.of(40.0, 30.0);
         }

         if (half == null) {
            return null;
         } else {
            double cs = Math.cos(a);
            double sn = Math.sin(a);
            Coord2d[] local = new Coord2d[]{Coord2d.of(-half.x, -half.y), Coord2d.of(half.x, -half.y), Coord2d.of(half.x, half.y), Coord2d.of(-half.x, half.y)};
            Coord2d[] world = new Coord2d[4];

            for (int i = 0; i < 4; i++) {
               Coord2d p = local[i];
               world[i] = Coord2d.of(rc.x + p.x * cs - p.y * sn, rc.y + p.x * sn + p.y * cs);
            }

            return world;
         }
      }
   }

   public static Coord2d[] knownFurnitureFootprint(String resid, Coord2d rc, double a) {
      if (rc == null) {
         return null;
      } else {
         Coord2d half = FURNITURE_HALF.get(baseResid(resid));
         if (half == null) {
            return null;
         } else {
            double cs = Math.cos(a);
            double sn = Math.sin(a);
            Coord2d[] local = new Coord2d[]{Coord2d.of(-half.x, -half.y), Coord2d.of(half.x, -half.y), Coord2d.of(half.x, half.y), Coord2d.of(-half.x, half.y)};
            Coord2d[] world = new Coord2d[4];

            for (int i = 0; i < 4; i++) {
               Coord2d p = local[i];
               world[i] = Coord2d.of(rc.x + p.x * cs - p.y * sn, rc.y + p.x * sn + p.y * cs);
            }

            return world;
         }
      }
   }

   public static boolean isHollowRing(List<Coord2d[]> polygons) {
      return LocalPlanner.isHollowRing(polygons);
   }


   private static double[] aabb(List<Coord2d[]> polygons) {
      return LocalPlanner.aabb(polygons);
   }


   private static Coord2d[] aabbPolygon(List<Coord2d[]> polygons) {
      return LocalPlanner.aabbPolygon(polygons);
   }


   private static Coord2d[] aabbPolygon(List<Coord2d[]> polygons, double pad) {
      return LocalPlanner.aabbPolygon(polygons, pad);
   }


   private static void rasterAabb(PrototypePathfinder.Grid grid, List<Coord2d[]> polygons) {
      LocalPlanner.rasterAabb(grid, polygons);
   }


   private static void rasterAabb(PrototypePathfinder.Grid grid, List<Coord2d[]> polygons, double pad) {
      LocalPlanner.rasterAabb(grid, polygons, pad);
   }


   private static void rasterDisk(PrototypePathfinder.Grid grid, Coord2d center, double radius) {
      LocalPlanner.rasterDisk(grid, center, radius);
   }


   public static double boundingRadius(Coord2d origin, List<Coord2d[]> polygons) {
      return LocalPlanner.boundingRadius(origin, polygons);
   }


   public static Coord worldCell(Coord2d origin, Coord2d p) {
      return LocalPlanner.worldCell(origin, p);
   }


   public static int dilationCells(double agentRadius) {
      return LocalPlanner.dilationCells(agentRadius);
   }


   public static void applyClearanceCost(double[] cost, boolean[] solid, int w, int h) {
      LocalPlanner.applyClearanceCost(cost, solid, w, h);
   }


   public static Coord2d approach(Coord2d from, Coord2d dest, double dist) {
      return LocalPlanner.approach(from, dest, dist);
   }


   public static void dilate(boolean[] blocked, int w, int h, int radiusCells) {
      LocalPlanner.dilate(blocked, w, h, radiusCells);
   }


   public static double agentRadius(Gob player) {
      double r = 4.5;
      try {
         double b = boundingRadius(player.rc, Hitbox.worldPolygons(player));
         if (b > 0.5) {
            r = b;
         }
      } catch (Loading var3) {
      }
      return TerrainPolicy.agentRadius(WorldGraphAdapter.mobility(player), r);
   }

   public static void rasterPolygon(boolean[] blocked, Coord2d origin, int w, int h, Coord2d[] polygon, double pad) {
      LocalPlanner.rasterPolygon(blocked, origin, w, h, polygon, pad);
   }


   public static void rasterPolygon(boolean[] blocked, Coord2d origin, int w, int h, Coord2d[] polygon, List<Coord2d[]> body) {
      LocalPlanner.rasterPolygon(blocked, origin, w, h, polygon, body);
   }


   private static void rasterPolygon(PrototypePathfinder.Grid grid, Coord2d[] polygon, double pad) {
      LocalPlanner.rasterPolygon(grid, polygon, pad);
   }


   private static void rasterPolygon(PrototypePathfinder.Grid grid, Coord2d[] polygon, List<Coord2d[]> body) {
      LocalPlanner.rasterPolygon(grid, polygon, body);
   }


   public static List<Coord2d[]> playerBodyOrigin(Gob player) {
      if (player != null && player.rc != null) {
         List<Coord2d[]> world;
         try {
            world = Hitbox.worldPolygons(player);
         } catch (Loading var7) {
            return Collections.emptyList();
         }

         List<Coord2d[]> out = new ArrayList<>();

         for (Coord2d[] poly : world) {
            if (poly != null && poly.length >= 2) {
               Coord2d[] rel = new Coord2d[poly.length];

               for (int i = 0; i < poly.length; i++) {
                  rel[i] = poly[i] == null ? Coord2d.of(0.0, 0.0) : poly[i].sub(player.rc);
               }

               out.add(rel);
            }
         }

         return out;
      } else {
         return Collections.emptyList();
      }
   }

   public static boolean bodyHits(Coord2d at, List<Coord2d[]> body, Coord2d[] obstacle) {
      return LocalPlanner.bodyHits(at, body, obstacle);
   }


   public static boolean polygonsOverlap(Coord2d[] a, Coord2d[] b) {
      return LocalPlanner.polygonsOverlap(a, b);
   }


   public static boolean pointInside(Coord2d p, Coord2d[] poly) {
      return LocalPlanner.pointInside(p, poly);
   }


   public static double edgeDistance(Coord2d p, Coord2d[] poly) {
      return LocalPlanner.edgeDistance(p, poly);
   }


   private static boolean parseBool(String s, Boolean current) {
      String t = s.trim().toLowerCase();
      switch (t) {
         case "true":
         case "on":
         case "1":
         case "yes":
            return true;
         case "false":
         case "off":
         case "0":
         case "no":
            return false;
         case "toggle":
         case "t":
            return current == null || !current;
         default:
            return current == null || !current;
      }
   }

   private static List<String> nearBlockers(GameUI gui, Gob player, Coord2d start) {
      List<String> out = new ArrayList<>();
      if (gui != null && gui.ui != null && gui.ui.sess != null) {
         try {
            synchronized (gui.ui.sess.glob.oc) {
               for (Gob gob : gui.ui.sess.glob.oc) {
                  if (gob != null && gob != player && !gob.virtual && gob.id >= 0L && gob.rc != null) {
                     try {
                        if (!Hitbox.passable(gob)) {
                           List<Coord2d[]> polys = collisionPolygons(gob);
                           if (polys != null && !polys.isEmpty()) {
                              double best = Double.POSITIVE_INFINITY;
                              Iterator var10 = polys.iterator();

                              while (true) {
                                 if (var10.hasNext()) {
                                    Coord2d[] poly = (Coord2d[])var10.next();
                                    if (poly == null || poly.length < 2) {
                                       continue;
                                    }

                                    if (!pointInside(start, poly)) {
                                       best = Math.min(best, edgeDistance(start, poly));
                                       continue;
                                    }

                                    best = 0.0;
                                 }

                                 if (!(best > 16.0)) {
                                    out.add(String.format("%s %.1ft", displayName(gob), best / MCache.tilesz.x));
                                    if (out.size() >= 6) {
                                       return out;
                                    }
                                 }
                                 break;
                              }
                           }
                        }
                     } catch (Loading var13) {
                     }
                  }
               }
            }
         } catch (Loading var15) {
         }

         return out;
      } else {
         return out;
      }
   }

   private static String clipAlong(GameUI gui, Gob player, List<Coord2d> path) {
      if (gui != null && gui.ui != null && path != null && path.size() >= 2) {
         try {
            synchronized (gui.ui.sess.glob.oc) {
               for (Gob gob : gui.ui.sess.glob.oc) {
                  if (gob != null && gob != player && !gob.virtual && gob.id >= 0L && gob.rc != null) {
                     try {
                        if (!Hitbox.passable(gob)) {
                           List<Coord2d[]> polys = collisionPolygons(gob);
                           if (solidFootprint(gob.resid()) || isHollowRing(polys) || furnitureFootprint(gob.resid())) {
                              double pad = solidFootprint(gob.resid()) ? 2.0 : 1.0;
                              Coord2d[] box = aabbPolygon(polys, pad);

                              for (int i = 0; i < path.size() - 1; i++) {
                                 if (segmentHitsPolygon(path.get(i), path.get(i + 1), box, 1.0)) {
                                    return displayName(gob);
                                 }
                              }
                           } else {
                              for (Coord2d[] poly : polys) {
                                 if (poly != null && poly.length >= 2) {
                                    double clipPad = wallClearance(gob.resid()) ? 2.0 : 1.0;

                                    for (int ix = 0; ix < path.size() - 1; ix++) {
                                       if (segmentHitsPolygon(path.get(ix), path.get(ix + 1), poly, clipPad)) {
                                          return displayName(gob);
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     } catch (Loading var13) {
                     }
                  }
               }
            }
         } catch (Loading var15) {
         }

         return "";
      } else {
         return "";
      }
   }

   private static List<Coord2d> smooth(List<Coord2d> raw, PrototypePathfinder.Grid grid, boolean[] los) {
      return LocalPlanner.smooth(raw, grid, los);
   }


   private static boolean canSmooth(Coord2d a, Coord2d b, PrototypePathfinder.Grid grid, boolean[] los) {
      return LocalPlanner.canSmooth(a, b, grid, los);
   }


   private static boolean besideLos(PrototypePathfinder.Grid grid, boolean[] blocked, int x, int y) {
      return LocalPlanner.besideLos(grid, blocked, x, y);
   }


   private static boolean segmentHitsPolygon(Coord2d a, Coord2d b, Coord2d[] poly, double pad) {
      return LocalPlanner.segmentHitsPolygon(a, b, poly, pad);
   }


   private static boolean segmentsCross(Coord2d a, Coord2d b, Coord2d c, Coord2d d) {
      return LocalPlanner.segmentsCross(a, b, c, d);
   }


   public static boolean segmentHitsSquare(Coord2d a, Coord2d b, double minx, double miny, double maxx, double maxy) {
      return LocalPlanner.segmentHitsSquare(a, b, minx, miny, maxx, maxy);
   }


   public static boolean lineOfSightClear(Coord2d origin, int w, int h, boolean[] blocked, Coord2d a, Coord2d b) {
      return LocalPlanner.lineOfSightClear(origin, w, h, blocked, a, b);
   }


   private static boolean clear(Coord2d a, Coord2d b, PrototypePathfinder.Grid grid, boolean[] blocked) {
      return LocalPlanner.clear(a, b, grid, blocked);
   }


   static {
      BUILDING_HALF.put("gfx/terobjs/arch/logcabin", Coord2d.of(24.0, 18.0));
      BUILDING_HALF.put("gfx/terobjs/arch/timberhouse", Coord2d.of(36.0, 26.0));
      BUILDING_HALF.put("gfx/terobjs/arch/stonestead", Coord2d.of(46.0, 34.0));
      BUILDING_HALF.put("gfx/terobjs/arch/stonemansion", Coord2d.of(50.0, 38.0));
      BUILDING_HALF.put("gfx/terobjs/arch/greathall", Coord2d.of(80.0, 40.0));
      BUILDING_HALF.put("gfx/terobjs/arch/greathall-door", Coord2d.of(8.0, 36.0));
      BUILDING_HALF.put("gfx/terobjs/arch/stonetower", Coord2d.of(38.0, 38.0));
      BUILDING_HALF.put("gfx/terobjs/arch/windmill", Coord2d.of(30.0, 30.0));
      BUILDING_HALF.put("gfx/terobjs/arch/greenhouse", Coord2d.of(24.0, 18.0));
      BUILDING_HALF.put("gfx/terobjs/arch/stonehut", Coord2d.of(22.0, 16.0));
      FURNITURE_HALF.put("gfx/terobjs/cupboard", Coord2d.of(5.0, 5.0));
      FURNITURE_HALF.put("gfx/terobjs/studydesk", Coord2d.of(6.0, 16.0));
      FURNITURE_HALF.put("gfx/terobjs/studydesk-big", Coord2d.of(6.0, 16.0));
      FURNITURE_HALF.put("gfx/terobjs/barrel", Coord2d.of(4.0, 4.0));
   }

   static final class ClipResult {
      final List<Coord2d> targets = new ArrayList<>();
      final List<Boolean> clipped = new ArrayList<>();
   }

   public static final class GobGeom {
      public long id;
      public String name = "";
      public String resid = "";
      public Coord2d rc;
      public double a;
      public double gobDist;
      public double polyDist = Double.POSITIVE_INFINITY;
      public double hitboxDist = Double.POSITIVE_INFINITY;
      public boolean cupboard;
      public boolean boulder;
      public boolean caveTransition;
      public boolean doorGate;
      public boolean visitorGate;
      public boolean passable;
      public int gateState = -1;
      public List<Coord2d[]> movement = Collections.emptyList();
      public List<Coord2d[]> obst = Collections.emptyList();
      public List<Coord2d[]> placement = Collections.emptyList();
      public List<Coord2d[]> hitbox = Collections.emptyList();
      public List<Coord2d[]> collision = Collections.emptyList();
      public Coord2d[] fallback;
      public String collisionSource = "";
      public String collisionReason = "";
   }

   static final class Grid extends NavGrid {
      Grid(Coord2d origin, int w, int h) {
         super(origin, w, h);
      }

      Grid(NavGrid src) {
         super(src.origin, src.w, src.h);
         System.arraycopy(src.blocked, 0, this.blocked, 0, src.blocked.length);
         System.arraycopy(src.cost, 0, this.cost, 0, src.cost.length);
      }
   }


   public static final class NearbyGob {
      public final long id;
      public final String name;
      public final String resid;
      public final double dist;
      public final Coord2d rc;

      private NearbyGob(long id, String name, String resid, double dist, Coord2d rc) {
         this.id = id;
         this.name = name;
         this.resid = resid;
         this.dist = dist;
         this.rc = rc;
      }

      public String label() {
         return String.format("%s  %.1ft", this.name, this.dist / MCache.tilesz.x);
      }
   }

   private static final class OccupancyBuild {
      final PrototypePathfinder.Grid grid;
      final boolean[] terrain;
      final String[] terrainNames;
      final boolean[] solid;
      final boolean[] dilated;
      final int obstacles;
      final List<Coord2d> hazards;
      final List<Coord2d[]> solids;

      private OccupancyBuild(
         PrototypePathfinder.Grid grid, boolean[] terrain, String[] terrainNames, boolean[] solid, boolean[] dilated, int obstacles, List<Coord2d> hazards, List<Coord2d[]> solids
      ) {
         this.grid = grid;
         this.terrain = terrain;
         this.terrainNames = terrainNames;
         this.solid = solid;
         this.dilated = dilated;
         this.obstacles = obstacles;
         this.hazards = hazards == null ? Collections.emptyList() : hazards;
         this.solids = solids == null ? Collections.<Coord2d[]>emptyList() : solids;
      }

      static PrototypePathfinder.OccupancyBuild build(GameUI gui, PrototypePathfinder.Grid grid, Gob player, List<Coord2d[]> debugPolys) {
         String[] terrainNames = new String[grid.blocked.length];
         boolean[] terrain = PrototypePathfinder.rasterTerrain(gui, grid, terrainNames);
         List<Coord2d[]> solids = debugPolys != null ? debugPolys : new ArrayList<Coord2d[]>();
         int obstacles = PrototypePathfinder.rasterGobs(gui, grid, player, solids, null);
         boolean[] solid = Arrays.copyOf(grid.blocked, grid.blocked.length);
         List<Coord2d[]> body = PrototypePathfinder.playerBodyOrigin(player);
         PrototypePathfinder.rasterGobs(gui, grid, player, null, body);
         PrototypePathfinder.inflateMasked(grid, terrain, body);
         boolean[] dilated = Arrays.copyOf(grid.blocked, grid.blocked.length);
         return new PrototypePathfinder.OccupancyBuild(grid, terrain, terrainNames, solid, dilated, obstacles, movingHazards(gui, player), solids);
      }
   }

   public static final class Plan {
      public final List<Coord2d> waypoints;
      public final boolean complete;
      public final boolean snapped;
      public final int expanded;
      public final int obstacles;
      public final PrototypePathfinder.Plan.Status status;

      private Plan(List<Coord2d> waypoints, boolean complete, boolean snapped, int expanded, int obstacles, PrototypePathfinder.Plan.Status status) {
         this.waypoints = waypoints;
         this.complete = complete;
         this.snapped = snapped;
         this.expanded = expanded;
         this.obstacles = obstacles;
         this.status = status;
      }

      private Plan(List<Coord2d> waypoints, boolean complete, int expanded, int obstacles, PrototypePathfinder.Plan.Status status) {
         this(waypoints, complete, false, expanded, obstacles, status);
      }

      public static PrototypePathfinder.Plan from(haven.nav.NavPlan n) {
         if (n == null) {
            return new PrototypePathfinder.Plan(Collections.emptyList(), false, 0, 0, PrototypePathfinder.Plan.Status.FAILED);
         }
         return new PrototypePathfinder.Plan(
            n.smoothedRoute, n.complete, n.snapped, n.expanded, n.obstacles, PrototypePathfinder.Plan.Status.valueOf(n.status.name())
         );
      }

      public static PrototypePathfinder.Plan direct(Coord2d from, Coord2d dest) {
         List<Coord2d> waypoints = new ArrayList<>();
         if (from != null) {
            waypoints.add(from);
         }

         if (dest != null) {
            waypoints.add(dest);
         }

         return new PrototypePathfinder.Plan(waypoints, true, false, 0, 0, PrototypePathfinder.Plan.Status.REACHED);
      }

      public static PrototypePathfinder.Plan of(List<Coord2d> waypoints) {
         if (waypoints == null) {
            waypoints = Collections.emptyList();
         }

         return new PrototypePathfinder.Plan(new ArrayList<>(waypoints), true, false, 0, 0, PrototypePathfinder.Plan.Status.REACHED);
      }

      public static PrototypePathfinder.Plan trimEnd(PrototypePathfinder.Plan plan, Coord2d avoid, double minDist) {
         if (plan != null && avoid != null) {
            List<Coord2d> waypoints = new ArrayList<>(plan.waypoints);

            while (waypoints.size() >= 2 && waypoints.get(waypoints.size() - 1).dist(avoid) < minDist) {
               waypoints.remove(waypoints.size() - 1);
            }

            return new PrototypePathfinder.Plan(waypoints, plan.complete, plan.snapped, plan.expanded, plan.obstacles, plan.status);
         } else {
            return plan;
         }
      }

      static PrototypePathfinder.Plan fabricated(
         List<Coord2d> waypoints, boolean complete, boolean snapped, int expanded, int obstacles, PrototypePathfinder.Plan.Status status
      ) {
         return new PrototypePathfinder.Plan(new ArrayList<>(waypoints), complete, snapped, expanded, obstacles, status);
      }

      public static enum Status {
         REACHED,
         CLIPPED,
         SNAPPED,
         PARTIAL,
         FAILED;
      }
   }

   public static final class Scene implements OccupancyView {
      public Coord2d origin;
      public int w;
      public int h;
      public double cell = 2.75;
      public double radius;
      public Coord2d player;
      public Coord playerCell;
      public boolean moving;
      public boolean playerInSolid;
      public boolean playerInDilated;
      public String terrain = "";
      public String[] terrainCells;
      public int solidCount;
      public int dilatedCount;
      public int obstacles;
      public PathfinderLog.Occupancy occupancy;
      public List<PrototypePathfinder.GobGeom> gobs = new ArrayList<>();
      public List<Coord2d[]> solids = Collections.emptyList();
      public List<Coord2d[]> playerBody = Collections.emptyList();

      @Override
      public Coord2d origin() {
         return this.origin;
      }

      @Override
      public int width() {
         return this.w;
      }

      @Override
      public int height() {
         return this.h;
      }

      @Override
      public double cell() {
         return this.cell;
      }

      @Override
      public double radius() {
         return this.radius;
      }

      @Override
      public byte[] occupancy() {
         return this.occupancy == null ? null : this.occupancy.occ;
      }

      @Override
      public Coord2d player() {
         return this.player;
      }

      @Override
      public Coord cellOf(Coord2d p) {
         return p != null && this.origin != null
            ? Coord.of((int)Math.floor((p.x - this.origin.x) / this.cell), (int)Math.floor((p.y - this.origin.y) / this.cell))
            : null;
      }

      public byte at(Coord2d p) {
         Coord c = this.cellOf(p);
         return this.occupancy != null && c != null ? this.occupancy.at(c.x, c.y) : 1;
      }

      public boolean bodyFree(Coord2d p) {
         byte v = this.at(p);
         return v == 0 || v == 3;
      }

      public boolean standable(Coord2d p) {
         return this.at(p) != 1;
      }

      public boolean aisleStand(Coord2d p) {
         if (!this.standable(p)) {
            return false;
         } else if (this.bodyFree(p)) {
            return true;
         } else {
            Coord c = this.cellOf(p);
            if (this.occupancy != null && c != null) {
               int[] dxs = new int[]{1, -1, 0, 0};
               int[] dys = new int[]{0, 0, 1, -1};

               for (int i = 0; i < 4; i++) {
                  byte v = this.occupancy.at(c.x + dxs[i], c.y + dys[i]);
                  if (v == 0 || v == 3) {
                     return true;
                  }
               }

               return false;
            } else {
               return true;
            }
         }
      }

      public List<Coord2d> approachStands(Coord2d gobRc, double boxHalf) {
         List<Coord2d> out = new ArrayList<>();
         if (gobRc != null && this.occupancy != null && this.origin != null) {
            Coord gc = this.cellOf(gobRc);
            if (gc == null) {
               return out;
            } else {
               int reach = (int)Math.ceil(boxHalf / this.cell) + 2;
               int[] dx = new int[]{0, 1, 0, -1};
               int[] dy = new int[]{-1, 0, 1, 0};
               Set<String> seen = new HashSet<>();

               for (int y = gc.y - reach; y <= gc.y + reach; y++) {
                  for (int x = gc.x - reach; x <= gc.x + reach; x++) {
                     Coord2d wc = this.origin.add(((double)x + 0.5) * this.cell, ((double)y + 0.5) * this.cell);
                     if (!(Math.abs(wc.x - gobRc.x) > boxHalf) && !(Math.abs(wc.y - gobRc.y) > boxHalf)) {
                        for (int d = 0; d < 4; d++) {
                           int nx = x + dx[d];
                           int ny = y + dy[d];
                           Coord2d np = this.origin.add(((double)nx + 0.5) * this.cell, ((double)ny + 0.5) * this.cell);
                           if ((!(Math.abs(np.x - gobRc.x) <= boxHalf) || !(Math.abs(np.y - gobRc.y) <= boxHalf)) && this.aisleStand(np)) {
                              String key = nx + "," + ny;
                              if (seen.add(key)) {
                                 out.add(np);
                              }
                           }
                        }
                     }
                  }
               }

               return out;
            }
         } else {
            return out;
         }
      }
   }
}
