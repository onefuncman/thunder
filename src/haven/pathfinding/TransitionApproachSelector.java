package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class TransitionApproachSelector {
   public static final double CELL = 2.75;
   public static final double TILE = 11.0;
   public static final int MAX_OPENNESS = 8;
   static final double PROXIMITY_WEIGHT = 1000.0;
   public static final int DEFAULT_MAX_EXPANDED = 100000;
   public static final double DEFAULT_FOOTPRINT_HALF = 5.5;
   public static final Set<String> WATER_TILES = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("gfx/tiles/water", "gfx/tiles/deep", "gfx/tiles/owater", "gfx/tiles/odeep", "gfx/tiles/odeeper"))
   );

   public static boolean isBoulderResid(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      return name != null && name.equals("gfx/terobjs/boulder");
   }

   public static TransitionApproachSelector.TransitionKind caveTransitionKind(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      if (name == null) {
         return null;
      } else {
         switch (name) {
            case "gfx/terobjs/minehole":
               return TransitionApproachSelector.TransitionKind.MINEHOLE;
            case "gfx/terobjs/ladder":
               return TransitionApproachSelector.TransitionKind.LADDER;
            case "gfx/terobjs/arch/cellardoor":
               return TransitionApproachSelector.TransitionKind.CELLAR_DOOR;
            case "gfx/terobjs/arch/cellarstairs":
            case "gfx/terobjs/arch/downstairs":
            case "gfx/terobjs/arch/upstairs":
               return TransitionApproachSelector.TransitionKind.CELLAR_STAIRS;
            default:
               return null;
         }
      }
   }

   public static boolean isCaveTransitionResid(String resid) {
      return caveTransitionKind(resid) != null;
   }

   public static TransitionApproachSelector.DoorGateKind doorGateKind(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      if (name == null) {
         return null;
      } else {
         switch (name) {
            case "gfx/terobjs/arch/palisadegate":
            case "gfx/terobjs/arch/palisadebiggate":
               return TransitionApproachSelector.DoorGateKind.PALISADE_GATE;
            case "gfx/terobjs/arch/polegate":
            case "gfx/terobjs/arch/polebiggate":
               return TransitionApproachSelector.DoorGateKind.TWIG_GATE;
            case "gfx/terobjs/arch/drystonewallgate":
            case "gfx/terobjs/arch/drystonewallbiggate":
               return TransitionApproachSelector.DoorGateKind.STONE_GATE;
            case "gfx/terobjs/arch/brickwallgate":
            case "gfx/terobjs/arch/brickbiggate":
               return TransitionApproachSelector.DoorGateKind.BRICK_GATE;
            default:
               return name.endsWith("-door") ? TransitionApproachSelector.DoorGateKind.DOOR : null;
         }
      }
   }

   public static boolean isDoorGateResid(String resid) {
      return doorGateKind(resid) != null;
   }

   public static TransitionApproachSelector.TransitionState transitionState(int gateState) {
      if (gateState == 1) {
         return TransitionApproachSelector.TransitionState.OPEN;
      } else {
         return gateState == 0 ? TransitionApproachSelector.TransitionState.CLOSED : TransitionApproachSelector.TransitionState.UNKNOWN;
      }
   }

   public static boolean isGateResid(String resid) {
      String name = PrototypePathfinder.baseResid(resid);
      if (name == null) {
         return false;
      } else {
         switch (name) {
            case "gfx/terobjs/arch/palisadegate":
            case "gfx/terobjs/arch/palisadebiggate":
            case "gfx/terobjs/arch/polegate":
            case "gfx/terobjs/arch/polebiggate":
            case "gfx/terobjs/arch/drystonewallgate":
            case "gfx/terobjs/arch/drystonewallbiggate":
            case "gfx/terobjs/arch/brickwallgate":
            case "gfx/terobjs/arch/brickbiggate":
               return true;
            default:
               return false;
         }
      }
   }

   public static boolean isWaterTileName(String tileName) {
      return tileName != null && WATER_TILES.contains(tileName);
   }

   public static boolean isUnknownTileName(String tileName) {
      if (tileName == null) {
         return true;
      } else {
         return !tileName.isEmpty() && !tileName.equals("loading") ? TerrainPolicy.isUnknownTile(tileName) : true;
      }
   }

   private TransitionApproachSelector() {
   }

   public static TransitionApproachSelector.Selection boulderApproach(PrototypePathfinder.Scene scene) {
      return select(scene, TransitionApproachSelector.TransitionProfile.BOULDER, TransitionApproachSelector.ApproachRange.boulder(), 100000);
   }

   public static TransitionApproachSelector.Selection boulderApproach(
      PrototypePathfinder.Scene scene, TransitionApproachSelector.ApproachRange range, int maxExpanded
   ) {
      return select(scene, TransitionApproachSelector.TransitionProfile.BOULDER, range, maxExpanded);
   }

   public static TransitionApproachSelector.Selection caveTransitionApproach(PrototypePathfinder.Scene scene) {
      return select(scene, TransitionApproachSelector.TransitionProfile.CAVE_TRANSITION, TransitionApproachSelector.ApproachRange.caveTransition(), 100000);
   }

   public static TransitionApproachSelector.Selection caveTransitionApproach(
      PrototypePathfinder.Scene scene, TransitionApproachSelector.ApproachRange range, int maxExpanded
   ) {
      return select(scene, TransitionApproachSelector.TransitionProfile.CAVE_TRANSITION, range, maxExpanded);
   }

   public static TransitionApproachSelector.Selection doorGateApproach(PrototypePathfinder.Scene scene) {
      return select(scene, TransitionApproachSelector.TransitionProfile.DOOR_GATE, TransitionApproachSelector.ApproachRange.doorGate(), 100000);
   }

   public static TransitionApproachSelector.Selection doorGateApproach(
      PrototypePathfinder.Scene scene, TransitionApproachSelector.ApproachRange range, int maxExpanded
   ) {
      return select(scene, TransitionApproachSelector.TransitionProfile.DOOR_GATE, range, maxExpanded);
   }

   public static TransitionApproachSelector.Selection waterlineApproach(PrototypePathfinder.Scene scene) {
      return select(scene, TransitionApproachSelector.TransitionProfile.WATERLINE, TransitionApproachSelector.ApproachRange.waterline(), 100000);
   }

   public static TransitionApproachSelector.Selection waterlineApproach(
      PrototypePathfinder.Scene scene, TransitionApproachSelector.ApproachRange range, int maxExpanded
   ) {
      return select(scene, TransitionApproachSelector.TransitionProfile.WATERLINE, range, maxExpanded);
   }

   public static TransitionApproachSelector.Selection select(
      PrototypePathfinder.Scene scene, TransitionApproachSelector.TransitionProfile profile, TransitionApproachSelector.ApproachRange range, int maxExpanded
   ) {
      Objects.requireNonNull(profile, "profile");
      if (!profile.supported) {
         return refuse(
            profile,
            TransitionApproachSelector.Refusal.UNSUPPORTED_PROFILE,
            0,
            String.format("%s (%s) — no selection is attempted", profile, profile.description)
         );
      } else if (scene == null) {
         return refuse(profile, TransitionApproachSelector.Refusal.NO_SCENE, 0, "no scene injected");
      } else if (scene.occupancy != null && scene.origin != null && scene.w > 0 && scene.h > 0) {
         byte[] occ = scene.occupancy.occ;
         if (occ == null || occ.length < scene.w * scene.h) {
            return refuse(
               profile,
               TransitionApproachSelector.Refusal.NO_OCCUPANCY,
               0,
               String.format(
                  "occupancy lattice is %d cells but the grid is %dx%d = %d cells", occ == null ? 0 : occ.length, scene.w, scene.h, scene.w * scene.h
               )
            );
         } else if (scene.player == null) {
            return refuse(
               profile, TransitionApproachSelector.Refusal.PLAYER_UNKNOWN, 0, "scene has no player anchor (approach selection is anchored at the player)"
            );
         } else if (range == null
            || Double.isNaN(range.minDist)
            || Double.isNaN(range.maxDist)
            || Double.isNaN(range.minStandoff)
            || Double.isNaN(range.maxStandoff)
            || range.minDist < 0.0
            || !(range.maxDist > range.minDist)
            || range.minStandoff < 0.0
            || !(range.maxStandoff > range.minStandoff)) {
            return refuse(
               profile,
               TransitionApproachSelector.Refusal.BOUNDS_INVALID,
               0,
               String.format("range must satisfy 0 <= minDist < maxDist and 0 <= minStandoff < maxStandoff (got %s)", range)
            );
         } else if (maxExpanded <= 0) {
            return refuse(
               profile,
               TransitionApproachSelector.Refusal.BOUNDS_INVALID,
               0,
               String.format("reachability budget %d expansions — no route search permitted", maxExpanded)
            );
         } else {
            Coord start = scene.cellOf(scene.player);
            if (start != null && start.x >= 0 && start.y >= 0 && start.x < scene.w && start.y < scene.h) {
               switch (profile) {
                  case BOULDER:
                     return boulderCore(scene, range, start, maxExpanded);
                  case CAVE_TRANSITION:
                     return caveTransitionCore(scene, range, start, maxExpanded);
                  case DOOR_GATE:
                     return doorGateCore(scene, range, start, maxExpanded);
                  case WATERLINE:
                     return waterlineCore(scene, range, start, maxExpanded);
                  default:
                     throw new AssertionError(profile);
               }
            } else {
               return refuse(
                  profile,
                  TransitionApproachSelector.Refusal.PLAYER_OFF_GRID,
                  0,
                  String.format("player %s outside the %dx%d occupancy grid", scene.player, scene.w, scene.h)
               );
            }
         }
      } else {
         return refuse(profile, TransitionApproachSelector.Refusal.NO_OCCUPANCY, 0, "scene has no usable occupancy lattice (origin/w/h/occupancy)");
      }
   }

   private static TransitionApproachSelector.Selection boulderCore(
      PrototypePathfinder.Scene scene, TransitionApproachSelector.ApproachRange range, Coord start, int maxExpanded
   ) {
      return fixtureCore(scene, TransitionApproachSelector.TransitionProfile.BOULDER, boulderGobs(scene), range, start, maxExpanded);
   }

   private static TransitionApproachSelector.Selection caveTransitionCore(
      PrototypePathfinder.Scene scene, TransitionApproachSelector.ApproachRange range, Coord start, int maxExpanded
   ) {
      return fixtureCore(scene, TransitionApproachSelector.TransitionProfile.CAVE_TRANSITION, caveTransitionGobs(scene), range, start, maxExpanded);
   }

   private static TransitionApproachSelector.Selection doorGateCore(
      PrototypePathfinder.Scene scene, TransitionApproachSelector.ApproachRange range, Coord start, int maxExpanded
   ) {
      return fixtureCore(scene, TransitionApproachSelector.TransitionProfile.DOOR_GATE, doorGateGobs(scene), range, start, maxExpanded);
   }

   private static TransitionApproachSelector.Selection fixtureCore(
      PrototypePathfinder.Scene scene,
      TransitionApproachSelector.TransitionProfile profile,
      List<PrototypePathfinder.GobGeom> fixtures,
      TransitionApproachSelector.ApproachRange range,
      Coord start,
      int maxExpanded
   ) {
      boolean exactGeometry = profile == TransitionApproachSelector.TransitionProfile.CAVE_TRANSITION
         || profile == TransitionApproachSelector.TransitionProfile.DOOR_GATE;
      int w = scene.w;
      int h = scene.h;
      int[] open = NavigationTestSpotSelector.openness(scene);
      int obsOpen = 0;
      int obsClosed = 0;
      int unknownState = 0;
      if (exactGeometry) {
         int unknown = 0;
         int inside = 0;
         int visitor = 0;

         for (PrototypePathfinder.GobGeom b : fixtures) {
            if (b != null && b.rc != null) {
               if (!hasFootprintGeometry(b)) {
                  unknown++;
               } else if (insideBox(footprintBox(b), scene.player)) {
                  inside++;
               }

               if (profile == TransitionApproachSelector.TransitionProfile.DOOR_GATE) {
                  if (b.visitorGate) {
                     visitor++;
                  }

                  switch (transitionState(b.gateState)) {
                     case OPEN:
                        obsOpen++;
                        break;
                     case CLOSED:
                        obsClosed++;
                        break;
                     default:
                        unknownState++;
                  }
               }
            }
         }

         if (visitor > 0) {
            return refuse(
               profile,
               TransitionApproachSelector.Refusal.VISITOR_GATE,
               fixtures.size(),
               String.format(
                  "%d classified %s, %d carrying a visitor flag — conservatively refused; ownership is never inferred",
                  fixtures.size(),
                  fixtureNoun(profile),
                  visitor
               )
            );
         }

         if (unknown > 0) {
            return refuse(
               profile,
               TransitionApproachSelector.Refusal.GEOMETRY_UNKNOWN,
               fixtures.size(),
               String.format(
                  "%d classified %s, %d with unobservable footprint geometry (no hitbox polygons) — refusing rather than guessing a standoff ring",
                  fixtures.size(),
                  fixtureNoun(profile),
                  unknown
               )
            );
         }

         if (inside > 0) {
            return refuse(
               profile,
               TransitionApproachSelector.Refusal.AMBIGUOUS,
               fixtures.size(),
               String.format(
                  "%d classified %s, player %s inside a fixture footprint box — approach is ambiguous", fixtures.size(), fixtureNoun(profile), scene.player
               )
            );
         }
      }

      List<TransitionApproachSelector.Candidate> cands = new ArrayList<>();
      int scanned = 0;
      int inBand = 0;
      int reachable = 0;

      for (PrototypePathfinder.GobGeom bx : fixtures) {
         if (bx != null && bx.rc != null) {
            TransitionApproachSelector.TransitionKind kind = profile == TransitionApproachSelector.TransitionProfile.CAVE_TRANSITION
               ? caveTransitionKind(bx.resid)
               : null;
            TransitionApproachSelector.DoorGateKind dgk = profile == TransitionApproachSelector.TransitionProfile.DOOR_GATE ? doorGateKind(bx.resid) : null;
            TransitionApproachSelector.TransitionState tstate = profile == TransitionApproachSelector.TransitionProfile.DOOR_GATE
               ? transitionState(bx.gateState)
               : null;
            double[] box = footprintBox(bx);
            double bxx = (box[0] + box[2]) * 0.5;
            double by = (box[1] + box[3]) * 0.5;
            double hx = (box[2] - box[0]) * 0.5;
            double hy = (box[3] - box[1]) * 0.5;
            double footprintTiles = Math.max(box[2] - box[0], box[3] - box[1]) / 11.0;

            for (int y = 0; y < h; y++) {
               for (int x = 0; x < w; x++) {
                  scanned++;
                  int idx = y * w + x;
                  if (!isBodyBlocked(scene, idx)) {
                     Coord2d center = cellCenter(scene, x, y);
                     double dx = Math.abs(center.x - bxx) - hx;
                     double dy = Math.abs(center.y - by) - hy;
                     if ((!(dx <= range.minStandoff) || !(dy <= range.minStandoff)) && !(dx > range.maxStandoff) && !(dy > range.maxStandoff)) {
                        double dist = scene.player.dist(center);
                        if (!(dist < range.minDist) && !(dist > range.maxDist)) {
                           inBand++;
                           GridAStar.Result r = NavigationTestSpotSelector.reachable(scene, start, Coord.of(x, y), maxExpanded);
                           if (r.complete && !r.cells.isEmpty()) {
                              reachable++;
                              double standoff = Math.max(dx, dy);
                              String side = sideOf(bxx, by, center);
                              int oc = Math.min(open[idx], 8);
                              double score = (range.maxDist - dist) * 1000.0 + (double)oc;
                              cands.add(
                                 new TransitionApproachSelector.Candidate(
                                    Coord.of(x, y),
                                    center,
                                    score,
                                    String.format(
                                       "%s side of fixture, standoff %.1fu, footprint %.1f tiles, %.1fu from player, route %d cells / %d expanded",
                                       side,
                                       standoff,
                                       footprintTiles,
                                       dist,
                                       r.cells.size(),
                                       r.expanded
                                    ),
                                    r.cells,
                                    r.expanded,
                                    side,
                                    standoff,
                                    footprintTiles,
                                    kind,
                                    dgk,
                                    tstate
                                 )
                              );
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      String noun = fixtureNoun(profile);
      if (fixtures.isEmpty()) {
         return refuse(
            profile,
            TransitionApproachSelector.Refusal.NO_FIXTURE,
            fixtures.size(),
            String.format("scanned %d cells: 0 classified %s in the observed scene's gob geometry", scanned, noun)
         );
      } else if (!cands.isEmpty()) {
         sortCandidates(cands);
         TransitionApproachSelector.Candidate best = cands.get(0);
         String evidence;
         if (profile == TransitionApproachSelector.TransitionProfile.DOOR_GATE) {
            evidence = String.format(
               "%s approach: selected cell %s world (%.1f, %.1f): %s; %d candidates (best first, %d reachable) of %d cells scanned across %d classified %s (%d observably open, %d observably closed, %d unknown state); transition state of the winning fixture: %s",
               profile.name(),
               best.tile,
               best.world.x,
               best.world.y,
               best.note,
               cands.size(),
               reachable,
               scanned,
               fixtures.size(),
               noun,
               obsOpen,
               obsClosed,
               unknownState,
               best.transitionState
            );
         } else {
            evidence = String.format(
               "%s approach: selected cell %s world (%.1f, %.1f): %s; %d candidates (best first, %d reachable) of %d cells scanned across %d classified %s",
               profile.name(),
               best.tile,
               best.world.x,
               best.world.y,
               best.note,
               cands.size(),
               reachable,
               scanned,
               fixtures.size(),
               noun
            );
         }

         return new TransitionApproachSelector.Selection(
            profile,
            TransitionApproachSelector.Status.SELECTED,
            null,
            best.world,
            best.tile,
            fixtures.size(),
            best.side,
            best.standoff,
            best.footprintTiles,
            scene.player.dist(best.world),
            cands,
            evidence,
            best.kind,
            best.doorGateKind,
            best.transitionState,
            0,
            0
         );
      } else {
         return inBand == 0
            ? refuse(
               profile,
               TransitionApproachSelector.Refusal.NO_CANDIDATE,
               fixtures.size(),
               String.format(
                  "scanned %d cells: %d classified %s, 0 body-free adjacent approach cells in band %s (ring standoff %.1f..%.1fu)",
                  scanned,
                  fixtures.size(),
                  noun,
                  range,
                  range.minStandoff,
                  range.maxStandoff
               )
            )
            : refuse(
               profile,
               TransitionApproachSelector.Refusal.NO_REACHABLE,
               fixtures.size(),
               String.format(
                  "scanned %d cells: %d classified %s, %d body-free adjacent approach cells in band, 0 reachable by a bounded occupancy route",
                  scanned,
                  fixtures.size(),
                  noun,
                  inBand
               )
            );
      }
   }

   private static TransitionApproachSelector.Selection waterlineCore(
      PrototypePathfinder.Scene scene, TransitionApproachSelector.ApproachRange range, Coord start, int maxExpanded
   ) {
      int w = scene.w;
      int h = scene.h;
      String[] terrain = scene.terrainCells;
      if (terrain != null && terrain.length >= w * h) {
         int pidx = start.y * w + start.x;
         if (isUnknownTileName(terrain[pidx])) {
            return waterlineRefuse(
               TransitionApproachSelector.Refusal.TERRAIN_UNKNOWN,
               0,
               0,
               String.format(
                  "player cell %s is on unknown/unclassified terrain (%s) — cannot confirm the player is not on water", start, tileDisplay(terrain[pidx])
               )
            );
         } else {
            boolean[] isWater = new boolean[w * h];
            int water = 0;

            for (int y = 0; y < h; y++) {
               for (int x = 0; x < w; x++) {
                  int idx = y * w + x;
                  if (isWaterTileName(terrain[idx])) {
                     isWater[idx] = true;
                     water++;
                  }
               }
            }

            if (isWater[pidx]) {
               return waterlineRefuse(
                  TransitionApproachSelector.Refusal.AMBIGUOUS,
                  water,
                  0,
                  String.format("player %s is standing on confirmed water (%s) — a land-side approach is ambiguous", scene.player, tileDisplay(terrain[pidx]))
               );
            } else if (water == 0) {
               return waterlineRefuse(
                  TransitionApproachSelector.Refusal.NO_WATER, 0, 0, "0 confirmed water cells in the observed terrain snapshot — no waterline"
               );
            } else {
               int waterline = 0;

               for (int y = 0; y < h; y++) {
                  for (int xx = 0; xx < w; xx++) {
                     if (isWater[y * w + xx]) {
                        boolean boundary = false;

                        for (int dy = -1; dy <= 1 && !boundary; dy++) {
                           for (int dx = -1; dx <= 1; dx++) {
                              if (dx != 0 || dy != 0) {
                                 int nx = xx + dx;
                                 int ny = y + dy;
                                 if (nx >= 0 && ny >= 0 && nx < w && ny < h && !isWater[ny * w + nx]) {
                                    boundary = true;
                                    if (isUnknownTileName(terrain[ny * w + nx])) {
                                       return waterlineRefuse(
                                          TransitionApproachSelector.Refusal.TERRAIN_UNKNOWN,
                                          water,
                                          0,
                                          String.format(
                                             "confirmed water cell (%d, %d) borders unknown/unclassified terrain (%s) — the waterline boundary is ambiguous",
                                             xx,
                                             y,
                                             tileDisplay(terrain[ny * w + nx])
                                          )
                                       );
                                    }
                                 }
                              }
                           }
                        }

                        if (boundary) {
                           waterline++;
                        }
                     }
                  }
               }

               int[] open = NavigationTestSpotSelector.openness(scene);
               double extent = waterExtentTiles(isWater, w, h, water);
               List<TransitionApproachSelector.Candidate> cands = new ArrayList<>();
               int scanned = 0;
               int inBand = 0;
               int reachable = 0;

               for (int y = 0; y < h; y++) {
                  for (int xxx = 0; xxx < w; xxx++) {
                     scanned++;
                     int idx = y * w + xxx;
                     if (!isBodyBlocked(scene, idx) && !isWater[idx] && !isUnknownTileName(terrain[idx])) {
                        Coord anchor = null;

                        for (int dy = -1; dy <= 1 && anchor == null; dy++) {
                           for (int dxx = -1; dxx <= 1; dxx++) {
                              if (dxx != 0 || dy != 0) {
                                 int nx = xxx + dxx;
                                 int ny = y + dy;
                                 if (nx >= 0 && ny >= 0 && nx < w && ny < h && isWater[ny * w + nx]) {
                                    anchor = Coord.of(nx, ny);
                                    break;
                                 }
                              }
                           }
                        }

                        if (anchor != null) {
                           Coord2d center = cellCenter(scene, xxx, y);
                           double dist = scene.player.dist(center);
                           if (!(dist < range.minDist) && !(dist > range.maxDist)) {
                              inBand++;
                              GridAStar.Result r = NavigationTestSpotSelector.reachable(scene, start, Coord.of(xxx, y), maxExpanded);
                              if (r.complete && !r.cells.isEmpty()) {
                                 reachable++;
                                 Coord2d wc = cellCenter(scene, anchor.x, anchor.y);
                                 double standoff = Math.max(0.0, wc.dist(center) - 1.375);
                                 String side = sideOf(wc.x, wc.y, center);
                                 int oc = Math.min(open[idx], 8);
                                 double score = (range.maxDist - dist) * 1000.0 + (double)oc;
                                 cands.add(
                                    new TransitionApproachSelector.Candidate(
                                       Coord.of(xxx, y),
                                       center,
                                       score,
                                       String.format(
                                          "%s side of the waterline, standoff %.1fu from the water cell, water extent %.1f tiles, %.1fu from player, route %d cells / %d expanded",
                                          side,
                                          standoff,
                                          extent,
                                          dist,
                                          r.cells.size(),
                                          r.expanded
                                       ),
                                       r.cells,
                                       r.expanded,
                                       side,
                                       standoff,
                                       extent,
                                       null,
                                       null,
                                       null
                                    )
                                 );
                              }
                           }
                        }
                     }
                  }
               }

               if (!cands.isEmpty()) {
                  sortCandidates(cands);
                  TransitionApproachSelector.Candidate best = cands.get(0);
                  String evidence = String.format(
                     "%s approach: selected cell %s world (%.1f, %.1f): %s; %d candidates (best first, %d reachable) of %d cells scanned across %d confirmed water cells (%d on the waterline)",
                     TransitionApproachSelector.TransitionProfile.WATERLINE.name(),
                     best.tile,
                     best.world.x,
                     best.world.y,
                     best.note,
                     cands.size(),
                     reachable,
                     scanned,
                     water,
                     waterline
                  );
                  return new TransitionApproachSelector.Selection(
                     TransitionApproachSelector.TransitionProfile.WATERLINE,
                     TransitionApproachSelector.Status.SELECTED,
                     null,
                     best.world,
                     best.tile,
                     0,
                     best.side,
                     best.standoff,
                     best.footprintTiles,
                     scene.player.dist(best.world),
                     cands,
                     evidence,
                     null,
                     null,
                     null,
                     water,
                     waterline
                  );
               } else {
                  return inBand == 0
                     ? waterlineRefuse(
                        TransitionApproachSelector.Refusal.NO_CANDIDATE,
                        water,
                        waterline,
                        String.format(
                           "scanned %d cells: %d confirmed water cells (%d on the waterline), 0 body-free land-side approach cells in band %s",
                           scanned,
                           water,
                           waterline,
                           range
                        )
                     )
                     : waterlineRefuse(
                        TransitionApproachSelector.Refusal.NO_REACHABLE,
                        water,
                        waterline,
                        String.format(
                           "scanned %d cells: %d confirmed water cells (%d on the waterline), %d body-free land-side approach cells in band, 0 reachable by a bounded occupancy route",
                           scanned,
                           water,
                           waterline,
                           inBand
                        )
                     );
               }
            }
         }
      } else {
         return waterlineRefuse(
            TransitionApproachSelector.Refusal.NO_TERRAIN,
            0,
            0,
            String.format(
               "no per-cell terrain snapshot (%s) for the %dx%d grid — no water can be confirmed", terrain == null ? "null" : terrain.length + " cells", w, h
            )
         );
      }
   }

   private static double waterExtentTiles(boolean[] isWater, int w, int h, int water) {
      if (water == 0) {
         return 0.0;
      } else {
         int minx = Integer.MAX_VALUE;
         int miny = Integer.MAX_VALUE;
         int maxx = Integer.MIN_VALUE;
         int maxy = Integer.MIN_VALUE;

         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               if (isWater[y * w + x]) {
                  minx = Math.min(minx, x);
                  maxx = Math.max(maxx, x);
                  miny = Math.min(miny, y);
                  maxy = Math.max(maxy, y);
               }
            }
         }

         return (double)Math.max(maxx - minx + 1, maxy - miny + 1) * 2.75 / 11.0;
      }
   }

   private static String tileDisplay(String name) {
      return name == null ? "null" : "\"" + name + "\"";
   }

   private static TransitionApproachSelector.Selection waterlineRefuse(
      TransitionApproachSelector.Refusal refusal, int waterCells, int waterlineCells, String why
   ) {
      return new TransitionApproachSelector.Selection(
         TransitionApproachSelector.TransitionProfile.WATERLINE,
         TransitionApproachSelector.Status.REFUSED,
         refusal,
         null,
         null,
         0,
         null,
         -1.0,
         -1.0,
         -1.0,
         Collections.emptyList(),
         String.format("%s refused: %s — %s", TransitionApproachSelector.TransitionProfile.WATERLINE, refusal, why),
         null,
         null,
         null,
         waterCells,
         waterlineCells
      );
   }

   private static boolean isBodyBlocked(PrototypePathfinder.Scene scene, int idx) {
      byte[] occ = scene.occupancy.occ;
      if (idx >= 0 && idx < occ.length) {
         byte v = occ[idx];
         return v == 1 || v == 2;
      } else {
         return true;
      }
   }

   private static Coord2d cellCenter(PrototypePathfinder.Scene scene, int x, int y) {
      return Coord2d.of(scene.origin.x + ((double)x + 0.5) * scene.cell, scene.origin.y + ((double)y + 0.5) * scene.cell);
   }

   static List<PrototypePathfinder.GobGeom> boulderGobs(PrototypePathfinder.Scene scene) {
      List<PrototypePathfinder.GobGeom> out = new ArrayList<>();
      if (scene != null && scene.gobs != null) {
         for (PrototypePathfinder.GobGeom g : scene.gobs) {
            if (g != null && g.boulder && g.rc != null) {
               out.add(g);
            }
         }
      }

      out.sort(Comparator.<PrototypePathfinder.GobGeom>comparingDouble(gx -> gx.rc.x).thenComparingDouble(gx -> gx.rc.y));
      return out;
   }

   static List<PrototypePathfinder.GobGeom> caveTransitionGobs(PrototypePathfinder.Scene scene) {
      List<PrototypePathfinder.GobGeom> out = new ArrayList<>();
      if (scene != null && scene.gobs != null) {
         for (PrototypePathfinder.GobGeom g : scene.gobs) {
            if (g != null && g.caveTransition && g.rc != null) {
               out.add(g);
            }
         }
      }

      out.sort(Comparator.<PrototypePathfinder.GobGeom>comparingDouble(gx -> gx.rc.x).thenComparingDouble(gx -> gx.rc.y));
      return out;
   }

   static List<PrototypePathfinder.GobGeom> doorGateGobs(PrototypePathfinder.Scene scene) {
      List<PrototypePathfinder.GobGeom> out = new ArrayList<>();
      if (scene != null && scene.gobs != null) {
         for (PrototypePathfinder.GobGeom g : scene.gobs) {
            if (g != null && g.doorGate && g.rc != null) {
               out.add(g);
            }
         }
      }

      out.sort(Comparator.<PrototypePathfinder.GobGeom>comparingDouble(gx -> gx.rc.x).thenComparingDouble(gx -> gx.rc.y));
      return out;
   }

   static boolean hasFootprintGeometry(PrototypePathfinder.GobGeom b) {
      if (b != null && b.hitbox != null) {
         for (Coord2d[] poly : b.hitbox) {
            if (poly != null) {
               for (Coord2d p : poly) {
                  if (p != null) {
                     return true;
                  }
               }
            }
         }

         return false;
      } else {
         return false;
      }
   }

   static boolean insideBox(double[] box, Coord2d p) {
      return box != null && p != null ? p.x > box[0] && p.x < box[2] && p.y > box[1] && p.y < box[3] : false;
   }

   static String fixtureNoun(TransitionApproachSelector.TransitionProfile profile) {
      switch (profile) {
         case CAVE_TRANSITION:
            return "cave transitions";
         case DOOR_GATE:
            return "doors/gates";
         default:
            return "boulders";
      }
   }

   static double[] footprintBox(PrototypePathfinder.GobGeom b) {
      double minx = Double.POSITIVE_INFINITY;
      double miny = Double.POSITIVE_INFINITY;
      double maxx = Double.NEGATIVE_INFINITY;
      double maxy = Double.NEGATIVE_INFINITY;
      boolean any = false;
      if (b != null && b.hitbox != null) {
         for (Coord2d[] poly : b.hitbox) {
            if (poly != null) {
               for (Coord2d p : poly) {
                  if (p != null) {
                     any = true;
                     minx = Math.min(minx, p.x);
                     miny = Math.min(miny, p.y);
                     maxx = Math.max(maxx, p.x);
                     maxy = Math.max(maxy, p.y);
                  }
               }
            }
         }
      }

      if (any) {
         return new double[]{minx, miny, maxx, maxy};
      } else {
         double rc = b != null && b.rc != null ? 5.5 : 0.0;
         double cx = b != null && b.rc != null ? b.rc.x : 0.0;
         double cy = b != null && b.rc != null ? b.rc.y : 0.0;
         return new double[]{cx - rc, cy - rc, cx + rc, cy + rc};
      }
   }

   static String sideOf(double bx, double by, Coord2d p) {
      double dx = p.x - bx;
      double dy = p.y - by;
      if (Math.abs(dx) >= Math.abs(dy)) {
         return dx < 0.0 ? "W" : "E";
      } else {
         return dy < 0.0 ? "N" : "S";
      }
   }

   private static void sortCandidates(List<TransitionApproachSelector.Candidate> cands) {
      cands.sort((a, b) -> {
         int c = Double.compare(b.score, a.score);
         if (c != 0) {
            return c;
         } else {
            c = Integer.compare(a.tile.y, b.tile.y);
            return c != 0 ? c : Integer.compare(a.tile.x, b.tile.x);
         }
      });
   }

   private static TransitionApproachSelector.Selection refuse(
      TransitionApproachSelector.TransitionProfile profile, TransitionApproachSelector.Refusal refusal, int fixtureCount, String why
   ) {
      return new TransitionApproachSelector.Selection(
         profile,
         TransitionApproachSelector.Status.REFUSED,
         refusal,
         null,
         null,
         fixtureCount,
         null,
         -1.0,
         -1.0,
         -1.0,
         Collections.emptyList(),
         String.format("%s refused: %s — %s", profile, refusal, why),
         null,
         null,
         null,
         0,
         0
      );
   }

   public static final class ApproachRange {
      public final double minDist;
      public final double maxDist;
      public final double minStandoff;
      public final double maxStandoff;

      public ApproachRange(double minDist, double maxDist, double minStandoff, double maxStandoff) {
         this.minDist = minDist;
         this.maxDist = maxDist;
         this.minStandoff = minStandoff;
         this.maxStandoff = maxStandoff;
      }

      public static TransitionApproachSelector.ApproachRange boulder() {
         return new TransitionApproachSelector.ApproachRange(5.5, 49.5, 0.0, 8.25);
      }

      public static TransitionApproachSelector.ApproachRange caveTransition() {
         return new TransitionApproachSelector.ApproachRange(5.5, 49.5, 0.0, 8.25);
      }

      public static TransitionApproachSelector.ApproachRange doorGate() {
         return new TransitionApproachSelector.ApproachRange(5.5, 49.5, 0.0, 8.25);
      }

      public static TransitionApproachSelector.ApproachRange waterline() {
         return new TransitionApproachSelector.ApproachRange(5.5, 49.5, 0.0, 2.75);
      }

      @Override
      public String toString() {
         return String.format("ApproachRange[%.1f..%.1fu from player, standoff %.1f..%.1fu]", this.minDist, this.maxDist, this.minStandoff, this.maxStandoff);
      }
   }

   public static final class Candidate {
      public final Coord tile;
      public final Coord2d world;
      public final double score;
      public final String note;
      public final List<Coord> route;
      public final int routeExpanded;
      public final String side;
      public final double standoff;
      public final double footprintTiles;
      public final TransitionApproachSelector.TransitionKind kind;
      public final TransitionApproachSelector.DoorGateKind doorGateKind;
      public final TransitionApproachSelector.TransitionState transitionState;

      private Candidate(
         Coord tile,
         Coord2d world,
         double score,
         String note,
         List<Coord> route,
         int routeExpanded,
         String side,
         double standoff,
         double footprintTiles,
         TransitionApproachSelector.TransitionKind kind,
         TransitionApproachSelector.DoorGateKind doorGateKind,
         TransitionApproachSelector.TransitionState transitionState
      ) {
         this.tile = Objects.requireNonNull(tile, "tile");
         this.world = Objects.requireNonNull(world, "world");
         this.score = score;
         this.note = note;
         this.route = route == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(route));
         this.routeExpanded = routeExpanded;
         this.side = side;
         this.standoff = standoff;
         this.footprintTiles = footprintTiles;
         this.kind = kind;
         this.doorGateKind = doorGateKind;
         this.transitionState = transitionState;
      }

      @Override
      public String toString() {
         return this.transitionState != null
            ? String.format(
               "Candidate[approach=%s world=(%.1f, %.1f), score=%.1f, %s, transitionState=%s]",
               this.tile,
               this.world.x,
               this.world.y,
               this.score,
               this.note,
               this.transitionState
            )
            : String.format("Candidate[approach=%s world=(%.1f, %.1f), score=%.1f, %s]", this.tile, this.world.x, this.world.y, this.score, this.note);
      }
   }

   public static enum DoorGateKind {
      PALISADE_GATE,
      TWIG_GATE,
      STONE_GATE,
      BRICK_GATE,
      DOOR;
   }

   public static enum Refusal {
      UNSUPPORTED_PROFILE,
      NO_SCENE,
      NO_OCCUPANCY,
      PLAYER_UNKNOWN,
      PLAYER_OFF_GRID,
      BOUNDS_INVALID,
      NO_FIXTURE,
      NO_CANDIDATE,
      NO_REACHABLE,
      NO_TERRAIN,
      NO_WATER,
      TERRAIN_UNKNOWN,
      GEOMETRY_UNKNOWN,
      VISITOR_GATE,
      STATE_UNKNOWN,
      AMBIGUOUS;
   }

   public static final class Selection {
      public final TransitionApproachSelector.TransitionProfile profile;
      public final TransitionApproachSelector.Status status;
      public final TransitionApproachSelector.Refusal refusal;
      public final Coord2d approachWorld;
      public final Coord approachTile;
      public final int fixtureCount;
      public final String side;
      public final double standoff;
      public final double footprintTiles;
      public final double playerDist;
      public final TransitionApproachSelector.TransitionKind kind;
      public final TransitionApproachSelector.DoorGateKind doorGateKind;
      public final TransitionApproachSelector.TransitionState transitionState;
      public final int waterCells;
      public final int waterlineCells;
      public final List<TransitionApproachSelector.Candidate> candidates;
      public final String evidence;

      private Selection(
         TransitionApproachSelector.TransitionProfile profile,
         TransitionApproachSelector.Status status,
         TransitionApproachSelector.Refusal refusal,
         Coord2d approachWorld,
         Coord approachTile,
         int fixtureCount,
         String side,
         double standoff,
         double footprintTiles,
         double playerDist,
         List<TransitionApproachSelector.Candidate> candidates,
         String evidence,
         TransitionApproachSelector.TransitionKind kind,
         TransitionApproachSelector.DoorGateKind doorGateKind,
         TransitionApproachSelector.TransitionState transitionState,
         int waterCells,
         int waterlineCells
      ) {
         this.profile = profile;
         this.status = status;
         this.refusal = refusal;
         this.approachWorld = approachWorld;
         this.approachTile = approachTile;
         this.fixtureCount = fixtureCount;
         this.side = side;
         this.standoff = standoff;
         this.footprintTiles = footprintTiles;
         this.playerDist = playerDist;
         this.kind = kind;
         this.doorGateKind = doorGateKind;
         this.transitionState = transitionState;
         this.waterCells = waterCells;
         this.waterlineCells = waterlineCells;
         this.candidates = candidates == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(candidates));
         this.evidence = evidence;
      }

      public boolean selected() {
         return this.status == TransitionApproachSelector.Status.SELECTED;
      }

      public boolean refused() {
         return this.status == TransitionApproachSelector.Status.REFUSED;
      }

      @Override
      public String toString() {
         return this.status == TransitionApproachSelector.Status.SELECTED
            ? String.format(
               "Selection[%s SELECTED approach=%s world=(%.1f, %.1f), %d fixtures, %d candidates%s]",
               this.profile,
               this.approachTile,
               this.approachWorld.x,
               this.approachWorld.y,
               this.fixtureCount,
               this.candidates.size(),
               this.transitionState == null ? "" : ", transitionState=" + this.transitionState
            )
            : String.format("Selection[%s REFUSED %s]", this.profile, this.refusal);
      }
   }

   public static enum Status {
      SELECTED,
      REFUSED;
   }

   public static enum TransitionKind {
      MINEHOLE,
      LADDER,
      CELLAR_DOOR,
      CELLAR_STAIRS;
   }

   public static enum TransitionProfile {
      BOULDER(true, "adjacent body-free locally reachable approach position beside a classified boulder footprint"),
      CAVE_TRANSITION(true, "adjacent body-free locally reachable approach position beside a classified minehole/ladder/cellar-door/cellar-stairs footprint"),
      DOOR_GATE(
         true,
         "adjacent body-free locally reachable approach position beside a classified door/gate footprint (observable open/closed or unobservable unknown state — approach-only; UNKNOWN stays a hard refusal for interaction/transition consumers)"
      ),
      WATERLINE(true, "adjacent body-free locally reachable land-side stand beside a confirmed water cell (never on water, never a boat)"),
      DOOR(false, "declared future — door transitions change topology (typed UNSUPPORTED_PROFILE refusal)"),
      GATE(false, "declared future — gate transitions change topology (typed UNSUPPORTED_PROFILE refusal)"),
      MINEHOLE(false, "declared future — minehole/cave transitions change topology (typed UNSUPPORTED_PROFILE refusal)"),
      BOAT(false, "declared future — boat transitions change topology (typed UNSUPPORTED_PROFILE refusal)"),
      WATER_CROSSING(false, "declared future — water-crossing transitions change topology (typed UNSUPPORTED_PROFILE refusal)"),
      CART(false, "declared future — cart transitions change topology (typed UNSUPPORTED_PROFILE refusal)");

      public final boolean supported;
      public final String description;

      private TransitionProfile(boolean supported, String description) {
         this.supported = supported;
         this.description = description;
      }
   }

   public static enum TransitionState {
      OPEN,
      CLOSED,
      UNKNOWN;
   }
}
