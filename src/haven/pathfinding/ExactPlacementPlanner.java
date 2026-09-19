package haven.pathfinding;

import haven.Area;
import haven.Coord2d;
import haven.WorldFootprint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, continuous-coordinate placement planner for any exact polygon shape.
 * Candidate anchors come from area edges and shape-to-shape contact, so tight
 * packing does not depend on whole-tile rounding or a dense coordinate scan.
 */
public final class ExactPlacementPlanner {
    public static final double DEFAULT_GAP = ObjectSpatialProfiles.DEFAULT_PLACEMENT_GAP;

    public enum FillOrder {
        /** Preserve the generic organizer's historical player-facing edge order. */
        FRONT_EDGE,
        /** For long objects, fill across the narrow axis before starting the next row. */
        SIDE_BY_SIDE,
        /** Fill fixed rows from the edge farthest from the source, advancing toward it. */
        SIDE_BY_SIDE_BACK_TO_FRONT
    }

    private ExactPlacementPlanner() {}

    public static final class Rect {
        public final double minX, minY, maxX, maxY;
        public Rect(double minX, double minY, double maxX, double maxY) {
            this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY;
        }
        public double width() {return maxX - minX;}
        public double height() {return maxY - minY;}
        public Rect move(double dx, double dy) {return new Rect(minX + dx, minY + dy, maxX + dx, maxY + dy);}
        public boolean inside(Rect outer) {
            return minX >= outer.minX && minY >= outer.minY && maxX <= outer.maxX && maxY <= outer.maxY;
        }
        public boolean conflicts(Rect other, double gap) {
            return !(maxX + gap <= other.minX || other.maxX + gap <= minX ||
                     maxY + gap <= other.minY || other.maxY + gap <= minY);
        }
    }

    /** One or more collision polygons. Candidate shapes are relative to their
     * anchor; obstacle shapes are already in world coordinates. */
    public static final class Shape {
        public final List<Coord2d[]> polygons;
        public final Rect bounds;

        public Shape(Collection<Coord2d[]> source) {
            this.polygons = copyPolygons(source, Coord2d.z, false);
            this.bounds = polygonBounds(this.polygons);
        }

        public static Shape relative(Collection<Coord2d[]> world, Coord2d anchor) {
            return new Shape(copyPolygons(world, anchor == null ? Coord2d.z : anchor, true));
        }

        public static Shape rectangle(Rect bounds) {
            List<Coord2d[]> polygon = new ArrayList<>();
            if(bounds != null) polygon.add(new Coord2d[]{
                Coord2d.of(bounds.minX, bounds.minY), Coord2d.of(bounds.maxX, bounds.minY),
                Coord2d.of(bounds.maxX, bounds.maxY), Coord2d.of(bounds.minX, bounds.maxY)
            });
            return new Shape(polygon);
        }

        public Shape move(Coord2d anchor) {
            return new Shape(copyPolygons(polygons, anchor == null ? Coord2d.z : anchor, false));
        }

        public boolean inside(Area tiles, Coord2d tileSize, double gap) {
            return inside(tiles, tileSize, gap, 0.0);
        }

        /**
         * Containment check with explicit observation tolerance. Planning uses
         * zero tolerance; authoritative server positions may differ by a tiny
         * wire-coordinate round-trip tolerance after placement is encoded.
         */
        public boolean inside(Area tiles, Coord2d tileSize, double gap, double tolerance) {
            if(tiles == null || tileSize == null || bounds == null) return false;
            double inset = Math.max(0.0, gap);
            double slack = Math.max(0.0, tolerance);
            double minX = tiles.ul.x * tileSize.x + inset - slack;
            double minY = tiles.ul.y * tileSize.y + inset - slack;
            double maxX = tiles.br.x * tileSize.x - inset + slack;
            double maxY = tiles.br.y * tileSize.y - inset + slack;
            for(Coord2d[] polygon : polygons) for(Coord2d point : polygon)
                if(point.x < minX || point.y < minY || point.x > maxX || point.y > maxY) return false;
            return true;
        }

