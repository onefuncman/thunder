package haven.pathfinding;

import haven.Coord2d;
import haven.Gob;
import haven.Hitbox;
import haven.Loading;
import haven.OCache;
import haven.RenderLink;
import haven.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves physical placement geometry without reusing navigation padding. */
public final class PlacementGeometry {
    private PlacementGeometry() {}

    /**
     * Maximum observed positional error after a placement coordinate is
     * encoded for the click and the grounded Gob coordinate is decoded again.
     * The round trip can differ by two protocol quanta (seen in clear-cut log
     * 1019578332), even when the requested anchor is exactly on the area edge.
     */
    public static final double SERVER_POSITION_TOLERANCE = 2.0 * Math.max(OCache.posres.x, OCache.posres.y);

    public static final class Resolved {
        public final ExactPlacementPlanner.Shape shape;
        public final String source;

        Resolved(ExactPlacementPlanner.Shape shape, String source) {
            this.shape = shape;
            this.source = source == null ? "" : source;
        }
    }

    /** Resolves a resource's model/Neg placement shape before any movement-catalog fallback. */
    public static Resolved resolve(String resource, double angle) {
        ObjectSpatialProfile profile = ObjectSpatialProfiles.resolve(resource);
        if (ObjectSpatialProfiles.ordinaryTreeLog(resource) && profile.placementHalf != null)
            return new Resolved(rectangle(Coord2d.z, profile.placementHalf, angle), profile.placementSource);
        List<Coord2d[]> local = resourcePlacementPolygons(resource);
        if (!local.isEmpty())
            return new Resolved(new ExactPlacementPlanner.Shape(rotateLocal(local, angle)), "resource-neg");
        if (profile.placementHalf != null)
            return new Resolved(rectangle(Coord2d.z, profile.placementHalf, angle), profile.placementSource);
        return new Resolved(null, "unavailable");
    }

    public static ExactPlacementPlanner.Shape relative(String resource, double angle) {
        return resolve(resource, angle).shape;
    }

    public static ExactPlacementPlanner.Shape relative(Gob gob, double angle) {
        if (gob == null || gob.rc == null) return null;
        String resource = resource(gob);
        ObjectSpatialProfile profile = ObjectSpatialProfiles.resolve(resource);
        if (ObjectSpatialProfiles.ordinaryTreeLog(resource) && profile.placementHalf != null) {
            return rectangle(Coord2d.z, profile.placementHalf, angle);
        }
        List<Coord2d[]> world;
        try {
            world = Hitbox.placementPolygons(gob);
        } catch (Loading loading) {
            return null;
        }
        if (world != null && !world.isEmpty())
            return new ExactPlacementPlanner.Shape(reorient(world, gob.rc, gob.a, angle));
        return profile.placementHalf == null ? null
            : rectangle(Coord2d.z, profile.placementHalf, angle);
    }

    public static ExactPlacementPlanner.Shape world(Gob gob) {
        if (gob == null || gob.rc == null) return null;
        String resource = resource(gob);
        ObjectSpatialProfile profile = ObjectSpatialProfiles.resolve(resource);
        if (ObjectSpatialProfiles.ordinaryTreeLog(resource) && profile.placementHalf != null) {
            return rectangle(gob.rc, profile.placementHalf, gob.a);
        }
        try {
            List<Coord2d[]> polygons = Hitbox.placementPolygons(gob);
            if (polygons != null && !polygons.isEmpty()) return new ExactPlacementPlanner.Shape(polygons);
        } catch (Loading loading) {
        }
        return profile.placementHalf == null ? null
            : rectangle(gob.rc, profile.placementHalf, gob.a);
    }

    /** Conservative grounded-obstacle geometry when no physical placement layer is readable. */
    public static ExactPlacementPlanner.Shape worldObstacle(Gob gob) {
        ExactPlacementPlanner.Shape physical = world(gob);
        if (physical != null) return physical;
        if (gob == null || gob.rc == null) return null;
        ObjectSpatialProfile profile = ObjectSpatialProfiles.resolve(resource(gob));
        if (profile.navigationHalf != null)
            return rectangle(gob.rc, profile.navigationHalf, gob.a);
        try {
            List<Coord2d[]> movement = Hitbox.movementPolygons(gob);
            return movement == null || movement.isEmpty() ? null : new ExactPlacementPlanner.Shape(movement);
        } catch (Loading loading) {
            return null;
        }
    }

