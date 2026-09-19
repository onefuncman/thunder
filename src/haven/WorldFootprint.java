package haven;

import haven.pathfinding.NurglingFallbacks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reusable exact obstacle/negative-layer geometry formerly private to MapView's placer snap. */
public final class WorldFootprint {
    /** Server-rejection-safe separation also used by manual magnetic placement. */
    public static final double DEFAULT_ABUT_GAP = 0.1;

    private WorldFootprint() {}

    public static final class Bounds {
        public final double minX, minY, maxX, maxY;
        public Bounds(double minX, double minY, double maxX, double maxY) {
            this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY;
        }
        public double width() {return maxX - minX;}
        public double height() {return maxY - minY;}
        public double[] array() {return new double[]{minX, minY, maxX, maxY};}
    }

    public static Resource meshResource(Gob gob) {
        Resource res;
        try {res = gob == null ? null : gob.getres();} catch(Loading e) {return null;}
        if(res == null) return null;
        Resource resolved = meshResource(gob, res);
        if(pointCount(resolved) > 0) return resolved;
        if(gob instanceof MapView.Plob && ((MapView.Plob)gob).drawable instanceof ResDrawable) {
            Sprite spr = ((ResDrawable)((MapView.Plob)gob).drawable).spr;
            Gob source = embeddedGob(spr);
            if(source != null && source != gob) {
                Resource sourceRes = meshResource(source);
                if(pointCount(sourceRes) > 0) return sourceRes;
            }
        }
        List<Gob.Overlay> overlays;
        synchronized(gob.ols) {overlays = new ArrayList<>(gob.ols);}
        for(Gob.Overlay overlay : overlays) {
            Resource overlayRes = overlayResource(overlay);
            Resource overlayMesh = overlayRes == null ? null : meshResource(gob, overlayRes);
            if(pointCount(overlayMesh) > 0) return overlayMesh;
        }
        return resolved;
    }

    public static Resource meshResource(Gob gob, Resource res) {
        Resource fixed = Hitbox.fix(gob, res);
        if(fixed == null) return null;
        Collection<RenderLink.Res> links = fixed.layers(RenderLink.Res.class);
        if(links != null) for(RenderLink.Res link : links) {
            if(link.l instanceof RenderLink.MeshMat) {
                try {return ((RenderLink.MeshMat)link.l).mesh.get();}
                catch(Loading e) {return null;}
            }
        }
        return fixed;
    }

    public static Bounds bounds(Gob gob) {
        if(gob != null && gob.rc != null) {
            try {
                Coord2d[] catalog = NurglingFallbacks.polygon(gob.resid(), gob.rc, gob.a);
                if(catalog != null) {
                    List<Coord2d[]> polygons = new ArrayList<>();
                    polygons.add(catalog);
                    return bounds(polygons);
                }
            } catch(Loading ignored) {}
        }
        Resource res = meshResource(gob);
        return res == null ? null : bounds(gob, res);
    }

    public static Bounds bounds(Gob gob, Resource res) {
        return bounds(transformedPolygons(gob, res));
    }

    /** Collision/negative-layer polygons transformed into live world coordinates. */
    public static List<Coord2d[]> transformedPolygons(Gob gob) {
        if(gob != null && gob.rc != null) {
            try {
                Coord2d[] catalog = NurglingFallbacks.polygon(gob.resid(), gob.rc, gob.a);
                if(catalog != null) {
                    List<Coord2d[]> polygons = new ArrayList<>();
                    polygons.add(catalog);
                    return polygons;
                }
            } catch(Loading ignored) {}
        }
        Resource res = meshResource(gob);
        return res == null ? new ArrayList<>() : transformedPolygons(gob, res);
    }

    public static List<Coord2d[]> transformedPolygons(Gob gob, Resource res) {
        List<Coord2d[]> out = new ArrayList<>();
        if(gob == null || gob.rc == null) return out;
        double cs = Math.cos(gob.a), sn = Math.sin(gob.a);
        for(Coord2d[] polygon : localPolygons(res)) {
            Coord2d[] transformed = new Coord2d[polygon.length];
            for(int i = 0; i < polygon.length; i++) {
                Coord2d p = polygon[i];
                transformed[i] = Coord2d.of(gob.rc.x + p.x * cs - p.y * sn,
                    gob.rc.y + p.x * sn + p.y * cs);
            }
            out.add(transformed);
        }
        return out;
    }