        public boolean conflicts(Shape other, double gap) {
            if(other == null || bounds == null || other.bounds == null ||
               !bounds.conflicts(other.bounds, gap)) return false;
            for(Coord2d[] a : polygons) for(Coord2d[] b : other.polygons)
                if(polygonConflicts(a, b, gap)) return true;
            return false;
        }
    }

    /**
     * @param footprintAtOrigin footprint bounds expressed relative to an anchor at (0,0)
     * @param entrance stable source-side location used to select the far edge
     */
    public static Coord2d next(Area tiles, Coord2d tileSize, Rect footprintAtOrigin,
                               List<Rect> obstacles, Coord2d entrance, double gap) {
        if(tiles == null || tileSize == null || footprintAtOrigin == null) return null;
        WorldFootprint.Bounds relative = new WorldFootprint.Bounds(footprintAtOrigin.minX,
            footprintAtOrigin.minY, footprintAtOrigin.maxX, footprintAtOrigin.maxY);
        List<WorldFootprint.Bounds> occupied = new ArrayList<>();
        if(obstacles != null) for(Rect obstacle : obstacles) occupied.add(new WorldFootprint.Bounds(
            obstacle.minX, obstacle.minY, obstacle.maxX, obstacle.maxY));
        List<Coord2d> legal = WorldFootprint.legalSubTileDrops(
            tiles, tileSize, relative, occupied, entrance, gap);
        if(!legal.isEmpty()) compactFrontEdgeOrder(legal, tiles, tileSize, footprintAtOrigin, entrance);
        return legal.isEmpty() ? null : legal.get(0);
    }

    /** Exact-polygon counterpart used for logs. Unlike the legacy AABB
     * overload, diagonal logs can abut along their real narrow sides instead
     * of reserving their much larger axis-aligned bounding boxes. */
    public static Coord2d nextExact(Area tiles, Coord2d tileSize, Shape footprintAtOrigin,
                                    List<Shape> obstacles, Coord2d entrance, double gap) {
        return nextExact(tiles, tileSize, footprintAtOrigin, obstacles, entrance, gap,
            FillOrder.FRONT_EDGE);
    }

    public static Coord2d nextExact(Area tiles, Coord2d tileSize, Shape footprintAtOrigin,
                                    List<Shape> obstacles, Coord2d entrance, double gap,
                                    FillOrder fillOrder) {
        if(tiles == null || tileSize == null || footprintAtOrigin == null || footprintAtOrigin.bounds == null ||
           !tiles.positive()) return null;
        Rect footprint = footprintAtOrigin.bounds;
        double areaMinX = tiles.ul.x * tileSize.x, areaMinY = tiles.ul.y * tileSize.y;
        double areaMaxX = tiles.br.x * tileSize.x, areaMaxY = tiles.br.y * tileSize.y;
        double lowX = areaMinX - footprint.minX;
        double highX = areaMaxX - footprint.maxX;
        double lowY = areaMinY - footprint.minY;
        double highY = areaMaxY - footprint.maxY;
        if(lowX > highX || lowY > highY) return null;

        Map<String, Coord2d> candidates = new LinkedHashMap<>();
        FillOrder order = fillOrder == null ? FillOrder.FRONT_EDGE : fillOrder;
        Coord2d source = entrance == null ? Coord2d.of(areaMinX, areaMinY) : entrance;
        double xPitch = footprint.width() + gap;
        double yPitch = footprint.height() + gap;
        boolean fixedRows = order == FillOrder.SIDE_BY_SIDE_BACK_TO_FRONT;
        boolean sideBySide = order == FillOrder.SIDE_BY_SIDE;
        double centerX = (areaMinX + areaMaxX) * 0.5;
        double centerY = (areaMinY + areaMaxY) * 0.5;
        boolean depthAlongX = Math.abs(source.x - centerX) >= Math.abs(source.y - centerY);
        boolean sourceXHigh = source.x > centerX;
        boolean sourceYHigh = source.y > centerY;
        List<Double> xs;
        List<Double> ys;
        if(fixedRows) {
            // Rows run parallel to the nearest edge of the clear-cut area. Start
            // at the opposite edge, fill one row from its low-coordinate end,
            // then advance one footprint toward the clear-cut area.
            xs = depthAlongX
                ? axisGridFromSide(lowX, highX, xPitch, !sourceXHigh)
                : axisGridFromSide(lowX, highX, xPitch, false);
            ys = depthAlongX
                ? axisGridFromSide(lowY, highY, yPitch, false)
                : axisGridFromSide(lowY, highY, yPitch, !sourceYHigh);
        } else {
            xs = sideBySide
                ? axisShelvesFromSide(lowX, highX, xPitch, sourceXHigh)
                : axisShelves(lowX, highX, xPitch);
            ys = sideBySide
                ? axisShelvesFromSide(lowY, highY, yPitch, sourceYHigh)
                : axisShelves(lowY, highY, yPitch);
        }
        for(double x : xs) for(double y : ys) addCandidate(candidates, Coord2d.of(x, y));
        if(!fixedRows && obstacles != null) for(Shape obstacle : obstacles) {
            if(obstacle == null || obstacle.bounds == null) continue;
            addContactCandidates(candidates, footprintAtOrigin, obstacle,
                lowX, highX, lowY, highY, gap);
        }

        List<Coord2d> legal = new ArrayList<>();
        for(Coord2d anchor : candidates.values()) {
            Shape placed = footprintAtOrigin.move(anchor);
            if(!placed.inside(tiles, tileSize, 0.0)) continue;
            boolean blocked = false;
            double collisionGap = fixedRows
                ? gap - PlacementGeometry.SERVER_POSITION_TOLERANCE
                : gap;
            if(obstacles != null) for(Shape obstacle : obstacles) if(placed.conflicts(obstacle, collisionGap)) {
                blocked = true;
                break;
            }
            if(!blocked) legal.add(anchor);
        }
        if(!legal.isEmpty()) compactFrontEdgeOrder(
            legal, tiles, tileSize, footprint, entrance, order);
        return legal.isEmpty() ? null : legal.get(0);
    }

