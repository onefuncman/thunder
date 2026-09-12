package haven.pathfinding;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
 *
 * <p>The export path is controlled by the system property
 * {@code haven.area_export_path}, defaulting to
 * {@code /home/greg/Documents/HavenHeadlessWorker/config/haven-areas.json}.</p>
 */
final class AreaExport {

    private static final String DEFAULT_EXPORT_PATH =
        "/home/greg/Documents/HavenHeadlessWorker/config/haven-areas.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AreaExport() {
    }

    /** Returns the configured export file path. */
    static Path exportPath() {
        String path = System.getProperty("haven.area_export_path", DEFAULT_EXPORT_PATH);
        return Paths.get(path);
    }

    /**
     * Produces a single registry-entry JSON object.
     *
     * @param gridVertices ordered polygon vertices, each {@code {gridId, lx, ly}}
     * @param name         the durable area id (used as both id and name)
     * @return a JSON object matching the Area registry's per-area shape
     * @throws NullPointerException if {@code gridVertices} is null/empty or {@code name} is null/blank
     */
    static JsonObject toJson(List<long[]> gridVertices, String name) {
        if (gridVertices == null || gridVertices.isEmpty()) {
            throw new NullPointerException("gridVertices must not be null or empty");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new NullPointerException("name must not be null or blank");
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("id", name.trim());
        obj.add("layer", JsonNull.INSTANCE);

        JsonArray tags = new JsonArray();
        tags.add("navlab");
        obj.add("tags", tags);

        obj.add("metadata", new JsonObject());

        JsonArray verts = new JsonArray();
        for (long[] gv : gridVertices) {
            JsonArray vert = new JsonArray();
            vert.add(Long.toString(gv[0]));
            vert.add((int) gv[1]);
            vert.add((int) gv[2]);
            verts.add(vert);
        }
        obj.add("grid_vertices", verts);
        return obj;
    }

    /**
     * Upserts the given area entry into the registry at {@code registryPath},
     * then writes the result atomically.
     *
     * <p>The registry file is expected to be a JSON object shaped like:
     * <pre>{@code {"version":1, "areas":[ ... ]}}</pre>
     * Each area entry is upserted by its {@code "id"} field. If the file does
     * not exist or is unparseable, a fresh registry is created containing only
     * the new area.</p>
     *
     * @param areaEntry    a single area JSON object (as produced by {@link #toJson})
     * @param registryPath the path to the registry file
     * @throws java.io.IOException if reading or writing fails
     */
    static void upsertToRegistry(JsonObject areaEntry, Path registryPath) throws java.io.IOException {
        String areaId = areaEntry.get("id").getAsString();
        JsonObject registry;

        if (Files.exists(registryPath)) {
            byte[] bytes = Files.readAllBytes(registryPath);
            String content = new String(bytes, StandardCharsets.UTF_8).trim();
            if (!content.isEmpty()) {
                try {
                    JsonElement parsed = JsonParser.parseString(content);
                    if (parsed != null && parsed.isJsonObject()) {
                        registry = parsed.getAsJsonObject();
                    } else {
                        registry = freshRegistry();
                    }
                } catch (Exception e) {
                    registry = freshRegistry();
                }
            } else {
                registry = freshRegistry();
            }
        } else {
            registry = freshRegistry();
        }

        // Ensure version field
        if (!registry.has("version")) {
            registry.addProperty("version", 1);
        }

        JsonArray areas;
        if (registry.has("areas") && registry.get("areas").isJsonArray()) {
            areas = registry.get("areas").getAsJsonArray();
        } else {
            areas = new JsonArray();
            registry.add("areas", areas);
        }

        // Upsert: find existing entry with same id
        int foundIndex = -1;
        for (int i = 0; i < areas.size(); i++) {
            JsonElement e = areas.get(i);
            if (e.isJsonObject()) {
                JsonObject area = e.getAsJsonObject();
                if (area.has("id") && areaId.equals(area.get("id").getAsString())) {
                    foundIndex = i;
                    break;
                }
            }
        }

        if (foundIndex >= 0) {
            areas.set(foundIndex, areaEntry);
        } else {
            areas.add(areaEntry);
        }

        // Write atomically: temp file in same directory, then atomic move
        Path parent = registryPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmpFile = Files.createTempFile(
            parent != null ? parent : Paths.get("."),
            "haven-areas-", ".tmp");
        try {
            Files.write(tmpFile,
                (GSON.toJson(registry) + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(tmpFile, registryPath, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // If atomic move fails (e.g. cross-device), try non-atomic
            try {
                Files.move(tmpFile, registryPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e2) {
                // Clean up temp file if everything fails
                try { Files.deleteIfExists(tmpFile); } catch (Exception ignored) {}
                throw e2;
            }
        }
    }

    private static JsonObject freshRegistry() {
        JsonObject reg = new JsonObject();
        reg.addProperty("version", 1);
        reg.add("areas", new JsonArray());
        return reg;
    }
}