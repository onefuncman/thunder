package haven.pathfinding;

import haven.Coord2d;
import haven.pathfinding.CupboardCatalog.Entry;
import haven.pathfinding.CupboardCatalog.Node;
import haven.pathfinding.CupboardCatalog.Step;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CupboardCatalogTest {
   private static Node n(long id, double tx, double ty) {
      return new Node(id, tx * 11.0, ty * 11.0);
   }

   @Test
   void wallCupboardIsNotACorner() {
      List<Node> wall = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0), n(3L, 2.0, 0.0));
      Assertions.assertFalse(CupboardCatalog.isCorner(wall.get(1), wall));
      Assertions.assertFalse(CupboardCatalog.isCorner(wall.get(0), wall));
   }

   @Test
   void lShapeHasOneCorner() {
      List<Node> l = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0), n(3L, 0.0, 1.0));
      Assertions.assertTrue(CupboardCatalog.isCorner(l.get(0), l));
      Assertions.assertFalse(CupboardCatalog.isCorner(l.get(1), l));
      Assertions.assertFalse(CupboardCatalog.isCorner(l.get(2), l));
   }

   @Test
   void lShapeOpensCornerThroughTheAisleCupboard() {
      List<Node> l = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0), n(3L, 0.0, 1.0));
      List<Step> steps = CupboardCatalog.tour(22.0, 0.0, l);
      Assertions.assertEquals(3, steps.size());
      Assertions.assertEquals(2L, steps.get(0).id);
      Assertions.assertEquals(0L, steps.get(0).via);
      Assertions.assertFalse(steps.get(0).corner);
      Step corner = steps.stream().filter(s -> s.id == 1L).findFirst().orElse(null);
      Assertions.assertNotNull(corner);
      Assertions.assertTrue(corner.corner);
      Assertions.assertEquals(2L, corner.via);
   }

   @Test
   void packedMiddleOpensFromNorthOrEastNotWest() {
      List<Node> row = Arrays.asList(n(1L, 0.0, 1.0), n(2L, 1.0, 1.0), n(3L, 2.0, 1.0), n(4L, 1.0, 0.0));
      Set<Long> walkable = new HashSet<>(Arrays.asList(1L, 3L, 4L));
      Node via = CupboardCatalog.preferredVia(row.get(1), Arrays.asList(row.get(0), row.get(2), row.get(3)));
      Assertions.assertNotNull(via);
      Assertions.assertEquals(4L, via.id, "north neighbour beats east and west");
      via = CupboardCatalog.preferredVia(row.get(1), Arrays.asList(row.get(0), row.get(2)));
      Assertions.assertEquals(3L, via.id, "east beats west when there is no north");
      via = CupboardCatalog.preferredVia(row.get(1), Collections.singletonList(row.get(0)));
      Assertions.assertEquals(1L, via.id, "west neighbour is last resort, not forbidden");
      List<Step> steps = CupboardCatalog.tour(0.0, 22.0, row, walkable);
      Step middle = steps.stream().filter(s -> s.id == 2L).findFirst().orElse(null);
      Assertions.assertNotNull(middle);
      Assertions.assertTrue(middle.corner);
      Assertions.assertTrue(middle.via == 1L || middle.via == 4L || middle.via == 3L, "a blocked middle opens through an aisle neighbour, window still up");
      int viaAt = -1;
      int midAt = -1;

      for (int i = 0; i < steps.size(); i++) {
         if (steps.get(i).id == middle.via) {
            viaAt = i;
         }

         if (steps.get(i).id == 2L) {
            midAt = i;
         }
      }

      Assertions.assertTrue(viaAt >= 0 && midAt > viaAt, "keep the aisle cupboard open, then click the middle");
   }

   @Test
   void extenderReachesSidewaysButNotBehindTheOpenCupboard() {
      Node front = n(1L, 0.0, 0.0);
      Node behind = n(2L, 0.0, 1.0);
      Node right = n(3L, 1.0, 0.0);
      List<Node> cups = Arrays.asList(front, behind, right);
      Set<Long> walkable = new HashSet<>(Collections.singletonList(1L));
      Map<Long, Integer> axes = new HashMap<>();
      axes.put(1L, 0);
      List<Step> steps = CupboardCatalog.tour(0.0, -11.0, cups, walkable, axes);
      Step backStep = steps.stream().filter(s -> s.id == 2L).findFirst().orElse(null);
      Step rightStep = steps.stream().filter(s -> s.id == 3L).findFirst().orElse(null);
      Assertions.assertNotNull(backStep);
      Assertions.assertNotNull(rightStep);
      Assertions.assertEquals(1L, backStep.via, "a nook behind the aisle is clicked with that window open");
      Assertions.assertEquals(1L, rightStep.via, "the same open cupboard extends reach to its side");
   }

   @Test
   void lateralReachChainsAcrossAnEntireRow() {
      List<Node> cups = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0), n(3L, 2.0, 0.0), n(4L, 3.0, 0.0), n(9L, 0.0, 1.0));
      Set<Long> walkable = new HashSet<>(Collections.singletonList(1L));
      Map<Long, Integer> axes = new HashMap<>();
      axes.put(1L, 0);
      List<Step> steps = CupboardCatalog.tour(0.0, -11.0, cups, walkable, axes);
      Assertions.assertEquals(1L, steps.get(0).id);
      Assertions.assertEquals(1L, steps.get(1).via, "only the adjacent cupboard uses the open window");
      Step s3 = steps.stream().filter(s -> s.id == 3L).findFirst().orElse(null);
      Step s4 = steps.stream().filter(s -> s.id == 4L).findFirst().orElse(null);
      Assertions.assertNotNull(s3);
      Assertions.assertNotNull(s4);
      Assertions.assertEquals(0L, s3.via, "do not chain nook-to-nook; that clicks from too far");
      Assertions.assertEquals(0L, s4.via, "do not chain nook-to-nook; that clicks from too far");
      Step behind = steps.stream().filter(s -> s.id == 9L).findFirst().orElse(null);
      Assertions.assertNotNull(behind);
      Assertions.assertEquals(1L, behind.via, "a blocked cupboard behind the aisle is a nook: keep the window, then click");
   }

   @Test
   void straightWallHasNoViaSteps() {
      List<Node> wall = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0), n(3L, 2.0, 0.0));
      List<Step> steps = CupboardCatalog.tour(11.0, 11.0, wall);
      Assertions.assertEquals(3, steps.size());

      for (Step s : steps) {
         Assertions.assertEquals(0L, s.via);
         Assertions.assertFalse(s.corner);
      }
   }

   @Test
   void tourOrderDoesNotDependOnCupboardDiscoveryOrder() {
      List<Node> a = Arrays.asList(n(30L, 1.0, 0.0), n(10L, 0.0, -1.0), n(20L, -1.0, 0.0), n(40L, 0.0, 1.0));
      List<Node> b = Arrays.asList(n(40L, 0.0, 1.0), n(20L, -1.0, 0.0), n(10L, 0.0, -1.0), n(30L, 1.0, 0.0));
      List<Step> ta = CupboardCatalog.tour(0.0, 0.0, a, new HashSet<>(Arrays.asList(10L, 20L, 30L, 40L)));
      List<Step> tb = CupboardCatalog.tour(0.0, 0.0, b, new HashSet<>(Arrays.asList(10L, 20L, 30L, 40L)));
      Assertions.assertEquals(ta.size(), tb.size());

      for (int i = 0; i < ta.size(); i++) {
         Assertions.assertEquals(ta.get(i).id, tb.get(i).id, "step " + i + " must be stable");
      }

      Assertions.assertEquals(10L, ta.get(0).id, "equal-distance plan starts north, then follows proximity");
   }

   @Test
   void packedBlockWalksNearestThenOpensTheRestThroughIt() {
      List<Node> block = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0), n(3L, 0.0, 1.0), n(4L, 1.0, 1.0));
      List<Step> steps = CupboardCatalog.tour(0.0, -11.0, block);
      Assertions.assertEquals(4, steps.size());
      Assertions.assertEquals(0L, steps.get(0).via);
      long via = steps.get(0).id;
      Assertions.assertEquals(1L, via);

      for (int i = 1; i < steps.size(); i++) {
         Assertions.assertEquals(via, steps.get(i).via);
         Assertions.assertTrue(steps.get(i).corner);
      }
   }

   @Test
   void roomPerimeterOpensEachCornerFromAnAdjacentAisle() {
      List<Node> room = new ArrayList<>();
      long id = 1L;

      for (int x = 0; x <= 3; x++) {
         for (int y = 0; y <= 3; y++) {
            if (x == 0 || x == 3 || y == 0 || y == 3) {
               room.add(n(id++, (double)x, (double)y));
            }
         }
      }

      Assertions.assertEquals(12, room.size());
      int corners = 0;

      for (Node n : room) {
         if (CupboardCatalog.isCorner(n, room)) {
            corners++;
         }
      }

      Assertions.assertEquals(4, corners);
      List<Step> steps = CupboardCatalog.tour(16.5, 16.5, room);
      Assertions.assertEquals(12, steps.size());
      int viaCorners = 0;

      for (Step s : steps) {
         if (s.corner) {
            viaCorners++;
            Assertions.assertTrue(s.via != 0L, "corner should open through an aisle cupboard");
         }
      }

      Assertions.assertEquals(4, viaCorners);
   }

   @Test
   void packedNeighborStandIsNotALegalAisle() {
      List<Node> pair = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0));
      Coord2d[] a = CupboardCatalog.standCandidates(pair.get(0).x, pair.get(0).y);
      Assertions.assertFalse(CupboardCatalog.standClear(a[1], pair, 1L), "east face-hug sits inside the next packed cupboard");
      Assertions.assertFalse(CupboardCatalog.standClear(a[5], pair, 1L), "east stand at 1 tile is the next cupboard origin");
      Assertions.assertTrue(CupboardCatalog.standClear(a[3], pair, 1L), "west of a packed row is the aisle when the neighbour is east");
      Assertions.assertTrue(CupboardCatalog.standClear(a[0], pair, 1L), "north of a packed row is still an aisle if nothing is there");
      Assertions.assertTrue(CupboardCatalog.standClear(a[2], pair, 1L), "south of a packed row is still an aisle if nothing is there");
      List<Node> stacked = Arrays.asList(n(1L, 0.0, 0.0), n(3L, 0.0, 1.0));
      Coord2d[] b = CupboardCatalog.standCandidates(stacked.get(0).x, stacked.get(0).y);
      Assertions.assertFalse(CupboardCatalog.standClear(b[2], stacked, 1L), "south stand at 1 tile is the cupboard in the next row");
   }

   @Test
   void faceSnapHugsTheBoxOnAllFourSides() {
      Coord2d gob = Coord2d.of(0.0, 0.0);
      Coord2d north = CupboardCatalog.faceSnap(gob, Coord2d.of(1.2, -8.0));
      Assertions.assertEquals(0.0, north.x, 1.0E-9);
      Assertions.assertEquals(-7.5, north.y, 1.0E-9);
      Coord2d east = CupboardCatalog.faceSnap(gob, Coord2d.of(6.0, 0.4));
      Assertions.assertEquals(7.5, east.x, 1.0E-9);
      Assertions.assertEquals(0.0, east.y, 1.0E-9);
      Coord2d west = CupboardCatalog.faceSnap(gob, Coord2d.of(-8.0, 0.2));
      Assertions.assertEquals(-7.5, west.x, 1.0E-9);
      Assertions.assertEquals(0.0, west.y, 1.0E-9);
   }

   @Test
   void cupboardResidIgnoresSuffix() {
      Assertions.assertTrue(CupboardCatalog.isCupboardResid("gfx/terobjs/cupboard"));
      Assertions.assertTrue(CupboardCatalog.isCupboardResid("gfx/terobjs/cupboard[0]"));
      Assertions.assertFalse(CupboardCatalog.isCupboardResid("gfx/terobjs/chest"));
      Assertions.assertFalse(CupboardCatalog.isCupboardResid(null));
   }

   @Test
   void formatIncludesQualityAmountAndCurioLp() {
      Entry branch = new Entry("Branch", 24.0, 12.0, null, null);
      Assertions.assertEquals("12× Branch  q24", CupboardCatalog.format(branch));
      Entry curio = new Entry("Chiming Bluebell", 42.4, 1.0, 1200, 85);
      Assertions.assertEquals("Chiming Bluebell  q42.4  LP 1200  LP/H 85", CupboardCatalog.format(curio));
   }

   @Test
   void mergeSumsAmountForSameItemAndQuality() {
      List<Entry> merged = CupboardCatalog.merge(
         Arrays.asList(
            new Entry("Branch", 24.0, 1.0, null, null),
            new Entry("Branch", 24.0, 1.0, null, null),
            new Entry("Branch", 30.0, 1.0, null, null),
            new Entry("Chiming Bluebell", 40.0, 1.0, 1000, 70),
            new Entry("Chiming Bluebell", 40.0, 1.0, 1100, 80)
         )
      );
      Assertions.assertEquals("2× Branch  q24", CupboardCatalog.format(merged.get(0)));
      Assertions.assertEquals("Branch  q30", CupboardCatalog.format(merged.get(1)));
      Assertions.assertEquals(4, merged.size());
      Assertions.assertEquals("Chiming Bluebell  q40  LP 1000  LP/H 70", CupboardCatalog.format(merged.get(2)));
      Assertions.assertEquals("Chiming Bluebell  q40  LP 1100  LP/H 80", CupboardCatalog.format(merged.get(3)));
   }

   private static Coord2d pstand(Node n, Coord2d player, List<Node> cups) {
      return CupboardCatalog.playerStand(Coord2d.of(n.x, n.y), player, cups, n.id);
   }

   private static Coord2d astand(Node n, Coord2d player, List<Node> cups) {
      return CupboardCatalog.aisleStand(Coord2d.of(n.x, n.y), player, cups, n.id);
   }

   @Test
   void playerFacingStandIsAisleOnly() {
      List<Node> cols = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0), n(3L, 2.0, 0.0));
      Coord2d west = Coord2d.of(-11.0, 0.0);
      Coord2d front = pstand(cols.get(0), west, cols);
      Assertions.assertNotNull(front);
      Assertions.assertEquals(-7.5, front.x - cols.get(0).x, 1.0E-9);
      Assertions.assertNull(pstand(cols.get(1), west, cols), "west of the second column sits inside the front cupboard");
      Assertions.assertNull(pstand(cols.get(2), west, cols), "third column is also behind packed furniture");
      Coord2d south = Coord2d.of(0.0, 11.0);
      Assertions.assertNotNull(pstand(cols.get(0), south, cols), "an empty south face of the front cupboard is a legal stand");
   }

   @Test
   void eastColumnStandIsOnTheEastFace() {
      List<Node> cols = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0));
      Coord2d west = Coord2d.of(-11.0, 0.0);
      Coord2d front = astand(cols.get(0), west, cols);
      Coord2d back = astand(cols.get(1), west, cols);
      Assertions.assertNotNull(front);
      Assertions.assertNotNull(back);
      Assertions.assertEquals(-7.5, front.x - cols.get(0).x, 1.0E-9);
      Assertions.assertEquals(7.5, back.x - cols.get(1).x, 1.0E-9, "the east column is opened from the east side of the block");
      Assertions.assertEquals(3, CupboardCatalog.faceIndex(Coord2d.of(cols.get(0).x, cols.get(0).y), front));
      Assertions.assertEquals(1, CupboardCatalog.faceIndex(Coord2d.of(cols.get(1).x, cols.get(1).y), back));
   }

   @Test
   void twoColumnTourWalksWestAisleThenEastAisle() {
      List<Node> cols = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 0.0, 1.0), n(4L, 1.0, 0.0), n(5L, 1.0, 1.0));
      Coord2d player = Coord2d.of(-11.0, 5.5);
      Set<Long> walkable = new HashSet<>();
      Map<Long, Integer> axes = new HashMap<>();
      Map<Long, Integer> faces = new HashMap<>();

      for (Node n : cols) {
         Coord2d stand = astand(n, player, cols);
         Assertions.assertNotNull(stand, "both columns have an outside aisle");
         walkable.add(n.id);
         Coord2d gob = Coord2d.of(n.x, n.y);
         faces.put(n.id, CupboardCatalog.faceIndex(gob, stand));
         axes.put(n.id, CupboardCatalog.extendAxis(gob, stand));
      }

      List<Step> steps = CupboardCatalog.tour(player.x, player.y, cols, walkable, axes, faces);
      Assertions.assertEquals(4, steps.size());
      Assertions.assertEquals(1L, steps.get(0).id);
      Assertions.assertEquals(0L, steps.get(0).via);
      Assertions.assertEquals(2L, steps.get(1).id);
      Assertions.assertEquals(0L, steps.get(1).via);
      Assertions.assertEquals(0L, steps.get(2).via, "east column is a walk-around, not a through-click from the west");
      Assertions.assertEquals(0L, steps.get(3).via);
      Set<Long> firstHalf = new HashSet<>(Arrays.asList(steps.get(0).id, steps.get(1).id));
      Assertions.assertEquals(new HashSet<>(Arrays.asList(1L, 2L)), firstHalf);
      Set<Long> secondHalf = new HashSet<>(Arrays.asList(steps.get(2).id, steps.get(3).id));
      Assertions.assertEquals(new HashSet<>(Arrays.asList(4L, 5L)), secondHalf);
   }

   @Test
   void openAisleCupboardDoesNotViaThroughToTheNextColumn() {
      List<Node> cols = Arrays.asList(n(1L, 0.0, 0.0), n(4L, 1.0, 0.0), n(7L, 2.0, 0.0));
      Set<Long> walkable = new HashSet<>(Arrays.asList(1L, 7L));
      Map<Long, Integer> axes = new HashMap<>();
      axes.put(1L, 1);
      axes.put(7L, 1);
      List<Step> steps = CupboardCatalog.tour(-11.0, 0.0, cols, walkable, axes);
      Step mid = steps.stream().filter(s -> s.id == 4L).findFirst().orElse(null);
      Assertions.assertNotNull(mid);
      Assertions.assertTrue(mid.via == 1L || mid.via == 7L, "a column with no aisle of its own is a nook: open the neighbour, then click");
   }

   @Test
   void wallFlushRowStandsInTheRoomNotOnTheWall() {
      List<Node> cups = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 0.0, 1.0), n(3L, 0.0, 2.0), n(10L, 4.0, 1.0), n(11L, 5.0, 1.0));
      Coord2d player = Coord2d.of(22.0, 11.0);

      for (int i = 0; i < 3; i++) {
         Coord2d stand = astand(cups.get(i), player, cups);
         Assertions.assertNotNull(stand, "west-wall #" + cups.get(i).id);
         Assertions.assertTrue(stand.x > cups.get(i).x, "west-wall #" + cups.get(i).id + " must stand in the east aisle, not in the wall");
      }

      List<Node> north = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0), n(3L, 2.0, 0.0), n(10L, 1.0, 4.0));

      for (Node cup : north.subList(0, 3)) {
         Coord2d stand = astand(cup, player, north);
         Assertions.assertNotNull(stand, "north-wall #" + cup.id);
         Assertions.assertTrue(stand.y > cup.y, "north-wall #" + cup.id + " must stand in the south aisle, including the west corner");
      }
   }

   @Test
   void roomCornerIsANookNotAWallWalk() {
      List<Node> room = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0), n(3L, 2.0, 0.0), n(4L, 0.0, 1.0), n(5L, 0.0, 2.0), n(10L, 3.0, 3.0));
      Coord2d player = Coord2d.of(11.0, 11.0);
      Assertions.assertNull(astand(room.get(0), player, room), "the NW L sits on two walls: open a neighbour, then click");
      Set<Long> walkable = new HashSet<>();
      Map<Long, Integer> faces = new HashMap<>();

      for (Node n : room) {
         Coord2d stand = astand(n, player, room);
         if (stand != null) {
            walkable.add(n.id);
            faces.put(n.id, CupboardCatalog.faceIndex(Coord2d.of(n.x, n.y), stand));
         }
      }

      Assertions.assertTrue(walkable.contains(2L) || walkable.contains(4L));
      List<Step> steps = CupboardCatalog.tour(player.x, player.y, room, walkable, null, faces);
      Step s1 = steps.stream().filter(s -> s.id == 1L).findFirst().orElse(null);
      Assertions.assertNotNull(s1);
      Assertions.assertTrue(s1.via == 2L || s1.via == 4L, "click the corner with 2 or 4 already open");
   }

   @Test
   void eastWallPackedPairAreNooksNotUniqueNSWalks() {
      List<Node> room = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 2.0, 0.0), n(3L, 0.0, 3.0), n(21L, 4.0, 4.0), n(22L, 5.0, 4.0), n(24L, 4.0, 5.0), n(23L, 5.0, 5.0));
      Coord2d player = Coord2d.of(33.0, 44.0);
      Assertions.assertNull(astand(n(22L, 5.0, 4.0), player, room), "22's unique north hug is the wall, not a walk-up");
      Assertions.assertNull(astand(n(23L, 5.0, 5.0), player, room), "23's unique south hug is the desk, not a walk-up");
      Assertions.assertNotNull(astand(n(21L, 4.0, 4.0), player, room), "21 has the west aisle");
      Assertions.assertNotNull(astand(n(24L, 4.0, 5.0), player, room), "24 has the west aisle");
      Set<Long> walkable = new HashSet<>();
      Map<Long, Integer> faces = new HashMap<>();

      for (Node n : room) {
         Coord2d stand = astand(n, player, room);
         if (stand != null) {
            walkable.add(n.id);
            faces.put(n.id, CupboardCatalog.faceIndex(Coord2d.of(n.x, n.y), stand));
         }
      }

      List<Step> steps = CupboardCatalog.tour(player.x, player.y, room, walkable, null, faces);
      Step s22 = steps.stream().filter(s -> s.id == 22L).findFirst().orElse(null);
      Step s23 = steps.stream().filter(s -> s.id == 23L).findFirst().orElse(null);
      Assertions.assertNotNull(s22);
      Assertions.assertNotNull(s23);
      Assertions.assertEquals(21L, s22.via, "click 22 with 21 already open");
      Assertions.assertEquals(24L, s23.via, "click 23 with 24 already open");
   }

   @Test
   void eastNookStandsNorthOfTheWestNeighbourNotThroughIt() {
      List<Node> island = Arrays.asList(n(16L, 0.0, 1.0), n(17L, 1.0, 1.0), n(18L, 0.0, 0.0), n(19L, 1.0, 0.0), n(1L, -4.0, -4.0), n(2L, 4.0, 4.0));
      Coord2d gob18 = Coord2d.of(0.0, 0.0);
      Coord2d gob19 = Coord2d.of(11.0, 0.0);
      Coord2d west = Coord2d.of(-7.5, 0.0);
      Coord2d north = Coord2d.of(0.0, -7.5);
      Coord2d stand = CupboardCatalog.lateralStand(gob18, gob19, west, island, 18L);
      Assertions.assertNotNull(stand);
      Assertions.assertEquals(north.x, stand.x, 1.0E-9);
      Assertions.assertEquals(north.y, stand.y, 1.0E-9);
      Assertions.assertTrue(CupboardCatalog.throughPacked(west, gob19, island, 19L), "west of 18 to 19 cuts through 18");
      Assertions.assertEquals(1, CupboardCatalog.extendAxis(gob18, west), "west face extends along Y, not through to 19");
      Assertions.assertFalse(CupboardCatalog.lateral(gob19.x, gob19.y, gob18.x, gob18.y, 1));
      Assertions.assertTrue(CupboardCatalog.lateral(gob19.x, gob19.y, gob18.x, gob18.y, 0), "north face extends along X to 19");
   }

   @Test
   void deskRowEastNookStandsWestOfTheWallColumnNotInTheWall() {
      List<Node> room = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 2.0, 0.0), n(3L, 0.0, 2.0), n(27L, 6.0, 4.0), n(30L, 5.0, 5.0), n(31L, 6.0, 5.0));
      Coord2d gob27 = Coord2d.of(66.0, 44.0);
      Coord2d gob31 = Coord2d.of(66.0, 55.0);
      Coord2d east = Coord2d.of(gob27.x + 7.5, gob27.y);
      Coord2d west = Coord2d.of(gob27.x - 7.5, gob27.y);
      Coord2d stand = CupboardCatalog.lateralStand(gob27, gob31, east, room, 27L);
      Assertions.assertNotNull(stand);
      Assertions.assertTrue(stand.x < gob27.x, "do not stand in the east wall to click 31; use the room-side face");
      Assertions.assertEquals(west.x, stand.x, 1.0E-9);
   }

   @Test
   void packedBackColumnWalksItsOwnFrontNotThroughTheEastCup() {
      List<Node> cups = Arrays.asList(n(16L, 1.0, 0.0), n(17L, 0.0, 0.0), n(18L, 1.0, 1.0), n(19L, 0.0, 1.0), n(1L, -4.0, -4.0), n(2L, 5.0, 5.0));
      Coord2d player = Coord2d.of(11.0, -11.0);
      Assertions.assertNotNull(astand(n(17L, 0.0, 0.0), player, cups), "17 has a north/west front");
      Assertions.assertNotNull(astand(n(19L, 0.0, 1.0), player, cups), "19 has a north/west front");
      Set<Long> walkable = new HashSet<>();
      Map<Long, Integer> faces = new HashMap<>();

      for (Node n : cups) {
         Coord2d stand = astand(n, player, cups);
         if (stand != null) {
            walkable.add(n.id);
            faces.put(n.id, CupboardCatalog.faceIndex(Coord2d.of(n.x, n.y), stand));
         }
      }

      Assertions.assertTrue(walkable.contains(17L) && walkable.contains(19L));
      List<Step> steps = CupboardCatalog.tour(player.x, player.y, cups, walkable, null, faces);

      for (long id : new long[]{17L, 19L}) {
         Step s = steps.stream().filter(st -> st.id == id).findFirst().orElse(null);
         Assertions.assertNotNull(s);
         Assertions.assertEquals(0L, s.via, "#" + id + " is a walk-up, not via the east column");
      }
   }

   @Test
   void packedBackRowWalksAroundToItsOwnAisle() {
      List<Node> rows = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 1.0, 0.0), n(3L, 2.0, 0.0), n(4L, 0.0, 1.0), n(5L, 1.0, 1.0), n(6L, 2.0, 1.0));
      Coord2d player = Coord2d.of(11.0, -22.0);
      Set<Long> walkable = new HashSet<>();
      Map<Long, Integer> axes = new HashMap<>();
      Map<Long, Integer> faces = new HashMap<>();

      for (Node n : rows) {
         Coord2d stand = astand(n, player, rows);
         if (stand != null) {
            walkable.add(n.id);
            Coord2d gob = Coord2d.of(n.x, n.y);
            faces.put(n.id, CupboardCatalog.faceIndex(gob, stand));
            axes.put(n.id, CupboardCatalog.extendAxis(gob, stand));
         }
      }

      Assertions.assertTrue(walkable.contains(1L) && walkable.contains(3L));
      Assertions.assertTrue(walkable.contains(4L) && walkable.contains(6L), "the packed row behind is opened from its own east/west aisle");
      Assertions.assertFalse(walkable.contains(2L));
      Assertions.assertFalse(walkable.contains(5L), "middles with no E/W aisle are nooks, not north-wall walks");
      List<Step> steps = CupboardCatalog.tour(player.x, player.y, rows, walkable, axes, faces);
      Assertions.assertEquals(6, steps.size());
      Step s2 = steps.stream().filter(s -> s.id == 2L).findFirst().orElse(null);
      Step s5 = steps.stream().filter(s -> s.id == 5L).findFirst().orElse(null);
      Assertions.assertNotNull(s2);
      Assertions.assertNotNull(s5);
      Assertions.assertTrue(s2.via == 1L || s2.via == 3L);
      Assertions.assertTrue(s5.via == 4L || s5.via == 6L);
   }

   @Test
   void rowEndCornerOpensThroughAisleNeighbour() {
      List<Node> cups = Arrays.asList(n(14L, -1.0, 0.0), n(15L, 0.0, 0.0), n(16L, 0.0, 1.0), n(4L, 1.0, 0.0));
      Set<Long> walkable = new HashSet<>(Arrays.asList(14L, 15L, 16L));
      Map<Long, Integer> axes = new HashMap<>();
      axes.put(14L, 0);
      axes.put(15L, 0);
      axes.put(16L, 0);
      List<Step> steps = CupboardCatalog.tour(0.0, -11.0, cups, walkable, axes);
      Step corner = steps.stream().filter(s -> s.id == 4L).findFirst().orElse(null);
      Assertions.assertNotNull(corner);
      Assertions.assertTrue(corner.via == 15L || corner.via == 16L, "cupboard 4 is opened with 15 or 16 already open");
      int viaAt = -1;
      int cornerAt = -1;

      for (int i = 0; i < steps.size(); i++) {
         if (steps.get(i).id == corner.via) {
            viaAt = i;
         }

         if (steps.get(i).id == 4L) {
            cornerAt = i;
         }
      }

      Assertions.assertTrue(viaAt >= 0 && cornerAt > viaAt, "keep 15/16 open, then click 4");
   }

   @Test
   void backToBackOpensTheBlockedOneThroughTheWalkableNeighbour() {
      List<Node> pair = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 0.0, 1.0));
      Assertions.assertFalse(CupboardCatalog.isCorner(pair.get(0), pair), "two cupboards back-to-back are not geometric L-corners");
      Set<Long> walkable = new HashSet<>(Collections.singleton(1L));
      List<Step> steps = CupboardCatalog.tour(0.0, -11.0, pair, walkable);
      Assertions.assertEquals(2, steps.size());
      Assertions.assertEquals(1L, steps.get(0).id);
      Assertions.assertEquals(0L, steps.get(0).via);
      Assertions.assertEquals(2L, steps.get(1).id);
      Assertions.assertEquals(1L, steps.get(1).via);
      Assertions.assertTrue(steps.get(1).corner);
   }

   @Test
   void lineThroughFrontCupboardIsPacked() {
      List<Node> cols = Arrays.asList(n(26L, 0.0, 0.0), n(4L, 1.0, 0.0));
      Coord2d west = Coord2d.of(-7.5, 0.0);
      Coord2d east = Coord2d.of(18.5, 0.0);
      Assertions.assertTrue(CupboardCatalog.throughPacked(west, east, cols, 4L), "west of 26 to east of 4 cuts through 26");
      Assertions.assertTrue(CupboardCatalog.throughPacked(west, Coord2d.of(cols.get(1).x, cols.get(1).y), cols, 4L), "west of 26 is not a legal click on 4");
      Assertions.assertFalse(CupboardCatalog.throughPacked(west, Coord2d.of(-7.5, 11.0), cols, -1L), "the west aisle itself is clear");
   }

   @Test
   void eastStandDetoursAroundThePackedBlock() {
      List<Node> block = Arrays.asList(
         n(30L, 0.0, -1.0), n(26L, 0.0, 0.0), n(27L, 0.0, 1.0), n(29L, 1.0, -1.0), n(4L, 1.0, 0.0), n(7L, 1.0, 1.0), n(11L, 0.0, -4.0), n(12L, 1.0, -4.0)
      );
      Coord2d west = Coord2d.of(-7.5, 0.0);
      Coord2d east = Coord2d.of(18.5, 0.0);
      List<Coord2d> route = CupboardCatalog.detour(west, east, block);
      Assertions.assertTrue(route.size() >= 3, "must not floor-click straight through 26");

      for (int i = 1; i < route.size(); i++) {
         Assertions.assertFalse(CupboardCatalog.throughPacked(route.get(i - 1), route.get(i), block, -1L), "detour segment " + i + " still cuts furniture");
      }

      Assertions.assertEquals(east.x, route.get(route.size() - 1).x, 1.0E-9);
      Assertions.assertEquals(east.y, route.get(route.size() - 1).y, 1.0E-9);

      for (int i = 1; i < route.size() - 1; i++) {
         Assertions.assertTrue(route.get(i).y > -22.0, "detour must go around the packed block, not the distant north wall");
      }
   }

   @Test
   void frontRowNooksOpenThroughTheAisleWindow() {
      List<Node> cups = Arrays.asList(n(24L, 0.0, 0.0), n(9L, 1.0, 0.0), n(12L, 0.0, -1.0), n(13L, 1.0, -1.0), n(14L, 2.0, -1.0));
      Coord2d player = Coord2d.of(5.5, 11.0);
      Set<Long> walkable = new HashSet<>();
      Map<Long, Integer> axes = new HashMap<>();
      Map<Long, Integer> faces = new HashMap<>();

      for (Node n : cups) {
         Coord2d stand = astand(n, player, cups);
         if (stand != null) {
            walkable.add(n.id);
            Coord2d gob = Coord2d.of(n.x, n.y);
            faces.put(n.id, CupboardCatalog.faceIndex(gob, stand));
            axes.put(n.id, CupboardCatalog.extendAxis(gob, stand));
         }
      }

      Assertions.assertTrue(walkable.contains(24L) && walkable.contains(9L), "the front row is the walk-up aisle");
      Assertions.assertFalse(walkable.contains(13L), "13 has no E/W aisle: click it with a neighbour open");
      List<Step> steps = CupboardCatalog.tour(player.x, player.y, cups, walkable, axes, faces);
      Step s13 = steps.stream().filter(s -> s.id == 13L).findFirst().orElse(null);
      Assertions.assertNotNull(s13);
      Assertions.assertTrue(s13.via == 9L || s13.via == 14L, "open 13 while the aisle neighbour (9) or row-end (14) window is up");
      int atVia = -1;
      int at13 = -1;

      for (int i = 0; i < steps.size(); i++) {
         if (steps.get(i).id == s13.via) {
            atVia = i;
         }

         if (steps.get(i).id == 13L) {
            at13 = i;
         }
      }

      Assertions.assertTrue(at13 == atVia + 1, "click 13 immediately after opening its via");
   }

   @Test
   void tourDoesNotDependOnWhereThePlayerIsStanding() {
      List<Node> cups = Arrays.asList(
         n(24L, 0.0, 0.0), n(9L, 1.0, 0.0), n(12L, 0.0, -1.0), n(13L, 1.0, -1.0), n(14L, 2.0, -1.0), n(4L, 2.0, 0.0), n(5L, 2.0, 1.0)
      );
      List<Step> a = planFrom(cups, Coord2d.of(-11.0, 11.0));
      List<Step> b = planFrom(cups, Coord2d.of(33.0, -22.0));
      Assertions.assertEquals(a.size(), b.size());

      for (int i = 0; i < a.size(); i++) {
         Assertions.assertEquals(a.get(i).id, b.get(i).id, "step " + i + " gob");
         Assertions.assertEquals(a.get(i).via, b.get(i).via, "step " + i + " via");
      }
   }

   private static List<Step> planFrom(List<Node> cups, Coord2d player) {
      Set<Long> walkable = new HashSet<>();
      Map<Long, Integer> axes = new HashMap<>();
      Map<Long, Integer> faces = new HashMap<>();
      Map<Long, Coord2d> stands = new HashMap<>();

      for (Node n : cups) {
         Coord2d stand = astand(n, player, cups);
         if (stand != null) {
            walkable.add(n.id);
            stands.put(n.id, stand);
            Coord2d gob = Coord2d.of(n.x, n.y);
            faces.put(n.id, CupboardCatalog.faceIndex(gob, stand));
            axes.put(n.id, CupboardCatalog.extendAxis(gob, stand));
         }
      }

      return CupboardCatalog.tour(player.x, player.y, cups, walkable, axes, faces, stands);
   }

   @Test
   void tourFinishesOneCorridorBeforeTheNext() {
      List<Node> cups = Arrays.asList(n(1L, 0.0, 0.0), n(2L, 0.0, 1.0), n(3L, 0.0, 2.0), n(4L, 1.0, 0.0), n(5L, 1.0, 1.0), n(6L, 1.0, 2.0));
      Coord2d player = Coord2d.of(-11.0, 11.0);
      Set<Long> walkable = new HashSet<>();
      Map<Long, Integer> faces = new HashMap<>();
      Map<Long, Coord2d> stands = new HashMap<>();

      for (Node n : cups) {
         Coord2d stand = astand(n, player, cups);
         Assertions.assertNotNull(stand, "#" + n.id);
         walkable.add(n.id);
         stands.put(n.id, stand);
         faces.put(n.id, CupboardCatalog.faceIndex(Coord2d.of(n.x, n.y), stand));
      }

      List<Step> steps = CupboardCatalog.tour(player.x, player.y, cups, walkable, null, faces, stands);
      List<Long> ids = new ArrayList<>();

      for (Step s : steps) {
         if (s.via == 0L) {
            ids.add(s.id);
         }
      }

      Assertions.assertEquals(Arrays.asList(1L, 2L, 3L), ids.subList(0, 3), "west column first, north to south");
      Assertions.assertEquals(new HashSet<>(Arrays.asList(4L, 5L, 6L)), new HashSet<>(ids.subList(3, 6)), "then the east column as a block");
      Assertions.assertTrue(
         ids.get(3) == 4L && ids.get(5) == 6L || ids.get(3) == 6L && ids.get(5) == 4L, "enter the east column at the nearer end and sweep it"
      );
   }
}