    /** Plans a deterministic front-to-back sequence without changing the caller's obstacle list. */
    public static List<Coord2d> planExact(Area tiles, Coord2d tileSize, Shape footprintAtOrigin,
                                          List<Shape> obstacles, Coord2d entrance,
                                          double gap, int count) {
        return planExact(tiles, tileSize, footprintAtOrigin, obstacles, entrance, gap,
            count, FillOrder.FRONT_EDGE);
    }

    public static List<Coord2d> planExact(Area tiles, Coord2d tileSize, Shape footprintAtOrigin,
                                          List<Shape> obstacles, Coord2d entrance,
                                          double gap, int count, FillOrder fillOrder) {
        List<Coord2d> anchors = new ArrayList<>();
        if(count <= 0) return anchors;
        List<Shape> occupied = new ArrayList<>();
        if(obstacles != null) occupied.addAll(obstacles);
        for(int i = 0; i < count; i++) {
            Coord2d anchor = nextExact(
                tiles, tileSize, footprintAtOrigin, occupied, entrance, gap, fillOrder);
            if(anchor == null) break;
            anchors.add(anchor);
            occupied.add(footprintAtOrigin.move(anchor));
        }
        return anchors;
    }

    /** Fill the edge nearest the player continuously, then advance deeper. */
    private static void compactFrontEdgeOrder(List<Coord2d> legal, Area tiles, Coord2d tileSize,
                                            Rect footprint, Coord2d entrance) {
        compactFrontEdgeOrder(legal, tiles, tileSize, footprint, entrance, FillOrder.FRONT_EDGE);
    }

