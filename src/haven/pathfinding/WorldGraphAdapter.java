package haven.pathfinding;

import haven.Coord;
import haven.Following;
import haven.GameUI;
import haven.Gob;
import haven.MCache;
import haven.Moving;
import haven.nav.GraphNode;
import haven.nav.MobilityProfile;

/** GameUI/Gob → core graph node and mobility. No planner logic. */
public final class WorldGraphAdapter {
   private WorldGraphAdapter() {
   }

   public static GraphNode of(String worldId, long segmentId, String layerId, Coord tile) {
      return GraphNode.of(worldId, Long.toUnsignedString(segmentId, 16), layerId, areaId(tile));
   }

   public static String areaId(Coord tile) {
      if (tile == null) {
         return "unknown";
      }
      Coord g = tile.div(MCache.cmaps);
      return g.x + "," + g.y;
   }

   public static String worldId(GameUI gui) {
      try {
         if (gui != null && gui.ui != null && gui.ui.sess != null && gui.ui.sess.user != null && gui.ui.sess.user.name != null) {
            return gui.ui.sess.user.name;
         }
      } catch (RuntimeException ignored) {
      }
      return "haven";
   }

   public static String layerId(GameUI gui, Gob me) {
      if (gui == null || gui.mapfile == null || gui.mapfile.view == null || gui.mapfile.view.sessloc == null) {
         return "unknown";
      }
      return Long.toUnsignedString(gui.mapfile.view.sessloc.seg.id, 16);
   }

   public static GraphNode current(GameUI gui) {
      if (gui == null || gui.mapfile == null || gui.mapfile.view == null || gui.map == null) {
         return null;
      }
      haven.MiniMap.Location sessloc = gui.mapfile.view.sessloc;
      Gob me = gui.map.player();
      if (sessloc == null || me == null || me.rc == null) {
         return null;
      }
      Coord tile = NamedPlaceNavigator.playerTile(me.rc, sessloc.tc);
      return GraphNode.of(worldId(gui), Long.toUnsignedString(sessloc.seg.id, 16), layerId(gui, me), areaId(tile));
   }

   public static MobilityProfile mobility(Gob player) {
      if (player == null || player.vehicleId() == 0L) {
         return MobilityProfile.land();
      }
      Moving mv = player.getattr(Moving.class);
      if (mv instanceof Following) {
         Following f = (Following) mv;
         Gob tgt = f.tgt();
         String resid = tgt == null ? null : tgt.resid();
         if (TransitionAdapter.isCartResid(resid)) {
            return MobilityProfile.cart();
         }
      }
      return MobilityProfile.boat();
   }

   public static MobilityProfile mobility(long vehicleId, boolean cart) {
      if (vehicleId == 0L) {
         return MobilityProfile.land();
      }
      return cart ? MobilityProfile.cart() : MobilityProfile.boat();
   }
}
