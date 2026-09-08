package haven.pathfinding;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.GOut;
import haven.MapView;
import haven.dev.DebugDraw;
import java.awt.Color;
import java.util.List;

/** Draws the in-game critical-route draft on the map. */
public final class CriticalRouteOverlay implements DebugDraw {
   private static final Color LINE = new Color(255, 210, 60, 220);
   private static final Color DOT = new Color(255, 255, 120, 240);
   private static final Color TEXT = new Color(255, 245, 180);

   static {
      DebugDraw.Registry.register(new CriticalRouteOverlay());
   }

   @Override
   public CFG<Boolean> toggle() {
      return CFG.PF_ROUTE_OVERLAY;
   }

   @Override
   public void paint(GOut g, MapView mv) {
      CriticalRouteBook book = CriticalRouteWnd.draft();
      if (mv == null || book == null || book.legs.isEmpty()) {
         return;
      }
      String live = CriticalRouteBook.segmentId(mv.ui == null ? null : mv.ui.gui);
      List<CriticalRouteBook.Leg> legs = book.legs;
      Coord prev = null;
      for (int i = 0; i < legs.size(); i++) {
         CriticalRouteBook.Leg leg = legs.get(i);
         if (!leg.onFloor(live)) {
            prev = null;
            continue;
         }
         Coord2d w = BuildingDoor.markerWorld(mv.ui == null ? null : mv.ui.gui, leg, CriticalRouteBook.sessionWorld(mv.ui == null ? null : mv.ui.gui, leg));
         Coord c = PathfinderDebug.screen(mv, w);
         if (c == null) {
            prev = null;
            continue;
         }
         boolean object = "gob".equals(leg.kind) || CriticalRouteBook.isApproach(leg);
         g.chcolor(DOT);
         if (object) {
            g.frect(c.add(-5, -5), Coord.of(10, 10));
         } else {
            g.fellipse(c, Coord.of(5, 5));
         }
         if (prev != null && !object) {
            g.chcolor(LINE);
            g.line(prev, c, 2);
         }
         g.chcolor(TEXT);
         g.atext(Integer.toString(i + 1), c.add(7, -4), 0, 0);
         if (!object) {
            prev = c;
         }
      }
      g.chcolor();
   }
}
