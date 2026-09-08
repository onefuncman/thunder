package haven.pathfinding;

import auto.Bot;
import auto.Bot.BotAction;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.HackThread;
import haven.MapFile;
import haven.Moving;
import haven.UI;
import haven.Utils;
import haven.NamedPlaceResolver.Place;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

final class MineholeDescent {
   static final double MIN_DESCENT_DISP_U = 704.0;

   static MineholeDescent.Completion completion(MineholeDescent.Observation before, MineholeDescent.Observation after) {
      if (before == null || after == null || before.world == null || after.world == null) {
         return MineholeDescent.Completion.LOCATION_UNAVAILABLE;
      } else if (!after.idle) {
         return MineholeDescent.Completion.STILL_MOVING;
      } else if (!after.bodyFree) {
         return MineholeDescent.Completion.STUCK_IN_SOLID;
      } else if (before.segment == after.segment) {
         return MineholeDescent.Completion.SAME_SEGMENT;
      } else {
         return before.world.dist(after.world) < 704.0
            ? MineholeDescent.Completion.INSUFFICIENT_DISPLACEMENT
            : MineholeDescent.Completion.DESCENDED;
      }
   }

   static enum Completion {
      DESCENDED,
      LOCATION_UNAVAILABLE,
      STILL_MOVING,
      STUCK_IN_SOLID,
      SAME_SEGMENT,
      INSUFFICIENT_DISPLACEMENT;
   }

   static final class Observation {
      final long segment;
      final Coord2d world;
      final boolean idle;
      final boolean bodyFree;

      Observation(long segment, Coord2d world, boolean idle, boolean bodyFree) {
         this.segment = segment;
         this.world = world;
         this.idle = idle;
         this.bodyFree = bodyFree;
      }
   }
}