    public static Bounds bounds(Collection<Coord2d[]> polygons) {
        if(polygons == null || polygons.isEmpty()) return null;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for(Coord2d[] polygon : polygons) for(Coord2d p : polygon) {
            if(p == null) continue;
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
            any = true;
        }
        return any ? new Bounds(minX, minY, maxX, maxY) : null;
    }

    public static int pointCount(Resource res) {
        if(res == null) return -1;
        int count = 0;
        Collection<Resource.Obstacle> obstacles = res.layers(Resource.Obstacle.class);
        if(obstacles != null) for(Resource.Obstacle obstacle : obstacles)
            for(Coord2d[] poly : obstacle.p) count += poly.length;
        Collection<Resource.Neg> negatives = res.layers(Resource.Neg.class);
        if(negatives != null) count += negatives.size() * 4;
        return count;
    }

    public static Coord2d[] localPoints(Resource res) {
        List<Coord2d> points = new ArrayList<>();
        for(Coord2d[] polygon : localPolygons(res)) for(Coord2d point : polygon) points.add(point);
        return points.toArray(new Coord2d[0]);
    }

    /** Preserves the individual collision polygons instead of flattening them into one point cloud. */
    public static List<Coord2d[]> localPolygons(Resource res) {
        List<Coord2d[]> polygons = new ArrayList<>();
        if(res != null) {
            Collection<Resource.Obstacle> obstacles = res.layers(Resource.Obstacle.class);
            if(obstacles != null) for(Resource.Obstacle obstacle : obstacles) for(Coord2d[] source : obstacle.p) {
                Coord2d[] polygon = new Coord2d[source.length];
                for(int i = 0; i < source.length; i++) polygon[i] = Coord2d.of(source[i].x, -source[i].y);
                polygons.add(polygon);
            }
            Collection<Resource.Neg> negatives = res.layers(Resource.Neg.class);
            if(negatives != null) for(Resource.Neg neg : negatives) polygons.add(new Coord2d[]{
                Coord2d.of(neg.ac.x, -neg.ac.y), Coord2d.of(neg.bc.x, -neg.ac.y),
                Coord2d.of(neg.bc.x, -neg.bc.y), Coord2d.of(neg.ac.x, -neg.bc.y)
            });
        }
        if(polygons.isEmpty()) {
            double radius = MCache.tilesz.x * 0.1;
            polygons.add(new Coord2d[]{Coord2d.of(-radius, -radius), Coord2d.of(radius, -radius),
                Coord2d.of(radius, radius), Coord2d.of(-radius, radius)});
        }
        return polygons;
    }

    /**
     * Exact world-AABB abutment used by manual placement and automated drops.
     * {@code nearSide} means slide toward the target and stop at its facing edge;
     * {@code alignInside} aligns the same-side edges inside a tile-sized target.
     */
    public static Coord2d abutTranslation(Bounds placer, Bounds target, int axisX, int axisY,
                                           double gap, boolean alignInside, boolean nearSide) {
        if(placer == null || target == null || (axisX == 0 && axisY == 0)) return null;
        double dx = 0, dy = 0;
        if(alignInside) {
            if(axisX < 0) dx = target.minX - placer.minX;
            else if(axisX > 0) dx = target.maxX - placer.maxX;
            else if(axisY < 0) dy = target.minY - placer.minY;
            else dy = target.maxY - placer.maxY;
        } else if(nearSide) {
            if(axisX < 0) dx = (target.maxX + gap) - placer.minX;
            else if(axisX > 0) dx = (target.minX - gap) - placer.maxX;
            else if(axisY < 0) dy = (target.maxY + gap) - placer.minY;
            else dy = (target.minY - gap) - placer.maxY;
        } else {
            if(axisX < 0) dx = (target.minX - gap) - placer.maxX;
            else if(axisX > 0) dx = (target.maxX + gap) - placer.minX;
            else if(axisY < 0) dy = (target.minY - gap) - placer.maxY;
            else dy = (target.maxY + gap) - placer.minY;
        }
        return Coord2d.of(dx, dy);
    }

