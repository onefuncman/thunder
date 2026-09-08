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

final class NavigationTestSpotScenario implements PfTestRunner.Scenario {
   static final int SELECT_BOUNDS_HALF = 32;
   static final double SELECT_MIN_TILES = 12.0;
   static final double SELECT_MAX_TILES = 24.0;
   static final int SELECT_MAX_EXPANDED = 40000;
   static final int SELECT_MAX_TOTAL_EXPANDED = 2000000;
   private final NavigationTestSpotSelector.Profile profile;

   NavigationTestSpotScenario(NavigationTestSpotSelector.Profile profile) {
      this.profile = profile;
   }

   @Override
   public String name() {
      switch (this.profile) {
         case OPEN_GROUND:
            return "select_open_ground";
         case LOCAL_OBSTACLE_OR_CORRIDOR:
            return "select_obstacle_corridor";
         case KNOWN_MAP_LONG_LEG:
            return "select_known_long_leg";
         default:
            throw new AssertionError(this.profile);
      }
   }

   @Override
   public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
      if (run.cancelled) {
         throw new PfTestRunner.Cancelled();
      } else {
         List<JSONObject> checks = new ArrayList<>();
         GameUI gui = ui == null ? null : ui.gui;
         if (gui != null && gui.map != null) {
            checks.add(PfTestRunner.check("in_game", true, "in game"));
            if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            } else {
               return this.profile == NavigationTestSpotSelector.Profile.KNOWN_MAP_LONG_LEG
                  ? this.executeLongLeg(run, ui, gui, checks)
                  : this.executeLocal(run, ui, gui, checks);
            }
         } else {
            checks.add(PfTestRunner.check("in_game", false, "client is not in game (" + this.name() + " requires in-game state)"));
            return body(
               checks, refusalFacts(this.profile, "NO_GAME", "client is not in game; selection not attempted"), "not in game: selection not attempted"
            );
         }
      }
   }

   private JSONObject executeLocal(PfTestRunner.Run run, UI ui, GameUI gui, List<JSONObject> checks) throws Exception {
      PrototypePathfinder.Scene scene;
      synchronized (ui) {
         scene = PrototypePathfinder.observe(gui);
      }

      NavigationTestSpotSelector.Selection sel = this.profile == NavigationTestSpotSelector.Profile.OPEN_GROUND
         ? NavigationTestSpotSelector.openGround(scene)
         : NavigationTestSpotSelector.localObstacleOrCorridor(scene);
      if (run.cancelled) {
         throw new PfTestRunner.Cancelled();
      } else {
         checks.add(selectionCheck(sel));
         return body(checks, factsJson(this.profile, sel, reportTile(sel, null), null), sel.refused() ? "no spot selected: " + sel.refusal : null);
      }
   }

   private JSONObject executeLongLeg(PfTestRunner.Run run, UI ui, GameUI gui, List<JSONObject> checks) throws Exception {
      MapFile file;
      synchronized (ui) {
         file = gui.mapfile == null ? null : gui.mapfile.file;
      }

      checks.add(
         PfTestRunner.check(
            "mapfile_available",
            file != null,
            file != null ? "map file present (persisted coarse tiles)" : "no map file in game state (select_known_long_leg needs persisted coarse tiles)"
         )
      );
      if (file == null) {
         return body(
            checks, refusalFacts(this.profile, "NO_SOURCE", "no map file in game state; selection not attempted"), "no map file: selection not attempted"
         );
      } else {
         NamedPlaceNavigator.Location loc;
         synchronized (ui) {
            loc = NamedPlaceNavigator.liveState(gui).current();
         }

         if (loc == null) {
            checks.add(PfTestRunner.check("session_state", false, "session location unavailable (no map file view/segment/player)"));
            return body(
               checks,
               refusalFacts(this.profile, "NO_SOURCE", "session location unavailable; selection not attempted"),
               "session location unavailable: selection not attempted"
            );
         } else {
            checks.add(PfTestRunner.check("session_state", true, String.format("segment %x, start tile %s", loc.seg, loc.tile)));
            if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            } else {
               Area bounds = selectBounds(loc.tile);
               MapFileTileSource src = MapFileTileSource.of(file, loc.seg, bounds);
               NavigationTestSpotSelector.Selection sel = NavigationTestSpotSelector.knownMapLongLeg(
                  src, loc.seg, loc.tile.sub(bounds.ul), 12.0, 24.0, 40000, 2000000
               );
               if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  checks.add(selectionCheck(sel));
                  return body(
                     checks,
                     factsJson(this.profile, sel, reportTile(sel, bounds), sel.selected() ? loc.seg : null),
                     sel.refused() ? "no spot selected: " + sel.refusal : null
                  );
               }
            }
         }
      }
   }

   static JSONObject selectionCheck(NavigationTestSpotSelector.Selection sel) {
      return sel == null
         ? PfTestRunner.check("selection_completed", false, "selection not attempted")
         : PfTestRunner.check("selection_completed", sel.selected(), sel.evidence);
   }

   static Area selectBounds(Coord startTile) {
      if (startTile == null) {
         throw new IllegalArgumentException("startTile must not be null");
      } else {
         return Area.corn(startTile.sub(32, 32), startTile.add(32, 32));
      }
   }

   static Coord reportTile(NavigationTestSpotSelector.Selection sel, Area bounds) {
      if (sel != null && sel.targetTile != null) {
         return bounds == null ? sel.targetTile : bounds.ul.add(sel.targetTile);
      } else {
         return null;
      }
   }

   static JSONObject refusalFacts(NavigationTestSpotSelector.Profile profile, String refusal, String reason) {
      JSONObject f = new JSONObject();
      f.put("profile", profile.name());
      f.put("selected", false);
      f.put("refusal", refusal);
      f.put("reason", reason);
      f.put("candidates", 0);
      return f;
   }

   static JSONObject factsJson(NavigationTestSpotSelector.Profile profile, NavigationTestSpotSelector.Selection sel, Coord reportTile, Long segment) {
      if (sel == null) {
         return refusalFacts(profile, "NO_GAME", "selection not attempted (no in-game state)");
      } else {
         JSONObject f = new JSONObject();
         f.put("profile", profile.name());
         if (sel.refused()) {
            f.put("selected", false);
            f.put("refusal", sel.refusal.name());
            f.put("reason", sel.evidence);
            f.put("candidates", sel.candidates.size());
            return f;
         } else {
            f.put("selected", true);
            f.put("refusal", JSONObject.NULL);
            f.put("reason", sel.evidence);
            f.put("candidates", sel.candidates.size());
            if (!sel.candidates.isEmpty()) {
               f.put("best_score", PfTestRunner.round2(sel.candidates.get(0).score));
            }

            if (reportTile != null) {
               f.put("target_tile", new JSONArray().put(reportTile.x).put(reportTile.y));
            }

            if (sel.targetWorld != null) {
               f.put("target_world", new JSONArray().put(PfTestRunner.round2(sel.targetWorld.x)).put(PfTestRunner.round2(sel.targetWorld.y)));
            }

            if (segment != null) {
               f.put("segment", String.format("%x", segment));
            }

            return f;
         }
      }
   }

   private static JSONObject body(List<JSONObject> checks, JSONObject facts, String note) {
      JSONObject o = new JSONObject();
      o.put("verdict", PfTestRunner.verdictOf(checks));
      JSONArray arr = new JSONArray();

      for (JSONObject c : checks) {
         arr.put(c);
      }

      o.put("checks", arr);
      if (facts != null) {
         o.put("facts", facts);
      }

      if (note != null && !note.isEmpty()) {
         o.put("note", note);
      }

      return o;
   }
}