    private static void compactFrontEdgeOrder(List<Coord2d> legal, Area tiles, Coord2d tileSize,
                                            Rect footprint, Coord2d entrance, FillOrder fillOrder) {
        double areaMinX = tiles.ul.x * tileSize.x, areaMinY = tiles.ul.y * tileSize.y;
        double areaMaxX = tiles.br.x * tileSize.x, areaMaxY = tiles.br.y * tileSize.y;
        double centerX = (areaMinX + areaMaxX) * 0.5, centerY = (areaMinY + areaMaxY) * 0.5;
        Coord2d source = entrance == null ? Coord2d.of(areaMinX, areaMinY) : entrance;

        double lowX = areaMinX - footprint.minX, highX = areaMaxX - footprint.maxX;
        double lowY = areaMinY - footprint.minY, highY = areaMaxY - footprint.maxY;
        boolean nearXHigh = source.x > centerX, nearYHigh = source.y > centerY;
        boolean sideBySide = fillOrder == FillOrder.SIDE_BY_SIDE;
        boolean backToFront = fillOrder == FillOrder.SIDE_BY_SIDE_BACK_TO_FRONT;
        boolean xPrimary = backToFront
            ? Math.abs(source.x - centerX) >= Math.abs(source.y - centerY)
            : sideBySide
                ? footprint.width() >= footprint.height()
                : Math.abs(source.x - centerX) >= Math.abs(source.y - centerY);

        Comparator<Coord2d> order = Comparator
            .comparingDouble((Coord2d c) -> {
                double depth = xPrimary
                    ? inward(c.x, lowX, highX, nearXHigh)
                    : inward(c.y, lowY, highY, nearYHigh);
                return backToFront ? -depth : depth;
            })
            .thenComparingDouble(c -> backToFront
                ? (xPrimary ? c.y : c.x)
                : (xPrimary
                    ? inward(c.y, lowY, highY, nearYHigh)
                    : inward(c.x, lowX, highX, nearXHigh)))
            .thenComparingDouble(c -> c.y)
            .thenComparingDouble(c -> c.x);
        legal.sort(order);
    }

    private static double inward(double coordinate, double low, double high, boolean nearIsHigh) {
        return nearIsHigh ? high - coordinate : coordinate - low;
    }

    private static List<Coord2d[]> copyPolygons(Collection<Coord2d[]> source, Coord2d delta,
                                                 boolean subtract) {
        List<Coord2d[]> out = new ArrayList<>();
        if(source == null) return out;
        double dx = delta == null ? 0.0 : delta.x;
        double dy = delta == null ? 0.0 : delta.y;
        if(subtract) {dx = -dx; dy = -dy;}
        for(Coord2d[] polygon : source) {
            if(polygon == null || polygon.length == 0) continue;
            Coord2d[] copy = new Coord2d[polygon.length];
            for(int i = 0; i < polygon.length; i++) copy[i] = polygon[i].add(dx, dy);
            out.add(copy);
        }
        return out;
    }

