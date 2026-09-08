package haven;

import haven.Config.Variable;
import haven.MapFile.Marker;
import haven.MapFile.PMarker;
import haven.MapFile.SMarker;
import haven.NamedPlaceResolver.Kind;
import haven.NamedPlaceResolver.MarkerType;
import haven.NamedPlaceResolver.Place;
import haven.NamedPlaceResolver.Result;
import haven.Resource.Saved;
import haven.Resource.Spec;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.List;
import me.ender.minimap.CustomMarker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class NamedPlaceResolverTest {
   private static final long SEG_A = -6148914691236517206L;
   private static final long SEG_B = -4919131752989213765L;

   private static MapFile newFile() {
      return new MapFile(new haven.NamedPlaceResolverTest.MemCache(), "test");
   }

   private static <M extends Marker> M add(MapFile f, M m) {
      f.lock.writeLock().lock();

      try {
         f.markers.add(m);
         f.markerseq++;
      } finally {
         f.lock.writeLock().unlock();
      }

      return m;
   }

   private static void knownSegs(MapFile f, long... segs) {
      f.lock.writeLock().lock();

      try {
         for (long seg : segs) {
            f.knownsegs.add(seg);
         }
      } finally {
         f.lock.writeLock().unlock();
      }
   }

   private static PMarker pm(MapFile f, long seg, int x, int y, String nm) {
      return new PMarker(f, seg, new Coord(x, y), nm, Color.RED, false);
   }

   private static SMarker sm(MapFile f, long seg, int x, int y, String nm) {
      return new SMarker(f, seg, new Coord(x, y), nm, UID.of(1L), new Saved(Resource.remote(), "gfx/terobjs/quest", 1), new byte[0]);
   }

   private static CustomMarker cm(MapFile f, long seg, int x, int y, String nm) {
      return new CustomMarker(f, seg, new Coord(x, y), nm, Color.BLUE, new Spec(Resource.remote(), "gfx/terobjs/custom/foo", 1));
   }

   private static Marker om(MapFile f, long seg, int x, int y, String nm) {
      return new Marker(f, seg, new Coord(x, y), nm) {
         public void draw(GOut g, Coord c, Text tip, float scale, MapFile file) {
         }

         public Area area() {
            return null;
         }
      };
   }

   @BeforeAll
   static void setupResdir() throws Exception {
      System.setProperty("haven.resdir", fixtureResdir().getAbsolutePath());
      Field inited = Variable.class.getDeclaredField("inited");
      inited.setAccessible(true);
      Field resdir = Resource.class.getDeclaredField("resdir");
      resdir.setAccessible(true);
      inited.setBoolean(resdir.get(null), false);
      Field local = Resource.class.getDeclaredField("_local");
      local.setAccessible(true);
      local.set(null, null);
      Field remote = Resource.class.getDeclaredField("_remote");
      remote.setAccessible(true);
      remote.set(null, null);
   }

   @AfterAll
   static void teardownResdir() {
      System.clearProperty("haven.resdir");
   }

   private static File fixtureResdir() {
      File f = new File("src/test/resources/fixtures/resdir");
      Assertions.assertTrue(f.isDirectory(), "missing fixture dir (run from repo root): " + f.getAbsolutePath());
      return f;
   }

   @Test
   void uniqueExactLookup_foundWithAbsoluteCoordinates() {
      MapFile f = newFile();
      knownSegs(f, -6148914691236517206L);
      add(f, pm(f, -6148914691236517206L, 5, 7, "Home"));
      Result r = new NamedPlaceResolver(f).resolve("Home");
      Assertions.assertTrue(r.isFound());
      Assertions.assertEquals(Kind.FOUND, r.kind);
      Place p = r.place;
      Assertions.assertNotNull(p);
      Assertions.assertEquals("Home", p.name);
      Assertions.assertEquals(-6148914691236517206L, p.seg);
      Assertions.assertEquals(new Coord(5, 7), p.tc);
      Assertions.assertEquals(MarkerType.PLACED, p.type);
      Assertions.assertTrue(p.knownSegment);
      Assertions.assertEquals(new Coord2d(55.0, 77.0), p.world());
   }

   @Test
   void resolveReturnsImmutableSnapshot_notAffectedByLaterMarkerMutation() {
      MapFile f = newFile();
      add(f, pm(f, -6148914691236517206L, 5, 7, "Home"));
      Place p = new NamedPlaceResolver(f).resolve("Home").place;
      f.lock.writeLock().lock();

      try {
         ((Marker)f.markers.iterator().next()).tc = new Coord(99, 99);
      } finally {
         f.lock.writeLock().unlock();
      }

      Assertions.assertEquals(new Coord(5, 7), p.tc, "returned place must be a snapshot, not a live marker");
   }

   @Test
   void duplicateNames_ambiguous_neverSilentlyPicks() {
      MapFile f = newFile();
      add(f, pm(f, -6148914691236517206L, 5, 7, "Home"));
      add(f, pm(f, -6148914691236517206L, 90, 12, "Home"));
      Result r = new NamedPlaceResolver(f).resolve("Home");
      Assertions.assertTrue(r.isAmbiguous());
      Assertions.assertEquals(Kind.AMBIGUOUS, r.kind);
      Assertions.assertNull(r.place);
      Assertions.assertNotNull(r.candidates);
      Assertions.assertEquals(2, r.candidates.size());
      Assertions.assertEquals(-6148914691236517206L, ((Place)r.candidates.get(0)).seg);
      Assertions.assertEquals(-6148914691236517206L, ((Place)r.candidates.get(1)).seg);
      Assertions.assertEquals(new Coord(5, 7), ((Place)r.candidates.get(0)).tc);
      Assertions.assertEquals(new Coord(90, 12), ((Place)r.candidates.get(1)).tc);
   }

   @Test
   void duplicateNamesEvenAtSameTile_ambiguous() {
      MapFile f = newFile();
      add(f, pm(f, -6148914691236517206L, 5, 7, "Home"));
      add(f, sm(f, -6148914691236517206L, 5, 7, "Home"));
      Assertions.assertTrue(new NamedPlaceResolver(f).resolve("Home").isAmbiguous());
   }

   @Test
   void ambiguousCandidates_unmodifiable() {
      MapFile f = newFile();
      add(f, pm(f, -6148914691236517206L, 5, 7, "Home"));
      add(f, pm(f, -6148914691236517206L, 9, 9, "Home"));
      List<Place> cands = new NamedPlaceResolver(f).resolve("Home").candidates;
      Assertions.assertThrows(UnsupportedOperationException.class, () -> cands.add(cands.get(0)));
   }

   @Test
   void duplicateNamesAcrossSegments_ambiguousWithSegmentContext() {
      MapFile f = newFile();
      add(f, pm(f, -6148914691236517206L, 5, 7, "Alpha"));
      add(f, sm(f, -4919131752989213765L, 30, 4, "Alpha"));
      Result r = new NamedPlaceResolver(f).resolve("Alpha");
      Assertions.assertTrue(r.isAmbiguous());
      Assertions.assertEquals(2, r.candidates.size());
      Assertions.assertEquals(-6148914691236517206L, ((Place)r.candidates.get(0)).seg);
      Assertions.assertEquals(-4919131752989213765L, ((Place)r.candidates.get(1)).seg);
      Assertions.assertEquals(new Coord(5, 7), ((Place)r.candidates.get(0)).tc);
      Assertions.assertEquals(new Coord(30, 4), ((Place)r.candidates.get(1)).tc);
   }

   @Test
   void foundPlace_reportsSegmentKnownness() {
      MapFile f = newFile();
      f.lock.writeLock().lock();

      try {
         f.markers.add(pm(f, -6148914691236517206L, 1, 1, "Known"));
         f.markers.add(pm(f, -4919131752989213765L, 2, 2, "Stale"));
         f.knownsegs.add(-6148914691236517206L);
         f.markerseq++;
      } finally {
         f.lock.writeLock().unlock();
      }

      NamedPlaceResolver resolver = new NamedPlaceResolver(f);
      Assertions.assertTrue(resolver.resolve("Known").place.knownSegment);
      Assertions.assertFalse(resolver.resolve("Stale").place.knownSegment, "a marker whose segment is absent from the index must be flagged");
   }

   @Test
   void missingName_returnsMissing() {
      MapFile f = newFile();
      add(f, pm(f, -6148914691236517206L, 5, 7, "Home"));
      Result r = new NamedPlaceResolver(f).resolve("Nowhere");
      Assertions.assertTrue(r.isMissing());
      Assertions.assertEquals(Kind.MISSING, r.kind);
      Assertions.assertNull(r.place);
      Assertions.assertNull(r.candidates);
   }

   @Test
   void nullAndEmptyNames_alwaysMissing() {
      MapFile f = newFile();
      add(f, pm(f, -6148914691236517206L, 5, 7, "Home"));
      add(f, pm(f, -6148914691236517206L, 6, 7, ""));
      NamedPlaceResolver resolver = new NamedPlaceResolver(f);
      Assertions.assertTrue(resolver.resolve(null).isMissing());
      Assertions.assertTrue(resolver.resolve("").isMissing());
      Assertions.assertEquals(2, f.markers.size());
   }

   @Test
   void lookupIsExactCaseSensitiveNoNormalization() {
      MapFile f = newFile();
      add(f, pm(f, -6148914691236517206L, 5, 7, "Home"));
      NamedPlaceResolver resolver = new NamedPlaceResolver(f);
      Assertions.assertTrue(resolver.resolve("Home").isFound());
      Assertions.assertTrue(resolver.resolve("home").isMissing());
      Assertions.assertTrue(resolver.resolve(" Home").isMissing());
      Assertions.assertTrue(resolver.resolve("Hom").isMissing());
      Assertions.assertTrue(resolver.resolve("Home ").isMissing());
   }

   @Test
   void allPersistedMarkerTypesResolveByExactName() {
      MapFile f = newFile();
      add(f, pm(f, -6148914691236517206L, 1, 1, "PlacedFlag"));
      add(f, sm(f, -6148914691236517206L, 2, 2, "ServerMarker"));
      add(f, cm(f, -6148914691236517206L, 3, 3, "CustomIcon"));
      add(f, om(f, -6148914691236517206L, 4, 4, "OtherMarker"));
      NamedPlaceResolver resolver = new NamedPlaceResolver(f);
      Place placed = resolver.resolve("PlacedFlag").place;
      Assertions.assertNotNull(placed);
      Assertions.assertEquals(MarkerType.PLACED, placed.type);
      Assertions.assertEquals(new Coord(1, 1), placed.tc);
      Place server = resolver.resolve("ServerMarker").place;
      Assertions.assertEquals(MarkerType.SERVER, server.type);
      Assertions.assertEquals(new Coord(2, 2), server.tc);
      Place custom = resolver.resolve("CustomIcon").place;
      Assertions.assertEquals(MarkerType.CUSTOM, custom.type);
      Assertions.assertEquals(new Coord(3, 3), custom.tc);
      Place other = resolver.resolve("OtherMarker").place;
      Assertions.assertEquals(MarkerType.OTHER, other.type);
      Assertions.assertEquals(new Coord(4, 4), other.tc);
   }

   @Test
   void resolvesMarkersAddedThroughPublicApi() {
      MapFile f = newFile();
      NamedPlaceResolver resolver = new NamedPlaceResolver(f);
      long before = resolver.resolve("Anything").markerseq;
      f.add(sm(f, -6148914691236517206L, 8, 9, "QuestGiver"));
      Result r = resolver.resolve("QuestGiver");
      Assertions.assertTrue(r.isFound());
      Assertions.assertEquals(-6148914691236517206L, r.place.seg);
      Assertions.assertEquals(new Coord(8, 9), r.place.tc);
      Assertions.assertTrue(r.markerseq > before, "markerseq must advance when the marker set changes");
   }

   @Test
   void resolveSafeFromWriteLockedThread() {
      MapFile f = newFile();
      f.lock.writeLock().lock();

      try {
         f.markers.add(pm(f, -6148914691236517206L, 5, 7, "Home"));
         f.markerseq++;
         Result r = new NamedPlaceResolver(f).resolve("Home");
         Assertions.assertTrue(r.isFound());
         Assertions.assertEquals(new Coord(5, 7), r.place.tc);
      } finally {
         f.lock.writeLock().unlock();
      }
   }

   private static final class MemCache implements ResCache {
      public OutputStream store(String name) {
         return new ByteArrayOutputStream();
      }

      public InputStream fetch(String name) throws IOException {
         throw new FileNotFoundException(name);
      }
   }
}
