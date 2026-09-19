package haven.pathfinding;

import haven.Coord2d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure geometry for stepping the player out of a just-dropped object's
 * collision polygon before normal pathfinding resumes. */
public final class PlacementEgress {
    private PlacementEgress() {}

    public static List<Coord2d> candidates(ExactPlacementPlanner.Shape shape, Coord2d player,
                                           Coord2d preference, double clearance) {
        List<Coord2d> out = new ArrayList<>();
        if(shape == null || shape.bounds == null || player == null) return out;
        double safe = Math.max(0.0, clearance);
        Map<String, Coord2d> unique = new LinkedHashMap<>();
        List<Coord2d> axes = axes(shape.polygons);
        for(Coord2d axis : axes) {
            double[] range = projection(shape.polygons, axis);
            double here = dot(player, axis);
            add(unique, player.add(axis.mul((range[1] + safe) - here)));
            add(unique, player.add(axis.mul((range[0] - safe) - here)));
        }

        double radius = safe;
        for(Coord2d[] polygon : shape.polygons) for(Coord2d point : polygon)
            radius = Math.max(radius, player.dist(point) + safe);
        for(int i = 0; i < 16; i++) {
            double angle = Math.PI * 2.0 * i / 16.0;
            add(unique, player.add(Math.cos(angle) * radius, Math.sin(angle) * radius));
        }
        out.addAll(unique.values());
        Coord2d preferred = preference == null ? player : preference;
        out.sort(Comparator.comparingDouble(player::dist)
            .thenComparingDouble(preferred::dist)
            .thenComparingDouble(c -> c.y).thenComparingDouble(c -> c.x));
        return out;
    }

    /** True when a disk centered on {@code point} with the requested clearance
     * neither lies inside nor touches any polygon in the shape. */
    public static boolean clearOf(ExactPlacementPlanner.Shape shape, Coord2d point, double clearance) {
        if(shape == null || shape.bounds == null || point == null) return false;
        double safe = Math.max(0.0, clearance);
        for(Coord2d[] polygon : shape.polygons) {
            if(pointInside(polygon, point)) return false;
            for(int i = 0; i < polygon.length; i++) {
                Coord2d a = polygon[i], b = polygon[(i + 1) % polygon.length];
                if(segmentDistance(point, a, b) < safe - 1e-7) return false;
            }
        }
        return true;
    }

    private static List<Coord2d> axes(Collection<Coord2d[]> polygons) {
        List<Coord2d> out = new ArrayList<>();
        if(polygons == null) return out;
        for(Coord2d[] polygon : polygons) {
            if(polygon == null || polygon.length < 2) continue;
            for(int i = 0; i < polygon.length; i++) {
                Coord2d a = polygon[i], b = polygon[(i + 1) % polygon.length];
                double ex = b.x - a.x, ey = b.y - a.y;
                double length = Math.hypot(ex, ey);
                if(length <= 1e-9) continue;
                Coord2d axis = Coord2d.of(-ey / length, ex / length);
                boolean duplicate = false;
                for(Coord2d old : out) if(Math.abs(Math.abs(dot(old, axis)) - 1.0) < 1e-7) {
                    duplicate = true;
                    break;
                }
                if(!duplicate) out.add(axis);
            }
        }
        return out;
    }

    private static double[] projection(Collection<Coord2d[]> polygons, Coord2d axis) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for(Coord2d[] polygon : polygons) for(Coord2d point : polygon) {
            double value = dot(point, axis);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new double[]{min, max};
    }

    private static boolean pointInside(Coord2d[] polygon, Coord2d point) {
        if(polygon == null || polygon.length < 3) return false;
        boolean inside = false;
        for(int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            Coord2d a = polygon[i], b = polygon[j];
            if(((a.y > point.y) != (b.y > point.y)) &&
               point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x)
                inside = !inside;
        }
        return inside;
    }

    private static double segmentDistance(Coord2d point, Coord2d a, Coord2d b) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double length2 = dx * dx + dy * dy;
        if(length2 <= 1e-12) return point.dist(a);
        double t = ((point.x - a.x) * dx + (point.y - a.y) * dy) / length2;
        t = Math.max(0.0, Math.min(1.0, t));
        return point.dist(Coord2d.of(a.x + t * dx, a.y + t * dy));
    }

    private static void add(Map<String, Coord2d> out, Coord2d point) {
        String key = Math.round(point.x * 1000000.0) + ":" + Math.round(point.y * 1000000.0);
        out.putIfAbsent(key, point);
    }

    private static double dot(Coord2d a, Coord2d b) {
        return a.x * b.x + a.y * b.y;
    }
}
