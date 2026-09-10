package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import haven.pathfinding.PathfinderLog.Occupancy;
import haven.pathfinding.PrototypePathfinder.GobGeom;
import haven.pathfinding.PrototypePathfinder.Scene;
import haven.pathfinding.TransitionApproachSelector.ApproachRange;
import haven.pathfinding.TransitionApproachSelector.Candidate;
import haven.pathfinding.TransitionApproachSelector.DoorGateKind;
import haven.pathfinding.TransitionApproachSelector.Refusal;
import haven.pathfinding.TransitionApproachSelector.Selection;
import haven.pathfinding.TransitionApproachSelector.Status;
import haven.pathfinding.TransitionApproachSelector.TransitionKind;
import haven.pathfinding.TransitionApproachSelector.TransitionProfile;
import haven.pathfinding.TransitionApproachSelector.TransitionState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TransitionApproachSelectorTest {
   private static final double CELL = 2.75;
   private static final double BOX_HALF = 5.5;

   private static Scene scene(int w, int h, char[][] g, double px, double py) {
      boolean[] solid = new boolean[w * h];
      boolean[] dilated = new boolean[w * h];

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            char c = g[y][x];
            if (c == '#') {
               solid[y * w + x] = true;
            } else if (c == '+') {
               dilated[y * w + x] = true;
            }
         }
      }

      Scene s = new Scene();
      s.origin = Coord2d.of(0.0, 0.0);
      s.w = w;
      s.h = h;
      s.cell = 2.75;
      s.player = Coord2d.of(px, py);
      s.playerCell = s.cellOf(s.player);
      s.occupancy = Occupancy.capture(s.origin, w, h, 2.75, solid, dilated, dilated, s.playerCell, null, null, Collections.emptyList());
      return s;
   }

   private static double cellWorld(int i) {
      return ((double)i + 0.5) * 2.75;
   }

   private static char[][] fill(int w, int h, char c) {
      char[][] g = new char[h][w];

      for (char[] row : g) {
         Arrays.fill(row, c);
      }

      return g;
   }

   private static GobGeom boulder(int cx, int cy, long id) {
      GobGeom g = new GobGeom();
      g.id = id;
      g.boulder = true;
      g.resid = "gfx/terobjs/boulder";
      g.rc = Coord2d.of(cellWorld(cx), cellWorld(cy));
      Coord2d[] box = new Coord2d[]{g.rc.add(-5.5, -5.5), g.rc.add(5.5, -5.5), g.rc.add(5.5, 5.5), g.rc.add(-5.5, 5.5)};
      g.hitbox = List.<Coord2d[]>of(box);
      return g;
   }

   private static void boulderFootprint(char[][] g, int cx, int cy, int n) {
      for (int dy = -n; dy <= n; dy++) {
         for (int dx = -n; dx <= n; dx++) {
            g[cy + dy][cx + dx] = '#';
         }
      }
   }

   @Test
   void classifiesBoulderResidExactly() {
      Assertions.assertTrue(TransitionApproachSelector.isBoulderResid("gfx/terobjs/boulder"));
      Assertions.assertTrue(
         TransitionApproachSelector.isBoulderResid("gfx/terobjs/boulder[flint]"),
         "bracketed variants match on the base resource id (the same convention cupboards use)"
      );
      Assertions.assertFalse(TransitionApproachSelector.isBoulderResid("gfx/terobjs/cupboard"));
      Assertions.assertFalse(TransitionApproachSelector.isBoulderResid("gfx/terobjs/arch/logcabin"));
      Assertions.assertFalse(
         TransitionApproachSelector.isBoulderResid("gfx/tiles/rocks/boulder"), "the boulder terrain tile is terrain, not a chippable boulder gob"
      );
      Assertions.assertFalse(TransitionApproachSelector.isBoulderResid(""));
      Assertions.assertFalse(TransitionApproachSelector.isBoulderResid(null));
   }

   @Test
   void boulderGobsAreSortedDeterministicallyAndDroppedWhenUnusable() {
      Scene s = new Scene();
      s.gobs = new ArrayList();
      s.gobs.add(boulder(20, 10, 3L));
      s.gobs.add(boulder(10, 20, 2L));
      s.gobs.add(boulder(10, 5, 1L));
      GobGeom notBoulder = boulder(0, 0, 9L);
      notBoulder.boulder = false;
      s.gobs.add(notBoulder);
      GobGeom noRc = boulder(30, 30, 8L);
      noRc.rc = null;
      s.gobs.add(noRc);
      List<GobGeom> got = TransitionApproachSelector.boulderGobs(s);
      Assertions.assertEquals(3, got.size());
      Assertions.assertEquals(Coord2d.of(cellWorld(10), cellWorld(5)), got.get(0).rc);
      Assertions.assertEquals(Coord2d.of(cellWorld(10), cellWorld(20)), got.get(1).rc);
      Assertions.assertEquals(Coord2d.of(cellWorld(20), cellWorld(10)), got.get(2).rc);
      Assertions.assertEquals(Collections.emptyList(), TransitionApproachSelector.boulderGobs(null));
      Assertions.assertEquals(Collections.emptyList(), TransitionApproachSelector.boulderGobs(new Scene()));
   }

   private static Scene boulderScene() {
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      s.gobs.add(boulder(12, 12, 77L));
      return s;
   }

   @Test
   void selectsAdjacentBodyFreeLocallyReachableApproachNeverTheBoulder() {
      Scene s = boulderScene();
      Selection sel = TransitionApproachSelector.boulderApproach(s);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(TransitionProfile.BOULDER, sel.profile);
      Assertions.assertNull(sel.refusal);
      Assertions.assertEquals(1, sel.fixtureCount);
      Assertions.assertNotNull(sel.approachTile);
      Assertions.assertNotNull(sel.approachWorld);
      Assertions.assertTrue(s.bodyFree(sel.approachWorld), "the selected approach must be body-free");
      Assertions.assertTrue(sel.standoff >= 0.0, "standoff evidence must be present");
      Assertions.assertTrue(sel.footprintTiles > 0.0, "footprint evidence must be present");
      Assertions.assertTrue(sel.playerDist >= 5.5 && sel.playerDist <= 49.500000001, "the approach must lie within the default player band");
      Assertions.assertTrue("NESW".contains(sel.side), sel.side);
      Assertions.assertTrue(sel.evidence.contains("BOULDER approach"), sel.evidence);
      double dx = Math.abs(sel.approachWorld.x - cellWorld(12)) - 5.5;
      double dy = Math.abs(sel.approachWorld.y - cellWorld(12)) - 5.5;
      Assertions.assertTrue(dx > 0.0 || dy > 0.0, "the approach must not sit inside the boulder footprint");
      Assertions.assertTrue(Math.max(dx, dy) <= 8.250000001, "the approach must hug the footprint box");
      Assertions.assertNotEquals(Coord2d.of(cellWorld(12), cellWorld(12)), sel.approachWorld, "the approach is never the boulder position");
      Assertions.assertFalse(sel.candidates.isEmpty());
      Assertions.assertEquals(sel.approachTile, ((Candidate)sel.candidates.get(0)).tile);
      Assertions.assertEquals(sel.approachWorld, ((Candidate)sel.candidates.get(0)).world);
      Assertions.assertEquals(((Candidate)sel.candidates.get(0)).route.get(((Candidate)sel.candidates.get(0)).route.size() - 1), sel.approachTile);
      Assertions.assertEquals(Coord.of(5, 5), ((Candidate)sel.candidates.get(0)).route.get(0), "the verified route starts at the player cell");

      for (int i = 1; i < sel.candidates.size(); i++) {
         Assertions.assertTrue(
            ((Candidate)sel.candidates.get(i - 1)).score >= ((Candidate)sel.candidates.get(i)).score, "candidates must be ordered by score descending"
         );
      }

      for (Candidate c : sel.candidates) {
         Assertions.assertTrue(s.bodyFree(c.world), "every candidate must be body-free");
         Assertions.assertTrue(c.route.size() >= 1 && !c.route.isEmpty());
         Assertions.assertTrue(c.routeExpanded > 0, "every candidate was A*-verified");
         Assertions.assertTrue("NESW".contains(c.side), c.side);
         Assertions.assertTrue(c.standoff > 0.0, "every candidate sits in the adjacency ring");
         Assertions.assertTrue(c.footprintTiles > 0.0, "every candidate carries footprint evidence");
         Assertions.assertFalse(c.note.contains("id="), "candidate notes carry no gob ids");
      }

      Assertions.assertFalse(sel.evidence.contains("id="), "selection evidence carries no gob ids");
      Assertions.assertFalse(sel.evidence.contains("77"), "selection evidence never names a gob id");
      Assertions.assertEquals(Coord.of(7, 7), sel.approachTile, sel.evidence);
   }

   @Test
   void selectionIsDeterministicAcrossGobIterationOrder() {
      Selection a = TransitionApproachSelector.boulderApproach(boulderScene());
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      GobGeom other = boulder(30, 30, 5L);
      s.gobs.add(0, other);
      s.gobs.add(boulder(12, 12, 99L));
      Selection b = TransitionApproachSelector.boulderApproach(s);
      Assertions.assertTrue(b.selected(), b.evidence);
      Assertions.assertEquals(a.approachTile, b.approachTile, "the winning approach must not depend on gob order");
      Assertions.assertEquals(2, b.fixtureCount, "both classified boulders are counted");
      Assertions.assertEquals(1, a.fixtureCount);
      Assertions.assertEquals(a.approachTile, b.approachTile, "the extra far boulder never steals the winner");
      Assertions.assertEquals(a.candidates.size(), b.candidates.size());
      char[][] g2 = fill(40, 40, ' ');
      boulderFootprint(g2, 12, 12, 1);
      Scene s2 = scene(40, 40, g2, cellWorld(5), cellWorld(5));
      s2.gobs.add(boulder(12, 12, 12345L));
      Selection c = TransitionApproachSelector.boulderApproach(s2);
      Assertions.assertTrue(c.selected(), c.evidence);
      Assertions.assertEquals(1, c.fixtureCount);
      Assertions.assertEquals(a.approachTile, c.approachTile);
      Assertions.assertEquals(a.evidence, c.evidence, "the evidence must not depend on gob order or ids");
      Assertions.assertEquals(a.toString(), c.toString());
   }

   @Test
   void selectionIsImmutableAndSideEffectFree() {
      Scene s = boulderScene();
      byte[] before = Arrays.copyOf(s.occupancy.occ, s.occupancy.occ.length);
      Selection a = TransitionApproachSelector.boulderApproach(s);
      Selection b = TransitionApproachSelector.boulderApproach(s);
      Assertions.assertArrayEquals(before, s.occupancy.occ, "the occupancy lattice must be untouched");
      Assertions.assertEquals(a.approachTile, b.approachTile, "repeated calls with identical inputs are identical");
      Assertions.assertEquals(a.evidence, b.evidence);
      Assertions.assertEquals(a.candidates.toString(), b.candidates.toString());
      Assertions.assertThrows(UnsupportedOperationException.class, () -> a.candidates.add((Candidate)a.candidates.get(0)), "the candidate list is unmodifiable");
      Assertions.assertThrows(
         UnsupportedOperationException.class, () -> ((Candidate)a.candidates.get(0)).route.add(Coord.of(0, 0)), "the verified route is unmodifiable"
      );
      Assertions.assertEquals(a.fixtureCount, b.fixtureCount);
   }

   @Test
   void approachPreferredSideFacesThePlayer() {
      Selection sel = TransitionApproachSelector.boulderApproach(boulderScene());
      Assertions.assertTrue(sel.side.equals("W") || sel.side.equals("S"), "winner faces the player: " + sel.side);
   }

   @Test
   void classifiesCaveTransitionResidsExactly() {
      Assertions.assertEquals(TransitionKind.MINEHOLE, TransitionApproachSelector.caveTransitionKind("gfx/terobjs/minehole"));
      Assertions.assertEquals(
         TransitionKind.MINEHOLE,
         TransitionApproachSelector.caveTransitionKind("gfx/terobjs/minehole[flint]"),
         "bracketed variants match on the base resource id"
      );
      Assertions.assertEquals(TransitionKind.LADDER, TransitionApproachSelector.caveTransitionKind("gfx/terobjs/ladder"));
      Assertions.assertEquals(TransitionKind.CELLAR_DOOR, TransitionApproachSelector.caveTransitionKind("gfx/terobjs/arch/cellardoor"));
      Assertions.assertEquals(TransitionKind.CELLAR_STAIRS, TransitionApproachSelector.caveTransitionKind("gfx/terobjs/arch/cellarstairs"));
      Assertions.assertEquals(TransitionKind.CELLAR_STAIRS, TransitionApproachSelector.caveTransitionKind("gfx/terobjs/arch/downstairs"));
      Assertions.assertEquals(TransitionKind.CELLAR_STAIRS, TransitionApproachSelector.caveTransitionKind("gfx/terobjs/arch/upstairs"));
      Assertions.assertTrue(TransitionApproachSelector.isCaveTransitionResid("gfx/terobjs/minehole"));
      Assertions.assertTrue(TransitionApproachSelector.isCaveTransitionResid("gfx/terobjs/arch/cellarstairs"));
      Assertions.assertNull(TransitionApproachSelector.caveTransitionKind("gfx/terobjs/arch/cellardoorstep"));
      Assertions.assertNull(TransitionApproachSelector.caveTransitionKind("gfx/terobjs/arch/cellardoors"));
      Assertions.assertNull(TransitionApproachSelector.caveTransitionKind("gfx/terobjs/arch/logcabin"));
      Assertions.assertNull(TransitionApproachSelector.caveTransitionKind("gfx/terobjs/arch/palisadegate"));
      Assertions.assertNull(
         TransitionApproachSelector.caveTransitionKind("gfx/tiles/cave/minehole"), "a minehole terrain tile is terrain, not a transition gob"
      );
      Assertions.assertNull(TransitionApproachSelector.caveTransitionKind("gfx/terobjs/boulder"));
      Assertions.assertFalse(TransitionApproachSelector.isCaveTransitionResid(""));
      Assertions.assertFalse(TransitionApproachSelector.isCaveTransitionResid(null));
   }

   @Test
   void caveTransitionGobsAreSortedDeterministicallyAndDroppedWhenUnusable() {
      Scene s = new Scene();
      s.gobs = new ArrayList();
      s.gobs.add(caveGob(20, 10, 3L, "gfx/terobjs/ladder"));
      s.gobs.add(caveGob(10, 20, 2L, "gfx/terobjs/arch/cellardoor"));
      s.gobs.add(caveGob(10, 5, 1L, "gfx/terobjs/minehole"));
      GobGeom notCave = caveGob(0, 0, 9L, "gfx/terobjs/minehole");
      notCave.caveTransition = false;
      s.gobs.add(notCave);
      GobGeom noRc = caveGob(30, 30, 8L, "gfx/terobjs/ladder");
      noRc.rc = null;
      s.gobs.add(noRc);
      List<GobGeom> got = TransitionApproachSelector.caveTransitionGobs(s);
      Assertions.assertEquals(3, got.size());
      Assertions.assertEquals(Coord2d.of(cellWorld(10), cellWorld(5)), got.get(0).rc);
      Assertions.assertEquals(Coord2d.of(cellWorld(10), cellWorld(20)), got.get(1).rc);
      Assertions.assertEquals(Coord2d.of(cellWorld(20), cellWorld(10)), got.get(2).rc);
      Assertions.assertEquals(Collections.emptyList(), TransitionApproachSelector.caveTransitionGobs(null));
      Assertions.assertEquals(Collections.emptyList(), TransitionApproachSelector.caveTransitionGobs(new Scene()));
   }

   private static Scene caveScene() {
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      s.gobs.add(caveGob(12, 12, 88L, "gfx/terobjs/minehole"));
      return s;
   }

   private static GobGeom caveGob(int cx, int cy, long id, String resid) {
      GobGeom g = new GobGeom();
      g.id = id;
      g.caveTransition = true;
      g.resid = resid;
      g.rc = Coord2d.of(cellWorld(cx), cellWorld(cy));
      Coord2d[] box = new Coord2d[]{g.rc.add(-5.5, -5.5), g.rc.add(5.5, -5.5), g.rc.add(5.5, 5.5), g.rc.add(-5.5, 5.5)};
      g.hitbox = List.<Coord2d[]>of(box);
      return g;
   }

   @Test
   void selectsAdjacentBodyFreeLocallyReachableCaveApproachNeverTheFixture() {
      Scene s = caveScene();
      Selection sel = TransitionApproachSelector.caveTransitionApproach(s);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(TransitionProfile.CAVE_TRANSITION, sel.profile);
      Assertions.assertNull(sel.refusal);
      Assertions.assertEquals(1, sel.fixtureCount);
      Assertions.assertEquals(TransitionKind.MINEHOLE, sel.kind, "the public facts identify the transition kind");
      Assertions.assertNotNull(sel.approachTile);
      Assertions.assertNotNull(sel.approachWorld);
      Assertions.assertTrue(s.bodyFree(sel.approachWorld), "the selected approach must be body-free");
      Assertions.assertTrue(sel.standoff >= 0.0, "standoff evidence must be present");
      Assertions.assertTrue(sel.footprintTiles > 0.0, "footprint evidence must be present");
      Assertions.assertTrue(sel.playerDist >= 5.5 && sel.playerDist <= 49.500000001, "the approach must lie within the default player band");
      Assertions.assertTrue("NESW".contains(sel.side), sel.side);
      Assertions.assertTrue(sel.evidence.contains("CAVE_TRANSITION approach"), sel.evidence);
      double dx = Math.abs(sel.approachWorld.x - cellWorld(12)) - 5.5;
      double dy = Math.abs(sel.approachWorld.y - cellWorld(12)) - 5.5;
      Assertions.assertTrue(dx > 0.0 || dy > 0.0, "the approach must not sit inside the fixture footprint");
      Assertions.assertTrue(Math.max(dx, dy) <= 8.250000001, "the approach must hug the footprint box");
      Assertions.assertNotEquals(Coord2d.of(cellWorld(12), cellWorld(12)), sel.approachWorld, "the approach is never the fixture position");
      Assertions.assertFalse(sel.candidates.isEmpty());
      Assertions.assertEquals(sel.approachTile, ((Candidate)sel.candidates.get(0)).tile);
      Assertions.assertEquals(sel.approachWorld, ((Candidate)sel.candidates.get(0)).world);
      Assertions.assertEquals(((Candidate)sel.candidates.get(0)).route.get(((Candidate)sel.candidates.get(0)).route.size() - 1), sel.approachTile);
      Assertions.assertEquals(Coord.of(5, 5), ((Candidate)sel.candidates.get(0)).route.get(0), "the verified route starts at the player cell");

      for (int i = 1; i < sel.candidates.size(); i++) {
         Assertions.assertTrue(
            ((Candidate)sel.candidates.get(i - 1)).score >= ((Candidate)sel.candidates.get(i)).score, "candidates must be ordered by score descending"
         );
      }

      for (Candidate c : sel.candidates) {
         Assertions.assertTrue(s.bodyFree(c.world), "every candidate must be body-free");
         Assertions.assertEquals(TransitionKind.MINEHOLE, c.kind);
         Assertions.assertTrue("NESW".contains(c.side), c.side);
         Assertions.assertTrue(c.standoff > 0.0, "every candidate sits in the adjacency ring");
         Assertions.assertFalse(c.note.contains("id="), "candidate notes carry no gob ids");
      }

      Assertions.assertFalse(sel.evidence.contains("id="), "selection evidence carries no gob ids");
      Assertions.assertFalse(sel.evidence.contains("88"), "selection evidence never names a gob id");
      Assertions.assertEquals(Coord.of(7, 7), sel.approachTile, sel.evidence);
      Assertions.assertNull(TransitionApproachSelector.boulderApproach(boulderScene()).kind);
   }

   @Test
   void caveSelectionIsDeterministicAcrossGobIterationOrderAndSideEffectFree() {
      Scene s = caveScene();
      byte[] before = Arrays.copyOf(s.occupancy.occ, s.occupancy.occ.length);
      Selection a = TransitionApproachSelector.caveTransitionApproach(s);
      Selection b = TransitionApproachSelector.caveTransitionApproach(s);
      Assertions.assertArrayEquals(before, s.occupancy.occ, "the occupancy lattice must be untouched");
      Assertions.assertEquals(a.approachTile, b.approachTile, "repeated calls with identical inputs are identical");
      Assertions.assertEquals(a.evidence, b.evidence);
      Assertions.assertEquals(a.kind, b.kind);
      Assertions.assertThrows(UnsupportedOperationException.class, () -> a.candidates.add((Candidate)a.candidates.get(0)), "the candidate list is unmodifiable");
      Assertions.assertThrows(
         UnsupportedOperationException.class, () -> ((Candidate)a.candidates.get(0)).route.add(Coord.of(0, 0)), "the verified route is unmodifiable"
      );
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      Scene s2 = scene(40, 40, g, cellWorld(5), cellWorld(5));
      GobGeom other = caveGob(30, 30, 6L, "gfx/terobjs/arch/cellardoor");
      s2.gobs.add(0, other);
      s2.gobs.add(caveGob(12, 12, 99L, "gfx/terobjs/minehole"));
      Selection c = TransitionApproachSelector.caveTransitionApproach(s2);
      Assertions.assertTrue(c.selected(), c.evidence);
      Assertions.assertEquals(2, c.fixtureCount, "both classified fixtures are counted");
      Assertions.assertEquals(a.approachTile, c.approachTile, "the winning approach must not depend on gob order");
      Assertions.assertEquals(TransitionKind.MINEHOLE, c.kind);
      Assertions.assertEquals(a.candidates.size(), c.candidates.size(), "the extra far fixture never steals or adds ring candidates");
      char[][] g3 = fill(40, 40, ' ');
      boulderFootprint(g3, 12, 12, 1);
      Scene s3 = scene(40, 40, g3, cellWorld(5), cellWorld(5));
      s3.gobs.add(caveGob(12, 12, 12345L, "gfx/terobjs/minehole"));
      Selection d = TransitionApproachSelector.caveTransitionApproach(s3);
      Assertions.assertTrue(d.selected(), d.evidence);
      Assertions.assertEquals(1, d.fixtureCount);
      Assertions.assertEquals(a.approachTile, d.approachTile);
      Assertions.assertEquals(a.kind, d.kind);
      Assertions.assertEquals(a.evidence, d.evidence, "the evidence must not depend on gob order or ids");
      Assertions.assertEquals(a.toString(), d.toString());
   }

   @Test
   void nearestCaveTransitionWinsWhenSeveralFixturesExist() {
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      boulderFootprint(g, 30, 30, 1);
      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      s.gobs.add(caveGob(30, 30, 4L, "gfx/terobjs/ladder"));
      s.gobs.add(caveGob(12, 12, 3L, "gfx/terobjs/minehole"));
      Selection sel = TransitionApproachSelector.caveTransitionApproach(s);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(2, sel.fixtureCount);
      Assertions.assertEquals(TransitionKind.MINEHOLE, sel.kind);
      Assertions.assertTrue(
         sel.approachWorld.dist(Coord2d.of(cellWorld(12), cellWorld(12))) < sel.approachWorld.dist(Coord2d.of(cellWorld(30), cellWorld(30))),
         "the winner approaches the near fixture"
      );
      Assertions.assertTrue(sel.evidence.contains("2 classified cave transitions"), sel.evidence);
   }

   @Test
   void caveTransitionProfileIsSupportedAndMineholeStaysDeclaredFuture() {
      Assertions.assertTrue(TransitionProfile.CAVE_TRANSITION.supported);
      Assertions.assertFalse(TransitionProfile.MINEHOLE.supported, "the minehole/cave crossing itself is never an approach-selection profile");
      Selection u = TransitionApproachSelector.select(caveScene(), TransitionProfile.MINEHOLE, ApproachRange.caveTransition(), 100000);
      Assertions.assertEquals(Status.REFUSED, u.status);
      Assertions.assertEquals(Refusal.UNSUPPORTED_PROFILE, u.refusal);
   }

   @Test
   void refusesUnknownCaveGeometryRatherThanGuessing() {
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      GobGeom noGeo = caveGob(12, 12, 21L, "gfx/terobjs/ladder");
      noGeo.hitbox = Collections.emptyList();
      s.gobs.add(noGeo);
      Selection sel = TransitionApproachSelector.caveTransitionApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.GEOMETRY_UNKNOWN, sel.refusal, sel.evidence);
      Assertions.assertEquals(1, sel.fixtureCount);
      Assertions.assertTrue(sel.evidence.contains("GEOMETRY_UNKNOWN"));
      Assertions.assertTrue(sel.evidence.contains("unobservable footprint geometry"), sel.evidence);
      Assertions.assertNull(sel.approachTile);
      Assertions.assertNull(sel.approachWorld);
      Scene b = boulderScene();
      ((GobGeom)b.gobs.get(0)).hitbox = Collections.emptyList();
      Assertions.assertTrue(
         TransitionApproachSelector.boulderApproach(b).selected(), "boulders fall back to the default disk footprint; cave transitions refuse"
      );
   }

   @Test
   void refusesAmbiguousPlayerStandingOnTheTransition() {
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      Scene s = scene(40, 40, g, cellWorld(12), cellWorld(12));
      s.gobs.add(caveGob(12, 12, 7L, "gfx/terobjs/minehole"));
      Selection sel = TransitionApproachSelector.caveTransitionApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.AMBIGUOUS, sel.refusal, sel.evidence);
      Assertions.assertEquals(1, sel.fixtureCount);
      Assertions.assertTrue(sel.evidence.contains("AMBIGUOUS"));
      Assertions.assertTrue(sel.evidence.contains("inside a fixture footprint box"), sel.evidence);
      Assertions.assertNull(sel.approachTile);
      Assertions.assertNull(sel.approachWorld);
   }

   @Test
   void refusesWhenNoCaveFixtureExists() {
      Scene s = scene(40, 40, fill(40, 40, ' '), cellWorld(5), cellWorld(5));
      Selection sel = TransitionApproachSelector.caveTransitionApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_FIXTURE, sel.refusal);
      Assertions.assertEquals(0, sel.fixtureCount);
      Assertions.assertTrue(sel.evidence.contains("NO_FIXTURE"));
      Assertions.assertTrue(sel.evidence.contains("0 classified cave transitions"));
      Assertions.assertNull(sel.approachTile);
      Assertions.assertNull(sel.approachWorld);
      Assertions.assertTrue(sel.candidates.isEmpty());
   }

   @Test
   void refusesWhenEveryCaveApproachIsBodyBlockedOrUnreachable() {
      char[][] g = fill(40, 40, ' ');

      for (int y = 7; y <= 17; y++) {
         for (int x = 7; x <= 17; x++) {
            g[y][x] = '#';
         }
      }

      Scene sealed = scene(40, 40, g, cellWorld(5), cellWorld(5));
      sealed.gobs.add(caveGob(12, 12, 1L, "gfx/terobjs/minehole"));
      Selection sealedSel = TransitionApproachSelector.caveTransitionApproach(sealed);
      Assertions.assertEquals(Status.REFUSED, sealedSel.status);
      Assertions.assertEquals(Refusal.NO_CANDIDATE, sealedSel.refusal, sealedSel.evidence);
      Assertions.assertEquals(1, sealedSel.fixtureCount);
      Assertions.assertTrue(sealedSel.evidence.contains("0 body-free adjacent approach"), sealedSel.evidence);
      char[][] w = fill(40, 40, ' ');

      for (int i = 6; i <= 17; i++) {
         w[6][i] = '#';
         w[17][i] = '#';
         w[i][6] = '#';
         w[i][17] = '#';
      }

      Scene walled = scene(40, 40, w, cellWorld(5), cellWorld(5));
      walled.gobs.add(caveGob(12, 12, 2L, "gfx/terobjs/ladder"));
      Selection sel = TransitionApproachSelector.caveTransitionApproach(walled);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_REACHABLE, sel.refusal, sel.evidence);
      Assertions.assertEquals(1, sel.fixtureCount);
      Assertions.assertTrue(sel.evidence.contains("NO_REACHABLE"));
      Assertions.assertTrue(sel.evidence.contains("0 reachable"), sel.evidence);
   }

   @Test
   void futureTransitionProfilesAreDeclaredAndNeverSelected() {
      Scene s = boulderScene();

      for (TransitionProfile p : List.of(
         TransitionProfile.DOOR,
         TransitionProfile.GATE,
         TransitionProfile.MINEHOLE,
         TransitionProfile.BOAT,
         TransitionProfile.WATER_CROSSING,
         TransitionProfile.CART
      )) {
         Assertions.assertFalse(p.supported, p + " must be declared not selectable");
         Selection sel = TransitionApproachSelector.select(s, p, ApproachRange.boulder(), 100000);
         Assertions.assertEquals(Status.REFUSED, sel.status, p + " must never be selected");
         Assertions.assertEquals(Refusal.UNSUPPORTED_PROFILE, sel.refusal, p + " refuses with the typed discriminator");
         Assertions.assertTrue(sel.evidence.contains("UNSUPPORTED_PROFILE"), sel.evidence);
         Assertions.assertTrue(sel.candidates.isEmpty());
         Assertions.assertNull(sel.approachTile);
      }

      Assertions.assertTrue(TransitionProfile.BOULDER.supported);
   }

   @Test
   void refusesMissingOrInvalidInputsPrecisely() {
      Assertions.assertEquals(Refusal.NO_SCENE, TransitionApproachSelector.boulderApproach(null).refusal);
      Scene noOcc = new Scene();
      noOcc.player = Coord2d.of(10.0, 10.0);
      Assertions.assertEquals(Refusal.NO_OCCUPANCY, TransitionApproachSelector.boulderApproach(noOcc).refusal);
      Scene noPlayer = scene(20, 20, fill(20, 20, ' '), cellWorld(5), cellWorld(5));
      noPlayer.player = null;
      Assertions.assertEquals(Refusal.PLAYER_UNKNOWN, TransitionApproachSelector.boulderApproach(noPlayer).refusal);
      Scene offGrid = scene(20, 20, fill(20, 20, ' '), cellWorld(40), cellWorld(40));
      Assertions.assertEquals(Refusal.PLAYER_OFF_GRID, TransitionApproachSelector.boulderApproach(offGrid).refusal);
      Scene s = boulderScene();
      Assertions.assertEquals(
         Refusal.BOUNDS_INVALID, TransitionApproachSelector.select(s, TransitionProfile.BOULDER, new ApproachRange(10.0, 5.0, 0.0, 3.0), 100).refusal
      );
      Assertions.assertEquals(
         Refusal.BOUNDS_INVALID, TransitionApproachSelector.select(s, TransitionProfile.BOULDER, new ApproachRange(0.0, 10.0, 5.0, 1.0), 100).refusal
      );
      Assertions.assertEquals(Refusal.BOUNDS_INVALID, TransitionApproachSelector.boulderApproach(s, ApproachRange.boulder(), 0).refusal);
   }

   @Test
   void refusesWhenNoBoulderFixtureExists() {
      Scene s = scene(40, 40, fill(40, 40, ' '), cellWorld(5), cellWorld(5));
      Selection sel = TransitionApproachSelector.boulderApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_FIXTURE, sel.refusal);
      Assertions.assertEquals(0, sel.fixtureCount);
      Assertions.assertTrue(sel.evidence.contains("NO_FIXTURE"));
      Assertions.assertTrue(sel.evidence.contains("0 classified boulders"));
      Assertions.assertNull(sel.approachTile);
      Assertions.assertNull(sel.approachWorld);
      Assertions.assertTrue(sel.candidates.isEmpty());
   }

   @Test
   void refusesWhenEveryAdjacentApproachIsBodyBlocked() {
      char[][] g = fill(40, 40, ' ');

      for (int y = 7; y <= 17; y++) {
         for (int x = 7; x <= 17; x++) {
            g[y][x] = '#';
         }
      }

      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      s.gobs.add(boulder(12, 12, 1L));
      Selection sel = TransitionApproachSelector.boulderApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_CANDIDATE, sel.refusal);
      Assertions.assertEquals(1, sel.fixtureCount, "the fixture is still classified");
      Assertions.assertTrue(sel.evidence.contains("NO_CANDIDATE"));
      Assertions.assertTrue(sel.evidence.contains("0 body-free adjacent approach"));
   }

   @Test
   void refusesWhenApproachesExistButNoneIsLocallyReachable() {
      char[][] g = fill(40, 40, ' ');

      for (int i = 6; i <= 17; i++) {
         g[6][i] = '#';
         g[17][i] = '#';
         g[i][6] = '#';
         g[i][17] = '#';
      }

      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      s.gobs.add(boulder(12, 12, 2L));
      Selection sel = TransitionApproachSelector.boulderApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_REACHABLE, sel.refusal, sel.evidence);
      Assertions.assertEquals(1, sel.fixtureCount);
      Assertions.assertTrue(sel.evidence.contains("NO_REACHABLE"));
      Assertions.assertTrue(sel.evidence.contains("body-free adjacent approach"));
      Assertions.assertTrue(sel.evidence.contains("0 reachable"));
   }

   @Test
   void nearestBoulderWinsWhenSeveralFixturesExist() {
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      boulderFootprint(g, 30, 30, 1);
      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      s.gobs.add(boulder(30, 30, 4L));
      s.gobs.add(boulder(12, 12, 3L));
      Selection sel = TransitionApproachSelector.boulderApproach(s);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(2, sel.fixtureCount);
      Assertions.assertTrue(
         sel.approachWorld.dist(Coord2d.of(cellWorld(12), cellWorld(12))) < sel.approachWorld.dist(Coord2d.of(cellWorld(30), cellWorld(30))),
         "the winner approaches the near boulder"
      );
      Assertions.assertTrue(sel.evidence.contains("2 classified boulders"), sel.evidence);
   }

   @Test
   void classifiesDoorGateResidsExactly() {
      Assertions.assertEquals(DoorGateKind.PALISADE_GATE, TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/palisadegate"));
      Assertions.assertEquals(DoorGateKind.PALISADE_GATE, TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/palisadebiggate"));
      Assertions.assertEquals(
         DoorGateKind.PALISADE_GATE,
         TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/palisadegate[oak]"),
         "bracketed variants match on the base resource id"
      );
      Assertions.assertEquals(DoorGateKind.TWIG_GATE, TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/polegate"));
      Assertions.assertEquals(DoorGateKind.TWIG_GATE, TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/polebiggate"));
      Assertions.assertEquals(DoorGateKind.STONE_GATE, TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/drystonewallgate"));
      Assertions.assertEquals(DoorGateKind.STONE_GATE, TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/drystonewallbiggate"));
      Assertions.assertEquals(DoorGateKind.BRICK_GATE, TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/brickwallgate"));
      Assertions.assertEquals(DoorGateKind.BRICK_GATE, TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/brickbiggate"));
      Assertions.assertEquals(DoorGateKind.DOOR, TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/greathall-door"));
      Assertions.assertEquals(DoorGateKind.DOOR, TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/greathall-door[oak]"));
      Assertions.assertTrue(TransitionApproachSelector.isDoorGateResid("gfx/terobjs/arch/brickbiggate"));
      Assertions.assertTrue(TransitionApproachSelector.isDoorGateResid("gfx/terobjs/arch/greathall-door"));
      Assertions.assertTrue(TransitionApproachSelector.isGateResid("gfx/terobjs/arch/palisadegate"));
      Assertions.assertTrue(TransitionApproachSelector.isGateResid("gfx/terobjs/arch/polebiggate"));
      Assertions.assertTrue(TransitionApproachSelector.isGateResid("gfx/terobjs/arch/brickbiggate"));
      Assertions.assertFalse(TransitionApproachSelector.isGateResid("gfx/terobjs/arch/greathall-door"), "doors are never gates");
      Assertions.assertNull(TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/cellardoor"), "the cellar door belongs to the CAVE_TRANSITION profile");
      Assertions.assertNull(TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/cellarstairs"));
      Assertions.assertNull(TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/cellardoorstep"));
      Assertions.assertNull(TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/cellardoors"));
      Assertions.assertNull(
         TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/logcabin"), "a building gob with a door offset table is not a door/gate fixture"
      );
      Assertions.assertNull(TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/stonetower"));
      Assertions.assertNull(
         TransitionApproachSelector.doorGateKind("gfx/terobjs/arch/woodgate"), "a -gate that is not one of the eight supported resids is never classified"
      );
      Assertions.assertNull(TransitionApproachSelector.doorGateKind("gfx/terobjs/boulder"));
      Assertions.assertNull(TransitionApproachSelector.doorGateKind("gfx/terobjs/minehole"));
      Assertions.assertNull(TransitionApproachSelector.doorGateKind("gfx/tiles/terobjs/palisadegate"), "a terrain tile is terrain, not a door/gate gob");
      Assertions.assertNull(TransitionApproachSelector.doorGateKind(""));
      Assertions.assertNull(TransitionApproachSelector.doorGateKind(null));
      Assertions.assertFalse(TransitionApproachSelector.isDoorGateResid(""));
      Assertions.assertFalse(TransitionApproachSelector.isDoorGateResid(null));
   }

   @Test
   void doorGateGobsAreSortedDeterministicallyAndDroppedWhenUnusable() {
      Scene s = new Scene();
      s.gobs = new ArrayList();
      s.gobs.add(doorGateGob(20, 10, 3L, "gfx/terobjs/arch/brickbiggate"));
      s.gobs.add(doorGateGob(10, 20, 2L, "gfx/terobjs/arch/greathall-door"));
      s.gobs.add(doorGateGob(10, 5, 1L, "gfx/terobjs/arch/palisadegate"));
      GobGeom notDoorGate = doorGateGob(0, 0, 9L, "gfx/terobjs/arch/polegate");
      notDoorGate.doorGate = false;
      s.gobs.add(notDoorGate);
      GobGeom noRc = doorGateGob(30, 30, 8L, "gfx/terobjs/arch/polebiggate");
      noRc.rc = null;
      s.gobs.add(noRc);
      List<GobGeom> got = TransitionApproachSelector.doorGateGobs(s);
      Assertions.assertEquals(3, got.size());
      Assertions.assertEquals(Coord2d.of(cellWorld(10), cellWorld(5)), got.get(0).rc);
      Assertions.assertEquals(Coord2d.of(cellWorld(10), cellWorld(20)), got.get(1).rc);
      Assertions.assertEquals(Coord2d.of(cellWorld(20), cellWorld(10)), got.get(2).rc);
      Assertions.assertEquals(Collections.emptyList(), TransitionApproachSelector.doorGateGobs(null));
      Assertions.assertEquals(Collections.emptyList(), TransitionApproachSelector.doorGateGobs(new Scene()));
   }

   private static GobGeom doorGateGob(int cx, int cy, long id, String resid) {
      GobGeom g = new GobGeom();
      g.id = id;
      g.doorGate = true;
      g.resid = resid;
      g.rc = Coord2d.of(cellWorld(cx), cellWorld(cy));
      g.gateState = 1;
      Coord2d[] box = new Coord2d[]{g.rc.add(-5.5, -5.5), g.rc.add(5.5, -5.5), g.rc.add(5.5, 5.5), g.rc.add(-5.5, 5.5)};
      g.hitbox = List.<Coord2d[]>of(box);
      return g;
   }

   private static Scene doorGateScene() {
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      Scene s = scene(40, 40, g, cellWorld(5), cellWorld(5));
      s.gobs.add(doorGateGob(12, 12, 66L, "gfx/terobjs/arch/palisadegate"));
      return s;
   }

   @Test
   void selectsAdjacentBodyFreeLocallyReachableDoorGateApproachNeverTheFixture() {
      Scene s = doorGateScene();
      Selection sel = TransitionApproachSelector.doorGateApproach(s);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(TransitionProfile.DOOR_GATE, sel.profile);
      Assertions.assertNull(sel.refusal);
      Assertions.assertEquals(1, sel.fixtureCount);
      Assertions.assertEquals(DoorGateKind.PALISADE_GATE, sel.doorGateKind, "the public facts identify the door/gate kind");
      Assertions.assertEquals(TransitionState.OPEN, sel.transitionState, "an observably-open gate selects with OPEN transition state");
      Assertions.assertNull(sel.kind, "door/gate selections carry no cave-transition kind");
      Assertions.assertNotNull(sel.approachTile);
      Assertions.assertNotNull(sel.approachWorld);
      Assertions.assertTrue(s.bodyFree(sel.approachWorld), "the selected approach must be body-free");
      Assertions.assertTrue(sel.standoff >= 0.0, "standoff evidence must be present");
      Assertions.assertTrue(sel.footprintTiles > 0.0, "footprint evidence must be present");
      Assertions.assertTrue(sel.playerDist >= 5.5 && sel.playerDist <= 49.500000001, "the approach must lie within the default player band");
      Assertions.assertTrue("NESW".contains(sel.side), sel.side);
      Assertions.assertTrue(sel.evidence.contains("DOOR_GATE approach"), sel.evidence);
      Assertions.assertTrue(
         sel.evidence.contains("transition state of the winning fixture: OPEN"), "the evidence names the winning fixture's observable transition state"
      );
      double dx = Math.abs(sel.approachWorld.x - cellWorld(12)) - 5.5;
      double dy = Math.abs(sel.approachWorld.y - cellWorld(12)) - 5.5;
      Assertions.assertTrue(dx > 0.0 || dy > 0.0, "the approach must not sit inside the fixture footprint");
      Assertions.assertTrue(Math.max(dx, dy) <= 8.250000001, "the approach must hug the footprint box");
      Assertions.assertNotEquals(Coord2d.of(cellWorld(12), cellWorld(12)), sel.approachWorld, "the approach is never the fixture position");
      Assertions.assertFalse(sel.candidates.isEmpty());
      Assertions.assertEquals(sel.approachTile, ((Candidate)sel.candidates.get(0)).tile);
      Assertions.assertEquals(sel.approachWorld, ((Candidate)sel.candidates.get(0)).world);
      Assertions.assertEquals(((Candidate)sel.candidates.get(0)).route.get(((Candidate)sel.candidates.get(0)).route.size() - 1), sel.approachTile);
      Assertions.assertEquals(Coord.of(5, 5), ((Candidate)sel.candidates.get(0)).route.get(0), "the verified route starts at the player cell");

      for (int i = 1; i < sel.candidates.size(); i++) {
         Assertions.assertTrue(
            ((Candidate)sel.candidates.get(i - 1)).score >= ((Candidate)sel.candidates.get(i)).score, "candidates must be ordered by score descending"
         );
      }

      for (Candidate c : sel.candidates) {
         Assertions.assertTrue(s.bodyFree(c.world), "every candidate must be body-free");
         Assertions.assertEquals(DoorGateKind.PALISADE_GATE, c.doorGateKind);
         Assertions.assertEquals(TransitionState.OPEN, c.transitionState, "every candidate carries its fixture's observable transition state");
         Assertions.assertNull(c.kind);
         Assertions.assertTrue("NESW".contains(c.side), c.side);
         Assertions.assertTrue(c.standoff > 0.0, "every candidate sits in the adjacency ring");
         Assertions.assertFalse(c.note.contains("id="), "candidate notes carry no gob ids");
      }

      Assertions.assertFalse(sel.evidence.contains("id="), "selection evidence carries no gob ids");
      Assertions.assertFalse(sel.evidence.contains("66"), "selection evidence never names a gob id");
      Assertions.assertEquals(Coord.of(7, 7), sel.approachTile, sel.evidence);
      Assertions.assertNull(TransitionApproachSelector.boulderApproach(boulderScene()).doorGateKind);
      Assertions.assertNull(TransitionApproachSelector.boulderApproach(boulderScene()).transitionState);
      Assertions.assertNull(TransitionApproachSelector.caveTransitionApproach(caveScene()).doorGateKind);
      Assertions.assertNull(TransitionApproachSelector.caveTransitionApproach(caveScene()).transitionState);
   }

   @Test
   void doorGateSelectionIsDeterministicAcrossGobIterationOrderAndSideEffectFree() {
      Scene s = doorGateScene();
      byte[] before = Arrays.copyOf(s.occupancy.occ, s.occupancy.occ.length);
      Selection a = TransitionApproachSelector.doorGateApproach(s);
      Selection b = TransitionApproachSelector.doorGateApproach(s);
      Assertions.assertArrayEquals(before, s.occupancy.occ, "the occupancy lattice must be untouched");
      Assertions.assertEquals(a.approachTile, b.approachTile, "repeated calls with identical inputs are identical");
      Assertions.assertEquals(a.evidence, b.evidence);
      Assertions.assertEquals(a.doorGateKind, b.doorGateKind);
      Assertions.assertThrows(UnsupportedOperationException.class, () -> a.candidates.add((Candidate)a.candidates.get(0)), "the candidate list is unmodifiable");
      Assertions.assertThrows(
         UnsupportedOperationException.class, () -> ((Candidate)a.candidates.get(0)).route.add(Coord.of(0, 0)), "the verified route is unmodifiable"
      );
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      Scene s2 = scene(40, 40, g, cellWorld(5), cellWorld(5));
      GobGeom other = doorGateGob(30, 30, 6L, "gfx/terobjs/arch/brickbiggate");
      s2.gobs.add(0, other);
      s2.gobs.add(doorGateGob(12, 12, 99L, "gfx/terobjs/arch/palisadegate"));
      Selection c = TransitionApproachSelector.doorGateApproach(s2);
      Assertions.assertTrue(c.selected(), c.evidence);
      Assertions.assertEquals(2, c.fixtureCount, "both classified fixtures are counted");
      Assertions.assertEquals(a.approachTile, c.approachTile, "the winning approach must not depend on gob order");
      Assertions.assertEquals(DoorGateKind.PALISADE_GATE, c.doorGateKind);
      Assertions.assertEquals(a.candidates.size(), c.candidates.size(), "the extra far fixture never steals or adds ring candidates");
      char[][] g3 = fill(40, 40, ' ');
      boulderFootprint(g3, 12, 12, 1);
      Scene s3 = scene(40, 40, g3, cellWorld(5), cellWorld(5));
      s3.gobs.add(doorGateGob(12, 12, 12345L, "gfx/terobjs/arch/palisadegate"));
      Selection d = TransitionApproachSelector.doorGateApproach(s3);
      Assertions.assertTrue(d.selected(), d.evidence);
      Assertions.assertEquals(1, d.fixtureCount);
      Assertions.assertEquals(a.approachTile, d.approachTile);
      Assertions.assertEquals(a.doorGateKind, d.doorGateKind);
      Assertions.assertEquals(a.evidence, d.evidence, "the evidence must not depend on gob order or ids");
      Assertions.assertEquals(a.toString(), d.toString());
   }

   @Test
   void doorGateProfileIsSupportedAndDoorGateCrossingStaysDeclaredFuture() {
      Assertions.assertTrue(TransitionProfile.DOOR_GATE.supported);
      Assertions.assertFalse(TransitionProfile.DOOR.supported, "the door crossing itself is never an approach-selection profile");
      Assertions.assertFalse(TransitionProfile.GATE.supported, "the gate crossing itself is never an approach-selection profile");

      for (TransitionProfile p : List.of(TransitionProfile.DOOR, TransitionProfile.GATE)) {
         Selection u = TransitionApproachSelector.select(doorGateScene(), p, ApproachRange.doorGate(), 100000);
         Assertions.assertEquals(Status.REFUSED, u.status);
         Assertions.assertEquals(Refusal.UNSUPPORTED_PROFILE, u.refusal, p + " refuses with the typed discriminator");
      }
   }

   @Test
   void refusesVisitorGateConservatively() {
      Scene s = doorGateScene();
      ((GobGeom)s.gobs.get(0)).visitorGate = true;
      Selection sel = TransitionApproachSelector.doorGateApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.VISITOR_GATE, sel.refusal, sel.evidence);
      Assertions.assertEquals(1, sel.fixtureCount);
      Assertions.assertTrue(sel.evidence.contains("VISITOR_GATE"));
      Assertions.assertTrue(sel.evidence.contains("visitor flag"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("ownership is never inferred"), sel.evidence);
      Assertions.assertNull(sel.approachTile);
      Assertions.assertNull(sel.approachWorld);
      Scene ok = doorGateScene();
      Assertions.assertTrue(TransitionApproachSelector.doorGateApproach(ok).selected());
   }

   @Test
   void selectsObservablyClosedDoorGateApproachWithClosedTransitionState() {
      Scene closed = doorGateScene();
      ((GobGeom)closed.gobs.get(0)).gateState = 0;
      Selection sel = TransitionApproachSelector.doorGateApproach(closed);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(TransitionState.CLOSED, sel.transitionState, "an observably-closed gate selects with CLOSED transition state");
      Assertions.assertEquals(DoorGateKind.PALISADE_GATE, sel.doorGateKind);
      Assertions.assertNotNull(sel.approachTile, "a closed gate still yields an adjacent approach stand");
      Assertions.assertTrue(sel.evidence.contains("transition state of the winning fixture: CLOSED"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("0 observably open, 1 observably closed"), "the evidence carries the typed open/closed counts");
      Assertions.assertEquals(TransitionState.CLOSED, ((Candidate)sel.candidates.get(0)).transitionState);
      double dx = Math.abs(sel.approachWorld.x - cellWorld(12)) - 5.5;
      double dy = Math.abs(sel.approachWorld.y - cellWorld(12)) - 5.5;
      Assertions.assertTrue(dx > 0.0 || dy > 0.0, "the approach must not sit inside the closed gate's footprint");
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      Scene doorClosed = scene(40, 40, g, cellWorld(5), cellWorld(5));
      GobGeom door = doorGateGob(12, 12, 5L, "gfx/terobjs/arch/greathall-door");
      door.gateState = 0;
      doorClosed.gobs.add(door);
      Selection doorSel = TransitionApproachSelector.doorGateApproach(doorClosed);
      Assertions.assertTrue(doorSel.selected(), doorSel.evidence);
      Assertions.assertEquals(TransitionState.CLOSED, doorSel.transitionState);
      Assertions.assertEquals(DoorGateKind.DOOR, doorSel.doorGateKind);
      char[][] g2 = fill(40, 40, ' ');
      boulderFootprint(g2, 12, 12, 1);
      Scene mixed = scene(40, 40, g2, cellWorld(5), cellWorld(5));
      GobGeom closedFar = doorGateGob(30, 30, 7L, "gfx/terobjs/arch/brickbiggate");
      closedFar.gateState = 0;
      mixed.gobs.add(closedFar);
      mixed.gobs.add(doorGateGob(12, 12, 8L, "gfx/terobjs/arch/palisadegate"));
      Selection mixedSel = TransitionApproachSelector.doorGateApproach(mixed);
      Assertions.assertTrue(mixedSel.selected(), mixedSel.evidence);
      Assertions.assertEquals(2, mixedSel.fixtureCount);
      Assertions.assertEquals(TransitionState.OPEN, mixedSel.transitionState, "the winning (closer) fixture is the open gate");
      Assertions.assertTrue(mixedSel.evidence.contains("1 observably open, 1 observably closed"), mixedSel.evidence);
      Assertions.assertTrue(TransitionApproachSelector.doorGateApproach(doorGateScene()).selected());
   }

   @Test
   void selectsUnobservableOrInvalidDoorGateApproachWithUnknownTransitionStateNeverGuessed() {
      Scene unobserved = doorGateScene();
      ((GobGeom)unobserved.gobs.get(0)).gateState = -1;
      Selection u = TransitionApproachSelector.doorGateApproach(unobserved);
      Assertions.assertTrue(u.selected(), u.evidence);
      Assertions.assertEquals(TransitionState.UNKNOWN, u.transitionState, "an unobservable-state gate selects with UNKNOWN transition state (never guessed)");
      Assertions.assertEquals(1, u.fixtureCount);
      Assertions.assertFalse(u.candidates.isEmpty());
      Assertions.assertTrue(u.evidence.contains("transition state of the winning fixture: UNKNOWN"), u.evidence);
      Assertions.assertTrue(
         u.evidence.contains("0 observably open, 0 observably closed, 1 unknown state"),
         "the evidence counts the state categories (open/closed/unknown): " + u.evidence
      );
      Assertions.assertNotNull(u.approachTile);
      Assertions.assertNotNull(u.approachWorld);
      Assertions.assertTrue(unobserved.bodyFree(u.approachWorld), "the selected approach must be body-free");
      Assertions.assertEquals(
         TransitionState.UNKNOWN, ((Candidate)u.candidates.get(0)).transitionState, "every candidate carries its fixture's UNKNOWN transition state"
      );
      double dx = Math.abs(u.approachWorld.x - cellWorld(12)) - 5.5;
      double dy = Math.abs(u.approachWorld.y - cellWorld(12)) - 5.5;
      Assertions.assertTrue(dx > 0.0 || dy > 0.0, "the approach must not sit inside the unknown gate's footprint");
      Scene animating = doorGateScene();
      ((GobGeom)animating.gobs.get(0)).gateState = 2;
      Selection a = TransitionApproachSelector.doorGateApproach(animating);
      Assertions.assertTrue(a.selected(), a.evidence);
      Assertions.assertEquals(TransitionState.UNKNOWN, a.transitionState, a.evidence);
      Scene emptySdt = doorGateScene();
      ((GobGeom)emptySdt.gobs.get(0)).gateState = 268431360;
      Selection e = TransitionApproachSelector.doorGateApproach(emptySdt);
      Assertions.assertTrue(e.selected(), e.evidence);
      Assertions.assertEquals(TransitionState.UNKNOWN, e.transitionState, e.evidence);
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      Scene mixed = scene(40, 40, g, cellWorld(5), cellWorld(5));
      GobGeom unknownFar = doorGateGob(30, 30, 7L, "gfx/terobjs/arch/brickbiggate");
      unknownFar.gateState = -1;
      mixed.gobs.add(unknownFar);
      mixed.gobs.add(doorGateGob(12, 12, 8L, "gfx/terobjs/arch/palisadegate"));
      Selection mixedSel = TransitionApproachSelector.doorGateApproach(mixed);
      Assertions.assertTrue(mixedSel.selected(), mixedSel.evidence);
      Assertions.assertEquals(2, mixedSel.fixtureCount);
      Assertions.assertEquals(TransitionState.OPEN, mixedSel.transitionState, "the winning (closer) fixture is the open gate");
      Assertions.assertTrue(
         mixedSel.evidence.contains("1 observably open, 0 observably closed, 1 unknown state"),
         "the typed evidence counts open/closed/unknown separately: " + mixedSel.evidence
      );
      char[][] g2 = fill(40, 40, ' ');
      boulderFootprint(g2, 12, 12, 1);
      Scene onlyUnknown = scene(40, 40, g2, cellWorld(5), cellWorld(5));
      GobGeom uk = doorGateGob(12, 12, 9L, "gfx/terobjs/arch/polegate");
      uk.gateState = -1;
      onlyUnknown.gobs.add(uk);
      Selection onlySel = TransitionApproachSelector.doorGateApproach(onlyUnknown);
      Assertions.assertTrue(onlySel.selected(), onlySel.evidence);
      Assertions.assertEquals(TransitionState.UNKNOWN, onlySel.transitionState, onlySel.evidence);
      Assertions.assertTrue(onlySel.evidence.contains("0 observably open, 0 observably closed, 1 unknown state"), onlySel.evidence);
      Assertions.assertNotNull(Refusal.STATE_UNKNOWN);
   }

   @Test
   void classifiesCanonicalTransitionStatesOpenClosedUnknown() {
      Assertions.assertEquals(
         TransitionState.OPEN, TransitionApproachSelector.transitionState(1), "sdt 1 is Haven's canonical open gate state (Hitbox.passable)"
      );
      Assertions.assertEquals(TransitionState.CLOSED, TransitionApproachSelector.transitionState(0), "sdt 0 is Haven's canonical closed gate state");
      Assertions.assertEquals(TransitionState.UNKNOWN, TransitionApproachSelector.transitionState(-1), "the never-captured default is unknown");
      Assertions.assertEquals(TransitionState.UNKNOWN, TransitionApproachSelector.transitionState(2), "an intermediate/animation value is unknown");
      Assertions.assertEquals(
         TransitionState.UNKNOWN, TransitionApproachSelector.transitionState(268431360), "the empty-sdt sentinel ResDrawable.sdtnum returns is unknown"
      );
   }

   @Test
   void refusesUnknownDoorGateGeometryRatherThanGuessing() {
      Scene s = doorGateScene();
      ((GobGeom)s.gobs.get(0)).hitbox = Collections.emptyList();
      Selection sel = TransitionApproachSelector.doorGateApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.GEOMETRY_UNKNOWN, sel.refusal, sel.evidence);
      Assertions.assertEquals(1, sel.fixtureCount);
      Assertions.assertTrue(sel.evidence.contains("GEOMETRY_UNKNOWN"));
      Assertions.assertTrue(sel.evidence.contains("unobservable footprint geometry"), sel.evidence);
      Assertions.assertNull(sel.approachTile);
      Assertions.assertNull(sel.approachWorld);
   }

   @Test
   void refusesAmbiguousPlayerStandingInTheDoorway() {
      char[][] g = fill(40, 40, ' ');
      boulderFootprint(g, 12, 12, 1);
      Scene s = scene(40, 40, g, cellWorld(12), cellWorld(12));
      s.gobs.add(doorGateGob(12, 12, 7L, "gfx/terobjs/arch/polegate"));
      Selection sel = TransitionApproachSelector.doorGateApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.AMBIGUOUS, sel.refusal, sel.evidence);
      Assertions.assertEquals(1, sel.fixtureCount);
      Assertions.assertTrue(sel.evidence.contains("AMBIGUOUS"));
      Assertions.assertTrue(sel.evidence.contains("inside a fixture footprint box"), sel.evidence);
      Assertions.assertNull(sel.approachTile);
      Assertions.assertNull(sel.approachWorld);
   }

   @Test
   void refusesWhenNoDoorGateFixtureExists() {
      Scene s = scene(40, 40, fill(40, 40, ' '), cellWorld(5), cellWorld(5));
      Selection sel = TransitionApproachSelector.doorGateApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_FIXTURE, sel.refusal);
      Assertions.assertEquals(0, sel.fixtureCount);
      Assertions.assertTrue(sel.evidence.contains("NO_FIXTURE"));
      Assertions.assertTrue(sel.evidence.contains("0 classified doors/gates"));
      Assertions.assertNull(sel.approachTile);
      Assertions.assertNull(sel.approachWorld);
      Assertions.assertTrue(sel.candidates.isEmpty());
   }

   @Test
   void refusesWhenEveryDoorGateApproachIsBodyBlockedOrUnreachable() {
      char[][] g = fill(40, 40, ' ');

      for (int y = 7; y <= 17; y++) {
         for (int x = 7; x <= 17; x++) {
            g[y][x] = '#';
         }
      }

      Scene sealed = scene(40, 40, g, cellWorld(5), cellWorld(5));
      sealed.gobs.add(doorGateGob(12, 12, 1L, "gfx/terobjs/arch/palisadegate"));
      Selection sealedSel = TransitionApproachSelector.doorGateApproach(sealed);
      Assertions.assertEquals(Status.REFUSED, sealedSel.status);
      Assertions.assertEquals(Refusal.NO_CANDIDATE, sealedSel.refusal, sealedSel.evidence);
      Assertions.assertEquals(1, sealedSel.fixtureCount);
      Assertions.assertTrue(sealedSel.evidence.contains("0 body-free adjacent approach"), sealedSel.evidence);
      char[][] w = fill(40, 40, ' ');

      for (int i = 6; i <= 17; i++) {
         w[6][i] = '#';
         w[17][i] = '#';
         w[i][6] = '#';
         w[i][17] = '#';
      }

      Scene walled = scene(40, 40, w, cellWorld(5), cellWorld(5));
      walled.gobs.add(doorGateGob(12, 12, 2L, "gfx/terobjs/arch/drystonewallgate"));
      Selection sel = TransitionApproachSelector.doorGateApproach(walled);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_REACHABLE, sel.refusal, sel.evidence);
      Assertions.assertEquals(1, sel.fixtureCount);
      Assertions.assertTrue(sel.evidence.contains("NO_REACHABLE"));
      Assertions.assertTrue(sel.evidence.contains("0 reachable"), sel.evidence);
   }

   private static String[] terrainNames(char[][] t) {
      String[] names = new String[t.length * t[0].length];

      for (int y = 0; y < t.length; y++) {
         for (int x = 0; x < t[0].length; x++) {
            switch (t[y][x]) {
               case '#':
                  names[y * t[0].length + x] = "gfx/tiles/odeep";
                  break;
               case '?':
                  names[y * t[0].length + x] = "gfx/tiles/notile";
                  break;
               case 'D':
                  names[y * t[0].length + x] = "gfx/tiles/deep";
                  break;
               case 'E':
                  names[y * t[0].length + x] = "";
                  break;
               case 'L':
                  names[y * t[0].length + x] = "loading";
                  break;
               case 'o':
                  names[y * t[0].length + x] = "gfx/tiles/owater";
                  break;
               case '~':
                  names[y * t[0].length + x] = "gfx/tiles/water";
                  break;
               default:
                  names[y * t[0].length + x] = "gfx/tiles/grass";
            }
         }
      }

      return names;
   }

   private static Scene waterScene(int w, int h, char[][] g, char[][] t, double px, double py) {
      Scene s = scene(w, h, g, px, py);
      s.terrainCells = terrainNames(t);
      return s;
   }

   private static Scene waterlineScene() {
      char[][] g = fill(40, 40, ' ');
      char[][] t = fill(40, 40, 'G');

      for (int y = 0; y < 40; y++) {
         for (int x = 15; x <= 16; x++) {
            t[y][x] = '~';
         }
      }

      return waterScene(40, 40, g, t, cellWorld(5), cellWorld(5));
   }

   @Test
   void classifiesConfirmedWaterAndUnknownTerrainNamesExactly() {
      Assertions.assertTrue(TransitionApproachSelector.isWaterTileName("gfx/tiles/water"));
      Assertions.assertTrue(TransitionApproachSelector.isWaterTileName("gfx/tiles/deep"));
      Assertions.assertTrue(TransitionApproachSelector.isWaterTileName("gfx/tiles/owater"));
      Assertions.assertTrue(TransitionApproachSelector.isWaterTileName("gfx/tiles/odeep"));
      Assertions.assertTrue(TransitionApproachSelector.isWaterTileName("gfx/tiles/odeeper"));
      Assertions.assertFalse(TransitionApproachSelector.isWaterTileName("gfx/tiles/grass"));
      Assertions.assertFalse(TransitionApproachSelector.isWaterTileName("gfx/tiles/waterfall"));
      Assertions.assertFalse(TransitionApproachSelector.isWaterTileName("gfx/tiles/rocks/boulder"));
      Assertions.assertFalse(TransitionApproachSelector.isWaterTileName("gfx/tiles/deepsoil"));
      Assertions.assertFalse(TransitionApproachSelector.isWaterTileName("gfx/terobjs/boat/rowboat"));
      Assertions.assertFalse(TransitionApproachSelector.isWaterTileName("gfx/terobjs/boat"));
      Assertions.assertFalse(TransitionApproachSelector.isWaterTileName(""));
      Assertions.assertFalse(TransitionApproachSelector.isWaterTileName(null));
      Assertions.assertEquals(5, TransitionApproachSelector.WATER_TILES.size());
      Assertions.assertTrue(TransitionApproachSelector.isUnknownTileName(null));
      Assertions.assertTrue(TransitionApproachSelector.isUnknownTileName(""));
      Assertions.assertTrue(TransitionApproachSelector.isUnknownTileName("loading"));
      Assertions.assertTrue(TransitionApproachSelector.isUnknownTileName("gfx/tiles/notile"));
      Assertions.assertTrue(TransitionApproachSelector.isUnknownTileName("gfx/tiles/notile/x"));
      Assertions.assertFalse(TransitionApproachSelector.isUnknownTileName("gfx/tiles/grass"));
      Assertions.assertFalse(TransitionApproachSelector.isUnknownTileName("gfx/tiles/cave"));
      Assertions.assertFalse(TransitionApproachSelector.isUnknownTileName("gfx/tiles/water"));
   }

   @Test
   void selectsLandSideStandBesideConfirmedWater() {
      Scene s = waterlineScene();
      Selection sel = TransitionApproachSelector.waterlineApproach(s);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(TransitionProfile.WATERLINE, sel.profile);
      Assertions.assertNotNull(sel.approachTile);
      Assertions.assertNotNull(sel.approachWorld);
      Assertions.assertEquals(
         "gfx/tiles/grass", s.terrainCells[sel.approachTile.y * s.w + sel.approachTile.x], "the selected stand is never a confirmed water cell"
      );
      boolean adjacentWater = false;

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            if (dx != 0 || dy != 0) {
               int nx = sel.approachTile.x + dx;
               int ny = sel.approachTile.y + dy;
               if (nx >= 0 && ny >= 0 && nx < s.w && ny < s.h && TransitionApproachSelector.isWaterTileName(s.terrainCells[ny * s.w + nx])) {
                  adjacentWater = true;
               }
            }
         }
      }

      Assertions.assertTrue(adjacentWater, "the stand must hug a confirmed water cell");
      Assertions.assertTrue(s.bodyFree(sel.approachWorld), sel.evidence);
      Assertions.assertFalse(sel.candidates.isEmpty());
      Assertions.assertTrue(((Candidate)sel.candidates.get(0)).route.size() >= 2, "a verified A* route");
      Assertions.assertTrue(((Candidate)sel.candidates.get(0)).routeExpanded > 0, "A* expansion count");
      Assertions.assertEquals(80, sel.waterCells);
      Assertions.assertEquals(80, sel.waterlineCells);
      Assertions.assertEquals(10.0, sel.footprintTiles, 0.01);
      Assertions.assertTrue("NESW".contains(sel.side), "a relative side fact: " + sel.side);
      Assertions.assertTrue(sel.standoff >= 0.0);
      Assertions.assertTrue(sel.playerDist > 0.0);
      Assertions.assertTrue(sel.evidence.contains("confirmed water cells (80 on the waterline)"), sel.evidence);
   }

   @Test
   void neverSelectsWaterEvenWhenBodyFree() {
      Scene s = waterlineScene();
      Selection sel = TransitionApproachSelector.waterlineApproach(s);
      Assertions.assertTrue(sel.selected(), sel.evidence);
      Assertions.assertEquals(
         "gfx/tiles/grass", s.terrainCells[sel.approachTile.y * s.w + sel.approachTile.x], "the stand is land-side even though the water cells are body-free"
      );
      Assertions.assertFalse(sel.approachTile.x == 15 || sel.approachTile.x == 16, "no stand inside the water columns: " + sel.approachTile);
   }

   @Test
   void waterlineIgnoresGobsAndIsDeterministicAndSideEffectFree() {
      Scene s = waterlineScene();
      GobGeom boat = new GobGeom();
      boat.id = 9001L;
      boat.name = "rowboat";
      boat.resid = "gfx/terobjs/boat/rowboat";
      boat.rc = Coord2d.of(cellWorld(16), cellWorld(20));
      s.gobs.add(boat);
      s.gobs.add(boulder(30, 30, 9002L));
      byte[] occBefore = (byte[])s.occupancy.occ.clone();
      Selection a = TransitionApproachSelector.waterlineApproach(s);
      Selection b = TransitionApproachSelector.waterlineApproach(s);
      Assertions.assertTrue(a.selected() && b.selected(), a.evidence);
      Assertions.assertEquals(a.approachTile, b.approachTile, "repeated calls are identical");
      Assertions.assertEquals(a.evidence, b.evidence);
      Assertions.assertEquals(a.candidates.size(), b.candidates.size());

      for (int i = 0; i < a.candidates.size(); i++) {
         Assertions.assertEquals(((Candidate)a.candidates.get(i)).tile, ((Candidate)b.candidates.get(i)).tile, "candidate ranking is identical across calls");
      }

      Assertions.assertArrayEquals(occBefore, s.occupancy.occ, "occupancy lattice must be untouched");
      Assertions.assertThrows(UnsupportedOperationException.class, () -> a.candidates.add(null));
      Assertions.assertThrows(UnsupportedOperationException.class, () -> ((Candidate)a.candidates.get(0)).route.add(null));
      Assertions.assertFalse(a.evidence.contains("9001"), a.evidence);
      Assertions.assertFalse(a.evidence.contains("9002"), a.evidence);
      Assertions.assertFalse(a.evidence.contains("rowboat"), a.evidence);
   }

   @Test
   void waterlineRefusesMissingTerrainSnapshot() {
      Scene s = scene(40, 40, fill(40, 40, ' '), cellWorld(5), cellWorld(5));
      Selection sel = TransitionApproachSelector.waterlineApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_TERRAIN, sel.refusal, sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("no per-cell terrain snapshot"), sel.evidence);
      Assertions.assertNull(sel.approachTile);
      Assertions.assertTrue(sel.candidates.isEmpty());
   }

   @Test
   void waterlineRefusesWhenNoConfirmedWaterExists() {
      Scene s = waterScene(40, 40, fill(40, 40, ' '), fill(40, 40, 'G'), cellWorld(5), cellWorld(5));
      Selection sel = TransitionApproachSelector.waterlineApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_WATER, sel.refusal, sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("0 confirmed water cells"), sel.evidence);
      Assertions.assertEquals(0, sel.waterCells);
      Assertions.assertNull(sel.approachTile);
   }

   @Test
   void waterlineRefusesPlayerStandingInWater() {
      char[][] t = fill(40, 40, 'G');

      for (int y = 0; y < 40; y++) {
         for (int x = 15; x <= 16; x++) {
            t[y][x] = '~';
         }
      }

      Scene s = waterScene(40, 40, fill(40, 40, ' '), t, cellWorld(15), cellWorld(5));
      Selection sel = TransitionApproachSelector.waterlineApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.AMBIGUOUS, sel.refusal, sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("standing on confirmed water"), sel.evidence);
      Assertions.assertTrue(sel.waterCells > 0, "the water count is still reported");
   }

   @Test
   void waterlineRefusesUnknownPlayerCellTerrain() {
      char[][] t = fill(40, 40, 'G');
      t[5][5] = '?';
      Scene s = waterScene(40, 40, fill(40, 40, ' '), t, cellWorld(5), cellWorld(5));
      Selection sel = TransitionApproachSelector.waterlineApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.TERRAIN_UNKNOWN, sel.refusal, sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("unknown/unclassified terrain"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("cannot confirm the player is not on water"), sel.evidence);
   }

   @Test
   void waterlineRefusesAmbiguousBoundaryWithUnknownNeighbor() {
      char[][] t = fill(40, 40, 'G');

      for (int y = 0; y < 40; y++) {
         for (int x = 15; x <= 16; x++) {
            t[y][x] = '~';
         }
      }

      t[5][14] = '?';
      Scene s = waterScene(40, 40, fill(40, 40, ' '), t, cellWorld(5), cellWorld(5));
      Selection sel = TransitionApproachSelector.waterlineApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.TERRAIN_UNKNOWN, sel.refusal, sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("borders unknown/unclassified terrain"), sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("ambiguous"), sel.evidence);
   }

   @Test
   void waterlineRefusesWhenAllLandSideStandsAreBlocked() {
      char[][] g = fill(40, 40, ' ');

      for (int y = 0; y < 40; y++) {
         g[y][14] = '#';
         g[y][17] = '#';
      }

      char[][] t = fill(40, 40, 'G');

      for (int y = 0; y < 40; y++) {
         for (int x = 15; x <= 16; x++) {
            t[y][x] = '~';
         }
      }

      Scene s = waterScene(40, 40, g, t, cellWorld(5), cellWorld(5));
      Selection sel = TransitionApproachSelector.waterlineApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_CANDIDATE, sel.refusal, sel.evidence);
      Assertions.assertEquals(80, sel.waterCells, "the water is still confirmed");
      Assertions.assertEquals(80, sel.waterlineCells);
      Assertions.assertTrue(sel.evidence.contains("0 body-free land-side approach cells"), sel.evidence);
   }

   @Test
   void waterlineRefusesWhenStandsExistButNoneReachable() {
      char[][] g = fill(40, 40, ' ');

      for (int i = 6; i <= 17; i++) {
         g[6][i] = '#';
         g[17][i] = '#';
         g[i][6] = '#';
         g[i][17] = '#';
      }

      char[][] t = fill(40, 40, 'G');

      for (int y = 8; y <= 15; y++) {
         for (int x = 15; x <= 16; x++) {
            t[y][x] = '~';
         }
      }

      Scene s = waterScene(40, 40, g, t, cellWorld(5), cellWorld(5));
      Selection sel = TransitionApproachSelector.waterlineApproach(s);
      Assertions.assertEquals(Status.REFUSED, sel.status);
      Assertions.assertEquals(Refusal.NO_REACHABLE, sel.refusal, sel.evidence);
      Assertions.assertTrue(sel.evidence.contains("0 reachable"), sel.evidence);
      Assertions.assertEquals(16, sel.waterCells);
      Assertions.assertTrue(sel.waterlineCells > 0);
   }

   @Test
   void waterlineIsSupportedAndCrossingProfilesStayFuture() {
      Assertions.assertTrue(TransitionApproachSelector.waterlineApproach(waterlineScene()).selected());

      for (TransitionProfile p : List.of(
         TransitionProfile.DOOR,
         TransitionProfile.GATE,
         TransitionProfile.MINEHOLE,
         TransitionProfile.BOAT,
         TransitionProfile.WATER_CROSSING,
         TransitionProfile.CART
      )) {
         Selection u = TransitionApproachSelector.select(waterlineScene(), p, ApproachRange.waterline(), 100000);
         Assertions.assertEquals(Status.REFUSED, u.status, p.name());
         Assertions.assertEquals(Refusal.UNSUPPORTED_PROFILE, u.refusal, p.name());
      }
   }

   @Test
   void waterlineRefusesSharedPreconditions() {
      Assertions.assertEquals(Refusal.NO_SCENE, TransitionApproachSelector.select(null, TransitionProfile.WATERLINE, ApproachRange.waterline(), 100000).refusal);
      Assertions.assertEquals(Refusal.NO_OCCUPANCY, TransitionApproachSelector.waterlineApproach(new Scene()).refusal);
      Scene noPlayer = waterlineScene();
      noPlayer.player = null;
      Assertions.assertEquals(Refusal.PLAYER_UNKNOWN, TransitionApproachSelector.waterlineApproach(noPlayer).refusal);
      Scene offGrid = waterlineScene();
      offGrid.player = Coord2d.of(-100.0, -100.0);
      Assertions.assertEquals(Refusal.PLAYER_OFF_GRID, TransitionApproachSelector.waterlineApproach(offGrid).refusal);
      Assertions.assertEquals(
         Refusal.BOUNDS_INVALID,
         TransitionApproachSelector.select(waterlineScene(), TransitionProfile.WATERLINE, new ApproachRange(10.0, 5.0, 0.0, 1.0), 100000).refusal
      );
   }
}
