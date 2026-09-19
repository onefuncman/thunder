package haven.pathfinding;

import haven.Coord;
import haven.MapFile;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Read-only cave-floor view of one live saved-map segment. */
public final class MapFileCaveSource implements CaveRoutePlanner.Source {
    /**
     * Exact cave-floor resources the strategic and local cave planners may
     * traverse. Generic {@code gfx/tiles/cave} is intentionally absent: it is
     * the dark cave terrain around the named walkable floor biomes.
     */
    private static final Set<String> WALKABLE_CAVE_FLOORS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "gfx/tiles/mine",
            "gfx/tiles/deepcave",
            "gfx/tiles/gleamgrotto",
            "gfx/tiles/lushcave",
            "gfx/tiles/shadehollow",
            "gfx/tiles/warmdepth",
            "gfx/tiles/wildcavern",
            // Older maps and clients can still expose this cave-floor name.
            "gfx/tiles/gloomdark"
        ))
    );

    private final MapFileWaterSource terrain;

    public MapFileCaveSource(MapFile file, long segmentId) {
        terrain = new MapFileWaterSource(file, segmentId);
    }

    public long segmentId() {return terrain.segmentId();}
    public void refreshMissing() {terrain.refreshMissing();}
    public int cachedGridCount() {return terrain.cachedGridCount();}
    public int missingGridCount() {return terrain.missingGridCount();}

    @Override
    public CaveRoutePlanner.Cell cell(Coord tile) {
        return classify(terrain.terrain(tile));
    }

    public static boolean isWalkableCaveFloor(String name) {
        return name != null && WALKABLE_CAVE_FLOORS.contains(
            name.toLowerCase(Locale.ROOT));
    }

    static CaveRoutePlanner.Cell classify(String name) {
        if(TerrainPolicy.isUnknownTile(name)) return CaveRoutePlanner.Cell.UNKNOWN;
        return isWalkableCaveFloor(name)
            ? CaveRoutePlanner.Cell.OPEN
            : CaveRoutePlanner.Cell.BLOCKED;
    }
}