    private static Rect polygonBounds(Collection<Coord2d[]> polygons) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        if(polygons != null) for(Coord2d[] polygon : polygons) for(Coord2d p : polygon) {
            if(p == null) continue;
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
            any = true;
        }
        return any ? new Rect(minX, minY, maxX, maxY) : null;
    }

    private static List<Double> axisShelves(double low, double high, double pitch) {
        List<Double> out = new ArrayList<>();
        out.add(low);
        if(high != low) out.add(high);
        if(pitch > 0.0) {
            for(double v = low + pitch; v < high; v += pitch) out.add(v);
            for(double v = high - pitch; v > low; v -= pitch) out.add(v);
        }
        return out;
    }

    private static List<Double> axisShelvesFromSide(double low, double high, double pitch,
                                                     boolean fromHigh) {
        List<Double> out = new ArrayList<>();
        double first = fromHigh ? high : low;
        double last = fromHigh ? low : high;
        out.add(first);
        if(pitch > 0.0) {
            if(fromHigh) {
                for(double value = high - pitch; value > low; value -= pitch) out.add(value);
            } else {
                for(double value = low + pitch; value < high; value += pitch) out.add(value);
            }
        }
        if(last != first) out.add(last);
        return out;
    }

    /** Regular slots from one edge only; unlike contact packing, this never
     * creates a new slot relative to an arbitrarily positioned obstacle. */
    private static List<Double> axisGridFromSide(double low, double high, double pitch,
                                                  boolean fromHigh) {
        List<Double> out = new ArrayList<>();
        out.add(fromHigh ? high : low);
        if(pitch <= 0.0) return out;
        if(fromHigh) {
            for(double value = high - pitch; value >= low; value -= pitch) out.add(value);
        } else {
            for(double value = low + pitch; value <= high; value += pitch) out.add(value);
        }
        return out;
    }

    private static void addContactCandidates(Map<String, Coord2d> out, Shape candidate,
                                             Shape obstacle, double lowX, double highX,
                                             double lowY, double highY, double gap) {
        List<Coord2d> candidateVertices = vertices(candidate.polygons);
        List<Coord2d> obstacleVertices = vertices(obstacle.polygons);
        List<Coord2d> axes = new ArrayList<>();
        addAxes(axes, candidate.polygons);
        addAxes(axes, obstacle.polygons);
        for(Coord2d normal : axes) {
            double[] cp = projection(candidateVertices, normal);
            double[] op = projection(obstacleVertices, normal);
            double[] constants = {op[1] + gap - cp[0], op[0] - gap - cp[1]};
            Coord2d tangent = Coord2d.of(-normal.y, normal.x);
            for(double constant : constants) {
                addLineBoundaryCandidates(out, normal, constant, lowX, highX, lowY, highY);
                for(Coord2d ov : obstacleVertices) for(Coord2d cv : candidateVertices) {
                    double along = dot(ov, tangent) - dot(cv, tangent);
                    addCandidate(out, Coord2d.of(normal.x * constant + tangent.x * along,
                        normal.y * constant + tangent.y * along));
                }
            }
        }
    }

    private static void addLineBoundaryCandidates(Map<String, Coord2d> out, Coord2d normal,
                                                   double constant, double lowX, double highX,
                                                   double lowY, double highY) {
        if(Math.abs(normal.y) > 1e-9) {
            addCandidate(out, Coord2d.of(lowX, (constant - normal.x * lowX) / normal.y));
            addCandidate(out, Coord2d.of(highX, (constant - normal.x * highX) / normal.y));
        }
        if(Math.abs(normal.x) > 1e-9) {
            addCandidate(out, Coord2d.of((constant - normal.y * lowY) / normal.x, lowY));
            addCandidate(out, Coord2d.of((constant - normal.y * highY) / normal.x, highY));
        }
    }

    private static void addCandidate(Map<String, Coord2d> out, Coord2d point) {
        if(point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) return;
        String key = Math.round(point.x * 1000000.0) + ":" + Math.round(point.y * 1000000.0);
        out.putIfAbsent(key, point);
    }

    private static List<Coord2d> vertices(Collection<Coord2d[]> polygons) {
        List<Coord2d> out = new ArrayList<>();
        if(polygons != null) for(Coord2d[] polygon : polygons) for(Coord2d point : polygon)
            if(point != null) out.add(point);
        return out;
    }

    private static void addAxes(List<Coord2d> axes, Collection<Coord2d[]> polygons) {
        if(polygons == null) return;
        for(Coord2d[] polygon : polygons) {
            if(polygon == null || polygon.length < 2) continue;
            for(int i = 0; i < polygon.length; i++) {
                Coord2d a = polygon[i], b = polygon[(i + 1) % polygon.length];
                double ex = b.x - a.x, ey = b.y - a.y;
                double length = Math.hypot(ex, ey);
                if(length <= 1e-9) continue;
                Coord2d normal = Coord2d.of(-ey / length, ex / length);
                boolean duplicate = false;
                for(Coord2d old : axes) if(Math.abs(Math.abs(dot(old, normal)) - 1.0) < 1e-7) {
                    duplicate = true;
                    break;
                }
                if(!duplicate) axes.add(normal);
            }
        }
    }

    private static boolean polygonConflicts(Coord2d[] a, Coord2d[] b, double gap) {
        List<Coord2d[]> pair = new ArrayList<>();
        pair.add(a); pair.add(b);
        List<Coord2d> axes = new ArrayList<>();
        addAxes(axes, pair);
        if(axes.isEmpty()) return false;
        List<Coord2d> av = new ArrayList<>(), bv = new ArrayList<>();
        for(Coord2d p : a) av.add(p);
        for(Coord2d p : b) bv.add(p);
        for(Coord2d axis : axes) {
            double[] ap = projection(av, axis), bp = projection(bv, axis);
            if(ap[1] + gap <= bp[0] + 1e-7 || bp[1] + gap <= ap[0] + 1e-7) return false;
        }
        return true;
    }

    private static double[] projection(Collection<Coord2d> points, Coord2d axis) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for(Coord2d point : points) {
            double value = dot(point, axis);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new double[]{min, max};
    }

    private static double dot(Coord2d a, Coord2d b) {
        return a.x * b.x + a.y * b.y;
    }
}
