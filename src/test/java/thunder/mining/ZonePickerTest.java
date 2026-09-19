package thunder.mining;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ZonePickerTest {
    @Test public void largeDragKeepsBothCornersAndIncludesTheReleasedTile() {
        Area area = ZonePicker.areaFor(Coord.of(100, 200), Coord.of(106, 204));

        assertEquals(Coord.of(100, 200), area.ul);
        assertEquals(Coord.of(107, 205), area.br);
        assertEquals(Coord.of(7, 5), area.sz());
    }

    @Test public void reverseDragProducesTheSameRectangle() {
        assertEquals(
            ZonePicker.areaFor(Coord.of(100, 200), Coord.of(106, 204)),
            ZonePicker.areaFor(Coord.of(106, 204), Coord.of(100, 200)));
    }
}
