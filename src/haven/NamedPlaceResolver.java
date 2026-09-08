package haven;

import haven.MapFile.Marker;
import haven.MapFile.PMarker;
import haven.MapFile.SMarker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import me.ender.minimap.CustomMarker;

public final class NamedPlaceResolver {
   public final MapFile file;

   public NamedPlaceResolver(MapFile file) {
      this.file = Objects.requireNonNull(file, "file");
   }

   public haven.NamedPlaceResolver.Result resolve(String name) {
      this.file.lock.readLock().lock();

      haven.NamedPlaceResolver.Result var11;
      try {
         long seq = (long)this.file.markerseq;
         if (name == null || name.isEmpty()) {
            return haven.NamedPlaceResolver.Result.missing(seq);
         }

         List<haven.NamedPlaceResolver.Place> matches = null;

         for (Marker m : this.file.markers) {
            if (m != null && m.nm != null && m.nm.equals(name)) {
               if (matches == null) {
                  matches = new ArrayList<>(2);
               }

               matches.add(new haven.NamedPlaceResolver.Place(m.nm, m.seg, new Coord(m.tc), typeOf(m), this.file.knownsegs.contains(m.seg)));
            }
         }

         if (matches == null) {
            return haven.NamedPlaceResolver.Result.missing(seq);
         }

         if (matches.size() != 1) {
            return haven.NamedPlaceResolver.Result.ambiguous(matches, seq);
         }

         var11 = haven.NamedPlaceResolver.Result.found(matches.get(0), seq);
      } finally {
         this.file.lock.readLock().unlock();
      }

      return var11;
   }

   private static haven.NamedPlaceResolver.MarkerType typeOf(Marker m) {
      if (m instanceof CustomMarker) {
         return haven.NamedPlaceResolver.MarkerType.CUSTOM;
      } else if (m instanceof PMarker) {
         return haven.NamedPlaceResolver.MarkerType.PLACED;
      } else {
         return m instanceof SMarker ? haven.NamedPlaceResolver.MarkerType.SERVER : haven.NamedPlaceResolver.MarkerType.OTHER;
      }
   }

   public static enum Kind {
      FOUND,
      MISSING,
      AMBIGUOUS;
   }

   public static enum MarkerType {
      PLACED,
      SERVER,
      CUSTOM,
      OTHER;
   }

   public static final class Place {
      public final String name;
      public final long seg;
      public final Coord tc;
      public final haven.NamedPlaceResolver.MarkerType type;
      public final boolean knownSegment;

      Place(String name, long seg, Coord tc, haven.NamedPlaceResolver.MarkerType type, boolean knownSegment) {
         this.name = name;
         this.seg = seg;
         this.tc = tc;
         this.type = type;
         this.knownSegment = knownSegment;
      }

      public Coord2d world() {
         return this.tc.mul(MCache.tilesz);
      }

      @Override
      public String toString() {
         return String.format("Place[%s, seg=%x, tc=%s, type=%s, knownSegment=%s]", this.name, this.seg, this.tc, this.type, this.knownSegment);
      }
   }

   public static final class Result {
      public final haven.NamedPlaceResolver.Kind kind;
      public final long markerseq;
      public final haven.NamedPlaceResolver.Place place;
      public final List<haven.NamedPlaceResolver.Place> candidates;

      private Result(haven.NamedPlaceResolver.Kind kind, long markerseq, haven.NamedPlaceResolver.Place place, List<haven.NamedPlaceResolver.Place> candidates) {
         this.kind = kind;
         this.markerseq = markerseq;
         this.place = place;
         this.candidates = candidates;
      }

      static haven.NamedPlaceResolver.Result found(haven.NamedPlaceResolver.Place place, long markerseq) {
         return new haven.NamedPlaceResolver.Result(haven.NamedPlaceResolver.Kind.FOUND, markerseq, place, null);
      }

      static haven.NamedPlaceResolver.Result missing(long markerseq) {
         return new haven.NamedPlaceResolver.Result(haven.NamedPlaceResolver.Kind.MISSING, markerseq, null, null);
      }

      static haven.NamedPlaceResolver.Result ambiguous(List<haven.NamedPlaceResolver.Place> candidates, long markerseq) {
         return new haven.NamedPlaceResolver.Result(
            haven.NamedPlaceResolver.Kind.AMBIGUOUS, markerseq, null, Collections.unmodifiableList(new ArrayList<>(candidates))
         );
      }

      public boolean isFound() {
         return this.kind == haven.NamedPlaceResolver.Kind.FOUND;
      }

      public boolean isMissing() {
         return this.kind == haven.NamedPlaceResolver.Kind.MISSING;
      }

      public boolean isAmbiguous() {
         return this.kind == haven.NamedPlaceResolver.Kind.AMBIGUOUS;
      }

      @Override
      public String toString() {
         if (this.kind == haven.NamedPlaceResolver.Kind.FOUND) {
            return String.format("Result[%s, seq=%d, %s]", this.kind, this.markerseq, this.place);
         } else {
            return this.kind == haven.NamedPlaceResolver.Kind.AMBIGUOUS
               ? String.format("Result[%s, seq=%d, %d candidates]", this.kind, this.markerseq, this.candidates.size())
               : String.format("Result[%s, seq=%d]", this.kind, this.markerseq);
         }
      }
   }
}