    /**
     * Returns continuous (not tile-snapped) legal anchors, farthest from the
     * entrance first. Candidate shelves abut area boundaries and observed object
     * bounds using the requested server-safe gap.
     */
    public static List<Coord2d> legalSubTileDrops(Area tiles, Coord2d tileSize, Bounds relative,
                                                   Collection<Bounds> obstacles, Coord2d entrance,
                                                   double gap) {
        List<Coord2d> legal = new ArrayList<>();
        if(tiles == null || tileSize == null || relative == null || !tiles.positive()) return legal;
        Bounds area = new Bounds(tiles.ul.x * tileSize.x, tiles.ul.y * tileSize.y,
            tiles.br.x * tileSize.x, tiles.br.y * tileSize.y);
        if(relative.width() <= 0 || relative.height() <= 0 ||
           relative.width() > area.width() || relative.height() > area.height()) return legal;

        // The selected-area border is not a physical obstacle. Keep the full
        // footprint contained, but apply the requested gap only to real objects.
        // This matters for a 22-unit log in an exactly two-tile-wide area.
        double lowX = area.minX - relative.minX;
        double highX = area.maxX - relative.maxX;
        double lowY = area.minY - relative.minY;
        double highY = area.maxY - relative.maxY;
        if(lowX > highX || lowY > highY) return legal;

        Set<Double> xs = new LinkedHashSet<>(), ys = new LinkedHashSet<>();
        addShelves(xs, lowX, highX, relative.width() + gap);
        addShelves(ys, lowY, highY, relative.height() + gap);
        if(obstacles != null) for(Bounds obstacle : obstacles) {
            xs.add(obstacle.minX - gap - relative.maxX); xs.add(obstacle.maxX + gap - relative.minX);
            ys.add(obstacle.minY - gap - relative.maxY); ys.add(obstacle.maxY + gap - relative.minY);
        }
        for(Double x : xs) for(Double y : ys) {
            Bounds placed = move(relative, x, y);
            if(!inside(placed, area)) continue;
            boolean blocked = false;
            if(obstacles != null) for(Bounds obstacle : obstacles) if(conflicts(placed, obstacle, gap)) {
                blocked = true; break;
            }
            if(!blocked) legal.add(Coord2d.of(x, y));
        }
        Coord2d rankFrom = entrance == null ? Coord2d.of(area.minX, area.minY) : entrance;
        legal.sort(Comparator.<Coord2d>comparingDouble(rankFrom::dist).reversed()
            .thenComparingDouble(c -> -c.y).thenComparingDouble(c -> -c.x));
        return legal;
    }

    private static Bounds move(Bounds b, double x, double y) {
        return new Bounds(b.minX + x, b.minY + y, b.maxX + x, b.maxY + y);
    }

    private static boolean inside(Bounds a, Bounds b) {
        return a.minX >= b.minX && a.minY >= b.minY && a.maxX <= b.maxX && a.maxY <= b.maxY;
    }

    private static boolean conflicts(Bounds a, Bounds b, double gap) {
        return !(a.maxX + gap <= b.minX || b.maxX + gap <= a.minX ||
                 a.maxY + gap <= b.minY || b.maxY + gap <= a.minY);
    }

    private static void addShelves(Set<Double> out, double low, double high, double pitch) {
        out.add(low); out.add(high);
        if(!(pitch > 0)) return;
        for(double v = low; v <= high + 0.0001; v += pitch) out.add(Math.min(v, high));
        for(double v = high; v >= low - 0.0001; v -= pitch) out.add(Math.max(v, low));
    }

    private static Gob embeddedGob(Sprite sprite) {
        if(sprite == null) return null;
        for(Class<?> type = sprite.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for(java.lang.reflect.Field field : type.getDeclaredFields()) {
                if(java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !Gob.class.isAssignableFrom(field.getType())) continue;
                try {field.setAccessible(true); Object value = field.get(sprite); if(value instanceof Gob) return (Gob)value;}
                catch(Throwable ignored) {}
            }
        }
        return null;
    }

    private static Resource overlayResource(Gob.Overlay overlay) {
        if(overlay.sm instanceof Sprite.Mill.FromRes) {
            try {return ((Sprite.Mill.FromRes)overlay.sm).res.get();} catch(Loading e) {return null;}
        }
        return overlay.spr == null ? null : overlay.spr.res;
    }
}
