package thunder;

import haven.Coord;
import haven.Coord2d;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DirectionalForagerLogicTest {
    @Test
    void caveRouteLookaheadHonorsRealDistance() {
        List<Coord> route = new ArrayList<>();
        for(int i = 0; i <= 10; i++) route.add(Coord.of(i, i));
        int index = DirectionalForagerLogic.lookahead(route, 1, Coord.of(0, 0), 8.0);
        assertEquals(5, index);
        assertTrue(Coord.of(0, 0).dist(route.get(index)) <= 8.0);
    }

    @Test
    void caveRouteRejoinIsBoundedAndKeepsRejectedDiagnostics() {
        List<Coord> route = new ArrayList<>();
        for(int x = 0; x <= 30; x++) route.add(Coord.of(x, 0));
        DirectionalForagerLogic.RejoinDecision decision = DirectionalForagerLogic.rejoinDecision(
            route, Coord.of(17, 4), 10, 4, 20, 2.0);
        assertFalse(decision.accepted);
        assertEquals(10, decision.selectedIndex);
        assertEquals(17, decision.nearestIndex);
        assertEquals(4.0, decision.nearestDistance, 0.001);
    }

    @Test void compassDirectionsUseWorldAxes() {
        Coord2d p = Coord2d.of(100, 200);
        assertPoint(100, 190, DirectionalForagerLogic.forward(p, DirectionalForagerLogic.Direction.NORTH, 10));
        assertPoint(100, 210, DirectionalForagerLogic.forward(p, DirectionalForagerLogic.Direction.SOUTH, 10));
        assertPoint(90, 200, DirectionalForagerLogic.forward(p, DirectionalForagerLogic.Direction.WEST, 10));
        assertPoint(110, 200, DirectionalForagerLogic.forward(p, DirectionalForagerLogic.Direction.EAST, 10));
    }

    @Test void nearestHonorsWhitelistAndFailedTargets() {
        List<DirectionalForagerLogic.Candidate> c = Arrays.asList(
            new DirectionalForagerLogic.Candidate(1, "dandelion", Coord2d.of(2, 0)),
            new DirectionalForagerLogic.Candidate(2, "rustroot", Coord2d.of(3, 0)),
            new DirectionalForagerLogic.Candidate(3, "dandelion", Coord2d.of(4, 0)));
        Set<String> selected = Collections.singleton("dandelion");
        assertEquals(1, DirectionalForagerLogic.nearest(Coord2d.z, c, selected, Collections.emptySet()).id);
        assertEquals(3, DirectionalForagerLogic.nearest(Coord2d.z, c, selected, Collections.singleton(1L)).id);
    }

    @Test void noSelectionMeansNoTarget() {
        DirectionalForagerLogic.Candidate c = new DirectionalForagerLogic.Candidate(1, "dandelion", Coord2d.z);
        assertNull(DirectionalForagerLogic.nearest(Coord2d.z, Collections.singleton(c), Collections.emptySet(), Collections.emptySet()));
    }

    @Test void caveProbesRotateAroundPreferredHeading() {
        Coord2d p = Coord2d.of(100, 100);
        assertPoint(100, 90, DirectionalForagerLogic.probe(p, DirectionalForagerLogic.Direction.NORTH, 10, 0));
        assertPoint(110, 100, DirectionalForagerLogic.probe(p, DirectionalForagerLogic.Direction.NORTH, 10, Math.PI / 2));
        assertEquals(10, DirectionalForagerLogic.forwardProgress(p, Coord2d.of(100, 90), DirectionalForagerLogic.Direction.NORTH), 0.0001);
        assertEquals(0, DirectionalForagerLogic.forwardProgress(p, Coord2d.of(110, 100), DirectionalForagerLogic.Direction.NORTH), 0.0001);
    }

    @Test void forwardProbeFanOffersExactAlternativesWithoutLosingHeading() {
        Coord2d p = Coord2d.of(100, 100);
        List<Coord2d> probes = DirectionalForagerLogic.forwardProbes(p,
            DirectionalForagerLogic.Direction.NORTH, 10,
            0, Math.PI / 8, -Math.PI / 8, Math.PI / 4, -Math.PI / 4);

        assertEquals(5, probes.size());
        assertPoint(100, 90, probes.get(0));
        for(Coord2d probe : probes) {
            assertEquals(10, p.dist(probe), 0.0001);
            assertTrue(DirectionalForagerLogic.forwardProgress(
                p, probe, DirectionalForagerLogic.Direction.NORTH) >= Math.sqrt(50));
        }
    }

    @Test void targetProbeFanAdvancesTowardTarget() {
        Coord2d p = Coord2d.of(10, 20);
        Coord2d target = Coord2d.of(110, 20);
        List<Coord2d> probes = DirectionalForagerLogic.towardProbes(
            p, target, 10, 0, Math.PI / 4, -Math.PI / 4);

        assertEquals(3, probes.size());
        assertPoint(20, 20, probes.get(0));
        for(Coord2d probe : probes) {
            assertEquals(10, p.dist(probe), 0.0001);
            assertTrue(probe.dist(target) < p.dist(target));
        }
    }

    @Test void forageableApproachFanKeepsPointTargetStandoff() {
        Coord2d player = Coord2d.of(10, 20);
        Coord2d target = Coord2d.of(110, 20);
        List<Coord2d> probes = DirectionalForagerLogic.approachProbes(
            player, target, 8, 0, Math.PI / 2, -Math.PI / 2);

        assertEquals(3, probes.size());
        assertPoint(102, 20, probes.get(0));
        for(Coord2d probe : probes) assertEquals(8, probe.dist(target), 0.0001);
    }

    @Test void dangerRadiusRejectsOnlyPointsInsideIt() {
        List<Coord2d> dangers = Collections.singletonList(Coord2d.of(100, 100));
        assertFalse(DirectionalForagerLogic.safeFrom(Coord2d.of(109, 100), dangers, 10));
        assertTrue(DirectionalForagerLogic.safeFrom(Coord2d.of(110, 100), dangers, 10));
        assertTrue(DirectionalForagerLogic.safeFrom(Coord2d.of(200, 200), dangers, 10));
    }

    @Test void removedForageableIsConfirmedWithoutAnInventorySignatureChange() {
        assertTrue(DirectionalForagerLogic.pickupConfirmed(true, false));
        assertTrue(DirectionalForagerLogic.pickupConfirmed(true, true));
        assertFalse(DirectionalForagerLogic.pickupConfirmed(false, true));
    }

    @Test void failedCaveLegBlacklistsTheFirstRouteTileBeyondTheStop() {
        List<Coord> route = new ArrayList<>();
        for(int x = 0; x <= 10; x++) route.add(Coord.of(x, 0));

        assertEquals(Coord.of(6, 0), DirectionalForagerLogic.firstBlockedRouteTile(
            route, Coord.of(5, 0), 2, 8));
        assertEquals(Coord.of(8, 0), DirectionalForagerLogic.firstBlockedRouteTile(
            route, Coord.of(8, 0), 2, 8));
    }

    @Test void completedCaveRouteCannotBeRetreadButCurrentTileCanStartTheNextChunk() {
        Coord current = Coord.of(5, 0);
        Set<Coord> traversed = new HashSet<>(Arrays.asList(
            Coord.of(3, 0), Coord.of(4, 0), current));
        Set<Coord> blocked = Collections.singleton(Coord.of(8, 0));
        Set<Coord> covered = new HashSet<>(Arrays.asList(
            current, Coord.of(6, 0), Coord.of(-40, 0)));

        assertTrue(DirectionalForagerLogic.caveRouteBlocked(
            Coord.of(4, 0), current, blocked, traversed, covered, 34));
        assertFalse(DirectionalForagerLogic.caveRouteBlocked(
            current, current, blocked, traversed, covered, 34));
        assertFalse(DirectionalForagerLogic.caveRouteBlocked(
            Coord.of(6, 0), current, blocked, traversed, covered, 34));
        assertFalse(DirectionalForagerLogic.caveRouteBlocked(
            Coord.of(40, 0), current, blocked, traversed, covered, 34));
        assertTrue(DirectionalForagerLogic.caveRouteBlocked(
            Coord.of(-40, 0), current, blocked, traversed, covered, 34));
        assertTrue(DirectionalForagerLogic.caveRouteBlocked(
            Coord.of(8, 0), current, blocked, traversed, covered, 34));
    }

    @Test void catalogNormalizesWorldAndInventoryResources() {
        assertEquals("spindlytaproot", ForageCatalog.key("gfx/terobjs/herbs/spindlytaproot"));
        assertEquals("spindlytaproot", ForageCatalog.key("gfx/invobjs/herbs/spindlytaproot"));
        Set<String> keys = new HashSet<>();
        for(ForageCatalog.Entry entry : ForageCatalog.entries()) keys.add(entry.key);
        assertTrue(keys.containsAll(Arrays.asList("perfectautumnleaf", "tansy", "windweed", "champignon", "clay-gray", "lakesnail")));
    }

    private static void assertPoint(double x, double y, Coord2d p) {
        assertEquals(x, p.x, 0.0001); assertEquals(y, p.y, 0.0001);
    }
}
