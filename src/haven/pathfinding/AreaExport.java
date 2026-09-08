package haven.pathfinding;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/**
 * Builds the JSON export payload for the Haven Command Center Area registry.
 *
 * <p>Vertices are in <b>durable (grid_id, local_x, local_y)</b> form: a grid
 * id is the server-assigned, re-stitch-stable address of a 100x100 map grid,
 * and the local cell is its 0..99 tile within that grid. This survives map
 * re-exports (which re-number every grid's (gx, gy) and segment), unlike the
 * old session-world-tile / segment-keyed payload. Grid ids are serialized as
 * strings because they are 64-bit values.</p>
 */
final class AreaExport {

    private AreaExport() {
    }

    /**
     * @param gridVertices ordered polygon vertices, each {@code {gridId, lx, ly}}
     * @param name         the durable area id (user-typed)
     * @param role         the semantic role (e.g. {@code avoid}, {@code transition}); empty defaults to {@code avoid}
     * @return a JSON object matching the Area registry's per-area shape
     * @throws NullPointerException if {@code gridVertices} is null/empty or {@code name} is null/blank
     */
    static JSONObject toJson(List<long[]> gridVertices, String name, String role) {
        if (gridVertices == null || gridVertices.isEmpty()) {
            throw new NullPointerException("gridVertices must not be null or empty");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new NullPointerException("name must not be null or blank");
        }
        JSONObject obj = new JSONObject();
        obj.put("id", name.trim());
        obj.put("role", (role == null || role.trim().isEmpty()) ? "avoid" : role.trim());
        obj.put("layer", JSONObject.NULL);
        JSONArray verts = new JSONArray();
        for (long[] gv : gridVertices) {
            verts.put(new JSONArray()
                    .put(Long.toString(gv[0]))
                    .put((int) gv[1])
                    .put((int) gv[2]));
        }
        obj.put("grid_vertices", verts);
        obj.put("exported_at_ms", System.currentTimeMillis());
        return obj;
    }
}
