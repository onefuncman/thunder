package haven.pathfinding;

import haven.*;
import haven.MapFile.Grid;
import haven.MapFile.TileInfo;
import haven.Resource.Saved;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MapFileWaterSourceTest {
    private static final long SEGMENT = 0x123456789abcdef0L;
    private static final long GRID = 0x4400L;

    @Test
    void classifiesWaterByTerrainEvenAcrossLargeHeightChanges() throws Exception {
        MemoryCache cache = new MemoryCache();
        MapFile file = new MapFile(cache, "water-fixture");
        writeSegment(file, Collections.singletonMap(Coord.of(0, 0), GRID));
        Map<Coord, String> names = new HashMap<>();
        names.put(Coord.of(10, 10), "gfx/tiles/water");
        names.put(Coord.of(11, 10), "gfx/tiles/deep/water");
        names.put(Coord.of(12, 10), "gfx/tiles/grass");
        names.put(Coord.of(13, 10), "gfx/tiles/notile");
        names.put(Coord.of(20, 20), "gfx/tiles/water");
        Map<Coord, Float> heights = new HashMap<>();
        heights.put(Coord.of(20, 20), 0.0F);
        heights.put(Coord.of(21, 20), 30.0F);
        heights.put(Coord.of(21, 21), 30.0F);
        heights.put(Coord.of(20, 21), 0.0F);
        writeGrid(file, "gfx/tiles/grass", names, heights);

        MapFileWaterSource source = new MapFileWaterSource(file, SEGMENT);
        assertEquals(WaterwayRoutePlanner.Cell.SHALLOW, source.cell(Coord.of(10, 10)));
        assertEquals(WaterwayRoutePlanner.Cell.DEEP, source.cell(Coord.of(11, 10)));
        assertEquals(WaterwayRoutePlanner.Cell.LAND, source.cell(Coord.of(12, 10)));
        assertEquals(WaterwayRoutePlanner.Cell.UNKNOWN, source.cell(Coord.of(13, 10)));
        assertEquals(WaterwayRoutePlanner.Cell.SHALLOW, source.cell(Coord.of(20, 20)));
        assertEquals(WaterwayRoutePlanner.Cell.UNKNOWN, source.cell(Coord.of(150, 10)));
        assertEquals(1, source.cachedGridCount());
        assertEquals(1, source.missingGridCount());
        source.refreshMissing();
        assertEquals(0, source.missingGridCount());
        assertEquals(1, source.cachedGridCount());
        source.refreshTile(Coord.of(20, 20));
        assertEquals(0, source.cachedGridCount());
    }

    @Test
    void absentSegmentIsUnknownAndReadsAreStable() throws Exception {
        MemoryCache cache = new MemoryCache();
        MapFile file = new MapFile(cache, "missing-water-fixture");
        MapFileWaterSource source = new MapFileWaterSource(file, 999L);
        assertEquals(WaterwayRoutePlanner.Cell.UNKNOWN, source.cell(Coord.of(1, 1)));
        assertEquals(WaterwayRoutePlanner.Cell.UNKNOWN, source.cell(Coord.of(1, 1)));
    }

    @Test
    void savedGridWaitNeverRunsWhileHoldingTheMapLock() {
        MapFile file = new MapFile(new MemoryCache(), "lock-fixture");
        Grid expected = new Grid(77L, new TileInfo[0], new int[0], new float[0], 0L);
        assertSame(expected, MapFileWaterSource.awaitGridWithoutMapLock(file, () -> expected));

        file.lock.readLock().lock();
        try {
            assertThrows(IllegalStateException.class,
                () -> MapFileWaterSource.awaitGridWithoutMapLock(file, () -> expected));
        } finally {
            file.lock.readLock().unlock();
        }
    }

    private static void writeSegment(MapFile file, Map<Coord, Long> grids) throws IOException {
        try(StreamMessage out = new StreamMessage(file.sstore("seg-%x", SEGMENT))) {
            out.adduint8(1);
            ZMessage z = new ZMessage(out);
            z.addint64(SEGMENT);
            z.addint32(grids.size());
            for(Map.Entry<Coord, Long> entry : grids.entrySet())
                z.addcoord(entry.getKey()).addint64(entry.getValue());
            z.finish();
        }
    }

    private static void writeGrid(MapFile file, String defaultName, Map<Coord, String> overrides,
                                  Map<Coord, Float> heightOverrides) throws IOException {
        Map<String, Integer> sets = new LinkedHashMap<>();
        for(int y = 0; y < MCache.cmaps.y; y++) {
            for(int x = 0; x < MCache.cmaps.x; x++) {
                String name = overrides.getOrDefault(Coord.of(x, y), defaultName);
                if(!sets.containsKey(name)) sets.put(name, sets.size());
            }
        }
        TileInfo[] tilesets = new TileInfo[sets.size()];
        for(Map.Entry<String, Integer> entry : sets.entrySet())
            tilesets[entry.getValue()] = new TileInfo(new Saved(Resource.remote(), entry.getKey(), 1), 0);
        int[] tiles = new int[MCache.cmaps.x * MCache.cmaps.y];
        float[] zmap = new float[tiles.length];
        Arrays.fill(zmap, 10.0F);
        for(int y = 0; y < MCache.cmaps.y; y++) {
            for(int x = 0; x < MCache.cmaps.x; x++) {
                Coord tile = Coord.of(x, y);
                int index = y * MCache.cmaps.x + x;
                tiles[index] = sets.get(overrides.getOrDefault(tile, defaultName));
                zmap[index] = heightOverrides.getOrDefault(tile, 10.0F);
            }
        }
        new Grid(GRID, tilesets, tiles, zmap, 0L).save(file);
    }

    private static final class MemoryCache implements ResCache {
        final Map<String, byte[]> data = new HashMap<>();

        @Override
        public OutputStream store(final String name) {
            return new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    super.close();
                    synchronized(data) {data.put(name, toByteArray());}
                }
            };
        }

        @Override
        public InputStream fetch(String name) throws IOException {
            synchronized(data) {
                byte[] bytes = data.get(name);
                if(bytes == null) throw new FileNotFoundException(name);
                return new ByteArrayInputStream(bytes);
            }
        }
    }
}