    public static ExactPlacementPlanner.Shape rectangle(Coord2d anchor, Coord2d half, double angle) {
        if (anchor == null || half == null || !(half.x > 0.0) || !(half.y > 0.0)) return null;
        double cs = Math.cos(angle), sn = Math.sin(angle);
        Coord2d[] local = new Coord2d[]{
            Coord2d.of(-half.x, -half.y), Coord2d.of(half.x, -half.y),
            Coord2d.of(half.x, half.y), Coord2d.of(-half.x, half.y)
        };
        Coord2d[] world = new Coord2d[local.length];
        for (int i = 0; i < local.length; i++) {
            Coord2d p = local[i];
            world[i] = Coord2d.of(anchor.x + p.x * cs - p.y * sn,
                                  anchor.y + p.x * sn + p.y * cs);
        }
        return new ExactPlacementPlanner.Shape(Collections.singletonList(world));
    }

    /** Local physical placement polygons from resource Neg layers, including linked models. */
    static List<Coord2d[]> resourcePlacementPolygons(String resource) {
        List<Coord2d[]> out = new ArrayList<Coord2d[]>();
        if (resource == null || resource.isEmpty()) return out;
        try {
            Resource root = Resource.remote().loadwait(MovementScene.baseResid(resource));
            Set<Resource> resources = new LinkedHashSet<Resource>();
            collectResources(root, resources);
            for (Resource res : resources) {
                Collection<Resource.Neg> negs = res.layers(Resource.Neg.class);
                if (negs == null) continue;
                for (Resource.Neg neg : negs) out.add(new Coord2d[]{
                    Coord2d.of(neg.ac.x, -neg.ac.y),
                    Coord2d.of(neg.bc.x, -neg.ac.y),
                    Coord2d.of(neg.bc.x, -neg.bc.y),
                    Coord2d.of(neg.ac.x, -neg.bc.y)
                });
            }
        } catch (Loading loading) {
        } catch (RuntimeException unavailable) {
        }
        return out;
    }

    private static void collectResources(Resource resource, Set<Resource> out) {
        if (resource == null || !out.add(resource)) return;
        Collection<RenderLink.Res> links = resource.layers(RenderLink.Res.class);
        if (links == null) return;
        for (RenderLink.Res link : links) {
            try {
                if (link.l instanceof RenderLink.MeshMat)
                    collectResources(((RenderLink.MeshMat)link.l).mesh.get(), out);
                else if (link.l instanceof RenderLink.Collect)
                    collectResources(((RenderLink.Collect)link.l).from.get(), out);
                else if (link.l instanceof RenderLink.ResSprite)
                    collectResources(((RenderLink.ResSprite)link.l).res.get(), out);
            } catch (Loading loading) {
            }
        }
    }

    private static List<Coord2d[]> rotateLocal(List<Coord2d[]> local, double angle) {
        List<Coord2d[]> out = new ArrayList<Coord2d[]>();
        double cs = Math.cos(angle), sn = Math.sin(angle);
        for (Coord2d[] polygon : local) {
            if (polygon == null || polygon.length == 0) continue;
            Coord2d[] rotated = new Coord2d[polygon.length];
            for (int i = 0; i < polygon.length; i++) {
                Coord2d p = polygon[i];
                rotated[i] = Coord2d.of(p.x * cs - p.y * sn, p.x * sn + p.y * cs);
            }
            out.add(rotated);
        }
        return out;
    }

    private static List<Coord2d[]> reorient(List<Coord2d[]> world, Coord2d anchor,
                                             double oldAngle, double newAngle) {
        List<Coord2d[]> out = new ArrayList<Coord2d[]>();
        double oldCs = Math.cos(-oldAngle), oldSn = Math.sin(-oldAngle);
        double newCs = Math.cos(newAngle), newSn = Math.sin(newAngle);
        for (Coord2d[] polygon : world) {
            if (polygon == null || polygon.length == 0) continue;
            Coord2d[] transformed = new Coord2d[polygon.length];
            for (int i = 0; i < polygon.length; i++) {
                Coord2d p = polygon[i].sub(anchor);
                double lx = p.x * oldCs - p.y * oldSn;
                double ly = p.x * oldSn + p.y * oldCs;
                transformed[i] = Coord2d.of(lx * newCs - ly * newSn,
                                            lx * newSn + ly * newCs);
            }
            out.add(transformed);
        }
        return out;
    }

    private static String resource(Gob gob) {
        try {
            return gob.resid();
        } catch (Loading loading) {
            return null;
        }
    }
}
