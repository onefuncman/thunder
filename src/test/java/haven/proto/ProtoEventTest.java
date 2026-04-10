package haven.proto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ProtoEventTest {

    @Test
    void builderDefaults() {
        ProtoEvent evt = new ProtoEvent.Builder().build();
        assertEquals(ProtoEvent.Direction.IN, evt.dir);
        assertEquals(ProtoEvent.Category.SESSION, evt.category);
        assertEquals("UNKNOWN", evt.typeName);
        assertEquals(-1, evt.typeId);
        assertEquals("", evt.summary);
        assertEquals("", evt.detail);
        assertEquals(0, evt.sizeBytes);
        assertEquals(-1, evt.gobId);
        assertEquals(-1, evt.widgetId);
    }

    @Test
    void builderSetsAllFields() {
        ProtoEvent evt = new ProtoEvent.Builder()
            .timestamp(1.5)
            .dir(ProtoEvent.Direction.OUT)
            .category(ProtoEvent.Category.WIDGET)
            .typeName("TEST")
            .typeId(42)
            .summary("test summary")
            .detail("test detail")
            .sizeBytes(100)
            .gobId(999L)
            .widgetId(7)
            .build();
        assertEquals(1.5, evt.timestamp, 1e-10);
        assertEquals(ProtoEvent.Direction.OUT, evt.dir);
        assertEquals(ProtoEvent.Category.WIDGET, evt.category);
        assertEquals("TEST", evt.typeName);
        assertEquals(42, evt.typeId);
        assertEquals("test summary", evt.summary);
        assertEquals("test detail", evt.detail);
        assertEquals(100, evt.sizeBytes);
        assertEquals(999L, evt.gobId);
        assertEquals(7, evt.widgetId);
    }

    @Test
    void categoryHasLabel() {
        assertEquals("Widget", ProtoEvent.Category.WIDGET.label);
        assertEquals("Object", ProtoEvent.Category.OBJECT.label);
        assertEquals("Map", ProtoEvent.Category.MAP.label);
        assertEquals("Session", ProtoEvent.Category.SESSION.label);
        assertEquals("Glob", ProtoEvent.Category.GLOB.label);
        assertEquals("Resource", ProtoEvent.Category.RESOURCE.label);
    }

    @Test
    void categoryHasColor() {
        assertNotNull(ProtoEvent.Category.WIDGET.color);
        assertNotNull(ProtoEvent.Category.OBJECT.color);
        assertNotNull(ProtoEvent.Category.MAP.color);
        assertNotNull(ProtoEvent.Category.SESSION.color);
        assertNotNull(ProtoEvent.Category.GLOB.color);
        assertNotNull(ProtoEvent.Category.RESOURCE.color);
    }

    @Test
    void directionValues() {
        assertEquals(2, ProtoEvent.Direction.values().length);
        assertNotNull(ProtoEvent.Direction.IN);
        assertNotNull(ProtoEvent.Direction.OUT);
    }
}
