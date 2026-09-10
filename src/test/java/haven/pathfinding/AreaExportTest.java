package haven.pathfinding;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AreaExportTest {

    @Test
    void toJsonProducesGridVertexStructure() {
        List<long[]> gv = List.of(
                new long[]{1234567890123456789L, 10, 20},
                new long[]{1234567890123456789L, 30, 20},
                new long[]{1234567890123456789L, 30, 40},
                new long[]{1234567890123456789L, 10, 40});

        JSONObject json = AreaExport.toJson(gv, "vil-claim", "avoid");

        assertEquals("vil-claim", json.getString("id"));
        assertEquals("avoid", json.getString("role"));
        assertTrue(json.isNull("layer"));

        JSONArray verts = json.getJSONArray("grid_vertices");
        assertEquals(4, verts.length());
        // grid id is a 64-bit value serialized as a string
        assertEquals("1234567890123456789", verts.getJSONArray(0).getString(0));
        assertEquals(10, verts.getJSONArray(0).getInt(1));
        assertEquals(20, verts.getJSONArray(0).getInt(2));
        assertEquals("1234567890123456789", verts.getJSONArray(3).getString(0));
        assertEquals(10, verts.getJSONArray(3).getInt(1));
        assertEquals(40, verts.getJSONArray(3).getInt(2));

        assertTrue(json.has("exported_at_ms"));
        assertTrue(json.getLong("exported_at_ms") > 0);
    }

    @Test
    void toJsonDefaultsRoleToAvoid() {
        List<long[]> gv = List.of(
                new long[]{1L, 0, 0},
                new long[]{1L, 5, 0},
                new long[]{1L, 5, 5},
                new long[]{1L, 0, 5});

        assertEquals("avoid", AreaExport.toJson(gv, "x", null).getString("role"));
        assertEquals("avoid", AreaExport.toJson(gv, "x", "   ").getString("role"));
    }

    @Test
    void toJsonTrimsName() {
        List<long[]> gv = List.of(new long[]{1L, 0, 0}, new long[]{1L, 5, 0}, new long[]{1L, 5, 5});
        assertEquals("yard", AreaExport.toJson(gv, "  yard  ", "avoid").getString("id"));
    }

    @Test
    void toJsonSerializesNegativeGridIdAsSignedString() {
        List<long[]> gv = List.of(new long[]{-8372368093160356060L, 7, 8});
        JSONObject json = AreaExport.toJson(gv, "n", "avoid");
        assertEquals("-8372368093160356060", json.getJSONArray("grid_vertices").getJSONArray(0).getString(0));
    }

    @Test
    void toJsonRejectsNullVertices() {
        assertThrows(NullPointerException.class, () -> AreaExport.toJson(null, "n", "avoid"));
    }

    @Test
    void toJsonRejectsEmptyVertices() {
        assertThrows(NullPointerException.class, () -> AreaExport.toJson(List.of(), "n", "avoid"));
    }

    @Test
    void toJsonRejectsNullName() {
        List<long[]> gv = List.of(new long[]{1L, 0, 0});
        assertThrows(NullPointerException.class, () -> AreaExport.toJson(gv, null, "avoid"));
        assertThrows(NullPointerException.class, () -> AreaExport.toJson(gv, "   ", "avoid"));
    }
}
