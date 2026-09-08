package haven.pathfinding;

import haven.Area;
import haven.Coord;
import haven.MapFile;
import haven.NamedPlaceResolver;
import haven.NamedPlaceResolver.Place;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class NamedPlaceRouteService {
   private final MapFile file;
   private final NamedPlaceResolver resolver;

   public NamedPlaceRouteService(MapFile file) {
      this.file = Objects.requireNonNull(file, "file");
      this.resolver = new NamedPlaceResolver(file);
   }

   public MapFile file() {
      return this.file;
   }

   public NamedPlaceRouteService.Result route(long startSeg, Coord startTile, String destName, Area bounds, int maxExpanded) {
      return this.route(new NamedPlaceRouteService.Request(startSeg, startTile, destName, bounds, maxExpanded));
   }

   public NamedPlaceRouteService.Result route(NamedPlaceRouteService.Request req) {
      Objects.requireNonNull(req, "req");
      haven.NamedPlaceResolver.Result rr = this.resolver.resolve(req.destName);
      if (rr.isMissing()) {
         return new NamedPlaceRouteService.Result(NamedPlaceRouteService.Kind.DEST_MISSING, rr, Collections.emptyList(), null, 0);
      } else if (rr.isAmbiguous()) {
         return new NamedPlaceRouteService.Result(NamedPlaceRouteService.Kind.DEST_AMBIGUOUS, rr, Collections.emptyList(), null, 0);
      } else {
         Place place = rr.place;
         if (place.seg != req.startSeg) {
            return new NamedPlaceRouteService.Result(NamedPlaceRouteService.Kind.CROSS_SEGMENT, rr, Collections.emptyList(), null, 0);
         } else {
            MapFileTileSource src = MapFileTileSource.of(this.file, req.startSeg, req.bounds);
            Coord start = req.startTile.sub(req.bounds.ul);
            Coord goal = place.tc.sub(req.bounds.ul);
            CoarseRoutePlanner.Route pr = CoarseRoutePlanner.plan(src, start, goal, req.maxExpanded);
            NamedPlaceRouteService.Kind kind = fromPlanner(pr.status);
            List<Coord> waypoints = kind == NamedPlaceRouteService.Kind.REACHED ? toAbsolute(pr.waypoints, req.bounds.ul) : Collections.emptyList();
            return new NamedPlaceRouteService.Result(kind, rr, waypoints, pr.cause, pr.expanded);
         }
      }
   }

   private static NamedPlaceRouteService.Kind fromPlanner(CoarseRoutePlanner.Status st) {
      switch (st) {
         case REACHED:
            return NamedPlaceRouteService.Kind.REACHED;
         case NO_KNOWN_ROUTE:
            return NamedPlaceRouteService.Kind.NO_KNOWN_ROUTE;
         case INVALID_START:
            return NamedPlaceRouteService.Kind.INVALID_START;
         case INVALID_GOAL:
            return NamedPlaceRouteService.Kind.INVALID_GOAL;
         case EXHAUSTED:
            return NamedPlaceRouteService.Kind.EXHAUSTED;
         default:
            throw new AssertionError(st);
      }
   }

   private static List<Coord> toAbsolute(List<Coord> local, Coord ul) {
      List<Coord> out = new ArrayList<>(local.size());

      for (Coord c : local) {
         out.add(c.add(ul));
      }

      return Collections.unmodifiableList(out);
   }

   public static enum Kind {
      REACHED,
      NO_KNOWN_ROUTE,
      INVALID_START,
      INVALID_GOAL,
      EXHAUSTED,
      DEST_MISSING,
      DEST_AMBIGUOUS,
      CROSS_SEGMENT;
   }

   public static final class Request {
      public final long startSeg;
      public final Coord startTile;
      public final String destName;
      public final Area bounds;
      public final int maxExpanded;

      public Request(long startSeg, Coord startTile, String destName, Area bounds, int maxExpanded) {
         this.startSeg = startSeg;
         this.startTile = Objects.requireNonNull(startTile, "startTile");
         this.destName = destName;
         this.bounds = Objects.requireNonNull(bounds, "bounds");
         this.maxExpanded = maxExpanded;
      }

      @Override
      public String toString() {
         return String.format(
            "Request[seg=%x, start=%s, dest=\"%s\", bounds=%s..%s, maxExpanded=%d]",
            this.startSeg,
            this.startTile,
            this.destName,
            this.bounds.ul,
            this.bounds.br,
            this.maxExpanded
         );
      }
   }

   public static final class Result {
      public final NamedPlaceRouteService.Kind kind;
      public final long markerseq;
      public final Place place;
      public final List<Place> candidates;
      public final List<Coord> waypoints;
      public final CoarseRoutePlanner.Cause cause;
      public final int expanded;

      private Result(NamedPlaceRouteService.Kind kind, haven.NamedPlaceResolver.Result rr, List<Coord> waypoints, CoarseRoutePlanner.Cause cause, int expanded) {
         this.kind = kind;
         this.markerseq = rr.markerseq;
         this.place = rr.place;
         this.candidates = rr.candidates;
         this.waypoints = waypoints;
         this.cause = cause;
         this.expanded = expanded;
      }

      public boolean reached() {
         return this.kind == NamedPlaceRouteService.Kind.REACHED;
      }

      public boolean noKnownRoute() {
         return this.kind == NamedPlaceRouteService.Kind.NO_KNOWN_ROUTE;
      }

      public boolean exhausted() {
         return this.kind == NamedPlaceRouteService.Kind.EXHAUSTED;
      }

      public boolean destinationMissing() {
         return this.kind == NamedPlaceRouteService.Kind.DEST_MISSING;
      }

      public boolean destinationAmbiguous() {
         return this.kind == NamedPlaceRouteService.Kind.DEST_AMBIGUOUS;
      }

      public boolean crossSegment() {
         return this.kind == NamedPlaceRouteService.Kind.CROSS_SEGMENT;
      }

      @Override
      public String toString() {
         return this.kind == NamedPlaceRouteService.Kind.DEST_AMBIGUOUS
            ? String.format("Result[%s, seq=%d, %d candidates]", this.kind, this.markerseq, this.candidates.size())
            : String.format(
               "Result[%s, seq=%d, place=%s, %d waypoints, %d expanded]", this.kind, this.markerseq, this.place, this.waypoints.size(), this.expanded
            );
      }
   }
}
