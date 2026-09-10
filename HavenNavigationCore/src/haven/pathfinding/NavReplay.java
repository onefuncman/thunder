package haven.pathfinding;

import haven.Coord;
import haven.Coord2d;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * NavReplay v1: versioned JSON for reproducing a navigation plan without a
 * live game connection. Stores occupancy, goal, routes, observations,
 * decisions, and outcome. Does not serialize renderer or UI objects.
 */
public final class NavReplay {
   public static final String FORMAT = "NavReplay";
   public static final int VERSION = 1;
   public static final String FEATURE = "navreplay";

   private NavReplay() {
   }

   public static JSONObject parse(String json) {
      return parseObject(new JSONObject(json));
   }

   public static JSONObject parseObject(JSONObject o) {
      if (o == null) {
         throw new IllegalArgumentException("NavReplay body is null");
      }
      if (!FORMAT.equals(o.optString("format"))) {
         throw new IllegalArgumentException("not a NavReplay document (format=" + o.optString("format") + ")");
      }
      if (o.optInt("version") != VERSION) {
         throw new IllegalArgumentException("unsupported NavReplay version " + o.optInt("version"));
      }
      return o;
   }

   public static JSONObject loadFile(Path file) throws IOException {
      List<String> lines = Files.readAllLines(file);
      if (lines.size() < 2) {
         throw new IllegalArgumentException("NavReplay file has no body: " + file);
      }
      JSONObject header = new JSONObject(lines.get(0));
      if (!"header".equals(header.optString("type"))) {
         throw new IllegalArgumentException("first line is not a header: " + file);
      }
      if (!FEATURE.equals(header.optString("feature")) && !FORMAT.equals(header.optString("format"))) {
         JSONObject body = new JSONObject(lines.get(1));
         if (FORMAT.equals(body.optString("format"))) {
            return parseObject(body);
         }
         throw new IllegalArgumentException("header is not a NavReplay: " + header.optString("feature"));
      }
      return parseObject(new JSONObject(lines.get(1)));
   }

   public static JSONArray points(List<Coord2d> pts) {
      JSONArray arr = new JSONArray();
      if (pts != null) {
         for (Coord2d p : pts) {
            arr.put(point(p));
         }
      }
      return arr;
   }

   public static JSONArray point(Coord2d p) {
      JSONArray a = new JSONArray();
      if (p != null) {
         a.put(round4(p.x)).put(round4(p.y));
      }
      return a;
   }

   public static JSONArray cell(Coord c) {
      return c == null ? new JSONArray() : new JSONArray().put(c.x).put(c.y);
   }

   public static Coord2d coord2d(Object v) {
      if (v instanceof JSONArray) {
         JSONArray a = (JSONArray) v;
         if (a.length() >= 2) {
            return Coord2d.of(a.getDouble(0), a.getDouble(1));
         }
      }
      return null;
   }

   public static Coord coord(Object v) {
      if (v instanceof JSONArray) {
         JSONArray a = (JSONArray) v;
         if (a.length() >= 2) {
            return Coord.of(a.getInt(0), a.getInt(1));
         }
      }
      return null;
   }

   public static List<Coord2d> coords2d(JSONArray arr) {
      if (arr == null) {
         return Collections.emptyList();
      }
      List<Coord2d> out = new ArrayList<>();
      for (int i = 0; i < arr.length(); i++) {
         Coord2d p = coord2d(arr.opt(i));
         if (p != null) {
            out.add(p);
         }
      }
      return out;
   }

   public static double round4(double v) {
      return (double) Math.round(v * 10000.0) / 10000.0;
   }
}
