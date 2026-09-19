package haven.pathfinding;

import haven.Coord2d;
import haven.Coord3f;
import haven.Following;
import haven.Gob;
import haven.Hitbox;
import haven.Loading;
import haven.Moving;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Live navigation facts for the vehicle driven by the player. */
public final class BoatNavigation {
   private BoatNavigation() {
   }

   public static Gob drivenVehicle(Gob player) {
      if (player == null || player.vehicleId() == 0L) return null;
      Moving moving = player.getattr(Moving.class);
      if (moving instanceof Following) {
         Gob target = ((Following)moving).tgt();
         if (target != null && target.id == player.vehicleId()) return target;
      }
      try {
         return player.glob == null ? null : player.glob.oc.getgob(player.vehicleId());
      } catch (RuntimeException ignored) {
         return null;
      }
   }

   public static Gob movementSubject(Gob player) {
      Gob vehicle = drivenVehicle(player);
      return vehicle == null ? player : vehicle;
   }

   public static Coord2d position(Gob player) {
      Gob subject = movementSubject(player);
      if (subject == null) return null;
      try {
         // Use the live movement coordinate, but exclude purely visual draw
         // offsets such as bobbing that must not look like navigation motion.
         Moving movement = subject.getattr(Moving.class);
         Coord3f c = movement == null ? subject.getrc() : movement.getc();
         if (c != null) return Coord2d.of(c.x, c.y);
      } catch (RuntimeException ignored) {
      }
      return subject.rc;
   }

   public static boolean moving(Gob player) {
      Gob vehicle = drivenVehicle(player);
      boolean playerMoving = player != null && player.getattr(Moving.class) != null;
      boolean vehicleMoving = vehicle != null && vehicle.getattr(Moving.class) != null;
      return selectMoving(vehicle != null, playerMoving, vehicleMoving);
   }

   static boolean selectMoving(boolean aboard, boolean playerMoving, boolean vehicleMoving) {
      return aboard ? vehicleMoving : playerMoving;
   }

   public static double heading(Gob player) {
      Gob subject = movementSubject(player);
      return subject == null ? 0.0 : subject.a;
   }

   public static String resid(Gob player) {
      Gob vehicle = drivenVehicle(player);
      if (vehicle == null) return null;
      try {
         return vehicle.resid();
      } catch (Loading ignored) {
         return null;
      }
   }

   /** Half-length and half-width in the vehicle's local coordinates. */
   public static Coord2d halfExtents(Gob player) {
      Gob vehicle = drivenVehicle(player);
      if (vehicle == null) return null;
      Coord2d known = NurglingFallbacks.half(resid(player));
      if (known != null) return known;
      double hx = 0.0;
      double hy = 0.0;
      // Hitbox.worldPolygons is anchored at Gob.rc even while LinMove draws
      // the boat between server updates. Measure the shape from that same
      // anchor so movement distance cannot accidentally inflate the hull.
      Coord2d origin = vehicle.rc;
      if (origin != null) {
         try {
            double cs = Math.cos(vehicle.a), sn = Math.sin(vehicle.a);
            for (Coord2d[] poly : Hitbox.worldPolygons(vehicle)) {
               if (poly == null) continue;
               for (Coord2d point : poly) {
                  if (point == null) continue;
                  double dx = point.x - origin.x, dy = point.y - origin.y;
                  double lx = dx * cs + dy * sn;
                  double ly = -dx * sn + dy * cs;
                  hx = Math.max(hx, Math.abs(lx));
                  hy = Math.max(hy, Math.abs(ly));
               }
            }
         } catch (Loading ignored) {
         }
      }
      return hx > 0.5 && hy > 0.5 ? Coord2d.of(hx, hy) : null;
   }

   public static double turnRadius(Gob player) {
      Coord2d half = halfExtents(player);
      return safeRadius(half, 11.0);
   }

   /** Clearance while the boat is travelling bow-first. */
   public static double travelRadius(Gob player) {
      Coord2d half = halfExtents(player);
      return half == null ? 11.0 : Math.max(6.0, half.y + 2.0);
   }

   static double safeRadius(Coord2d half, double fallback) {
      if (half == null) return fallback;
      return Math.max(fallback, Math.hypot(half.x, half.y) + 1.0);
   }

   /** Vehicle body relative to the navigation position, for occupancy inflation. */
   public static List<Coord2d[]> bodyOrigin(Gob player) {
      Gob subject = movementSubject(player);
      Coord2d origin = subject == null ? null : subject.rc;
      if (subject == null || origin == null) return Collections.emptyList();
      Coord2d[] catalog = NurglingFallbacks.polygon(resid(player), origin, subject.a);
      if (catalog != null && catalog.length >= 3) {
         return relativeTo(catalog, origin);
      }
      List<Coord2d[]> world = Collections.emptyList();
      try {
         world = Hitbox.worldPolygons(subject);
      } catch (Loading ignored) {
      }
      if ((world == null || world.isEmpty()) && subject != player) {
         Coord2d[] fallback = NurglingFallbacks.polygon(resid(player), origin, subject.a);
         if (fallback != null) world = Collections.singletonList(fallback);
      }
      if (world == null || world.isEmpty()) return Collections.emptyList();
      List<Coord2d[]> out = new ArrayList<>();
      for (Coord2d[] poly : world) {
         if (poly == null || poly.length < 2) continue;
         out.addAll(relativeTo(poly, origin));
      }
      return out;
   }

   private static List<Coord2d[]> relativeTo(Coord2d[] polygon, Coord2d origin) {
      if (polygon == null || polygon.length < 2 || origin == null) return Collections.emptyList();
      Coord2d[] relative = new Coord2d[polygon.length];
      for (int i = 0; i < polygon.length; i++) {
         relative[i] = polygon[i] == null ? Coord2d.of(0.0, 0.0) : polygon[i].sub(origin);
      }
      return Collections.singletonList(relative);
   }
}
