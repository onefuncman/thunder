package haven.pathfinding;

import haven.Coord;
import haven.Indir;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MapFile.Grid;
import haven.MapFile.Segment;
import java.util.*;

/** Read-only water view of one live saved-map segment. */
public final class MapFileWaterSource {
    private final MapFile file;
    private final long segmentId;
    private final Map<Coord, Grid> grids = new HashMap<>();
    private final Set<Coord> missing = new HashSet<>();

    public MapFileWaterSource(MapFile file, long segmentId) {
        if(file == null) throw new IllegalArgumentException("file must not be null");
        this.file = file;
        this.segmentId = segmentId;
    }

    public long segmentId() {return segmentId;}

    /** Keep already loaded grids between route recalculations. Only map grids
     * which were absent during the previous pass need another lookup. */
    public void refreshMissing() {missing.clear();}

    /** Refresh the grid containing newly revealed terrain without throwing
     * away the rest of the cached waterway. */
    public void refreshTile(Coord tile) {
        if(tile != null) grids.remove(tile.div(MCache.cmaps));
        missing.clear();
    }

    public int cachedGridCount() {return grids.size();}
    public int missingGridCount() {return missing.size();}

    public String terrain(Coord tile) {
        Grid grid = grid(tile.div(MCache.cmaps));
        if(grid == null) return null;
        try {
            int ti = grid.gettile(tile.mod(MCache.cmaps));
            if(ti < 0 || grid.tilesets == null || ti >= grid.tilesets.length
                || grid.tilesets[ti] == null || grid.tilesets[ti].res == null) return null;
            return grid.tilesets[ti].res.name;
        } catch(RuntimeException failure) {
            return null;
        }
    }

    private Grid grid(Coord gridCoord) {
        Grid cached = grids.get(gridCoord);
        if(cached != null) return cached;
        if(missing.contains(gridCoord)) return null;
        Indir<Grid> indirect;
        file.lock.readLock().lock();
        try {
            Segment segment = file.segments.get(segmentId);
            if(segment == null) {
                missing.add(new Coord(gridCoord));
                return null;
            }
            indirect = segment.grid(gridCoord);
        } catch(RuntimeException failure) {
            missing.add(new Coord(gridCoord));
            return null;
        } finally {
            file.lock.readLock().unlock();
        }
        try {
            // Grid loading runs on another worker which takes this same map
            // lock. Waiting while retaining our read lock can strand that
            // worker behind a queued map-save writer.
            Grid grid = awaitGridWithoutMapLock(file, indirect);
            if(grid == null) missing.add(new Coord(gridCoord));
            else grids.put(new Coord(gridCoord), grid);
            return grid;
        } catch(RuntimeException failure) {
            missing.add(new Coord(gridCoord));
            return null;
        }
    }

    static Grid awaitGridWithoutMapLock(MapFile file, Indir<Grid> indirect) {
        if(file.lock.getReadHoldCount() != 0 || file.lock.isWriteLockedByCurrentThread())
            throw new IllegalStateException("cannot wait for a saved-map grid while holding its lock");
        if(indirect == null) return null;
        try {
            return Loading.waitforint(indirect);
        } catch(InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
